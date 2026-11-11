package com.xnotes.core.stroke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class StabilizerTest {

    /** A straight line at [speed] dp/s along x, sampled every [dt] ms for [ms] ms; the trail at the end, in dp. */
    private fun trailAt(
        speed: Double,
        trail: Double,
        crossover: Double = Stabilizer.DEFAULT_CROSSOVER,
        speedScale: Double = 1.0,
        dt: Double = 4.0,
        ms: Double = 1500.0,
    ): Double {
        val s = Stabilizer(trail, crossover, speedScale)
        val v = speed / speedScale / 1000.0
        s.start(0.0, 0.0, 0.0)
        var t = 0.0
        while (t < ms) {
            t += dt
            s.step(v * t, 0.0, t)
        }
        return (v * t - s.x) * speedScale
    }

    /** How far a 10 Hz tremor of 1 dp across a line at [speed] dp/s still moves the steadied point, in dp. */
    private fun tremorKept(speed: Double, trail: Double): Double {
        val s = Stabilizer(trail, Stabilizer.DEFAULT_CROSSOVER, 1.0)
        s.start(0.0, 0.0, 0.0)
        var t = 0.0
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        while (t < 2000.0) {
            t += 4.0
            s.step(speed * t / 1000.0, sin(2.0 * PI * 10.0 * t / 1000.0), t)
            if (t > 1000.0) {
                lo = minOf(lo, s.y)
                hi = maxOf(hi, s.y)
            }
        }
        return (hi - lo) / 2.0
    }

    @Test fun aStillNibStaysExactlyPut() {
        val s = Stabilizer(6.0, Stabilizer.DEFAULT_CROSSOVER, 1.0)
        s.start(12.5, -40.25, 0.0)
        for (i in 1..200) s.step(12.5, -40.25, i * 4.0)
        assertEquals(12.5, s.x, 0.0)
        assertEquals(-40.25, s.y, 0.0)
    }

    @Test fun theSteadiedPointCatchesUpOnceTheNibStops() {
        val s = Stabilizer(12.0, Stabilizer.DEFAULT_CROSSOVER, 1.0)
        s.start(0.0, 0.0, 0.0)
        var t = 0.0
        while (t < 300.0) {
            t += 4.0
            s.step(0.2 * t, 0.1 * t, t)
        }
        val endX = 0.2 * t
        val endY = 0.1 * t
        assertTrue("trails while moving", endX - s.x > 1.0)
        repeat(500) {
            t += 4.0
            s.step(endX, endY, t)
        }
        assertEquals(endX, s.x, 1e-3)
        assertEquals(endY, s.y, 1e-3)
    }

    @Test fun aSteadySpeedNeverTrailsPastTheCap() {
        for (cap in listOf(1.2, 3.6, 6.0, 12.0, 24.0)) {
            for (speed in listOf(5.0, 20.0, 60.0, 200.0, 600.0, 2000.0, 8000.0)) {
                for (dt in listOf(2.0, 4.0, 8.0, 16.0)) {
                    val trail = trailAt(speed, cap, dt = dt)
                    assertTrue("cap $cap at $speed dp/s every $dt ms trails $trail", trail > 0.0 && trail < cap)
                }
            }
        }
    }

    @Test fun theTrailIsTheDesignedOneAtAnyReportRate() {
        val vc = Stabilizer.DEFAULT_CROSSOVER
        for (speed in listOf(20.0, 60.0, 200.0, 600.0)) {
            for (dt in listOf(2.0, 4.0, 8.0, 16.0)) {
                val want = 6.0 * speed / (vc + speed)
                assertEquals("$speed dp/s every $dt ms", want, trailAt(speed, 6.0, dt = dt), 1e-6 * want)
            }
        }
    }

    @Test fun fasterMovementTrailsFurther() {
        var last = 0.0
        for (speed in listOf(5.0, 20.0, 60.0, 200.0, 600.0, 2000.0)) {
            val trail = trailAt(speed, 6.0)
            assertTrue("$speed dp/s trails $trail, not past $last", trail > last)
            last = trail
        }
    }

    @Test fun moreStrengthTrailsFurther() {
        val weak = trailAt(100.0, 1.2)
        val mid = trailAt(100.0, 6.0)
        val strong = trailAt(100.0, 12.0)
        assertTrue("$weak < $mid < $strong", weak < mid && mid < strong)
    }

    @Test fun aHigherCrossoverSteadiesFasterMovementToo() {
        assertTrue(trailAt(60.0, 6.0, crossover = 20.0) > trailAt(60.0, 6.0, crossover = 170.0))
    }

    @Test fun slowShakyMovementIsSteadiedHardest() {
        val slow = tremorKept(5.0, 6.0)
        val fast = tremorKept(300.0, 6.0)
        assertTrue("slow keeps $slow, fast keeps $fast", slow < fast)
        assertTrue("slow keeps $slow of a 1 dp tremor", slow < 0.6)
        assertTrue("stronger steadies more", tremorKept(5.0, 12.0) < slow)
    }

    @Test fun zoomDoesNotChangeTheFeel() {
        val ref = trailAt(150.0, 6.0, speedScale = 1.0)
        for (scale in listOf(0.25, 1.75, 4.0, 40.0)) {
            assertEquals("at speed scale $scale", ref, trailAt(150.0, 6.0, speedScale = scale), 1e-9 * max(1.0, ref))
        }
    }

    @Test fun theTipCrossesTheTrailAtTheNibsSpeed() {
        val v = 0.15
        val s = Stabilizer(6.0, Stabilizer.DEFAULT_CROSSOVER, 1.0)
        s.start(0.0, 0.0, 0.0)
        var t = 0.0
        while (t < 1500.0) {
            t += 4.0
            s.step(v * t, 0.0, t)
        }
        val trail = v * t - s.x
        assertEquals(t + s.lagMs, s.tipTime, 1e-12)
        assertEquals(v, trail / s.lagMs, 1e-6 * v)
    }

    @Test fun samplesWithOneTimestampStayFinite() {
        val s = Stabilizer(6.0, Stabilizer.DEFAULT_CROSSOVER, 1.0)
        s.start(0.0, 0.0, 10.0)
        for (i in 1..20) s.step(i * 3.0, i * 1.0, 10.0)
        assertTrue(s.x.isFinite() && s.y.isFinite() && s.lagMs.isFinite())
        assertTrue(s.x > 0.0 && s.x < 60.0)
    }

    @Test fun startForgetsTheLastStroke() {
        fun run(s: Stabilizer): List<Double> {
            s.start(5.0, 5.0, 0.0)
            val out = ArrayList<Double>()
            for (i in 1..60) {
                s.step(5.0 + i * 0.7, 5.0 + abs(sin(i * 0.3)) * 4.0, i * 4.0)
                out.add(s.x)
                out.add(s.y)
            }
            return out
        }
        val used = Stabilizer(6.0, 40.0, 1.0)
        used.start(900.0, -300.0, 0.0)
        for (i in 1..30) used.step(900.0 - i * 9.0, -300.0, i * 4.0)
        assertEquals(run(Stabilizer(6.0, 40.0, 1.0)), run(used))
        assertTrue(used.moved)
    }

    @Test fun offOrEmptySettingsSteadyNothing() {
        assertNull(Stabilizer.forStroke(false, 3.6, Stabilizer.DEFAULT_CROSSOVER, 1.0))
        assertNull(Stabilizer.forStroke(true, 0.0, Stabilizer.DEFAULT_CROSSOVER, 1.0))
        assertNull(Stabilizer.forStroke(true, 3.6, 0.0, 1.0))
        assertNull(Stabilizer.forStroke(true, 3.6, Stabilizer.DEFAULT_CROSSOVER, 0.0))
        assertNotNull(Stabilizer.forStroke(true, 3.6, Stabilizer.DEFAULT_CROSSOVER, 1.0))
    }
}
