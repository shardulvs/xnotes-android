package com.xnotes.ui

import androidx.compose.runtime.mutableStateListOf
import com.xnotes.core.model.Page
import java.io.File

/**
 * A process-wide clipboard of copied pages, so pages copied in one note can be pasted into another
 * (the clipboard used to be per-editor and was wiped on every document switch). The pages are deep
 * copies; [sourcePdfFile] is the PDF the copied pages were backed by, if any, so a PDF-backed page
 * can be flattened to an image when pasted into a different note.
 */
object PageClipboard {
    val pages = mutableStateListOf<Page>()
    var sourcePdfFile: File? = null
        private set

    val isEmpty: Boolean get() = pages.isEmpty()

    fun set(newPages: List<Page>, pdfFile: File?) {
        pages.clear()
        pages.addAll(newPages)
        sourcePdfFile = pdfFile
    }
}
