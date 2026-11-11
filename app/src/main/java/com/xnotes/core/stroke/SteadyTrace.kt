package com.xnotes.core.stroke

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What stabilization did to one stroke, for the debug HUD: the nib's path and the steadied one,
 * event by event while the pen draws, measured against each other once it lifts.
 */
class SteadyTrace {
    private var nx = DoubleArray(INITIAL_CAPACITY)
    private var ny = DoubleArray(INITIAL_CAPACITY)
    private var sx = DoubleArray(INITIAL_CAPACITY)
    private var sy = DoubleArray(INITIAL_CAPACITY)

    /** Events recorded since [start]. */
    var count = 0
        private set

    /** Begin a stroke where the nib went down, which is where its steadied point starts too. */
    fun start(x: Double, y: Double) {
        count = 0
        add(x, y, x, y)
    }

    fun add(nibX: Double, nibY: Double, steadyX: Double, steadyY: Double) {
        if (count == nx.size) {
            val c = nx.size * 2
            nx = nx.copyOf(c)
            ny = ny.copyOf(c)
            sx = sx.copyOf(c)
            sy = sy.copyOf(c)
        }
        nx[count] = nibX
        ny[count] = nibY
        sx[count] = steadyX
        sy[count] = steadyY
        count++
    }

    /** The stroke's numbers in mm on screen, [mmPerPx] converting from content px. */
    fun measure(mmPerPx: Double): SteadyEffect {
        val n = count
        if (n == 0) return SteadyEffect(0.0, 0.0, 0.0, Double.NaN)
        var trail = 0.0
        var sum = 0.0
        var worst = 0.0
        for (i in 0 until n) {
            trail = max(trail, hypot(nx[i] - sx[i], ny[i] - sy[i]))
            val d = offSteadied(i)
            sum += d
            worst = max(worst, d)
        }
        val step = WIGGLE_STEP_MM / mmPerPx
        val raw = turning(nx, ny, n, step)
        val wiggle = if (raw > MIN_TURNING) turning(sx, sy, n, step) / raw - 1.0 else Double.NaN
        return SteadyEffect(sum / n * mmPerPx, worst * mmPerPx, trail * mmPerPx, wiggle)
    }

    /** How far nib point [i] lies from the steadied path, searched over the events around it. */
    private fun offSteadied(i: Int): Double {
        val n = count
        if (n == 1) return hypot(nx[i] - sx[0], ny[i] - sy[0])
        var best = Double.POSITIVE_INFINITY
        for (j in max(0, i - SEARCH_EVENTS)..min(n - 2, i + SEARCH_EVENTS)) {
            best = min(best, segmentDistance2(nx[i], ny[i], sx[j], sy[j], sx[j + 1], sy[j + 1]))
        }
        return sqrt(best)
    }

    companion object {
        /** Millimetres in a dp, which is 1/160 of an inch. */
        const val MM_PER_DP = 25.4 / 160.0

        private const val INITIAL_CAPACITY = 256

        /** Events either side of a nib point searched for the nearest stretch of steadied path. */
        private const val SEARCH_EVENTS = 400

        /** The arc spacing, mm on screen, both paths are resampled at before their turning is summed. */
        private const val WIGGLE_STEP_MM = 0.15

        /** Radians of turning below which the nib's path is too straight for its wiggle to mean anything. */
        private const val MIN_TURNING = 0.2

        /** The squared distance from ([px], [py]) to the segment from ([ax], [ay]) to ([bx], [by]). */
        private fun segmentDistance2(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0.0) (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val ex = px - (ax + t * dx)
            val ey = py - (ay + t * dy)
            return ex * ex + ey * ey
        }

        /** The total turning, in radians, of the path through [n] points resampled every [step] of arc. */
        internal fun turning(xs: DoubleArray, ys: DoubleArray, n: Int, step: Double): Double {
            if (n < 2 || !(step > 0.0)) return 0.0
            var total = 0.0
            var qx = xs[0]
            var qy = ys[0]
            var cx = 0.0
            var cy = 0.0
            var chord = false
            var need = step
            for (k in 1 until n) {
                val ax = xs[k - 1]
                val ay = ys[k - 1]
                val dx = xs[k] - ax
                val dy = ys[k] - ay
                val len = hypot(dx, dy)
                var at = 0.0
                while (len - at >= need) {
                    at += need
                    need = step
                    val px = ax + dx * at / len
                    val py = ay + dy * at / len
                    val ux = px - qx
                    val uy = py - qy
                    if (chord) total += abs(atan2(cx * uy - cy * ux, cx * ux + cy * uy))
                    cx = ux
                    cy = uy
                    chord = true
                    qx = px
                    qy = py
                }
                need -= len - at
            }
            return total
        }
    }
}

/**
 * One stroke's stabilization, lengths in mm on screen: how far the nib's path sits from the ink, on
 * average and at most; how far the steadied point fell behind the nib; and how much the ink's
 * turning changed against the nib's (-0.4 is 40% less wiggle, NaN on a path too straight to say).
 */
class SteadyEffect(val movedMean: Double, val movedMax: Double, val trail: Double, val wiggle: Double)

/** The last stroke's stabilization, as the debug HUD shows it. */
class SteadyReadout {
    /** A stroke's Strength and Crossover as the sliders read, and what they did, or null when it was off. */
    class Row(val strength: Double, val crossover: Double, val effect: SteadyEffect?)

    @Volatile
    var last: Row? = null

    fun lines(): List<String> {
        val row = last ?: return listOf("stabilization  draw to measure")
        val e = row.effect ?: return listOf("stabilization  off")
        val wiggle = if (e.wiggle.isNaN()) "-" else "%+.0f%%".format(e.wiggle * 100.0)
        return listOf(
            "stabilization  S%.0f C%.0f".format(row.strength, row.crossover),
            "moved   avg %.2f  max %.2f mm".format(e.movedMean, e.movedMax),
            "trail   %.2f mm".format(e.trail),
            "wiggle  $wiggle",
        )
    }
}
