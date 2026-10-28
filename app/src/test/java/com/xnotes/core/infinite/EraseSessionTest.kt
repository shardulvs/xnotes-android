package com.xnotes.core.infinite

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EraseSessionTest {

    /** A horizontal stroke from x=0 to x=[length] along y=[y], one sample every 5 px. */
    private fun line(length: Double, y: Double = 0.0): Stroke = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN),
        (0..(length / 5).toInt()).map { Sample(it * 5.0, y, 1.0) }.toMutableList(),
    )

    private fun image(x: Double, y: Double): ImageItem =
        ImageItem(ImageData(File("none"), 10, 10), Rect(x, y, 10.0, 10.0))

    private fun docOf(vararg items: CanvasItem): InfiniteDocument =
        InfiniteDocument().apply { addAll(items.toList()) }

    private fun square(l: Double, t: Double, size: Double) =
        listOf(Pt(l, t), Pt(l + size, t), Pt(l + size, t + size), Pt(l, t + size))

    private val sky = Rgba(90, 160, 240, 255)

    // --- fills: a touch takes them whole ---

    @Test fun anEraserOverInkOnAFillTakesBoth() {
        for (area in listOf(false, true)) {
            val fill = ShapeItem.fill(listOf(square(-50.0, -50.0, 300.0)), sky)
            val ink = line(100.0)
            val doc = docOf(fill, ink)
            EraseSession(doc).erase(50.0, 0.0, 8.0, area)
            assertTrue("the fill goes whole (area=$area)", doc.items.none { it === fill })
            assertTrue("and the ink with it (area=$area)", doc.items.none { it === ink })
        }
    }

    @Test fun aSlightTouchAtAFillsEdgeTakesItWhole() {
        for (area in listOf(false, true)) {
            val fill = ShapeItem.fill(listOf(square(0.0, 0.0, 200.0)), sky)
            val doc = docOf(fill)
            val session = EraseSession(doc)
            assertNull(session.erase(-20.0, 100.0, 8.0, area))
            assertNotNull(session.erase(-5.0, 100.0, 8.0, area))
            assertEquals("area=$area", 0, doc.itemCount)
        }
    }

    @Test fun aFilledShapeLosesOnlyItsFillToATouchInside() {
        val box = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 200.0), Rgba(1, 1, 1, 255), 3.0, sky)
        val doc = docOf(box)
        val session = EraseSession(doc)
        session.erase(100.0, 100.0, 8.0, area = true)
        assertSame(box, doc.items.single())
        assertNull(box.fillRgba)
        val undo = session.buildCommand()!!
        undo.undo()
        assertEquals(sky, box.fillRgba)
        undo.redo()
        assertNull(box.fillRgba)
    }

    @Test fun rubbingOutWritingInAFilledShapeTakesItsFillButLeavesItsOutline() {
        val box = ShapeItem(ShapeKind.RECTANGLE, Pt(-50.0, -50.0), Pt(200.0, 200.0), Rgba(1, 1, 1, 255), 3.0, sky)
        val ink = line(100.0)
        val doc = docOf(box, ink)
        EraseSession(doc).erase(50.0, 0.0, 8.0, area = false)
        assertSame(box, doc.items.single())
        assertNull(box.fillRgba)
    }

    @Test fun aFillComesOffBeforeItsShapeIsCutAndUndoesInOrder() {
        val box = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 200.0), Rgba(1, 1, 1, 255), 3.0, sky)
        val doc = docOf(box)
        val session = EraseSession(doc)
        session.erase(100.0, 100.0, 8.0, area = true)
        session.erase(100.0, 0.0, 8.0, area = true) // then across its top edge
        assertTrue(doc.items.none { it === box })
        session.buildCommand()!!.undo()
        assertSame(box, doc.items.single())
        assertEquals(sky, box.fillRgba)
    }

    // --- whole-stroke mode ---

    @Test fun wholeStrokeModeRemovesWhatItTouches() {
        val hit = line(100.0)
        val miss = line(100.0, y = 500.0)
        val doc = docOf(hit, miss)
        val session = EraseSession(doc)

        assertNotNull(session.erase(50.0, 0.0, 8.0, area = false))
        assertEquals(listOf<CanvasItem>(miss), doc.items)
    }

    @Test fun wholeStrokeModeMissesWhatIsOutOfReach() {
        val doc = docOf(line(100.0))
        val session = EraseSession(doc)
        assertNull(session.erase(50.0, 400.0, 8.0, area = false))
        assertEquals(1, doc.itemCount)
        assertTrue(session.isEmpty)
    }

    @Test fun imagesSurviveBothModes() {
        val img = image(0.0, 0.0)
        val doc = docOf(img)
        val session = EraseSession(doc)
        assertNull(session.erase(5.0, 5.0, 20.0, area = false))
        assertNull(session.erase(5.0, 5.0, 20.0, area = true))
        assertEquals(listOf<CanvasItem>(img), doc.items)
    }

    @Test fun aDragThatRemovesSeveralStrokesIsOneEdit() {
        val a = line(60.0, y = 0.0)
        val b = line(60.0, y = 6.0)
        val doc = docOf(a, b)
        val session = EraseSession(doc)
        session.erase(30.0, 3.0, 20.0, area = false)
        assertTrue(doc.isEmpty)

        val cmd = session.buildCommand()!!
        cmd.undo()
        assertEquals(listOf<CanvasItem>(a, b), doc.items)
        cmd.redo()
        assertTrue(doc.isEmpty)
    }

    // --- area mode ---

    @Test fun areaModeCutsAStrokeInTwo() {
        val stroke = line(100.0)
        val doc = docOf(stroke)
        val session = EraseSession(doc)

        assertNotNull(session.erase(50.0, 0.0, 8.0, area = true))
        assertEquals("a mid-stroke hole leaves two fragments", 2, doc.itemCount)
        assertTrue(doc.items.all { it is Stroke && it !== stroke })
        val left = doc.items[0] as Stroke
        val right = doc.items[1] as Stroke
        assertTrue(left.samples.last().x < 50.0)
        assertTrue(right.samples.first().x > 50.0)
    }

    @Test fun fragmentsTakeTheOriginalsPlaceInTheOrder() {
        val under = line(20.0, y = 100.0)
        val cut = line(100.0)
        val over = line(20.0, y = 200.0)
        val doc = docOf(under, cut, over)
        val session = EraseSession(doc)

        session.erase(50.0, 0.0, 8.0, area = true)
        assertEquals(4, doc.itemCount)
        assertSame("the item below must stay below", under, doc.items.first())
        assertSame("the item above must stay above", over, doc.items.last())
    }

    @Test fun trimmingAnEndLeavesOneFragment() {
        val stroke = line(100.0)
        val doc = docOf(stroke)
        EraseSession(doc).erase(0.0, 0.0, 8.0, area = true)
        assertEquals(1, doc.itemCount)
        assertTrue((doc.items[0] as Stroke).samples.first().x > 0.0)
    }

    @Test fun erasingEveryPointRemovesTheStrokeOutright() {
        val stroke = line(20.0)
        val doc = docOf(stroke)
        val session = EraseSession(doc)
        session.erase(10.0, 0.0, 60.0, area = true)
        assertTrue(doc.isEmpty)

        // Nothing is left to find the stroke by, so the recorded slot is what brings it back.
        session.buildCommand()!!.undo()
        assertEquals(listOf<CanvasItem>(stroke), doc.items)
    }

    @Test fun undoRestoresTheExactListAfterAnAreaCut() {
        val before = listOf(line(20.0, 100.0), line(100.0), line(20.0, 200.0))
        val doc = InfiniteDocument().apply { addAll(before) }
        val session = EraseSession(doc)
        session.erase(50.0, 0.0, 8.0, area = true)
        val cmd = session.buildCommand()!!

        cmd.undo()
        assertEquals(before, doc.items)
        cmd.redo()
        assertEquals(4, doc.itemCount)
        cmd.undo()
        assertEquals(before, doc.items)
    }

    @Test fun aFragmentCutAgainStillUndoesToTheOriginal() {
        val stroke = line(200.0)
        val doc = docOf(stroke)
        val session = EraseSession(doc)

        session.erase(60.0, 0.0, 8.0, area = true) // splits into two
        assertEquals(2, doc.itemCount)
        session.erase(140.0, 0.0, 8.0, area = true) // cuts the right-hand fragment again
        assertEquals(3, doc.itemCount)

        val cmd = session.buildCommand()!!
        cmd.undo()
        assertEquals("one drag, one original, however many cuts", listOf<CanvasItem>(stroke), doc.items)
        cmd.redo()
        assertEquals(3, doc.itemCount)
    }

    @Test fun severalOriginalsCutInOneDragAllComeBack() {
        val a = line(120.0, y = 0.0)
        val b = line(120.0, y = 60.0)
        val doc = docOf(a, b)
        val session = EraseSession(doc)

        session.erase(60.0, 0.0, 8.0, area = true)
        session.erase(60.0, 60.0, 8.0, area = true)
        assertEquals(4, doc.itemCount)

        session.buildCommand()!!.undo()
        assertEquals(listOf<CanvasItem>(a, b), doc.items)
    }

    @Test fun cuttingTheLowerItemFirstStillUndoesInOrder() {
        // The recorded slots are captured against different list states, so undo has to run in
        // reverse to read each one against the state it was taken in.
        val a = line(120.0, y = 0.0)
        val b = line(120.0, y = 60.0)
        val c = line(120.0, y = 120.0)
        val doc = docOf(a, b, c)
        val session = EraseSession(doc)

        session.erase(60.0, 0.0, 8.0, area = true)   // splits a, shifting b and c along
        session.erase(60.0, 120.0, 8.0, area = true) // then splits c
        session.buildCommand()!!.undo()
        assertEquals(listOf<CanvasItem>(a, b, c), doc.items)
    }

    // --- bookkeeping ---

    @Test fun aDragThatCutsNothingProducesNoEdit() {
        val doc = docOf(line(100.0))
        val session = EraseSession(doc)
        session.erase(50.0, 900.0, 8.0, area = true)
        assertTrue(session.isEmpty)
        assertNull(session.buildCommand())
    }

    @Test fun theSpatialIndexTracksTheFragments() {
        val doc = docOf(line(100.0))
        EraseSession(doc).erase(50.0, 0.0, 8.0, area = true)
        assertEquals(doc.itemCount, doc.index.size)
        assertEquals(2, doc.itemsIn(Rect(-20.0, -20.0, 160.0, 40.0)).size)
        // The hole is really a hole: nothing is indexed where the eraser passed.
        assertTrue(doc.itemsIn(Rect(48.0, -1.0, 4.0, 2.0)).isEmpty())
    }

    @Test fun theIndexIsRestoredByUndo() {
        val stroke = line(100.0)
        val doc = docOf(stroke)
        val session = EraseSession(doc)
        session.erase(50.0, 0.0, 8.0, area = true)
        session.buildCommand()!!.undo()
        assertEquals(1, doc.index.size)
        assertSame(stroke, doc.itemsIn(Rect(40.0, -10.0, 20.0, 20.0)).single())
    }

    @Test fun onlyItemsUnderTheEraserAreEvenConsidered() {
        // A far-off stroke must not be touched, whatever the document holds.
        val near = line(40.0)
        val far = line(40.0, y = 50_000.0)
        val doc = docOf(near, far)
        EraseSession(doc).erase(20.0, 0.0, 10.0, area = false)
        assertEquals(listOf<CanvasItem>(far), doc.items)
    }

    @Test fun contentBoundsFollowTheCut() {
        val doc = docOf(line(100.0))
        val wide = doc.contentBounds()!!.w
        EraseSession(doc).erase(95.0, 0.0, 20.0, area = true)
        assertTrue("trimming the end must shrink the extent", doc.contentBounds()!!.w < wide)
    }

    @Test fun aCutLabelledShapeLeavesItsWordsAsDrawnText() {
        val ink = Rgba(5, 6, 7, 255)
        val box = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 100.0), ink, 2.0)
        box.label = ShapeLabel("Go", TextStyle(ink, 10.0, FontFace.SANS), FakeTextMeasurer())
        val doc = docOf(box)
        val session = EraseSession(doc)
        assertNotNull(session.erase(100.0, 0.0, 10.0, area = true))
        val shapes = doc.items.filterIsInstance<ShapeItem>()
        assertEquals("the outline is ink, the label drawn text", 1, shapes.size)
        assertSame(ShapeKind.GLYPHS, shapes.single().shape)
        assertTrue(doc.items.any { it is Stroke })
    }

    // --- connectors ---

    private fun labelledConnector(): com.xnotes.core.model.ConnectorItem {
        val ink = com.xnotes.core.model.Rgba(1, 2, 3, 255)
        val c = com.xnotes.core.model.ConnectorItem(
            com.xnotes.core.model.Routing.STRAIGHT,
            com.xnotes.core.model.ConnectorEnd(com.xnotes.core.geometry.Pt(0.0, 0.0)),
            com.xnotes.core.model.ConnectorEnd(com.xnotes.core.geometry.Pt(300.0, 0.0)),
            ink, 2.0,
        )
        c.label = com.xnotes.core.model.ShapeLabel("go", com.xnotes.core.model.TextStyle(ink, 10.0, com.xnotes.core.pal.FontFace.SANS), com.xnotes.core.FakeTextMeasurer())
        return c
    }

    @Test fun theAreaEraserCutsAConnectorIntoInkAndLeavesItsLabelAsDrawnText() {
        val c = labelledConnector()
        val doc = docOf(c)
        EraseSession(doc).erase(40.0, 0.0, 10.0, area = true)
        assertTrue(doc.items.none { it is com.xnotes.core.model.ConnectorItem })
        // The label's gap already parts the line in two, and the cut parts one piece again.
        assertEquals(3, doc.items.count { it is Stroke })
        val glyphs = doc.items.filterIsInstance<com.xnotes.core.model.ShapeItem>().single()
        assertEquals(com.xnotes.core.shapes.ShapeKind.GLYPHS, glyphs.shape)
    }

    @Test fun theStrokeEraserTakesAConnectorWhole() {
        val c = labelledConnector()
        val doc = docOf(c)
        EraseSession(doc).erase(40.0, 0.0, 10.0, area = false)
        assertTrue(doc.items.isEmpty())
    }
}

