package com.xnotes.core.room

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Glue
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Room
import com.xnotes.core.model.Routing
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoomCutTest {

    private val eps = 1e-6
    private val ink = Rgba(1, 2, 3, 255)

    private fun line(x: Double, from: Double, to: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), List(21) { Sample(x, from + (to - from) * it / 20.0, 1.0) })

    private fun box(l: Double, t: Double, r: Double, b: Double) = ShapeItem(ShapeKind.RECTANGLE, Pt(l, t), Pt(r, b), ink)

    private fun page(vararg items: CanvasItem) = Page(400.0, 600.0).also { it.items.addAll(items) }

    private fun firstY(s: Stroke) = s.yAt(0)

    @Test fun openingMovesWhatLiesBelowAndKeepsWhatLiesAbove() {
        val above = line(50.0, 10.0, 80.0)
        val below = line(50.0, 200.0, 300.0)
        val p = page(above, below)
        val cmd = RoomCut.open(p, 150.0, 40.0)!!
        assertEquals(10.0, firstY(above), eps)
        assertEquals(240.0, firstY(below), eps)
        assertEquals(640.0, p.height, eps)
        assertEquals(listOf(Room(150.0, 40.0)), p.rooms)
        cmd.undo()
        assertEquals(200.0, firstY(below), eps)
        assertEquals(600.0, p.height, eps)
        assertTrue(p.rooms.isEmpty())
        cmd.redo()
        assertEquals(240.0, firstY(below), eps)
        assertEquals(640.0, p.height, eps)
    }

    @Test fun inkTheCutCrossesSplitsIntoTwoPiecesThatKeepEverySample() {
        val s = line(50.0, 100.0, 200.0)
        val p = page(s)
        val cmd = RoomCut.open(p, 150.0, 40.0)!!
        assertEquals(2, p.items.size)
        val near = p.items[0] as Stroke
        val far = p.items[1] as Stroke
        assertSame(s, near)
        assertEquals(21, near.sampleCount)
        assertEquals(21, far.sampleCount)
        assertTrue(near.bounds().bottom <= 150.0 + eps)
        assertTrue(far.bounds().top >= 190.0 - eps)
        assertEquals(240.0, far.bounds().bottom, 3.0)
        cmd.undo()
        assertEquals(listOf<CanvasItem>(s), p.items)
        assertTrue(s.cuts.isEmpty())
        cmd.redo()
        assertEquals(2, p.items.size)
        assertEquals(1, (p.items[0] as Stroke).cuts.size)
    }

    @Test fun whatCannotBeCutStaysWithTheUpperBand() {
        val across = box(10.0, 100.0, 90.0, 200.0)
        val text = TextItem(Pt(100.0, 140.0), 100.0, 20.0, "hi", ink, 12.0, measurer = FakeTextMeasurer())
        val under = box(10.0, 300.0, 90.0, 350.0)
        val p = page(across, text, under)
        RoomCut.open(p, 150.0, 40.0)
        assertEquals(100.0, across.bounds().top, 3.0)
        assertEquals(140.0, text.bounds().top, eps)
        assertEquals(340.0, under.bounds().top, 3.0)
        assertEquals(3, p.items.size)
    }

    @Test fun aGroupTheCutCrossesStaysWholeAndOneBelowItMoves() {
        val a = line(50.0, 100.0, 200.0).also { it.group = 1 }
        val b = line(60.0, 100.0, 120.0).also { it.group = 1 }
        val c = line(50.0, 300.0, 320.0).also { it.group = 2 }
        val d = line(60.0, 330.0, 340.0).also { it.group = 2 }
        val p = page(a, b, c, d)
        RoomCut.open(p, 150.0, 40.0)
        assertEquals(4, p.items.size)
        assertTrue(a.cuts.isEmpty())
        assertEquals(100.0, firstY(a), eps)
        assertEquals(340.0, firstY(c), eps)
        assertEquals(370.0, firstY(d), eps)
    }

    @Test fun anUprightPictureIsSlicedAndAFreeTurnedOneStays() {
        val pic = ImageItem(ImageData(File("x"), 100, 100), Rect(0.0, 100.0, 100.0, 100.0))
        val turned = ImageItem(ImageData(File("y"), 100, 100), Rect(200.0, 100.0, 100.0, 100.0), angle = 0.4)
        val p = page(pic, turned)
        val cmd = RoomCut.open(p, 150.0, 40.0)!!
        assertEquals(3, p.items.size)
        assertEquals(Rect(0.0, 100.0, 100.0, 50.0), pic.rect)
        assertEquals(Rect.ltrb(0.0, 0.0, 1.0, 0.5), pic.part)
        val far = p.items[1] as ImageItem
        assertEquals(Rect(0.0, 190.0, 100.0, 50.0), far.rect)
        assertEquals(Rect.ltrb(0.0, 0.5, 1.0, 1.0), far.part)
        assertSame(turned, p.items[2])
        assertEquals(Rect(200.0, 100.0, 100.0, 100.0), turned.rect)
        cmd.undo()
        assertEquals(Rect(0.0, 100.0, 100.0, 100.0), pic.rect)
        assertNull(pic.part)
    }

    @Test fun closingTakesAStripOutAndBringsTheFarBandBack() {
        val p = page()
        RoomCut.open(p, 150.0, 100.0)
        val inside = line(50.0, 170.0, 190.0)
        val across = line(60.0, 140.0, 230.0)
        val below = line(70.0, 300.0, 320.0)
        val anchoredBox = box(100.0, 180.0, 200.0, 300.0)
        p.items.addAll(listOf(inside, across, below, anchoredBox))
        val cmd = RoomCut.close(p, p.rooms[0], 160.0, 210.0)!!
        assertFalse(inside in p.items)
        assertEquals(650.0, p.height, eps)
        assertEquals(listOf(Room(150.0, 50.0)), p.rooms)
        assertEquals(250.0, firstY(below), eps)
        assertEquals(180.0, anchoredBox.bounds().top, 3.0)
        val near = across
        val far = p.items[p.items.indexOf(across) + 1] as Stroke
        assertTrue(near.bounds().bottom <= 160.0 + eps)
        assertTrue(far.bounds().top >= 160.0 - eps)
        assertTrue(far.bounds().bottom <= 180.0 + 3.0)
        cmd.undo()
        assertTrue(inside in p.items)
        assertEquals(300.0, firstY(below), eps)
        assertEquals(listOf(Room(150.0, 100.0)), p.rooms)
    }

    @Test fun closingIsClampedToTheRoomAndAClosedRoomIsGone() {
        val p = page()
        RoomCut.open(p, 150.0, 40.0)
        assertNotNull(RoomCut.close(p, p.rooms[0], 0.0, 1000.0))
        assertTrue(p.rooms.isEmpty())
        assertEquals(600.0, p.height, eps)
        assertNull(RoomCut.close(p, Room(150.0, 40.0), 150.0, 150.0))
    }

    @Test fun aGluedConnectorFollowsWhatItIsGluedTo() {
        val top = box(0.0, 0.0, 100.0, 60.0)
        val bottom = box(0.0, 300.0, 100.0, 360.0)
        val c = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Glue.point(top, 6)!!.at, top, 6), ConnectorEnd(Glue.point(bottom, 2)!!.at, bottom, 2), ink, 2.0)
        val p = page(top, bottom, c)
        val was = c.end.at
        val cmd = RoomCut.open(p, 150.0, 40.0)!!
        assertEquals(was.y + 40.0, c.end.at.y, eps)
        assertEquals(Glue.point(top, 6)!!.at, c.start.at)
        cmd.undo()
        assertEquals(was, c.end.at)
    }

    @Test fun anchoredItemsAreTheUncuttableOnesTheCutCrosses() {
        val across = box(10.0, 100.0, 90.0, 200.0)
        val ink = line(50.0, 100.0, 200.0)
        val p = page(across, ink, box(0.0, 0.0, 10.0, 10.0))
        assertEquals(listOf<CanvasItem>(across), RoomCut.anchored(p, false, 150.0, 150.0))
    }

    @Test fun aPageStopsGrowingAtWhatAPdfCanHold() {
        val doc = Document()
        val p = Page(400.0, 600.0)
        doc.pages += p
        assertEquals(14_400.0 * doc.dpi / 72.0 - 600.0, RoomCut.maxOpen(p, doc), eps)
    }
}
