package com.xnotes.core.shapes

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.vector.PathFlattener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class BasicKindsTest {

    private val box = Rect(10.0, 20.0, 120.0, 80.0)
    private val ink = Rgba(0, 0, 0)

    private fun geom(kind: ShapeKind, n: Int = kind.defaultN, adj: DoubleArray = kind.defaultAdj) =
        kind.build(box, ShapeParams(adj, n))

    private fun points(g: ShapeGeometry) = g.paths.flatMap { p -> p.contours.flatMap { PathFlattener.flatten(it, 0.05) } }

    private fun area(ring: List<Pt>): Double {
        var a = 0.0
        for (i in ring.indices) {
            val p = ring[i]
            val q = ring[(i + 1) % ring.size]
            a += p.x * q.y - q.x * p.y
        }
        return abs(a) / 2.0
    }

    private val basicKinds = listOf(
        ShapeKind.ROUNDED_RECT, ShapeKind.SQUARE, ShapeKind.CAPSULE, ShapeKind.RIGHT_TRIANGLE, ShapeKind.DIAMOND,
        ShapeKind.PARALLELOGRAM, ShapeKind.TRAPEZOID, ShapeKind.REGULAR_POLYGON, ShapeKind.STAR, ShapeKind.CROSS,
        ShapeKind.HEART, ShapeKind.CLOUD, ShapeKind.RING, ShapeKind.PIE, ShapeKind.ARC,
    )

    @Test fun everyKindDrawsInsideItsBox() {
        for (k in ShapeKind.all.filter { !it.isLegacy && !it.isFree && it.staysInBox }) {
            val g = geom(k)
            val b = Rect.bounding(points(g))
            assertTrue("$k left its box: $b", b.left >= box.left - 1e-6 && b.top >= box.top - 1e-6 && b.right <= box.right + 1e-6 && b.bottom <= box.bottom + 1e-6)
            assertTrue("$k is too small: $b", b.w > box.w * 0.3 && b.h > box.h * 0.3)
            val t = g.textRect
            assertTrue("$k label leaves the box", t.left >= box.left - 1e-6 && t.right <= box.right + 1e-6)
            assertFalse("$k is not a legacy kind", k.isLegacy)
        }
    }

    @Test fun cornersAreWhereTheDotsGo() {
        val want = mapOf(
            ShapeKind.SQUARE to 4, ShapeKind.RIGHT_TRIANGLE to 3, ShapeKind.DIAMOND to 4, ShapeKind.PARALLELOGRAM to 4,
            ShapeKind.TRAPEZOID to 4, ShapeKind.STAR to 10, ShapeKind.CROSS to 12, ShapeKind.PIE to 3, ShapeKind.ARC to 2,
            ShapeKind.ROUNDED_RECT to 0, ShapeKind.CAPSULE to 0, ShapeKind.HEART to 0, ShapeKind.CLOUD to 0, ShapeKind.RING to 0,
        )
        for ((k, n) in want) assertEquals("$k", n, geom(k).corners.size)
        for (n in listOf(5, 6, 8)) assertEquals(n, geom(ShapeKind.REGULAR_POLYGON, n).corners.size)
    }

    @Test fun aRegularPolygonFillsItsBox() {
        val b = Rect.bounding(geom(ShapeKind.REGULAR_POLYGON, 5).corners)
        assertEquals(box.left, b.left, 1e-9)
        assertEquals(box.bottom, b.bottom, 1e-9)
        assertEquals(box.w, b.w, 1e-9)
    }

    @Test fun aFivePointStarIsTheClassicOneUntilItsKnobMoves() {
        val square = Rect(0.0, 0.0, 100.0, 100.0)
        val star = ShapeKind.STAR.build(square, ShapeParams(ShapeKind.STAR.defaultAdj, 5))
        // Fitted to the square both ways, so compare in the unit star's own ratio along the stretch.
        val c = Pt(50.0, 50.0 + 50.0 * (1.0 - kotlin.math.cos(PI / 5.0)) / (1.0 + kotlin.math.cos(PI / 5.0)))
        val outer = star.corners[0].distanceTo(c)
        val inner = star.corners[1].distanceTo(c)
        assertTrue("dents classic depth: ${inner / outer}", abs(inner / outer - 0.382) < 0.06)
        val k = ShapeKind.STAR.knobs.single()
        val deeper = k.drag(Pt(50.0, 100.0), square, ShapeKind.STAR.defaultAdj, 5)
        assertEquals("dragged through the centre it clamps", 0.05, deeper[0], 1e-9)
    }

    @Test fun knobsClampToWhatTheKindCanDraw() {
        val far = Pt(10_000.0, 10_000.0)
        assertEquals(0.5, ShapeKind.ROUNDED_RECT.knobs.single().drag(far, box, ShapeKind.ROUNDED_RECT.defaultAdj, 0)[0], 0.0)
        assertEquals(0.5, ShapeKind.TRAPEZOID.knobs.single().drag(far, box, ShapeKind.TRAPEZOID.defaultAdj, 0)[0], 0.0)
        assertEquals(0.9, ShapeKind.PARALLELOGRAM.knobs.single().drag(far, box, ShapeKind.PARALLELOGRAM.defaultAdj, 0)[0], 0.0)
        assertEquals(1.0, ShapeKind.CROSS.knobs.single().drag(far, box, ShapeKind.CROSS.defaultAdj, 0)[0], 0.0)
        assertEquals(0.98, ShapeKind.RING.knobs.single().drag(far, box, ShapeKind.RING.defaultAdj, 0)[0], 0.0)
    }

    @Test fun aPieIsThreeQuartersAndItsKnobsTurnIt() {
        val g = geom(ShapeKind.PIE)
        val full = PI * 60.0 * 40.0
        assertEquals(0.75 * full, area(PathFlattener.flatten(g.paths.single().contours.single(), 0.01)), full * 0.01)
        val k = ShapeKind.PIE.knobs[1]
        val adj = k.drag(Pt(box.centerX, box.bottom), box, ShapeKind.PIE.defaultAdj, 0)
        assertEquals(90.0, adj[1], 1e-9)
        assertEquals(0.0, adj[0], 1e-9)
    }

    @Test fun anArcIsOpenAndTakesHeads() {
        val arc = ShapeItem(ShapeKind.ARC, box.topLeft, Pt(box.right, box.bottom), ink, 2.0, headEnd = Head.FILLED)
        assertTrue(ShapeKind.ARC.isOpen)
        assertEquals(PathRole.HEAD_FILLED, arc.paths().last().role)
    }

    @Test fun aRingFillsItsBandAndNotItsHole() {
        val ring = ShapeItem(ShapeKind.RING, box.topLeft, Pt(box.right, box.bottom), ink, 2.0, Rgba(0, 0, 0, 80))
        assertTrue(ring.contains(Pt(box.centerX, box.top + 5.0)))
        assertFalse(ring.contains(box.center))
    }

    @Test fun aMirroredPieMirrorsItsAngles() {
        val pie = ShapeItem(ShapeKind.PIE, box.topLeft, Pt(box.right, box.bottom), ink, 2.0)
        val before = pie.vertexDots().map { it.at }
        val flip = Affine.scaleAbout(box.center, -1.0, 1.0)
        pie.applyTransform(flip)
        assertEquals(ShapeKind.PIE, pie.shape)
        val after = pie.vertexDots().map { it.at }
        for (p in before.map(flip::apply)) assertTrue("missing $p in $after", after.any { it.distanceTo(p) < 1e-6 })
    }

    @Test fun aMirroredRightTriangleStaysOneFlipped() {
        val tri = ShapeItem(ShapeKind.RIGHT_TRIANGLE, box.topLeft, Pt(box.right, box.bottom), ink, 2.0)
        val before = tri.vertexDots().map { it.at }
        val flip = Affine.scaleAbout(box.center, -1.0, 1.0)
        tri.applyTransform(flip)
        assertEquals(ShapeKind.RIGHT_TRIANGLE, tri.shape)
        assertEquals(Flip.X, tri.flip)
        val after = tri.vertexDots().map { it.at }
        for (p in before.map(flip::apply)) assertTrue("missing $p in $after", after.any { it.distanceTo(p) < 1e-9 })
    }

    @Test fun theBasicPageHasItsTwentyFiveChipsInOrder() {
        val keys = ShapePresets.basic.presets.map { it.key }
        assertEquals(25, keys.size)
        assertEquals(listOf("line", "arrow", "spline", "rectangle", "rounded_rect", "square"), keys.take(6))
        assertEquals(listOf("heart", "cloud", "ring", "pie", "arc", ShapePresets.FREE_POLYGON), keys.takeLast(6))
        assertEquals(6, ShapePresets.byKey("hexagon")!!.n)
    }
}
