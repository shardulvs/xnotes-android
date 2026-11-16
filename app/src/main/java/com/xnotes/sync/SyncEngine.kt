package com.xnotes.sync

import android.content.Context
import com.xnotes.sync.SyncConflict.Reason
import com.xnotes.sync.merge.XnoteBundle
import java.io.File

/**
 * Two-way reconciliation between the local note tree and a folder on a [CloudService].
 *
 * Change detection compares each side to the recorded baseline (see [SyncState]).
 * Conflicts (both sides changed) are resolved losslessly. A note (.xnote) is merged page by page
 * when it can be (see [XnoteBundle]); changes on different pages merge cleanly with no copy. Where
 * both changed the same page, or nothing could be merged, the local version stays and the remote
 * one is saved beside it under a "(Sync Conflict ...)" name, with a [SyncConflict.Record] saying why.
 *
 * Deletions DO propagate: a note that was synced before and is now gone on one side is
 * removed on the other, unless that other side changed since the baseline (an edit always
 * beats a delete, and the mismatch is reported instead). The recycle bin (`.trash`) is a
 * normal synced folder, so a soft delete, a restore, and a permanent delete are all just
 * ordinary moves/deletes this same logic replicates. A run that finds one whole side empty
 * while the baseline is not is treated as a transient read failure and propagates no deletes.
 *
 * Folders are mirrored too, empty ones included, with their own baseline. A folder deleted on one
 * side is removed on the other only once nothing is left in it there (never unsynced files).
 */
class SyncEngine(
    private val context: Context,
    private val treeUri: String,
    private val storage: CloudStorage,
    private val remoteRootId: String,
    // Direction gates. [up] permits changes to the remote (uploads, remote deletes); [down]
    // permits changes to the local tree (downloads, local deletes). A conflict is resolved
    // toward whichever side is allowed; with both on it keeps the lossless conflict copy.
    private val up: Boolean = true,
    private val down: Boolean = true,
    /** Called after each file is reconciled with how many are done out of the total. */
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    /** One line per change made or problem met, for the sync log. */
    private val log: (String) -> Unit = {},
    /** The next just-closed note to sync ahead of everything else, as a path under the notes folder; null when none waits. */
    private val takePriority: () -> String? = { null },
    /** Called after a priority note is synced, to save the baseline at once. */
    private val checkpoint: () -> Unit = {},
) {
    /** A note whose two sides diverged: the edit was kept and the user should know. */
    data class ConflictNote(val path: String, val message: String)

    data class Summary(
        var uploaded: Int = 0,
        var downloaded: Int = 0,
        var conflicts: Int = 0,
        var deleted: Int = 0,
        var skipped: Int = 0,
        val errors: MutableList<String> = mutableListOf(),
        val conflictNotes: MutableList<ConflictNote> = mutableListOf(),
    )

    /** Everything one walk of the remote tree found; [foreign] names folders holding things sync leaves alone. */
    private class RemoteTree {
        val files = HashMap<String, CloudStorage.RemoteFile>()
        val folders = HashMap<String, String>()
        val foreign = HashSet<String>()
    }

    /** Remote paths this run moved to the cloud trash, so a folder emptied by them counts as empty. */
    private val trashedRemote = HashSet<String>()

    private val tmpDir = File(context.cacheDir, "sync").apply { mkdirs() }

    /** Paths a priority sync already settled; the full pass leaves them, since its listing may predate that. */
    private val handled = HashSet<String>()

    /** Every local file's document uri by relative path, kept current through the run (for settings sync's recents). */
    val localUris = HashMap<String, String>()

    /** The conflict copies this run kept, by copy path, written to [SyncConflict.RECORDS_PATH] once the files are done. */
    private val records = LinkedHashMap<String, SyncConflict.Record>()

    /** How the conflict records name this device, e.g. "Erik's Galaxy S23"; the model when the user gave it no name. */
    private val deviceName: String by lazy {
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    }

    fun sync(state: SyncState): Summary {
        val summary = Summary()
        tmpDir.listFiles()?.forEach { it.delete() } // left behind if a run was killed mid-transfer
        syncPriority(state, summary)
        var phase = System.currentTimeMillis()
        val localTree = LocalNoteStore.listTree(context, treeUri, recover = true)
        val local = localTree.files.associateBy { it.relativePath }
        localTree.files.associateTo(localUris) { it.relativePath to it.documentUri }
        log("Listed ${local.size} files here in ${secondsSince(phase)}")
        phase = System.currentTimeMillis()
        val remoteTree = RemoteTree()
        walkRemote(remoteRootId, "", remoteTree, HashSet())
        val remote = remoteTree.files
        log("Listed ${remote.size} files in ${remoteTree.folders.size + 1} cloud folders in ${secondsSince(phase)}")
        phase = System.currentTimeMillis()

        // Guard: if one whole side comes back empty while the baseline is not, the enumeration
        // almost certainly failed transiently (root permission lost, folder unmounted, offline).
        // Propagating deletes then would wipe everything, so suppress them for this run.
        val hadBaseline = state.filePaths().any { LocalNoteStore.syncsFile(it) }
        val suppressDeletes = hadBaseline && (local.isEmpty() || remote.isEmpty())

        val allPaths = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys); addAll(state.filePaths()) }
        // Counted like the sidebar's file count: hidden paths (settings, sidecars, Trash) are synced but not shown.
        val total = allPaths.count { !LocalNoteStore.isHiddenPath(it) }
        var done = 0
        for (path in allPaths) {
            syncPriority(state, summary) // a note closed while this runs goes next, not after the whole pass
            if (path !in handled) try {
                reconcile(path, local[path], remote[path], state, summary, suppressDeletes)
            } catch (e: Exception) {
                summary.errors.add("$path: ${e.message}")
                log("Error $path: ${e.message}")
            }
            if (!LocalNoteStore.isHiddenPath(path)) onProgress(++done, total)
        }
        log("Compared and transferred files in ${secondsSince(phase)}")
        saveRecords()
        phase = System.currentTimeMillis()
        reconcileFolders(localTree.folders, remoteTree, state, summary, suppressDeletes)
        log("Matched folders in ${secondsSince(phase)}")
        return summary
    }

    /** Syncs each just-closed note waiting, from a fresh look at it here and at its one cloud folder. */
    private fun syncPriority(state: SyncState, summary: Summary) {
        while (true) {
            val path = takePriority() ?: return
            val started = System.currentTimeMillis()
            try {
                val loc = LocalNoteStore.entryAt(context, treeUri, path) ?: continue
                val name = path.substringAfterLast('/')
                val rem = storage.list(remoteParent(path)).files.firstOrNull { it.name == name }
                // Deletes are never applied from a single folder's view; the full pass judges those.
                reconcile(path, loc, rem, state, summary, suppressDeletes = true)
                handled.add(path)
                localUris[path] = loc.documentUri
                log("Synced $path first in ${secondsSince(started)}")
                checkpoint()
            } catch (e: Exception) {
                summary.errors.add("$path: ${e.message}")
                log("Error $path: ${e.message}")
            }
        }
    }

    private fun secondsSince(ms: Long) = "%.1f s".format(java.util.Locale.US, (System.currentTimeMillis() - ms) / 1000.0)

    /**
     * Mirrors folders both ways, matched case-insensitively. Deepest first, so a parent is judged only after
     * its subfolders were settled: a deleted folder goes on the other side only when it is empty there.
     */
    private fun reconcileFolders(localFolders: Set<String>, remote: RemoteTree, state: SyncState, summary: Summary, suppressDeletes: Boolean) {
        val local = localFolders.associateBy { it.lowercase() }
        val remoteByKey = remote.folders.keys.associateBy { it.lowercase() }
        val baseline = state.folderPaths()
        val trashedFolders = HashSet<String>()
        fun under(path: String, key: String) = path.lowercase().let { it == key || it.startsWith("$key/") }
        val keys = (local.keys + remoteByKey.keys + baseline).sortedByDescending { it.count { c -> c == '/' } }
        for (key in keys) {
            val loc = local[key]
            val rem = remoteByKey[key]
            try {
                when {
                    loc != null && rem != null -> state.putFolder(key, remote.folders.getValue(rem))
                    loc != null -> when {
                        key !in baseline -> if (up) state.putFolder(key, storage.ensureFolderPath(remoteRootId, loc.split("/"))) else summary.skipped++
                        suppressDeletes -> summary.skipped++
                        // Gone remotely: drop it here if emptied, else it still holds something and goes back up.
                        down && LocalNoteStore.deleteDirIfEmpty(context, treeUri, loc) -> { state.removeFolder(key); summary.deleted++ }
                        up -> state.putFolder(key, storage.ensureFolderPath(remoteRootId, loc.split("/")))
                        else -> summary.skipped++
                    }
                    rem != null -> {
                        val emptied = remote.files.keys.none { under(it, key) && it !in trashedRemote } &&
                            remote.foreign.none { under(it, key) } &&
                            remote.folders.keys.none { under(it, key) && it.lowercase() != key && it.lowercase() !in trashedFolders }
                        when {
                            key !in baseline -> if (down && LocalNoteStore.ensureDir(context, treeUri, rem) != null) state.putFolder(key, remote.folders.getValue(rem)) else summary.skipped++
                            suppressDeletes -> summary.skipped++
                            // Deleted here: trash it remotely only when nothing of it is left there.
                            up && emptied -> { storage.trashFolder(remote.folders.getValue(rem)); trashedFolders.add(key); state.removeFolder(key); summary.deleted++ }
                            down && LocalNoteStore.ensureDir(context, treeUri, rem) != null -> state.putFolder(key, remote.folders.getValue(rem))
                            else -> summary.skipped++
                        }
                    }
                    else -> state.removeFolder(key)
                }
            } catch (e: Exception) {
                summary.errors.add("${loc ?: rem ?: key}/: ${e.message}")
                log("Error ${loc ?: rem ?: key}/: ${e.message}")
            }
        }
    }

    private fun reconcile(
        path: String, loc: LocalNoteStore.LocalEntry?, rem: CloudStorage.RemoteFile?,
        state: SyncState, summary: Summary, suppressDeletes: Boolean,
    ) {
        // Only baselines can bring other paths here, such as the .xnote-config settings one, which SettingsSync owns.
        if (!LocalNoteStore.syncsFile(path)) return
        val st = state.get(path)
        // An open note is never rewritten or deleted under its editor; it is picked up once it closes.
        val writesHere = loc != null && if (rem == null) st != null && loc.modified == st.localModified
            else st == null || rem.lastModified != st.remoteLastModified
        if (writesHere && OpenNotes.isOpen(loc!!.documentUri)) {
            log("Skipped $path: open in the editor")
            summary.skipped++
            return
        }
        when {
            loc != null && rem != null -> {
                if (st == null) {
                    val identical = matchesRemote(path, loc, rem, state) ?: return
                    if (identical) summary.skipped++ else resolveConflict(path, loc, rem, state, summary)
                } else {
                    val localChanged = loc.modified != st.localModified
                    val remoteChanged = rem.lastModified != st.remoteLastModified
                    when {
                        localChanged && !remoteChanged -> if (up) doUpload(path, loc, rem.id, state, summary) else summary.skipped++
                        remoteChanged && !localChanged -> if (down) doDownload(path, rem, state, summary) else summary.skipped++
                        // Both sides holding the same bytes is no conflict, e.g. a run killed after its upload but before its baseline was saved.
                        localChanged && remoteChanged -> {
                            val identical = matchesRemote(path, loc, rem, state) ?: return
                            if (identical) summary.skipped++ else resolveConflict(path, loc, rem, state, summary)
                        }
                        // A baseline from before fingerprints: the file still is what was synced, so fill them in now.
                        isNote(path) && st.pages == null -> backfillFingerprints(path, loc, st, state)
                        else -> Unit
                    }
                }
            }
            loc != null && rem == null -> when {
                st == null -> if (up) doUpload(path, loc, null, state, summary) else summary.skipped++ // a brand-new local note
                loc.modified != st.localModified -> {
                    // Edited here, but deleted on another device: the edit wins (re-upload) and we flag it.
                    if (up) {
                        doUpload(path, loc, null, state, summary)
                        if (!LocalNoteStore.isTrashPath(path)) note(summary, path, "was deleted on another device, but you had edited it, so your copy was kept")
                    } else summary.skipped++
                }
                suppressDeletes -> summary.skipped++
                // Unchanged here and gone from the other device: applying that deletion locally is a
                // download-side change, so only when down is allowed.
                down -> if (LocalNoteStore.deleteLocal(context, treeUri, path)) { state.remove(path); localUris.remove(path); summary.deleted++; log("Deleted here $path") } else summary.skipped++
                else -> summary.skipped++
            }
            rem != null && loc == null -> when {
                st == null -> if (down) doDownload(path, rem, state, summary) else summary.skipped++ // a note new to this device
                rem.lastModified != st.remoteLastModified -> {
                    // Deleted here, but changed on another device: the edit wins (re-download) and we flag it.
                    if (down) {
                        doDownload(path, rem, state, summary)
                        if (!LocalNoteStore.isTrashPath(path)) note(summary, path, "you deleted it, but it was changed on another device, so it came back")
                    } else summary.skipped++
                }
                suppressDeletes -> summary.skipped++
                // Unchanged remotely and deleted here: trashing it remotely is an upload-side change.
                up -> {
                    // Not caught: a failed trash (connection lost) keeps the baseline, so the next run trashes it again instead of downloading it back.
                    storage.trashFile(rem.id)
                    trashedRemote.add(path)
                    state.remove(path)
                    summary.deleted++
                    log("Deleted in cloud $path")
                }
                else -> summary.skipped++
            }
            else -> state.remove(path)
        }
    }

    /**
     * Whether the local file already holds the remote bytes; if so the baseline is set to both. Null when the
     * local file can't be read. Prefers the remote hash; when it's absent (older uploads, other clients) the bytes are compared.
     */
    private fun matchesRemote(path: String, loc: LocalNoteStore.LocalEntry, rem: CloudStorage.RemoteFile, state: SyncState): Boolean? = Temps(tmpDir).use { t ->
        val file = t.file()
        if (!LocalNoteStore.copyTo(context, loc.documentUri, file)) return null
        val same = if (rem.hash != null) rem.hash == storage.hash(file)
            else t.file().also { storage.download(rem, it) }.let { sameContent(file, it) }
        if (same) state.put(path, withFingerprints(path, file, SyncState.Entry(rem.id, rem.lastModified, loc.modified, loc.size)))
        same
    }

    /** Resolve a two-sided change toward the allowed direction; keep the lossless copy only when both are on. */
    private fun resolveConflict(path: String, loc: LocalNoteStore.LocalEntry, rem: CloudStorage.RemoteFile, state: SyncState, summary: Summary) {
        when {
            // Sidecar colours and pins are small JSON files: both versions are merged, no conflict copy.
            up && down && !LocalNoteStore.isSynced(path.substringAfterLast('/')) -> doSidecarMerge(path, loc, rem, state, summary)
            up && down -> doConflict(path, loc, rem, state, summary)
            up -> doUpload(path, loc, rem.id, state, summary)
            down -> doDownload(path, rem, state, summary)
            else -> summary.skipped++
        }
    }

    private fun note(summary: Summary, path: String, message: String) {
        summary.conflicts++
        summary.conflictNotes.add(ConflictNote(path, message))
        log("Conflict $path: $message")
    }

    private fun doUpload(path: String, loc: LocalNoteStore.LocalEntry, oldRemoteUuid: String?, state: SyncState, summary: Summary) = Temps(tmpDir).use { t ->
        val file = localCopy(loc, t)
        val sent = storage.upload(remoteParent(path), path.substringAfterLast('/'), file, loc.modified)
        oldRemoteUuid?.let { runCatching { storage.trashFile(it) } }
        state.put(path, withFingerprints(path, file, SyncState.Entry(sent.id, sent.lastModified, loc.modified, loc.size)))
        summary.uploaded++
        log("Uploaded $path")
    }

    private fun doDownload(path: String, rem: CloudStorage.RemoteFile, state: SyncState, summary: Summary) = Temps(tmpDir).use { t ->
        val file = t.file()
        storage.download(rem, file)
        val uri = LocalNoteStore.writeNote(context, treeUri, path, file)
        localUris[path] = uri
        val localMod = LocalNoteStore.modifiedOf(context, uri)
        state.put(path, withFingerprints(path, file, SyncState.Entry(rem.id, rem.lastModified, localMod, file.length())))
        summary.downloaded++
        log("Downloaded $path")
    }

    private fun doConflict(path: String, loc: LocalNoteStore.LocalEntry, rem: CloudStorage.RemoteFile, state: SyncState, summary: Summary) = Temps(tmpDir).use { t ->
        val remoteFile = t.file()
        storage.download(rem, remoteFile)
        val copyPath = SyncConflict.copyPath(path)
        val copyName = copyPath.substringAfterLast('/')
        val name = path.substringAfterLast('/')
        val localFile = localCopy(loc, t)
        val mergedFile = t.file()
        val base = state.get(path)
        val outcome = when {
            base == null -> XnoteBundle.Refused(Reason.BOTH_NEW)
            !isNote(path) -> XnoteBundle.Refused(Reason.NOT_A_NOTE)
            base.pages == null || base.doc == null -> XnoteBundle.Refused(Reason.NO_BASELINE)
            OpenNotes.isOpen(loc.documentUri) -> XnoteBundle.Refused(Reason.OPEN)
            else -> runCatching { XnoteBundle.merge(base.pages, base.doc, localFile, remoteFile, mergedFile) }
                .getOrElse { XnoteBundle.Refused(Reason.UNREADABLE) }
        }
        // Checked again right before writing: an edit saved since the listing, or a note opened meanwhile, must not be overwritten.
        val refused = when {
            outcome is XnoteBundle.Refused -> outcome.reason
            OpenNotes.isOpen(loc.documentUri) -> Reason.OPEN
            LocalNoteStore.modifiedOf(context, loc.documentUri) != loc.modified -> Reason.SAVED_DURING
            else -> null
        }
        if (refused != null) {
            LocalNoteStore.writeNote(context, treeUri, copyPath, remoteFile)
            val sent = storage.upload(remoteParent(path), name, localFile, loc.modified)
            runCatching { storage.trashFile(rem.id) }
            state.put(path, withFingerprints(path, localFile, SyncState.Entry(sent.id, sent.lastModified, loc.modified, loc.size)))
            records[copyPath] = SyncConflict.Record(path, System.currentTimeMillis(), deviceName, refused)
            val what = when (refused) {
                Reason.BOTH_NEW -> "was also created on another device, with different content"
                Reason.NOT_A_NOTE -> "was also changed on another device"
                else -> "was also changed on another device and could not be merged because ${refused.why.format("this device")}"
            }
            note(summary, path, "$what; that version is saved as \"$copyName\"")
            return@use
        }
        val merged = outcome as XnoteBundle.Merged
        val clashed = merged.clashedPages
        // The other version is kept here before the remote one is trashed; a failed upload after it only leaves a spare copy.
        if (clashed.isNotEmpty()) LocalNoteStore.writeNote(context, treeUri, copyPath, remoteFile)
        // Uploaded before the note is written here, so a failed upload leaves it as it was and the next run starts over.
        val sent = storage.upload(remoteParent(path), name, mergedFile, System.currentTimeMillis())
        runCatching { storage.trashFile(rem.id) }
        val uri = LocalNoteStore.writeNote(context, treeUri, path, mergedFile)
        localUris[path] = uri
        val localMod = LocalNoteStore.modifiedOf(context, uri)
        val f = merged.fingerprints
        state.put(path, SyncState.Entry(sent.id, sent.lastModified, localMod, mergedFile.length(), f.pages, f.doc))
        if (clashed.isEmpty()) {
            summary.uploaded++
            summary.downloaded++
            log("Merged $path: changed here and on another device, on different pages")
            return@use
        }
        records[copyPath] = SyncConflict.Record(path, System.currentTimeMillis(), deviceName, Reason.SAME_PAGES, clashed)
        val pages = if (clashed.size == 1) "page ${clashed[0]}" else "pages ${clashed.joinToString(", ")}"
        note(summary, path, "was also changed on another device, on $pages too; the rest was merged and the other version of $pages is in \"$copyName\"")
    }

    /** Adds this run's conflict records to the sidecar file, read as it is now, so the next run sends them up. */
    private fun saveRecords() {
        if (records.isEmpty()) return
        try {
            Temps(tmpDir).use { t ->
                val existing = localUris[SyncConflict.RECORDS_PATH]?.let { uri ->
                    t.file().takeIf { LocalNoteStore.copyTo(context, uri, it) }?.readText()
                }
                val out = t.file().apply { writeText(SyncConflict.withRecords(existing, records)) }
                val uri = LocalNoteStore.writeNote(context, treeUri, SyncConflict.RECORDS_PATH, out)
                localUris[SyncConflict.RECORDS_PATH] = uri
            }
        } catch (e: Exception) {
            log("Error ${SyncConflict.RECORDS_PATH}: ${e.message}")
        }
        records.clear()
    }

    private fun doSidecarMerge(path: String, loc: LocalNoteStore.LocalEntry, rem: CloudStorage.RemoteFile, state: SyncState, summary: Summary) = Temps(tmpDir).use { t ->
        val remoteFile = t.file().also { storage.download(rem, it) }
        val localFile = localCopy(loc, t)
        val merged = t.file().apply { writeText(SidecarMerge.merge(localFile.readText(), remoteFile.readText())) }
        // Uploaded first, as with a merged note, so a failed upload leaves this device as it was.
        val sent = storage.upload(remoteParent(path), path.substringAfterLast('/'), merged, System.currentTimeMillis())
        runCatching { storage.trashFile(rem.id) }
        val uri = LocalNoteStore.writeNote(context, treeUri, path, merged)
        state.put(path, SyncState.Entry(sent.id, sent.lastModified, LocalNoteStore.modifiedOf(context, uri), merged.length()))
        summary.uploaded++
        summary.downloaded++
        log("Merged $path")
    }

    /** Sync works on a private copy of the local file, so a save landing meanwhile cannot change what is sent. */
    private fun localCopy(loc: LocalNoteStore.LocalEntry, t: Temps): File =
        t.file().also { if (!LocalNoteStore.copyTo(context, loc.documentUri, it)) throw IllegalStateException("cannot read local file") }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x -> b.inputStream().buffered().use { y ->
            while (true) { val c = x.read(); if (c != y.read()) return false; if (c < 0) return true }
        } }
    }

    /** Temp files under [dir] that are all deleted on close. */
    private class Temps(private val dir: File) : java.io.Closeable {
        private val files = ArrayList<File>()
        fun file(): File = File.createTempFile("sync", ".tmp", dir).also { files.add(it) }
        override fun close() = files.forEach { it.delete() }
    }

    private fun isNote(path: String) = path.endsWith(".xnote", ignoreCase = true)

    /** Remembers a note's page fingerprints with its baseline, so the next conflict on it can be merged. */
    private fun withFingerprints(path: String, file: File, entry: SyncState.Entry): SyncState.Entry {
        if (!isNote(path)) return entry
        val f = runCatching { XnoteBundle.fingerprintsOf(file) }.getOrNull() ?: return entry
        return entry.copy(pages = f.pages, doc = f.doc)
    }

    private fun backfillFingerprints(path: String, loc: LocalNoteStore.LocalEntry, st: SyncState.Entry, state: SyncState) {
        // Only the zip directory and the manifest are read, straight from the folder, however big the note.
        // The stream is not closed: that would close the descriptor the provider owns, which use() closes once.
        val f = runCatching {
            context.contentResolver.openFileDescriptor(android.net.Uri.parse(loc.documentUri), "r")?.use { pfd ->
                XnoteBundle.fingerprintsOf(java.io.FileInputStream(pfd.fileDescriptor).channel)
            }
        }.getOrNull() ?: return
        state.put(path, st.copy(pages = f.pages, doc = f.doc))
    }

    private fun remoteParent(path: String): String {
        val folders = path.split("/").dropLast(1)
        return if (folders.isEmpty()) remoteRootId else storage.ensureFolderPath(remoteRootId, folders)
    }

    private fun walkRemote(uuid: String, prefix: String, out: RemoteTree, visited: HashSet<String>) {
        if (!visited.add(uuid)) return
        val content = storage.list(uuid)
        for (file in content.files) {
            val path = join(prefix, file.name)
            if (LocalNoteStore.syncsFile(path)) out.files[path] = file else out.foreign.add(prefix)
        }
        for (folder in content.folders) {
            val path = join(prefix, folder.name)
            // Sidecars and their Trash are descended into; other hidden folders (config, templates, .obsidian) are left alone.
            if (!LocalNoteStore.syncsDir(path)) { out.foreign.add(prefix); continue }
            out.folders[path] = folder.id
            walkRemote(folder.id, path, out, visited)
        }
    }

    private fun join(prefix: String, name: String) = if (prefix.isEmpty()) name else "$prefix/$name"
}
