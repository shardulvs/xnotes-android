package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnTest {

    private val eps = 1e-6

    private fun stroke(vararg pts: Pt) =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), pts.map { Sample(it.x, it.y, 1.0) }.toMutableList())

    private fun assertPt(expected: Pt, actual: Pt) {
        assertEquals("x", expected.x, actual.x, 1e-3)
        assertEquals("y", expected.y, actual.y, 1e-3)
    }

    @Test fun cornersGoWhereTheTurnSends() {
        val w = 200.0
        val h = 100.0
        val right = PageTurn.affine(w, h, 1)
        assertPt(Pt(h, 0.0), right.apply(Pt(0.0, 0.0)))
        assertPt(Pt(h, w), right.apply(Pt(w, 0.0)))
        assertPt(Pt(0.0, 0.0), right.apply(Pt(0.0, h)))
        val left = PageTurn.affine(w, h, -1)
        assertPt(Pt(0.0, w), left.apply(Pt(0.0, 0.0)))
        assertPt(Pt(0.0, 0.0), left.apply(Pt(w, 0.0)))
        val half = PageTurn.affine(w, h, 2)
        assertPt(Pt(w, h), half.apply(Pt(0.0, 0.0)))
    }

    @Test fun aQuarterTurnSwapsTheSidesAndTurnsTheInk() {
        val doc = Document()
        val page = Page(200.0, 100.0)
        doc.pages.add(page)
        val ink = stroke(Pt(10.0, 20.0), Pt(30.0, 20.0))
        page.items.add(ink)
        PageTurn.turn(page, doc, 1)
        assertEquals(100.0, page.width, eps)
        assertEquals(200.0, page.height, eps)
        assertPt(Pt(80.0, 10.0), Pt(ink.sampleAt(0).x, ink.sampleAt(0).y))
        assertPt(Pt(80.0, 30.0), Pt(ink.sampleAt(1).x, ink.sampleAt(1).y))
    }

    @Test fun fourTurnsComeBackToTheStart() {
        val doc = Document()
        val page = Page(200.0, 100.0)
        doc.pages.add(page)
        val ink = stroke(Pt(10.0, 20.0), Pt(30.0, 60.0))
        page.items.add(ink)
        repeat(4) { PageTurn.turn(page, doc, 1) }
        assertEquals(200.0, page.width, eps)
        assertPt(Pt(10.0, 20.0), Pt(ink.sampleAt(0).x, ink.sampleAt(0).y))
        assertPt(Pt(30.0, 60.0), Pt(ink.sampleAt(1).x, ink.sampleAt(1).y))
    }

    @Test fun textBoxesStayUprightAndKeepTheirPlace() {
        val doc = Document()
        val page = Page(200.0, 100.0)
        doc.pages.add(page)
        val box = TextItem(Pt(0.0, 0.0), width = 40.0, height = 20.0, measurer = FakeTextMeasurer())
        page.items.add(box)
        PageTurn.turn(page, doc, 1)
        // Its centre (20, 10) goes to (90, 20); the box stays 40 wide around it.
        assertPt(Pt(70.0, 10.0), box.pos)
        assertEquals(40.0, box.width, eps)
    }

    @Test fun marginsTurnWithTheirEdges() {
        val m = PageMargins(left = 0.1, top = 0.2, right = 0.3, bottom = 0.4)
        assertEquals(PageMargins(left = 0.4, top = 0.1, right = 0.2, bottom = 0.3), PageTurn.margins(m, 1))
        assertEquals(PageMargins(left = 0.3, top = 0.4, right = 0.1, bottom = 0.2), PageTurn.margins(m, 2))
        assertEquals(PageMargins(left = 0.2, top = 0.3, right = 0.4, bottom = 0.1), PageTurn.margins(m, 3))
        assertEquals(m, PageTurn.margins(PageTurn.margins(m, 1), 3))
    }

    @Test fun inheritedMarginsStayInheritedWhenTurningLeavesThemAlone() {
        val doc = Document(margins = PageMargins(left = 0.1, top = 0.1, right = 0.1, bottom = 0.1))
        val page = Page(200.0, 100.0)
        doc.pages.add(page)
        PageTurn.turn(page, doc, 1)
        assertTrue(page.margins.isEmpty)
        doc.margins = PageMargins(left = 0.2)
        PageTurn.turn(page, doc, 1)
        assertEquals(PageMargins(top = 0.2), page.margins)
    }

    @Test fun aPdfPageTurnsItsPdfAndItsMarkups() {
        val doc = Document(dpi = 72)
        val page = Page(200.0, 100.0, pdfPage = 4)
        doc.pages.add(page)
        val quad = com.xnotes.core.pdf.TextQuad(10f, 20f, 50f, 30f, 0)
        page.markups = listOf(TextMarkup("m", MarkupType.HIGHLIGHT, Rgba(255, 0, 0, 255), 1.0, listOf(quad), "x", null, 0L, 0L))
        PageTurn.turn(page, doc, 1)
        assertEquals(1, page.pdfTurn)
        assertEquals(PdfKey.of(4, 1), page.pdfKey)
        assertEquals(com.xnotes.core.pdf.TextQuad(70f, 10f, 80f, 50f, 1), page.markups.single().quads.single())
        PageTurn.turn(page, doc, 3)
        assertEquals(0, page.pdfTurn)
        assertEquals(quad, page.markups.single().quads.single())
    }

    @Test fun aKeyCarriesTheIndexAndTheTurn() {
        val key = PdfKey.of(123_456, 3)
        assertEquals(123_456, PdfKey.index(key))
        assertEquals(3, PdfKey.turn(key))
        assertEquals(7, PdfKey.of(7, 0))
        assertEquals(PdfKey.of(7, 1), PdfKey.of(7, 5))
    }

    @Test fun aShapeComesBackExactly() {
        val doc = Document()
        val page = Page(200.0, 100.0, margins = PageMargins(left = 0.25))
        doc.pages.add(page)
        val ink = stroke(Pt(10.0, 20.0), Pt(30.0, 60.0))
        val picture = ImageItem(ImageData(java.io.File("x"), 10, 10), Rect(5.0, 5.0, 10.0, 10.0))
        page.items.addAll(listOf(ink, picture))
        val before = PageShape.of(page)
        PageTurn.turn(page, doc, 1)
        before.restore(page)
        assertEquals(200.0, page.width, eps)
        assertEquals(PageMargins(left = 0.25), page.margins)
        assertPt(Pt(10.0, 20.0), Pt(ink.sampleAt(0).x, ink.sampleAt(0).y))
        assertEquals(Rect(5.0, 5.0, 10.0, 10.0), picture.rect)
        assertEquals(0.0, picture.angle, eps)
    }
}
