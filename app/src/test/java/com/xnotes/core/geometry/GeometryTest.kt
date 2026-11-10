package com.xnotes.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

    @Test fun dotProduct() {
        assertEquals(11.0, Geometry.dot(Pt(1.0, 2.0), Pt(3.0, 4.0)), 1e-9)
        assertEquals(0.0, Geometry.dot(Pt(1.0, 0.0), Pt(0.0, 1.0)), 1e-9)
    }

    @Test fun projectPointOntoLine() {
        val a = Pt(0.0, 0.0)
        val b = Pt(10.0, 0.0)
        // Drops to the foot on the horizontal line.
        val foot = Geometry.projectPointOntoLine(Pt(5.0, 7.0), a, b)
        assertEquals(5.0, foot.x, 1e-9)
        assertEquals(0.0, foot.y, 1e-9)
        // Unclamped: a point beyond b projects past b (segment-clamp would stop at b).
        val far = Geometry.projectPointOntoLine(Pt(20.0, 3.0), a, b)
        assertEquals(20.0, far.x, 1e-9)
        assertEquals(0.0, far.y, 1e-9)
        // Works on a diagonal line through the origin.
        val diag = Geometry.projectPointOntoLine(Pt(0.0, 2.0), Pt(0.0, 0.0), Pt(2.0, 2.0))
        assertEquals(1.0, diag.x, 1e-9)
        assertEquals(1.0, diag.y, 1e-9)
        // Degenerate a == b returns a.
        assertEquals(a, Geometry.projectPointOntoLine(Pt(4.0, 4.0), a, a))
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

    // --- Swept-disc ribbon bridge ---
    private fun signedArea2(a: Pt, b: Pt, c: Pt) =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    @Test fun ribbonQuadCornersSitOnBothDiscRims() {
        // Each of the four corners lies exactly on the rim of the disc it belongs to, so the quad
        // meets the discs flush and the union has no gap and no overshoot.
        val q = Geometry.ribbonQuad(Pt(0.0, 0.0), 3.0, Pt(10.0, 0.0), 5.0)
        assertEquals(4, q.size)
        for (p in q) {
            val onFirst = p.distanceTo(Pt(0.0, 0.0))
            val onSecond = p.distanceTo(Pt(10.0, 0.0))
            assertTrue("corner on neither rim", kotlin.math.min(kotlin.math.abs(onFirst - 3.0), kotlin.math.abs(onSecond - 5.0)) < 1e-9)
        }
    }

    @Test fun ribbonQuadWindsPositiveSoFillsUnion() {
        // The quad is returned positively wound, regardless of which way the segment runs, so a
        // nonzero fill of several quads plus their discs unions instead of cancelling a gap.
        val forward = Geometry.ribbonQuad(Pt(0.0, 0.0), 3.0, Pt(10.0, 1.0), 4.0)
        val backward = Geometry.ribbonQuad(Pt(10.0, 1.0), 4.0, Pt(0.0, 0.0), 3.0)
        assertTrue(signedArea2(forward[0], forward[1], forward[2]) >= 0.0)
        assertTrue(signedArea2(backward[0], backward[1], backward[2]) >= 0.0)
    }

    @Test fun ribbonQuadEmptyForCoincidentCentres() {
        assertTrue(Geometry.ribbonQuad(Pt(5.0, 5.0), 3.0, Pt(5.0, 5.0), 4.0).isEmpty())
    }

    // --- Hull of two brush discs ---
    private val tangents = DoubleArray(4)

    // x0, y0, r0, x1, y1, r1: growing, shrinking backwards, equal and vertical, from a point
    private val capsules = listOf(
        doubleArrayOf(0.0, 0.0, 3.0, 10.0, 0.0, 5.0),
        doubleArrayOf(2.0, 1.0, 6.0, -7.0, 4.0, 2.0),
        doubleArrayOf(0.0, 0.0, 4.0, 0.0, 9.0, 4.0),
        doubleArrayOf(5.0, 5.0, 0.0, 9.0, 8.0, 3.0),
    )

    private fun capsule(c: DoubleArray) = Geometry.capsule(c[0], c[1], c[2], c[3], c[4], c[5], tangents)

    /** The hull's outline as the renderer traces it: the far rim from the left tangent, then the near rim from the right one. */
    private fun capsuleOutline(c: DoubleArray): List<Pt> {
        val front = capsule(c)
        val out = mutableListOf<Pt>()
        fun rim(cx: Double, cy: Double, r: Double, nx: Double, ny: Double, sweep: Double) {
            val a0 = atan2(ny, nx)
            for (k in 0..64) {
                val a = a0 + Math.toRadians(sweep) * k / 64
                out += Pt(cx + r * cos(a), cy + r * sin(a))
            }
        }
        rim(c[3], c[4], c[5], tangents[0], tangents[1], front)
        rim(c[0], c[1], c[2], tangents[2], tangents[3], 360.0 - front)
        return out
    }

    @Test fun capsuleTangentsRestOnBothRims() {
        for (c in capsules) {
            assertTrue(capsule(c) != Geometry.SWALLOWED)
            for (side in 0..1) {
                val nx = tangents[2 * side]
                val ny = tangents[2 * side + 1]
                assertEquals(1.0, hypot(nx, ny), 1e-12)
                assertEquals("one line touches both discs", nx * c[0] + ny * c[1] + c[2], nx * c[3] + ny * c[4] + c[5], 1e-9)
            }
        }
    }

    @Test fun capsuleLeftTangentIsLeftOfTravel() {
        assertEquals(180.0, Geometry.capsule(0.0, 0.0, 3.0, 10.0, 0.0, 3.0, tangents), 1e-9)
        assertEquals(0.0, tangents[0], 1e-12)
        assertEquals(-1.0, tangents[1], 1e-12)
        assertEquals(0.0, tangents[2], 1e-12)
        assertEquals(1.0, tangents[3], 1e-12)
    }

    @Test fun capsuleFarRimRunsClockwiseThroughTheTravelFromLeftToRight() {
        for (c in capsules) {
            val front = capsule(c)
            val a0 = Math.toDegrees(atan2(tangents[1], tangents[0]))
            val end = Math.toRadians(a0 + front)
            assertEquals(tangents[2], cos(end), 1e-9)
            assertEquals(tangents[3], sin(end), 1e-9)
            val mid = Math.toRadians(a0 + front / 2)
            val d = hypot(c[3] - c[0], c[4] - c[1])
            assertEquals((c[3] - c[0]) / d, cos(mid), 1e-9)
            assertEquals((c[4] - c[1]) / d, sin(mid), 1e-9)
        }
    }

    @Test fun capsuleShowsLessOfTheSmallerRim() {
        val shrinking = Geometry.capsule(0.0, 0.0, 5.0, 10.0, 0.0, 2.0, tangents)
        assertEquals(2 * Math.toDegrees(acos(tangents[0])), shrinking, 1e-9)
        assertTrue(shrinking < 180.0)
        assertTrue(Geometry.capsule(0.0, 0.0, 2.0, 10.0, 0.0, 5.0, tangents) > 180.0)
    }

    @Test fun capsuleWindsLikeClockwiseCirclesSoFillsUnion() {
        for (c in capsules) {
            val ring = capsuleOutline(c)
            var area2 = 0.0
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                area2 += a.x * b.y - b.x * a.y
            }
            assertTrue("negative winding would cancel overlaps", area2 > 0.0)
        }
    }

    @Test fun capsuleHoldsBothDiscsAndTheBandBetween() {
        for (c in capsules) {
            val ring = capsuleOutline(c)
            for (k in 0 until 36) {
                val a = Math.toRadians(k * 10.0)
                assertTrue(Geometry.pointInPolygon(ring, Pt(c[0] + 0.98 * c[2] * cos(a), c[1] + 0.98 * c[2] * sin(a))) || c[2] == 0.0)
                assertTrue(Geometry.pointInPolygon(ring, Pt(c[3] + 0.98 * c[5] * cos(a), c[4] + 0.98 * c[5] * sin(a))))
            }
            assertTrue(Geometry.pointInPolygon(ring, Pt((c[0] + c[3]) / 2, (c[1] + c[4]) / 2)))
        }
    }

    @Test fun capsuleIsJustTheLargerDiscWhenOneHoldsTheOther() {
        assertEquals(Geometry.SWALLOWED, Geometry.capsule(0.0, 0.0, 6.0, 2.0, 0.0, 3.0, tangents), 0.0)
        assertEquals(Geometry.SWALLOWED, Geometry.capsule(0.0, 0.0, 3.0, 2.0, 0.0, 6.0, tangents), 0.0)
        assertEquals(Geometry.SWALLOWED, Geometry.capsule(5.0, 5.0, 3.0, 5.0, 5.0, 3.0, tangents), 0.0)
    }
}
