package com.xnotes.core.geometry

import kotlin.math.hypot

/** Pure geometry predicates used by selection and hit-testing (spec 06 §15). */
object Geometry {

    /**
     * Even-odd point-in-polygon test (ray casting). The polygon is treated as a
     * closed ring (last vertex implicitly joins the first). Fewer than 3 vertices
     * is never "inside".
     */
    fun pointInPolygon(polygon: List<Pt>, p: Pt): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            val crosses = (a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    /** Shortest distance from point [p] to the segment [a]–[b]. */
    fun distancePointToSegment(p: Pt, a: Pt, b: Pt): Double {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq < 1e-12) return p.distanceTo(a)
        var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq
        t = t.coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * abx), p.y - (a.y + t * aby))
    }
}
