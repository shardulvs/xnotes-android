package com.xnotes.core.model

import com.xnotes.core.util.Paths

/** A user bookmark into a document (spec 02 §3). */
data class Bookmark(var page: Int, var label: String)

/**
 * An ordered collection of pages plus on-disk identity (spec 02 §3). New notes
 * open with three blank pages at the user's default page size/orientation.
 */
class Document(
    val pages: MutableList<Page> = mutableListOf(),
    var dpi: Int = PageSize.DEFAULT_DPI,
    var path: String? = null,
    var dirty: Boolean = false,
    /** Embedded source PDF (for PDF-imported notes), so the note stays self-contained. */
    var pdfBytes: ByteArray? = null,
    val bookmarks: MutableList<Bookmark> = mutableListOf(),
) {
    /** Derived: the file base name without extension, or "Untitled". */
    val title: String get() = path?.let { Paths.stem(it) } ?: "Untitled"

    val hasPdf: Boolean get() = pdfBytes != null

    /**
     * Appends a blank page sized [width] × [height] and marks the document
     * dirty. The UI passes the current page's dimensions so a note stays uniform.
     */
    fun addPage(width: Double, height: Double, pdfPage: Int? = null): Page {
        val page = Page(width, height, pdfPage = pdfPage)
        pages.add(page)
        dirty = true
        return page
    }

    companion object {
        const val DEFAULT_NEW_PAGES = 3

        /** A blank document of [count] pages of the given size/orientation. */
        fun blank(
            count: Int = DEFAULT_NEW_PAGES,
            size: PageSize = PageSize.A4,
            orientation: Orientation = Orientation.PORTRAIT,
            dpi: Int = PageSize.DEFAULT_DPI,
        ): Document {
            val doc = Document(dpi = dpi)
            repeat(count) { doc.pages.add(Page.blank(size, orientation, dpi)) }
            return doc
        }
    }
}
