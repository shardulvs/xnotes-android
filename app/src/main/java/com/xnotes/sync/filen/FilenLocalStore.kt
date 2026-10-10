package com.xnotes.sync.filen

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xnotes.core.util.ReaderKind

/**
 * Reads and writes the note files under the explorer browse root (a SAF tree uri, which
 * also covers the app's internal DocumentsProvider). Mirrors the DocumentsContract calls
 * the Editor already uses, but without depending on it, so background sync can run.
 */
object FilenLocalStore {
    private val NOTE_EXTENSIONS = listOf(".xnote", ".xcanvas")
    private const val SIDECAR_DIR = ".xnote"

    /** The hidden recycle-bin folder under the browse root. Synced like a normal folder (so a
     *  deletion reaches every device), but hidden from the note listing and presented as Trash. */
    const val TRASH_DIR = ".trash"

    fun isTrashPath(relativePath: String): Boolean = relativePath == TRASH_DIR || relativePath.startsWith("$TRASH_DIR/")

    data class LocalEntry(val relativePath: String, val documentUri: String, val size: Long, val modified: Long)

    /** The synced files and every synced folder (relative paths), from one walk of the tree. */
    class LocalTree(val files: List<LocalEntry>, val folders: Set<String>)

    fun isNote(name: String) = NOTE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    /** Notes and canvases, plus the read-only kinds (Markdown, Office, CSV, PDF) the reader opens. */
    fun isSynced(name: String) = isNote(name) || ReaderKind.isReadable(name)

    fun listNotes(context: Context, treeUri: String): List<LocalEntry> = listTree(context, treeUri).files

    fun listTree(context: Context, treeUri: String): LocalTree {
        val tree = Uri.parse(treeUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val out = ArrayList<LocalEntry>()
        val folders = HashSet<String>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<Pair<String, String>>().apply { addLast(rootId to "") }
        while (stack.isNotEmpty()) {
            val (docId, prefix) = stack.removeLast()
            if (!seen.add(docId)) continue
            for (child in children(context, tree, docId)) {
                if (child.isDir) {
                    // Skip hidden folders (colour sidecar, config) but descend into .trash so the
                    // recycle bin replicates across devices like any other synced folder.
                    if (child.name.startsWith(".") && child.name != TRASH_DIR) continue
                    val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    folders.add(rel)
                    stack.addLast(child.docId to rel)
                } else if (isSynced(child.name)) {
                    val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
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

    fun readBytes(context: Context, documentUri: String): ByteArray? =
        runCatching { context.contentResolver.openInputStream(Uri.parse(documentUri))?.use { it.readBytes() } }.getOrNull()

    fun modifiedOf(context: Context, documentUri: String): Long = runCatching {
        context.contentResolver.query(
            Uri.parse(documentUri), arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    /** Create or overwrite a note at [relativePath] (folders created as needed). Returns the document uri, or null. */
    fun writeNote(context: Context, treeUri: String, relativePath: String, bytes: ByteArray): String? {
        val tree = Uri.parse(treeUri)
        val segments = relativePath.split("/")
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        for (i in 0 until segments.size - 1) {
            parentId = findChild(context, tree, parentId, segments[i], dir = true)
                ?: createDir(context, tree, parentId, segments[i]) ?: return null
        }
        val fileName = segments.last()
        val existing = findChild(context, tree, parentId, fileName, dir = false)
        val uri = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(tree, existing)
        } else {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            runCatching {
                DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", fileName)
            }.getOrNull() ?: return null
        }
        return runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            uri.toString()
        }.getOrNull()
    }

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
