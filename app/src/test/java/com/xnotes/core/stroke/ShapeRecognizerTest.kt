package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Synthetic, seeded handwriting vectors exercising [ShapeRecognizer]. */
class ShapeRecognizerTest {

    private val rnd = Random(42)

    private fun jit(amp: Double) = (rnd.nextDouble() * 2.0 - 1.0) * amp

    private fun circle(cx: Double, cy: Double, r: Double, n: Int, noise: Double): List<Pt> =
        ellipse(cx, cy, r, r, n, noise)

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, n: Int, noise: Double): List<Pt> =
        (0 until n).map { i ->
            val a = 2.0 * PI * i / n
            Pt(cx + rx * cos(a) + jit(noise), cy + ry * sin(a) + jit(noise))
        }

    private fun line(a: Pt, b: Pt, n: Int, noise: Double): List<Pt> =
        (0 until n).map { i ->
            val t = i.toDouble() / (n - 1)
            Pt(a.x + t * (b.x - a.x) + jit(noise), a.y + t * (b.y - a.y) + jit(noise))
        }

    /** Walk the polygon edges (incl. the closing edge), so first ≈ last ⇒ a closed path. */
    private fun polygon(vertices: List<Pt>, perEdge: Int, noise: Double): List<Pt> {
        val out = ArrayList<Pt>()
        for (e in vertices.indices) {
            val a = vertices[e]
            val b = vertices[(e + 1) % vertices.size]
            for (s in 0 until perEdge) {
                val t = s.toDouble() / perEdge
                out.add(Pt(a.x + t * (b.x - a.x) + jit(noise), a.y + t * (b.y - a.y) + jit(noise)))
            }
        }
        return out
    }

    /** Walk the edges without the closing one, so first and last stay apart ⇒ an open polyline. */
    private fun polyline(vertices: List<Pt>, perEdge: Int, noise: Double): List<Pt> {
        val out = ArrayList<Pt>()
        for (e in 0 until vertices.size - 1) {
            val a = vertices[e]
            val b = vertices[e + 1]
            for (s in 0 until perEdge) {
                val t = s.toDouble() / perEdge
                out.add(Pt(a.x + t * (b.x - a.x) + jit(noise), a.y + t * (b.y - a.y) + jit(noise)))
            }
        }
        out.add(vertices.last())
        return out
    }

    /**
     * A box as a pen actually laps it: rounded corners, a slight tilt, jitter, and samples that
     * bunch through the corners (drawn slowly) and spread along the edges (drawn fast).
     */
    private fun tracedBox(
        w: Double,
        h: Double,
        corner: Double = 16.0,
        tiltDeg: Double = 2.0,
        noise: Double = 3.0,
    ): List<Pt> {
        val cs = listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, h), Pt(0.0, h))
        val out = ArrayList<Pt>()
        for (e in cs.indices) {
            val a = cs[e]
            val b = cs[(e + 1) % 4]
            val c = cs[(e + 2) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            val ux = (b.x - a.x) / len
            val uy = (b.y - a.y) / len
            var t = corner
            while (t < len - corner) {
                out.add(Pt(a.x + ux * t, a.y + uy * t))
                t += 8.0
            }
            val len2 = hypot(c.x - b.x, c.y - b.y)
            val p0 = Pt(b.x - ux * corner, b.y - uy * corner)
            val p1 = Pt(b.x + (c.x - b.x) / len2 * corner, b.y + (c.y - b.y) / len2 * corner)
            for (s in 0..8) { // quarter-arc rounding the corner
                val u = s / 8.0
                val m = 1.0 - u
                out.add(
                    Pt(
                        m * m * p0.x + 2 * m * u * b.x + u * u * p1.x,
                        m * m * p0.y + 2 * m * u * b.y + u * u * p1.y,
                    ),
                )
            }
        }
        val rad = tiltDeg * PI / 180.0
        return out.map {
            val x = it.x + jit(noise)
            val y = it.y + jit(noise)
            Pt(x * cos(rad) - y * sin(rad), x * sin(rad) + y * cos(rad))
        }
    }

    // --- positives ---

    @Test fun circleSnapsToCircle() {
        val rec = ShapeRecognizer.recognizePoints(circle(200.0, 200.0, 150.0, 48, 5.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.CIRCLE, rec!!.kind)
        assertEquals(200.0, (rec.start.x + rec.end.x) / 2.0, 20.0)
        assertEquals(200.0, (rec.start.y + rec.end.y) / 2.0, 20.0)
        assertEquals(150.0, abs(rec.end.x - rec.start.x) / 2.0, 25.0)
        assertEquals(150.0, abs(rec.end.y - rec.start.y) / 2.0, 25.0)
    }

    @Test fun straightStrokeSnapsToLine() {
        val a = Pt(60.0, 70.0)
        val b = Pt(360.0, 300.0)
        val rec = ShapeRecognizer.recognizePoints(line(a, b, 40, 4.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.LINE, rec!!.kind)
        assertEquals(a.x, rec.start.x, 12.0)
        assertEquals(a.y, rec.start.y, 12.0)
        assertEquals(b.x, rec.end.x, 12.0)
        assertEquals(b.y, rec.end.y, 12.0)
    }

    @Test fun boxSnapsToRectangle() {
        val sq = polygon(
            listOf(Pt(50.0, 50.0), Pt(250.0, 50.0), Pt(250.0, 250.0), Pt(50.0, 250.0)),
            perEdge = 16, noise = 5.0,
        )
        val rec = ShapeRecognizer.recognizePoints(sq)
        assertNotNull(rec)
        assertEquals(ShapeKind.RECTANGLE, rec!!.kind)
        assertEquals(200.0, abs(rec.end.x - rec.start.x), 30.0)
        assertEquals(200.0, abs(rec.end.y - rec.start.y), 30.0)
    }

    @Test fun wideBoxKeepsAspect() {
        val r = polygon(
            listOf(Pt(40.0, 80.0), Pt(340.0, 80.0), Pt(340.0, 230.0), Pt(40.0, 230.0)),
            perEdge = 16, noise = 5.0,
        )
        val rec = ShapeRecognizer.recognizePoints(r)
        assertNotNull(rec)
        assertEquals(ShapeKind.RECTANGLE, rec!!.kind)
        assertEquals(300.0, abs(rec.end.x - rec.start.x), 35.0)
        assertEquals(150.0, abs(rec.end.y - rec.start.y), 35.0)
    }

    @Test fun longThinBoxSnapsToRectangle() {
        // 8:1. The default corner window is wider than the short ends, so only the retry pass at a
        // tighter window can tell this box's four corners apart.
        val rec = ShapeRecognizer.recognizePoints(tracedBox(800.0, 100.0, tiltDeg = 0.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.RECTANGLE, rec!!.kind)
        assertEquals(800.0, abs(rec.end.x - rec.start.x), 40.0)
        assertEquals(100.0, abs(rec.end.y - rec.start.y), 40.0)
    }

    @Test fun veryLongBoxSnapsToRectangle() {
        // 16:1, drawn with a tilt and fat rounded corners: still a rectangle.
        val rec = ShapeRecognizer.recognizePoints(tracedBox(800.0, 50.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.RECTANGLE, rec!!.kind)
    }

    @Test fun tallThinBoxSnapsToRectangle() {
        val rec = ShapeRecognizer.recognizePoints(tracedBox(120.0, 800.0, tiltDeg = 0.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.RECTANGLE, rec!!.kind)
        assertEquals(120.0, abs(rec.end.x - rec.start.x), 40.0)
        assertEquals(800.0, abs(rec.end.y - rec.start.y), 40.0)
    }

    @Test fun longBoxKeepsFourCorners() {
        // A tighter window can plant a spare corner on a long edge; the box must still be a box.
        val rec = ShapeRecognizer.recognizePoints(
            polygon(
                listOf(Pt(0.0, 0.0), Pt(800.0, 0.0), Pt(800.0, 80.0), Pt(0.0, 80.0)),
                perEdge = 20, noise = 4.0,
            ),
        )
        assertNotNull(rec)
        assertTrue(
            "a long box is a rectangle or a 4-gon, never more corners",
            rec!!.kind == ShapeKind.RECTANGLE || rec.vertices?.size == 4,
        )
    }

    @Test fun uprightIsoscelesSnapsToTriangle() {
        val tri = polygon(
            listOf(Pt(200.0, 40.0), Pt(360.0, 340.0), Pt(40.0, 340.0)),
            perEdge = 22, noise = 5.0,
        )
        val rec = ShapeRecognizer.recognizePoints(tri)
        assertNotNull(rec)
        assertEquals(ShapeKind.TRIANGLE, rec!!.kind)
        assertEquals(320.0, abs(rec.end.x - rec.start.x), 30.0)
        assertEquals(300.0, abs(rec.end.y - rec.start.y), 30.0)
    }

    @Test fun threeCornerStrokeSnapsToFreePolygon() {
        val tri = polygon(
            listOf(Pt(80.0, 40.0), Pt(360.0, 300.0), Pt(40.0, 340.0)),
            perEdge = 22, noise = 5.0,
        )
        val rec = ShapeRecognizer.recognizePoints(tri)
        assertNotNull(rec)
        assertEquals(ShapeKind.POLYGON, rec!!.kind)
        assertEquals(3, rec.vertices!!.size)
    }

    @Test fun pentagonSnapsToPolygon() {
        val verts = listOf(
            Pt(200.0, 50.0), Pt(342.7, 153.6), Pt(288.2, 321.4), Pt(111.8, 321.4), Pt(57.3, 153.6),
        )
        val rec = ShapeRecognizer.recognizePoints(polygon(verts, perEdge = 18, noise = 4.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.POLYGON, rec!!.kind)
        assertEquals(5, rec.vertices!!.size)
    }

    @Test fun tiltedSquareStaysPolygon() {
        // A 45°-rotated square: its corners sit at the bbox edge midpoints, not the bbox corners,
        // so the hybrid rectangle test must reject it and keep the real (tilted) 4-gon.
        val diamond = polygon(
            listOf(Pt(200.0, 60.0), Pt(340.0, 200.0), Pt(200.0, 340.0), Pt(60.0, 200.0)),
            perEdge = 18, noise = 4.0,
        )
        val rec = ShapeRecognizer.recognizePoints(diamond)
        assertNotNull(rec)
        assertEquals(ShapeKind.POLYGON, rec!!.kind)
        assertEquals(4, rec.vertices!!.size)
    }

    @Test fun openZigZagSnapsToPolyline() {
        val zig = polyline(
            listOf(Pt(40.0, 200.0), Pt(120.0, 80.0), Pt(200.0, 200.0), Pt(280.0, 80.0), Pt(360.0, 200.0)),
            perEdge = 16, noise = 4.0,
        )
        val rec = ShapeRecognizer.recognizePoints(zig)
        assertNotNull(rec)
        assertEquals(ShapeKind.POLYLINE, rec!!.kind)
        assertTrue("expected the zig-zag corners preserved", rec.vertices!!.size in 4..6)
    }

    @Test fun handDrawnCStaysSmoothCurve() {
        val cx = 400.0
        val cy = 400.0
        val r = 250.0
        val c = (0..70).map { i ->
            val a = -PI * 0.42 + (0.83 * PI) * i / 70
            Pt(cx + r * cos(a) + jit(3.0), cy + r * sin(a) + jit(3.0))
        }
        val rec = ShapeRecognizer.recognizePoints(c)
        assertNotNull(rec)
        assertEquals(ShapeKind.SPLINE, rec!!.kind)
        assertTrue(rec.vertices!!.size in 3..7)
        for (p in rec.vertices!!) assertEquals(r, hypot(p.x - cx, p.y - cy), 34.0)
    }

    @Test fun clearOvalStaysEllipse() {
        val rec = ShapeRecognizer.recognizePoints(ellipse(200.0, 200.0, 200.0, 80.0, 56, 4.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.ELLIPSE, rec!!.kind)
        val w = abs(rec.end.x - rec.start.x)
        val h = abs(rec.end.y - rec.start.y)
        assertEquals(400.0, w, 40.0)
        assertEquals(160.0, h, 40.0)
        assertTrue("a clear oval keeps its aspect", w - h > 100.0)
    }

    @Test fun nearCircleOvalSnapsToCircle() {
        // Aspect ~0.87 is within the balanced circle threshold, so it squares up to a circle.
        val rec = ShapeRecognizer.recognizePoints(ellipse(200.0, 200.0, 150.0, 130.0, 56, 4.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.CIRCLE, rec!!.kind)
        val w = abs(rec.end.x - rec.start.x)
        val h = abs(rec.end.y - rec.start.y)
        assertEquals("near-circle squares up", w, h, 6.0)
        assertEquals(200.0, (rec.start.x + rec.end.x) / 2.0, 12.0)
        assertEquals(200.0, (rec.start.y + rec.end.y) / 2.0, 12.0)
    }

    // --- negatives (stay ink) ---

    @Test fun tapIsNotAShape() {
        assertNull(ShapeRecognizer.recognizePoints(listOf(Pt(10.0, 10.0), Pt(11.0, 11.0), Pt(10.5, 10.5))))
    }

    @Test fun tinyStrokeIsNotAShape() {
        assertNull(ShapeRecognizer.recognizePoints(circle(10.0, 10.0, 6.0, 40, 0.5)))
    }

    @Test fun thinOvalStaysEllipse() {
        // A long flat oval hugs the top and bottom of its box, so it must not read as a rectangle.
        val rec = ShapeRecognizer.recognizePoints(ellipse(400.0, 100.0, 400.0, 45.0, 90, 4.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.ELLIPSE, rec!!.kind)
    }

    @Test fun thinDiamondIsNotARectangle() {
        // Its corners sit at the box's edge midpoints, half the (short) height from a box corner.
        val rec = ShapeRecognizer.recognizePoints(
            polygon(
                listOf(Pt(0.0, 60.0), Pt(400.0, 0.0), Pt(800.0, 60.0), Pt(400.0, 120.0)),
                perEdge = 24, noise = 3.0,
            ),
        )
        assertTrue("a flat diamond is never squared up", rec?.kind != ShapeKind.RECTANGLE)
    }

    @Test fun strikeThroughStaysInk() {
        // A line scrubbed back over itself: a hair-thin loop, not a box.
        val backForth = (0 until 60).map { i ->
            val t = i / 59.0
            if (t < 0.5) Pt(800.0 * t * 2.0, jit(3.0)) else Pt(800.0 * (1.0 - (t - 0.5) * 2.0), 8.0 + jit(3.0))
        }
        assertTrue(ShapeRecognizer.recognizePoints(backForth)?.kind != ShapeKind.RECTANGLE)
    }

    @Test fun openArcSnapsToCurve() {
        // A ~150° arc: open (ends far apart), bows far from its chord (not a line), no sharp
        // corners (not a polyline), and within one cubic's reach -> a smooth curve.
        val arc = (0..40).map { i ->
            val a = (0.83 * PI) * i / 40.0
            Pt(200.0 + 150.0 * cos(a), 200.0 + 150.0 * sin(a))
        }
        val rec = ShapeRecognizer.recognizePoints(arc)
        assertNotNull(rec)
        assertTrue(rec!!.kind == ShapeKind.SPLINE || rec.kind == ShapeKind.FUNCTION)
    }

    @Test fun sCurveSnapsToCurve() {
        // One sine period over a descending y: a flowing S with two bends and no sharp corners.
        val s = (0..60).map { i ->
            val t = i / 60.0
            Pt(200.0 + 80.0 * sin(2.0 * PI * t) + jit(3.0), 40.0 + 320.0 * t + jit(3.0))
        }
        val rec = ShapeRecognizer.recognizePoints(s)
        assertNotNull(rec)
        assertEquals(ShapeKind.SPLINE, rec!!.kind)
        // The fit keeps the stroke's endpoints.
        assertEquals(s.first().x, rec.vertices!!.first().x, 18.0)
        assertEquals(s.last().x, rec.vertices!!.last().x, 18.0)
    }

    // --- properties ---

    @Test fun recognitionIsDeterministic() {
        val pts = circle(150.0, 150.0, 120.0, 48, 4.0)
        assertEquals(ShapeRecognizer.recognizePoints(pts), ShapeRecognizer.recognizePoints(pts))
    }

    @Test fun classificationIsScaleInvariant() {
        val small = circle(100.0, 100.0, 80.0, 48, 3.0)
        val big = small.map { Pt(it.x * 5.0, it.y * 5.0) }
        assertEquals(ShapeKind.CIRCLE, ShapeRecognizer.recognizePoints(small)?.kind)
        assertEquals(ShapeKind.CIRCLE, ShapeRecognizer.recognizePoints(big)?.kind)
    }

    @Test fun recognizeReadsSamplePositions() {
        val samples = circle(180.0, 180.0, 140.0, 48, 4.0).map { Sample(it.x, it.y, 1.0) }
        val rec = ShapeRecognizer.recognize(samples)
        assertNotNull(rec)
        assertEquals(ShapeKind.CIRCLE, rec!!.kind)
    }

    @Test fun lineEndpointsAreOrdered() {
        // A near-horizontal stroke stays open and snaps to a line between its true ends.
        val rec = ShapeRecognizer.recognizePoints(line(Pt(20.0, 100.0), Pt(320.0, 110.0), 30, 3.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.LINE, rec!!.kind)
        assertTrue(rec.start.x < rec.end.x)
    }

    // --- axis snapping (horizontal / vertical) ---

    @Test fun nearHorizontalLineSnapsFlat() {
        // ~1.5° off horizontal: well inside the snap angle, so both ends level to one y.
        val rec = ShapeRecognizer.recognizePoints(line(Pt(20.0, 100.0), Pt(320.0, 108.0), 30, 2.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.LINE, rec!!.kind)
        assertEquals(rec.start.y, rec.end.y, 1e-9)
        assertTrue(rec.start.x < rec.end.x)
    }

    @Test fun nearVerticalLineSnapsFlat() {
        // ~1.5° off vertical: both ends line up on one x.
        val rec = ShapeRecognizer.recognizePoints(line(Pt(100.0, 20.0), Pt(108.0, 320.0), 30, 2.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.LINE, rec!!.kind)
        assertEquals(rec.start.x, rec.end.x, 1e-9)
        assertTrue(rec.start.y < rec.end.y)
    }

    @Test fun tiltedLineBeyondThresholdStaysTilted() {
        // ~27° off horizontal: too steep to snap, so the line keeps its drawn slant.
        val rec = ShapeRecognizer.recognizePoints(line(Pt(40.0, 60.0), Pt(340.0, 210.0), 30, 2.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.LINE, rec!!.kind)
        assertTrue("a clearly slanted line is left alone", abs(rec.end.y - rec.start.y) > 100.0)
    }

    @Test fun zigZagStaircaseSnapsAxisAligned() {
        // A slightly skewed staircase: every run is near an axis, so all three edges square up.
        val stair = polyline(
            listOf(Pt(40.0, 100.0), Pt(200.0, 108.0), Pt(208.0, 260.0), Pt(368.0, 268.0)),
            perEdge = 18, noise = 3.0,
        )
        val rec = ShapeRecognizer.recognizePoints(stair)
        assertNotNull(rec)
        assertEquals(ShapeKind.POLYLINE, rec!!.kind)
        val v = rec.vertices!!
        assertEquals(4, v.size)
        assertEquals("first run is horizontal", v[0].y, v[1].y, 1e-9)
        assertEquals("middle run is vertical", v[1].x, v[2].x, 1e-9)
        assertEquals("last run is horizontal", v[2].y, v[3].y, 1e-9)
    }

    @Test fun triangleNearHorizontalBaseLevels() {
        // A triangle whose base is ~1.5° off level: the base flattens, the steep sides are left alone.
        val tri = polygon(
            listOf(Pt(90.0, 40.0), Pt(360.0, 344.0), Pt(40.0, 336.0)),
            perEdge = 22, noise = 3.0,
        )
        val rec = ShapeRecognizer.recognizePoints(tri)
        assertNotNull(rec)
        assertEquals(ShapeKind.POLYGON, rec!!.kind)
        val v = rec.vertices!!
        assertEquals(3, v.size)
        var horiz = 0
        for (i in v.indices) {
            val a = v[i]
            val b = v[(i + 1) % v.size]
            if (abs(a.y - b.y) < 1e-9) horiz++
        }
        assertEquals("exactly the base levels to horizontal", 1, horiz)
    }

    @Test fun diagonalEdgesAreNotSnapped() {
        // A 45°-rotated square: every edge is far from both axes, so none should be straightened.
        val diamond = polygon(
            listOf(Pt(200.0, 60.0), Pt(340.0, 200.0), Pt(200.0, 340.0), Pt(60.0, 200.0)),
            perEdge = 18, noise = 4.0,
        )
        val rec = ShapeRecognizer.recognizePoints(diamond)
        assertNotNull(rec)
        assertEquals(ShapeKind.POLYGON, rec!!.kind)
        val v = rec.vertices!!
        for (i in v.indices) {
            val a = v[i]
            val b = v[(i + 1) % v.size]
            assertTrue("a 45° edge keeps both components", abs(a.x - b.x) > 20.0 && abs(a.y - b.y) > 20.0)
        }
    }

    // --- function graphs ---

    private fun graph(f: (Double) -> Double, from: Double, to: Double, x0: Double, w: Double, h: Double, noise: Double): List<Pt> {
        val n = 70
        val ys = (0..n).map { f(from + (to - from) * it / n) }
        val lo = ys.min()
        val hi = ys.max()
        return (0..n).map { i ->
            Pt(x0 + w * i / n + jit(noise), 40.0 + h * (hi - ys[i]) / (hi - lo) + jit(noise))
        }
    }

    @Test fun sineWaveSnapsToFunction() {
        val rec = ShapeRecognizer.recognizePoints(graph({ sin(it) }, 0.0, 4 * PI, 40.0, 600.0, 120.0, 3.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.FUNCTION, rec!!.kind)
        val spec = rec.function!!
        assertEquals(4 * PI, spec.to - spec.from, 0.5)
    }

    @Test fun cosineWaveSnapsToCos() {
        val rec = ShapeRecognizer.recognizePoints(graph({ cos(it) }, 0.0, 2 * PI, 40.0, 400.0, 150.0, 3.0))
        assertEquals(ShapeKind.FUNCTION, rec!!.kind)
        assertEquals("cos(x)", rec.function!!.expr)
    }

    @Test fun parabolaSnapsToFunction() {
        val rec = ShapeRecognizer.recognizePoints(graph({ it * it }, -2.0, 2.0, 40.0, 300.0, 300.0, 3.0))
        assertEquals(ShapeKind.FUNCTION, rec!!.kind)
        assertEquals("x^2", rec.function!!.expr)
    }

    @Test fun exponentialSnapsToFunction() {
        val rec = ShapeRecognizer.recognizePoints(graph({ kotlin.math.exp(it) }, -2.0, 2.0, 40.0, 200.0, 360.0, 3.0))
        assertEquals(ShapeKind.FUNCTION, rec!!.kind)
        assertEquals("e^x", rec.function!!.expr)
    }

    @Test fun logarithmSnapsToFunction() {
        val rec = ShapeRecognizer.recognizePoints(graph({ kotlin.math.ln(it) }, 0.3, 6.0, 40.0, 360.0, 200.0, 3.0))
        assertEquals(ShapeKind.FUNCTION, rec!!.kind)
        assertEquals("ln(x)", rec.function!!.expr)
    }

    @Test fun cubicSnapsToFunction() {
        val rec = ShapeRecognizer.recognizePoints(graph({ it * it * it }, -1.5, 1.5, 40.0, 240.0, 360.0, 3.0))
        assertEquals(ShapeKind.FUNCTION, rec!!.kind)
        assertEquals("x^3", rec.function!!.expr)
    }

    @Test fun openScribbleStaysInk() {
        val scribble = (0 until 80).map { i ->
            val t = i / 79.0
            Pt(40.0 + 300.0 * t + 90.0 * sin(t * 37.0), 200.0 + 120.0 * cos(t * 23.0) + jit(6.0))
        }
        assertNull(ShapeRecognizer.recognizePoints(scribble))
    }
}
