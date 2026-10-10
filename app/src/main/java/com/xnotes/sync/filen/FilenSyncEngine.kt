package com.xnotes.sync.filen

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two-way reconciliation between the local note tree and a Filen folder.
 *
 * Change detection compares each side to the recorded baseline (see [FilenSyncState]).
 * Conflicts (both sides changed) are resolved losslessly: the remote copy is saved
 * beside the local one under a "(filen conflict ...)" name and the local file becomes
 * the canonical remote version.
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
class FilenSyncEngine(
    private val context: Context,
    private val treeUri: String,
    private val client: FilenClient,
    private val remoteRootUuid: String,
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

    private data class RemoteInfo(val file: FilenApi.RemoteFile, val meta: FilenClient.FileMetadata)

    /** Everything one walk of the remote tree found; [foreign] names folders holding things sync leaves alone. */
    private class RemoteTree {
        val files = HashMap<String, RemoteInfo>()
        val folders = HashMap<String, String>()
        val foreign = HashSet<String>()
    }

    /** Remote paths this run moved to the Filen trash, so a folder emptied by them counts as empty. */
    private val trashedRemote = HashSet<String>()

    fun sync(state: FilenSyncState): Summary {
        val summary = Summary()
        val localTree = FilenLocalStore.listTree(context, treeUri)
        val local = localTree.files.associateBy { it.relativePath }
        val remoteTree = RemoteTree()
        walkRemote(remoteRootUuid, "", remoteTree, HashSet())
        val remote = remoteTree.files

        // Guard: if one whole side comes back empty while the baseline is not, the enumeration
        // almost certainly failed transiently (root permission lost, folder unmounted, offline).
        // Propagating deletes then would wipe everything, so suppress them for this run.
        val hadBaseline = state.filePaths().any { !it.startsWith(".") || FilenLocalStore.isTrashPath(it) }
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
    private fun reconcileFolders(localFolders: Set<String>, remote: RemoteTree, state: FilenSyncState, summary: Summary, suppressDeletes: Boolean) {
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
                        key !in baseline -> if (up) state.putFolder(key, client.ensureFolderPath(remoteRootUuid, loc.split("/"))) else summary.skipped++
                        suppressDeletes -> summary.skipped++
                        // Gone remotely: drop it here if emptied, else it still holds something and goes back up.
                        down && FilenLocalStore.deleteDirIfEmpty(context, treeUri, loc) -> { state.removeFolder(key); summary.deleted++ }
                        up -> state.putFolder(key, client.ensureFolderPath(remoteRootUuid, loc.split("/")))
                        else -> summary.skipped++
                    }
                    rem != null -> {
                        val emptied = remote.files.keys.none { under(it, key) && it !in trashedRemote } &&
                            remote.foreign.none { under(it, key) } &&
                            remote.folders.keys.none { under(it, key) && it.lowercase() != key && it.lowercase() !in trashedFolders }
                        when {
                            key !in baseline -> if (down && FilenLocalStore.ensureDir(context, treeUri, rem) != null) state.putFolder(key, remote.folders.getValue(rem)) else summary.skipped++
                            suppressDeletes -> summary.skipped++
                            // Deleted here: trash it on Filen only when nothing of it is left there.
                            up && emptied -> { client.trashFolder(remote.folders.getValue(rem)); trashedFolders.add(key); state.removeFolder(key); summary.deleted++ }
                            down && FilenLocalStore.ensureDir(context, treeUri, rem) != null -> state.putFolder(key, remote.folders.getValue(rem))
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
        path: String, loc: FilenLocalStore.LocalEntry?, rem: RemoteInfo?,
        state: FilenSyncState, summary: Summary, suppressDeletes: Boolean,
    ) {
        // Dot-prefixed paths that are not notes (the .xnote-config settings baseline, colour
        // sidecars) are left alone; the .trash recycle bin, however, is a normal synced folder.
        if (path.startsWith(".") && !FilenLocalStore.isTrashPath(path)) return
        // Read-only files are held whole in memory to transfer, so very large ones are left out.
        if (!FilenLocalStore.isNote(path) && maxOf(loc?.size ?: 0L, rem?.meta?.size ?: 0L) > MAX_READER_BYTES) { summary.skipped++; return }
        val st = state.get(path)
        when {
            loc != null && rem != null -> {
                if (st == null) {
                    val bytes = FilenLocalStore.readBytes(context, loc.documentUri) ?: return
                    // Prefer the remote hash; when it's absent (older uploads / other clients), compare
                    // the actual bytes so byte-identical files don't spawn a spurious conflict copy.
                    val identical = if (rem.meta.hash != null) rem.meta.hash == FilenCrypto.sha512Hex(bytes)
                        else client.downloadFile(rem.file, rem.meta).contentEquals(bytes)
                    if (identical) {
                        state.put(path, FilenSyncState.Entry(rem.file.uuid, rem.meta.lastModified, loc.modified, loc.size))
                        summary.skipped++
                    } else {
                        resolveConflict(path, loc, rem, state, summary)
                    }
                } else {
                    val localChanged = loc.modified != st.localModified
                    val remoteChanged = rem.meta.lastModified != st.remoteLastModified
                    when {
                        localChanged && !remoteChanged -> if (up) doUpload(path, loc, rem.file.uuid, state, summary) else summary.skipped++
                        remoteChanged && !localChanged -> if (down) doDownload(path, rem, state, summary) else summary.skipped++
                        localChanged && remoteChanged -> resolveConflict(path, loc, rem, state, summary)
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
                        if (!FilenLocalStore.isTrashPath(path)) note(summary, path, "was deleted on another device, but you had edited it, so your copy was kept")
                    } else summary.skipped++
                }
                suppressDeletes -> summary.skipped++
                // Unchanged here and gone from the other device: applying that deletion locally is a
                // download-side change, so only when down is allowed.
                down -> if (FilenLocalStore.deleteLocal(context, treeUri, path)) { state.remove(path); summary.deleted++ } else summary.skipped++
                else -> summary.skipped++
            }
            rem != null && loc == null -> when {
                st == null -> if (down) doDownload(path, rem, state, summary) else summary.skipped++ // a note new to this device
                rem.meta.lastModified != st.remoteLastModified -> {
                    // Deleted here, but changed on another device: the edit wins (re-download) and we flag it.
                    if (down) {
                        doDownload(path, rem, state, summary)
                        if (!FilenLocalStore.isTrashPath(path)) note(summary, path, "you deleted it, but it was changed on another device, so it came back")
                    } else summary.skipped++
                }
                suppressDeletes -> summary.skipped++
                // Unchanged remotely and deleted here: trashing it on Filen is an upload-side change.
                up -> {
                    runCatching { client.trashFile(rem.file.uuid) }
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
    private fun resolveConflict(path: String, loc: FilenLocalStore.LocalEntry, rem: RemoteInfo, state: FilenSyncState, summary: Summary) {
        when {
            up && down -> doConflict(path, loc, rem, state, summary)
            up -> doUpload(path, loc, rem.file.uuid, state, summary)
            down -> doDownload(path, rem, state, summary)
            else -> summary.skipped++
        }
    }

    private fun note(summary: Summary, path: String, message: String) {
        summary.conflicts++
        summary.conflictNotes.add(ConflictNote(path, message))
    }

    private fun doUpload(path: String, loc: FilenLocalStore.LocalEntry, oldRemoteUuid: String?, state: FilenSyncState, summary: Summary) {
        val bytes = FilenLocalStore.readBytes(context, loc.documentUri) ?: throw IllegalStateException("cannot read local file")
        val newUuid = client.uploadFile(remoteParent(path), path.substringAfterLast('/'), bytes, loc.modified)
        oldRemoteUuid?.let { runCatching { client.trashFile(it) } }
        state.put(path, FilenSyncState.Entry(newUuid, loc.modified, loc.modified, loc.size))
        summary.uploaded++
    }

    private fun doDownload(path: String, rem: RemoteInfo, state: FilenSyncState, summary: Summary) {
        val bytes = client.downloadFile(rem.file, rem.meta)
        val uri = FilenLocalStore.writeNote(context, treeUri, path, bytes) ?: throw IllegalStateException("cannot write local file")
        val localMod = FilenLocalStore.modifiedOf(context, uri)
        state.put(path, FilenSyncState.Entry(rem.file.uuid, rem.meta.lastModified, localMod, bytes.size.toLong()))
        summary.downloaded++
    }

    private fun doConflict(path: String, loc: FilenLocalStore.LocalEntry, rem: RemoteInfo, state: FilenSyncState, summary: Summary) {
        val remoteBytes = client.downloadFile(rem.file, rem.meta)
        val copyPath = conflictPath(path)
        FilenLocalStore.writeNote(context, treeUri, copyPath, remoteBytes)
        val bytes = FilenLocalStore.readBytes(context, loc.documentUri) ?: throw IllegalStateException("cannot read local file")
        val newUuid = client.uploadFile(remoteParent(path), path.substringAfterLast('/'), bytes, loc.modified)
        runCatching { client.trashFile(rem.file.uuid) }
        state.put(path, FilenSyncState.Entry(newUuid, loc.modified, loc.modified, loc.size))
        note(summary, path, "was also changed on another device; that version is saved as \"${copyPath.substringAfterLast('/')}\"")
    }

    private fun remoteParent(path: String): String {
        val folders = path.split("/").dropLast(1)
        return if (folders.isEmpty()) remoteRootUuid else client.ensureFolderPath(remoteRootUuid, folders)
    }

    private fun conflictPath(path: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
        val dot = path.lastIndexOf('.')
        return if (dot <= 0) "$path (filen conflict $ts)"
        else path.substring(0, dot) + " (filen conflict $ts)" + path.substring(dot)
    }

    private fun walkRemote(uuid: String, prefix: String, out: RemoteTree, visited: HashSet<String>) {
        if (!visited.add(uuid)) return
        val content = client.listDecrypted(uuid)
        for ((name, file, meta) in content.files) {
            if (FilenLocalStore.isSynced(name)) out.files[join(prefix, name)] = RemoteInfo(file, meta) else out.foreign.add(prefix)
        }
        for ((name, folder) in content.folders) {
            // Descend into .trash (the synced recycle bin), skip other hidden folders (config, sidecars, .obsidian).
            if (name.startsWith(".") && name != FilenLocalStore.TRASH_DIR) { out.foreign.add(prefix); continue }
            val path = join(prefix, name)
            out.folders[path] = folder.uuid
            walkRemote(folder.uuid, path, out, visited)
        }
    }

    private companion object {
        const val MAX_READER_BYTES = 64L * 1024 * 1024
    }

    private fun join(prefix: String, name: String) = if (prefix.isEmpty()) name else "$prefix/$name"
}
