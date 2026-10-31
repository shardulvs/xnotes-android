package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.pal.FontFace
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.RestyleItems
import com.xnotes.core.stroke.Sample
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DrawStyleTest {

    private val red = Rgba(220, 40, 40)
    private val blue = Rgba(40, 80, 220)

    private fun stroke() = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN).copy(rgba = red, baseWidth = 3.0),
        mutableListOf(Sample(0.0, 0.0, 1.0), Sample(20.0, 5.0, 1.0)),
    )

    private fun shape() = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(10.0, 10.0), red, 2.0)

    @Test fun `a stroke reports and takes back its colour and width`() {
        val s = stroke()
        assertEquals(DrawStyle(red, 3.0), DrawStyle.of(s))
        DrawStyle(blue, 8.0).applyTo(s)
        assertEquals(DrawStyle(blue, 8.0), DrawStyle.of(s))
        assertEquals(blue, s.config.rgba)
    }

    @Test fun `a shape fill follows the outline colour and keeps its own alpha`() {
        val s = shape().apply { fillRgba = red.copy(a = 64) }
        DrawStyle(blue, 5.0).applyTo(s)
        assertEquals(blue.copy(a = 64), s.fillRgba)
        assertEquals(blue, s.strokeRgba)
        assertEquals(5.0, s.strokeWidth, 1e-9)
    }

    @Test fun `an unfilled shape stays unfilled`() {
        val s = shape()
        DrawStyle(blue, 5.0).applyTo(s)
        assertNull(s.fillRgba)
    }

    @Test fun `items with no colour and width have no style`() {
        val image = ImageItem(ImageData(java.io.File("none"), 4, 4), com.xnotes.core.geometry.Rect(0.0, 0.0, 4.0, 4.0))
        assertNull(DrawStyle.of(image))
        assertNotNull(DrawStyle.of(stroke()))
    }

    @Test fun `restyling an editable text box preserves its invisible optimized host`() {
        val host = ShapeItem(ShapeKind.RECTANGLE, Pt(10.0, 20.0), Pt(250.0, 100.0),
            red.copy(a = 0), 0.0).apply {
            label = ShapeLabel("Test text", TextStyle(red, 18.0, FontFace.SANS), FakeTextMeasurer())
        }
        val before = DrawStyle.of(host)!!
        assertEquals(DrawStyle(red, 18.0), before)
        val after = DrawStyle(blue, 24.0)
        val command = RestyleItems(listOf(RestyleItems.Entry(host, before, after)))
        command.redo()
        assertEquals(after, DrawStyle.of(host))
        assertEquals(true, host.isTextOnlyBox())
        assertEquals(0.0, host.strokeWidth, 0.0)
        assertEquals(0, host.strokeRgba.a)
        assertNull(host.fillRgba)
        command.undo()
        assertEquals(before, DrawStyle.of(host))
        assertEquals(true, host.isTextOnlyBox())
    }

    @Test fun `undo restores each item's own former style`() {
        val a = stroke()
        val b = shape().apply { strokeRgba = blue; strokeWidth = 7.0 }
        val entries = listOf(a, b).map { item ->
            val before = DrawStyle.of(item)!!
            RestyleItems.Entry(item, before, DrawStyle(Rgba(0, 200, 0), 12.0))
        }
        val command = RestyleItems(entries)
        command.redo()
        assertEquals(DrawStyle(Rgba(0, 200, 0), 12.0), DrawStyle.of(a))
        assertEquals(DrawStyle(Rgba(0, 200, 0), 12.0), DrawStyle.of(b))
        command.undo()
        assertEquals(DrawStyle(red, 3.0), DrawStyle.of(a))
        assertEquals(DrawStyle(blue, 7.0), DrawStyle.of(b))
    }
}
