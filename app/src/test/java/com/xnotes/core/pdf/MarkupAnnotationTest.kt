package com.xnotes.core.pdf

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupAnnotationTest {

    private fun markup(type: MarkupType, vararg quads: TextQuad, note: String? = null) =
        TextMarkup("id", type, Rgba(255, 128, 0), 0.4, quads.toList(), "text", note, 1L, 2L)

    // An upright A4 page with its box at the origin: user space is the page turned y up.
    private val upright = PageFrame.of(0.0, 0.0, 595.0, 842.0, 0, 72.0 / 300)

    private fun onUpright(x: Double, y: Double): Pt = upright.pointToUser(x, y)

    private val line = TextQuad(100f, 200f, 300f, 214f, 0)

    /** The operands of each line of [content] that ends in operator [op]. */
    private fun operands(content: String, op: String): List<List<Double>> =
        content.lines().filter { it.endsWith(" $op") }.map { l -> l.removeSuffix(" $op").split(' ').map { it.toDouble() } }

    @Test
    fun aHighlightFillsItsQuadsAsOnePathThroughMultiply() {
        val a = MarkupAnnotation.of(markup(MarkupType.HIGHLIGHT, line, TextQuad(100f, 212f, 180f, 226f, 0)), ::onUpright)!!
        assertArrayEquals(
            floatArrayOf(100f, 642f, 300f, 642f, 100f, 628f, 300f, 628f, 100f, 630f, 180f, 630f, 100f, 616f, 180f, 616f),
            a.quadPoints, 1e-4f,
        )
        assertEquals(Rect.ltrb(100.0, 616.0, 300.0, 642.0), a.rect)
        val ops = a.content.lines()
        assertEquals(listOf("q", "/GS0 gs", "1 0.502 0 rg"), ops.take(3))
        // One fill for both lines, so where they overlap the colour goes on once.
        assertEquals(1, ops.count { it == "f" })
        assertEquals(2, ops.count { it == "h" })
        assertEquals(listOf(listOf(100.0, 642.0), listOf(100.0, 630.0)), operands(a.content, "m"))
        assertTrue(a.content.endsWith("f\nQ\n"))
    }

    @Test
    fun aLineIsStrokedRoundWhereTheScreenDrawsIt() {
        val a = MarkupAnnotation.of(markup(MarkupType.UNDERLINE, line), ::onUpright)!!
        assertTrue(a.content.contains("1 J\n1 j\n"))
        assertEquals(listOf(listOf(1.0, 0.502, 0.0)), operands(a.content, "RG"))
        // 14 pt tall: a 1 pt line, 1 pt above the glyphs' feet at y 214.
        assertEquals(listOf(listOf(1.0)), operands(a.content, "w"))
        assertEquals(listOf(listOf(100.0, 629.0)), operands(a.content, "m"))
        assertEquals(listOf(listOf(300.0, 629.0)), operands(a.content, "l"))
        // Round caps reach half a width past the quad.
        assertEquals(Rect.ltrb(99.5, 627.5, 300.5, 642.5), a.rect)
        assertFalse(a.content.contains(" gs"))
    }

    @Test
    fun aSquiggleFollowsThePaintersWave() {
        val a = MarkupAnnotation.of(markup(MarkupType.SQUIGGLY, line), ::onUpright)!!
        val wave = MarkupPainter.lineOf(MarkupType.SQUIGGLY, line).map { onUpright(it.x, it.y) }
        val drawn = operands(a.content, "m") + operands(a.content, "l")
        assertEquals(wave.size, drawn.size)
        for ((p, d) in wave.zip(drawn)) {
            assertEquals(p.x, d[0], 1e-3)
            assertEquals(p.y, d[1], 1e-3)
        }
    }

    @Test
    fun aNoteAddsNothingToTheAppearance() {
        val plain = MarkupAnnotation.of(markup(MarkupType.STRIKEOUT, line), ::onUpright)!!
        val noted = MarkupAnnotation.of(markup(MarkupType.STRIKEOUT, line, note = "see eq. 4"), ::onUpright)!!
        assertEquals(plain.content, noted.content)
        assertEquals(plain.rect, noted.rect)
    }

    @Test
    fun aTurnedPageGetsTheQuadsItShows() {
        // /Rotate 90 on an offset box: the QuadPoints, read back through the page's map, are the quads as displayed.
        val frame = PageFrame.of(10.0, 20.0, 622.0, 812.0, 90, 72.0 / 300)
        val shown = PdfPageGeometry.of(10.0, 20.0, 622.0, 812.0, 90)
        val down = TextQuad(400f, 100f, 414f, 380f, 1)
        val a = MarkupAnnotation.of(markup(MarkupType.HIGHLIGHT, line, down), frame::pointToUser)!!
        val back = a.quadPoints.toList().chunked(2).map { (x, y) -> shown.toDisplay(x.toDouble(), y.toDouble()) }
        for ((e, b) in (MarkupPainter.corners(line) + MarkupPainter.corners(down)).zip(back)) {
            assertEquals(e.x, b.x, 1e-3)
            assertEquals(e.y, b.y, 1e-3)
        }
        for ((x, y) in a.quadPoints.toList().chunked(2)) {
            assertTrue(x >= a.rect.left - 1e-3 && x <= a.rect.right + 1e-3 && y >= a.rect.top - 1e-3 && y <= a.rect.bottom + 1e-3)
        }
    }

    @Test
    fun noQuadsNoAnnotation() {
        assertNull(MarkupAnnotation.of(markup(MarkupType.HIGHLIGHT), ::onUpright))
    }
}
