package com.xnotes.core.tools

import com.xnotes.core.model.Rgba
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    @Test fun theMarkupToolHighlightsAtNinetyAndPansAFreeFinger() {
        val c = ToolDefaults.configFor(Tool.MARKUP)
        assertEquals(MarkupMode.HIGHLIGHT, c.markupMode)
        assertEquals(0.9, c.markupIntensity, 1e-9)
        assertTrue(Tool.MARKUP.fingerPansWhenOff)
        assertFalse(Tool.MARKUP.isStroke)
        assertTrue(Tool.MARKUP in Tool.wheelOrder && Tool.MARKUP in ToolDefaults.persistedTools)
        assertEquals(MarkupMode.HIGHLIGHT, MarkupMode.fromId("nonsense"))
        assertEquals(null, MarkupMode.SELECT.type)
        assertEquals(com.xnotes.core.model.MarkupType.SQUIGGLY, MarkupMode.SQUIGGLY.type)
    }

    @Test fun factoryDefaultsMatchSpec() {
        assertEquals(ToolConfig(3.0, true, 0.35, 0.0), ToolDefaults.configFor(Tool.PEN).copy(rgba = ToolConfig().rgba))
        val cal = ToolDefaults.configFor(Tool.CALLIGRAPHY)
        assertEquals(6.0, cal.baseWidth, 1e-9)
        assertTrue(cal.pressureEnabled)
        assertEquals(0.40, cal.pressureMinFactor, 1e-9)
        assertEquals(0.60, cal.directionStrength, 1e-9)
        val hi = ToolDefaults.configFor(Tool.HIGHLIGHTER)
        assertEquals(16.0, hi.baseWidth, 1e-9)
        assertFalse(hi.pressureEnabled)
        assertEquals(0.90, hi.highlighterAlpha, 1e-9)
        assertEquals(1.0, ToolConversions.intensityToHighlighterAlpha(100.0), 1e-9)
        assertEquals(24.0, ToolDefaults.configFor(Tool.ERASER).baseWidth, 1e-9)
        assertEquals(2.0, ToolDefaults.configFor(Tool.LASSO).baseWidth, 1e-9)
        // A tool without an entry uses ToolConfig() = (3.0, on, 0.35, 0.0).
        assertEquals(ToolConfig().copy(rgba = ToolDefaults.configFor(Tool.PAN).rgba), ToolDefaults.configFor(Tool.PAN))
    }

    @Test fun sensitivityRoundTrip() {
        assertEquals(1.0, ToolConversions.sensitivityToMinFactor(0.0), 1e-9)
        assertEquals(0.1, ToolConversions.sensitivityToMinFactor(100.0), 1e-9)
        assertEquals(0.55, ToolConversions.sensitivityToMinFactor(50.0), 1e-9)
        // inverse
        assertEquals(50.0, ToolConversions.minFactorToSensitivity(0.55), 1e-9)
        assertEquals(0.0, ToolConversions.minFactorToSensitivity(1.0), 1e-9)
    }

    @Test fun speedPenSlidersRoundTrip() {
        val speed = ToolDefaults.configFor(Tool.SPEED)
        assertEquals(25.0, ToolConversions.blurToSmoothing(speed.speedSmoothing), 1e-9)
        assertEquals(50.0, ToolConversions.dpPerMsToTopSpeed(speed.speedTop), 1e-9)
        assertEquals(0.2, ToolConversions.topSpeedToDpPerMs(0.0), 1e-9)
        assertEquals(1.8, ToolConversions.topSpeedToDpPerMs(100.0), 1e-9)
        for (x in listOf(0.0, 12.5, 50.0, 87.0, 100.0)) {
            assertEquals(x, ToolConversions.blurToSmoothing(ToolConversions.smoothingToBlur(x)), 1e-9)
            assertEquals(x, ToolConversions.dpPerMsToTopSpeed(ToolConversions.topSpeedToDpPerMs(x)), 1e-9)
        }
    }

    @Test fun stabilizationSlidersMapToTheirSettings() {
        val pen = ToolDefaults.configFor(Tool.PEN)
        assertEquals(false, pen.stabilize)
        assertEquals(50.0, ToolConversions.trailToStrength(pen.stabilizeTrail), 1e-9)
        assertEquals(50.0, ToolConversions.dpPerSToCrossover(pen.stabilizeCrossover), 1e-9)
        assertEquals(24.0, ToolConversions.strengthToTrail(100.0), 1e-9)
        assertEquals(0.0, ToolConversions.strengthToTrail(-5.0), 1e-9)
        assertEquals(pen.stabilizeCrossover / 3.0, ToolConversions.crossoverToDpPerS(0.0), 1e-9)
        assertEquals(pen.stabilizeCrossover * 3.0, ToolConversions.crossoverToDpPerS(100.0), 1e-9)
        for (x in listOf(0.0, 12.5, 50.0, 87.0, 100.0)) {
            assertEquals(x, ToolConversions.trailToStrength(ToolConversions.strengthToTrail(x)), 1e-9)
            assertEquals(x, ToolConversions.dpPerSToCrossover(ToolConversions.crossoverToDpPerS(x)), 1e-9)
        }
    }

    @Test fun multiplierRoundTrip() {
        // M = 4.0  <->  ds = 0.60 (calligraphy default)
        assertEquals(0.60, ToolConversions.multiplierToDirectionStrength(4.0), 1e-9)
        assertEquals(4.0, ToolConversions.directionStrengthToMultiplier(0.60), 1e-9)
        // clamp at 0.95
        assertEquals(0.95, ToolConversions.multiplierToDirectionStrength(1000.0), 1e-9)
        assertEquals(0.0, ToolConversions.multiplierToDirectionStrength(1.0), 1e-9)
    }

    @Test fun widthRanges() {
        assertEquals(4.0..120.0, ToolConversions.widthRange(Tool.HIGHLIGHTER))
        assertEquals(1.0..20.0, ToolConversions.widthRange(Tool.PEN))
    }

    @Test fun widthSnapsToHalfPixels() {
        // The label shows the width drawn, so two strokes labelled alike are alike.
        assertEquals(2.0, ToolConversions.snapWidth(Tool.PEN, 2.2), 0.0)
        assertEquals(2.5, ToolConversions.snapWidth(Tool.PEN, 2.4), 0.0)
        assertEquals(2.5, ToolConversions.snapWidth(Tool.PEN, 2.6), 0.0)
        assertEquals(3.0, ToolConversions.snapWidth(Tool.PEN, 2.8), 0.0)
        assertEquals(2.0, ToolConversions.snapWidth(Tool.PEN, 2.0), 0.0)
        // and stays inside the tool's own range
        assertEquals(20.0, ToolConversions.snapWidth(Tool.PEN, 20.7), 0.0)
        assertEquals(1.0, ToolConversions.snapWidth(Tool.PEN, 0.2), 0.0)
        assertEquals(4.0, ToolConversions.snapWidth(Tool.HIGHLIGHTER, 1.0), 0.0)
        assertEquals(120.0, ToolConversions.snapWidth(Tool.HIGHLIGHTER, 500.0), 0.0)
    }

    @Test fun strokeToolsAndAlpha() {
        assertTrue(Tool.CALLIGRAPHY.isStroke)
        assertTrue(Tool.DASHED.isStroke)
        assertFalse(Tool.SELECT.isStroke)
        assertEquals(0.35, Tool.HIGHLIGHTER.alphaScale, 1e-9)
        assertEquals(1.0, Tool.PEN.alphaScale, 1e-9)
        assertEquals(Tool.PAN, Tool.DEFAULT)
    }

    @Test fun shapeKinds() {
        assertTrue(ShapeKind.RECTANGLE.isClosed)
        assertTrue(ShapeKind.LINE.isOpen)
        assertTrue(ShapeKind.ARROW.isOpen)
        assertEquals(ShapeKind.RECTANGLE, ShapeKind.fromId("nonsense"))
        assertEquals(ShapeKind.TRIANGLE, ShapeKind.fromId("triangle"))
    }

    @Test fun theShapeToolsFillReachesClosedKindsOnly() {
        val ink = Rgba(10, 20, 30)
        val on = ShapeConfig(fill = true, fillAlpha = 0.5)
        assertEquals(Rgba(10, 20, 30, 127), on.fillFor(ShapeKind.POLYGON, ink))
        assertEquals(Rgba(10, 20, 30, 127), on.fillFor(ShapeKind.ELLIPSE, ink))
        assertNull(on.fillFor(ShapeKind.POLYLINE, ink))
        assertNull(on.fillFor(ShapeKind.CURVE, ink))
        assertNull(on.fillFor(ShapeKind.LINE, ink))
        assertNull(ShapeConfig(fill = false).fillFor(ShapeKind.RECTANGLE, ink))
    }

    @Test fun toolIdRoundTrip() {
        for (t in Tool.entries) assertEquals(t, Tool.fromId(t.id))
        assertEquals(16, Tool.wheelOrder.size)
    }
}
