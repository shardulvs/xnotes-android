package com.xnotes.core.fill

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.floor
import kotlin.math.max

/**
 * A dropped colour's area: its outline rings in content space (holes by parity), and the cells it
 * covers on the grid it was found on, so other outlines can be measured against it.
 */
class FillArea internal constructor(
    val grid: FillGrid,
    private val x0: Int,
    private val y0: Int,
    private val w: Int,
    private val h: Int,
    private val inside: ByteArray,
    /** Cells the area covers. */
    val cells: Int,
    /** Of those, the cells along its edges. */
    val edge: Int,
    val rings: List<List<Pt>>,
) {
    val isEmpty: Boolean get() = cells == 0 || rings.isEmpty()

    val bounds: Rect get() = Rect.bounding(rings.flatten())

    /** True when content point [at] lies in the area's cells. */
    fun holds(at: Pt): Boolean {
        val x = floor(grid.gx(at.x)).toInt() - x0
        val y = floor(grid.gy(at.y)).toInt() - y0
        return x in 0 until w && y in 0 until h && inside[y * w + x].toInt() != 0
    }

    /** How [rings] (content space, filled by parity) sit against this area, counted in its cells. */
    fun compare(rings: List<List<Pt>>): Overlap {
        val theirsAt = ByteArray(w * h)
        var theirs = 0
        var shared = 0
        var left = Double.MAX_VALUE
        var top = Double.MAX_VALUE
        var right = -Double.MAX_VALUE
        var bottom = -Double.MAX_VALUE
        val local = rings.map { ring ->
            DoubleArray(ring.size * 2).also { out ->
                for ((k, p) in ring.withIndex()) {
                    val gx = grid.gx(p.x) - x0
                    val gy = grid.gy(p.y) - y0
                    out[2 * k] = gx
                    out[2 * k + 1] = gy
                    left = minOf(left, gx)
                    right = maxOf(right, gx)
                    top = minOf(top, gy)
                    bottom = maxOf(bottom, gy)
                }
            }
        }
        CellRaster(w, h).rings(local) { row, from, to ->
            val base = row * w
            for (x in from..to) {
                if (theirsAt[base + x].toInt() != 0) continue
                theirsAt[base + x] = 1
                theirs++
                if (inside[base + x].toInt() != 0) shared++
            }
        }
        var theirEdge = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (theirsAt[i].toInt() == 0) continue
                val boundary = x == 0 || y == 0 || x == w - 1 || y == h - 1 || theirsAt[i - 1].toInt() == 0 ||
                    theirsAt[i + 1].toInt() == 0 || theirsAt[i - w].toInt() == 0 || theirsAt[i + w].toInt() == 0
                if (boundary) theirEdge++
            }
        }
        val clipped = left < 0.0 || top < 0.0 || right > w || bottom > h
        return Overlap(cells, edge, theirs, theirEdge, shared, clipped)
    }
}

/**
 * How two areas sit against each other, in cells: [ours] and [theirs] with the cells along each
 * one's edge, the cells they share, and whether theirs ran past the grid ours was measured on.
 * An area found by flooding meets an outline drawn exactly to within a cell or two along its edge,
 * so every test allows a few cells per edge cell.
 */
class Overlap(
    val ours: Int,
    val ourEdge: Int,
    val theirs: Int,
    val theirEdge: Int,
    val shared: Int,
    val clipped: Boolean,
) {
    /** One area, to within their edges. */
    val same: Boolean get() = !clipped && theirs > 0 && ours + theirs - 2 * shared <= SAME_SLACK * max(ourEdge, theirEdge)

    /** Ours takes in all of theirs, to within their edge. */
    val covers: Boolean get() = !clipped && theirs > 0 && theirs - shared <= EDGE_SLACK * theirEdge

    /** Ours lies inside theirs, to within our edge. */
    val within: Boolean get() = ours > 0 && ours - shared <= EDGE_SLACK * ourEdge

    private companion object {
        const val SAME_SLACK = 3
        const val EDGE_SLACK = 2
    }
}
