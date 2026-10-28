package com.xnotes.core.fill

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Turns geometry in grid units into row spans of cells within [w] x [h]. Lines go in conservatively,
 * every cell a triangle so much as touches, so a hairline can never leave a gap for an area to leak
 * through. Fills go in by cell centre and parity, so an area is measured without bias either way.
 */
internal class CellRaster(private val w: Int, private val h: Int) {

    fun interface Sink {
        fun span(row: Int, from: Int, to: Int)
    }

    private var lo = 0.0
    private var hi = 0.0
    private var xs = DoubleArray(16)

    /** Every cell the triangle touches, row by row. */
    fun triangle(x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, sink: Sink) {
        val top = min(y0, min(y1, y2))
        val bottom = max(y0, max(y1, y2))
        if (bottom < 0.0 || top >= h || !top.isFinite() || !bottom.isFinite()) return
        if (max(x0, max(x1, x2)) < 0.0 || min(x0, min(x1, x2)) >= w) return
        val r0 = max(0, floor(top).toInt())
        val r1 = min(h - 1, max(r0, ceil(bottom).toInt() - 1))
        for (r in r0..r1) {
            val ya = r.toDouble()
            val yb = ya + 1.0
            lo = Double.POSITIVE_INFINITY
            hi = Double.NEGATIVE_INFINITY
            edge(x0, y0, x1, y1, ya, yb)
            edge(x1, y1, x2, y2, ya, yb)
            edge(x2, y2, x0, y0, ya, yb)
            if (lo > hi) continue
            val c0 = max(0, floor(lo).toInt())
            val c1 = min(w - 1, max(floor(lo).toInt(), ceil(hi).toInt() - 1))
            if (c0 <= c1) sink.span(r, c0, c1)
        }
    }

    /** Widen [lo]..[hi] by the part of edge a-b within rows [ya, yb]. */
    private fun edge(ax: Double, ay: Double, bx: Double, by: Double, ya: Double, yb: Double) {
        val up = ay <= by
        val px = if (up) ax else bx
        val py = if (up) ay else by
        val qx = if (up) bx else ax
        val qy = if (up) by else ay
        if (qy < ya || py > yb) return
        if (qy - py < 1e-12) {
            take(px)
            take(qx)
            return
        }
        val t0 = max(ya, py)
        val t1 = min(yb, qy)
        take(px + (qx - px) * (t0 - py) / (qy - py))
        take(px + (qx - px) * (t1 - py) / (qy - py))
    }

    private fun take(x: Double) {
        if (x < lo) lo = x
        if (x > hi) hi = x
    }

    /** Every cell whose centre lies inside [rings] by parity; each ring is x0, y0, x1, y1, ... in grid units. */
    fun rings(rings: List<DoubleArray>, sink: Sink) {
        var top = Double.POSITIVE_INFINITY
        var bottom = Double.NEGATIVE_INFINITY
        for (ring in rings) {
            var i = 1
            while (i < ring.size) {
                top = min(top, ring[i])
                bottom = max(bottom, ring[i])
                i += 2
            }
        }
        if (!top.isFinite() || !bottom.isFinite()) return
        val r0 = max(0, ceil(top - 0.5).toInt())
        val r1 = min(h - 1, floor(bottom - 0.5).toInt())
        for (r in r0..r1) {
            val y = r + 0.5
            var n = 0
            for (ring in rings) {
                val count = ring.size / 2
                if (count < 3) continue
                for (k in 0 until count) {
                    val ax = ring[2 * k]
                    val ay = ring[2 * k + 1]
                    val m = if (k + 1 == count) 0 else k + 1
                    val bx = ring[2 * m]
                    val by = ring[2 * m + 1]
                    if ((ay <= y) == (by <= y)) continue
                    if (n == xs.size) xs = xs.copyOf(n * 2)
                    xs[n++] = ax + (y - ay) * (bx - ax) / (by - ay)
                }
            }
            if (n < 2) continue
            java.util.Arrays.sort(xs, 0, n)
            var k = 0
            while (k + 1 < n) {
                val c0 = max(0, ceil(xs[k] - 0.5).toInt())
                val c1 = min(w - 1, floor(xs[k + 1] - 0.5).toInt())
                if (c0 <= c1) sink.span(r, c0, c1)
                k += 2
            }
        }
    }
}
