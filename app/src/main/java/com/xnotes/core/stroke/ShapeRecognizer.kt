package com.xnotes.core.stroke

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.tools.ShapeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A geometric shape inferred from a freehand stroke (the "hold to snap" gesture).
 * [start]/[end] are opposite corners of the bounding box (or the two endpoints for
 * [ShapeKind.LINE]); [vertices] carries the corner list (content px) for the
 * [ShapeKind.POLYGON] and [ShapeKind.POLYLINE] kinds and the control points for
 * [ShapeKind.SPLINE], and is null for the rest; [function] is set for [ShapeKind.FUNCTION].
 */
data class RecognizedShape(
    val kind: ShapeKind,
    val start: Pt,
    val end: Pt,
    val vertices: List<Pt>? = null,
    val function: FunctionSpec? = null,
)

/**
 * Classifies a freehand stroke into a clean shape, or `null` when it is not a confident
 * match (the caller then leaves the stroke as ink).
 *
 * Pure and deterministic so it can be unit-tested on the plain JVM, like [StrokeEngine].
 * Works in page-local content px; thresholds are fractions of the stroke's bounding-box
 * diagonal or plain angles, so recognition is scale- (and zoom-) independent.
 *
 * Shapes recognized:
 *  - open straight stroke -> [ShapeKind.LINE]
 *  - open multi-segment zig-zag -> [ShapeKind.POLYLINE] (vertices preserved)
 *  - an open graph of a known function (parabola, cubic, exp, ln, sine wave) -> [ShapeKind.FUNCTION]
 *  - any other smooth open stroke (a C, an S, ...) -> an editable [ShapeKind.SPLINE] through a few
 *    control points, or left as ink if a short spline can't follow it
 *  - closed round blob -> [ShapeKind.ELLIPSE], or [ShapeKind.CIRCLE] when nearly round
 *  - closed n-gon with sharp corners -> [ShapeKind.RECTANGLE] when it is an upright box (squared up
 *    when its sides nearly match), [ShapeKind.TRIANGLE] for an upright isosceles triangle, else
 *    [ShapeKind.POLYGON] (vertices preserved)
 *
 * Corners drive the closed-shape decision: a smooth outline (no sharp turns) is the only
 * thing that becomes an ellipse, so a hexagon stays a polygon and a circle never does. Corners are
 * read over a window a twelfth of the path long, which a short edge (the end of a long thin
 * rectangle) fits inside, hiding its two corners as one; a stroke that matches nothing is therefore
 * read again on a denser resample at tighter windows before it is given up on as ink.
 *
 * A line, and every polygon/polyline edge, that lands within [AXIS_SNAP_DEG] of horizontal or
 * vertical is straightened flat ([snapAxisAligned]), the way a near-round blob squares to a circle.
 */
object ShapeRecognizer {

    /** Fewer samples than this is a tap or tick, never a shape. */
    private const val MIN_POINTS = 8

    /** Bounding-box diagonal (content px) below which the stroke is too small to snap. */
    private const val MIN_DIAGONAL = 24.0

    /** |first-last| / diagonal at or under this counts the path as closed. */
    private const val CLOSED_GAP_FRAC = 0.22

    /** max perpendicular deviation / chord length at or under this is a straight line. */
    private const val LINE_DEV_FRAC = 0.08

    /** Points the path is resampled to before measuring roundness / corners. */
    private const val RESAMPLE_N = 64

    /** Denser resample used by the retry pass, so a tighter corner window still has samples to work with. */
    private const val FINE_RESAMPLE_N = 128

    /** Corner windows (samples on the [FINE_RESAMPLE_N] path) the retry pass tries, widest first.
     *  A window longer than an edge merges that edge's two corners into one, which is what hides
     *  the short ends of a long thin rectangle from the default window. */
    private val FINE_CORNER_K = intArrayOf(5, 3, 2)

    /** Most vertices a tight-window retry may claim. Short edges belong to plain shapes (a box, a
     *  triangle, an L); a tight window on a wavy scribble finds "corners" all along it, and this is
     *  what stops those from being taken for a drawn polygon. */
    private const val FINE_MAX_VERTS = 6

    /** RMS normalized ellipse residual at or under this is an ellipse/circle. */
    private const val ELLIPSE_RESIDUAL_FRAC = 0.11

    /** Turn (direction change) at a vertex at or above this counts as a corner. */
    private const val CORNER_ANGLE_DEG = 50.0

    private val CORNER_ANGLE_RAD = CORNER_ANGLE_DEG * PI / 180.0

    /** A corner candidate must also turn this sharply over a tight window, else it is a smooth bend. */
    private const val SHARP_CORNER_DEG = 42.0

    private val SHARP_CORNER_RAD = SHARP_CORNER_DEG * PI / 180.0

    /** Shorter axis / longer axis at or above this snaps a recognized ellipse to a circle. */
    private const val CIRCLE_ASPECT_MIN = 0.80

    /** A 4-gon whose corners each sit within this·diagonal of a distinct bbox corner is an upright rectangle. */
    private const val RECT_CORNER_FRAC = 0.12

    /** Cap on that tolerance as a fraction of the shorter bbox side, so a long thin 4-gon still has
     *  to reach its corners (at 0.5 a thin diamond's corners would land on the bbox corners). */
    private const val RECT_CORNER_SHORT_FRAC = 0.35

    /** A line, or a polygon/polyline edge, within this angle of horizontal or vertical snaps flat.
     *  Matches the rectangle's corner tolerance (RECT_CORNER_FRAC·diag is about a 10° edge tilt). */
    private const val AXIS_SNAP_DEG = 8.0

    private val AXIS_SNAP_RAD = AXIS_SNAP_DEG * PI / 180.0

    /** Max edge bow / diagonal for a polygon/polyline edge to count as straight. */
    private const val EDGE_DEV_FRAC = 0.06

    /** Cap on that bow as a fraction of the shorter bbox side: a thin shape must not be validated
     *  at a tolerance wider than it is thick, else a missed corner (which pulls an edge about half
     *  the short side off course) still reads as a straight edge. */
    private const val EDGE_DEV_SHORT_FRAC = 0.25

    /** Share of each edge dropped at both ends before fitting its line, to skip the rounded corners. */
    private const val CORNER_FIT_TRIM = 0.25

    /** Two edges must cross at least this steeply (sine of the angle) for their meeting point to count. */
    private const val CORNER_FIT_MIN_SIN = 0.25

    /** How far a refined corner may move, as a fraction of its shorter edge. */
    private const val CORNER_FIT_REACH = 0.4

    /** More inferred corners than this means a noisy blob, not a drawn polygon. */
    private const val MAX_POLY_VERTS = 12

    /** Max distance from the stroke to its fitted cubic, as a fraction of the bbox diagonal;
     *  beyond this one cubic can't follow it (too wide an arc, or too complex) so it stays ink. */
    private const val CURVE_FIT_TOL_FRAC = 0.09

    /** Points the single fitted cubic is sampled to before its control points are picked. */
    private const val CURVE_SAMPLES = 64

    /** Most control points a recognized curve is given; more and it is a scribble, left as ink. */
    private const val SPLINE_MAX_POINTS = 7

    /** Douglas-Peucker tolerance picking a curve's control points, as a fraction of the diagonal. */
    private const val SPLINE_THIN_FRAC = 0.025

    /** Shorter side / longer side at or above this squares a recognized rectangle up. */
    private const val SQUARE_ASPECT_MIN = 0.88

    /** How far each triangle corner may sit from the upright isosceles one, as a fraction of the diagonal. */
    private const val TRIANGLE_CORNER_FRAC = 0.1

    /** Recognize from raw stroke samples (the page-local positions are what matter). */
    fun recognize(samples: List<Sample>): RecognizedShape? = recognizePoints(samples.map { it.pos })

    /** Recognize from a bare list of page-local points; drives the unit tests directly. */
    fun recognizePoints(points: List<Pt>): RecognizedShape? {
        if (points.size < MIN_POINTS) return null
        val bbox = Rect.bounding(points)
        val diag = hypot(bbox.w, bbox.h)
        if (diag < MIN_DIAGONAL) return null

        val closed = points.first().distanceTo(points.last()) <= CLOSED_GAP_FRAC * diag
        return if (closed) recognizeClosed(points, bbox, diag) else recognizeOpen(points, bbox, diag)
    }

    // --- open paths: straight line or zig-zag polyline ---

    private fun recognizeOpen(points: List<Pt>, bbox: Rect, diag: Double): RecognizedShape? {
        val tol = edgeTol(bbox, diag)
        val first = points.first()
        val last = points.last()
        // A stroke hugging its chord snaps to a straight line.
        val chord = first.distanceTo(last)
        if (chord > 1e-6) {
            val maxDev = points.maxOf { Geometry.distancePointToSegment(it, first, last) }
            if (maxDev / chord <= LINE_DEV_FRAC) {
                val ends = snapAxisAligned(listOf(first, last), closed = false)
                return RecognizedShape(ShapeKind.LINE, ends[0], ends[1])
            }
        }
        val path = resample(points, RESAMPLE_N)
        // A multi-segment zig-zag has sharp corners joined by straight runs -> polyline.
        polylineFrom(path, cornerPeaks(path, wrap = false), tol)?.let { return it }
        // The graph of a familiar function snaps to that function, fitted to the drawn box.
        FunctionFit.fit(path, bbox, diag)?.let { return RecognizedShape(ShapeKind.FUNCTION, bbox.topLeft, Pt(bbox.right, bbox.bottom), function = it) }
        // Otherwise a smooth freehand curve (a C, an S, a flowing open stroke): snap it to an
        // editable spline, or leave it as ink if a short one can't follow it.
        curveFrom(path, diag)?.let { return it }
        // Neither: retry the corner reading at tighter windows, which is what a run with a short
        // edge (a flat staircase riser, a traced long rectangle left open) needs.
        val fine = resample(points, FINE_RESAMPLE_N)
        for (k in FINE_CORNER_K) {
            polylineFrom(fine, cornerPeaks(fine, wrap = false, window = k), tol, FINE_MAX_VERTS - 2)
                ?.let { return it }
        }
        return null
    }

    /** Build the polyline through [peaks], or null when they don't join up with straight edges. */
    private fun polylineFrom(
        path: List<Pt>,
        peaks: List<Int>,
        tol: Double,
        maxCorners: Int = MAX_POLY_VERTS,
    ): RecognizedShape? {
        if (peaks.isEmpty() || peaks.size > maxCorners) return null
        val vertIdx = listOf(0) + peaks + listOf(path.size - 1)
        if (!edgesStraight(path, vertIdx, wrap = false, tol)) return null
        val kept = dropCollinear(refineCorners(path, vertIdx, wrap = false), closed = false, tol)
        val verts = snapAxisAligned(kept, closed = false)
        val box = Rect.bounding(verts)
        if (min(box.w, box.h) < 1e-9) return null // snapped flat: a straight line, not a zig-zag
        return RecognizedShape(ShapeKind.POLYLINE, box.topLeft, Pt(box.right, box.bottom), verts)
    }

    /** A spline through a few control points along the stroke: those of one fitted cubic when it
     *  follows, else the stroke's own thinned path, as long as that needs few enough points. */
    private fun curveFrom(path: List<Pt>, diag: Double): RecognizedShape? {
        val tol = CURVE_FIT_TOL_FRAC * diag
        val source = CubicFit.fitSampled(path, tol, CURVE_SAMPLES)?.takeIf { it.size >= 3 } ?: path
        var control = thin(source, SPLINE_THIN_FRAC * diag)
        if (control.size > SPLINE_MAX_POINTS) return null
        if (control.size < 3) control = listOf(source.first(), source[source.size / 2], source.last())
        if (source === path && path.maxOf { p -> distanceToPolyline(p, control) } > tol) return null
        val box = Rect.bounding(control)
        return RecognizedShape(ShapeKind.SPLINE, box.topLeft, Pt(box.right, box.bottom), control)
    }

    /** Douglas-Peucker: keep the fewest points of [pts] whose polyline stays within [eps] of it. */
    private fun thin(pts: List<Pt>, eps: Double): List<Pt> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var far = -1
            var farD = eps
            for (i in a + 1 until b) {
                val d = Geometry.distancePointToSegment(pts[i], pts[a], pts[b])
                if (d > farD) {
                    farD = d
                    far = i
                }
            }
            if (far >= 0) {
                keep[far] = true
                stack.addLast(a to far)
                stack.addLast(far to b)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToPolyline(p: Pt, line: List<Pt>): Double =
        (0 until line.size - 1).minOf { Geometry.distancePointToSegment(p, line[it], line[it + 1]) }

    // --- closed paths: polygon (incl. rectangle) or ellipse/circle ---

    private fun recognizeClosed(points: List<Pt>, bbox: Rect, diag: Double): RecognizedShape? {
        // Resample evenly around the loop (including the closing segment) so the corner/roundness
        // tests are insensitive to where the stroke started and to uneven sampling speed.
        val loop = resampleClosed(points, RESAMPLE_N)

        // Sharp, straight-edged corners win: that is what tells a hexagon from a circle.
        polygonFrom(loop, cornerPeaks(loop, wrap = true), bbox, diag)?.let { return it }

        // No clean corners: a round outline snaps to an ellipse (a circle when nearly round).
        if (ellipseResidual(loop, bbox) <= ELLIPSE_RESIDUAL_FRAC) return ellipseOrCircle(bbox)

        // Neither corners nor a round fit, so the corner window may have swallowed a short edge:
        // retry on a denser loop with tighter windows. This is what recovers a long thin rectangle,
        // whose two ends are closer together than one default window is wide.
        val fine = resampleClosed(points, FINE_RESAMPLE_N)
        for (k in FINE_CORNER_K) {
            polygonFrom(fine, cornerPeaks(fine, wrap = true, window = k), bbox, diag, FINE_MAX_VERTS)
                ?.let { return it }
        }
        return null
    }

    /** Build the closed shape through [peaks] (upright box -> rectangle), or null when they don't
     *  form 3..[maxCorners] corners joined by straight edges. */
    private fun polygonFrom(
        loop: List<Pt>,
        peaks: List<Int>,
        bbox: Rect,
        diag: Double,
        maxCorners: Int = MAX_POLY_VERTS,
    ): RecognizedShape? {
        if (peaks.size !in 3..maxCorners) return null
        val tol = edgeTol(bbox, diag)
        if (!edgesStraight(loop, peaks, wrap = true, tol)) return null
        val verts = dropCollinear(refineCorners(loop, peaks, wrap = true), closed = true, tol)
        if (verts.size < 3) return null
        if (verts.size == 4 && isAxisAlignedRect(verts, bbox, diag)) return rectangleOrSquare(bbox)
        if (verts.size == 3 && isUprightTriangle(verts, bbox, diag)) {
            return RecognizedShape(ShapeKind.TRIANGLE, bbox.topLeft, Pt(bbox.right, bbox.bottom))
        }
        // A 4-gon that the stroke agrees is a box becomes an exact one, squared on its long edges.
        val squared = if (verts.size == 4) rectangleFit(loop, verts, tol) else null
        val snapped = snapAxisAligned(squared ?: verts, closed = true)
        val box = Rect.bounding(snapped)
        if (min(box.w, box.h) < 1e-9) return null // snapped flat: the corners were never a shape
        // Every edge straightened flat: the 4-gon is a rectangle, so hand it over as one.
        if (snapped.size == 4 && isUprightQuad(snapped)) return rectangleOrSquare(box)
        return RecognizedShape(ShapeKind.POLYGON, box.topLeft, Pt(box.right, box.bottom), snapped)
    }

    /** An upright box, squared up about its centre when its sides nearly match. */
    private fun rectangleOrSquare(box: Rect): RecognizedShape {
        val aspect = if (max(box.w, box.h) > 1e-9) min(box.w, box.h) / max(box.w, box.h) else 1.0
        if (aspect < SQUARE_ASPECT_MIN) return RecognizedShape(ShapeKind.RECTANGLE, box.topLeft, Pt(box.right, box.bottom))
        val half = (box.w + box.h) / 4.0
        return RecognizedShape(
            ShapeKind.RECTANGLE,
            Pt(box.centerX - half, box.centerY - half),
            Pt(box.centerX + half, box.centerY + half),
        )
    }

    /** Three corners near the apex-up isosceles triangle of the box (apex mid-top, base along the bottom). */
    private fun isUprightTriangle(verts: List<Pt>, bbox: Rect, diag: Double): Boolean {
        val ideal = listOf(Pt(bbox.centerX, bbox.top), Pt(bbox.left, bbox.bottom), Pt(bbox.right, bbox.bottom))
        val tol = TRIANGLE_CORNER_FRAC * diag
        return ideal.all { c -> verts.any { it.distanceTo(c) <= tol } }
    }

    /** Near-round ellipse -> a true circle (centred square box); a clear oval stays an ellipse. */
    private fun ellipseOrCircle(bbox: Rect): RecognizedShape {
        val longAxis = max(bbox.w, bbox.h)
        val shortAxis = min(bbox.w, bbox.h)
        val aspect = if (longAxis > 1e-9) shortAxis / longAxis else 1.0
        if (aspect < CIRCLE_ASPECT_MIN) {
            return RecognizedShape(ShapeKind.ELLIPSE, bbox.topLeft, Pt(bbox.right, bbox.bottom))
        }
        val r = (bbox.w + bbox.h) / 4.0
        val cx = bbox.centerX
        val cy = bbox.centerY
        return RecognizedShape(ShapeKind.CIRCLE, Pt(cx - r, cy - r), Pt(cx + r, cy + r))
    }

    /**
     * Sharpen each corner to where its two edges actually meet. A pen rounds its corners, so the
     * turn peaks somewhere inside the bend, short of the real corner; fitting a line through each
     * edge's straight middle and intersecting the two puts the vertex back out where it belongs.
     * A corner keeps its sampled position when the fit is degenerate, the two edges run near
     * parallel, or the intersection lands implausibly far away.
     */
    private fun refineCorners(path: List<Pt>, vertIdx: List<Int>, wrap: Boolean): List<Pt> {
        val m = vertIdx.size
        val raw = vertIdx.map { path[it] }
        if (m < 3) return raw
        val edges = if (wrap) m else m - 1
        val lines = arrayOfNulls<Pair<Pt, Pt>>(edges) // (a point on the line, its unit direction)
        for (e in 0 until edges) lines[e] = fitEdgeLine(path, vertIdx[e], vertIdx[(e + 1) % m], wrap)
        return List(m) { i ->
            val inEdge = if (wrap) (i - 1 + m) % m else i - 1
            if (inEdge < 0 || i >= edges) return@List raw[i] // an open path's two ends
            val a = lines[inEdge] ?: return@List raw[i]
            val b = lines[i] ?: return@List raw[i]
            val cross = a.second.x * b.second.y - a.second.y * b.second.x
            if (abs(cross) < CORNER_FIT_MIN_SIN) return@List raw[i]
            val wx = b.first.x - a.first.x
            val wy = b.first.y - a.first.y
            val t = (wx * b.second.y - wy * b.second.x) / cross
            val hit = Pt(a.first.x + t * a.second.x, a.first.y + t * a.second.y)
            val reach = CORNER_FIT_REACH * min(
                raw[i].distanceTo(raw[(i - 1 + m) % m]),
                raw[i].distanceTo(raw[(i + 1) % m]),
            )
            if (hit.distanceTo(raw[i]) > reach) raw[i] else hit
        }
    }

    /** Total-least-squares line through the straight middle of the edge from [i0] to [i1]. */
    private fun fitEdgeLine(path: List<Pt>, i0: Int, i1: Int, wrap: Boolean): Pair<Pt, Pt>? {
        val n = path.size
        val span = if (wrap) (i1 - i0 + n) % n else i1 - i0
        if (span < 3) return null
        val trim = (span * CORNER_FIT_TRIM).toInt().coerceAtLeast(1)
        val from = trim
        val to = span - trim
        if (to - from < 1) return null
        var sx = 0.0
        var sy = 0.0
        val count = to - from + 1
        for (s in from..to) {
            val p = path[(i0 + s) % n]
            sx += p.x
            sy += p.y
        }
        val cx = sx / count
        val cy = sy / count
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (s in from..to) {
            val p = path[(i0 + s) % n]
            val dx = p.x - cx
            val dy = p.y - cy
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        if (xx + yy < 1e-12) return null
        val theta = 0.5 * atan2(2.0 * xy, xx - yy)
        return Pt(cx, cy) to Pt(cos(theta), sin(theta))
    }

    /**
     * Drop vertices that sit within [tol] of the line through their neighbours. A tight corner
     * window can plant two corners on one long edge, and the spare would turn a box into a 5-gon.
     * Open paths keep their two ends.
     */
    private fun dropCollinear(verts: List<Pt>, closed: Boolean, tol: Double): List<Pt> {
        val floor = if (closed) 3 else 2
        val out = ArrayList(verts)
        var i = if (closed) 0 else 1
        while (out.size > floor && i < out.size - (if (closed) 0 else 1)) {
            val prev = out[(i - 1 + out.size) % out.size]
            val next = out[(i + 1) % out.size]
            if (Geometry.distancePointToSegment(out[i], prev, next) <= tol) out.removeAt(i) else i++
        }
        return out
    }

    /**
     * Square a 4-gon onto the exact rectangle its longer pair of edges implies, or null when the
     * stroke disagrees (some [loop] point strays more than [tol] from that rectangle's outline).
     * The long edges carry many samples and so give a trustworthy orientation; the short ends of a
     * thin box carry almost none, which is why measuring them directly reads as a tilt they lack.
     */
    private fun rectangleFit(loop: List<Pt>, verts: List<Pt>, tol: Double): List<Pt>? {
        val edges = List(4) { verts[(it + 1) % 4] - verts[it] }
        val evenPair = edges[0].length() + edges[2].length() >= edges[1].length() + edges[3].length()
        val a = if (evenPair) edges[0] else edges[1]
        val b = if (evenPair) edges[2] else edges[3] // the opposite edge, which runs the other way
        val dx = a.x - b.x
        val dy = a.y - b.y
        val len = hypot(dx, dy)
        if (len < 1e-9) return null
        val ux = dx / len
        val uy = dy / len
        var minU = Double.MAX_VALUE
        var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (p in loop) {
            val pu = p.x * ux + p.y * uy
            val pv = -p.x * uy + p.y * ux
            minU = min(minU, pu)
            maxU = max(maxU, pu)
            minV = min(minV, pv)
            maxV = max(maxV, pv)
        }
        for (p in loop) {
            val pu = p.x * ux + p.y * uy
            val pv = -p.x * uy + p.y * ux
            val toOutline = min(min(pu - minU, maxU - pu), min(pv - minV, maxV - pv))
            if (toOutline > tol) return null
        }
        return listOf(
            Pt(minU, minV), Pt(maxU, minV), Pt(maxU, maxV), Pt(minU, maxV),
        ).map { Pt(it.x * ux - it.y * uy, it.x * uy + it.y * ux) }
    }

    /** True if the four [verts] alternate exactly horizontal and vertical edges (a snapped box). */
    private fun isUprightQuad(verts: List<Pt>): Boolean {
        for (i in verts.indices) {
            val a = verts[i]
            val b = verts[(i + 1) % verts.size]
            val horiz = abs(a.y - b.y) < 1e-9 && abs(a.x - b.x) > 1e-9
            val vert = abs(a.x - b.x) < 1e-9 && abs(a.y - b.y) > 1e-9
            if (!horiz && !vert) return false
        }
        // Two of each is the only way four axis-aligned edges close up, so it is a box.
        return verts.indices.count { abs(verts[it].y - verts[(it + 1) % verts.size].y) < 1e-9 } == 2
    }

    /** True if the four [verts] each sit near a distinct corner of [bbox] (an upright rectangle). */
    private fun isAxisAlignedRect(verts: List<Pt>, bbox: Rect, diag: Double): Boolean {
        val corners = listOf(
            Pt(bbox.left, bbox.top), Pt(bbox.right, bbox.top),
            Pt(bbox.right, bbox.bottom), Pt(bbox.left, bbox.bottom),
        )
        val tol = min(RECT_CORNER_FRAC * diag, RECT_CORNER_SHORT_FRAC * min(bbox.w, bbox.h))
        val used = BooleanArray(corners.size)
        for (v in verts) {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (c in corners.indices) {
                if (used[c]) continue
                val d = v.distanceTo(corners[c])
                if (d < bestD) {
                    bestD = d
                    best = c
                }
            }
            if (best < 0 || bestD > tol) return false
            used[best] = true
        }
        return true
    }

    /**
     * Indices of the corner peaks along [path]. The turn at each point is the angle between the
     * chord coming in and the chord going out (over a [k]-sample window so sampling noise doesn't
     * fake corners); a run of points clearing [CORNER_ANGLE_RAD] is one corner, reported at its
     * sharpest point. A closed loop ([wrap]) starts the scan at its straightest point so no corner
     * straddles the seam; an open path never treats its two ends as corners.
     *
     * A point only opens or extends a run when it also turns sharply over a tight window, so two
     * corners a short edge apart stay two corners instead of merging into one: the wide window
     * still reads the whole short edge as turning, but its middle is not sharp.
     */
    private fun cornerPeaks(path: List<Pt>, wrap: Boolean, window: Int = 0): List<Int> {
        val n = path.size
        if (n < 8) return emptyList()
        val k = if (window > 0) window else (n / 12).coerceAtLeast(2)
        val turns = DoubleArray(n) { i ->
            if (!wrap && (i - k < 0 || i + k >= n)) {
                0.0
            } else {
                val prev = path[(i - k + n) % n]
                val cur = path[i]
                val next = path[(i + k) % n]
                angleBetween(cur - prev, next - cur)
            }
        }
        val corner = BooleanArray(n) { turns[it] >= CORNER_ANGLE_RAD && isSharpCorner(path, it, wrap) }
        val startAt = if (wrap) {
            var minIdx = 0
            for (i in 1 until n) if (turns[i] < turns[minIdx]) minIdx = i
            minIdx
        } else {
            0
        }
        val peaks = ArrayList<Int>()
        var i = 0
        while (i < n) {
            val gi = (startAt + i) % n
            if (corner[gi]) {
                var bestIdx = gi
                var bestTurn = turns[gi]
                i++
                while (i < n) {
                    val gj = (startAt + i) % n
                    if (!corner[gj]) break
                    if (turns[gj] > bestTurn) {
                        bestTurn = turns[gj]
                        bestIdx = gj
                    }
                    i++
                }
                peaks.add(bestIdx)
            } else {
                i++
            }
        }
        peaks.sort()
        return peaks
    }

    /** True if the turn at [i] is sharp over a tight window (a real corner, not a flowing bend). */
    private fun isSharpCorner(path: List<Pt>, i: Int, wrap: Boolean): Boolean {
        val n = path.size
        val kt = 2
        if (!wrap && (i - kt < 0 || i + kt >= n)) return true // at an open end; endpoints handled elsewhere
        val prev = path[(i - kt + n) % n]
        val cur = path[i]
        val next = path[(i + kt) % n]
        return angleBetween(cur - prev, next - cur) >= SHARP_CORNER_RAD
    }

    /** How far an edge may bow and still count as straight (content px). */
    private fun edgeTol(bbox: Rect, diag: Double): Double =
        min(EDGE_DEV_FRAC * diag, EDGE_DEV_SHORT_FRAC * min(bbox.w, bbox.h))

    /** True if every edge between consecutive vertices stays within [tol] of straight. */
    private fun edgesStraight(path: List<Pt>, vertIdx: List<Int>, wrap: Boolean, tol: Double): Boolean {
        val n = path.size
        val m = vertIdx.size
        if (m < 2) return false
        val edges = if (wrap) m else m - 1
        for (e in 0 until edges) {
            val i0 = vertIdx[e]
            val i1 = vertIdx[(e + 1) % m]
            val a = path[i0]
            val b = path[i1]
            var j = i0
            while (j != i1) {
                if (Geometry.distancePointToSegment(path[j], a, b) > tol) return false
                j = (j + 1) % n
                if (!wrap && j == 0) break // an open path never wraps past its end
            }
        }
        return true
    }

    /**
     * Straighten every edge within [AXIS_SNAP_RAD] of horizontal or vertical, sharing the move
     * across the corners each edge owns so the path stays connected (a closed polygon stays
     * closed). Each edge is classified once; then the two axes are solved independently by
     * union-find: vertices tied by horizontal edges take their group's mean y, those tied by
     * vertical edges their mean x, so a snapped corner only ever moves along the axis being
     * straightened.
     */
    private fun snapAxisAligned(verts: List<Pt>, closed: Boolean): List<Pt> {
        val m = verts.size
        if (m < 2) return verts
        val xGroup = IntArray(m) { it }
        val yGroup = IntArray(m) { it }
        val edges = if (closed) m else m - 1
        for (e in 0 until edges) {
            val a = verts[e]
            val b = verts[(e + 1) % m]
            val dx = b.x - a.x
            val dy = b.y - a.y
            if (hypot(dx, dy) < 1e-9) continue
            val fromHoriz = atan2(abs(dy), abs(dx)) // 0 = horizontal, PI/2 = vertical
            when {
                fromHoriz <= AXIS_SNAP_RAD -> ufUnion(yGroup, e, (e + 1) % m)
                fromHoriz >= PI / 2.0 - AXIS_SNAP_RAD -> ufUnion(xGroup, e, (e + 1) % m)
            }
        }
        val xMean = groupMeans(xGroup, DoubleArray(m) { verts[it].x })
        val yMean = groupMeans(yGroup, DoubleArray(m) { verts[it].y })
        return List(m) { Pt(xMean[it], yMean[it]) }
    }

    private fun ufFind(group: IntArray, i: Int): Int {
        var root = i
        while (group[root] != root) root = group[root]
        var cur = i
        while (group[cur] != cur) {
            val next = group[cur]
            group[cur] = root
            cur = next
        }
        return root
    }

    private fun ufUnion(group: IntArray, a: Int, b: Int) {
        group[ufFind(group, a)] = ufFind(group, b)
    }

    /** Replace each coordinate with the mean of its union-find group (so a group lands on one line). */
    private fun groupMeans(group: IntArray, coords: DoubleArray): DoubleArray {
        val sum = HashMap<Int, Double>()
        val count = HashMap<Int, Int>()
        for (i in coords.indices) {
            val root = ufFind(group, i)
            sum[root] = (sum[root] ?: 0.0) + coords[i]
            count[root] = (count[root] ?: 0) + 1
        }
        return DoubleArray(coords.size) { val root = ufFind(group, it); sum[root]!! / count[root]!! }
    }

    /** RMS of `sqrt((x/rx)^2 + (y/ry)^2) - 1` about the bbox centre: 0 on a perfect inscribed ellipse. */
    private fun ellipseResidual(loop: List<Pt>, bbox: Rect): Double {
        val cx = bbox.center.x
        val cy = bbox.center.y
        val rx = (bbox.w / 2.0).coerceAtLeast(1e-6)
        val ry = (bbox.h / 2.0).coerceAtLeast(1e-6)
        var sumSq = 0.0
        for (p in loop) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            val d = sqrt(nx * nx + ny * ny) - 1.0
            sumSq += d * d
        }
        return sqrt(sumSq / loop.size)
    }

    /** Angle in radians between two vectors; 0 when either is degenerate. */
    private fun angleBetween(u: Pt, v: Pt): Double {
        val lu = u.length()
        val lv = v.length()
        if (lu < 1e-9 || lv < 1e-9) return 0.0
        val cos = ((u.x * v.x + u.y * v.y) / (lu * lv)).coerceIn(-1.0, 1.0)
        return acos(cos)
    }

    /** Resample [points] to [n] points spaced evenly around the closed loop (last joins first). */
    private fun resampleClosed(points: List<Pt>, n: Int): List<Pt> =
        resample(points + points.first(), n + 1).let { if (it.size > n) it.subList(0, n) else it }

    /** Classic arc-length resampling to exactly [n] evenly-spaced points (spec-style). */
    private fun resample(points: List<Pt>, n: Int): List<Pt> {
        if (points.size <= 1 || n <= 1) return points
        val total = pathLength(points)
        if (total < 1e-9) return List(n) { points.first() }
        val step = total / (n - 1)
        val out = ArrayList<Pt>(n)
        out.add(points.first())
        var prev = points.first()
        var accum = 0.0
        var i = 1
        while (i < points.size && out.size < n) {
            val curr = points[i]
            val seg = prev.distanceTo(curr)
            if (seg < 1e-12) {
                i++
                continue
            }
            if (accum + seg >= step) {
                val t = (step - accum) / seg
                val np = Pt(prev.x + t * (curr.x - prev.x), prev.y + t * (curr.y - prev.y))
                out.add(np)
                prev = np
                accum = 0.0
            } else {
                accum += seg
                prev = curr
                i++
            }
        }
        while (out.size < n) out.add(points.last())
        return out
    }

    private fun pathLength(points: List<Pt>): Double {
        var len = 0.0
        for (i in 1 until points.size) len += points[i - 1].distanceTo(points[i])
        return len
    }
}
