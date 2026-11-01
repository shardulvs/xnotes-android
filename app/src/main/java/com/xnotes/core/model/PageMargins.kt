package com.xnotes.core.model

/** The four page edges a margin can be added to. */
enum class PageEdge(val id: String) {
    LEFT("left"),
    RIGHT("right"),
    TOP("top"),
    BOTTOM("bottom"),
}

/**
 * Extra blank space added **outside** a page's content box, one fraction per edge, held on both
 * [Page] (current page) and [Document] ("all pages"). Left/right are fractions of the page's own
 * width, top/bottom of its own height, the room inserted into it left out ([Page.sourceWidth],
 * [Page.sourceHeight]), so they never compound: the page grows from its stored size, never from an
 * already-grown one, and inserting room never widens a margin.
 *
 * The page's content keeps its coordinates — a margin moves the *paper* out from under it, it
 * never moves the ink — so page space simply starts at negative coordinates on a margined edge
 * (see [PageInsets]). Like [PageStyle] every field is independently nullable: null inherits from
 * the next level down (page -> document -> none), which is what gives the UI its "Default" chip.
 */
data class PageMargins(
    val left: Double? = null,
    val top: Double? = null,
    val right: Double? = null,
    val bottom: Double? = null,
) {
    /** True when nothing is overridden — the codec writes no `margins` object in this case. */
    val isEmpty: Boolean
        get() = left == null && top == null && right == null && bottom == null

    fun edge(e: PageEdge): Double? = when (e) {
        PageEdge.LEFT -> left
        PageEdge.RIGHT -> right
        PageEdge.TOP -> top
        PageEdge.BOTTOM -> bottom
    }

    /** This style with one edge replaced; a null [fraction] resets that edge to inherit. */
    fun withEdge(e: PageEdge, fraction: Double?): PageMargins {
        val f = fraction?.coerceIn(0.0, MAX)
        return when (e) {
            PageEdge.LEFT -> copy(left = f)
            PageEdge.RIGHT -> copy(right = f)
            PageEdge.TOP -> copy(top = f)
            PageEdge.BOTTOM -> copy(bottom = f)
        }
    }

    companion object {
        /** A margin can at most double the page along each axis' edge (100% of width/height). */
        const val MAX = 1.0
    }
}

/**
 * A page's resolved margins in content pixels. The page's own content box stays at page-space
 * (0, 0)..(width, height); the *footprint* the paper covers runs from (-[left], -[top]) to
 * (width + [right], height + [bottom]).
 */
data class PageInsets(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val isZero: Boolean get() = left == 0.0 && top == 0.0 && right == 0.0 && bottom == 0.0

    companion object {
        val NONE = PageInsets(0.0, 0.0, 0.0, 0.0)
    }
}

// --- resolution: a page's own override -> its document's ("all pages") override -> none ---
// Mirrors the [PageStyle] hierarchy so the canvas, the thumbnails and PDF export all resolve
// against the document actually being drawn (not necessarily the open one).

fun Page.resolvedMargins(doc: Document): PageMargins {
    val d = doc.margins
    return PageMargins(
        left = margins.left ?: d.left,
        top = margins.top ?: d.top,
        right = margins.right ?: d.right,
        bottom = margins.bottom ?: d.bottom,
    )
}

/** [resolvedMargins] converted to content pixels against this page's own size, its room left out. */
fun Page.insets(doc: Document): PageInsets {
    if (margins.isEmpty && doc.margins.isEmpty) return PageInsets.NONE
    val m = resolvedMargins(doc)
    fun f(v: Double?, of: Double) = (v ?: 0.0).coerceIn(0.0, PageMargins.MAX) * of
    val w = sourceWidth
    val h = sourceHeight
    return PageInsets(f(m.left, w), f(m.top, h), f(m.right, w), f(m.bottom, h))
}
