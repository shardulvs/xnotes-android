package com.xnotes.core.stroke

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Conformance vectors from spec 03 §6. `width` below means `2 × half_width`. Geometry is stored
 * as packed floats, so assertions on built geometry use 1e-6 (past any render precision); the
 * pure-double helpers keep their exact tolerances.
 */
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

    // --- Smoothing is measured in travel, not in samples ---
    @Test fun smoothedCurveSurvivesPenUpSampleReduction() {
        // A hand-sized curve at the spacing a stylus actually reports. What gets committed is the
        // reduced stroke, so its smoothed centreline has to land on the drawn one's at every
        // sample they share. Under a per-sample low-pass it would not: the lag there is one
        // spacing, so thinning the samples pulls the curve in and the ink visibly changes at
        // pen-up. Here the lag is a distance, and the reduction cannot move it.
        val drawnSamples = ArrayList<Sample>()
        var arc = 0.0
        while (arc <= 120.0) {
            drawnSamples.add(Sample(40.0 * sin(arc / 40.0), 40.0 * (1 - cos(arc / 40.0)), 1.0, arc * 5.0))
            arc += 1.2
        }
        val drawn = StrokeEngine.build(drawnSamples, 3.0, false, 1.0, 0.0)
        val kept = StrokeSimplify.simplify(drawnSamples, drawn.halfWidths, StrokeSimplify.LEGACY_EPS)
        assertTrue("the reduction has to actually drop samples", kept.size < drawnSamples.size)
        val committed = StrokeEngine.build(kept, 3.0, false, 1.0, 0.0)

        var worst = 0.0
        var j = 0
        for (i in drawnSamples.indices) {
            if (j >= kept.size || drawnSamples[i] !== kept[j]) continue
            val dx = drawn.cx(i) - committed.cx(j)
            val dy = drawn.cy(i) - committed.cy(j)
            worst = maxOf(worst, hypot(dx, dy))
            j++
        }
        assertEquals("every kept sample should have been matched", kept.size, j)
        // Measured at 0.008 px; the bound leaves room for tuning without letting a regression past.
        assertTrue("centreline moved $worst content px under reduction", worst < 0.05)
    }

    @Test fun smoothingLagIsADistanceNotASampleCount() {
        // The same straight ramp at two sample densities settles to the same trailing distance.
        val dense = (0..60).map { Sample(it * 1.0, 0.0, 1.0) }
        val sparse = (0..20).map { Sample(it * 3.0, 0.0, 1.0) }
        val gd = StrokeEngine.build(dense, 3.0, false, 1.0, 0.0)
        val gs = StrokeEngine.build(sparse, 3.0, false, 1.0, 0.0)
        // Both end at x = 60; the smoothed tip trails it by SMOOTH_LEN either way.
        assertEquals(60.0 - StrokeEngine.SMOOTH_LEN, gd.cx(gd.pointCount - 1), 1e-6)
        assertEquals(60.0 - StrokeEngine.SMOOTH_LEN, gs.cx(gs.pointCount - 1), 1e-6)
    }

    @Test fun calligraphyNibWidthSurvivesPenUpSampleReduction() {
        // A nib stroke that runs thin, turns through the nib's edge, then runs broad: the shape the
        // thick/thin decision is actually made on, sampled densely enough that the reduction can
        // bite (the gap cap alone floors it at ordinary spacing). Past the head that decision is an
        // integrator over travel, so it moves continuously with its input and the width channel the
        // reducer already reads catches any sample that mattered. What is left is the drift from the
        // reduction moving the curve at all, and on a nib spanning 1.7 to 4.3 that is 0.006 px.
        val ds = 0.6
        val step = 0.3
        val pts = (0..160).map { Sample(it * step, -it * step, 1.0) } +
            (1..160).map { Sample(48.0 + it * step, -48.0 + it * step, 1.0) }
        val drawn = StrokeEngine.build(pts, 6.0, false, 1.0, ds)
        val kept = StrokeSimplify.simplify(
            pts, drawn.halfWidths, StrokeSimplify.LEGACY_EPS, 1.0, ds,
        )
        assertTrue("the reduction has to actually drop samples", kept.size < pts.size)
        val committed = StrokeEngine.build(kept, 6.0, false, 1.0, ds)

        var worst = 0.0
        var j = 0
        for (i in pts.indices) {
            if (j >= kept.size || pts[i] !== kept[j]) continue
            worst = maxOf(worst, abs(drawn.hw(i) - committed.hw(j)))
            j++
        }
        assertEquals("every kept sample should have been matched", kept.size, j)
        // Measured at 0.006 px; the bound leaves room for tuning without letting a regression past.
        assertEquals("nib half-width moved under reduction", 0.0, worst, 0.02)
    }

    @Test fun calligraphyHeadSurvivesPenUpSampleReduction() {
        // The head is the one part of the nib the width channel cannot protect: it is the chord
        // across the stroke's first HEAD_LEN, pinned flat over the whole window, and a run that is
        // flat by construction has no width deviation for the reducer to notice. Drop the sample the
        // chord ends on and it ends on a different one, at a different arc. A gently rotating dense
        // stroke, where that sample sits mid-window with nothing else keeping it: unguarded the
        // reduction drops 33 of the 43 samples in the window and the rebuilt head lands off.
        val ds = 0.6
        val pts = ArrayList<Sample>()
        var x = 0.0
        var y = 0.0
        var arc = 0.0
        while (arc <= 60.0) {
            pts.add(Sample(x, y, 1.0, arc))
            val angle = 1.0 - arc / 30.0
            x += 0.3 * cos(angle)
            y += 0.3 * sin(angle)
            arc += 0.3
        }
        val drawn = StrokeEngine.build(pts, 6.0, false, 1.0, ds)
        val kept = StrokeSimplify.simplify(pts, drawn.halfWidths, StrokeSimplify.LEGACY_EPS, 1.0, ds)
        assertTrue("the reduction has to actually drop samples", kept.size < pts.size)
        val rebuilt = StrokeEngine.build(kept, 6.0, false, 1.0, ds)
        for (i in 0 until 6) {
            assertEquals("head half-width at $i", drawn.hw(i), rebuilt.hw(i), 1e-6)
        }
    }

    @Test fun theSameGestureAtAnyZoomIsAScaledCopy() {
        // Drawing at 4x lays the same hand movement onto a quarter as much page, so the ink has to
        // come out a quarter the size and otherwise identical. It did not: the nib's arc constants
        // were quoted in page pixels, so at 4x the pen had to be dragged four times as far across the
        // glass before it would thicken. Every one of them now scales with the draw zoom, and this
        // pins the whole width pipeline to it, head window and widening rate included.
        val ds = 0.6
        val k = 0.25
        // A thin lead-in across the nib's edge, then a long broad downstroke.
        val gesture = (0..14).map { Sample(it * 1.4, -it * 1.4, 1.0) } +
            (1..30).map { Sample(19.6 + it * 1.4, -19.6 + it * 1.4, 1.0) }
        val full = StrokeEngine.build(gesture, 6.0, false, 1.0, ds)
        val zoomed = StrokeEngine.build(
            gesture.map { Sample(it.x * k, it.y * k, it.pressure, it.t) },
            6.0 * k, false, 1.0, ds, smoothScale = k,
        )
        assertEquals(full.pointCount, zoomed.pointCount)
        for (i in 0 until full.pointCount) {
            assertEquals("half-width at $i", full.hw(i) * k, zoomed.hw(i), 1e-6)
            assertEquals("x at $i", full.cx(i) * k, zoomed.cx(i), 1e-6)
            assertEquals("y at $i", full.cy(i) * k, zoomed.cy(i), 1e-6)
        }
        // And the stroke really does thicken, so the check is not passing on two thin ribbons.
        assertTrue(full.halfWidths.max() > 1.8f * full.halfWidths.min())

        // The taper pen's floor is the same kind of length: a 12 px flick tapers, and at 4x it is a
        // 3 px flick that still has to, since the hand did the same thing.
        val tick = (0..8).map { Sample(it * 1.5, 0.0, 1.0) }
        val tickFull = StrokeEngine.build(tick, 4.0, false, 1.0, 0.0, taperEnabled = true)
        val tickZoomed = StrokeEngine.build(
            tick.map { Sample(it.x * k, it.y * k, it.pressure, it.t) },
            4.0 * k, false, 1.0, 0.0, taperEnabled = true, smoothScale = k,
        )
        assertTrue("the flick has to actually taper", tickFull.hw(8) < 0.5f * tickFull.hw(0))
        for (i in 0 until tickFull.pointCount) {
            assertEquals("taper half-width at $i", tickFull.hw(i) * k, tickZoomed.hw(i), 1e-6)
        }
    }

    @Test fun theReductionDropsTheSameSamplesAtAnyZoom() {
        // The reduction's own arcs are lengths of hand too. Left in page pixels, drawing at 4x let it
        // open a gap four times as wide on screen as the one it opens at 100%, and protect a corner
        // over a quarter as much of it.
        val k = 0.25
        val pts = (0..300).map { Sample(it * 0.5, 18.0 * sin(it * 0.5 / 13.0), 1.0) }
        val full = StrokeEngine.build(pts, 3.0, false, 1.0, 0.0)
        val keptFull = StrokeSimplify.simplify(pts, full.halfWidths, 0.2, 1.0, 0.0)
        assertTrue("the reduction has to actually drop samples", keptFull.size < pts.size)

        val scaled = pts.map { Sample(it.x * k, it.y * k, it.pressure, it.t) }
        val zoomed = StrokeEngine.build(scaled, 3.0 * k, false, 1.0, 0.0, smoothScale = k)
        val keptZoomed = StrokeSimplify.simplify(scaled, zoomed.halfWidths, 0.2 * k, k, 0.0)
        assertEquals("the same gesture must reduce to the same samples", keptFull.size, keptZoomed.size)
        for (j in keptFull.indices) {
            assertEquals("kept sample $j", keptFull[j].x * k, keptZoomed[j].x, 1e-9)
        }
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
    @Test fun singleSampleIsOneDiscNoOutline() {
        val g = StrokeEngine.build(listOf(Sample(10.0, 20.0, 1.0)), 3.0, true, 0.35, 0.0)
        assertTrue(g.outline.isEmpty())
        assertEquals(1, g.pointCount)
        assertEquals(10.0, g.cx(0), 1e-6)
        assertEquals(20.0, g.cy(0), 1e-6)
        assertEquals(1.5, g.hw(0), 1e-6) // full-pressure half-width = the swept dot's radius
    }

    @Test fun threeCollinearSamples() {
        val g = StrokeEngine.build(
            listOf(Sample(0.0, 0.0, 1.0), Sample(10.0, 0.0, 1.0), Sample(20.0, 0.0, 1.0)),
            3.0, true, 0.35, 0.0,
        )
        assertEquals(6, g.outlineCount)       // 3 left edge + 3 right edge
        assertEquals(3, g.pointCount)
    }

    private fun pen(vararg pts: Pair<Double, Double>): StrokeGeometry =
        StrokeEngine.build(pts.map { Sample(it.first, it.second, 1.0) }, 3.0, true, 0.35, 0.0, holdEnds = true)

    @Test fun theOutlineIsTheLeftRailThenTheRightReversed() {
        val g = pen(*Array(5) { (it * 4.0) to 0.0 })
        val n = g.pointCount
        for (i in 0 until n) {
            assertEquals(g.outline[2 * i].toDouble(), g.leftX(i), 0.0)
            assertEquals(g.outline[2 * i + 1].toDouble(), g.leftY(i), 0.0)
            assertEquals(g.outline[2 * (2 * n - 1 - i)].toDouble(), g.rightX(i), 0.0)
        }
    }

    @Test fun theRailsStraddleTheCentrelineByTheHalfWidth() {
        val g = pen(*Array(6) { (it * 4.0) to 0.0 })
        for (i in 0 until g.pointCount) {
            assertEquals("left rail off the centreline", g.hw(i), hypot(g.leftX(i) - g.cx(i), g.leftY(i) - g.cy(i)), 1e-4)
            assertEquals("right rail off the centreline", g.hw(i), hypot(g.rightX(i) - g.cx(i), g.rightY(i) - g.cy(i)), 1e-4)
            assertEquals(g.cx(i), (g.leftX(i) + g.rightX(i)) / 2.0, 1e-4)
            assertEquals(g.cy(i), (g.leftY(i) + g.rightY(i)) / 2.0, 1e-4)
        }
    }

    @Test fun theRailsAreOnOppositeSidesEverywhere() {
        val g = pen(0.0 to 0.0, 10.0 to 3.0, 18.0 to 14.0, 22.0 to 30.0)
        for (i in 0 until g.pointCount) {
            val lx = g.leftX(i) - g.cx(i)
            val ly = g.leftY(i) - g.cy(i)
            val rx = g.rightX(i) - g.cx(i)
            val ry = g.rightY(i) - g.cy(i)
            assertTrue("rails on the same side at $i", lx * rx + ly * ry < 0.0)
        }
    }

    @Test fun emptyInput() {
        val g = StrokeEngine.build(emptyList(), 3.0, true, 0.35, 0.0)
        assertTrue(g.outline.isEmpty())
        assertTrue(g.centerline.isEmpty())
    }

    // --- Taper pen (§1.1) ---
    @Test fun taperSpansTheWholeStrokeFromHeadToTip() {
        // The taper eases across the entire stroke now: full width at the head, down to the tip at
        // the end (a point when tip width is 0). Pressure off, m=1 ⇒ a flat 2.0 before the taper.
        val pts = (0..10).map { Sample(it * 10.0, 0.0, 1.0) }  // total 100 px
        val plain = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0)
        val tapered = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperEnabled = true, smooth = false)

        assertEquals(2.0, plain.hw(0), 1e-6)
        assertEquals(2.0, tapered.hw(0), 1e-6)   // head: full width
        assertEquals(1.0, tapered.hw(5), 1e-6)   // halfway: edge 0.5 ⇒ half of full
        assertEquals(0.0, tapered.hw(10), 1e-6)  // tail: a point

        // Width eases monotonically from the head all the way to the tip.
        for (i in 0 until tapered.pointCount - 1) assertTrue(tapered.hw(i) > tapered.hw(i + 1))
    }

    @Test fun taperIgnoresVeryShortStrokes() {
        // Total arc length < 8 px ⇒ left un-tapered (a quick tick shouldn't vanish).
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(3.0, 0.0, 1.0))
        val g = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperEnabled = true)
        assertEquals(2.0, g.hw(0), 1e-6)
    }

    @Test fun taperProfileScalesWithStrokeLength() {
        // The taper spans the whole stroke, so two different-length strokes share the same width
        // at the same fractional position (it is proportional now, not a fixed arc length).
        val a = (0..10).map { Sample(it * 10.0, 0.0, 1.0) }  // total 100
        val b = (0..20).map { Sample(it * 10.0, 0.0, 1.0) }  // total 200
        val ga = StrokeEngine.build(a, 4.0, false, 1.0, 0.0, taperEnabled = true, smooth = false)
        val gb = StrokeEngine.build(b, 4.0, false, 1.0, 0.0, taperEnabled = true, smooth = false)
        assertEquals(ga.hw(5), gb.hw(10), 1e-9)  // both 50% along the stroke
        assertEquals(ga.hw(0), gb.hw(0), 1e-9)   // both full at the head
        assertEquals(ga.hw(10), gb.hw(20), 1e-9) // both a point at the tail
    }

    @Test fun taperBottomsOutAtTheTipWidthFloor() {
        // A non-zero tip width stops the tail at that fraction of full width instead of a point.
        val pts = (0..10).map { Sample(it * 10.0, 0.0, 1.0) } // total 100 px
        val g = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperEnabled = true, taperMinFactor = 0.25, smooth = false)
        assertEquals(2.0, g.hw(0), 1e-6)    // head: full width
        assertEquals(0.5, g.hw(10), 1e-6)   // tail: 0.25 of the 2.0 full half-width
        assertEquals(1.25, g.hw(5), 1e-6)   // halfway: 0.25 + 0.75·0.5 of full
    }

    // --- Speed pen (§1.1) ---
    @Test fun speedThinsFastStrokes() {
        val xs = (0..5).map { it * 10.0 }
        val slow = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 100.0) } // ~0.1 px/ms
        val fast = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 5.0) }   // ~2 px/ms
        val gSlow = StrokeEngine.build(slow, 4.0, false, 1.0, 0.0, speedStrength = 0.8)
        val gFast = StrokeEngine.build(fast, 4.0, false, 1.0, 0.0, speedStrength = 0.8)

        assertTrue(gSlow.halfWidths.max() > 1.8)          // slow: near full width
        assertTrue(gFast.halfWidths.max() < 2.0)          // fast: thinned even at its widest
        assertTrue(gFast.halfWidths.min() < 1.0)          // and clearly thin where fastest
    }

    @Test fun speedOffIgnoresTiming() {
        val xs = (0..5).map { it * 10.0 }
        val slow = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 100.0) }
        val fast = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 5.0) }
        // speedStrength defaults to 0 ⇒ identical geometry regardless of timing.
        val a = StrokeEngine.build(slow, 4.0, false, 1.0, 0.0)
        val b = StrokeEngine.build(fast, 4.0, false, 1.0, 0.0)
        assertArrayEquals(a.halfWidths, b.halfWidths, 0f)
    }

    @Test fun speedScaleScalesPerceivedSpeed() {
        // Same gesture and timing; a larger content->dp scale (e.g. zoomed in) reads as
        // faster, so the line thins more than the unscaled one (which stays near full width).
        val pts = (0..5).map { Sample(it * 10.0, 0.0, 1.0, it * 100.0) }
        val unscaled = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = 1.0)
        val scaled = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = 5.0)
        assertTrue(unscaled.halfWidths.max() > 1.8)
        assertTrue(scaled.halfWidths.max() < unscaled.halfWidths.max())
    }

    @Test fun speedKeepsAnEvenWidthThroughAConstantSpeedCorner() {
        // Right then up at a steady sample rate: the nib's path length per unit time never
        // changes, so a speed-thinned line must keep an even width through the corner. Reading the
        // raw sample motion over a time window (not the corner-cutting smoothed centerline, nor a
        // sample-count window that collapses onto the bunched corner) is what keeps the corner from
        // reading a false slow-down and ballooning into a blob. speedScale puts it mid-band so any
        // dip would show.
        val right = (0..10).map { Sample(it * 8.0, 0.0, 1.0, it * 8.0) }
        val up = (1..10).map { Sample(80.0, -it * 8.0, 1.0, (10 + it) * 8.0) }
        val g = StrokeEngine.build(right + up, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = 0.5)
        val maxHw = g.halfWidths.max().toDouble()
        val minHw = g.halfWidths.min().toDouble()
        assertTrue("partially thinned, not saturated", maxHw < 1.9 && minHw > 0.5)
        assertEquals("even width through the corner", maxHw, minHw, 1e-5)
    }

    private val pxPerMm = 249.0 / 25.4
    private val handScale = 160.0 / 280.0

    private fun minJerk(u: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        return t * t * t * (10 - 15 * t + 6 * t * t)
    }

    /** A hand's 'V': two 10 mm min-jerk legs of 150 ms, through a 240 Hz pen, whole-ms times and a 1 px gate. */
    private fun handV(pauseMs: Double = 0.0, restMs: Double = 0.0): List<Sample> {
        val leg = 150.0
        val dx = 5.0 * pxPerMm
        val dy = 8.66 * pxPerMm
        val total = restMs + leg + pauseMs + leg
        fun at(t: Double): Pair<Double, Double> {
            val u = t - restMs
            return when {
                u <= 0.0 -> 0.0 to 0.0
                u < leg -> minJerk(u / leg).let { dx * it to dy * it }
                u < leg + pauseMs -> dx to dy
                else -> minJerk((u - leg - pauseMs) / leg).let { dx + dx * it to dy - dy * it }
            }
        }
        val out = ArrayList<Sample>()
        var k = 0
        while (k * 1000.0 / 240.0 <= total) {
            val t = k * 1000.0 / 240.0
            val (x, y) = at(t)
            val last = out.lastOrNull()
            if (last == null || abs(last.x - x) + abs(last.y - y) >= 1.0) out.add(Sample(x, y, 1.0, kotlin.math.floor(t)))
            k++
        }
        val (ex, ey) = at(total)
        out.add(Sample(ex, ey, 1.0, kotlin.math.floor(total)))
        return out
    }

    /** The steepest half-width change per px of centreline; 1 or more means a disc swallows its neighbour. */
    private fun steepest(g: StrokeGeometry): Double {
        var worst = 0.0
        for (i in 0 until g.pointCount - 1) {
            val d = hypot(g.cx(i + 1) - g.cx(i), g.cy(i + 1) - g.cy(i))
            val dr = abs(g.hw(i + 1) - g.hw(i))
            worst = maxOf(worst, if (d > 1e-9) dr / d else if (dr > 1e-9) Double.MAX_VALUE else 0.0)
        }
        return worst
    }

    private fun speedV(w: Double, pauseMs: Double = 0.0, restMs: Double = 0.0) =
        StrokeEngine.build(handV(pauseMs, restMs), w, false, 1.0, 0.0, speedStrength = 0.8, speedScale = handScale)

    @Test fun speedEdgesStayGentleAtEveryWidth() {
        for (w in listOf(4.0, 10.0, 20.0, 40.0)) for (pause in listOf(0.0, 80.0)) {
            val edge = steepest(speedV(w, pause))
            assertTrue("width $w, pause $pause: steepest edge $edge", edge < 0.5)
        }
    }

    @Test fun holdingStillNeitherWidensNorThins() {
        val plain = speedV(20.0)
        val corner = plain.halfWidths.max().toDouble()
        val paused = speedV(20.0, pauseMs = 300.0).halfWidths.max().toDouble()
        assertEquals("a paused corner", corner, paused, 0.05 * corner)
        assertEquals("a rest at pen-down", plain.hw(0), speedV(20.0, restMs = 300.0).hw(0), 0.05 * plain.hw(0))
    }

    @Test fun storedSpeedWidthsAreWhatTheEngineApplies() {
        val s = handV()
        val xs = DoubleArray(s.size) { s[it].x }
        val ys = DoubleArray(s.size) { s[it].y }
        val ts = DoubleArray(s.size) { s[it].t }
        val f = StrokeEngine.speedWidths(
            xs, ys, ts, 20.0, 0.8, StrokeEngine.SPEED_HI, StrokeEngine.DEFAULT_SPEED_SMOOTHING, handScale, true, 1.0,
        )
        val kept = s.mapIndexed { i, it -> it.copy(widthFactor = f[i]) }
        val stored = StrokeEngine.build(kept, 20.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = handScale)
        assertArrayEquals(speedV(20.0).halfWidths, stored.halfWidths, 0f)
    }

    @Test fun topSpeedSetsWhereTheLineIsThinnest() {
        val pts = (0..40).map { Sample(it * 2.0, 0.0, 1.0, it * 4.0) } // a steady 0.5 dp/ms
        val partial = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedTop = 0.6)
        val full = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedTop = 0.4)
        assertEquals(0.4, full.hw(20).toDouble(), 1e-6)
        assertTrue("short of the top speed it is only part thinned", partial.hw(20) > 0.5)
    }

    // --- Direction smoothing & end-width hold ---
    @Test fun calligraphyRibbonIsThinnedByDirection() {
        // An upward calligraphy ribbon is thinned by the direction term; the swept disc rounds its
        // ends regardless, so only the half-widths are interesting here.
        val pts = (0..6).map { Sample(0.0, -it * 10.0, 1.0) } // straight up
        val g = StrokeEngine.build(pts, 6.0, true, 0.40, 0.60)
        assertTrue("upward calligraphy ribbon is thinned by the direction term",
            g.hw(0) < 1.5)
    }

    @Test fun calligraphyStrayLiftOffSampleDoesNotSwellTheEnd() {
        // Travel in the thin (nib-edge) direction, then a single stray sample jumps the other way as
        // the pen lifts. The limiter alone would let that widen the end, since a rate bounds how fast
        // the width moves and cannot delete a short run: 3 px of an 8 px OPEN_LEN buys 3/8 of the
        // direction channel, which is 1.35 px of half-width against a 1.2 px body. The tail rule is
        // what stops it. Over the last HEAD_LEN the nib may only thin, so the skid gets nothing.
        val ds = 0.6
        val up = (0..20).map { Sample(0.0, -it * 4.0, 1.0) }   // travel -y: thin (1 - ds)
        val stray = Sample(0.0, -20 * 4.0 + 3.0, 1.0)          // one sample back down (+y): thick
        val g = StrokeEngine.build(up + stray, 6.0, false, 1.0, ds, smooth = false)
        var body = 0.0
        for (i in 0 until g.pointCount - 1) if (g.hw(i) > body) body = g.hw(i)
        assertEquals("the thin body is untouched", 3.0 * (1.0 - ds), body, 1e-6)
        assertTrue("a stray lift-off sample must not swell past the body width",
            g.hw(g.pointCount - 1) <= body + 1e-6)
    }

    @Test fun calligraphyTailRuleSurvivesPenUpSampleReduction() {
        // The reducer runs straight after the tail rule, on a stroke whose tail it has just flattened.
        // A flat channel is what the reducer drops most freely, so this is the same shape of risk the
        // head guard covers. It needs no guard of its own: the ceiling is a minimum over the window
        // and every sample in the window carries it, while the skid itself is inside END_KEEP.
        val ds = 0.6
        val up = (0..200).map { Sample(0.0, -it * 0.3, 1.0) }
        val stray = Sample(0.0, -200 * 0.3 + 3.0, 1.0)
        val pts = up + stray
        val drawn = StrokeEngine.build(pts, 6.0, false, 1.0, ds)
        val kept = StrokeSimplify.simplify(pts, drawn.halfWidths, StrokeSimplify.LEGACY_EPS, 1.0, ds)
        assertTrue("the reduction has to actually drop samples", kept.size < pts.size)
        val committed = StrokeEngine.build(kept, 6.0, false, 1.0, ds)
        val body = 3.0 * (1.0 - ds)
        assertTrue("the drawn end is held to the body width", drawn.hw(drawn.pointCount - 1) <= body + 1e-6)
        assertTrue("and so is the committed one",
            committed.hw(committed.pointCount - 1) <= body + 1e-6)
    }

    @Test fun calligraphyLiftOffSwellIsOnlyTakenBackAtPenUp() {
        // The tail rule is a lift-time rule, and it has to be. While the pen is down the last sample
        // is the pen itself, so capping it would stop the ink widening at all: every downstroke would
        // draw thin under its own tip. Mid-draw the same stray move is a real heading and widens.
        val ds = 0.6
        val up = (0..20).map { Sample(0.0, -it * 4.0, 1.0) }
        val stray = Sample(0.0, -20 * 4.0 + 3.0, 1.0)
        val g = StrokeEngine.build(up + stray, 6.0, false, 1.0, ds, smooth = false, finished = false)
        // Its own travel and no more: 3 px of OPEN_LEN, times (baseWidth / 2) · ds.
        val earned = (6.0 / 2.0) * ds * (3.0 * 2.0 / StrokeEngine.OPEN_LEN)
        assertEquals(3.0 * (1.0 - ds) + earned, g.hw(g.pointCount - 1), 1e-6)
    }

    @Test fun calligraphyTailRuleLeavesAThinningEndAlone() {
        // The tail caps widening, it does not flatten. A stroke that curves out of the broad face on
        // its way to the pen-up still thins across the tail at the CLOSE_LEN rate, one value per
        // sample, instead of collapsing to the thinnest heading in the window.
        val ds = 0.6
        val down = (0..19).map { Sample(0.0, it * 1.5, 1.0) }            // +y: broad
        val out = (1..2).map { Sample(it * 1.5, 28.5, 1.0) }             // +x: mid
        val g = StrokeEngine.build(down + out, 6.0, false, 1.0, ds, smooth = false)
        val last = g.pointCount - 1
        assertEquals("the end settles at the mid width", 3.0, g.hw(last), 1e-6)
        assertTrue("the sample before it is still broader", g.hw(last - 1) > 3.5)
        assertTrue("and the one before that broader still", g.hw(last - 2) > g.hw(last - 1))
        // The window reaches 5 px back into the downstroke, and every bit of that stays broad: the
        // ceiling opens at the width the stroke already had, so only the widening is capped.
        assertEquals("nothing already earned is taken back", 3.0 * (1.0 + ds), g.hw(last - 3), 1e-6)
    }

    @Test fun calligraphyStrayPenDownSampleDoesNotSwellTheStart() {
        // A stray first move in the broad (thick) direction at pen-down, then the real stroke travels
        // thin. This is what the head rule is for: the window is read as one chord, so the lone thick
        // pen-down move only displaces its far end by its own 3 px and the run that follows wins the
        // net. The first half-width stays at the thin body width instead of opening with a fat dot.
        val ds = 0.6
        val stray = Sample(0.0, -3.0, 1.0)                     // first move jumps +y: thick
        val up = (0..20).map { Sample(0.0, -it * 4.0, 1.0) }   // then travel -y: thin (1 - ds)
        val g = StrokeEngine.build(listOf(stray) + up, 6.0, false, 1.0, ds, smooth = false)
        var body = 0.0
        for (i in 1 until g.pointCount) if (g.hw(i) > body) body = g.hw(i)
        assertTrue("a stray pen-down sample must not swell past the body width",
            g.hw(0) <= body + 1e-6)
    }

    @Test fun calligraphySustainedThickStrokeReachesFullWidth() {
        // The limiter only bounds how fast the width may change, it does not cap the width: a long
        // stroke in the broad direction still reaches full thick width. Here it opens thick too,
        // since the head window's own heading is broad all the way through.
        val ds = 0.6
        val pts = (0..40).map { Sample(0.0, it * 4.0, 1.0) }   // travel +y (thick) for 160 px
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, ds, smooth = false)
        assertEquals(3.0 * (1.0 + ds), g.hw(g.pointCount - 1), 1e-6) // half = 3 · direction, thick = 1 + ds
        assertEquals("a stroke that starts broad opens broad", 3.0 * (1.0 + ds), g.hw(0), 1e-6)
    }

    @Test fun calligraphyThickeningSwellsInOverOpenLen() {
        // A mid (horizontal) lead-in, then a long sustained turn into the broad (thick, +y) face.
        // The lead-in is not rewritten once the downstroke proves itself, which is what the old
        // opening's dilation did: the width swells in from the corner at the OPEN_LEN rate instead,
        // one quarter of the channel per px here, and tops out 4 px past it.
        val horizontal = (0..8).map { Sample(it.toDouble(), 0.0, 1.0) }   // travel +x: mid
        val down = (1..40).map { Sample(8.0, it.toDouble(), 1.0) }        // travel +y: thick
        val g = StrokeEngine.build(horizontal + down, 6.0, false, 1.0, 0.6, smooth = false)
        assertEquals("the lead-in keeps its own mid width", 3.0, g.hw(7), 1e-6)
        assertEquals("one px past the corner: a quarter of the way over", 3.45, g.hw(9), 1e-6)
        assertEquals("full thick within OPEN_LEN of the corner", 4.8, g.hw(12), 1e-6)
    }

    @Test fun highlighterEndsHeldToBodyWidth() {
        // The highlighter holds its ends, so the swept end discs round the line at full body width;
        // with pressure off every half-width is already the full 8.0.
        val pts = (0..4).map { Sample(it * 10.0, 0.0, 1.0) }
        val g = StrokeEngine.build(pts, 16.0, false, 1.0, 0.0, holdEnds = true)
        assertEquals(8.0, g.hw(0), 1e-6) // 16 × 1.0 / 2
        assertEquals(8.0, g.hw(g.pointCount - 1), 1e-6)
    }

    @Test fun penEndsHeldToBodyWidth() {
        // The regular pen holds its ends to the body half-width (ds = 0 ⇒ the pure-pressure value),
        // so the swept end discs round the line at full width.
        val pts = (0..4).map { Sample(it * 10.0, 0.0, 1.0) }
        val g = StrokeEngine.build(pts, 3.0, true, 0.35, 0.0, holdEnds = true)
        assertEquals(1.5, g.hw(0), 1e-6) // 3.0 × 1.0 / 2
        assertEquals(1.5, g.hw(g.pointCount - 1), 1e-6)
    }

    @Test fun penEndsDoNotPinchAtLightPenDownAndUp() {
        // Pen-down and pen-up samples arrive light; without the end-width hold the end disc would
        // shrink to ~0.62 (the 0.1-pressure tip) against a ~1.5 body. The hold lifts the ends to the
        // settled body pressure so each end disc meets the line at nearly full width (no pinch), and
        // never overshoots it (no bulge past the ribbon).
        val pts = (0..11).map { i -> Sample(i * 10.0, 0.0, if (i == 0 || i == 11) 0.1 else 1.0) }
        val g = StrokeEngine.build(pts, 3.0, true, 0.35, 0.0, holdEnds = true)
        val body = g.halfWidths.max().toDouble()
        assertTrue("head should not pinch to the light tip", g.hw(0) > 1.4)
        assertTrue("tail should not pinch to the light tip", g.hw(g.pointCount - 1) > 1.4)
        assertTrue("ends never exceed the body width", g.hw(0) <= body + 1e-6)
        assertTrue(g.hw(g.pointCount - 1) <= body + 1e-6)
    }

    @Test fun penHoldOnlyRaisesTheEndsNeverThinsTheMiddle() {
        // The hold only lifts the end samples up to the inner body width; a mid-stroke pressure dip
        // is left alone, so the ribbon still narrows in the middle where the pen was pressed lighter.
        val pts = (0..11).map { i -> Sample(i * 10.0, 0.0, if (i in 5..6) 0.2 else 1.0) }
        val g = StrokeEngine.build(pts, 3.0, true, 0.35, 0.0, holdEnds = true)
        assertTrue("ends held to the body width", g.hw(0) > 1.4 && g.hw(g.pointCount - 1) > 1.4)
        assertTrue("mid-stroke dip survives", g.halfWidths.min() < 1.2)
    }

    @Test fun calligraphyDotTakesTheDotWidth() {
        // A finished stroke too short to fill the head window (4 px, under HEAD_LEN) never gives the
        // head anything to measure, so it is a tap: every half-width is 3 · (1 + ds · DOT_DIR_Y),
        // slightly past the broad face, not the near-invisible thin extreme an unfilled window would
        // otherwise pin it to.
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(2.0, 0.0, 1.0), Sample(4.0, 0.0, 1.0))
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, 0.6, smooth = false)
        for (i in 0 until g.pointCount) assertEquals(3.0 * 1.9, g.hw(i), 1e-6)
    }

    @Test fun calligraphyDotStaysThinWhileThePenIsDown() {
        // The same short stroke mid-draw (finished = false) keeps the thin width: with the window
        // unfilled there is no head yet, so it draws the safe value and the live preview never opens
        // thick at pen-down. It is rewritten once, when the window fills.
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(2.0, 0.0, 1.0), Sample(4.0, 0.0, 1.0))
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, 0.6, smooth = false, finished = false)
        for (i in 0 until g.pointCount) assertEquals(3.0 * 0.4, g.hw(i), 1e-6)
    }

    @Test fun calligraphySingleSampleDotTakesTheDotWidth() {
        // A single-sample calligraphy tap is the extreme dot: its one swept disc takes the dot
        // width too, instead of the mid (ty = 0) width.
        val g = StrokeEngine.build(listOf(Sample(0.0, 0.0, 1.0)), 6.0, false, 1.0, 0.6)
        assertEquals(3.0 * 1.9, g.hw(0), 1e-6)
    }

    @Test fun calligraphyShortTickPastTheDotLengthStaysThin() {
        // Past HEAD_LEN the tap rule no longer applies: a 12 px upward tick fills the head window, so
        // the head reads the thin heading actually travelled and the whole tick is thin. There is no
        // band between the two rules any more, since the dot threshold is the head window itself.
        val pts = (0..4).map { Sample(0.0, -it * 3.0, 1.0) }
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, 0.6, smooth = false)
        for (i in 0 until g.pointCount) assertEquals(3.0 * 0.4, g.hw(i), 1e-6)
    }

    @Test fun calligraphyWidthGlidesAcrossADirectionChange() {
        // An L-stroke turning from a long rightward run (mid width) into a long upward run (thin):
        // the width eases down over several samples instead of snapping the instant the tangent
        // flips. What paces it is CLOSE_LEN, a travel rather than a filter, so each sample may move
        // the width by exactly its own share of the range and no more. At 1.5 px spacing that is
        // 3/8 of the 2.0 channel per sample, three samples to cross a right-angle turn.
        val ds = 0.6
        val pts = (0..19).map { Sample(it * 1.5, 0.0, 1.0) } +
            (1..19).map { Sample(28.5, -it * 1.5, 1.0) }
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, ds, smooth = false)
        val corner = 19 // the corner vertex; the tangent starts turning here
        val settled = g.hw(g.pointCount - 1)
        val cap = 3.0 * ds * (1.5 * 2.0 / StrokeEngine.CLOSE_LEN) // half-width one sample may spend
        assertEquals("the run into the corner is at the mid width", 3.0, g.hw(corner - 1), 1e-6)
        assertEquals("thinning starts at the corner and is capped", 3.0 - cap, g.hw(corner), 1e-6)
        assertEquals("still mid-transition a sample past it", 3.0 - 2 * cap, g.hw(corner + 1), 1e-6)
        assertEquals("thin two samples past it", settled, g.hw(corner + 2), 1e-6)
        assertTrue("ends thinner than it started", settled < g.hw(0))
    }

    @Test fun calligraphyHeadIgnoresAPenDownFlick() {
        // The case the head rule exists for. The pen lands with a 4 px downward flick and the writer
        // then draws 40 px upward: the flick is the broad face and the upstroke is the thin one, and
        // no causal rule can tell them apart at pen-down, because at pen-down only the flick has
        // happened. The head is the chord across the whole first HEAD_LEN, so the flick and the
        // upstroke that shares its window net out upward and the stroke opens thin, with no blob.
        // The chord's limit, and the price of its simplicity: measured on a vertical flick the net
        // stays upward up to about 4 px of flick and turns over above 5, half the window either way.
        val ds = 0.6
        val flick = (0..3).map { Sample(0.0, it * 1.0, 1.0) }       // +y: the broad face
        val up = (1..40).map { Sample(0.0, 3.0 - it * 1.0, 1.0) }   // -y: what was meant
        val g = StrokeEngine.build(flick + up, 6.0, false, 1.0, ds, smooth = false)
        val thin = 3.0 * (1.0 - ds)
        for (i in 0 until 9) assertEquals("head half-width at $i", thin, g.hw(i), 1e-6)
        assertEquals("nothing anywhere on the stroke is broader", thin, g.halfWidths.max().toDouble(), 1e-6)
    }

    @Test fun calligraphyWidthNeedsOpenLenToCrossItsRange() {
        // The rate itself: a thin diagonal run turning into a broad one, both at 45°, so the heading
        // moves 1.414 of the direction channel's 2.0 span. OPEN_LEN carries the whole span, so this
        // crossing takes 1.414/2 of it, 5.66 px of travel, and every sample in between moves by
        // exactly its own share.
        val ds = 0.6
        val thin = (0..30).map { Sample(it * 1.0, -it * 1.0, 1.0) }
        val broad = (1..40).map { Sample(30.0 + it * 1.0, -30.0 + it * 1.0, 1.0) }
        val pts = thin + broad
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, ds, smooth = false)
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        val thinHw = g.hw(10)
        val broadHw = g.hw(60)
        var from = -1.0
        var to = -1.0
        for (i in pts.indices) {
            if (from < 0 && g.hw(i) > thinHw + 1e-6) from = cum[i - 1]
            if (to < 0 && g.hw(i) >= broadHw - 1e-6 && from >= 0) to = cum[i]
        }
        val span = StrokeEngine.OPEN_LEN * (broadHw - thinHw) / (3.0 * ds * 2.0)
        assertEquals("the crossing is paced by OPEN_LEN", span, to - from, 1.5)
    }

    @Test fun calligraphyWidthIsTheSameAtAnySampleSpacing() {
        // Nothing in the nib reads time. A fast pen reports sparser samples, each step is longer, and
        // each gets a proportionally larger allowance, so the same path drawn at five times the speed
        // has to come out the same width. The head itself agrees to 0.012 px. Measured at 0.096 px
        // overall, all of it at the far edge of the head hold: the hold has to end on a sample, so it
        // runs past the window by up to one sample spacing, and on a curving path the held value and
        // the drifting one separate there.
        fun run(spacing: Double): Pair<DoubleArray, DoubleArray> {
            val pts = ArrayList<Sample>()
            var arc = 0.0
            while (arc <= 60.0) {
                val a = arc / 20.0
                pts.add(Sample(20.0 * a, 12.0 * sin(a * 2), 1.0, arc))
                arc += spacing
            }
            val g = StrokeEngine.build(pts, 6.0, false, 1.0, 0.6)
            val cum = DoubleArray(pts.size)
            for (i in 1 until pts.size) {
                cum[i] = cum[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            }
            return cum to DoubleArray(pts.size) { g.hw(it) }
        }
        val (denseArc, denseHw) = run(0.3)
        val (sparseArc, sparseHw) = run(1.5)
        var worst = 0.0
        for (j in sparseArc.indices) {
            val s = sparseArc[j]
            if (s < 2.0 || s > denseArc.last() - 2.0) continue
            var i = 0
            while (i < denseArc.size - 2 && denseArc[i + 1] < s) i++
            val t = (s - denseArc[i]) / (denseArc[i + 1] - denseArc[i])
            worst = maxOf(worst, abs(denseHw[i] + (denseHw[i + 1] - denseHw[i]) * t - sparseHw[j]))
        }
        assertTrue("half-width moved $worst px with the sample spacing", worst < 0.12)
    }

    @Test fun calligraphyHeadIsNotDecidedBySubPixelPenDownNoise() {
        // A real downstroke off the tablet: the pen lands, its first reported move is 0.55 px the
        // wrong way, and the other 120 px all head down. That step is a tenth the length of the ones
        // just after it, because the smoothed centreline has not caught up yet, so its tangent is
        // noise. Read per sample it took the whole head and the stroke opened at the thin extreme,
        // staying under full width for 19 px. Read as a chord it displaces the far end by half a
        // pixel and the downstroke wins.
        val ds = 0.6
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(0.11, -0.54, 1.0)) +
            (1..60).map { Sample(0.11 - it * 0.3, -0.54 + it * 1.4, 1.0) }
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, ds)
        assertTrue("the stroke has to open broad, not climb out of a hole",
            g.hw(0) > 0.95 * 3.0 * (1.0 + ds))
    }
}
