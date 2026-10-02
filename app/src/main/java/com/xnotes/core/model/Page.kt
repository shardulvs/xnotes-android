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
) {
    /**
     * A process-unique, stable id (not persisted). Pages compare by identity, but Compose list keys
     * must be a Bundle-storable type, so the side panel keys its thumbnail rows by this — it stays
     * the same when a page is reordered, and a clone gets a fresh one.
     */
    val uid: Long = nextUid()

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
