package com.xnotes.sync

import android.content.Context
import com.xnotes.sync.merge.XnoteBundle
import java.io.File

/**
 * Two-way reconciliation between the local note tree and a folder on a [CloudService].
 *
 * Change detection compares each side to the recorded baseline (see [SyncState]).
 * Conflicts (both sides changed) are resolved losslessly: the remote copy is always saved
 * beside the local one under a "(Sync Conflict ...)" name. A note (.xnote) is then merged page
 * by page when it can be (see [XnoteBundle]); otherwise the local file becomes the canonical
 * remote version.
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

    fun sync(state: SyncState): Summary {
        val summary = Summary()
        tmpDir.listFiles()?.forEach { it.delete() } // left behind if a run was killed mid-transfer
        val localTree = LocalNoteStore.listTree(context, treeUri)
        val local = localTree.files.associateBy { it.relativePath }
        val remoteTree = RemoteTree()
        walkRemote(remoteRootId, "", remoteTree, HashSet())
        val remote = remoteTree.files

        // Guard: if one whole side comes back empty while the baseline is not, the enumeration
        // almost certainly failed transiently (root permission lost, folder unmounted, offline).
        // Propagating deletes then would wipe everything, so suppress them for this run.
        val hadBaseline = state.filePaths().any { !it.startsWith(".") || LocalNoteStore.isTrashPath(it) }
        val suppressDeletes = hadBaseline && (local.isEmpty() || remote.isEmpty())

        val allPaths = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys); addAll(state.filePaths()) }
        for (path in allPaths) {
            try {
                reconcile(path, local[path], remote[path], state, summary, suppressDeletes)
            } catch (e: Exception) {
                summary.errors.add("$path: ${e.message}")
            }
        }
        reconcileFolders(localTree.folders, remoteTree, state, summary, suppressDeletes)
        return summary
    }

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
            }
        }
    }

    private fun reconcile(
        path: String, loc: LocalNoteStore.LocalEntry?, rem: CloudStorage.RemoteFile?,
        state: SyncState, summary: Summary, suppressDeletes: Boolean,
    ) {
        // Dot-prefixed paths that are not notes (the .xnote-config settings baseline, colour
        // sidecars) are left alone; the .trash recycle bin, however, is a normal synced folder.
        if (path.startsWith(".") && !LocalNoteStore.isTrashPath(path)) return
        val st = state.get(path)
        when {
            loc != null && rem != null -> {
                if (st == null) {
                    val identical = Temps(tmpDir).use { t ->
                        val file = t.file()
                        if (!LocalNoteStore.copyTo(context, loc.documentUri, file)) return
                        // Prefer the remote hash; when it's absent (older uploads / other clients), compare
                        // the actual bytes so byte-identical files don't spawn a spurious conflict copy.
                        val same = if (rem.hash != null) rem.hash == storage.hash(file)
                            else t.file().also { storage.download(rem, it) }.let { sameContent(file, it) }
                        if (same) state.put(path, withFingerprints(path, file, SyncState.Entry(rem.id, rem.lastModified, loc.modified, loc.size)))
                        same
                    }
                    if (identical) summary.skipped++ else resolveConflict(path, loc, rem, state, summary)
                } else {
                    val localChanged = loc.modified != st.localModified
                    val remoteChanged = rem.lastModified != st.remoteLastModified
                    when {
                        localChanged && !remoteChanged -> if (up) doUpload(path, loc, rem.id, state, summary) else summary.skipped++
                        remoteChanged && !localChanged -> if (down) doDownload(path, rem, state, summary) else summary.skipped++
                        localChanged && remoteChanged -> resolveConflict(path, loc, rem, state, summary)
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
                down -> if (LocalNoteStore.deleteLocal(context, treeUri, path)) { state.remove(path); summary.deleted++ } else summary.skipped++
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
                    runCatching { storage.trashFile(rem.id) }
                    trashedRemote.add(path)
                    state.remove(path)
                    summary.deleted++
                }
                else -> summary.skipped++
            }
            else -> state.remove(path)
        }
    }

    /** Resolve a two-sided change toward the allowed direction; keep the lossless copy only when both are on. */
    private fun resolveConflict(path: String, loc: LocalNoteStore.LocalEntry, rem: CloudStorage.RemoteFile, state: SyncState, summary: Summary) {
        when {
            up && down -> doConflict(path, loc, rem, state, summary)
            up -> doUpload(path, loc, rem.id, state, summary)
            down -> doDownload(path, rem, state, summary)
            else -> summary.skipped++
        }
    }

    private fun note(summary: Summary, path: String, message: String) {
        summary.conflicts++
        summary.conflictNotes.add(ConflictNote(path, message))
    }

    private fun doUpload(path: String, loc: LocalNoteStore.LocalEntry, oldRemoteUuid: String?, state: SyncState, summary: Summary) = Temps(tmpDir).use { t ->
        val file = localCopy(loc, t)
        val sent = storage.upload(remoteParent(path), path.substringAfterLast('/'), file, loc.modified)
        oldRemoteUuid?.let { runCatching { storage.trashFile(it) } }
        state.put(path, withFingerprints(path, file, SyncState.Entry(sent.id, sent.lastModified, loc.modified, loc.size)))
        summary.uploaded++
    }

    private fun doDownload(path: String, rem: CloudStorage.RemoteFile, state: SyncState, summary: Summary) = Temps(tmpDir).use { t ->
        val file = t.file()
        storage.download(rem, file)
        val uri = LocalNoteStore.writeNote(context, treeUri, path, file) ?: throw IllegalStateException("cannot write local file")
        val localMod = LocalNoteStore.modifiedOf(context, uri)
        state.put(path, withFingerprints(path, file, SyncState.Entry(rem.id, rem.lastModified, localMod, file.length())))
        summary.downloaded++
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
        val merged = if (isNote(path) && base?.pages != null && base.doc != null && !OpenNotes.isOpen(loc.documentUri)) {
            runCatching { XnoteBundle.merge(base.pages, base.doc, localFile, remoteFile, mergedFile) }.getOrNull()
        } else null
        // Checked again right before writing: an edit saved since the listing, or a note opened meanwhile, must not be overwritten.
        if (merged == null || OpenNotes.isOpen(loc.documentUri) || LocalNoteStore.modifiedOf(context, loc.documentUri) != loc.modified) {
            LocalNoteStore.writeNote(context, treeUri, copyPath, remoteFile)
            val sent = storage.upload(remoteParent(path), name, localFile, loc.modified)
            runCatching { storage.trashFile(rem.id) }
            state.put(path, withFingerprints(path, localFile, SyncState.Entry(sent.id, sent.lastModified, loc.modified, loc.size)))
            note(summary, path, "was also changed on another device; that version is saved as \"$copyName\"")
            return@use
        }
        // Uploaded before anything is written here, so a failed upload leaves no trace and the next run starts over.
        val sent = storage.upload(remoteParent(path), name, mergedFile, System.currentTimeMillis())
        runCatching { storage.trashFile(rem.id) }
        val uri = LocalNoteStore.writeNote(context, treeUri, path, mergedFile) ?: throw IllegalStateException("cannot write local file")
        val localMod = LocalNoteStore.modifiedOf(context, uri)
        LocalNoteStore.writeNote(context, treeUri, copyPath, remoteFile)
        val f = merged.fingerprints
        state.put(path, SyncState.Entry(sent.id, sent.lastModified, localMod, mergedFile.length(), f.pages, f.doc))
        val both = merged.keptBothPages
        val bothText = when {
            both.isEmpty() -> ""
            both.size == 1 -> " Page ${both[0]} holds both devices' versions of the same part."
            else -> " Pages ${both.joinToString(", ")} hold both devices' versions of the same part."
        }
        note(summary, path, "was also changed on another device and the changes were merged.$bothText The other version is also saved as \"$copyName\"")
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
            if (LocalNoteStore.isSynced(file.name)) out.files[join(prefix, file.name)] = file else out.foreign.add(prefix)
        }
        for (folder in content.folders) {
            val name = folder.name
            // Descend into .trash (the synced recycle bin), skip other hidden folders (config, sidecars, .obsidian).
            if (name.startsWith(".") && name != LocalNoteStore.TRASH_DIR) { out.foreign.add(prefix); continue }
            val path = join(prefix, name)
            out.folders[path] = folder.id
            walkRemote(folder.id, path, out, visited)
        }
    }

    private fun join(prefix: String, name: String) = if (prefix.isEmpty()) name else "$prefix/$name"
}
