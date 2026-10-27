package com.xnotes.core.model

import com.xnotes.canvas.SelectionMath
import com.xnotes.core.fill.ColorDrop
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tape.TapeGeometry
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class TapeItemTest {

    private val sky = Rgba(90, 160, 240, 255)

    private fun straight(a: Pt = Pt(0.0, 0.0), b: Pt = Pt(100.0, 0.0), w: Double = 20.0) = TapeShape(TapeMode.STRAIGHT, listOf(a, b), w)

    private fun rect(w: Double = 20.0) = TapeShape(TapeMode.RECTANGLE, listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), w)

    private fun hook() = TapeShape(TapeMode.FREEHAND, listOf(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 50.0), Pt(10.0, 40.0)), 10.0)

    private fun tape(s: TapeShape = straight()) = TapeItem(s, sky)

    private fun ink() = Stroke(Tool.PEN, ToolConfig(), (0..20).map { Sample(it * 5.0, 0.0, 1.0) })

    private fun centreline(points: List<Pt>, p: Pt): Double =
        (0 until points.size - 1).minOf { Geometry.distancePointToSegment(p, points[it], points[it + 1]) }

    @Test fun aStraightStripIsACapsuleRoundItsAxis() {
        val t = tape()
        assertEquals(Rect(-10.0, -10.0, 120.0, 20.0), t.bounds())
        assertTrue(t.contains(Pt(50.0, 9.0)))
        assertFalse(t.contains(Pt(50.0, 11.0)))
        assertTrue("round past its end", t.contains(Pt(105.0, 0.0)))
        assertFalse(t.contains(Pt(105.0, 9.0)))
    }

    @Test fun aRectangleEndsSquareAtItsAxis() {
        val t = tape(rect())
        assertEquals(Rect(0.0, -10.0, 100.0, 20.0), t.bounds())
        assertTrue(t.contains(Pt(99.0, 9.0)))
        assertFalse(t.contains(Pt(101.0, 0.0)))
        assertTrue(t.intersectsCircle(103.0, 0.0, 4.0))
    }

    @Test fun aFreehandBandFollowsTheHand() {
        val t = tape(hook())
        assertTrue(t.contains(Pt(50.0, 25.0)))
        assertFalse("the inside of the hook stays bare", t.contains(Pt(25.0, 20.0)))
    }

    @Test fun aPeeledFreehandBandShowsOnlyItsEdge() {
        val s = hook()
        val lines = s.outline
        assertTrue(lines.isNotEmpty())
        for (line in lines) for (p in line.pts) assertTrue("$p lies inside the band", centreline(s.points, p) >= s.half - 1e-3)
    }

    @Test fun aStripOrRectangleOutlineIsItsOneClosedEdge() {
        assertEquals(1, straight().outline.size)
        assertTrue(rect().outline.single().closed)
    }

    @Test fun everyPatternStaysOnTheBody() {
        for (s in listOf(straight(b = Pt(80.0, 60.0)), rect(48.0), hook())) {
            for (p in listOf(TapePattern.STRIPES, TapePattern.DOTS, TapePattern.GRID)) {
                val marks = s.pattern(p)
                assertTrue("$p on ${s.mode}", marks.isNotEmpty())
                for (m in marks) for (v in m) assertTrue("$p on ${s.mode} strays to $v", TapeGeometry.distance(s, v) <= 1e-6)
            }
        }
        assertTrue(straight().pattern(TapePattern.SOLID).isEmpty())
    }

    @Test fun aStripSnapsLevelOrUprightOnlyWhenNearlySo() {
        assertEquals(Pt(100.0, 0.0), TapeGeometry.snap(Pt(0.0, 0.0), Pt(100.0, 5.0)))
        assertEquals(Pt(-100.0, 0.0), TapeGeometry.snap(Pt(0.0, 0.0), Pt(-100.0, 4.0)))
        assertEquals(Pt(0.0, 100.0), TapeGeometry.snap(Pt(0.0, 0.0), Pt(3.0, 100.0)))
        assertEquals(Pt(100.0, 20.0), TapeGeometry.snap(Pt(0.0, 0.0), Pt(100.0, 20.0)))
    }

    @Test fun turningKeepsTheWidthAndStretchingAcrossWidensIt() {
        val turned = straight().transformed(Affine.rotateAbout(Pt(0.0, 0.0), PI / 2))
        assertEquals(20.0, turned.width, 1e-9)
        assertEquals(0.0, turned.points[1].x, 1e-9)
        assertEquals(100.0, turned.points[1].y, 1e-9)
        assertEquals(20.0, straight().transformed(Affine.scaleAbout(Pt(0.0, 0.0), 2.0, 1.0)).width, 1e-9)
        assertEquals(60.0, rect().transformed(Affine.scaleAbout(Pt(0.0, 0.0), 1.0, 3.0)).width, 1e-9)
        assertEquals(20.0, hook().transformed(Affine.scaleAbout(Pt(0.0, 0.0), 2.0, 2.0)).width, 1e-9)
    }

    @Test fun aCopyStartsCoveredAndKeepsItsLook() {
        val t = tape().also {
            it.peeled = true
            it.pattern = TapePattern.DOTS
        }
        val c = t.deepCopy(FakeTextMeasurer()) as TapeItem
        assertNotSame(t, c)
        assertFalse(c.peeled)
        assertSame(t.shape, c.shape)
        assertEquals(TapePattern.DOTS, c.pattern)
        assertEquals(sky, c.rgba)
    }

    @Test fun erasersTakeATapeWhole() {
        val t = tape()
        assertTrue(EraseRule.whole(t))
        assertTrue(EraseRule.takes(t, 50.0, 0.0, 2.0))
        assertTrue("even at its edge", EraseRule.takes(t, 50.0, 13.0, 4.0))
        assertFalse(EraseRule.takes(t, 50.0, 30.0, 4.0))
        assertFalse(EraseRule.takes(tape().also { it.locked = true }, 50.0, 0.0, 2.0))
    }

    @Test fun exportsLeaveTapeOut() {
        val ink = ink()
        assertEquals(listOf<CanvasItem>(ink), listOf(ink, tape()).forExport())
    }

    @Test fun aRestyleRecoloursButLeavesARectanglesHeight() {
        val red = Rgba(200, 30, 30, 255)
        val strip = tape()
        assertEquals(DrawStyle(sky, 20.0), DrawStyle.of(strip))
        DrawStyle(red, 30.0).applyTo(strip)
        assertEquals(red, strip.rgba)
        assertEquals(30.0, strip.shape.width, 0.0)
        val box = tape(rect())
        DrawStyle(red, 30.0).applyTo(box)
        assertEquals(red, box.rgba)
        assertEquals(20.0, box.shape.width, 0.0)
    }

    @Test fun itCoversOpaquelyAndPrintsInAContrastingShade() {
        val t = TapeItem(straight(), Rgba(240, 220, 120, 90))
        assertEquals(255, t.coverColor.a)
        assertTrue(t.printColor.luminance() < t.coverColor.luminance())
        val dark = TapeItem(straight(), Rgba(10, 10, 10, 255))
        assertTrue(dark.printColor.luminance() > dark.coverColor.luminance())
    }

    @Test fun aRingPicksTapeByItsEdgeNotItsBody() {
        val t = tape(rect(60.0))
        fun ring(l: Double, tp: Double, r: Double, b: Double) = listOf(Pt(l, tp), Pt(r, tp), Pt(r, b), Pt(l, b))
        assertTrue("a ring inside the tape leaves it out", SelectionMath.lassoMembers(listOf(t), ring(20.0, -10.0, 60.0, 10.0)).isEmpty())
        assertEquals(listOf<CanvasItem>(t), SelectionMath.lassoMembers(listOf(t), ring(80.0, -10.0, 120.0, 10.0)))
        assertEquals(listOf<CanvasItem>(t), SelectionMath.bandMembers(listOf(t), Rect(-10.0, -40.0, 130.0, 80.0)))
    }

    @Test fun aDropRecoloursACoveredTapeAndGoesThroughAPeeledOne() {
        val ink = ink()
        val t = tape(rect())
        assertSame(t, ColorDrop.lineAt(listOf(ink, t), Pt(50.0, 0.0), 2.0))
        t.peeled = true
        assertSame(ink, ColorDrop.lineAt(listOf(ink, t), Pt(50.0, 0.0), 2.0))
    }

    @Test fun clippingKeepsWhatLiesInside() {
        val square = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))
        val half = TapeGeometry.clipConvex(square, listOf(Pt(5.0, -5.0), Pt(20.0, -5.0), Pt(20.0, 20.0), Pt(5.0, 20.0)))
        assertEquals(Rect(5.0, 0.0, 5.0, 10.0), Rect.bounding(half))
        assertTrue(TapeGeometry.clipConvex(square, listOf(Pt(20.0, 0.0), Pt(30.0, 0.0), Pt(30.0, 10.0))).isEmpty())
    }

    @Test fun simplifyingKeepsTheEndsAndTheCorners() {
        val path = listOf(Pt(0.0, 0.0), Pt(10.0, 0.1), Pt(20.0, 0.0), Pt(20.0, 10.0), Pt(20.1, 20.0))
        assertEquals(listOf(Pt(0.0, 0.0), Pt(20.0, 0.0), Pt(20.1, 20.0)), TapeGeometry.simplify(path, 0.75))
    }

    @Test fun aDragLaysWhatTheModeSays() {
        val strip = TapeGeometry.laid(TapeMode.STRAIGHT, Pt(0.0, 0.0), Pt(100.0, 3.0), emptyList(), 30.0)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), strip.points)
        assertEquals(30.0, strip.width, 0.0)
        val box = TapeGeometry.laid(TapeMode.RECTANGLE, Pt(10.0, 10.0), Pt(110.0, 12.0), emptyList(), 30.0)
        assertEquals("never thinner than the narrowest tape", TapeItem.MIN_WIDTH, box.width, 0.0)
        assertEquals(Rect(10.0, 7.0, 100.0, 8.0), box.bounds)
        val path = listOf(Pt(0.0, 0.0), Pt(5.0, 5.0), Pt(10.0, 0.0))
        assertEquals(path, TapeGeometry.laid(TapeMode.FREEHAND, path[0], path[2], path, 12.0).points)
    }

    @Test fun aCoveredTapeMeshesAsACoverAndAPeeledOneAsItsEdge() {
        val t = TapeItem(rect(), sky, TapePattern.STRIPES)
        val covered = ItemMesher.mesh(t)!!.parts
        assertEquals(2, covered.size)
        assertTrue(covered.all { it.pass == InkPass.OPAQUE })
        assertTrue("the body hides highlights beneath it", covered[0].covers)
        assertEquals(t.printColor, covered[1].color)
        t.peeled = true
        val peeled = ItemMesher.mesh(t)!!.parts
        assertEquals(1, peeled.size)
        assertFalse(peeled[0].covers)
    }

    @Test fun aPeeledTapeIsHitOnlyAlongItsEdgeButStillPeelsFromInside() {
        val t = tape(rect(40.0))
        assertTrue(t.contains(Pt(50.0, 0.0)))
        t.peeled = true
        assertFalse("what shows through it stays reachable", t.contains(Pt(50.0, 0.0)))
        assertFalse(EraseRule.takes(t, 50.0, 0.0, 4.0))
        assertTrue(t.contains(Pt(50.0, 19.0)))
        assertTrue(EraseRule.takes(t, 50.0, 23.0, 4.0))
        assertTrue("a tap inside still covers it again", t.under(Pt(50.0, 0.0)))
    }
}
