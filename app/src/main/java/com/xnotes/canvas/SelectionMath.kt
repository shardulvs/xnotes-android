package com.xnotes.canvas

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page

/** A selected item plus the index of the page it lives on. */
data class Selected(val pageIndex: Int, val item: CanvasItem)

/**
 * Pure selection-membership tests (spec 06 §6–7).
 *
 * A locked item is not a member of anything, whichever way the selection was drawn. Putting that
 * here rather than at each call site is what makes it true of the band and the lasso on both
 * canvases at once.
 */
object SelectionMath {

    /**
     * Band selection over a flat item list, for a canvas with no pages: every item whose bounds
     * meet [band]. The paged overloads below are the same test with a page-space translation in
     * front of it; sharing the rule is the point, so the two canvases select alike.
     */
    fun bandMembers(items: List<CanvasItem>, band: Rect, whole: Boolean = false): List<CanvasItem> =
        items.filter { !it.locked && inBand(it.bounds(), band, whole) }

    /** Lasso selection over a flat item list: see [inLasso]. */
    fun lassoMembers(items: List<CanvasItem>, polygon: List<Pt>, whole: Boolean = false): List<CanvasItem> {
        if (polygon.size < 3) return emptyList()
        return items.filter { !it.locked && inLasso(polygon, it.centroid(), it.outlinePoints(), whole) }
    }

    /** [whole]: the band holds the item's bounds entirely; otherwise touching them is enough. */
    private fun inBand(bounds: Rect, band: Rect, whole: Boolean): Boolean =
        if (whole) band.contains(bounds.topLeft) && band.contains(Pt(bounds.right, bounds.bottom))
        else bounds.intersects(band)

    /** [whole]: every outline point lies inside the loop; otherwise one of them, or the centroid, is enough. */
    private fun inLasso(polygon: List<Pt>, centroid: Pt, outline: List<Pt>, whole: Boolean): Boolean =
        if (whole) outline.isNotEmpty() && outline.all { Geometry.pointInPolygon(polygon, it) }
        else Geometry.pointInPolygon(polygon, centroid) || outline.any { Geometry.pointInPolygon(polygon, it) }

    /**
     * Band selection: every item whose content-space bounds meet [band] (or lie inside it, when
     * [whole]). [toContentRect] maps an item's page-space bounds on page i into content space (a
     * plain page-rect translation, plus the display rotation when the view is rotated).
     */
    fun bandMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        band: Rect,
        whole: Boolean = false,
        toContentRect: (Int, Rect) -> Rect = { i, r -> r.translate(pageRects[i].left, pageRects[i].top) },
    ): List<Selected> {
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            // Skip a page the band cannot reach, before touching its items. item.bounds() builds the
            // stroke's ribbon, so without this one small drag rebuilds every stroke in the document
            // and undoes the canvas's geometry eviction. Outset by OFF_PAGE_SLACK because an item
            // may paint past its page edge (a neon glow, ink drawn over the margin).
            if (!pr.outset(OFF_PAGE_SLACK).intersects(band)) continue
            for (item in pages[i].items) {
                if (item.locked) continue
                if (inBand(toContentRect(i, item.bounds()), band, whole)) out.add(Selected(i, item))
            }
        }
        return out
    }

    /** How far past its page edge an item may reach and still be reachable by a band that misses
     *  the page rect. Covers neon bloom and ink drawn over the margin. */
    const val OFF_PAGE_SLACK = 64.0

    /**
     * Lasso selection: see [inLasso], tested in content space (even-odd). [toContent] maps a
     * page-space point on page i into content space, like [bandMembers].
     */
    fun lassoMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        polygon: List<Pt>,
        whole: Boolean = false,
        toContent: (Int, Pt) -> Pt = { i, p -> Pt(p.x + pageRects[i].left, p.y + pageRects[i].top) },
    ): List<Selected> {
        if (polygon.size < 3) return emptyList()
        val reach = Rect.bounding(polygon)
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            if (!pr.outset(OFF_PAGE_SLACK).intersects(reach)) continue
            for (item in pages[i].items) {
                if (item.locked) continue
                if (inLasso(polygon, toContent(i, item.centroid()), item.outlinePoints().map { toContent(i, it) }, whole)) {
                    out.add(Selected(i, item))
                }
            }
        }
        return out
    }
}
