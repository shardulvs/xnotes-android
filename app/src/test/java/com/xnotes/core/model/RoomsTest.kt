package com.xnotes.core.model

import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsTest {

    private val eps = 1e-9

    @Test fun aCutOpensARoomAndPushesTheRoomsAfterIt() {
        val one = Rooms.opened(emptyList(), 100.0, 50.0)
        assertEquals(listOf(Room(100.0, 50.0)), one)
        val two = Rooms.opened(one, 20.0, 10.0)
        assertEquals(listOf(Room(20.0, 10.0), Room(110.0, 50.0)), two)
    }

    @Test fun aCutInsideOrOnTheEdgeOfARoomGrowsIt() {
        val room = listOf(Room(100.0, 50.0))
        assertEquals(listOf(Room(100.0, 70.0)), Rooms.opened(room, 120.0, 20.0))
        assertEquals(listOf(Room(100.0, 70.0)), Rooms.opened(room, 150.0, 20.0))
        assertEquals(listOf(Room(100.0, 70.0)), Rooms.opened(room, 100.0, 20.0))
    }

    @Test fun closingShrinksTheRoomAndPullsLaterOnesBack() {
        val rooms = listOf(Room(100.0, 50.0), Room(300.0, 40.0))
        val shut = Rooms.closed(rooms, rooms[0], 110.0, 130.0)
        assertEquals(listOf(Room(100.0, 30.0), Room(280.0, 40.0)), shut)
    }

    @Test fun closingIsClampedToTheRoomAndARoomClosedToNothingIsGone() {
        val rooms = listOf(Room(100.0, 50.0), Room(300.0, 40.0))
        val shut = Rooms.closed(rooms, rooms[0], 0.0, 400.0)
        assertEquals(listOf(Room(250.0, 40.0)), shut)
    }

    @Test fun sideAndDownRoomsKeepToTheirOwnAxes() {
        val rooms = Rooms.opened(listOf(Room(50.0, 10.0, sideways = true)), 100.0, 20.0)
        assertEquals(10.0, Rooms.total(rooms, true), eps)
        assertEquals(20.0, Rooms.total(rooms, false), eps)
        assertEquals(50.0, Rooms.toSource(rooms, 60.0, true), eps)
        assertEquals(60.0, Rooms.toSource(rooms, 60.0, false), eps)
    }

    @Test fun displayAndSourceMapEachWay() {
        val rooms = listOf(Room(100.0, 50.0), Room(300.0, 40.0))
        assertEquals(80.0, Rooms.toSource(rooms, 80.0, false), eps)
        assertEquals(100.0, Rooms.toSource(rooms, 120.0, false), eps)
        assertEquals(110.0, Rooms.toSource(rooms, 160.0, false), eps)
        assertEquals(270.0, Rooms.toSource(rooms, 360.0, false), eps)
        for (s in listOf(0.0, 99.0, 101.0, 249.0, 251.0, 500.0)) {
            assertEquals(s, Rooms.toSource(rooms, Rooms.toDisplay(rooms, s, false), false), eps)
        }
        assertEquals("a point on the cut stays above the room", 100.0, Rooms.toDisplay(rooms, 100.0, false), eps)
    }

    @Test fun aPressNearARoomFindsIt() {
        val rooms = listOf(Room(100.0, 50.0))
        assertEquals(rooms[0], Rooms.at(rooms, 120.0, false))
        assertNull(Rooms.at(rooms, 153.0, false))
        assertEquals(rooms[0], Rooms.at(rooms, 153.0, false, slop = 4.0))
        assertNull(Rooms.at(rooms, 120.0, true))
    }

    @Test fun bandsRunBetweenTheCuts() {
        val bands = Rooms.bands(listOf(Room(100.0, 50.0), Room(300.0, 40.0)), 400.0, false)
        assertEquals(3, bands.size)
        assertEquals(0.0, bands[0].from, eps)
        assertEquals(100.0, bands[0].to, eps)
        assertEquals(0.0, bands[0].shift, eps)
        assertEquals(100.0, bands[1].from, eps)
        assertEquals(250.0, bands[1].to, eps)
        assertEquals(50.0, bands[1].shift, eps)
        assertEquals(250.0, bands[2].from, eps)
        assertEquals(400.0, bands[2].to, eps)
        assertEquals(90.0, bands[2].shift, eps)
    }

    @Test fun aRoomInAMarginMovesTheWholeSource() {
        val top = Rooms.bands(listOf(Room(-20.0, 30.0)), 400.0, false)
        assertEquals(1, top.size)
        assertEquals(0.0, top[0].from, eps)
        assertEquals(30.0, top[0].shift, eps)
        val bottom = Rooms.bands(listOf(Room(420.0, 30.0)), 400.0, false)
        assertEquals(1, bottom.size)
        assertEquals(0.0, bottom[0].shift, eps)
        assertEquals(400.0, bottom[0].to, eps)
    }

    @Test fun tilesCrossBothAxes() {
        val rooms = listOf(Room(100.0, 50.0), Room(60.0, 10.0, sideways = true))
        val tiles = Rooms.tiles(rooms, 200.0, 300.0)
        assertEquals(4, tiles.size)
        val last = tiles.last()
        assertEquals(Rect(60.0, 100.0, 140.0, 200.0), last.source)
        assertEquals(Rect(70.0, 150.0, 140.0, 200.0), last.display)
    }

    @Test fun paperWithoutRoomsIsTheMargins() {
        val cover = Rect(-10.0, -20.0, 220.0, 340.0)
        val paper = Rooms.paper(cover, emptyList(), 200.0, 300.0)
        val area = paper.sumOf { it.w * it.h }
        assertEquals(220.0 * 340.0 - 200.0 * 300.0, area, 1e-6)
        assertEquals(4, paper.size)
    }

    @Test fun paperTakesInTheRoomStrips() {
        val cover = Rect(0.0, 0.0, 200.0, 350.0)
        val paper = Rooms.paper(cover, listOf(Room(100.0, 50.0)), 200.0, 300.0)
        assertEquals(listOf(Rect(0.0, 100.0, 200.0, 50.0)), paper)
        val side = Rooms.paper(Rect(0.0, 0.0, 210.0, 300.0), listOf(Room(60.0, 10.0, sideways = true)), 200.0, 300.0)
        assertEquals(listOf(Rect(60.0, 0.0, 10.0, 300.0)), side)
    }

    @Test fun roomsTurnWithThePageAndKeepMappingTheSamePoints() {
        val w = 200.0
        val h = 350.0
        val rooms = listOf(Room(100.0, 50.0))
        for (q in 1..3) {
            val turned = Rooms.turned(rooms, w, h, q)
            val t = PageTurn.affine(w, h, q)
            val st = PageTurn.affine(w, h - 50.0, q)
            for (y in listOf(10.0, 90.0, 160.0, 340.0)) {
                val p = com.xnotes.core.geometry.Pt(30.0, y)
                val source = com.xnotes.core.geometry.Pt(30.0, Rooms.toSource(rooms, y, false))
                val shown = t.apply(p)
                val expected = st.apply(source)
                assertEquals("turn $q y $y x", expected.x, Rooms.toSource(turned, shown.x, true), 1e-6)
                assertEquals("turn $q y $y y", expected.y, Rooms.toSource(turned, shown.y, false), 1e-6)
            }
        }
        var back = rooms
        var bw = w
        var bh = h
        repeat(4) {
            back = Rooms.turned(back, bw, bh, 1)
            val t = bw
            bw = bh
            bh = t
        }
        assertEquals(rooms, back)
    }

    @Test fun turningAPageTurnsItsRoomAndItsMarkupsInTheSourceFrame() {
        val doc = Document()
        val page = Page(200.0, 350.0, pdfPage = 0, rooms = listOf(Room(100.0, 50.0)))
        doc.pages.add(page)
        val quad = com.xnotes.core.pdf.TextQuad(0f, 0f, 10f, 10f, 0)
        page.markups = listOf(TextMarkup("m", MarkupType.HIGHLIGHT, Rgba(255, 255, 0, 255), 1.0, listOf(quad), "", null, 0L, 0L))
        PageTurn.turn(page, doc, 1)
        assertEquals(listOf(Room(200.0, 50.0, sideways = true)), page.rooms)
        assertEquals(350.0, page.width, eps)
        val toPts = 72.0 / doc.dpi
        val q = page.markups[0].quads[0]
        assertEquals((300.0 * toPts - 10.0).toFloat(), q.left, 1e-3f)
    }

    @Test fun marginsMeasureThePageWithoutItsRoom() {
        val doc = Document()
        val page = Page(200.0, 300.0, margins = PageMargins(top = 0.1), rooms = listOf(Room(100.0, 100.0)))
        doc.pages.add(page)
        assertEquals(20.0, page.insets(doc).top, eps)
    }

    @Test fun aPageShapePutsTheRoomBack() {
        val page = Page(200.0, 300.0)
        val before = PageShape.of(page)
        page.rooms = listOf(Room(10.0, 20.0))
        page.height = 320.0
        before.restore(page)
        assertTrue(page.rooms.isEmpty())
        assertEquals(300.0, page.height, eps)
    }
}
