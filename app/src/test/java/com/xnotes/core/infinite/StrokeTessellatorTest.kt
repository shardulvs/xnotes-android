package com.xnotes.core.infinite

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Rect
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeEngine
import com.xnotes.core.stroke.StrokeGeometry
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StrokeTessellatorTest {

    private fun geometryOf(vararg pts: Pair<Double, Double>, tool: Tool = Tool.PEN): StrokeGeometry {
        val c = ToolDefaults.configFor(tool)
        return StrokeEngine.build(
            pts.map { Sample(it.first, it.second, 1.0) },
            c.baseWidth, c.pressureEnabled, c.pressureMinFactor, c.directionStrength,
            c.speedStrength, c.taperEnabled, c.taperMinFactor,
            holdEnds = tool == Tool.PEN,
        )
    }

    private fun line(n: Int, dx: Double = 4.0): StrokeGeometry =
        geometryOf(*Array(n) { (it * dx) to 0.0 })

    /** Every point of the mesh, as (x, y) pairs. */
    private fun points(m: MeshData): List<Pair<Double, Double>> =
        (0 until m.vertexCount).map { m.positions[2 * it] to m.positions[2 * it + 1] }

    private fun meshBounds(m: MeshData): Rect {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for ((x, y) in points(m)) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return Rect(minX, minY, maxX - minX, maxY - minY)
    }

    /** Winding-agnostic point-in-mesh test: is the point inside any triangle? */
    private fun covers(m: MeshData, px: Double, py: Double): Boolean {
        for (t in 0 until m.triangleCount) {
            val a = m.indices[3 * t]
            val b = m.indices[3 * t + 1]
            val c = m.indices[3 * t + 2]
            if (inTriangle(
                    px, py,
                    m.positions[2 * a], m.positions[2 * a + 1],
                    m.positions[2 * b], m.positions[2 * b + 1],
                    m.positions[2 * c], m.positions[2 * c + 1],
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun inTriangle(
        px: Double, py: Double,
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
    ): Boolean {
        fun side(x1: Double, y1: Double, x2: Double, y2: Double) =
            (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
        val d1 = side(ax, ay, bx, by)
        val d2 = side(bx, by, cx, cy)
        val d3 = side(cx, cy, ax, ay)
        val neg = d1 < -1e-12 || d2 < -1e-12 || d3 < -1e-12
        val pos = d1 > 1e-12 || d2 > 1e-12 || d3 > 1e-12
        return !(neg && pos)
    }

    // --- the dashed pen ---

    /** Midpoint of every centerline segment, which is where a gap is easiest to probe for. */
    private fun midpoints(g: StrokeGeometry): List<Pair<Double, Double>> =
        (0 until g.pointCount - 1).map {
            (g.cx(it) + g.cx(it + 1)) / 2.0 to (g.cy(it) + g.cy(it + 1)) / 2.0
        }

    @Test fun aDashedLineIsDrawnInPiecesRatherThanSolid() {
        val g = line(40, 5.0)
        val m = StrokeTessellator.tessellateDashed(g, 12.0, 12.0, 1.5)
        val probes = midpoints(g)
        assertTrue("dashes must be drawn", probes.any { covers(m, it.first, it.second) })
        assertTrue("gaps must be left", probes.any { !covers(m, it.first, it.second) })
    }

    @Test fun aDashedLineStaysOnItsOwnCenterline() {
        val g = geometryOf(0.0 to 0.0, 20.0 to 12.0, 44.0 to 8.0, 70.0 to 30.0)
        val m = StrokeTessellator.tessellateDashed(g, 6.0, 5.0, 1.5)
        assertTrue(meshBounds(m).w <= meshBounds(StrokeTessellator.tessellate(g)).w + 1e-6)
    }

    @Test fun aDegenerateDashDrawsTheWholeLine() {
        val g = line(40, 5.0)
        val m = StrokeTessellator.tessellateDashed(g, 12.0, 0.0, 1.5)
        for ((x, y) in midpoints(g)) assertTrue("gap at $x", covers(m, x, y))
    }

    @Test fun aDashedDotIsStillADisc() {
        val g = geometryOf(5.0 to 7.0)
        val m = StrokeTessellator.tessellateDashed(g, 10.0, 8.0, 1.5)
        assertTrue(covers(m, 5.0, 7.0))
    }

    @Test fun aDashWithNoWidthDrawsNothing() {
        assertTrue(StrokeTessellator.tessellateDashed(line(10), 10.0, 8.0, 0.0).isEmpty)
    }

    // --- mesh shape ---

    @Test fun anEmptyGeometryTessellatesToNothing() {
        val m = StrokeTessellator.tessellate(StrokeGeometry.EMPTY)
        assertTrue(m.isEmpty)
        assertEquals(0, m.vertexCount)
    }

    @Test fun aSingleSampleBecomesADisc() {
        val g = geometryOf(5.0 to 7.0)
        val m = StrokeTessellator.tessellate(g)
        assertTrue(m.triangleCount >= StrokeTessellator.MIN_CIRCLE_SEGMENTS)
        assertTrue(covers(m, 5.0, 7.0))
        assertTrue(covers(m, 5.0 + g.hw(0) * 0.8, 7.0))
        assertTrue(!covers(m, 5.0 + g.hw(0) * 2.0, 7.0))
    }

    @Test fun aStraightLineCoversItsWholeLength() {
        val g = line(8, dx = 5.0)
        val m = StrokeTessellator.tessellate(g)
        var x = g.cx(0)
        while (x <= g.cx(g.pointCount - 1)) {
            assertTrue("gap on the centerline at x=$x", covers(m, x, 0.0))
            x += 0.5
        }
    }

    @Test fun theRibbonIsWatertightAcrossASharpCorner() {
        val g = geometryOf(0.0 to 0.0, 20.0 to 0.0, 20.0 to 20.0, 0.0 to 20.0)
        val m = StrokeTessellator.tessellate(g)
        for (i in 0 until g.pointCount) {
            assertTrue("hole at sample $i", covers(m, g.cx(i), g.cy(i)))
        }
        // And along the centerline between samples, where a pinched join would leave a notch.
        for (i in 0 until g.pointCount - 1) {
            for (t in 1..9) {
                val f = t / 10.0
                val x = g.cx(i) + (g.cx(i + 1) - g.cx(i)) * f
                val y = g.cy(i) + (g.cy(i + 1) - g.cy(i)) * f
                assertTrue("hole between samples $i and ${i + 1}", covers(m, x, y))
            }
        }
    }

    @Test fun theEndsAreRoundedPastTheLastSample() {
        val g = line(6, dx = 6.0)
        val m = StrokeTessellator.tessellate(g)
        val h = g.hw(0)
        // A flat cap would stop at the sample; the round one reaches most of a half-width past it.
        assertTrue("start cap is flat", covers(m, g.cx(0) - h * 0.7, 0.0))
        val last = g.pointCount - 1
        assertTrue("end cap is flat", covers(m, g.cx(last) + g.hw(last) * 0.7, 0.0))
    }

    @Test fun theMeshStaysInsideTheStrokeBounds() {
        val g = geometryOf(0.0 to 0.0, 14.0 to 9.0, 30.0 to 4.0, 41.0 to 22.0)
        val m = StrokeTessellator.tessellate(g)
        val mb = meshBounds(m)
        // Every mesh vertex is within the stroke's own disc-swept bounds, plus the chord tolerance.
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until g.pointCount) {
            minX = minOf(minX, g.cx(i) - g.hw(i))
            maxX = maxOf(maxX, g.cx(i) + g.hw(i))
            minY = minOf(minY, g.cy(i) - g.hw(i))
            maxY = maxOf(maxY, g.cy(i) + g.hw(i))
        }
        val slack = 1e-6
        assertTrue(mb.left >= minX - slack)
        assertTrue(mb.top >= minY - slack)
        assertTrue(mb.right <= maxX + slack)
        assertTrue(mb.bottom <= maxY + slack)
    }

    @Test fun everyIndexPointsAtARealVertex() {
        for (g in listOf(line(2), line(9), geometryOf(0.0 to 0.0), geometryOf(0.0 to 0.0, 3.0 to 40.0, 3.0 to 0.0))) {
            val m = StrokeTessellator.tessellate(g)
            assertEquals(0, m.indices.size % 3)
            for (i in m.indices) {
                assertTrue("index $i out of range", i >= 0 && i < m.vertexCount)
            }
        }
    }

    @Test fun noTriangleIsDegenerate() {
        val g = geometryOf(0.0 to 0.0, 11.0 to 2.0, 23.0 to 9.0, 30.0 to 25.0)
        val m = StrokeTessellator.tessellate(g)
        var degenerate = 0
        for (t in 0 until m.triangleCount) {
            val a = m.indices[3 * t]
            val b = m.indices[3 * t + 1]
            val c = m.indices[3 * t + 2]
            val area = kotlin.math.abs(
                (m.positions[2 * b] - m.positions[2 * a]) * (m.positions[2 * c + 1] - m.positions[2 * a + 1]) -
                    (m.positions[2 * c] - m.positions[2 * a]) * (m.positions[2 * b + 1] - m.positions[2 * a + 1]),
            ) / 2.0
            if (area < 1e-9) degenerate++
        }
        assertEquals(0, degenerate)
    }

    // --- chord tolerance ---

    @Test fun aTighterToleranceCutsMoreSegments() {
        val coarse = StrokeTessellator.circleSegments(3.0, 0.5)
        val fine = StrokeTessellator.circleSegments(3.0, 0.5 / 64.0)
        assertTrue(fine > coarse)
    }

    @Test fun segmentCountsStayInsideTheirBounds() {
        for (r in listOf(0.01, 0.5, 3.0, 20.0, 400.0)) {
            for (tol in listOf(1e-6, 0.008, 0.1, 5.0)) {
                val n = StrokeTessellator.circleSegments(r, tol)
                assertTrue(n >= StrokeTessellator.MIN_CIRCLE_SEGMENTS)
                assertTrue(n <= StrokeTessellator.MAX_CIRCLE_SEGMENTS)
            }
        }
    }

    @Test fun theDefaultToleranceHoldsHalfAPixelAtMaxZoom() {
        val radius = 1.5 // a 3 px pen
        val n = StrokeTessellator.circleSegments(radius, StrokeTessellator.DEFAULT_TOLERANCE)
        val sagitta = radius * (1.0 - kotlin.math.cos(Math.PI / n))
        assertTrue(
            "chord error $sagitta content px shows at ${sagitta * CanvasViewport.MAX_ZOOM} device px",
            sagitta * CanvasViewport.MAX_ZOOM <= 0.5 + 1e-9,
        )
    }

    @Test fun degenerateRadiiAndTolerancesDoNotThrow() {
        assertTrue(StrokeTessellator.circleSegments(0.0, 0.01) >= StrokeTessellator.MIN_CIRCLE_SEGMENTS)
        assertTrue(StrokeTessellator.circleSegments(-1.0, 0.01) >= StrokeTessellator.MIN_CIRCLE_SEGMENTS)
        assertTrue(StrokeTessellator.circleSegments(3.0, 0.0) <= StrokeTessellator.MAX_CIRCLE_SEGMENTS)
        assertTrue(StrokeTessellator.circleSegments(3.0, Double.NaN) <= StrokeTessellator.MAX_CIRCLE_SEGMENTS)
    }

    // --- the swept disc ---

    /** Calligraphy on a wave, the nib swelling as fast as it may, and on the broadest a pressure dip that swallows a disc. */
    private fun swells(): List<StrokeGeometry> {
        val pts = (0..80).map { Sample(it * 1.5, 22.0 * kotlin.math.sin(it * 1.5 / 9.0), 1.0) }
        val dip = pts.mapIndexed { i, s -> if (i in 40..43) s.copy(pressure = 0.0) else s }
        return listOf(StrokeEngine.build(pts, 12.0, true, 0.4, 0.6), StrokeEngine.build(dip, 24.0, true, 0.4, 0.9))
    }

    @Test fun theBroadestSwellReallySwallowsADisc() {
        val g = swells().last()
        assertTrue((0 until g.pointCount - 1).any {
            kotlin.math.abs(g.hw(it + 1) - g.hw(it)) >= hypot(g.cx(it + 1) - g.cx(it), g.cy(it + 1) - g.cy(it))
        })
    }

    /** Whether ([px], [py]) is in the disc swept down [g] with every radius grown by [grow]: the paged painter's ink. */
    private fun inSweep(g: StrokeGeometry, px: Double, py: Double, grow: Double): Boolean {
        for (i in 0 until g.pointCount) {
            val r = g.hw(i) + grow
            if (r > 0.0 && hypot(px - g.cx(i), py - g.cy(i)) <= r) return true
        }
        val n = DoubleArray(4)
        for (i in 0 until g.pointCount - 1) {
            val r0 = g.hw(i) + grow
            val r1 = g.hw(i + 1) + grow
            if (r0 <= 0.0 || r1 <= 0.0) continue
            if (Geometry.capsule(g.cx(i), g.cy(i), r0, g.cx(i + 1), g.cy(i + 1), r1, n) == Geometry.SWALLOWED) continue
            val ax = g.cx(i) + r0 * n[0]
            val ay = g.cy(i) + r0 * n[1]
            val bx = g.cx(i + 1) + r1 * n[0]
            val by = g.cy(i + 1) + r1 * n[1]
            val cx = g.cx(i + 1) + r1 * n[2]
            val cy = g.cy(i + 1) + r1 * n[3]
            val dx = g.cx(i) + r0 * n[2]
            val dy = g.cy(i) + r0 * n[3]
            if (inTriangle(px, py, ax, ay, bx, by, cx, cy) || inTriangle(px, py, ax, ay, cx, cy, dx, dy)) return true
        }
        return false
    }

    /** Every grid point around [g] at [step] spacing. */
    private fun grid(g: StrokeGeometry, step: Double): List<Pair<Double, Double>> {
        val widest = (0 until g.pointCount).maxOf { g.hw(it) }
        val xs = (0 until g.pointCount).map { g.cx(it) }
        val ys = (0 until g.pointCount).map { g.cy(it) }
        val out = ArrayList<Pair<Double, Double>>()
        var y = ys.min() - widest - 1.0
        while (y <= ys.max() + widest + 1.0) {
            var x = xs.min() - widest - 1.0
            while (x <= xs.max() + widest + 1.0) {
                out += x to y
                x += step
            }
            y += step
        }
        return out
    }

    @Test fun theMeshFillsTheSweptDiscANotePaints() {
        for (g in swells()) {
            val m = StrokeTessellator.tessellate(g)
            // What a shared join vertex may cut off a rim, plus a little: well under a pixel.
            val margin = 0.01 * (0 until g.pointCount).maxOf { g.hw(it) }
            var probed = 0
            for ((x, y) in grid(g, 0.5)) {
                if (!inSweep(g, x, y, -margin)) continue
                probed++
                assertTrue("the note paints ($x, $y) and the canvas does not", covers(m, x, y))
            }
            assertTrue("nothing was probed", probed > 2000)
        }
    }

    @Test fun theMeshPaintsNothingPastTheSweptDisc() {
        for (g in swells()) {
            val m = StrokeTessellator.tessellate(g)
            for ((x, y) in grid(g, 0.5)) {
                if (inSweep(g, x, y, 1e-6)) continue
                assertTrue("the canvas paints ($x, $y) and the note does not", !covers(m, x, y))
            }
        }
    }

    @Test fun aSmoothStrokeCostsTwoVerticesASample() {
        val pts = (0..20).map { (it * 3.0) to (it * 0.15) }
        val g = geometryOf(*pts.toTypedArray())
        val m = StrokeTessellator.tessellate(g)
        val last = g.pointCount - 1
        val tol = StrokeTessellator.DEFAULT_TOLERANCE
        val caps = 2 + StrokeTessellator.circleSegments(g.hw(0), tol) + StrokeTessellator.circleSegments(g.hw(last), tol)
        assertEquals(2 * g.pointCount + caps, m.vertexCount)
    }

    // --- scale ---

    @Test fun costGrowsLinearlyWithSampleCount() {
        val short = StrokeTessellator.tessellate(line(10))
        val long = StrokeTessellator.tessellate(line(100))
        // The caps are a fixed overhead, so the body dominates and the ratio tracks the samples.
        val bodyShort = short.triangleCount
        val bodyLong = long.triangleCount
        assertTrue(bodyLong > bodyShort)
        assertTrue("cost should stay near linear", bodyLong < bodyShort * 12)
    }

    @Test fun aHighlighterRibbonTessellatesLikeAPenOne() {
        val g = geometryOf(0.0 to 0.0, 40.0 to 0.0, 80.0 to 10.0, tool = Tool.HIGHLIGHTER)
        val m = StrokeTessellator.tessellate(g)
        assertTrue(!m.isEmpty)
        assertTrue(covers(m, 40.0, 0.0))
        assertTrue(covers(m, 20.0, 0.0))
    }

    @Test fun everyRailVertexKnowsHowFarItSitsFromTheCentreline() {
        val g = line(6)
        val m = StrokeTessellator.tessellate(g)
        assertEquals(m.positions.size, m.offsets.size)
        var offRail = 0
        for (i in 0 until m.vertexCount) {
            val ox = m.offsets[2 * i]
            val oy = m.offsets[2 * i + 1]
            if (hypot(ox, oy) > 1e-9) offRail++
        }
        assertTrue("the ribbon and its caps must carry offsets", offRail > 0)
        // Every offset is at most a half-width, since that is how far the rails reach.
        val widest = (0 until g.pointCount).maxOf { g.hw(it) }
        for (i in 0 until m.vertexCount) {
            assertTrue(hypot(m.offsets[2 * i], m.offsets[2 * i + 1]) <= widest + 1e-6)
        }
    }

    @Test fun aStrokeThinnerThanAPixelFadesRatherThanVanishing() {
        // The shader's rule, checked here as the arithmetic it implements.
        val halfWidth = 1.5
        val zoom = 0.05
        val reach = halfWidth * zoom
        assertTrue("this stroke really is sub-pixel", reach < 0.5)
        val fade = reach / 0.5
        val widened = halfWidth * (0.5 / reach)
        assertEquals("widened back to the floor", 0.5, widened * zoom, 1e-9)
        assertEquals("and the width taken back out of the alpha", reach, widened * zoom * fade, 1e-9)
    }

    @Test fun tessellationIsDeterministic() {
        val g = geometryOf(0.0 to 0.0, 12.0 to 5.0, 25.0 to 3.0)
        val a = StrokeTessellator.tessellate(g)
        val b = StrokeTessellator.tessellate(g)
        assertTrue(a.positions.contentEquals(b.positions))
        assertTrue(a.offsets.contentEquals(b.offsets))
        assertTrue(a.indices.contentEquals(b.indices))
    }
}
