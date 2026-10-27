package com.xnotes.core.model

import com.xnotes.core.text.TextFlow
import com.xnotes.core.util.Paths

/** A user bookmark into a document (spec 02 §3). */
data class Bookmark(var page: Int, var label: String)

/** What was aimed at a page when it was taken out: its bookmarks, with their list slots, and the page breaks to it. */
class PageMarks(val bookmarks: List<Pair<Int, Bookmark>>, val breaks: List<com.xnotes.core.text.Paragraph>) {
    companion object {
        val NONE = PageMarks(emptyList(), emptyList())
    }
}

/**
 * An ordered collection of pages plus on-disk identity (spec 02 §3). New notes
 * open with one blank page at the user's default page size/orientation.
 */
class Document(
    val pages: MutableList<Page> = mutableListOf(),
    var dpi: Int = PageSize.DEFAULT_DPI,
    var path: String? = null,
    /** Real file name from the storage provider, when known (overrides [path]-derived title). */
    var displayName: String? = null,
    var dirty: Boolean = false,
    /**
     * Embedded source PDF (for PDF-imported notes), so the note stays self-contained. Held as a
     * **file** on disk, not bytes in RAM: the file is memory-mapped by the renderer and streamed
     * into/out of the `.xnote` bundle, so even a very large PDF never has to fit in the heap. The
     * file lives in a private cache dir owned by the platform layer (which manages its lifetime).
     */
    var pdfFile: java.io.File? = null,
    val bookmarks: MutableList<Bookmark> = mutableListOf(),
    /** Document-wide ("all pages") style override; per-page [Page.style] layers on top. */
    var style: PageStyle = PageStyle(),
    /** Document-wide ("all pages") margin override; per-page [Page.margins] layers on top. */
    var margins: PageMargins = PageMargins(),
    /** The document-wide flowing rich text (empty until typed into; persisted only when non-empty). */
    val flow: TextFlow = TextFlow(),
    /** When the note was created (epoch ms), or null for files written before this was recorded. */
    var created: Long? = null,
    /**
     * The `.xtemplate` files this note's styles name, by key, carried inside the bundle so the note
     * draws the same on any device. Built-in rulings are never here. Replaced whole, never mutated.
     */
    var templates: Map<String, String> = emptyMap(),
    /**
     * The packaged templates (zips holding pictures) this note's styles name, by key, as files on
     * disk so their pictures never sit in the heap. Like [templates], replaced whole.
     */
    var templatePackages: Map<String, java.io.File> = emptyMap(),
    /** The size of each page added from now on, or null to give it the size of the page before it. */
    var newPageSize: PageSizeChoice? = null,
) {
    /** Transient: set by the codec when legacy ink was compacted at load (debug overlay readout). */
    var compactedOnLoad = false

    /** Derived: the storage display name (or path) base name without extension, or "Untitled". */
    val title: String
        get() = displayName?.let { Paths.stem(it) }
            ?: path?.let { Paths.stem(it) }
            ?: "Untitled"

    val hasPdf: Boolean get() = pdfFile != null

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

    /**
     * Put [page] in at [index] (clamped), moving every bookmark and page break aimed from there on
     * down a page, and put back [marks], what [removePage] took off it, as it stood. Returns the index.
     */
    fun insertPage(page: Page, index: Int, marks: PageMarks = PageMarks.NONE): Int {
        val at = index.coerceIn(0, pages.size)
        pages.add(at, page)
        for (mark in bookmarks) if (mark.page >= at) mark.page++
        for ((slot, mark) in marks.bookmarks) bookmarks.add(slot.coerceIn(0, bookmarks.size), mark.also { it.page = at })
        for (para in flow.paragraphs) para.breakToPage?.let { if (it >= at) para.breakToPage = it + 1 }
        for (para in marks.breaks) para.breakToPage = at
        return at
    }

    /**
     * Take [page] out with the bookmarks on it, moving later ones up a page. A page break aimed at
     * it is left aimed at the page that takes its place. Returns what it took, for [insertPage].
     */
    fun removePage(page: Page): PageMarks {
        val at = pages.indexOfFirst { it === page }
        if (at < 0) return PageMarks.NONE
        pages.removeAt(at)
        val taken = bookmarks.withIndex().filter { it.value.page == at }.map { it.index to it.value }
        for ((slot, _) in taken.asReversed()) bookmarks.removeAt(slot)
        for (mark in bookmarks) if (mark.page > at) mark.page--
        val breaks = flow.paragraphs.filter { it.breakToPage == at }
        for (para in flow.paragraphs) para.breakToPage?.let { if (it > at) para.breakToPage = it - 1 }
        return PageMarks(taken, breaks)
    }

    /** The size of a blank page added after [previous]: [newPageSize] if set, else [previous]'s, else A4 portrait. */
    fun blankPageSize(previous: Page?): Pair<Double, Double> =
        newPageSize?.pixels(dpi)
            ?: previous?.let { it.width to it.height }
            ?: PageSize.A4.pixels(Orientation.PORTRAIT, dpi)

    companion object {
        const val DEFAULT_NEW_PAGES = 1

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

        /** A blank document of [count] pages measured in document pixels, for a custom size. */
        fun blankPixels(
            count: Int = DEFAULT_NEW_PAGES,
            width: Double,
            height: Double,
            dpi: Int = PageSize.DEFAULT_DPI,
        ): Document {
            val doc = Document(dpi = dpi)
            repeat(count) { doc.pages.add(Page(width, height)) }
            return doc
        }
    }
}
