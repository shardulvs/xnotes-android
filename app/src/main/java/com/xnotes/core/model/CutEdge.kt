package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A straight edge room cut an item along. The item shows only on the near side of the line through
 * ([x], [y]): the side its unit normal ([nx], [ny]) points away from. Page space, moved and turned
 * with the item.
 */
data class CutEdge(val x: Double, val y: Double, val nx: Double, val ny: Double) {

    /** How far ([px], [py]) lies past the edge, out along its normal; negative on the side that shows. */
    fun past(px: Double, py: Double): Double = (px - x) * nx + (py - y) * ny

    fun moved(dx: Double, dy: Double): CutEdge = CutEdge(x + dx, y + dy, nx, ny)

    /** The edge where [t] takes the item: its point mapped, its normal by the inverse transpose. */
    fun mapped(t: Affine): CutEdge {
        val p = t.apply(Pt(x, y))
        val det = t.determinant
        if (abs(det) < 1e-12) return CutEdge(p.x, p.y, nx, ny)
        val mx = (t.d * nx - t.b * ny) / det
        val my = (-t.c * nx + t.a * ny) / det
        val len = hypot(mx, my)
        return CutEdge(p.x, p.y, mx / len, my / len)
    }

    companion object {
        /** An edge along `y = [at]` that keeps what lies above it, or below it when [keepAfter]; across `x = [at]` when [sideways]. */
        fun along(at: Double, sideways: Boolean, keepAfter: Boolean, near: Double): CutEdge {
            val n = if (keepAfter) -1.0 else 1.0
            return if (sideways) CutEdge(at, near, n, 0.0) else CutEdge(near, at, 0.0, n)
        }
    }
}

/** Clipping by a set of [CutEdge]s, what every cut item draws and is hit through. */
object CutEdges {

    /** True when ([px], [py]) shows through every edge, counting [slack] past each. */
    fun keeps(cuts: List<CutEdge>, px: Double, py: Double, slack: Double = 0.0): Boolean {
        for (c in cuts) if (c.past(px, py) > slack) return false
        return true
    }

    /** [cuts] with [edge] added, dropping any edge with its normal that [edge] makes redundant, or [edge] if one already does. */
    fun with(cuts: List<CutEdge>, edge: CutEdge): List<CutEdge> {
        val out = ArrayList<CutEdge>(cuts.size + 1)
        var needed = true
        for (c in cuts) {
            if (abs(c.nx - edge.nx) < 1e-9 && abs(c.ny - edge.ny) < 1e-9) {
                if (edge.past(c.x, c.y) > 0.0) continue
                needed = false
            }
            out += c
        }
        if (needed) out += edge
        return out
    }

    /** The convex [polygon] cut down to what shows; empty when nothing does. */
    fun clip(polygon: List<Pt>, cuts: List<CutEdge>): List<Pt> {
        var poly = polygon
        for (c in cuts) {
            if (poly.isEmpty()) break
            val out = ArrayList<Pt>(poly.size + 1)
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                val da = c.past(a.x, a.y)
                val db = c.past(b.x, b.y)
                if (da <= 0.0) out += a
                if ((da < 0.0 && db > 0.0) || (da > 0.0 && db < 0.0)) {
                    val t = da / (da - db)
                    out += Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                }
            }
            poly = out
        }
        return poly
    }

    /** [r] cut down to what shows, as a polygon. */
    fun clip(r: Rect, cuts: List<CutEdge>): List<Pt> =
        clip(listOf(r.topLeft, Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom)), cuts)

    /** The box of what shows of [r], or null when none of it does. */
    fun clipBox(r: Rect, cuts: List<CutEdge>): Rect? {
        if (cuts.isEmpty()) return r
        val poly = clip(r, cuts)
        return if (poly.size < 3) null else Rect.bounding(poly)
    }

    /** The open polyline [pts] cut into the runs that show. */
    fun clipLine(pts: List<Pt>, cuts: List<CutEdge>): List<List<Pt>> {
        if (cuts.isEmpty()) return listOf(pts)
        if (pts.size == 1) return if (keeps(cuts, pts[0].x, pts[0].y)) listOf(pts) else emptyList()
        val runs = ArrayList<List<Pt>>()
        var run = ArrayList<Pt>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            var lo = 0.0
            var hi = 1.0
            for (c in cuts) {
                val da = c.past(a.x, a.y)
                val db = c.past(b.x, b.y)
                if (da > 0.0 && db > 0.0) { lo = 1.0; hi = 0.0; break }
                if (da > 0.0) lo = maxOf(lo, da / (da - db))
                if (db > 0.0) hi = minOf(hi, da / (da - db))
            }
            if (lo >= hi) {
                if (run.isNotEmpty()) runs += run
                run = ArrayList()
                continue
            }
            val p = Pt(a.x + (b.x - a.x) * lo, a.y + (b.y - a.y) * lo)
            val q = Pt(a.x + (b.x - a.x) * hi, a.y + (b.y - a.y) * hi)
            if (lo > 0.0 && run.isNotEmpty()) {
                runs += run
                run = ArrayList()
            }
            if (run.isEmpty()) run += p
            run += q
            if (hi < 1.0) {
                runs += run
                run = ArrayList()
            }
        }
        if (run.isNotEmpty()) runs += run
        return runs
    }
}
