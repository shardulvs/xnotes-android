package com.xnotes.core.model

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.shapes.PathRole
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.shapes.SolidMathKinds
import com.xnotes.format.CanvasCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SplineShapeTest {

    private val ink = Rgba(0, 0, 0)

    private fun spline(vararg pts: Pt) = ShapeItem.poly(ShapeKind.SPLINE, pts.toList(), ink, 3.0)

    private fun points(s: ShapeItem) = s.vertexDots().map { it.at }

    @Test fun aNewSplineIsAHumpThroughThreePoints() {
        val s = ShapeItem(ShapeKind.SPLINE, Pt(0.0, 0.0), Pt(100.0, 50.0), ink)
        assertEquals(listOf(Pt(0.0, 50.0), Pt(50.0, 0.0), Pt(100.0, 50.0)), points(s))
        assertTrue(s.contains(Pt(50.0, 0.0)))
    }

    @Test fun theCurvePassesThroughEveryPoint() {
        val s = spline(Pt(0.0, 0.0), Pt(40.0, 90.0), Pt(120.0, 10.0), Pt(200.0, 70.0))
        val curve = s.paths().first { it.role == PathRole.STROKE_ONLY }.contours.single()
        assertEquals(3, curve.segments.size)
        assertEquals(listOf(Pt(40.0, 90.0), Pt(120.0, 10.0), Pt(200.0, 70.0)), curve.segments.map { it.end })
    }

    @Test fun movingAPointReshapesTheCurveThroughIt() {
        val s = spline(Pt(0.0, 0.0), Pt(50.0, 80.0), Pt(100.0, 0.0))
        s.moveVertex(s.vertexDots()[1].spot, Pt(60.0, -40.0))
        assertEquals(Pt(60.0, -40.0), points(s)[1])
        assertTrue(s.contains(Pt(60.0, -40.0)))
    }

    @Test fun anEdgeDotSitsOnTheCurveAndSplittingThereKeepsItsShape() {
        val s = spline(Pt(0.0, 0.0), Pt(50.0, 80.0), Pt(100.0, 0.0), Pt(150.0, 80.0))
        val dot = s.edgeDots()[1]
        assertTrue("the dot is on the curve, not the straight line between points", s.contains(dot.at))
        val added = s.splitEdge(dot.spot)!!
        assertEquals(5, points(s).size)
        assertEquals(0.0, points(s)[added.index].distanceTo(dot.at), 1e-6)
    }

    @Test fun aSplineKeepsAtLeastThreePoints() {
        val s = spline(Pt(0.0, 0.0), Pt(50.0, 80.0), Pt(100.0, 0.0), Pt(150.0, 80.0))
        assertTrue(s.deleteVertex(s.vertexDots()[1].spot))
        assertEquals(3, points(s).size)
        assertFalse(s.deleteVertex(s.vertexDots()[1].spot))
        assertEquals(3, points(s).size)
    }

    @Test fun aSplineRoundTripsWithItsPointsAndStandsInAsALine() {
        val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = InfiniteDocument()
        doc.add(spline(Pt(0.0, 0.0), Pt(40.0, 90.0), Pt(120.0, 10.0), Pt(200.0, 70.0)).also { it.rotation = 0.4 })
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        val back = codec.read(ByteArrayInputStream(out.toByteArray())).items.single() as ShapeItem
        val was = doc.items.single() as ShapeItem
        assertEquals(ShapeKind.SPLINE, back.shape)
        assertEquals(0, codec.forkShapesRead)
        assertEquals(0.4, back.rotation, 1e-9)
        points(was).zip(points(back)).forEach { (a, b) -> assertEquals(0.0, a.distanceTo(b), 1e-4) }
        assertEquals(ShapeKind.POLYLINE.id, back.legacyId())
    }

    @Test fun axesCrossingMidBoxTickBothSidesAlike() {
        val axes = ShapeItem(SolidMathKinds.AXES_2D, Pt(0.0, 0.0), Pt(200.0, 200.0), ink, adj = doubleArrayOf(0.5, 0.5), n = 4)
        val ticks = axes.paths().first { it.role == PathRole.STROKE_ONLY }.contours.drop(2)
        assertEquals("four ticks on each half of each axis", 16, ticks.size)
        val corner = ShapeItem(SolidMathKinds.AXES_2D, Pt(0.0, 0.0), Pt(200.0, 200.0), ink, n = 4)
        assertEquals("upstream's corner axes tick only ahead", 8, corner.paths().first { it.role == PathRole.STROKE_ONLY }.contours.size - 2)
    }
}
