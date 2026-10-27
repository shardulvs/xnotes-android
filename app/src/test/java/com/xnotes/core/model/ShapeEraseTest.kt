package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeEraseTest {

    private val red = Rgba(255, 0, 0, 255)

    private fun line(dashed: Boolean = false) = ShapeItem(
        ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 0.0), red, 4.0, null,
        dashed = dashed, dashLength = 12.0, dashGap = 5.0,
    )

    private fun ends(item: CanvasItem): Pair<Pt, Pt> {
        val s = (item as Stroke).samples
        return s.first().pos to s.last().pos
    }

    private fun approx(p: Pt) = Pt(Math.round(p.x * 1e6) / 1e6, Math.round(p.y * 1e6) / 1e6)

    @Test fun untouchedLineReturnsNull() {
        assertNull(line().erasedBy(50.0, 30.0, 10.0))
        assertNull(line().erasedBy(-40.0, 0.0, 10.0))
    }

    @Test fun aCutShapeBecomesInkSplitAtTheCircle() {
        val frags = line().erasedBy(50.0, 0.0, 10.0)!!
        assertEquals(2, frags.size)
        assertTrue(frags.all { it is Stroke && it.tool == Tool.PEN })
        assertEquals(Pt(0.0, 0.0), approx(ends(frags[0]).first))
        assertEquals(Pt(40.0, 0.0), approx(ends(frags[0]).second))
        assertEquals(Pt(60.0, 0.0), approx(ends(frags[1]).first))
        assertEquals(Pt(100.0, 0.0), approx(ends(frags[1]).second))
    }

    @Test fun theInkKeepsTheShapesColourAndWidthHeldConstant() {
        val s = line().erasedBy(50.0, 0.0, 10.0)!!.first() as Stroke
        assertEquals(red, s.config.rgba)
        assertEquals("the ribbon is as wide as the outline was", 4.0, s.config.baseWidth, 1e-9)
        assertFalse(s.config.pressureEnabled)
        assertFalse(s.config.taperEnabled)
        assertEquals("never smoothed, so corners stay sharp", 0.0, s.smoothScale, 0.0)
    }

    @Test fun theInkCanBeErasedFurtherAnywhereAlongIt() {
        val piece = line().erasedBy(90.0, 0.0, 5.0)!!.first() as Stroke
        val again = piece.erasedBy(40.0, 0.0, 5.0)
        assertNotNull("samples close enough for a cut in the middle of a straight run", again)
        assertEquals(2, again!!.size)
    }

    @Test fun coveredLineErasesWhole() {
        assertEquals(0, line().erasedBy(50.0, 0.0, 200.0)!!.size)
    }

    @Test fun aDashedShapeBecomesDashedInk() {
        val frags = line(dashed = true).erasedBy(50.0, 0.0, 10.0)!!
        frags.forEach {
            val s = it as Stroke
            assertEquals(Tool.DASHED, s.tool)
            assertEquals(12.0, s.config.dashLength, 1e-9)
            assertEquals(5.0, s.config.dashGap, 1e-9)
        }
    }

    @Test fun rectEdgeCutMergesAcrossTheSeam() {
        val rect = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 100.0), red, 3.0, null)
        val frags = rect.erasedBy(50.0, 0.0, 10.0)!!
        // One cut in the top edge: the rest of the outline survives as a single open run
        // from (60,0) clockwise around through the corners back to (40,0).
        assertEquals(1, frags.size)
        val (first, last) = ends(frags[0])
        assertEquals(Pt(60.0, 0.0), approx(first))
        assertEquals(Pt(40.0, 0.0), approx(last))
        val pts = (frags[0] as Stroke).samples.map { it.pos }
        assertTrue("the corners survive exactly", pts.any { it.distanceTo(Pt(100.0, 100.0)) < 1e-6 })
        assertTrue(pts.any { it.distanceTo(Pt(0.0, 100.0)) < 1e-6 })
    }

    @Test fun aFilledShapeCutOnItsEdgeLosesItsFill() {
        val filled = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 100.0), red, 3.0, Rgba(255, 0, 0, 64))
        val edge = filled.erasedBy(50.0, 0.0, 10.0)!!
        assertEquals("cut on its edge: the rest of the outline as ink", 1, edge.size)
        assertTrue(edge.single() is Stroke)
        assertNull("only the fill touched: the eraser takes just the fill then", filled.erasedBy(50.0, 50.0, 10.0))
        assertNull(filled.erasedBy(200.0, 50.0, 10.0))
    }

    @Test fun ellipseCutLeavesAnArcOfInk() {
        val ell = ShapeItem(ShapeKind.CIRCLE, Pt(0.0, 0.0), Pt(100.0, 100.0), red, 3.0, null)
        val frags = ell.erasedBy(100.0, 50.0, 15.0)!! // bite at the right extreme
        assertEquals(1, frags.size)
        val pts = (frags[0] as Stroke).samples.map { it.pos }
        assertTrue(pts.size > 10)
        // Every surviving sample stays outside the eraser circle.
        pts.forEach { assertTrue(it.distanceTo(Pt(100.0, 50.0)) >= 15.0 - 1e-6) }
    }

    @Test fun polylineCutSplitsIntoRuns() {
        val zig = ShapeItem.poly(
            ShapeKind.POLYLINE, listOf(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 50.0), Pt(100.0, 50.0)),
            red, 2.0,
        )
        val frags = zig.erasedBy(50.0, 25.0, 10.0)!! // bite the vertical middle
        assertEquals(2, frags.size)
        assertEquals(Pt(50.0, 15.0), approx(ends(frags[0]).second))
        assertEquals(Pt(50.0, 35.0), approx(ends(frags[1]).first))
    }

    @Test fun aSolidsHiddenEdgesBecomeDashedInk() {
        val cube = ShapeItem(com.xnotes.core.shapes.SolidMathKinds.CUBE, Pt(0.0, 0.0), Pt(100.0, 100.0), red, 2.0)
        val frags = cube.erasedBy(0.0, 62.5, 6.0)!!
        assertTrue("visible edges as pen ink", frags.any { (it as Stroke).tool == Tool.PEN })
        assertTrue("hidden edges as dashed ink", frags.any { (it as Stroke).tool == Tool.DASHED })
    }

    @Test fun aCutArrowKeepsItsChevronAsSolidInk() {
        val arrow = ShapeItem(ShapeKind.ARROW, Pt(0.0, 0.0), Pt(100.0, 0.0), red, 2.0, dashed = true)
        val frags = arrow.erasedBy(30.0, 0.0, 5.0)!!.map { it as Stroke }
        assertEquals("both pieces of the shaft stay dashed", 2, frags.count { it.tool == Tool.DASHED })
        val chevron = frags.single { it.tool == Tool.PEN }
        assertTrue("its tip half a width past the end", chevron.samples.any { it.pos.distanceTo(Pt(101.0, 0.0)) < 1e-4 })
    }

    @Test fun aFilledHeadKeepsItsOutline() {
        val line = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 0.0), red, 2.0, headEnd = Head.FILLED)
        val frags = line.erasedBy(30.0, 0.0, 5.0)!!.map { it as Stroke }
        assertEquals(3, frags.size)
        val ring = frags.single { ends(it).let { (a, b) -> a.distanceTo(b) < 1e-4 } }
        assertTrue("the triangle at the end", ring.samples.all { it.pos.x >= 100.0 - Head.length(2.0) - 1e-4 })
    }

    @Test fun aNeonShapeBecomesNeonInk() {
        val neon = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 0.0), red, 4.0, neon = true, neonStrength = 0.8)
        val s = neon.erasedBy(50.0, 0.0, 10.0)!!.first() as Stroke
        assertTrue(s.config.neon)
        assertEquals(0.8, s.config.neonStrength, 1e-9)
    }
}
