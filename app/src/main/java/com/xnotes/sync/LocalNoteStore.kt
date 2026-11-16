package com.xnotes.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xnotes.core.util.ReaderKind

/**
 * Reads and writes the note files under the explorer browse root (a SAF tree uri, which
 * also covers the app's internal DocumentsProvider). Mirrors the DocumentsContract calls
 * the Editor already uses, but without depending on it, so background sync can run.
 */
object LocalNoteStore {
    private val NOTE_EXTENSIONS = listOf(".xnote", ".xcanvas")
    private const val SIDECAR_DIR = ".xnote"
    /** In a folder's sidecar: its colours, and in the top one the colour names, pins and sync conflict records. */
    private val SIDECAR_FILES = setOf("colors.json", "color-names.json", "pins.json", "conflicts.json")
    /** The top folder's Trash inside its sidecar; each item sits in a folder of its own beside a [TRASH_INFO]. */
    private const val SIDECAR_TRASH = "trash"
    private const val TRASH_INFO = "trash.json"

    /** The hidden recycle-bin folder under the browse root. Synced like a normal folder (so a
     *  deletion reaches every device), but hidden from the note listing and presented as Trash. */
    const val TRASH_DIR = ".trash"

    fun isTrashPath(relativePath: String): Boolean = relativePath == TRASH_DIR || relativePath.startsWith("$TRASH_DIR/") ||
        relativePath.startsWith("$SIDECAR_DIR/$SIDECAR_TRASH/")

    /** Whether sync mirrors the folder at [relativePath]: no hidden folders but the sidecars, their Trash, and the old .trash. */
    fun syncsDir(relativePath: String): Boolean {
        val segs = relativePath.split("/")
        return segs.indices.all { i ->
            val seg = segs[i]
            when {
                !seg.startsWith(".") -> true
                seg == TRASH_DIR -> true
                // A folder's sidecar itself, or the top one's Trash; never the template library.
                seg == SIDECAR_DIR -> i == segs.lastIndex || i == 0 && segs[1] == SIDECAR_TRASH
                else -> false
            }
        }
    }

    /** Whether sync carries the file at [relativePath]: notes and readable files, and the sidecars' colours, pins and Trash. */
    fun syncsFile(relativePath: String): Boolean {
        val parent = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        if (parent.isNotEmpty() && !syncsDir(parent)) return false
        if (parent == SIDECAR_DIR || parent.endsWith("/$SIDECAR_DIR")) return name in SIDECAR_FILES
        if (name == TRASH_INFO) return relativePath.startsWith("$SIDECAR_DIR/$SIDECAR_TRASH/")
        return isSynced(name)
    }

    /** A path with a hidden segment (settings, sidecars, Trash): synced but not counted as a note. */
    fun isHiddenPath(relativePath: String): Boolean = relativePath.split("/").any { it.startsWith(".") }

    data class LocalEntry(val relativePath: String, val documentUri: String, val size: Long, val modified: Long)

    /** The synced files and every synced folder (relative paths), from one walk of the tree. */
    class LocalTree(val files: List<LocalEntry>, val folders: Set<String>)

    fun isNote(name: String) = NOTE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    /** Notes and canvases, plus the read-only kinds (Markdown, Office, CSV, PDF) the reader opens. */
    fun isSynced(name: String) = isNote(name) || ReaderKind.isReadable(name)

    fun listNotes(context: Context, treeUri: String): List<LocalEntry> = listTree(context, treeUri).files

    /** With [recover], swaps that [writeNote] left half done in a crash are finished first; only sync passes it. */
    fun listTree(context: Context, treeUri: String, recover: Boolean = false): LocalTree {
        val tree = Uri.parse(treeUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val out = ArrayList<LocalEntry>()
        val folders = HashSet<String>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<Pair<String, String>>().apply { addLast(rootId to "") }
        while (stack.isNotEmpty()) {
            val (docId, prefix) = stack.removeLast()
            if (!seen.add(docId)) continue
            val kids = children(context, tree, docId)
            for (child in if (recover && finishSwaps(context, tree, docId, kids)) children(context, tree, docId) else kids) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDir) {
                    if (!syncsDir(rel)) continue
                    folders.add(rel)
                    stack.addLast(child.docId to rel)
                } else if (syncsFile(rel)) {
                    out.add(LocalEntry(rel, child.uri, child.size, child.modified))
                }
            }
        }
        return LocalTree(out, folders)
    }

    /** Resolves (creating as needed) the folder at [relativePath]; returns its document id, or null. */
    fun ensureDir(context: Context, treeUri: String, relativePath: String): String? {
        val tree = Uri.parse(treeUri)
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        for (seg in relativePath.split("/")) {
            parentId = findChild(context, tree, parentId, seg, dir = true) ?: createDir(context, tree, parentId, seg) ?: return null
        }
        return parentId
    }

    /**
     * Deletes the folder at [relativePath] when nothing is left in it but the app's hidden colour sidecar.
     * True when the folder is gone afterwards; false when it still holds something (kept, never emptied here).
     */
    fun deleteDirIfEmpty(context: Context, treeUri: String, relativePath: String): Boolean {
        val tree = Uri.parse(treeUri)
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (seg in relativePath.split("/")) id = findChild(context, tree, id, seg, dir = true) ?: return true
        if (children(context, tree, id).any { !(it.isDir && it.name == SIDECAR_DIR) }) return false
        return runCatching { DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
            .getOrDefault(false)
    }

    /** Copy the document at [documentUri] into [to]. False when it cannot be read. */
    fun copyTo(context: Context, documentUri: String, to: java.io.File): Boolean = runCatching {
        context.contentResolver.openInputStream(Uri.parse(documentUri))?.use { input -> to.outputStream().use { input.copyTo(it) } } != null
    }.getOrDefault(false)

    fun modifiedOf(context: Context, documentUri: String): Long = runCatching {
        context.contentResolver.query(
            Uri.parse(documentUri), arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    /** Create or overwrite a note at [relativePath] (folders created as needed). Returns the document uri; throws saying which step failed. */
    fun writeNote(context: Context, treeUri: String, relativePath: String, from: java.io.File): String {
        val tree = Uri.parse(treeUri)
        val segments = relativePath.split("/")
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        for (i in 0 until segments.size - 1) {
            parentId = findChild(context, tree, parentId, segments[i], dir = true)
                ?: createDir(context, tree, parentId, segments[i]) ?: error("cannot create folder ${segments[i]}")
        }
        val fileName = segments.last()
        val resolver = context.contentResolver
        val doc = { id: String -> DocumentsContract.buildDocumentUriUsingTree(tree, id) }
        // The first failed step, so the sync log can say what went wrong.
        var why: String? = null
        fun write(uri: Uri): Boolean = try {
            (resolver.openOutputStream(uri, "wt")?.use { out -> from.inputStream().use { it.copyTo(out) } } != null)
                .also { if (!it) why = why ?: "write: no output stream" }
        } catch (e: Exception) { why = why ?: "write: ${e.message}"; false }
        fun move(uri: Uri, name: String): Uri? = try {
            DocumentsContract.renameDocument(resolver, uri, name).also { if (it == null) why = why ?: "rename to $name failed" }
        } catch (e: Exception) { why = why ?: "rename to $name: ${e.message}"; null }
        val existing = findChild(context, tree, parentId, fileName, dir = false)?.let(doc)
        val tempName = fileName + TEMP_SUFFIX
        val folderKey = "$treeUri|$parentId"
        val temp = if (folderKey in noSwap) null else {
            findChild(context, tree, parentId, tempName, dir = false)?.let { runCatching { DocumentsContract.deleteDocument(resolver, doc(it)) } }
            runCatching { DocumentsContract.createDocument(resolver, doc(parentId), "application/octet-stream", tempName) }.getOrNull()
        }
        // The swap needs rename, and ids that are paths so the note keeps its uri (open notes and recents hold it).
        if (temp == null || !supportsRename(context, temp) || DocumentsContract.getDocumentId(temp) != "$parentId/$tempName") {
            if (temp != null) noSwap.add(folderKey)
            temp?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            // shortcut: such providers are overwritten in place, so a crash mid-write can still truncate the note there.
            val uri = existing ?: try {
                DocumentsContract.createDocument(resolver, doc(parentId), "application/octet-stream", fileName)
            } catch (e: Exception) { error("cannot create $fileName: ${e.message}") } ?: error("cannot create $fileName")
            if (!write(uri)) error("cannot write local file: $why")
            return uri.toString()
        }
        val placed = if (!write(temp)) null else if (existing == null) {
            move(temp, fileName)
        } else {
            // The original is only moved aside, never deleted, until the new copy is in place.
            val backup = move(existing, fileName + BACKUP_SUFFIX)
            val swapped = backup?.let { move(temp, fileName) }
            if (backup != null) {
                if (swapped != null) runCatching { DocumentsContract.deleteDocument(resolver, backup) } else move(backup, fileName)
            }
            swapped
        }
        if (placed == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, temp) }
            error("cannot write local file: $why")
        }
        return placed.toString()
    }

    private const val TEMP_SUFFIX = ".synctmp"
    private const val BACKUP_SUFFIX = ".syncold"

    /** Folders found unable to swap, so they are not probed with a throwaway temp on every write. */
    private val noSwap: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun rename(context: Context, uri: Uri, name: String): Uri? =
        runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, name) }.getOrNull()

    private fun supportsRename(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
        }
    }.getOrNull() == true

    /** What finishing a crashed swap does with one leftover: rename it to [to], or delete it when [to] is null. */
    data class SwapFix(val name: String, val to: String?)

    /**
     * A leftover backup means the crash came mid-swap: the original comes back, so the next sync simply
     * downloads again. A temp is always dropped, as it may have been cut off mid-write.
     */
    fun swapFixes(names: List<String>): List<SwapFix> {
        val present = names.mapTo(HashSet()) { it.lowercase() }
        val fixes = ArrayList<SwapFix>()
        for (name in names) {
            when {
                name.endsWith(BACKUP_SUFFIX) -> fixes += SwapFix(name, name.removeSuffix(BACKUP_SUFFIX).takeIf { it.lowercase() !in present })
                name.endsWith(TEMP_SUFFIX) -> fixes += SwapFix(name, null)
            }
        }
        return fixes
    }

    /** Applies [swapFixes] in one folder; true when anything changed. Throws when a leftover cannot be made the file again. */
    private fun finishSwaps(context: Context, tree: Uri, folderId: String, kids: List<Child>): Boolean {
        val resolver = context.contentResolver
        val fixes = swapFixes(kids.filter { !it.isDir }.map { it.name })
        for (fix in fixes) {
            val uri = Uri.parse(kids.first { it.name == fix.name }.uri)
            if (fix.to == null || rename(context, uri, fix.to) != null) {
                if (fix.to == null) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                continue
            }
            // No rename here: the content is copied to the right name instead, never dropped.
            val target = runCatching {
                DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, folderId), "application/octet-stream", fix.to)
            }.getOrNull()
            val copied = target != null && runCatching {
                resolver.openInputStream(uri)?.use { input -> resolver.openOutputStream(target, "wt")?.use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (!copied) {
                target?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                error("Could not restore ${fix.to} from ${fix.name}")
            }
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
        return fixes.isNotEmpty()
    }

    /**
     * Where the document at [documentUri] lies under [treeUri], when the provider's ids are paths (this app's
     * storage, the device's shared storage); null for other providers, which then wait for the full sync.
     */
    fun relativePathOf(treeUri: String, documentUri: String): String? {
        val root = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        val id = runCatching { DocumentsContract.getDocumentId(Uri.parse(documentUri)) }.getOrNull() ?: return null
        return id.removePrefix("$root/").takeIf { id.startsWith("$root/") && it.isNotEmpty() && syncsFile(it) }
    }

    /** The file at [relativePath] as it is now, or null when it has gone. */
    fun entryAt(context: Context, treeUri: String, relativePath: String): LocalEntry? = runCatching {
        val tree = Uri.parse(treeUri)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "${DocumentsContract.getTreeDocumentId(tree)}/$relativePath")
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            LocalEntry(relativePath, uri.toString(), if (c.isNull(0)) 0L else c.getLong(0), if (c.isNull(1)) 0L else c.getLong(1))
        }
    }.getOrNull()

    /** Permanently delete the note at [relativePath] (applying a deletion that came from sync). True
     *  when the file is gone afterwards (already-absent counts as success). */
    fun deleteLocal(context: Context, treeUri: String, relativePath: String): Boolean {
        val tree = Uri.parse(treeUri)
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        val segments = relativePath.split("/")
        for (i in 0 until segments.size - 1) {
            parentId = findChild(context, tree, parentId, segments[i], dir = true) ?: return true
        }
        val fileId = findChild(context, tree, parentId, segments.last(), dir = false) ?: return true
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, fileId)
        return runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
    }

    private data class Child(val name: String, val docId: String, val uri: String, val isDir: Boolean, val size: Long, val modified: Long)

    private fun children(context: Context, tree: Uri, parentDocId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<Child>()
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val id = c.getString(1) ?: continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    val size = if (!c.isNull(3)) c.getLong(3) else 0L
                    val modified = if (!c.isNull(4)) c.getLong(4) else 0L
                    out.add(Child(name, id, uri, isDir, size, modified))
                }
            }
        }
        return out
    }

    private fun findChild(context: Context, tree: Uri, parentDocId: String, name: String, dir: Boolean): String? =
        children(context, tree, parentDocId).firstOrNull { it.isDir == dir && it.name.equals(name, ignoreCase = true) }?.docId

    private fun createDir(context: Context, tree: Uri, parentDocId: String, name: String): String? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
        val uri = runCatching {
            DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
        }.getOrNull() ?: return null
        return DocumentsContract.getDocumentId(uri)
    }
}
