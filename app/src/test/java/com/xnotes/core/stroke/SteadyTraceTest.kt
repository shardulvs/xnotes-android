package com.xnotes.core.stroke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SteadyTraceTest {

    private val mm = SteadyTrace.MM_PER_DP

    /** A slow line with a 10 Hz tremor of [shake] dp across it, drawn through [s] and traced. */
    private fun shakyLine(s: Stabilizer?, shake: Double = 1.0): SteadyEffect {
        val trace = SteadyTrace()
        trace.start(0.0, 0.0)
        s?.start(0.0, 0.0, 0.0)
        var t = 0.0
        var x = 0.0
        var y = 0.0
        while (t < 1500.0) {
            t += 4.0
            x = 0.012 * t
            y = shake * sin(2.0 * PI * 10.0 * t / 1000.0)
            if (s == null) {
                trace.add(x, y, x, y)
            } else {
                s.step(x, y, t)
                trace.add(x, y, s.x, s.y)
            }
        }
        trace.add(x, y, x, y)
        return trace.measure(mm)
    }

    @Test fun inkOnTheNibsPathMeasuresNothing() {
        val e = shakyLine(null)
        assertEquals(0.0, e.movedMean, 1e-12)
        assertEquals(0.0, e.movedMax, 1e-12)
        assertEquals(0.0, e.trail, 1e-12)
        assertEquals(0.0, e.wiggle, 1e-12)
    }

    @Test fun lagAlongAStraightLineTrailsButMovesNothing() {
        val trace = SteadyTrace()
        trace.start(0.0, 0.0)
        for (i in 1..100) trace.add(i * 2.0, i * 1.0, (i * 2.0 - 5.0).coerceAtLeast(0.0), (i * 1.0 - 2.5).coerceAtLeast(0.0))
        trace.add(200.0, 100.0, 200.0, 100.0)
        val e = trace.measure(mm)
        assertEquals(0.0, e.movedMax, 1e-9)
        assertEquals(kotlin.math.hypot(5.0, 2.5) * mm, e.trail, 1e-9)
        assertTrue("a straight line has no wiggle to judge", e.wiggle.isNaN())
    }

    @Test fun inkCutInsideACircleMovesByTheGap() {
        val trace = SteadyTrace()
        trace.start(50.0, 0.0)
        for (i in 1..360) {
            val a = i * PI / 180.0
            trace.add(50.0 * cos(a), 50.0 * sin(a), 48.0 * cos(a), 48.0 * sin(a))
        }
        val e = trace.measure(1.0)
        assertEquals(2.0, e.movedMean, 0.05)
        assertEquals(2.0, e.movedMax, 0.05)
    }

    @Test fun aCircleTurnsOnceHoweverItIsSampled() {
        for (count in listOf(40, 200, 2000)) {
            val xs = DoubleArray(count + 1) { 50.0 * cos(2.0 * PI * it / count) }
            val ys = DoubleArray(count + 1) { 50.0 * sin(2.0 * PI * it / count) }
            assertEquals("$count points", 2.0 * PI, SteadyTrace.turning(xs, ys, count + 1, 1.0), 2.0 * PI / count + 2.0 / 50.0)
        }
    }

    @Test fun steadyingAShakyLineTakesOutItsWiggle() {
        val weak = shakyLine(Stabilizer(1.2, Stabilizer.DEFAULT_CROSSOVER, 1.0))
        val strong = shakyLine(Stabilizer(12.0, Stabilizer.DEFAULT_CROSSOVER, 1.0))
        assertTrue("strong keeps ${strong.wiggle}", strong.wiggle < -0.5)
        assertTrue("weak ${weak.wiggle} vs strong ${strong.wiggle}", weak.wiggle > strong.wiggle)
        assertTrue("strong moves the ink", strong.movedMean > weak.movedMean && strong.movedMean > 0.0)
        assertTrue("strong trails further", strong.trail > weak.trail)
    }

    @Test fun theReadoutShowsTheLastStroke() {
        val readout = SteadyReadout()
        assertEquals(listOf("stabilization  draw to measure"), readout.lines())
        readout.last = SteadyReadout.Row(70.0, 50.0, SteadyEffect(0.04, 0.21, 0.52, -0.38))
        readout.last = SteadyReadout.Row(30.0, 45.0, SteadyEffect(0.012, 0.3, 0.6, -0.214))
        assertEquals(
            listOf("stabilization  S30 C45", "moved   avg 0.01  max 0.30 mm", "trail   0.60 mm", "wiggle  -21%"),
            readout.lines(),
        )
        readout.last = SteadyReadout.Row(30.0, 50.0, SteadyEffect(0.0, 0.0, 0.0, Double.NaN))
        assertEquals("wiggle  -", readout.lines()[3])
        readout.last = SteadyReadout.Row(30.0, 50.0, null)
        assertEquals(listOf("stabilization  off"), readout.lines())
    }
}
