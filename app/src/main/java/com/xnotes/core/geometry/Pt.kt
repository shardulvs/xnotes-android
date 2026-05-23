package com.xnotes.core.geometry

import kotlin.math.abs
import kotlin.math.hypot

/**
 * A 2D point / vector in document or page-local space.
 *
 * The core uses [Double] precision throughout so the stroke engine reproduces
 * the spec's conformance vectors exactly; coordinates are narrowed to Float only
 * at the Android rendering boundary.
 */
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Double) = Pt(x * s, y * s)
    operator fun div(s: Double) = Pt(x / s, y / s)

    fun length(): Double = hypot(x, y)
    fun distanceTo(o: Pt): Double = hypot(x - o.x, y - o.y)

    /** Manhattan distance `|dx| + |dy|` — used by the 1px sample filter (spec 03 §5). */
    fun manhattanTo(o: Pt): Double = abs(x - o.x) + abs(y - o.y)

    fun normalized(): Pt {
        val l = length()
        return if (l < 1e-12) ZERO else Pt(x / l, y / l)
    }

    /** Rotate 90° counter-clockwise: `(x, y) -> (-y, x)` (the stroke normal). */
    fun perp(): Pt = Pt(-y, x)

    companion object {
        val ZERO = Pt(0.0, 0.0)
    }
}
