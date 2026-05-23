package com.xnotes.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    private val square = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))

    @Test fun pointInsidePolygon() {
        assertTrue(Geometry.pointInPolygon(square, Pt(5.0, 5.0)))
    }

    @Test fun pointOutsidePolygon() {
        assertFalse(Geometry.pointInPolygon(square, Pt(15.0, 5.0)))
        assertFalse(Geometry.pointInPolygon(square, Pt(-1.0, 5.0)))
    }

    @Test fun degeneratePolygonIsNeverInside() {
        assertFalse(Geometry.pointInPolygon(listOf(Pt(0.0, 0.0), Pt(1.0, 1.0)), Pt(0.5, 0.5)))
    }

    @Test fun evenOddConcaveHole() {
        // A C-shape where the ray crosses an even number of edges in the notch.
        val poly = listOf(
            Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0),
            Pt(0.0, 7.0), Pt(8.0, 7.0), Pt(8.0, 3.0), Pt(0.0, 3.0),
        )
        assertTrue(Geometry.pointInPolygon(poly, Pt(9.0, 5.0)))
        assertFalse(Geometry.pointInPolygon(poly, Pt(4.0, 5.0)))
    }

    @Test fun distancePointToSegment() {
        assertEquals(5.0, Geometry.distancePointToSegment(Pt(0.0, 5.0), Pt(0.0, 0.0), Pt(10.0, 0.0)), 1e-9)
        // Beyond the endpoint clamps to the endpoint distance.
        assertEquals(5.0, Geometry.distancePointToSegment(Pt(-5.0, 0.0), Pt(0.0, 0.0), Pt(10.0, 0.0)), 1e-9)
    }

    @Test fun rectIntersection() {
        val a = Rect(0.0, 0.0, 10.0, 10.0)
        assertTrue(a.intersects(Rect(5.0, 5.0, 10.0, 10.0)))
        assertFalse(a.intersects(Rect(20.0, 20.0, 5.0, 5.0)))
    }

    @Test fun rectFromPointsIsNormalized() {
        val r = Rect.fromPoints(Pt(10.0, 10.0), Pt(0.0, 4.0))
        assertEquals(0.0, r.left, 1e-9)
        assertEquals(4.0, r.top, 1e-9)
        assertEquals(10.0, r.w, 1e-9)
        assertEquals(6.0, r.h, 1e-9)
    }

    @Test fun ptVectorOps() {
        assertEquals(5.0, Pt(3.0, 4.0).length(), 1e-9)
        assertEquals(7.0, Pt(0.0, 0.0).manhattanTo(Pt(3.0, 4.0)), 1e-9)
        val n = Pt(2.0, 0.0).normalized()
        assertEquals(1.0, n.x, 1e-9)
        val perp = Pt(1.0, 0.0).perp() // (-y, x) = (0, 1)
        assertEquals(0.0, perp.x, 1e-9)
        assertEquals(1.0, perp.y, 1e-9)
    }
}
