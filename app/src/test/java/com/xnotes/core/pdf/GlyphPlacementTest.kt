package com.xnotes.core.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class GlyphPlacementTest {

    /** Where each glyph actually lands when the adjustments are applied, in px. */
    private fun landings(widths: DoubleArray, adj: DoubleArray, sizePx: Double): DoubleArray {
        val unit = sizePx / 1000.0
        var pen = 0.0
        return DoubleArray(widths.size) { i ->
            pen -= adj[i] * unit
            val at = pen
            pen += widths[i] * unit
            at
        }
    }

    @Test
    fun naturalAdvancesNeedNoAdjustment() {
        val widths = doubleArrayOf(500.0, 250.0, 600.0)
        val size = 20.0
        val targets = doubleArrayOf(0.0, 10.0, 15.0)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), GlyphPlacement.adjustments(widths, targets, size), 1e-9)
    }

    @Test
    fun widerGapsPullTheNextGlyphRightWithNegativeNumbers() {
        val widths = doubleArrayOf(500.0, 500.0)
        val adj = GlyphPlacement.adjustments(widths, doubleArrayOf(0.0, 12.0), 20.0)
        assertEquals(0.0, adj[0], 1e-9)
        assertEquals(-100.0, adj[1], 1e-9) // 2 px more than the 10 px advance, at 20 px per em
    }

    @Test
    fun theFirstGlyphMayStartAwayFromTheOrigin() {
        val adj = GlyphPlacement.adjustments(doubleArrayOf(400.0), doubleArrayOf(3.0), 30.0)
        assertEquals(-100.0, adj[0], 1e-9)
    }

    @Test
    fun roundingNeverAccumulatesAlongALine() {
        val n = 400
        val size = 25.0
        val widths = DoubleArray(n) { 577.3 }
        // Hinted advances: every glyph lands on a whole pixel, unlike the font's own width.
        val targets = DoubleArray(n) { it * 14.0 }
        val adj = GlyphPlacement.adjustments(widths, targets, size)
        val at = landings(widths, adj, size)
        val worst = targets.indices.maxOf { abs(at[it] - targets[it]) }
        // One rounding step of 0.1 units at 25 px per em is 0.00125 px; half of it is the bound.
        assertEquals(0.0, worst, 0.000625 + 1e-9)
    }
}
