package com.xnotes.canvas

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Outline
import com.xnotes.core.model.Page
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TapeItem
import com.xnotes.core.shapes.ShapePath
import com.xnotes.core.vector.PathFlattener

/** A selected item plus the index of the page it lives on. */
data class Selected(val pageIndex: Int, val item: CanvasItem)

/**
 * Pure selection-membership tests (spec 06 §6–7).
 *
 * The band and the lasso share one rule: an item is in when the ring they draw touches what the
 * item draws, or goes all the way round it. What an item draws is its ink, a shape's or a
 * connector's outline, an image's edge or a text box's frame, never a shape's fill or label, so a
 * ring round writing inside a box that stays clear of the box's outline leaves the box out. A
 * dropped colour and a tape draw only fill, so their outer edge stands in for an outline.
 *
 * A locked item is not a member of anything, whichever way the selection was drawn. Putting that
 * here rather than at each call site is what makes it true of the band and the lasso on both
 * canvases at once.
 */
object SelectionMath {

    /**
     * Band selection over a flat item list, for a canvas with no pages. The paged overloads below
     * are the same test with a page-space translation in front of it; sharing the rule is the
     * point, so the two canvases select alike.
     */
    fun bandMembers(items: List<CanvasItem>, band: Rect, whole: Boolean = false): List<CanvasItem> {
        val ring = Ring(corners(band))
        return items.filter { !it.locked && ring.takes(it, whole) }
    }

    /** Lasso selection over a flat item list: the band's rule, with the loop as the ring. */
    fun lassoMembers(items: List<CanvasItem>, polygon: List<Pt>, whole: Boolean = false): List<CanvasItem> {
        if (polygon.size < 3) return emptyList()
        val ring = Ring(polygon)
        return items.filter { !it.locked && ring.takes(it, whole) }
    }

    /**
     * Band selection across pages. [toPage] maps a content-space point into page i's own space (a
     * plain page-rect translation, plus the display rotation when the view is rotated), where the
     * items' geometry lives.
     */
    fun bandMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        band: Rect,
        whole: Boolean = false,
        toPage: (Int, Pt) -> Pt = { i, p -> Pt(p.x - pageRects[i].left, p.y - pageRects[i].top) },
    ): List<Selected> = members(pages, pageRects, corners(band), whole, toPage)

    /** Lasso selection across pages: the band's rule, with the loop as the ring. */
    fun lassoMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        polygon: List<Pt>,
        whole: Boolean = false,
        toPage: (Int, Pt) -> Pt = { i, p -> Pt(p.x - pageRects[i].left, p.y - pageRects[i].top) },
    ): List<Selected> {
        if (polygon.size < 3) return emptyList()
        return members(pages, pageRects, polygon, whole, toPage)
    }

    private fun members(pages: List<Page>, pageRects: List<Rect>, ring: List<Pt>, whole: Boolean, toPage: (Int, Pt) -> Pt): List<Selected> {
        val reach = Rect.bounding(ring)
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            // Skip pages out of reach before touching their items; the slack covers ink painted past the edge.
            if (!pr.outset(OFF_PAGE_SLACK).intersects(reach)) continue
            val local = Ring(ring.map { toPage(i, it) })
            for (item in pages[i].items) {
                if (!item.locked && local.takes(item, whole)) out.add(Selected(i, item))
            }
        }
        return out
    }

    /** How far past its page edge an item may reach and still be reachable by a band that misses
     *  the page rect. Covers neon bloom and ink drawn over the margin. */
    const val OFF_PAGE_SLACK = 64.0

    private fun corners(r: Rect): List<Pt> = listOf(r.topLeft, Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom))

    /** One run of what an item draws, joined back to its start when [closed]. */
    private class Line(val pts: List<Pt>, val closed: Boolean)

    /** What an item draws, as lines, and how far its ink spreads either side of them. */
    private class Drawn(val lines: List<Line>, val reach: Double)

    private fun drawnOf(item: CanvasItem): Drawn = when (item) {
        is Stroke -> Drawn(listOf(Line(List(item.sampleCount) { Pt(item.xAt(it), item.yAt(it)) }, false)), item.config.baseWidth / 2.0)
        is ShapeItem -> if (item.isFill) fillEdge(item) else outlines(item.paths(), item.strokeWidth)
        is ConnectorItem -> outlines(item.paths(), item.strokeWidth)
        is ImageItem -> Drawn(listOf(Line(item.corners(), true)), 0.0)
        // Tape is all body, like a dropped colour, so its edge stands in for an outline.
        is TapeItem -> Drawn(item.shape.outline.map { Line(it.pts, it.closed) }, 0.0)
        else -> Drawn(listOf(Line(corners(item.bounds()), true)), 0.0)
    }

    /**
     * A dropped colour's outer edge: the rings that bound it, never its holes, whose edges sit under
     * lines drawn inside it. So a ring round writing inside a filled area leaves the fill out.
     */
    private fun fillEdge(fill: ShapeItem): Drawn {
        val rings = fill.fillRings(Outline.HIT_FLATTEN)
        val outer = rings.filter { ring -> rings.count { it !== ring && Geometry.pointInPolygon(it, ring[0]) } % 2 == 0 }
        return Drawn(outer.map { Line(it, true) }, 0.0)
    }

    private fun outlines(paths: List<ShapePath>, width: Double) = Drawn(
        paths.flatMap { path -> path.contours.map { Line(PathFlattener.flatten(it, Outline.HIT_FLATTEN), it.closed) } },
        width / 2.0,
    )

    /** A band or a lasso as the closed ring it draws, inside by the even-odd rule. */
    private class Ring(private val pts: List<Pt>) {
        private val box = Rect.bounding(pts)

        /** [whole]: everything [item] draws lies inside the ring; otherwise [meets]. */
        fun takes(item: CanvasItem, whole: Boolean): Boolean = if (whole) holds(item) else meets(item)

        private fun holds(item: CanvasItem): Boolean {
            val drawn = drawnOf(item)
            val area = boundsOf(drawn) ?: return false
            if (!area.intersects(box)) return false
            return drawn.lines.all { line -> line.pts.all { Geometry.pointInPolygon(pts, it) } }
        }

        /** True when some of what [item] draws lies inside the ring or within its ink's reach of the ring's line. */
        fun meets(item: CanvasItem): Boolean {
            val drawn = drawnOf(item)
            val reach = drawn.reach
            val area = boundsOf(drawn)?.outset(reach) ?: return false
            if (!area.intersects(box)) return false
            // A line starting inside is in; one starting outside must cross or touch an edge near the item.
            for (line in drawn.lines) if (line.pts.isNotEmpty() && Geometry.pointInPolygon(pts, line.pts[0])) return true
            val n = pts.size
            val near = (0 until n).filter { i ->
                val a = pts[i]
                val b = pts[(i + 1) % n]
                minOf(a.x, b.x) <= area.right && maxOf(a.x, b.x) >= area.left && minOf(a.y, b.y) <= area.bottom && maxOf(a.y, b.y) >= area.top
            }
            if (near.isEmpty()) return false
            for (line in drawn.lines) {
                val v = line.pts
                if (v.size == 1) {
                    if (near.any { Geometry.distancePointToSegment(v[0], pts[it], pts[(it + 1) % n]) <= reach }) return true
                    continue
                }
                val segments = if (line.closed) v.size else v.size - 1
                for (k in 0 until segments) {
                    val p = v[k]
                    val q = v[(k + 1) % v.size]
                    for (e in near) if (Geometry.distanceSegmentToSegment(p, q, pts[e], pts[(e + 1) % n]) <= reach) return true
                }
            }
            return false
        }

        private fun boundsOf(drawn: Drawn): Rect? {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (line in drawn.lines) for (p in line.pts) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return if (minX > maxX) null else Rect(minX, minY, maxX - minX, maxY - minY)
        }
    }
}
