package com.xnotes.core.stroke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conformance vectors from spec 03 §6. `width` below means `2 × half_width`. */
class StrokeEngineTest {

    private fun width(
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        pressure: Double,
        ty: Double,
    ) = 2.0 * StrokeEngine.halfWidth(baseWidth, pressureEnabled, m, ds, pressure, ty)

    // --- EMA low-pass ---
    @Test fun emaFirstSamplePassesThrough() {
        assertEquals(5.0, StrokeEngine.ema(listOf(5.0, 9.0, 1.0))[0], 1e-12)
    }

    @Test fun emaTwoSamples() {
        assertEquals(listOf(0.0, 5.0), StrokeEngine.ema(listOf(0.0, 10.0)))
    }

    @Test fun emaEmpty() {
        assertEquals(emptyList<Double>(), StrokeEngine.ema(emptyList()))
    }

    // --- Width formula ---
    @Test fun penWidth() {
        // base 3, pressure on, m=0.35, ds=0
        assertEquals(1.05, width(3.0, true, 0.35, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(3.0, width(3.0, true, 0.35, 0.0, 1.0, 0.0), 1e-9)
    }

    @Test fun calligraphyWidth() {
        // base 6, pressure on, m=0.40, ds=0.60
        assertEquals(0.96, width(6.0, true, 0.40, 0.60, 0.0, -1.0), 1e-9) // thinnest
        assertEquals(9.6, width(6.0, true, 0.40, 0.60, 1.0, 1.0), 1e-9)   // thickest
        val ratio = width(6.0, true, 0.40, 0.60, 1.0, 1.0) / width(6.0, true, 0.40, 0.60, 1.0, -1.0)
        assertEquals(4.0, ratio, 1e-9) // direction-only ratio
    }

    @Test fun pressureDisabledIsFullWidth() {
        // base 4, off, m=0.1, ds=0 -> full width regardless of pressure
        assertEquals(4.0, width(4.0, false, 0.1, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(4.0, width(4.0, false, 0.1, 0.0, 1.0, 0.0), 1e-9)
    }

    @Test fun directionFloorClamps() {
        // base 10, off, m=1.0, ds=0.95 -> would-be-negative factor clamps to 0.1
        assertEquals(1.0, width(10.0, false, 1.0, 0.95, 1.0, -1.0), 1e-9)
    }

    // --- Geometry ---
    @Test fun singleSampleIsOneCapNoOutline() {
        val g = StrokeEngine.build(listOf(Sample(10.0, 20.0, 1.0)), 3.0, true, 0.35, 0.0)
        assertTrue(g.outline.isEmpty())
        assertEquals(1, g.caps.size)
        assertEquals(10.0, g.caps[0].center.x, 1e-9)
        assertEquals(20.0, g.caps[0].center.y, 1e-9)
        assertEquals(1.5, g.caps[0].radius, 1e-9) // full-pressure half-width
    }

    @Test fun threeCollinearSamples() {
        val g = StrokeEngine.build(
            listOf(Sample(0.0, 0.0, 1.0), Sample(10.0, 0.0, 1.0), Sample(20.0, 0.0, 1.0)),
            3.0, true, 0.35, 0.0,
        )
        assertEquals(2, g.caps.size)          // head + tail
        assertEquals(6, g.outline.size)       // 3 left edge + 3 right edge
    }

    @Test fun emptyInput() {
        val g = StrokeEngine.build(emptyList(), 3.0, true, 0.35, 0.0)
        assertTrue(g.outline.isEmpty())
        assertTrue(g.caps.isEmpty())
    }

    // --- Taper pen (§1.1) ---
    @Test fun taperPointsTheEnds() {
        // Pressure off, m=1 ⇒ a flat 2.0 half-width everywhere without taper.
        val pts = (0..4).map { Sample(it * 10.0, 0.0, 1.0) }
        val plain = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0)
        val tapered = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperAmount = 1.0)

        assertEquals(2.0, plain.caps[0].radius, 1e-9)   // plain: full dome
        assertEquals(0.0, tapered.caps[0].radius, 1e-9) // tapered: collapses to a point
        assertEquals(0.0, tapered.caps[1].radius, 1e-9)

        val mid = tapered.halfWidths[2]
        assertTrue(mid > tapered.halfWidths[0])         // middle fatter than the tip
        assertTrue(mid > 1.5 && mid <= 2.0)             // and near full width
    }

    @Test fun taperIgnoresVeryShortStrokes() {
        // Total arc length < 8 px ⇒ left un-tapered (a quick tick shouldn't vanish).
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(3.0, 0.0, 1.0))
        val g = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperAmount = 1.0)
        assertEquals(2.0, g.caps[0].radius, 1e-9)
    }

    // --- Speed pen (§1.1) ---
    @Test fun speedThinsFastStrokes() {
        val xs = (0..5).map { it * 10.0 }
        val slow = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 100.0) } // ~0.1 px/ms
        val fast = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 5.0) }   // ~2 px/ms
        val gSlow = StrokeEngine.build(slow, 4.0, false, 1.0, 0.0, speedStrength = 0.8)
        val gFast = StrokeEngine.build(fast, 4.0, false, 1.0, 0.0, speedStrength = 0.8)

        assertEquals(2.0, gSlow.halfWidths.maxOrNull()!!, 1e-6)   // slow: full width
        assertTrue(gFast.halfWidths.maxOrNull()!! < 2.0)          // fast: thinned even at its widest
        assertTrue(gFast.halfWidths.minOrNull()!! < 1.0)          // and clearly thin where fastest
    }

    @Test fun speedOffIgnoresTiming() {
        val xs = (0..5).map { it * 10.0 }
        val slow = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 100.0) }
        val fast = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 5.0) }
        // speedStrength defaults to 0 ⇒ identical geometry regardless of timing.
        val a = StrokeEngine.build(slow, 4.0, false, 1.0, 0.0)
        val b = StrokeEngine.build(fast, 4.0, false, 1.0, 0.0)
        assertEquals(a.halfWidths, b.halfWidths)
    }
}
