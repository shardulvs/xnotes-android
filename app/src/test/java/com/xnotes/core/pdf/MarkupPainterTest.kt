package com.xnotes.core.pdf

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupPainterTest {

    /** Records what a markup paints, in the renderer's own coordinates. */
    private class Recorder : Renderer {
        val ops = ArrayList<String>()
        val layers = ArrayList<Triple<Rect, Double, BlendMode>>()
        val fills = ArrayList<Pair<Rect, Rgba>>()
        val lines = ArrayList<Pair<List<Pt>, Pen>>()
        var scale = 1.0

        override fun save() { ops += "save" }
        override fun restore() { ops += "restore" }
        override fun saveLayerAlpha(bounds: Rect, alpha: Double) { ops += "layerAlpha" }
        override fun saveLayerBlended(bounds: Rect, alpha: Double, blend: BlendMode) {
            ops += "layer"
            layers += Triple(bounds, alpha, blend)
        }
        override fun translate(dx: Double, dy: Double) {}
        override fun scale(sx: Double, sy: Double) {
            ops += "scale"
            scale *= sx
        }
        override fun clipRect(rect: Rect) {}
        override fun clear() {}
        override fun fillBackground(rect: Rect, color: Rgba) {}
        override fun fillRect(rect: Rect, color: Rgba) {
            ops += "fill"
            fills += rect to color
        }
        override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) { ops += "polygon" }
        override fun fillCircle(center: Pt, radius: Double, color: Rgba) { ops += "circle" }
        override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) {}
        override fun strokeRect(rect: Rect, pen: Pen) {}
        override fun strokePolyline(points: List<Pt>, pen: Pen) {
            ops += "line"
            lines += points to pen
        }
        override fun strokePolygon(points: List<Pt>, pen: Pen) {}
        override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) {}
        override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {}
        override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) {}
    }

    private val green = Rgba(0, 230, 118)

    private fun markup(type: MarkupType, vararg quads: TextQuad, note: String? = null) =
        TextMarkup("id", type, green, 0.5, quads.toList(), "text", note, 0L, 0L)

    // A line 14 pt tall, so lines are 1 pt thick.
    private val upright = TextQuad(100f, 200f, 240f, 214f, 0)

    private fun near(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test
    fun aHighlightMultipliesOneLayerAtItsIntensity() {
        val r = Recorder()
        val second = TextQuad(100f, 213f, 180f, 227f, 0)
        MarkupPainter.paint(r, markup(MarkupType.HIGHLIGHT, upright, second))
        assertEquals(listOf("layer", "fill", "fill", "restore"), r.ops)
        val (bounds, alpha, blend) = r.layers.single()
        assertEquals(Rect.ltrb(100.0, 200.0, 240.0, 227.0), bounds)
        near(0.5, alpha)
        assertEquals(BlendMode.MULTIPLY, blend)
        assertEquals(Rect.ltrb(100.0, 200.0, 240.0, 214.0), r.fills[0].first)
        assertEquals(255, r.fills[0].second.a)
    }

    @Test
    fun paintingScalesPointsToThePixelsAsked() {
        val r = Recorder()
        MarkupPainter.paint(r, listOf(markup(MarkupType.UNDERLINE, upright)), 150.0 / 72)
        assertEquals(listOf("save", "scale", "line", "restore"), r.ops)
        near(150.0 / 72, r.scale)
        MarkupPainter.paint(r, emptyList(), 2.0)
        assertEquals(4, r.ops.size)
    }

    @Test
    fun anUnderlineRunsAlongTheFeetOfAnUprightLine() {
        val line = MarkupPainter.lineOf(MarkupType.UNDERLINE, upright)
        assertEquals(listOf(Pt(100.0, 213.0), Pt(240.0, 213.0)), line)
        near(1.0, MarkupPainter.thickness(upright))
        val r = Recorder()
        MarkupPainter.paint(r, markup(MarkupType.UNDERLINE, upright))
        assertFalse(r.lines.single().second.cosmetic)
        assertEquals(green, r.lines.single().second.color)
    }

    @Test
    fun aStrikeoutCrossesTheMiddle() {
        assertEquals(listOf(Pt(100.0, 207.0), Pt(240.0, 207.0)), MarkupPainter.lineOf(MarkupType.STRIKEOUT, upright))
    }

    @Test
    fun turnedTextIsUnderlinedOnTheSideItsFeetFace() {
        // Turned a quarter clockwise the glyphs' feet face left; half a turn, up; three quarters, right.
        val down = TextQuad(300f, 100f, 314f, 240f, 1)
        assertEquals(listOf(Pt(301.0, 100.0), Pt(301.0, 240.0)), MarkupPainter.lineOf(MarkupType.UNDERLINE, down))
        val upsideDown = TextQuad(100f, 200f, 240f, 214f, 2)
        assertEquals(listOf(Pt(240.0, 201.0), Pt(100.0, 201.0)), MarkupPainter.lineOf(MarkupType.UNDERLINE, upsideDown))
        val up = TextQuad(300f, 100f, 314f, 240f, 3)
        assertEquals(listOf(Pt(313.0, 240.0), Pt(313.0, 100.0)), MarkupPainter.lineOf(MarkupType.UNDERLINE, up))
        near(1.0, MarkupPainter.thickness(down))
    }

    @Test
    fun aSquiggleWavesAlongTheWholeLineInsideIt() {
        for (quarter in 0..3) {
            val q = if (quarter % 2 == 0) TextQuad(100f, 200f, 240f, 214f, quarter) else TextQuad(300f, 100f, 314f, 240f, quarter)
            val wave = MarkupPainter.lineOf(MarkupType.SQUIGGLY, q)
            val box = Rect.ltrb(q.left.toDouble(), q.top.toDouble(), q.right.toDouble(), q.bottom.toDouble()).outset(1e-9)
            assertTrue(wave.all { box.contains(it) })
            assertEquals(MarkupPainter.frame(q, 0.0, 0.0).let { if (quarter % 2 == 0) it.x else it.y }, wave.first().let { if (quarter % 2 == 0) it.x else it.y }, 1e-9)
            assertEquals(MarkupPainter.frame(q, 140.0, 0.0).let { if (quarter % 2 == 0) it.x else it.y }, wave.last().let { if (quarter % 2 == 0) it.x else it.y }, 1e-9)
        }
        // Upright: feet at y = 214, 1 pt thick, so the wave's lowest point is half a point above them.
        val ys = MarkupPainter.lineOf(MarkupType.SQUIGGLY, upright).map { it.y }
        near(213.5, ys.max())
        near(213.5 - 2 * 14.0 / 12, ys.min())
    }

    @Test
    fun tinyTextKeepsAVisibleLine() {
        near(0.5, MarkupPainter.thickness(TextQuad(0f, 0f, 10f, 3f, 0)))
    }

    @Test
    fun aNoteLeavesThePageAlone() {
        val first = TextQuad(100f, 180f, 300f, 194f, 0)
        val plain = Recorder()
        val noted = Recorder()
        MarkupPainter.paint(plain, markup(MarkupType.UNDERLINE, first, upright))
        MarkupPainter.paint(noted, markup(MarkupType.UNDERLINE, first, upright, note = "check eq. 4"))
        assertEquals(listOf("line", "line"), noted.ops)
        assertEquals(plain.lines.map { it.first }, noted.lines.map { it.first })
    }

    @Test
    fun theBoundsAreTheQuads() {
        assertEquals(Rect.ltrb(100.0, 200.0, 240.0, 214.0), MarkupPainter.bounds(markup(MarkupType.SQUIGGLY, upright)))
        assertEquals(Rect.ltrb(100.0, 200.0, 240.0, 214.0), MarkupPainter.bounds(markup(MarkupType.SQUIGGLY, upright, note = "n")))
        assertNull(MarkupPainter.bounds(markup(MarkupType.HIGHLIGHT)))
    }

    @Test
    fun aTapFindsTheTopmostMarkup() {
        val under = markup(MarkupType.HIGHLIGHT, upright)
        val over = markup(MarkupType.UNDERLINE, TextQuad(150f, 200f, 300f, 214f, 0), note = "n")
        val marks = listOf(under, over)
        assertEquals(under, MarkupPainter.markupAt(marks, 120.0, 207.0, 2.0))
        assertEquals(over, MarkupPainter.markupAt(marks, 200.0, 207.0, 2.0))
        assertEquals(under, MarkupPainter.markupAt(marks, 99.0, 199.0, 2.0))
        assertNull(MarkupPainter.markupAt(marks, 120.0, 230.0, 2.0))
        assertNull(MarkupPainter.markupAt(marks, 303.0, 207.0, 2.0))
    }

    @Test
    fun theEraserTakesAMarkOnlyWhereItIsDrawn() {
        val highlight = markup(MarkupType.HIGHLIGHT, upright)
        assertTrue(MarkupPainter.touches(highlight, 170.0, 220.0, 6.5))
        assertFalse(MarkupPainter.touches(highlight, 170.0, 220.0, 5.5))
        // The underline runs at y = 213, a point thick: the rest of the line's box is not it.
        val underline = markup(MarkupType.UNDERLINE, upright)
        assertFalse(MarkupPainter.touches(underline, 170.0, 205.0, 2.0))
        assertTrue(MarkupPainter.touches(underline, 170.0, 210.0, 2.6))
        assertFalse(MarkupPainter.touches(underline, 170.0, 210.0, 2.4))
        // The squiggle waves between about 211.2 and 213.5.
        val squiggly = markup(MarkupType.SQUIGGLY, upright)
        assertFalse(MarkupPainter.touches(squiggly, 170.0, 202.0, 2.0))
        assertTrue(MarkupPainter.touches(squiggly, 170.0, 212.3, 1.5))
        // Turned a quarter, the underline runs down x = 301.
        val down = markup(MarkupType.UNDERLINE, TextQuad(300f, 100f, 314f, 240f, 1))
        assertTrue(MarkupPainter.touches(down, 304.0, 170.0, 2.6))
        assertFalse(MarkupPainter.touches(down, 310.0, 170.0, 2.0))
        // A note is no part of what the eraser can reach past the line's end.
        assertFalse(MarkupPainter.touches(markup(MarkupType.STRIKEOUT, upright, note = "n"), 245.0, 203.0, 1.5))
    }
}
