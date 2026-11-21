package com.xnotes.core.model

import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePaperTest {

    private val paper = Rgba(250, 250, 250)
    private val red = Rgba(200, 0, 0)
    private val blue = Rgba(0, 0, 200)

    private fun assertRects(expected: List<Rect>, actual: List<Rect>) {
        assertEquals(expected.size, actual.size)
        for ((e, a) in expected.zip(actual)) {
            assertEquals(e.x, a.x, 1e-9)
            assertEquals(e.y, a.y, 1e-9)
            assertEquals(e.w, a.w, 1e-9)
            assertEquals(e.h, a.h, 1e-9)
        }
    }

    @Test fun defaultPaperHasNoStrips() {
        val doc = Document()
        val page = Page(200.0, 300.0, margins = PageMargins(left = 0.1, top = 0.1), rooms = listOf(Room(100.0, 50.0)))
        doc.pages += page
        assertTrue(page.paperStrips(doc, paper).isEmpty())
    }

    @Test fun marginsTakeTheirColourRoundThePage() {
        val doc = Document()
        doc.margins = PageMargins(left = 0.1, top = 0.1, color = blue)
        val page = Page(200.0, 300.0)
        doc.pages += page
        val strips = page.paperStrips(doc, paper).single()
        assertEquals(blue, strips.color)
        assertRects(listOf(Rect(-20.0, -30.0, 220.0, 30.0), Rect(-20.0, 0.0, 20.0, 300.0)), strips.rects)
    }

    @Test fun aPageMarginColourOverridesTheNotes() {
        val doc = Document()
        doc.margins = PageMargins(top = 0.1, color = blue)
        val page = Page(200.0, 300.0, margins = PageMargins(color = red))
        doc.pages += page
        assertEquals(red, page.paperStrips(doc, paper).single().color)
    }

    @Test fun aRoomRunsAcrossTheMarginsInItsOwnColour() {
        val doc = Document()
        val page = Page(200.0, 350.0, margins = PageMargins(left = 0.1, right = 0.1, color = blue), rooms = listOf(Room(100.0, 50.0, color = red)))
        doc.pages += page
        val strips = page.paperStrips(doc, paper)
        assertEquals(listOf(blue, red), strips.map { it.color })
        assertRects(listOf(Rect(-20.0, 100.0, 240.0, 50.0)), strips[1].rects)
    }

    @Test fun aDefaultRoomShowsThePaperAcrossColouredMargins() {
        val doc = Document()
        val page = Page(200.0, 350.0, margins = PageMargins(left = 0.1, color = blue), rooms = listOf(Room(100.0, 50.0)))
        doc.pages += page
        val strips = page.paperStrips(doc, paper)
        assertEquals(listOf(blue, paper), strips.map { it.color })
    }

    @Test fun aColouredRoomWithoutMarginsIsJustItsBand() {
        val doc = Document()
        val page = Page(200.0, 350.0, rooms = listOf(Room(100.0, 50.0, color = red), Room(200.0, 10.0)))
        doc.pages += page
        val strips = page.paperStrips(doc, paper).single()
        assertEquals(red, strips.color)
        assertRects(listOf(Rect(0.0, 100.0, 200.0, 50.0)), strips.rects)
    }

    @Test fun aSidewaysRoomStandsDownThePage() {
        val doc = Document()
        val page = Page(250.0, 300.0, rooms = listOf(Room(100.0, 50.0, sideways = true, color = red)))
        doc.pages += page
        assertRects(listOf(Rect(100.0, 0.0, 50.0, 300.0)), page.paperStrips(doc, paper).single().rects)
    }
}
