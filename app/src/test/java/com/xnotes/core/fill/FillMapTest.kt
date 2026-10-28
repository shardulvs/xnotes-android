package com.xnotes.core.fill

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.infinite.ShapeTessellator
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FillMapTest {

    private val ink = Rgba(10, 10, 10, 255)

    private fun box(l: Double, t: Double, r: Double, b: Double, width: Double = 4.0) =
        ShapeItem(ShapeKind.RECTANGLE, Pt(l, t), Pt(r, b), ink, width)

    private fun ring(cx: Double, cy: Double, radius: Double, width: Double = 2.0) =
        ShapeItem(ShapeKind.ELLIPSE, Pt(cx - radius, cy - radius), Pt(cx + radius, cy + radius), ink, width)

    private fun line(vararg pts: Pt, width: Double = 4.0) = ShapeItem.poly(ShapeKind.POLYLINE, pts.toList(), ink, width)

    private fun outline(s: ShapeItem): MeshData = ShapeTessellator.outlineMesh(s, 0.1)

    private fun map(vararg lines: ShapeItem, paint: List<List<Pt>> = emptyList()): FillMap {
        val m = FillMap(FillGrid(Rect(0.0, 0.0, 300.0, 300.0), 2.0))
        for (s in lines) m.addLine(outline(s), s.strokeWidth / 2.0)
        if (paint.isNotEmpty()) m.addPaint(paint)
        m.seal()
        return m
    }

    private fun enclosed(p: Probe): Probe.Enclosed {
        assertTrue("expected a closed area, got $p", p is Probe.Enclosed)
        return p as Probe.Enclosed
    }

    @Test fun aClosedBoxFillsToTheMiddleOfItsOutline() {
        val m = map(box(50.0, 50.0, 150.0, 150.0))
        val area = m.finish(enclosed(m.probe(Pt(100.0, 100.0))))
        assertTrue(area.holds(Pt(100.0, 100.0)))
        assertTrue("reaches under the line", area.holds(Pt(50.6, 100.0)))
        assertFalse("but not past its middle", area.holds(Pt(49.0, 100.0)))
        assertFalse(area.holds(Pt(200.0, 100.0)))
        assertEquals(1, area.rings.size)
        val b = area.bounds
        assertEquals(50.0, b.left, 1.0)
        assertEquals(150.0, b.right, 1.0)
        assertEquals(50.0, b.top, 1.0)
        assertEquals(150.0, b.bottom, 1.0)
    }

    @Test fun anOutlineWithAGapIsOpen() {
        val m = map(line(Pt(50.0, 50.0), Pt(150.0, 50.0), Pt(150.0, 150.0), Pt(50.0, 150.0), Pt(50.0, 60.0)))
        assertSame(Probe.Open, m.probe(Pt(100.0, 100.0)))
    }

    @Test fun paperOutsideEveryOutlineIsOpen() {
        val m = map(box(50.0, 50.0, 150.0, 150.0))
        assertSame(Probe.Open, m.probe(Pt(250.0, 250.0)))
        assertSame("outside the window", Probe.Open, m.probe(Pt(400.0, 100.0)))
    }

    @Test fun aDropOnAThickLineFindsNoPaper() {
        val m = map(box(50.0, 50.0, 150.0, 150.0, width = 10.0))
        assertSame(Probe.Blocked, m.probe(Pt(50.0, 100.0)))
    }

    @Test fun aDropJustOnAThinLineFillsBesideIt() {
        val m = map(box(50.0, 50.0, 150.0, 150.0, width = 1.0))
        val p = m.probe(Pt(50.4, 100.0))
        assertTrue(p is Probe.Enclosed || p === Probe.Open)
    }

    @Test fun aLetterSizedHoleFillsAndAnEyeSizedOneStaysOpen() {
        val m = map(box(20.0, 20.0, 280.0, 280.0), ring(80.0, 80.0, 4.0), ring(200.0, 200.0, 30.0))
        val area = m.finish(enclosed(m.probe(Pt(150.0, 150.0))), smallHole = 30)
        assertTrue("the small one fills", area.holds(Pt(80.0, 80.0)))
        assertFalse("the big one stays empty", area.holds(Pt(200.0, 200.0)))
        assertTrue("its outline's outer half is covered", area.holds(Pt(200.0, 169.5)))
        assertEquals("the outline and the eye", 2, area.rings.size)
    }

    @Test fun withoutASizeEveryHoleStaysOpen() {
        val m = map(box(20.0, 20.0, 280.0, 280.0), ring(80.0, 80.0, 4.0))
        val area = m.finish(enclosed(m.probe(Pt(150.0, 150.0))))
        assertFalse(area.holds(Pt(80.0, 80.0)))
    }

    @Test fun aSmallHoleAnOlderFillPaintsStaysOpen() {
        val hole = List(24) { k -> Pt(80.0 + 4.0 * kotlin.math.cos(k * Math.PI / 12), 80.0 + 4.0 * kotlin.math.sin(k * Math.PI / 12)) }
        val m = map(box(20.0, 20.0, 280.0, 280.0), ring(80.0, 80.0, 4.0), paint = listOf(hole))
        val area = m.finish(enclosed(m.probe(Pt(150.0, 150.0))), smallHole = 30)
        assertFalse(area.holds(Pt(80.0, 80.0)))
    }

    @Test fun anAreaKeptOutOfALineStopsAtItsEdge() {
        val outer = box(50.0, 50.0, 150.0, 150.0, width = 6.0)
        val m = map(outer)
        val area = m.finish(enclosed(m.probe(Pt(100.0, 100.0))), keepOut = listOf(outline(outer)))
        assertTrue(area.holds(Pt(54.0, 100.0)))
        assertFalse("never under the kept line", area.holds(Pt(51.5, 100.0)))
    }

    @Test fun twoAreasMeetAtTheMiddleOfTheLineBetweenThem() {
        val m = map(box(50.0, 50.0, 250.0, 150.0), line(Pt(150.0, 40.0), Pt(150.0, 160.0), width = 6.0))
        val left = m.finish(enclosed(m.probe(Pt(100.0, 100.0))))
        val right = m.finish(enclosed(m.probe(Pt(200.0, 100.0))))
        assertTrue(left.holds(Pt(149.0, 100.0)))
        assertFalse(left.holds(Pt(151.5, 100.0)))
        assertTrue(right.holds(Pt(151.0, 100.0)))
        assertFalse(right.holds(Pt(148.5, 100.0)))
        assertFalse("each floods only its own side", left.holds(Pt(200.0, 100.0)))
    }

    @Test fun anAreaComparesWithTheOutlineThatMadeIt() {
        val shape = box(50.0, 50.0, 150.0, 150.0)
        val m = map(shape)
        val area = m.finish(enclosed(m.probe(Pt(100.0, 100.0))))
        val square = listOf(listOf(Pt(50.0, 50.0), Pt(150.0, 50.0), Pt(150.0, 150.0), Pt(50.0, 150.0)))
        val o = area.compare(square)
        assertTrue(o.same)
        assertTrue(o.covers)
        assertTrue(o.within)
        val half = listOf(listOf(Pt(50.0, 50.0), Pt(100.0, 50.0), Pt(100.0, 150.0), Pt(50.0, 150.0)))
        val h = area.compare(half)
        assertFalse(h.same)
        assertTrue("it takes in the half", h.covers)
        assertFalse(h.within)
    }

    @Test fun theProbeLeavesTheMapAsItFoundIt() {
        val m = map(box(50.0, 50.0, 150.0, 150.0))
        val first = enclosed(m.probe(Pt(100.0, 100.0)))
        val second = enclosed(m.probe(Pt(120.0, 80.0)))
        assertEquals(first.count, second.count)
        assertTrue(second.holds(m.cellX(Pt(100.0, 100.0)), m.cellY(Pt(100.0, 100.0))))
    }
}
