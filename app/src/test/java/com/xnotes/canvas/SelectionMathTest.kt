package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class SelectionMathTest {

    private fun dot(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    // Two A4-ish pages stacked; page rects in content space.
    private val pages = listOf(Page(100.0, 100.0), Page(100.0, 100.0))
    private val rects = listOf(Rect(48.0, 48.0, 100.0, 100.0), Rect(48.0, 186.0, 100.0, 100.0))

    @Test fun bandSelectsOnlyIntersecting() {
        val a = dot(10.0, 10.0) // page 0, content ~ (58,58)
        val b = dot(90.0, 90.0) // page 0, content ~ (138,138)
        pages[0].items.addAll(listOf(a, b))
        // Band over the top-left corner of page 0 only.
        val members = SelectionMath.bandMembers(pages, rects, Rect(50.0, 50.0, 30.0, 30.0))
        assertEquals(1, members.size)
        assertSame(a, members[0].item)
        assertEquals(0, members[0].pageIndex)
    }

    @Test fun lassoSelectsWhatItEncloses() {
        val a = dot(10.0, 10.0)
        val b = dot(90.0, 90.0)
        pages[0].items.addAll(listOf(a, b))
        // Polygon (content space) around a's centroid (~58,58).
        val poly = listOf(Pt(50.0, 50.0), Pt(75.0, 50.0), Pt(75.0, 75.0), Pt(50.0, 75.0))
        val members = SelectionMath.lassoMembers(pages, rects, poly)
        assertEquals(1, members.size)
        assertSame(a, members[0].item)
    }

    @Test fun bandSpansPages() {
        val a = dot(50.0, 50.0) // page 0
        val b = dot(50.0, 50.0) // page 1
        pages[0].items.add(a)
        pages[1].items.add(b)
        val members = SelectionMath.bandMembers(pages, rects, Rect(0.0, 0.0, 300.0, 400.0))
        assertEquals(2, members.size)
        assertTrue(members.any { it.pageIndex == 0 } && members.any { it.pageIndex == 1 })
    }

    @Test fun degenerateLassoSelectsNothing() {
        pages[0].items.add(dot(50.0, 50.0))
        assertTrue(SelectionMath.lassoMembers(pages, rects, listOf(Pt(0.0, 0.0), Pt(1.0, 1.0))).isEmpty())
    }

    // --- touching or enclosing what is drawn ---

    private fun ink(vararg pts: Pt, width: Double = 3.0): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN).copy(baseWidth = width), pts.map { Sample(it.x, it.y, 1.0) }.toMutableList())

    private fun box(filled: Boolean = false) =
        ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 100.0), Rgba(1, 2, 3, 255), 2.0, if (filled) Rgba(1, 2, 3, 80) else null)

    private fun loop(l: Double, t: Double, r: Double, b: Double) = listOf(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b))

    private val word = ink(Pt(80.0, 50.0), Pt(100.0, 45.0), Pt(120.0, 50.0))

    @Test fun aLassoInsideAHollowShapeLeavesTheShapeOut() {
        val box = box()
        assertEquals(listOf<CanvasItem>(word), SelectionMath.lassoMembers(listOf(box, word), loop(60.0, 30.0, 140.0, 70.0)))
    }

    @Test fun aLassoInsideAFilledShapeLeavesTheShapeOut() {
        val box = box(filled = true)
        assertEquals(listOf<CanvasItem>(word), SelectionMath.lassoMembers(listOf(box, word), loop(60.0, 30.0, 140.0, 70.0)))
    }

    @Test fun aLassoRoundAShapeTakesItAndWhatItHolds() {
        val box = box()
        assertEquals(listOf<CanvasItem>(box, word), SelectionMath.lassoMembers(listOf(box, word), loop(-10.0, -10.0, 210.0, 110.0)))
    }

    @Test fun aLassoCrossingAShapesOutlineTakesIt() {
        val box = box()
        assertEquals(listOf<CanvasItem>(box), SelectionMath.lassoMembers(listOf(box, word), loop(150.0, 30.0, 260.0, 70.0)))
    }

    @Test fun aRingDrawnRoundWordsStaysOutOfALassoRoundTheWords() {
        val ring = ink(*Array(33) { i -> Pt(100.0 + 90.0 * cos(i * Math.PI / 16), 50.0 + 90.0 * sin(i * Math.PI / 16)) })
        assertEquals(listOf<CanvasItem>(word), SelectionMath.lassoMembers(listOf(ring, word), loop(60.0, 30.0, 140.0, 70.0)))
    }

    @Test fun aLassoTakesALineItOnlyCrosses() {
        val line = ink(Pt(0.0, 50.0), Pt(300.0, 50.0))
        assertEquals(listOf<CanvasItem>(line), SelectionMath.lassoMembers(listOf(line), loop(100.0, 30.0, 150.0, 70.0)))
    }

    @Test fun aLassoTakesInkItsLineOnlyGrazes() {
        val thick = ink(Pt(100.0, 74.0), Pt(140.0, 74.0), width = 10.0)
        val thin = ink(Pt(100.0, 74.0), Pt(140.0, 74.0), width = 2.0)
        assertEquals(listOf<CanvasItem>(thick), SelectionMath.lassoMembers(listOf(thick, thin), loop(60.0, 30.0, 160.0, 70.0)))
    }

    @Test fun aLassoInsideAnImageLeavesTheImageOut() {
        val image = ImageItem(ImageData(File("none"), 10, 10), Rect(0.0, 0.0, 300.0, 200.0))
        assertEquals(listOf<CanvasItem>(word), SelectionMath.lassoMembers(listOf(image, word), loop(60.0, 30.0, 140.0, 70.0)))
    }

    @Test fun aBandInsideAHollowShapeLeavesTheShapeOut() {
        val box = box()
        assertEquals(listOf<CanvasItem>(word), SelectionMath.bandMembers(listOf(box, word), Rect(60.0, 30.0, 80.0, 40.0)))
    }

    @Test fun aBandCrossingAShapesOutlineTakesIt() {
        val box = box()
        assertEquals(listOf<CanvasItem>(box), SelectionMath.bandMembers(listOf(box, word), Rect(150.0, 30.0, 110.0, 40.0)))
    }

    @Test fun aBandTakesALineItOnlyCrosses() {
        val line = ink(Pt(0.0, 50.0), Pt(300.0, 50.0))
        assertEquals(listOf<CanvasItem>(line), SelectionMath.bandMembers(listOf(line), Rect(100.0, 30.0, 50.0, 40.0)))
    }

    @Test fun aBandMissingTheInkInsideItsBoxLeavesItOut() {
        val corner = ink(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0))
        assertTrue(SelectionMath.bandMembers(listOf(corner), Rect(10.0, 50.0, 40.0, 40.0)).isEmpty())
    }

    @Test fun aLassoOnAPageTestsInThePagesOwnSpace() {
        val box = ShapeItem(ShapeKind.RECTANGLE, Pt(10.0, 10.0), Pt(90.0, 90.0), Rgba(1, 2, 3, 255), 2.0)
        val note = dot(50.0, 50.0)
        pages[0].items.addAll(listOf(box, note))
        // Content (88..108) is page 0's (40..60): round the dot, inside the box.
        val members = SelectionMath.lassoMembers(pages, rects, loop(88.0, 88.0, 108.0, 108.0))
        assertEquals(listOf<CanvasItem>(note), members.map { it.item })
    }

    private fun sq(l: Double, t: Double, size: Double) = listOf(Pt(l, t), Pt(l + size, t), Pt(l + size, t + size), Pt(l, t + size))

    @Test fun aLassoRoundWritingInAFillLeavesTheFillOut() {
        val face = ShapeItem.fill(listOf(sq(0.0, 0.0, 300.0), sq(100.0, 100.0, 50.0)), Rgba(240, 200, 160, 255))
        val word = dot(200.0, 200.0)
        val eye = dot(125.0, 125.0)
        assertEquals(listOf<CanvasItem>(word), SelectionMath.lassoMembers(listOf(face, word), sq(180.0, 180.0, 40.0)))
        assertEquals("round its hole, too", listOf<CanvasItem>(eye), SelectionMath.lassoMembers(listOf(face, eye), sq(90.0, 90.0, 70.0)))
    }

    @Test fun aRingCrossingAFillsEdgeOrAroundItTakesIt() {
        val fill = ShapeItem.fill(listOf(sq(0.0, 0.0, 100.0)), Rgba(240, 200, 160, 255))
        assertEquals(listOf<CanvasItem>(fill), SelectionMath.bandMembers(listOf(fill), Rect(80.0, 40.0, 50.0, 20.0)))
        assertEquals(listOf<CanvasItem>(fill), SelectionMath.lassoMembers(listOf(fill), sq(-10.0, -10.0, 130.0)))
        assertTrue(SelectionMath.bandMembers(listOf(fill), Rect(30.0, 30.0, 20.0, 20.0)).isEmpty())
    }
}
