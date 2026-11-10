package com.xnotes.core.model

import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeEngine
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * [Stroke] hands a live stroke's geometry to a growing [com.xnotes.core.stroke.WetRibbon] and a
 * finished one back to [StrokeEngine]. These pin the seam between them: the ink must not change as
 * a stroke crosses it, and every path that edits samples wholesale must let the ribbon go rather
 * than carry a stale one forward.
 */
class LiveStrokeRibbonTest {

    private fun path(count: Int): List<Sample> = (0 until count).map { i ->
        val u = i * 0.11
        Sample(
            30.0 + u * 21.0 + sin(u * 2.7) * 9.0,
            45.0 + cos(u * 1.9) * 27.0,
            0.3 + 0.5 * (0.5 + 0.5 * sin(u * 3.3)),
            i * 6.0,
        )
    }

    private fun draw(tool: Tool, samples: List<Sample>, finish: Boolean = false): Stroke {
        val s = Stroke(tool, ToolDefaults.configFor(tool))
        s.finished = false
        for (p in samples) s.addSample(p)
        if (finish) s.finished = true
        return s
    }

    /** The geometry [StrokeEngine] would build for the same samples and liveness. */
    private fun batch(stroke: Stroke, finished: Boolean) = StrokeEngine.build(
        stroke.samples.toList(),
        stroke.config.baseWidth,
        stroke.config.pressureEnabled,
        stroke.config.pressureMinFactor,
        stroke.config.directionStrength,
        stroke.config.speedStrength,
        stroke.config.taperEnabled,
        stroke.config.taperMinFactor,
        stroke.speedScale,
        smooth = !stroke.straight,
        holdEnds = stroke.tool == Tool.PEN || stroke.tool == Tool.HIGHLIGHTER,
        finished = finished,
        smoothScale = stroke.smoothScale,
        speedTop = stroke.config.speedTop,
        speedSmoothing = stroke.config.speedSmoothing,
    )

    private fun assertSameInk(tool: Tool, finished: Boolean) {
        val stroke = draw(tool, path(80), finish = finished)
        val got = stroke.geometry()
        val want = batch(stroke, finished)
        assertEquals("$tool point count", want.pointCount, got.pointCount)
        for (i in 0 until want.pointCount) {
            assertEquals("$tool cx[$i]", want.cx(i), got.cx(i), 0.0)
            assertEquals("$tool cy[$i]", want.cy(i), got.cy(i), 0.0)
            assertEquals("$tool hw[$i]", want.hw(i), got.hw(i), 0.0)
        }
    }

    @Test fun liveInkMatchesTheEngine() {
        for (tool in listOf(Tool.PEN, Tool.CALLIGRAPHY, Tool.SPEED, Tool.DASHED)) {
            assertSameInk(tool, finished = false)
        }
    }

    @Test fun penUpGoesBackThroughTheEngine() {
        // The lift-time rules (the nib's tail cap, the calligraphy dot) only exist in the batch
        // build, so a finished stroke must not still be reading a ribbon.
        for (tool in listOf(Tool.PEN, Tool.CALLIGRAPHY, Tool.SPEED, Tool.DASHED)) {
            assertSameInk(tool, finished = true)
        }
    }

    @Test fun aLiveStrokeHasARibbonAndAFinishedOneDoesNot() {
        val live = draw(Tool.PEN, path(40))
        assertNotNull(live.wetRibbon)
        live.finished = true
        assertNull(live.wetRibbon)
    }

    @Test fun theTaperPenAndStraightToolsDrawWithoutOne() {
        assertNull(draw(Tool.TAPER, path(40)).wetRibbon)
        val straight = Stroke(Tool.HIGHLIGHTER, ToolDefaults.configFor(Tool.HIGHLIGHTER), straight = true)
        straight.finished = false
        for (p in path(4)) straight.setStraightEnd(p)
        assertNull(straight.wetRibbon)
    }

    @Test fun theTaperPenStillDrawsWhatTheEngineDraws() {
        assertSameInk(Tool.TAPER, finished = false)
        assertSameInk(Tool.TAPER, finished = true)
    }

    @Test fun replacingTheSamplesDropsTheRibbon() {
        val stroke = draw(Tool.PEN, path(40))
        assertNotNull(stroke.wetRibbon)
        stroke.setSamples(path(12))
        assertNull(stroke.wetRibbon)
        // And the next sample picks a fresh one up over what is already there, rather than leaving
        // the stroke rebuilding from scratch for the rest of its life.
        stroke.addSample(Sample(200.0, 200.0, 0.5, 500.0))
        assertNotNull(stroke.wetRibbon)
        assertEquals(13, stroke.wetRibbon!!.pointCount)
        val want = batch(stroke, finished = false)
        for (i in 0 until want.pointCount) {
            assertEquals("cx[$i]", want.cx(i), stroke.geometry().cx(i), 0.0)
            assertEquals("hw[$i]", want.hw(i), stroke.geometry().hw(i), 0.0)
        }
    }

    @Test fun boundsMatchWhicheverPathBuiltThem() {
        val samples = path(80)
        val live = draw(Tool.CALLIGRAPHY, samples)
        val settled = draw(Tool.CALLIGRAPHY, samples)
        settled.releaseGeometry() // banks the ribbon's box, then lets it go
        assertNull(settled.wetRibbon)
        assertEquals(live.bounds(), settled.bounds())
    }

    @Test fun theRibbonSettlesWhileTheStrokeIsStillBeingDrawn() {
        val stroke = draw(Tool.PEN, path(120))
        val ribbon = stroke.wetRibbon!!
        assertTrue("nothing settled", ribbon.settledCount > 0)
        assertTrue("the tail is not bounded", ribbon.pointCount - ribbon.settledCount <= 8)
    }
}
