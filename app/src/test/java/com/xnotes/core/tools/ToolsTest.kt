package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

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

    @Test fun multiplierRoundTrip() {
        // M = 4.0  <->  ds = 0.60 (calligraphy default)
        assertEquals(0.60, ToolConversions.multiplierToDirectionStrength(4.0), 1e-9)
        assertEquals(4.0, ToolConversions.directionStrengthToMultiplier(0.60), 1e-9)
        // clamp at 0.95
        assertEquals(0.95, ToolConversions.multiplierToDirectionStrength(1000.0), 1e-9)
        assertEquals(0.0, ToolConversions.multiplierToDirectionStrength(1.0), 1e-9)
    }

    @Test fun widthRanges() {
        assertEquals(4.0..40.0, ToolConversions.widthRange(Tool.HIGHLIGHTER))
        assertEquals(1.0..20.0, ToolConversions.widthRange(Tool.PEN))
    }

    @Test fun strokeToolsAndAlpha() {
        assertTrue(Tool.CALLIGRAPHY.isStroke)
        assertFalse(Tool.SELECT.isStroke)
        assertEquals(0.35, Tool.HIGHLIGHTER.alphaScale, 1e-9)
        assertEquals(1.0, Tool.PEN.alphaScale, 1e-9)
        assertEquals(Tool.CALLIGRAPHY, Tool.DEFAULT)
    }

    @Test fun shapeKinds() {
        assertTrue(ShapeKind.RECTANGLE.isClosed)
        assertTrue(ShapeKind.LINE.isOpen)
        assertTrue(ShapeKind.ARROW.isOpen)
        assertEquals(ShapeKind.RECTANGLE, ShapeKind.fromId("nonsense"))
        assertEquals(ShapeKind.TRIANGLE, ShapeKind.fromId("triangle"))
    }

    @Test fun toolIdRoundTrip() {
        for (t in Tool.entries) assertEquals(t, Tool.fromId(t.id))
        assertEquals(11, Tool.wheelOrder.size)
    }
}
