package com.xnotes.sync

import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.ConcurrentHashMap

/** The notes open in an editor right now, by document id, so sync never rewrites one under it. */
object OpenNotes {
    private val byEditor = ConcurrentHashMap<Any, String>()

    fun set(editor: Any, uri: String?) {
        if (uri == null) byEditor.remove(editor) else byEditor[editor] = docId(uri)
    }

    fun isOpen(uri: String): Boolean = docId(uri) in byEditor.values

    private fun docId(uri: String): String = runCatching { DocumentsContract.getDocumentId(Uri.parse(uri)) }.getOrDefault(uri)
}
