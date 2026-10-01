package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.tools.ShapeKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An editable geometric shape (spec 02 §5.4): open (line/arrow, two endpoints)
 * or closed (rectangle/ellipse/triangle, drawn inside the normalized AABB of
 * start/end). Polygon/polyline additionally carry a vertex list in [points].
 * Closed shapes are stroked and optionally filled. Shapes erase like ink: whole
 * on contact in STROKE mode, and cut into outline fragments by the AREA eraser.
 */
class ShapeItem(
    var shape: ShapeKind,
    var start: Pt,
    var end: Pt,
    var strokeRgba: Rgba,
    var strokeWidth: Double = 3.0,
    var fillRgba: Rgba? = null,
    /** Render the outline as a glowing neon tube (halo + white-hot core). */
    var neon: Boolean = false,
    /** Glow intensity in [0, 1] (halo size + brightness); used only when [neon]. */
    var neonStrength: Double = 0.6,
    /**
     * Vertices for [ShapeKind.POLYGON]/[ShapeKind.POLYLINE], stored normalized to the
     * unit box so they scale with [start]/[end] for free; null for every other kind.
     */
    var points: List<Pt>? = null,
    /** Stroke the outline dashed (dash/gap run lengths in content px). */
    var dashed: Boolean = false,
    var dashLength: Double = 10.0,
    var dashGap: Double = 8.0,
    /** Clockwise turn in radians about the box centre; only [ShapeKind.COORD_AXES] uses it. */
    var angle: Double = 0.0,
    /** What a [ShapeKind.FUNCTION] plots; its sampled curve lives in [points]. */
    var function: FunctionSpec? = null,
) : CanvasItem, Resizable {

    init {
        if (shape == ShapeKind.FUNCTION && points == null) points = function?.normalizedSamples()
    }

    override val kind = KIND
    override val resizable = true
    override var locked = false
    override var link: String? = null

    /** Normalized AABB of the two drag points. */
    val box: Rect get() = Rect.fromPoints(start, end)

    /** Polygon/polyline vertices mapped from normalized storage into content space. */
    internal fun absPoints(): List<Pt> {
        val pts = points ?: return emptyList()
        val b = box
        return pts.map { Pt(b.left + it.x * b.w, b.top + it.y * b.h) }
    }

    /** The open vertex-list outline as separate runs: a plotted function breaks at its poles. */
    internal fun runs(): List<List<Pt>> = FunctionSpec.runs(absPoints())

    /** Absolute (content-space) vertices for polygon/polyline kinds; null for the rest. */
    fun vertices(): List<Pt>? = if (points == null) null else absPoints()

    private fun pen() =
        Pen(color = strokeRgba, width = strokeWidth, cosmetic = false, dashed = dashed, dashOn = dashLength, dashGap = dashGap)

    /** Triangle vertices: apex at top-edge midpoint, base along the bottom edge. */
    internal fun triangleVertices(): List<Pt> {
        val b = box
        return listOf(Pt(b.centerX, b.top), Pt(b.left, b.bottom), Pt(b.right, b.bottom))
    }

    /** Open ">" arrowhead as the chevron polyline barbLeft -> tip -> barbRight (stroked, not filled),
     *  sized from the stroke width. The tip sits just past [end] so the point clears the shaft's
     *  round cap and reads slightly forward instead of buried under the line end. */
    internal fun arrowHead(): List<Pt> {
        val dir = (end - start).normalized()
        if (dir.length() < 1e-9) return emptyList()
        val headLen = max(12.0, strokeWidth * 3.5)
        val tip = end + dir * (strokeWidth * 0.5)
        val back = tip - dir * headLen
        val perp = dir.perp() * (headLen * 0.5)
        return listOf(back + perp, tip, back - perp)
    }

    /**
     * X-Y coordinate axes as a set of disjoint polylines: the two axes crossing at the box centre,
     * an arrowhead on the positive (right/top) end of each, and evenly spaced tick marks. Each entry
     * is one stroked run, turned by [angle].
     */
    internal fun axesSegments(): List<List<Pt>> {
        val upright = uprightAxesSegments()
        if (angle == 0.0) return upright
        val turn = Affine.rotateAbout(box.center, angle)
        return upright.map { seg -> seg.map { turn.apply(it) } }
    }

    private fun uprightAxesSegments(): List<List<Pt>> {
        val b = box
        val cx = b.centerX
        val cy = b.centerY
        val (head, tick) = markSizes(min(b.w, b.h))
        val segs = ArrayList<List<Pt>>()
        segs.add(listOf(Pt(b.left, cy), Pt(b.right, cy)))            // X axis
        segs.add(listOf(Pt(cx, b.bottom), Pt(cx, b.top)))           // Y axis
        segs.add(listOf(Pt(b.right - head, cy - head * 0.5), Pt(b.right, cy), Pt(b.right - head, cy + head * 0.5))) // +x head
        segs.add(listOf(Pt(cx - head * 0.5, b.top + head), Pt(cx, b.top), Pt(cx + head * 0.5, b.top + head)))      // +y head
        val n = 4
        for (i in 1..n) {
            val f = i.toDouble() / (n + 1)
            val xr = cx + (b.right - cx) * f
            val xl = cx - (cx - b.left) * f
            segs.add(listOf(Pt(xr, cy - tick), Pt(xr, cy + tick)))
            segs.add(listOf(Pt(xl, cy - tick), Pt(xl, cy + tick)))
            val yt = cy - (cy - b.top) * f
            val yb = cy + (b.bottom - cy) * f
            segs.add(listOf(Pt(cx - tick, yt), Pt(cx + tick, yt)))
            segs.add(listOf(Pt(cx - tick, yb), Pt(cx + tick, yb)))
        }
        return segs
    }

    /** Arrowhead length and tick half-length for the axes and the number line, scaled to [extent]. */
    private fun markSizes(extent: Double): Pair<Double, Double> {
        val head = max(5.0, min(strokeWidth * 1.7, extent * 0.08))
        val tick = max(strokeWidth * 0.8, min(head * 0.45, extent * 0.025))
        return head to tick
    }

    /** A number line from [start] to [end]: the axis, an arrowhead at [end] and evenly spaced ticks. */
    internal fun numberLineSegments(): List<List<Pt>> {
        val len = start.distanceTo(end)
        if (len < 1e-9) return emptyList()
        val dir = (end - start).normalized()
        val perp = dir.perp()
        val (head, tick) = markSizes(len)
        val back = end - dir * head
        val segs = ArrayList<List<Pt>>()
        segs.add(listOf(start, end))
        segs.add(listOf(back + perp * (head * 0.5), end, back - perp * (head * 0.5)))
        val n = 10
        for (i in 1 until n) {
            val at = start + dir * (len * i / n)
            segs.add(listOf(at + perp * tick, at - perp * tick))
        }
        return segs
    }

    /** A spline's control points in content space: the stored ones, or both ends and their midpoint. */
    fun controlPoints(): List<Pt> =
        if (points != null) absPoints() else listOf(start, Pt((start.x + end.x) / 2.0, (start.y + end.y) / 2.0), end)

    /** Replace a spline's control points, refitting the box around them. */
    fun setControlPoints(pts: List<Pt>) {
        val b = Rect.bounding(pts)
        start = b.topLeft
        end = Pt(b.right, b.bottom)
        points = normalize(pts, b)
    }

    fun moveControlPoint(index: Int, to: Pt) {
        val c = controlPoints().toMutableList()
        if (index !in c.indices) return
        c[index] = to
        setControlPoints(c)
    }

    /**
     * Add a control point at [at], slotted in after the span the curve passes closest to. Past
     * either end of the curve it extends that end instead.
     */
    fun addControlPointAt(at: Pt) {
        val c = controlPoints()
        if (c.size < 2) return
        val last = c.size - 2
        var index = 1
        var bestD = Double.MAX_VALUE
        var bestOvershoot = Double.MAX_VALUE
        for (i in 0..last) {
            var a = c[i]
            for (s in 1..SPLINE_SAMPLES) {
                val b = catmullRom(c, i, s.toDouble() / SPLINE_SAMPLES)
                val ab = b - a
                val len2 = Geometry.dot(ab, ab)
                val u = if (len2 < 1e-12) 0.0 else Geometry.dot(at - a, ab) / len2
                val d = (a + ab * u.coerceIn(0.0, 1.0)).distanceTo(at)
                // At a joint both neighbouring segments are equally close; the one the tap overshoots less wins.
                val overshoot = max(-u, u - 1.0) * sqrt(len2)
                if (d < bestD - 1e-9 || (d <= bestD + 1e-9 && overshoot < bestOvershoot)) {
                    bestD = d
                    bestOvershoot = overshoot
                    index = when {
                        i == 0 && s == 1 && u < 0.0 -> 0
                        i == last && s == SPLINE_SAMPLES && u > 1.0 -> c.size
                        else -> i + 1
                    }
                }
                a = b
            }
        }
        setControlPoints(c.take(index) + at + c.drop(index))
    }

    /** Drop control point [index]; a spline keeps at least three. */
    fun removeControlPointAt(index: Int): Boolean {
        val c = controlPoints()
        if (c.size <= 3 || index !in c.indices) return false
        setControlPoints(c.filterIndexed { k, _ -> k != index })
        return true
    }

    /** The smooth curve through every control point (uniform Catmull-Rom), sampled as a polyline. */
    internal fun splinePath(): List<Pt> {
        val c = controlPoints()
        if (c.size < 3) return c
        val out = ArrayList<Pt>((c.size - 1) * SPLINE_SAMPLES + 1)
        for (i in 0 until c.size - 1) {
            for (s in 0 until SPLINE_SAMPLES) out.add(catmullRom(c, i, s.toDouble() / SPLINE_SAMPLES))
        }
        out.add(c.last())
        return out
    }

    private fun catmullRom(c: List<Pt>, i: Int, t: Double): Pt {
        val p0 = c[max(i - 1, 0)]
        val p1 = c[i]
        val p2 = c[i + 1]
        val p3 = c[min(i + 2, c.size - 1)]
        val t2 = t * t
        val t3 = t2 * t
        fun f(a: Double, b: Double, cc: Double, d: Double) =
            0.5 * (2 * b + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - d) * t2 + (-a + 3 * b - 3 * cc + d) * t3)
        return Pt(f(p0.x, p1.x, p2.x, p3.x), f(p0.y, p1.y, p2.y, p3.y))
    }

    internal fun ellipsePolygon(segments: Int = 48): List<Pt> {
        val b = box
        val cx = b.centerX
        val cy = b.centerY
        val rx = b.w / 2.0
        val ry = b.h / 2.0
        return (0 until segments).map {
            val a = 2.0 * PI * it / segments
            Pt(cx + rx * cos(a), cy + ry * sin(a))
        }
    }

    override fun paint(r: Renderer) {
        if (neon) return paintNeon(r)
        fillRgba?.let { drawFill(r, it) }
        drawOutline(r, pen())
        if (shape == ShapeKind.ARROW) drawArrowHead(r, pen())
    }

    /** Fill the closed-shape interior (no-op for open line/arrow). */
    private fun drawFill(r: Renderer, fill: Rgba) {
        val b = box
        when (shape) {
            ShapeKind.RECTANGLE -> r.fillRect(b, fill)
            ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> r.fillEllipse(b.center, b.w / 2.0, b.h / 2.0, fill)
            ShapeKind.TRIANGLE -> r.fillPolygon(triangleVertices(), fill)
            ShapeKind.POLYGON -> r.fillPolygon(absPoints(), fill)
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.COORD_AXES, ShapeKind.POLYLINE, ShapeKind.CURVE, ShapeKind.FUNCTION,
            ShapeKind.NUMBER_LINE, ShapeKind.SPLINE -> {}
        }
    }

    /** Stroke the shape's outline (the arrow's shaft for arrows) with [pen]. */
    private fun drawOutline(r: Renderer, pen: Pen) {
        val b = box
        when (shape) {
            ShapeKind.LINE, ShapeKind.ARROW -> r.strokePolyline(listOf(start, end), pen)
            ShapeKind.RECTANGLE -> r.strokeRect(b, pen)
            ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> r.strokeEllipse(b.center, b.w / 2.0, b.h / 2.0, pen)
            ShapeKind.TRIANGLE -> r.strokePolygon(triangleVertices(), pen)
            ShapeKind.POLYGON -> r.strokePolygon(absPoints(), pen)
            ShapeKind.COORD_AXES -> axesSegments().forEach { r.strokePolyline(it, pen) }
            ShapeKind.POLYLINE, ShapeKind.CURVE, ShapeKind.FUNCTION -> runs().forEach { r.strokePolyline(it, pen) }
            ShapeKind.NUMBER_LINE -> numberLineSegments().forEach { r.strokePolyline(it, pen) }
            ShapeKind.SPLINE -> r.strokePolyline(splinePath(), pen)
        }
    }

    /** Stroke the open ">" arrowhead with [pen], mirroring the shaft so the head and shaft share one tube.
     *  The chevron is always solid: a dash break on the short barbs would maim the point. */
    private fun drawArrowHead(r: Renderer, pen: Pen) {
        val head = arrowHead()
        if (head.size == 3) r.strokePolyline(head, pen.copy(dashed = false))
    }

    /**
     * Neon: the outline as a glowing glass tube — a blurred ink-colour **halo**
     * (composited once at a glow-intensity alpha so corners don't blow out), the
     * opaque colour **body**, then a thinner **white-hot core** down the centre so
     * the colour reads at the tube's edges. [neonStrength] scales the halo only.
     */
    internal fun neonGlowRadius(): Double {
        val s = neonStrength.coerceIn(0.0, 1.0)
        return (strokeWidth * (GLOW_FACTOR_MIN + GLOW_FACTOR_SPAN * s)).coerceAtLeast(GLOW_MIN)
    }

    override fun paintBounds(): Rect =
        if (neon) bounds().outset(neonGlowRadius() * 2.0 + 4.0)
        else bounds()

    private fun paintNeon(r: Renderer) {
        val glowR = neonGlowRadius()
        val s = neonStrength.coerceIn(0.0, 1.0)
        val color = strokeRgba.withAlpha(255)
        val white = Rgba(255, 255, 255, 255)
        val glowAlpha = GLOW_ALPHA_MIN + GLOW_ALPHA_SPAN * s
        val coreW = (strokeWidth * CORE_WIDTH_FRAC).coerceAtLeast(CORE_WIDTH_MIN)

        fillRgba?.let { drawFill(r, it) }

        // 1) Outer halo (ink colour), bounded in its own glow-alpha layer.
        r.saveLayerAlpha(paintBounds(), glowAlpha)
        val haloPen = pen().copy(color = color, glowRadius = glowR)
        drawOutline(r, haloPen)
        if (shape == ShapeKind.ARROW) drawArrowHead(r, haloPen)
        r.restore()

        // 2) Tube body — saturated colour shows at the rim.
        drawOutline(r, pen())
        if (shape == ShapeKind.ARROW) drawArrowHead(r, pen())

        // 3) White-hot core — a thinner white line down the centre of the shaft and the chevron.
        val corePen = pen().copy(color = white, width = coreW)
        drawOutline(r, corePen)
        if (shape == ShapeKind.ARROW) drawArrowHead(r, corePen)
    }

    override fun bounds(): Rect {
        val pad = strokeWidth / 2.0 + 1.0
        return when (shape) {
            ShapeKind.LINE -> Rect.fromPoints(start, end).outset(pad)
            ShapeKind.ARROW -> Rect.bounding(listOf(start, end) + arrowHead()).outset(pad)
            ShapeKind.NUMBER_LINE -> Rect.bounding(numberLineSegments().flatten().ifEmpty { listOf(start, end) }).outset(pad)
            ShapeKind.SPLINE -> Rect.bounding(splinePath()).outset(pad)
            ShapeKind.COORD_AXES -> Rect.bounding(axesSegments().flatten()).outset(pad)
            else -> box.outset(pad)
        }
    }

    override fun translate(dx: Double, dy: Double) {
        start = Pt(start.x + dx, start.y + dy)
        end = Pt(end.x + dx, end.y + dy)
    }

    override fun contains(p: Pt): Boolean {
        val tol = max(strokeWidth / 2.0, HIT_TOLERANCE)
        return when (shape) {
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.NUMBER_LINE -> Geometry.distancePointToSegment(p, start, end) <= tol
            ShapeKind.RECTANGLE -> if (fillRgba != null) box.contains(p) else nearRectOutline(p, tol)
            ShapeKind.TRIANGLE -> {
                val v = triangleVertices()
                if (fillRgba != null) Geometry.pointInPolygon(v, p) else nearPolyOutline(v, p, tol)
            }
            ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> {
                val poly = ellipsePolygon()
                if (fillRgba != null) Geometry.pointInPolygon(poly, p) else nearPolyOutline(poly, p, tol)
            }
            ShapeKind.POLYGON -> {
                val v = absPoints()
                if (fillRgba != null) Geometry.pointInPolygon(v, p) else nearPolyOutline(v, p, tol)
            }
            ShapeKind.COORD_AXES -> nearAxes(p, tol)
            ShapeKind.POLYLINE, ShapeKind.CURVE, ShapeKind.FUNCTION -> runs().any { nearPolyOutline(it, p, tol, closed = false) }
            ShapeKind.SPLINE -> nearPolyOutline(splinePath(), p, tol, closed = false)
        }
    }

    /** Near either main axis line (the ticks/heads are cosmetic; the axes carry the hit). */
    private fun nearAxes(p: Pt, tol: Double): Boolean {
        val (x, y) = axesSegments()
        return Geometry.distancePointToSegment(p, x[0], x[1]) <= tol ||
            Geometry.distancePointToSegment(p, y[0], y[1]) <= tol
    }

    private fun nearRectOutline(p: Pt, tol: Double): Boolean {
        val b = box
        val corners = listOf(
            Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom),
        )
        return nearPolyOutline(corners, p, tol)
    }

    private fun nearPolyOutline(verts: List<Pt>, p: Pt, tol: Double, closed: Boolean = true): Boolean {
        if (verts.isEmpty()) return false
        val edges = if (closed) verts.size else verts.size - 1
        for (i in 0 until edges) {
            val a = verts[i]
            val b = verts[(i + 1) % verts.size]
            if (Geometry.distancePointToSegment(p, a, b) <= tol) return true
        }
        return false
    }

    override fun centroid(): Pt = bounds().center

    override fun outlinePoints(): List<Pt> = when (shape) {
        ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.NUMBER_LINE -> listOf(start, end)
        ShapeKind.COORD_AXES -> axesSegments().take(2).flatten()
        else -> currentOutline().filterNot { it.x.isNaN() }
    }

    /** True if an eraser circle of [radius] at (cx,cy) touches the shape's geometry. */
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        val p = Pt(cx, cy)
        if (bounds().distanceTo(p) > radius) return false // cheap AABB reject
        val tol = radius + strokeWidth / 2.0
        return when (shape) {
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.NUMBER_LINE -> Geometry.distancePointToSegment(p, start, end) <= tol
            ShapeKind.RECTANGLE ->
                if (fillRgba != null && box.contains(p)) true else nearRectOutline(p, tol)
            ShapeKind.TRIANGLE -> {
                val v = triangleVertices()
                if (fillRgba != null && Geometry.pointInPolygon(v, p)) true else nearPolyOutline(v, p, tol)
            }
            ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> {
                val poly = ellipsePolygon()
                if (fillRgba != null && Geometry.pointInPolygon(poly, p)) true else nearPolyOutline(poly, p, tol)
            }
            ShapeKind.POLYGON -> {
                val v = absPoints()
                if (fillRgba != null && Geometry.pointInPolygon(v, p)) true else nearPolyOutline(v, p, tol)
            }
            ShapeKind.COORD_AXES -> nearAxes(p, tol)
            ShapeKind.POLYLINE, ShapeKind.CURVE, ShapeKind.FUNCTION -> runs().any { nearPolyOutline(it, p, tol, closed = false) }
            ShapeKind.SPLINE -> nearPolyOutline(splinePath(), p, tol, closed = false)
        }
    }

    /**
     * AREA-erase: the outline parts that survive an eraser circle (page-local [cx], [cy],
     * [radius]), cut cleanly at the circle boundary — the same contract as [Stroke.erasedBy]:
     *  - `null`      — untouched (keep the original)
     *  - empty list  — remove the whole shape
     *  - fragments   — the surviving outline runs, as polyline shapes keeping this style
     * A filled shape erases whole on any hit: a cut outline cannot hold its fill.
     */
    fun erasedBy(cx: Double, cy: Double, radius: Double): List<ShapeItem>? {
        val c = Pt(cx, cy)
        if (bounds().distanceTo(c) > radius) return null
        if (fillRgba != null) return if (intersectsCircle(cx, cy, radius)) emptyList() else null
        // Axes erase as a unit (disjoint segments don't cut into single-polyline fragments cleanly).
        if (shape == ShapeKind.COORD_AXES || shape == ShapeKind.NUMBER_LINE) return if (intersectsCircle(cx, cy, radius)) emptyList() else null
        val verts = when (shape) {
            // The arrow clips by its shaft, like its hit tests; a cut arrow loses its head.
            ShapeKind.LINE, ShapeKind.ARROW -> listOf(start, end)
            else -> currentOutline()
        }
        if (verts.size < 2) return if (intersectsCircle(cx, cy, radius)) emptyList() else null
        val closed = shape.isClosed
        val paths = if (closed) listOf(verts + verts.first()) else FunctionSpec.runs(verts)

        var touched = false
        val runs = mutableListOf<MutableList<Pt>>()
        var current = mutableListOf<Pt>()
        fun flush() {
            if (current.size >= 2 && polylineLength(current) > 1e-6) runs.add(current)
            current = mutableListOf()
        }
        for (pts in paths) {
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val outside = outsideSpans(a, b, c, radius)
                if (outside.size == 1 && outside[0].first == 0.0 && outside[0].second == 1.0) {
                    if (current.isEmpty()) current.add(a)
                    current.add(b)
                    continue
                }
                touched = true
                if (outside.isEmpty()) { // the whole segment sits inside the circle
                    flush()
                    continue
                }
                for ((ta, tb) in outside) {
                    if (ta == 0.0) {
                        if (current.isEmpty()) current.add(a)
                    } else {
                        flush()
                        current.add(lerp(a, b, ta))
                    }
                    current.add(if (tb == 1.0) b else lerp(a, b, tb))
                    if (tb < 1.0) flush()
                }
            }
            flush()
        }
        if (!touched) return null

        // A closed outline cut away from the seam vertex leaves the seam intact in two runs;
        // join them so the survivor is one continuous polyline instead of splitting at v0.
        if (closed && runs.size >= 2) {
            val pts = paths.first()
            val first = runs.first()
            val last = runs.last()
            if (first.first().distanceTo(pts.first()) < 1e-9 && last.last().distanceTo(pts.first()) < 1e-9) {
                runs.removeAt(runs.size - 1)
                runs[0] = (last + first.drop(1)).toMutableList()
            }
        }
        return runs.map {
            poly(
                ShapeKind.POLYLINE, it, strokeRgba, strokeWidth, null, neon, neonStrength,
                dashed, dashLength, dashGap,
            )
        }
    }

    /** Sub-intervals of the segment [a]→[b] (as fractions) lying outside the circle at [c]. */
    private fun outsideSpans(a: Pt, b: Pt, c: Pt, r: Double): List<Pair<Double, Double>> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val fx = a.x - c.x
        val fy = a.y - c.y
        val qa = dx * dx + dy * dy
        if (qa < 1e-12) return if (fx * fx + fy * fy > r * r) listOf(0.0 to 1.0) else emptyList()
        val qb = 2.0 * (fx * dx + fy * dy)
        val qc = fx * fx + fy * fy - r * r
        val disc = qb * qb - 4.0 * qa * qc
        if (disc <= 0.0) return listOf(0.0 to 1.0) // misses (or grazes) the segment's line
        val sq = sqrt(disc)
        val t1 = (-qb - sq) / (2.0 * qa)
        val t2 = (-qb + sq) / (2.0 * qa)
        if (t2 <= 0.0 || t1 >= 1.0) return listOf(0.0 to 1.0) // chord lies beyond the segment span
        val spans = mutableListOf<Pair<Double, Double>>()
        if (t1 > 0.0) spans.add(0.0 to min(t1, 1.0))
        if (t2 < 1.0) spans.add(max(t2, 0.0) to 1.0)
        return spans
    }

    private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun polylineLength(pts: List<Pt>): Double {
        var len = 0.0
        for (i in 0 until pts.size - 1) len += pts[i].distanceTo(pts[i + 1])
        return len
    }

    override fun geometry(): GeoHandle = ShapeHandle(start, end)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is ShapeHandle) {
            start = handle.start
            end = handle.end
        }
    }

    override fun snapshotGeometry(): GeometrySnapshot = ShapeSnapshot(shape, start, end, points, strokeWidth, angle, function)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is ShapeSnapshot) return
        shape = snap.shape
        start = snap.start
        end = snap.end
        points = snap.points
        strokeWidth = snap.strokeWidth
        angle = snap.angle
        function = snap.function
    }

    /** Plot [spec] in the current box instead; false (and unchanged) when it cannot be drawn. */
    fun setFunction(spec: FunctionSpec): Boolean {
        val pts = spec.normalizedSamples() ?: return false
        function = spec
        points = pts
        return true
    }

    /**
     * Bake a transform into the shape. A line/arrow keeps its kind (only its two endpoints move).
     * A pure scale keeps every kind parametric (the box scales; normalized vertices follow). A
     * rotation can't be held by an axis-aligned box, so a rotated rectangle/ellipse/triangle is
     * converted to a [ShapeKind.POLYGON] (closed) or [ShapeKind.POLYLINE] (open) of baked
     * vertices. The outline width scales by the transform's linear factor (1.0 for a rotation).
     */
    override fun applyTransform(t: Affine) {
        strokeWidth *= t.linearScale
        if (shape == ShapeKind.SPLINE) {
            setControlPoints(controlPoints().map { t.apply(it) })
            return
        }
        if (shape == ShapeKind.COORD_AXES) {
            applyAxesTransform(t)
            return
        }
        if (shape.isEndpointShape || t.isAxisAligned) {
            start = t.apply(start)
            end = t.apply(end)
            return
        }
        val verts = currentOutline().map { t.apply(it) }
        val bb = Rect.bounding(verts.filterNot { it.x.isNaN() })
        shape = if (shape.isClosed) ShapeKind.POLYGON else ShapeKind.POLYLINE
        function = null
        start = bb.topLeft
        end = Pt(bb.right, bb.bottom)
        points = normalize(verts, bb)
    }

    /** Like [ImageItem.applyTransform]: scale the upright box along its own axes about the mapped centre, and add the turn to [angle]. */
    private fun applyAxesTransform(t: Affine) {
        val b = box
        val c = t.apply(b.center)
        val ca = cos(angle)
        val sa = sin(angle)
        val w = b.w * hypot(t.a * ca + t.c * sa, t.b * ca + t.d * sa)
        val h = b.h * hypot(-t.a * sa + t.c * ca, -t.b * sa + t.d * ca)
        start = Pt(c.x - w / 2.0, c.y - h / 2.0)
        end = Pt(c.x + w / 2.0, c.y + h / 2.0)
        angle += t.rotationAngle
    }

    /** Content-space vertices of the current outline; used to bake a rotation into a vertex list. */
    private fun currentOutline(): List<Pt> = when (shape) {
        ShapeKind.POLYGON, ShapeKind.POLYLINE, ShapeKind.CURVE, ShapeKind.FUNCTION -> absPoints()
        ShapeKind.SPLINE -> splinePath()
        ShapeKind.TRIANGLE -> triangleVertices()
        ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> ellipsePolygon()
        else -> {
            val b = box
            listOf(Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom))
        }
    }

    companion object {
        const val KIND = "shape"
        const val HIT_TOLERANCE = 6.0
        private const val SPLINE_SAMPLES = 16

        /** Build a polygon/polyline from absolute [vertices], stored normalized to their box. */
        fun poly(
            shape: ShapeKind,
            vertices: List<Pt>,
            strokeRgba: Rgba,
            strokeWidth: Double = 3.0,
            fillRgba: Rgba? = null,
            neon: Boolean = false,
            neonStrength: Double = 0.6,
            dashed: Boolean = false,
            dashLength: Double = 10.0,
            dashGap: Double = 8.0,
        ): ShapeItem {
            val box = Rect.bounding(vertices)
            return ShapeItem(
                shape, box.topLeft, Pt(box.right, box.bottom), strokeRgba, strokeWidth,
                fillRgba, neon, neonStrength, normalize(vertices, box), dashed, dashLength, dashGap,
            )
        }

        private fun normalize(vertices: List<Pt>, box: Rect): List<Pt> {
            val w = if (box.w > 1e-9) box.w else 1.0
            val h = if (box.h > 1e-9) box.h else 1.0
            return vertices.map { Pt((it.x - box.left) / w, (it.y - box.top) / h) }
        }

        /** Halo blur = stroke_width × (MIN + SPAN × neonStrength), floored in page px. */
        private const val GLOW_FACTOR_MIN = 1.2
        private const val GLOW_FACTOR_SPAN = 2.6
        private const val GLOW_MIN = 4.0

        /** Halo opacity = MIN + SPAN × neonStrength. */
        internal const val GLOW_ALPHA_MIN = 0.25
        internal const val GLOW_ALPHA_SPAN = 0.55

        /** White-hot core line width as a fraction of the stroke width, with a page-px floor. */
        internal const val CORE_WIDTH_FRAC = 0.4
        internal const val CORE_WIDTH_MIN = 1.0
    }
}

/** Snapshot of a shape's transformable geometry (kind can change when a box shape is rotated). */
private data class ShapeSnapshot(
    val shape: ShapeKind,
    val start: Pt,
    val end: Pt,
    val points: List<Pt>?,
    val strokeWidth: Double,
    val angle: Double,
    val function: FunctionSpec?,
) : GeometrySnapshot
