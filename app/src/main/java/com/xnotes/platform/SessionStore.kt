package com.xnotes.platform

import com.xnotes.core.model.Document
import com.xnotes.format.DocumentCodec
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Persists the working session — the open document (including unsaved edits) plus
 * the view state (zoom/scroll) — under a private directory, so relaunching reopens
 * the last note where the user left off instead of a blank one.
 *
 * The document is written in the normal `.xnote` format via [DocumentCodec]; the
 * file's identity ([Document.path]/[Document.displayName]/[Document.dirty], which
 * the codec doesn't carry) and the view state go in a small sidecar JSON.
 * Best-effort: failures are swallowed, and a missing or corrupt session restores
 * nothing.
 */
class SessionStore(private val dir: File, private val codec: DocumentCodec) {

    private val docFile = File(dir, "document.xnote")
    private val meta = JsonStore(File(dir, "session.json"))

    /** A restored session: the document plus the view state to reapply. */
    class Snapshot(
        val document: Document,
        val zoom: Double,
        val scrollX: Double,
        val scrollY: Double,
        val zoomLocked: Boolean,
    )

    /**
     * Save [document] and view state. Pass [writeDocument] = false to refresh only
     * the (cheap) view-state sidecar when the document content hasn't changed since
     * the last save — avoiding re-serializing a large note on every pause.
     */
    fun save(
        document: Document,
        zoom: Double,
        scrollX: Double,
        scrollY: Double,
        zoomLocked: Boolean,
        writeDocument: Boolean,
    ) {
        runCatching {
            dir.mkdirs()
            if (writeDocument || !docFile.exists()) {
                val tmp = File(dir, "document.xnote.tmp")
                FileOutputStream(tmp).use { codec.write(document, it) }
                if (!tmp.renameTo(docFile)) {
                    docFile.delete()
                    tmp.renameTo(docFile)
                }
            }
            val m = JSONObject()
                .put("dirty", document.dirty)
                .put("zoom", zoom)
                .put("scrollX", scrollX)
                .put("scrollY", scrollY)
                .put("zoomLocked", zoomLocked)
            document.path?.let { m.put("path", it) }
            document.displayName?.let { m.put("displayName", it) }
            meta.write(m)
        }
    }

    /** Load the saved session, or null when there is none or it can't be read. */
    fun load(): Snapshot? {
        if (!docFile.exists()) return null
        val doc = runCatching { FileInputStream(docFile).use { codec.read(it) } }.getOrNull() ?: return null
        val m = meta.read()
        doc.path = m.optString("path", "").ifEmpty { null }
        doc.displayName = m.optString("displayName", "").ifEmpty { null }
        doc.dirty = m.optBoolean("dirty", false)
        return Snapshot(
            doc,
            m.optDouble("zoom", 0.0),
            m.optDouble("scrollX", 0.0),
            m.optDouble("scrollY", 0.0),
            m.optBoolean("zoomLocked", false),
        )
    }
}
