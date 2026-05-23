package com.xnotes.core.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * An axis-aligned bounding rectangle (AABB), stored as origin + size with
 * non-negative width/height. Origin is top-left; y increases downward.
 */
data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val left get() = x
    val top get() = y
    val right get() = x + w
    val bottom get() = y + h
    val centerX get() = x + w / 2.0
    val centerY get() = y + h / 2.0
    val center get() = Pt(centerX, centerY)
    val topLeft get() = Pt(x, y)

    fun translate(dx: Double, dy: Double) = Rect(x + dx, y + dy, w, h)

    /** Inclusive point containment. */
    fun contains(p: Pt): Boolean = p.x in left..right && p.y in top..bottom

    /** Standard AABB overlap test (used by band selection). */
    fun intersects(o: Rect): Boolean =
        left <= o.right && right >= o.left && top <= o.bottom && bottom >= o.top

    /** Grow on every side by [m] (used to include stroke width / arrowheads). */
    fun outset(m: Double) = Rect(x - m, y - m, w + 2 * m, h + 2 * m)

    fun union(o: Rect): Rect {
        val l = min(left, o.left)
        val t = min(top, o.top)
        val r = max(right, o.right)
        val b = max(bottom, o.bottom)
        return Rect(l, t, r - l, b - t)
    }

    /** Shortest distance from [p] to the rectangle (0 inside). */
    fun distanceTo(p: Pt): Double {
        val dx = max(max(left - p.x, p.x - right), 0.0)
        val dy = max(max(top - p.y, p.y - bottom), 0.0)
        return kotlin.math.hypot(dx, dy)
    }

    companion object {
        /** Normalized rectangle from any two corners. */
        fun ltrb(l: Double, t: Double, r: Double, b: Double) =
            Rect(min(l, r), min(t, b), abs(r - l), abs(b - t))

        fun fromPoints(a: Pt, b: Pt) = ltrb(a.x, a.y, b.x, b.y)

        /** Tight AABB enclosing all [points]; empty rect when the list is empty. */
        fun bounding(points: List<Pt>): Rect {
            if (points.isEmpty()) return Rect(0.0, 0.0, 0.0, 0.0)
            var minX = points[0].x
            var minY = points[0].y
            var maxX = minX
            var maxY = minY
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return Rect(minX, minY, maxX - minX, maxY - minY)
        }
    }
}
