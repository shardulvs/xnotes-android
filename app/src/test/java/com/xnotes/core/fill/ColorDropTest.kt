package com.xnotes.core.fill

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ColorDropTest {

    private val ink = Rgba(10, 10, 10, 255)
    private val blue = Rgba(30, 90, 220, 255)

    /** A pen line through [pts], sampled every 2 px so the smoothing keeps to it. */
    private fun stroke(vararg pts: Pt, tool: Tool = Tool.PEN, width: Double = 3.0): Stroke {
        val samples = ArrayList<Sample>()
        for (k in 0 until pts.size - 1) {
            val a = pts[k]
            val b = pts[k + 1]
            val steps = maxOf(1, (a.distanceTo(b) / 2.0).toInt())
            for (s in 0 until steps) samples += Sample(a.x + (b.x - a.x) * s / steps, a.y + (b.y - a.y) * s / steps, 1.0)
        }
        samples += Sample(pts.last().x, pts.last().y, 1.0)
        return Stroke(tool, ToolConfig(baseWidth = width, rgba = ink), samples)
    }

    /** A box drawn with the pen, run on past its start so it closes. */
    private fun loop(l: Double, t: Double, r: Double, b: Double, tool: Tool = Tool.PEN) =
        stroke(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b), Pt(l, t), Pt(l + 30.0, t), tool = tool)

    private fun rect(l: Double, t: Double, r: Double, b: Double, fill: Rgba? = null) =
        ShapeItem(ShapeKind.RECTANGLE, Pt(l, t), Pt(r, b), ink, 3.0, fill)

    private fun drop(items: List<CanvasItem>, at: Pt): DropAction {
        val map = ColorDrop.map(items, FillGrid(Rect(0.0, 0.0, 400.0, 400.0), 2.0))!!
        return ColorDrop.decide(items, map, at, blue, reach = 2.0, smallHole = 20)
    }

    private inline fun <reified T : DropAction> expect(a: DropAction): T {
        assertTrue("expected ${T::class.simpleName}, got $a", a is T)
        return a as T
    }

    @Test fun aDropOnInkRecoloursIt() {
        val ink = loop(100.0, 100.0, 300.0, 300.0)
        assertSame(ink, expect<DropAction.Recolour>(drop(listOf(ink), Pt(200.0, 100.0))).item)
    }

    @Test fun aDropInsideAnInkLoopAddsAFillUnderEverything() {
        val add = expect<DropAction.AddFill>(drop(listOf(loop(100.0, 100.0, 300.0, 300.0)), Pt(200.0, 200.0)))
        assertNull(add.above)
        assertTrue(add.replaced.isEmpty())
        assertTrue(add.fill.isFill)
        assertEquals(blue, add.fill.fillRgba)
        assertTrue(add.fill.contains(Pt(200.0, 200.0)))
        assertTrue("under the line", add.fill.contains(Pt(100.6, 200.0)))
        assertFalse(add.fill.contains(Pt(80.0, 200.0)))
    }

    @Test fun aHighlighterNeitherClosesNorRecolours() {
        val marker = loop(100.0, 100.0, 300.0, 300.0, tool = Tool.HIGHLIGHTER)
        assertSame(DropAction.NotClosed, drop(listOf(marker), Pt(200.0, 100.0)))
        assertSame(DropAction.NotClosed, drop(listOf(marker), Pt(200.0, 200.0)))
    }

    @Test fun aDropInsideAHollowShapeFillsTheShape() {
        val box = rect(100.0, 100.0, 300.0, 300.0)
        assertSame(box, expect<DropAction.FillShape>(drop(listOf(box), Pt(200.0, 200.0))).shape)
    }

    @Test fun writingInsideAShapeStillFillsTheShape() {
        val box = rect(100.0, 100.0, 300.0, 300.0)
        val word = stroke(Pt(150.0, 200.0), Pt(160.0, 190.0), Pt(170.0, 210.0), Pt(180.0, 195.0))
        assertSame(box, expect<DropAction.FillShape>(drop(listOf(box, word), Pt(250.0, 250.0))).shape)
    }

    @Test fun aShapeSplitByInkFillsOnlyThePart() {
        val box = rect(100.0, 100.0, 300.0, 300.0)
        val split = stroke(Pt(200.0, 80.0), Pt(200.0, 320.0))
        val add = expect<DropAction.AddFill>(drop(listOf(box, split), Pt(150.0, 200.0)))
        assertTrue(add.fill.contains(Pt(150.0, 200.0)))
        assertFalse(add.fill.contains(Pt(250.0, 200.0)))
    }

    @Test fun aLockedShapeGetsAFillUnderItInstead() {
        val box = rect(100.0, 100.0, 300.0, 300.0).also { it.locked = true }
        expect<DropAction.AddFill>(drop(listOf(box), Pt(200.0, 200.0)))
        assertNull("its outline is not recoloured", ColorDrop.lineAt(listOf(box), Pt(100.0, 200.0), 2.0))
    }

    @Test fun paperNoLineClosesIsNotClosed() {
        assertSame(DropAction.NotClosed, drop(listOf(loop(100.0, 100.0, 300.0, 300.0)), Pt(50.0, 50.0)))
        assertSame(DropAction.NotClosed, drop(emptyList(), Pt(50.0, 50.0)))
    }

    @Test fun aFillWhoseLinesAreGoneIsStillRecoloured() {
        val fill = ShapeItem.fill(listOf(listOf(Pt(100.0, 100.0), Pt(300.0, 100.0), Pt(300.0, 300.0), Pt(100.0, 300.0))), ink)
        assertSame(fill, expect<DropAction.Refill>(drop(listOf(fill), Pt(200.0, 200.0))).fill)
    }

    @Test fun theSameAreaRecoloursItsFill() {
        val line = loop(100.0, 100.0, 300.0, 300.0)
        val first = expect<DropAction.AddFill>(drop(listOf(line), Pt(200.0, 200.0))).fill
        assertSame(first, expect<DropAction.Refill>(drop(listOf(first, line), Pt(180.0, 220.0))).fill)
    }

    @Test fun aWiderAreaReplacesTheFillsItCovers() {
        val line = loop(100.0, 100.0, 300.0, 300.0)
        val split = stroke(Pt(200.0, 80.0), Pt(200.0, 320.0))
        val half = expect<DropAction.AddFill>(drop(listOf(line, split), Pt(150.0, 200.0))).fill
        val add = expect<DropAction.AddFill>(drop(listOf(half, line), Pt(250.0, 200.0)))
        assertEquals(listOf(half), add.replaced)
    }

    @Test fun anEyeInAFilledFaceGoesJustAboveTheFace() {
        val face = ShapeItem(ShapeKind.ELLIPSE, Pt(50.0, 50.0), Pt(350.0, 350.0), ink, 3.0, Rgba(240, 200, 160, 255))
        val eye = loop(120.0, 140.0, 180.0, 180.0)
        val add = expect<DropAction.AddFill>(drop(listOf(face, eye), Pt(150.0, 160.0)))
        assertSame(face, add.above)
        assertTrue(add.fill.contains(Pt(150.0, 160.0)))
    }

    @Test fun aSplitFilledShapeFillsThePartAboveItAndOffItsOutline() {
        val box = rect(100.0, 100.0, 300.0, 300.0, fill = Rgba(200, 200, 200, 255))
        val split = stroke(Pt(200.0, 80.0), Pt(200.0, 320.0))
        val add = expect<DropAction.AddFill>(drop(listOf(box, split), Pt(150.0, 200.0)))
        assertSame(box, add.above)
        assertFalse("never over the shape's own outline", add.fill.contains(Pt(100.5, 200.0)))
        assertTrue(add.fill.contains(Pt(103.0, 200.0)))
    }

    @Test fun aPictureHidesTheLineUnderIt() {
        val line = loop(100.0, 100.0, 300.0, 300.0)
        val picture = ImageItem(ImageData(File("x.png"), 10, 10), Rect(150.0, 50.0, 100.0, 100.0))
        assertNull(ColorDrop.lineAt(listOf(line, picture), Pt(200.0, 100.0), 2.0))
        assertSame(line, ColorDrop.lineAt(listOf(picture, line), Pt(200.0, 100.0), 2.0))
    }

    @Test fun aTextBoxsWordsTakeTheColour() {
        val box = TextItem(Pt(100.0, 100.0), width = 100.0, text = "Hello", measurer = FakeTextMeasurer())
        assertSame(box, expect<DropAction.Recolour>(drop(listOf(box), Pt(110.0, 105.0))).item)
    }
}
