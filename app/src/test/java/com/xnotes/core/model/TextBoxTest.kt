package com.xnotes.core.model

import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pal.BoxTextHost
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowEditor
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.ParaAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBoxTest {
    private val m = FakeTextMeasurer()
    private val adv = 10.0 * FakeTextMeasurer.ADVANCE_PER_POINT

    private fun box(text: String, width: Double = 300.0, auto: Boolean = false) =
        TextItem(Pt(100.0, 100.0), width = width, text = text, rgba = Rgba(0, 0, 0), pointSize = 10.0, measurer = m, autoWidth = auto)

    @Test fun theBoxStandsAnInsetOutFromItsText() {
        val t = box("hello")
        val text = t.textRect()
        val b = t.bounds()
        assertEquals(Pt(100.0, 100.0), Pt(text.left, text.top))
        assertEquals(text.left - TextItem.PAD_X, b.left, 1e-9)
        assertEquals(text.top - TextItem.PAD_Y, b.top, 1e-9)
        assertEquals(text.w + 2 * TextItem.PAD_X, b.w, 1e-9)
        assertEquals(text.h + 2 * TextItem.PAD_Y, b.h, 1e-9)
    }

    @Test fun aSetWidthWrapsAndGrowsDownward() {
        val one = box("aaaa bbbb", width = 300.0)
        val two = box("aaaa bbbb", width = 6 * adv)
        assertEquals(300.0, one.textRect().w, 1e-9)
        assertEquals(2.0 * one.textRect().h, two.textRect().h, 1e-9)
    }

    @Test fun aHuggingBoxIsAsWideAsItsLongestLineAndNeverWraps() {
        val t = box("aaaa bbbb cccc\ndd", auto = true)
        val lines = t.content.frame.lines
        assertEquals(2, lines.size)
        val w = t.textRect().w
        assertTrue(w >= 14 * adv && w < 14 * adv + 3.0)
    }

    @Test fun aHuggingBoxGrowsAsItsTextDoes() {
        val t = box("ab", auto = true)
        val before = t.textRect().w
        FlowEditor(t.flow).insertText(FlowPos(0, 2), "cdefghijklmnop")
        assertEquals(before, t.textRect().w, 1e-9) // nothing published until told
        t.reflow()
        assertTrue(t.textRect().w > before + 10 * adv)
    }

    @Test fun anEmptyBoxStillStandsOneLineTall() {
        val t = box("", auto = true)
        assertEquals(m.lineHeight(t.font), t.textRect().h, 1e-9)
        assertEquals(TextItem.MIN_TEXT_WIDTH, t.textRect().w, 1e-9)
        assertEquals("", t.text)
    }

    @Test fun plainTextRoundTripsThroughTheFlow() {
        val t = box("one\n\nthree")
        assertEquals(3, t.flow.paragraphs.size)
        assertEquals("one\n\nthree", t.text)
        t.text = "x"
        assertEquals(1, t.flow.paragraphs.size)
        assertEquals("x", t.text)
    }

    @Test fun styledRunsPaintAsTheirOwnPieces() {
        val t = box("bold plain")
        FlowEditor(t.flow).setCharStyle(FlowRange(FlowPos(0, 0), FlowPos(0, 4))) { it.copy(bold = true) }
        t.reflow()
        val r = FakeRenderer()
        t.paint(r)
        assertTrue(r.ops.any { it.startsWith("drawTextRun:bold@") })
        assertTrue(r.ops.any { it.startsWith("drawTextRun:plain@") })
        assertTrue(t.content.frame.lines[0].second.segs.first().font.bold)
    }

    @Test fun centredLinesCentreInTheBox() {
        val t = box("ab", width = 200.0)
        FlowEditor(t.flow).setParaStyle(FlowRange.caret(FlowPos.START)) { it.align = ParaAlign.CENTER }
        t.reflow()
        val line = t.content.frame.lines[0].second
        assertEquals((200.0 - 2 * adv) / 2.0, line.xs.first(), 1e-9)
    }

    @Test fun aBareBoxPaintsNoFrame() {
        val r = FakeRenderer()
        box("x").paint(r)
        assertFalse(r.ops.contains("fillRect"))
        assertFalse(r.ops.contains("strokeRect"))
        assertFalse(r.ops.contains("fillPolygon"))
    }

    @Test fun fillAndBorderPaintUnderTheText() {
        val t = box("x")
        t.box = BoxStyle(fill = Rgba(255, 255, 0), border = Rgba(0, 0, 0), borderWidth = 2.0)
        val r = FakeRenderer()
        t.paint(r)
        val fill = r.ops.indexOf("fillRect")
        val stroke = r.ops.indexOf("strokeRect")
        val text = r.ops.indexOfFirst { it.startsWith("drawTextRun:x@") }
        assertTrue(fill in 0 until text)
        assertTrue(stroke in 0 until text)
        assertEquals(t.bounds().outset(1.0), t.paintBounds())
    }

    @Test fun roundedCornersPaintAsAPolygon() {
        val t = box("x")
        t.box = BoxStyle(fill = Rgba(255, 255, 0), corner = 6.0)
        val r = FakeRenderer()
        t.paint(r)
        assertTrue(r.ops.contains("fillPolygon"))
        val ring = TextItem.roundedRect(t.bounds(), 6.0)
        assertTrue(ring.all { t.bounds().outset(1e-6).contains(it) })
    }

    @Test fun aBorderWithNoThicknessIsNoBorder() {
        val t = box("x")
        t.box = BoxStyle(border = Rgba(0, 0, 0), borderWidth = 0.0)
        val r = FakeRenderer()
        t.paint(r)
        assertFalse(r.ops.contains("strokeRect"))
        assertEquals(t.bounds(), t.paintBounds())
    }

    @Test fun cornerScaleGrowsEveryTextSize() {
        val t = box("big small", auto = true)
        FlowEditor(t.flow).setCharStyle(FlowRange(FlowPos(0, 0), FlowPos(0, 3))) { it.copy(sizePt = 20.0) }
        t.reflow()
        val snap = t.snapshotGeometry()
        val b = t.bounds()
        t.applyTransform(Affine.scaleAbout(Pt(b.left, b.top), 2.0, 2.0))
        assertEquals(20.0, t.pointSize, 1e-9)
        assertEquals(40.0, t.flow.paragraphs[0].runs[0].style.sizePt!!, 1e-9)
        assertTrue(t.autoWidth)
        assertEquals(b.left, t.bounds().left, 1e-9)
        t.restoreGeometry(snap)
        assertEquals(10.0, t.pointSize, 1e-9)
        assertEquals(20.0, t.flow.paragraphs[0].runs[0].style.sizePt!!, 1e-9)
        assertEquals(b, t.bounds())
    }

    @Test fun aSideScaleEndsHugging() {
        val t = box("hello there", auto = true)
        val b = t.bounds()
        t.applyTransform(Affine.scaleAbout(Pt(b.left, b.top), 0.5, 1.0))
        assertFalse(t.autoWidth)
        assertEquals(b.w * 0.5, t.bounds().w, 1e-9)
        assertEquals(10.0, t.pointSize, 1e-9)
    }

    @Test fun aCopyHasItsOwnText() {
        val t = box("one")
        t.box = BoxStyle(fill = Rgba(1, 2, 3))
        val c = t.copy()
        assertNotSame(t.flow, c.flow)
        c.text = "two"
        assertEquals("one", t.text)
        assertEquals(t.box, c.box)
        assertEquals(t.bounds().left, c.bounds().left, 1e-9)
    }

    @Test fun whatWasPublishedIsOutOfAnEditsReach() {
        val t = box("one")
        val frozen = t.content.frozen
        FlowEditor(t.flow).insertText(FlowPos(0, 3), " two")
        t.reflow()
        assertEquals("one", frozen.plainText())
        assertEquals("one two", t.content.frozen.plainText())
    }

    @Test fun aBoxFollowingTheThemeTakesItsColour() {
        var auto = Rgba(10, 10, 10)
        val host = object : TextMeasurer by m, BoxTextHost {
            override fun autoColor(): Rgba = auto
        }
        val t = TextItem(Pt(0.0, 0.0), text = "x", rgba = null, measurer = host)
        assertEquals(Rgba(10, 10, 10), t.rgba)
        auto = Rgba(240, 240, 240)
        t.reflow()
        assertEquals(Rgba(240, 240, 240), t.rgba)
        assertEquals(null, t.color)
        t.rgba = Rgba(255, 0, 0)
        assertEquals(Rgba(255, 0, 0), t.color)
    }

    @Test fun defaultStyleIsUntouchedByOrdinaryText() {
        val t = box("x")
        assertEquals(CharStyle.DEFAULT, t.flow.paragraphs[0].runs[0].style)
    }

    @Test fun aHuggingBoxGrowsAwayFromTheEdgeItIsAlignedTo() {
        val left = box("ab", auto = true)
        val centre = box("ab", auto = true)
        val right = box("ab", auto = true)
        FlowEditor(centre.flow).setParaStyle(FlowRange.caret(FlowPos.START)) { it.align = ParaAlign.CENTER }
        FlowEditor(right.flow).setParaStyle(FlowRange.caret(FlowPos.START)) { it.align = ParaAlign.RIGHT }
        for (t in listOf(left, centre, right)) t.reflow()
        val mid = centre.bounds().centerX
        val edge = right.bounds().right
        for (t in listOf(left, centre, right)) {
            FlowEditor(t.flow).insertText(FlowPos(0, 2), "cdefghij")
            t.retype()
        }
        assertEquals(100.0, left.pos.x, 1e-9)
        assertEquals(mid, centre.bounds().centerX, 1e-9)
        assertEquals(edge, right.bounds().right, 1e-9)
    }

    @Test fun aSetWidthBoxStaysPutAsItIsTyped() {
        val t = box("ab", width = 200.0)
        FlowEditor(t.flow).setParaStyle(FlowRange.caret(FlowPos.START)) { it.align = ParaAlign.CENTER }
        FlowEditor(t.flow).insertText(FlowPos(0, 2), "cdefghij")
        t.retype()
        assertEquals(100.0, t.pos.x, 1e-9)
    }

    @Test fun aBoxEditUndoesTheTextAndTheLayoutTogether() {
        val t = box("ab", auto = true)
        val before = t.bounds()
        val (cmd, _) = FlowEditor(t.flow).insertText(FlowPos(0, 2), "cdefghij")
        t.retype()
        val edit = com.xnotes.core.history.BoxEdit(t, cmd!!)
        assertTrue(t.bounds().w > before.w)
        edit.undo()
        assertEquals("ab", t.text)
        assertEquals(before, t.bounds())
        edit.redo()
        assertEquals("abcdefghij", t.text)
    }

    @Test fun rebasingChangesTheBaseStyleAndGivesItBack() {
        val t = TextItem(Pt(0.0, 0.0), text = "x", rgba = null, pointSize = 10.0, measurer = m)
        val old = com.xnotes.core.history.BoxBase.of(t)
        val cmd = com.xnotes.core.history.BoxEdit(t, com.xnotes.core.history.RebaseBox(t, old, old.copy(color = Rgba(9, 9, 9), sizePt = 20.0)))
        cmd.redo()
        assertEquals(Rgba(9, 9, 9), t.color)
        assertEquals(20.0, t.pointSize, 1e-9)
        assertEquals(m.lineHeight(t.font), t.textRect().h, 1e-9)
        cmd.undo()
        // A box following the theme goes back to following it, not to the colour that resolved to.
        assertEquals(null, t.color)
        assertEquals(10.0, t.pointSize, 1e-9)
    }

    @Test fun restylingTheFrameIsOneStepEitherWay() {
        val t = box("x")
        val framed = BoxStyle(fill = Rgba(1, 2, 3), corner = 4.0)
        val cmd = com.xnotes.core.history.RestyleBox(t, t.box, framed)
        cmd.redo()
        assertEquals(framed, t.box)
        cmd.undo()
        assertEquals(BoxStyle.NONE, t.box)
    }

    /** A backend that writes real text, as the PDF export does, recording what a box asks of it. */
    private class Writer(val inner: FakeRenderer = FakeRenderer()) : com.xnotes.core.pal.Renderer by inner {
        val links = mutableListOf<Triple<Int, com.xnotes.core.geometry.Rect, String>>()
        var marks = 0
        override val writesText: Boolean get() = true

        override fun beginMark(mark: com.xnotes.core.pal.Mark) {
            marks++
        }

        override fun linkArea(index: Int, rect: com.xnotes.core.geometry.Rect, uri: String) {
            links += Triple(index, rect, uri)
        }
    }

    @Test fun aWrittenBoxKeepsItsSpacesAndAnnouncesNoMarksOfItsOwn() {
        val w = Writer()
        box("one two").paint(w)
        val runs = w.inner.ops.filter { it.startsWith("drawTextRun:") }.map { it.substringBefore('@') }
        assertEquals(listOf("drawTextRun:one", "drawTextRun: ", "drawTextRun:two"), runs)
        // The exporter marks the whole box as one; its lines add none inside that.
        assertEquals(0, w.marks)
    }

    @Test fun anAddressInABoxIsALinkWhereItIsDrawn() {
        val t = box("see https://example.org now")
        val w = Writer()
        t.paint(w)
        assertEquals(1, w.links.size)
        val (index, rect, uri) = w.links[0]
        assertEquals(0, index)
        assertEquals("https://example.org", uri)
        assertEquals(t.pos.x + 4 * adv, rect.left, 1e-6)
        assertEquals(19 * adv, rect.w, 1e-6)
        assertTrue(t.textRect().outset(1e-6).contains(Pt(rect.left, rect.top)))
    }

    @Test fun theScreenIsAskedForNoLinks() {
        val r = FakeRenderer()
        box("see https://example.org now").paint(r)
        assertFalse(r.ops.any { it.startsWith("linkArea") })
    }

    @Test fun aSnapshotOfAnUnchangedBoxEqualsTheLast() {
        // A handle pressed and let go without a drag must read as no change.
        val t = box("same", auto = true)
        assertEquals(t.snapshotGeometry(), t.snapshotGeometry())
        val before = t.snapshotGeometry()
        t.translate(1.0, 0.0)
        assertFalse(before == t.snapshotGeometry())
    }

    @Test fun scalingFromACornerHoldsTheOppositeOneStill() {
        val t = box("hold this corner", auto = true)
        val b = t.bounds()
        // Dragging the top-left handle scales about the bottom-right corner.
        t.applyTransform(Affine.scaleAbout(Pt(b.right, b.bottom), 1.5, 1.5))
        assertEquals(b.right, t.bounds().right, 1e-9)
        assertEquals(b.bottom, t.bounds().bottom, 1e-9)
        assertEquals(15.0, t.pointSize, 1e-9)
    }

    @Test fun aSideDragHoldsTheOtherSideStill() {
        val t = box("one two three four", width = 200.0)
        val b = t.bounds()
        // Dragging the left handle inward scales along x about the right edge; the text wraps taller.
        t.applyTransform(Affine.scaleAbout(Pt(b.right, b.top), 0.4, 1.0))
        assertEquals(b.right, t.bounds().right, 1e-9)
        assertEquals(b.top, t.bounds().top, 1e-9)
        assertTrue(t.bounds().h > b.h)
    }

    @Test fun aBoxInAScaledGroupMovesWithItsNearestCorner() {
        val t = box("x", width = 100.0)
        val b = t.bounds()
        // The group's still point is its own corner, up and left of this box.
        t.applyTransform(Affine.scaleAbout(Pt(0.0, 0.0), 2.0, 2.0))
        assertEquals(b.left * 2.0, t.bounds().left, 1e-9)
        assertEquals(b.top * 2.0, t.bounds().top, 1e-9)
    }

    @Test fun aMoveOnlyMoves() {
        val t = box("x", auto = true)
        val b = t.bounds()
        t.applyTransform(Affine.translateBy(7.0, -3.0))
        assertEquals(b.translate(7.0, -3.0), t.bounds())
        assertTrue(t.autoWidth)
    }
}
