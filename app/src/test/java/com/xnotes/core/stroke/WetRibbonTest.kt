package com.xnotes.core.stroke

import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * [WetRibbon] is only worth having if it draws what the batch engine draws. Every test here grows a
 * ribbon a sample at a time and, at each length, holds the whole thing against
 * [StrokeEngine.build] over the same prefix — centres, half-widths and both rails.
 *
 * The settled mark gets the same treatment from the other side: a point it calls final must still
 * hold the value it was given once the stroke is far longer. That is the promise the raster and
 * mesh caches are built on, so it is checked against the engine rather than against the ribbon's
 * own bookkeeping.
 */
class WetRibbonTest {

    /** A hand-sized curve at about the spacing a stylus reports, with pressure and timing on it. */
    private fun path(count: Int, wobble: Double = 1.0): List<Sample> {
        val out = ArrayList<Sample>(count)
        for (i in 0 until count) {
            val u = i * 0.09
            val x = 40.0 + u * 26.0 + wobble * sin(u * 3.1) * 7.0
            val y = 60.0 + sin(u * 1.7) * 34.0 + wobble * cos(u * 5.3) * 2.5
            val p = 0.35 + 0.4 * (0.5 + 0.5 * sin(u * 2.3))
            out.add(Sample(x, y, p, i * 7.0))
        }
        return out
    }

    private fun configFor(tool: Tool): ToolConfig = ToolDefaults.configFor(tool)

    private fun ribbonFor(config: ToolConfig, tool: Tool, smoothScale: Double, speedScale: Double) =
        WetRibbon(
            baseWidth = config.baseWidth,
            pressureEnabled = config.pressureEnabled,
            m = config.pressureMinFactor,
            ds = config.directionStrength,
            speedStrength = config.speedStrength,
            speedScale = speedScale,
            smooth = true,
            holdEnds = tool == Tool.PEN || tool == Tool.HIGHLIGHTER,
            smoothScale = smoothScale,
            speedTop = config.speedTop,
            speedSmoothing = config.speedSmoothing,
        )

    private fun build(
        config: ToolConfig,
        tool: Tool,
        samples: List<Sample>,
        smoothScale: Double,
        speedScale: Double,
    ): StrokeGeometry = StrokeEngine.build(
        samples,
        config.baseWidth,
        config.pressureEnabled,
        config.pressureMinFactor,
        config.directionStrength,
        config.speedStrength,
        config.taperEnabled,
        config.taperMinFactor,
        speedScale,
        smooth = true,
        holdEnds = tool == Tool.PEN || tool == Tool.HIGHLIGHTER,
        finished = false,
        smoothScale = smoothScale,
        speedTop = config.speedTop,
        speedSmoothing = config.speedSmoothing,
    )

    /** Grow a ribbon over [samples], checking it against the batch build at every single length. */
    private fun assertMatchesAtEveryLength(
        tool: Tool,
        samples: List<Sample>,
        smoothScale: Double = 1.0,
        speedScale: Double = 1.0,
        config: ToolConfig = configFor(tool),
    ) {
        val ribbon = ribbonFor(config, tool, smoothScale, speedScale)
        for (n in 1..samples.size) {
            val s = samples[n - 1]
            ribbon.append(s.x, s.y, s.pressure, s.t)
            val want = build(config, tool, samples.subList(0, n), smoothScale, speedScale)
            assertEquals("$tool point count at n=$n", want.pointCount, ribbon.pointCount)
            for (i in 0 until want.pointCount) {
                assertEquals("$tool cx[$i] at n=$n", want.cx(i), ribbon.cx(i), 0.0)
                assertEquals("$tool cy[$i] at n=$n", want.cy(i), ribbon.cy(i), 0.0)
                assertEquals("$tool hw[$i] at n=$n", want.hw(i), ribbon.hw(i), 0.0)
            }
            if (!want.hasRails) continue
            for (i in 0 until want.pointCount) {
                assertEquals("$tool leftX[$i] at n=$n", want.leftX(i), ribbon.leftX(i), 0.0)
                assertEquals("$tool leftY[$i] at n=$n", want.leftY(i), ribbon.leftY(i), 0.0)
                assertEquals("$tool rightX[$i] at n=$n", want.rightX(i), ribbon.rightX(i), 0.0)
                assertEquals("$tool rightY[$i] at n=$n", want.rightY(i), ribbon.rightY(i), 0.0)
            }
        }
    }

    // --- the ribbon reproduces the engine, sample for sample ---

    @Test fun penMatchesTheBatchEngine() {
        assertMatchesAtEveryLength(Tool.PEN, path(140))
    }

    @Test fun calligraphyMatchesTheBatchEngine() {
        assertMatchesAtEveryLength(Tool.CALLIGRAPHY, path(140))
    }

    @Test fun speedMatchesTheBatchEngine() {
        assertMatchesAtEveryLength(Tool.SPEED, path(140))
    }

    @Test fun dashedMatchesTheBatchEngine() {
        assertMatchesAtEveryLength(Tool.DASHED, path(140))
    }

    @Test fun matchesWhenDrawnZoomedIn() {
        // Writing small at high zoom scales every arc constant the engine measures the hand
        // against, the nib's head window included, so the head freezes at a different sample.
        assertMatchesAtEveryLength(Tool.CALLIGRAPHY, path(140), smoothScale = 0.25)
        assertMatchesAtEveryLength(Tool.SPEED, path(140), smoothScale = 0.25, speedScale = 4.0)
    }

    @Test fun matchesAcrossACoincidentSampleRun() {
        // The pen reporting without moving: no arc for the low-pass to integrate over, and no
        // tangent to read, so both fall back on carried state. That carry is the part an
        // incremental filter can get wrong.
        val out = ArrayList<Sample>()
        out.addAll(path(20))
        val stuck = out.last()
        repeat(6) { out.add(Sample(stuck.x, stuck.y, stuck.pressure, stuck.t + 7.0 * (it + 1))) }
        out.addAll(path(20).map { Sample(it.x + 60.0, it.y + 10.0, it.pressure, it.t + 300.0) })
        assertMatchesAtEveryLength(Tool.PEN, out)
        assertMatchesAtEveryLength(Tool.CALLIGRAPHY, out)
        assertMatchesAtEveryLength(Tool.SPEED, out)
    }

    /** A stroke whose speed swings hard, with gaps where a gate would have dropped samples while the pen stood still. */
    private fun jerkyTimedPath(count: Int): List<Sample> {
        val out = ArrayList<Sample>(count)
        var t = 0.0
        for (i in 0 until count) {
            val u = i * 0.09
            out.add(Sample(40.0 + u * 26.0 + sin(u * 3.1) * 7.0, 60.0 + sin(u * 1.7) * 34.0, 0.6, t))
            t += if (i % 23 == 22) 120.0 else 3.0 + 9.0 * (0.5 + 0.5 * sin(u * 0.7))
        }
        return out
    }

    /** Cursive strokes reversing sharply between hairline and downstroke, each followed by a short wobble. */
    private fun cuspyPath(): List<Sample> {
        val out = arrayListOf(Sample(30.0, 80.0, 0.6, 0.0))
        var x = 30.0
        var y = 80.0
        var t = 0.0
        fun leg(dx: Double, dy: Double, steps: Int) = repeat(steps) {
            x += dx / steps
            y += dy / steps
            t += 4.0
            out.add(Sample(x, y, 0.6, t))
        }
        repeat(4) {
            leg(8.0, -36.0, 24)
            leg(-3.0, 36.0, 24)
            leg(6.0, -1.5, 3)
            leg(4.0, 0.0, 4)
        }
        return out
    }

    @Test fun calligraphyMatchesAcrossReversals() {
        assertMatchesAtEveryLength(Tool.CALLIGRAPHY, cuspyPath())
        assertMatchesAtEveryLength(Tool.CALLIGRAPHY, cuspyPath(), config = configFor(Tool.CALLIGRAPHY).copy(baseWidth = 20.0))
    }

    @Test fun calligraphySettledPointsNeverMoveAcrossReversals() {
        assertSettledPointsNeverMove(Tool.CALLIGRAPHY, cuspyPath())
        assertSettledPointsNeverMove(Tool.CALLIGRAPHY, cuspyPath(), configFor(Tool.CALLIGRAPHY).copy(baseWidth = 20.0))
    }

    @Test fun speedMatchesUnderAWideBlurAndPauses() {
        val wide = configFor(Tool.SPEED).copy(baseWidth = 20.0, speedSmoothing = 1.0)
        assertMatchesAtEveryLength(Tool.SPEED, jerkyTimedPath(160), config = wide)
        assertMatchesAtEveryLength(Tool.SPEED, jerkyTimedPath(160), config = wide.copy(speedSmoothing = 0.0))
    }

    @Test fun speedSettledPointsNeverMoveUnderAWideBlur() {
        val wide = configFor(Tool.SPEED).copy(baseWidth = 20.0, speedSmoothing = 1.0)
        assertSettledPointsNeverMove(Tool.SPEED, jerkyTimedPath(200), wide)
        assertSettledPointsNeverMove(Tool.SPEED, jerkyTimedPath(200), wide.copy(speedSmoothing = 0.0))
    }

    @Test fun matchesOnAStrokeTooShortToSettleAnything() {
        for (len in 1..10) {
            assertMatchesAtEveryLength(Tool.PEN, path(len))
            assertMatchesAtEveryLength(Tool.CALLIGRAPHY, path(len))
            assertMatchesAtEveryLength(Tool.SPEED, path(len))
        }
    }

    // --- the settled mark tells the truth ---

    /**
     * Every point the ribbon called settled at some length still carries that value at every later
     * length. Checked against the engine, so a ribbon that both drew and judged a point wrongly
     * cannot agree with itself into a pass.
     */
    private fun assertSettledPointsNeverMove(tool: Tool, samples: List<Sample>, config: ToolConfig = configFor(tool)) {
        val ribbon = ribbonFor(config, tool, 1.0, 1.0)
        val settledCx = HashMap<Int, Double>()
        val settledHw = HashMap<Int, Double>()
        for (n in 1..samples.size) {
            val s = samples[n - 1]
            ribbon.append(s.x, s.y, s.pressure, s.t)
            val want = build(config, tool, samples.subList(0, n), 1.0, 1.0)
            for ((i, cx) in settledCx) {
                assertEquals("$tool settled cx[$i] moved by n=$n", cx, want.cx(i), 0.0)
                assertEquals("$tool settled hw[$i] moved by n=$n", settledHw[i]!!, want.hw(i), 0.0)
            }
            for (i in 0 until ribbon.settledCount) {
                settledCx[i] = ribbon.cx(i)
                settledHw[i] = ribbon.hw(i)
            }
        }
        assertTrue("$tool settled nothing at all", ribbon.settledCount > 0)
    }

    @Test fun penSettledPointsNeverMove() {
        assertSettledPointsNeverMove(Tool.PEN, path(160))
    }

    @Test fun calligraphySettledPointsNeverMove() {
        assertSettledPointsNeverMove(Tool.CALLIGRAPHY, path(160))
    }

    @Test fun speedSettledPointsNeverMove() {
        assertSettledPointsNeverMove(Tool.SPEED, path(160))
    }

    @Test fun dashedSettledPointsNeverMove() {
        assertSettledPointsNeverMove(Tool.DASHED, path(160))
    }

    @Test fun settledPointsNeverMoveOnAJitteryStroke() {
        // A stroke that keeps changing its mind: sharp reversals and pressure spikes are what
        // would expose a lookahead rule that is one sample short.
        val out = ArrayList<Sample>()
        for (i in 0 until 200) {
            val u = i * 0.3
            out.add(
                Sample(
                    50.0 + u * 3.0 + sin(u * 9.0) * 11.0,
                    50.0 + cos(u * 7.0) * 13.0,
                    0.2 + 0.7 * (0.5 + 0.5 * sin(u * 11.0)),
                    i * 4.0,
                ),
            )
        }
        assertSettledPointsNeverMove(Tool.PEN, out)
        assertSettledPointsNeverMove(Tool.CALLIGRAPHY, out)
        assertSettledPointsNeverMove(Tool.SPEED, out)
    }

    @Test fun settledMarkOnlyEverMovesForward() {
        val samples = path(160)
        val config = configFor(Tool.SPEED)
        val ribbon = ribbonFor(config, Tool.SPEED, 1.0, 1.0)
        var last = 0
        for (s in samples) {
            ribbon.append(s.x, s.y, s.pressure, s.t)
            assertTrue("settled went backwards: $last -> ${ribbon.settledCount}", ribbon.settledCount >= last)
            assertTrue("settled past the ribbon", ribbon.settledCount <= ribbon.pointCount)
            last = ribbon.settledCount
        }
    }

    @Test fun theTailStaysShortHoweverLongTheStrokeGets() {
        // The whole point: what a frame costs must stop growing. The unsettled tail is what a
        // frame redraws, so it is the thing that has to stay bounded.
        for (tool in listOf(Tool.PEN, Tool.CALLIGRAPHY, Tool.SPEED, Tool.DASHED)) {
            val config = configFor(tool)
            val ribbon = ribbonFor(config, tool, 1.0, 1.0)
            var worst = 0
            val samples = path(600)
            for ((i, s) in samples.withIndex()) {
                ribbon.append(s.x, s.y, s.pressure, s.t)
                if (i >= 40) worst = maxOf(worst, ribbon.pointCount - ribbon.settledCount)
            }
            assertTrue("$tool left a tail of $worst points", worst <= 32)
        }
    }

    // --- what the ribbon will not take on ---

    @Test fun theTaperPenIsTurnedDown() {
        assertFalse(WetRibbon.supports(taperEnabled = true, straight = false))
        assertFalse(WetRibbon.supports(taperEnabled = false, straight = true))
        assertTrue(WetRibbon.supports(taperEnabled = false, straight = false))
    }

    @Test fun geometrySnapshotMatchesTheBatchEngine() {
        val samples = path(90)
        val config = configFor(Tool.CALLIGRAPHY)
        val ribbon = ribbonFor(config, Tool.CALLIGRAPHY, 1.0, 1.0)
        for (s in samples) ribbon.append(s.x, s.y, s.pressure, s.t)
        val got = ribbon.geometry()
        val want = build(config, Tool.CALLIGRAPHY, samples, 1.0, 1.0)
        assertEquals(want.pointCount, got.pointCount)
        assertEquals(want.centerline.size, got.centerline.size)
        assertEquals(want.halfWidths.size, got.halfWidths.size)
        for (i in want.outline.indices) {
            assertEquals("outline[$i]", want.outline[i], got.outline[i], 0.0f)
        }
    }

    @Test fun boundsMatchTheBatchGeometry() {
        val samples = path(120)
        val config = configFor(Tool.PEN)
        val ribbon = ribbonFor(config, Tool.PEN, 1.0, 1.0)
        for (s in samples) ribbon.append(s.x, s.y, s.pressure, s.t)
        val want = build(config, Tool.PEN, samples, 1.0, 1.0)
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until want.pointCount) {
            minX = minOf(minX, want.cx(i) - want.hw(i))
            maxX = maxOf(maxX, want.cx(i) + want.hw(i))
            minY = minOf(minY, want.cy(i) - want.hw(i))
            maxY = maxOf(maxY, want.cy(i) + want.hw(i))
        }
        val got = ribbon.bounds()
        assertEquals(minX, got.left, 1e-9)
        assertEquals(minY, got.top, 1e-9)
        assertEquals(maxX - minX, got.w, 1e-9)
        assertEquals(maxY - minY, got.h, 1e-9)
    }
}
