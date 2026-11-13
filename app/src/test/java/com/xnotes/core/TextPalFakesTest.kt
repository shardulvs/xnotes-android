package com.xnotes.core

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the FakeTextMeasurer invariants every flow-layout test depends on:
 * deterministic monospace advances and metrics that sum to the line height
 * (the same relation the Android implementation guarantees by construction).
 */
class TextPalFakesTest {
    private val measurer = FakeTextMeasurer()
    private val font = FontSpec(10.0)

    @Test
    fun metricsSumToLineHeight() {
        val m = measurer.metrics(font)
        assertEquals(measurer.lineHeight(font), m.ascent + m.descent, 1e-9)
        assertEquals(measurer.lineHeight(font), m.height, 1e-9)
    }

    @Test
    fun advancesAreUniformPerCharacter() {
        val a = measurer.advances("hello world", font)
        assertEquals(11, a.size)
        assertEquals(10.0 * FakeTextMeasurer.ADVANCE_PER_POINT, a[0], 1e-9)
        assertEquals(a.sum(), a.size * a[0], 1e-9)
        assertEquals(0, measurer.advances("", font).size)
    }

    @Test
    fun cornerScalingKeepsAutoHeightAndShrinksWithContent() {
        val item = TextItem(Pt(20.0, 30.0), width = 150.0, text = "A long paragraph of text that wraps across multiple lines", pointSize = 20.0, measurer = measurer)
        val originalHeight = item.bounds().h
        item.applyTransform(Affine.scaleAbout(Pt.ZERO, 0.5, 0.5))
        assertEquals(0.0, item.height, 1e-9)
        assertEquals(10.0, item.pointSize, 1e-9)
        assertEquals(75.0, item.width, 1e-9)
        assertEquals(originalHeight * 0.5, item.bounds().h, 1e-6)
        item.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 2.0))
        assertEquals(0.0, item.height, 1e-9)
        assertEquals(originalHeight, item.bounds().h, 1e-6)
    }

    @Test
    fun verticalEdgeResizeStillReservesHeight() {
        val item = TextItem(Pt.ZERO, width = 150.0, text = "Hello", pointSize = 20.0, measurer = measurer)
        val contentHeight = item.contentHeight()
        item.applyTransform(Affine.scaleAbout(Pt.ZERO, 1.0, 2.0))
        assertEquals(contentHeight * 2.0, item.height, 1e-6)
    }

    @Test
    fun rendererRecordsTextRuns() {
        val r = FakeRenderer()
        r.drawTextRun("hi", 4.0, 12.0, font, com.xnotes.core.model.Rgba(0, 0, 0, 255))
        assertEquals(listOf("drawTextRun:hi@4.0,12.0"), r.ops)
    }
}
