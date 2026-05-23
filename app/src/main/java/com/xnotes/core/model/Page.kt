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
) {
    companion object {
        /** A blank page sized from a named size and orientation at [dpi]. */
        fun blank(size: PageSize, orientation: Orientation, dpi: Int): Page {
            val (w, h) = size.pixels(orientation, dpi)
            return Page(w, h)
        }
    }
}
