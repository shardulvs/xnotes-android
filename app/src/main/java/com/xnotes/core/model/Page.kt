package com.xnotes.core.model

/**
 * A single page (spec 02 §4). Z-order is list order: `items[0]` is at the back,
 * `items.last()` is on top. Pages are mutable and compared by **identity**.
 */
class Page(
    var width: Double,
    var height: Double,
    val items: MutableList<CanvasItem> = mutableListOf(),
    /** Index of the source-PDF page drawn as this page's background, or null. */
    var pdfPage: Int? = null,
    /** Per-page style override (paper colour + ruling); null fields inherit. See [PageStyle]. */
    var style: PageStyle = PageStyle(),
    /** Per-page margin override (extra paper outside the content box); null fields inherit. */
    var margins: PageMargins = PageMargins(),
    /** The marks on its PDF text, bottom first. Replaced whole, never mutated, so any thread can read it. */
    @Volatile var markups: List<TextMarkup> = emptyList(),
    /** Quarter turns clockwise its PDF page is shown at past the PDF's own /Rotate, 0 to 3. */
    var pdfTurn: Int = 0,
) {
    /**
     * A process-unique, stable id (not persisted). Pages compare by identity, but Compose list keys
     * must be a Bundle-storable type, so the side panel keys its thumbnail rows by this — it stays
     * the same when a page is reordered, and a clone gets a fresh one.
     */
    val uid: Long = nextUid()

    /** Whether this is the note's cover. Only the first page can be one, and a copied page never is. */
    var cover: Boolean = false

    /** The [PdfKey] its PDF background is read by, or null without one. */
    val pdfKey: Int? get() = pdfPage?.let { PdfKey.of(it, pdfTurn) }

    companion object {
        private val uidCounter = java.util.concurrent.atomic.AtomicLong(0L)
        private fun nextUid(): Long = uidCounter.incrementAndGet()

        /** A blank page sized from a named size and orientation at [dpi]. */
        fun blank(size: PageSize, orientation: Orientation, dpi: Int): Page {
            val (w, h) = size.pixels(orientation, dpi)
            return Page(w, h)
        }
    }
}

/**
 * A PDF page index and the quarter turns it is shown at, packed in one Int, so whatever reads or
 * holds a PDF page by number tells a turned copy of a page from the page itself.
 */
object PdfKey {
    private const val TURN_SHIFT = 28
    private const val INDEX_MASK = (1 shl TURN_SHIFT) - 1

    fun of(index: Int, turn: Int): Int = (index and INDEX_MASK) or (PageTurn.normalize(turn) shl TURN_SHIFT)

    fun index(key: Int): Int = key and INDEX_MASK

    fun turn(key: Int): Int = (key ushr TURN_SHIFT) and 3
}
