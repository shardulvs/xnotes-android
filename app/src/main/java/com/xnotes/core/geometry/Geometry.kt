package com.xnotes.core.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.sqrt

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

    /** [pointInPolygon] over a packed interleaved x,y ring (the stroke ribbon outline). */
    fun pointInPolygon(polygon: FloatArray, p: Pt): Boolean {
        val n = polygon.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val ax = polygon[2 * i].toDouble()
            val ay = polygon[2 * i + 1].toDouble()
            val bx = polygon[2 * j].toDouble()
            val by = polygon[2 * j + 1].toDouble()
            val crosses = (ay > p.y) != (by > p.y) &&
                p.x < (bx - ax) * (p.y - ay) / (by - ay) + ax
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

    /** Shortest distance between the segments [a]–[b] and [c]–[d]; 0 when they cross. */
    fun distanceSegmentToSegment(a: Pt, b: Pt, c: Pt, d: Pt): Double {
        val abc = cross(a, b, c)
        val abd = cross(a, b, d)
        val cda = cross(c, d, a)
        val cdb = cross(c, d, b)
        if (((abc > 0.0 && abd < 0.0) || (abc < 0.0 && abd > 0.0)) && ((cda > 0.0 && cdb < 0.0) || (cda < 0.0 && cdb > 0.0))) return 0.0
        return minOf(
            minOf(distancePointToSegment(a, c, d), distancePointToSegment(b, c, d)),
            minOf(distancePointToSegment(c, a, b), distancePointToSegment(d, a, b)),
        )
    }

    /** Which side of the line [a]->[b] point [p] falls on: positive one way, negative the other. */
    private fun cross(a: Pt, b: Pt, p: Pt): Double = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    /** Dot product of two vectors. */
    fun dot(a: Pt, b: Pt): Double = a.x * b.x + a.y * b.y

    /** What [capsule] returns when one disc holds the other, so their hull is just the larger disc. */
    const val SWALLOWED = -1.0

    /**
     * Hull of discs ([x0], [y0], [r0]) and ([x1], [y1], [r1]): writes its two tangent lines' outward normals into [out]
     * (left x, y, then right x, y, travelling first to second, y down) and returns the degrees of the second rim on it.
     */
    fun capsule(x0: Double, y0: Double, r0: Double, x1: Double, y1: Double, r1: Double, out: DoubleArray): Double {
        val dx = x1 - x0
        val dy = y1 - y0
        val d = hypot(dx, dy)
        if (d <= abs(r1 - r0) + 1e-9) return SWALLOWED
        val ux = dx / d
        val uy = dy / d
        val s = (r0 - r1) / d
        val k = sqrt(1.0 - s * s)
        out[0] = ux * s + uy * k
        out[1] = uy * s - ux * k
        out[2] = ux * s - uy * k
        out[3] = uy * s + ux * k
        return 180.0 - 2.0 * asin(s) * 180.0 / PI
    }

    /**
     * Foot of the perpendicular from [p] onto the **infinite** line through [a]–[b]
     * (the parameter is not clamped, so the result can fall past either endpoint).
     * A degenerate `a == b` returns [a]. Used to ride a stroke along the ruler edge.
     */
    fun projectPointOntoLine(p: Pt, a: Pt, b: Pt): Pt {
        val ab = b - a
        val lenSq = dot(ab, ab)
        if (lenSq < 1e-12) return a
        return a + ab * (dot(p - a, ab) / lenSq)
    }
}
