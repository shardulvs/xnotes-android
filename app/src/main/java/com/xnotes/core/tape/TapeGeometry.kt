package com.xnotes.core.tape

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TapeMode
import com.xnotes.core.model.TapePattern
import com.xnotes.core.model.TapeShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a tape draws, worked out once in pure geometry so the note's painter and the canvas's mesher
 * draw exactly the same thing: its body as convex pieces, the pattern printed on it cut to those
 * pieces, and the outline a peeled tape shows.
 *
 * A straight strip is a capsule round its axis; a rectangle is its axis with square ends and the
 * width across it, its corners rounded a little; a freehand band is a capsule per segment of the
 * hand's path, so its joins and ends come out round. Everything is in content px.
 */
object TapeGeometry {

    /** The most a rectangle's corners are rounded by. */
    const val CORNER = 4.0

    /** Diagonal stripes: one every [STRIPE_PITCH] across, each [STRIPE_BAND] wide. */
    const val STRIPE_PITCH = 14.0
    const val STRIPE_BAND = 6.0

    /** Dots on a staggered grid. */
    const val DOT_PITCH = 12.0
    const val DOT_RADIUS = 2.2

    /** Grid lines both ways. */
    const val GRID_PITCH = 12.0
    const val GRID_LINE = 1.4

    /** The dashed outline a peeled tape shows. */
    const val OUTLINE_WIDTH = 1.6
    const val DASH_ON = 7.0
    const val DASH_GAP = 5.0

    /** A straight strip within this many degrees of level or upright snaps to it. */
    const val SNAP_DEGREES = 5.0

    /** How far a round end may stray from a true circle. */
    private const val ARC_TOLERANCE = 0.05

    /** The longest step between samples of a freehand band's edge, when finding its outline. */
    private const val EDGE_STEP = 2.0

    /** One run of the peeled outline, joined back to its start when [closed]. */
    class Line(val pts: List<Pt>, val closed: Boolean)

    /** [b] snapped level or upright with [a] when the strip from [a] runs within [SNAP_DEGREES] of either. */
    fun snap(a: Pt, b: Pt): Pt {
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (dx == 0.0 && dy == 0.0) return b
        val deg = Math.toDegrees(atan2(abs(dy), abs(dx)))
        return when {
            deg <= SNAP_DEGREES -> Pt(b.x, a.y)
            deg >= 90.0 - SNAP_DEGREES -> Pt(a.x, b.y)
            else -> b
        }
    }

    /**
     * What a drag from [start] to [at] lays in [mode], [width] across: a strip snapped level or
     * upright when nearly so; a rectangle corner to corner, never thinner either way than the
     * narrowest tape; or a band along [path], the hand's way so far.
     */
    fun laid(mode: TapeMode, start: Pt, at: Pt, path: List<Pt>, width: Double): TapeShape = when (mode) {
        TapeMode.STRAIGHT -> TapeShape(mode, listOf(start, snap(start, at)), width)
        TapeMode.RECTANGLE -> {
            val box = Rect.fromPoints(start, at)
            val w = max(box.w, TapeItem.MIN_WIDTH)
            val h = max(box.h, TapeItem.MIN_WIDTH)
            TapeShape(mode, listOf(Pt(box.centerX - w / 2.0, box.centerY), Pt(box.centerX + w / 2.0, box.centerY)), h)
        }
        TapeMode.FREEHAND -> TapeShape(mode, path, width)
    }

    /** The body as convex pieces, back to front; the union is the tape. */
    fun body(s: TapeShape): List<List<Pt>> = when (s.mode) {
        TapeMode.STRAIGHT -> listOf(capsule(s.points[0], s.points[1], s.half))
        TapeMode.RECTANGLE -> listOf(roundedBox(s.points[0], s.points[1], s.half))
        TapeMode.FREEHAND -> if (s.points.size == 1) {
            listOf(capsule(s.points[0], s.points[0], s.half))
        } else {
            (0 until s.points.size - 1).map { capsule(s.points[it], s.points[it + 1], s.half) }
        }
    }

    /** The outline a peeled tape shows: the edge of its body, without the seams between pieces. */
    fun outline(s: TapeShape): List<Line> {
        val pieces = s.body
        if (pieces.size == 1) return listOf(Line(pieces[0], true))
        return unionEdge(s.points, s.half, pieces)
    }

    /** The pattern printed on the body as convex polygons, each inside one body piece. */
    fun pattern(s: TapeShape, pattern: TapePattern): List<List<Pt>> {
        if (pattern == TapePattern.SOLID) return emptyList()
        val frame = frameOf(s)
        val out = ArrayList<List<Pt>>()
        for (piece in s.body) {
            val local = piece.map { frame.toLocal(it) }
            val box = Rect.bounding(local)
            for (prim in primitives(pattern, box)) {
                val clipped = clipConvex(prim, local)
                if (clipped.size >= 3) out.add(clipped.map { frame.toWorld(it) })
            }
        }
        return out
    }

    /** Distance from [p] to the body: zero on or inside it. */
    fun distance(s: TapeShape, p: Pt): Double = when (s.mode) {
        TapeMode.RECTANGLE -> {
            val f = frameOf(s)
            val l = f.toLocal(p)
            val len = s.points[0].distanceTo(s.points[1])
            val dx = max(max(-l.x, l.x - len), 0.0)
            val dy = max(abs(l.y) - s.half, 0.0)
            hypot(dx, dy)
        }
        else -> max(centrelineDistance(s.points, p) - s.half, 0.0)
    }

    /** Distance from [p] to the edge a peeled tape shows. */
    fun edgeDistance(s: TapeShape, p: Pt): Double {
        var best = Double.POSITIVE_INFINITY
        for (line in s.outline) {
            val v = line.pts
            val segments = if (line.closed) v.size else v.size - 1
            for (k in 0 until segments) best = min(best, Geometry.distancePointToSegment(p, v[k], v[(k + 1) % v.size]))
        }
        return best
    }

    /** The body's upright bounds. */
    fun bounds(s: TapeShape): Rect = when (s.mode) {
        TapeMode.RECTANGLE -> Rect.bounding(corners(s))
        else -> Rect.bounding(s.points).outset(s.half)
    }

    /** A rectangle's four corners (a strip's box without its round ends), going round from beside its first point. */
    fun corners(s: TapeShape): List<Pt> {
        val a = s.points[0]
        val b = s.points[s.points.size - 1]
        val v = axes(a, b).second
        val h = s.half
        return listOf(a - v * h, b - v * h, b + v * h, a + v * h)
    }

    /** Unit vectors along and across the axis from [a] to [b]; level when the two meet. */
    fun axes(a: Pt, b: Pt): Pair<Pt, Pt> {
        val d = b - a
        val len = d.length()
        val u = if (len < 1e-9) Pt(1.0, 0.0) else d / len
        return u to u.perp()
    }

    /**
     * [points] with every point closer than [tolerance] to the line through its neighbours dropped
     * (Ramer-Douglas-Peucker), keeping both ends. A freehand band needs far fewer points than the
     * hand gave it to look the same at its width.
     */
    fun simplify(points: List<Pt>, tolerance: Double): List<Pt> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast().let { it[0] to it[1] }
            var worst = -1.0
            var at = -1
            for (k in i + 1 until j) {
                val d = Geometry.distancePointToSegment(points[k], points[i], points[j])
                if (d > worst) {
                    worst = d
                    at = k
                }
            }
            if (at >= 0 && worst > tolerance) {
                keep[at] = true
                stack.addLast(intArrayOf(i, at))
                stack.addLast(intArrayOf(at, j))
            }
        }
        return points.filterIndexed { k, _ -> keep[k] }
    }

    /**
     * Sutherland-Hodgman: the part of convex polygon [subject] inside convex polygon [clip], or an
     * empty list when they miss. Both may wind either way.
     */
    fun clipConvex(subject: List<Pt>, clip: List<Pt>): List<Pt> {
        if (subject.size < 3 || clip.size < 3) return emptyList()
        val sign = if (signedArea(clip) >= 0.0) 1.0 else -1.0
        var out = subject
        for (i in clip.indices) {
            if (out.isEmpty()) break
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            val input = out
            val next = ArrayList<Pt>(input.size + 2)
            fun inside(p: Pt) = sign * ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) >= 0.0
            for (k in input.indices) {
                val cur = input[k]
                val prev = input[(k + input.size - 1) % input.size]
                val curIn = inside(cur)
                val prevIn = inside(prev)
                if (curIn) {
                    if (!prevIn) crossing(prev, cur, a, b)?.let { next.add(it) }
                    next.add(cur)
                } else if (prevIn) {
                    crossing(prev, cur, a, b)?.let { next.add(it) }
                }
            }
            out = next
        }
        return out
    }

    // --- pieces ---

    /** A capsule round the segment [a]-[b] with radius [r]: a disc when the two meet. Convex. */
    fun capsule(a: Pt, b: Pt, r: Double): List<Pt> {
        val n = arcSegments(r, PI)
        val d = b - a
        val theta = if (d.length() < 1e-9) 0.0 else atan2(d.y, d.x)
        val out = ArrayList<Pt>(2 * n + 2)
        for (i in 0..n) {
            val phi = theta - PI / 2 + PI * i / n
            out.add(Pt(b.x + r * cos(phi), b.y + r * sin(phi)))
        }
        for (i in 0..n) {
            val phi = theta + PI / 2 + PI * i / n
            out.add(Pt(a.x + r * cos(phi), a.y + r * sin(phi)))
        }
        return out
    }

    /** The box along [a]-[b] reaching [h] either side, its corners rounded by up to [CORNER]. Convex. */
    private fun roundedBox(a: Pt, b: Pt, h: Double): List<Pt> {
        val (u, v) = axes(a, b)
        val len = a.distanceTo(b)
        val rc = min(CORNER, min(h, len / 2.0)).coerceAtLeast(0.0)
        fun at(x: Double, y: Double) = Pt(a.x + u.x * x + v.x * y, a.y + u.y * x + v.y * y)
        if (rc <= 1e-9) return listOf(at(0.0, -h), at(len, -h), at(len, h), at(0.0, h))
        val m = arcSegments(rc, PI / 2)
        val out = ArrayList<Pt>(4 * (m + 1))
        val centres = listOf(
            Pt(len - rc, -h + rc) to -PI / 2,
            Pt(len - rc, h - rc) to 0.0,
            Pt(rc, h - rc) to PI / 2,
            Pt(rc, -h + rc) to PI,
        )
        for ((c, start) in centres) {
            for (i in 0..m) {
                val phi = start + (PI / 2) * i / m
                out.add(at(c.x + rc * cos(phi), c.y + rc * sin(phi)))
            }
        }
        return out
    }

    /** Segments for an arc of [sweep] radians on radius [r] that keeps within [ARC_TOLERANCE]. */
    private fun arcSegments(r: Double, sweep: Double): Int {
        if (r <= ARC_TOLERANCE) return 2
        val step = 2.0 * acos(1.0 - ARC_TOLERANCE / r)
        return ceil(sweep / step).toInt().coerceIn(2, 48)
    }

    // --- union edge of a freehand band ---

    /**
     * The edge of the union of [pieces] (the capsules along [points] with radius [h]): each piece's
     * boundary, sampled finely, keeping the parts no other capsule covers. Runs of kept samples
     * become the outline's lines; seams inside the band and the overlaps where it crosses itself go.
     */
    private fun unionEdge(points: List<Pt>, h: Double, pieces: List<List<Pt>>): List<Line> {
        val segs = pieces.size
        val boxes = Array(segs) { Rect.bounding(pieces[it]) }
        val out = ArrayList<Line>()
        val inner = h * (1.0 - 1e-6) - 1e-6
        for (i in 0 until segs) {
            val near = (0 until segs).filter { it != i && boxes[it].intersects(boxes[i]) }
            val ring = densify(pieces[i])
            val kept = BooleanArray(ring.size) { k ->
                val q = ring[k]
                near.none { j -> Geometry.distancePointToSegment(q, points[j], points[j + 1]) < inner }
            }
            if (kept.all { it }) {
                out.add(Line(ring, true))
                continue
            }
            // Walk the ring from a dropped sample so every run comes out whole.
            val start = kept.indexOfFirst { !it }
            var run = ArrayList<Pt>()
            for (step in 1..ring.size) {
                val k = (start + step) % ring.size
                if (kept[k]) {
                    run.add(ring[k])
                } else if (run.isNotEmpty()) {
                    if (run.size >= 2) out.add(Line(run, false))
                    run = ArrayList()
                }
            }
            if (run.size >= 2) out.add(Line(run, false))
        }
        return out
    }

    /** [ring] with every edge longer than [EDGE_STEP] split into equal steps. */
    private fun densify(ring: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>(ring.size * 2)
        for (k in ring.indices) {
            val a = ring[k]
            val b = ring[(k + 1) % ring.size]
            val n = max(1, ceil(a.distanceTo(b) / EDGE_STEP).toInt())
            for (s in 0 until n) out.add(Pt(a.x + (b.x - a.x) * s / n, a.y + (b.y - a.y) * s / n))
        }
        return out
    }

    // --- pattern ---

    /** Where a pattern is printed from: along a strip or rectangle's axis, upright on a freehand band. */
    private class Frame(val origin: Pt, val u: Pt, val v: Pt) {
        fun toLocal(p: Pt): Pt {
            val dx = p.x - origin.x
            val dy = p.y - origin.y
            return Pt(dx * u.x + dy * u.y, dx * v.x + dy * v.y)
        }

        fun toWorld(p: Pt): Pt = Pt(origin.x + u.x * p.x + v.x * p.y, origin.y + u.y * p.x + v.y * p.y)
    }

    private fun frameOf(s: TapeShape): Frame = when (s.mode) {
        TapeMode.FREEHAND -> Frame(s.points[0], Pt(1.0, 0.0), Pt(0.0, 1.0))
        else -> {
            val (u, v) = axes(s.points[0], s.points[1])
            Frame(s.points[0], u, v)
        }
    }

    /** The pattern's convex marks in the frame's coordinates, those that may reach into [box]. */
    private fun primitives(pattern: TapePattern, box: Rect): List<List<Pt>> = when (pattern) {
        TapePattern.SOLID -> emptyList()
        TapePattern.STRIPES -> stripes(box)
        TapePattern.DOTS -> dots(box)
        TapePattern.GRID -> grid(box)
    }

    /** Bands at 45 degrees to the axis: where (x - y) / sqrt 2 runs from k * pitch to k * pitch + band. */
    private fun stripes(box: Rect): List<List<Pt>> {
        val r2 = sqrt(2.0)
        val lo = (box.left - box.bottom) / r2
        val hi = (box.right - box.top) / r2
        val along = Pt(1.0 / r2, 1.0 / r2)
        val across = Pt(1.0 / r2, -1.0 / r2)
        val reach = hypot(box.w, box.h) + 1.0
        val mid = Pt(box.centerX, box.centerY)
        val t0 = mid.x * along.x + mid.y * along.y
        val out = ArrayList<List<Pt>>()
        var k = floor((lo - STRIPE_BAND) / STRIPE_PITCH).toInt()
        while (k * STRIPE_PITCH <= hi) {
            val s0 = k * STRIPE_PITCH
            val s1 = s0 + STRIPE_BAND
            fun p(s: Double, t: Double) = Pt(across.x * s + along.x * t, across.y * s + along.y * t)
            out.add(listOf(p(s0, t0 - reach), p(s1, t0 - reach), p(s1, t0 + reach), p(s0, t0 + reach)))
            k++
        }
        return out
    }

    /** Dots on rows [DOT_PITCH] apart, every other row shifted half a pitch. */
    private fun dots(box: Rect): List<List<Pt>> {
        val out = ArrayList<List<Pt>>()
        val n = arcSegments(DOT_RADIUS, 2 * PI)
        var j = floor((box.top - DOT_RADIUS) / DOT_PITCH).toInt()
        while (j * DOT_PITCH <= box.bottom + DOT_RADIUS) {
            val shift = if (j % 2 != 0) DOT_PITCH / 2 else 0.0
            var i = floor((box.left - DOT_RADIUS - shift) / DOT_PITCH).toInt()
            while (i * DOT_PITCH + shift <= box.right + DOT_RADIUS) {
                val cx = i * DOT_PITCH + shift
                val cy = j * DOT_PITCH
                out.add(List(n) { k -> Pt(cx + DOT_RADIUS * cos(2 * PI * k / n), cy + DOT_RADIUS * sin(2 * PI * k / n)) })
                i++
            }
            j++
        }
        return out
    }

    /** Thin lines [GRID_PITCH] apart, along and across. */
    private fun grid(box: Rect): List<List<Pt>> {
        val out = ArrayList<List<Pt>>()
        val half = GRID_LINE / 2
        var i = floor(box.left / GRID_PITCH).toInt()
        while (i * GRID_PITCH <= box.right + half) {
            val x = i * GRID_PITCH
            out.add(listOf(Pt(x - half, box.top - 1), Pt(x + half, box.top - 1), Pt(x + half, box.bottom + 1), Pt(x - half, box.bottom + 1)))
            i++
        }
        var j = floor(box.top / GRID_PITCH).toInt()
        while (j * GRID_PITCH <= box.bottom + half) {
            val y = j * GRID_PITCH
            out.add(listOf(Pt(box.left - 1, y - half), Pt(box.right + 1, y - half), Pt(box.right + 1, y + half), Pt(box.left - 1, y + half)))
            j++
        }
        return out
    }

    // --- helpers ---

    private fun centrelineDistance(points: List<Pt>, p: Pt): Double {
        if (points.size == 1) return p.distanceTo(points[0])
        var best = Double.POSITIVE_INFINITY
        for (k in 0 until points.size - 1) best = min(best, Geometry.distancePointToSegment(p, points[k], points[k + 1]))
        return best
    }

    private fun signedArea(ring: List<Pt>): Double {
        var s = 0.0
        for (k in ring.indices) {
            val a = ring[k]
            val b = ring[(k + 1) % ring.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
    }

    /** Where segment [p]-[q] crosses the line through [a] and [b], or null when it runs parallel. */
    private fun crossing(p: Pt, q: Pt, a: Pt, b: Pt): Pt? {
        val ex = b.x - a.x
        val ey = b.y - a.y
        val dx = q.x - p.x
        val dy = q.y - p.y
        val den = ex * dy - ey * dx
        if (abs(den) < 1e-12) return null
        val t = (ex * (a.y - p.y) - ey * (a.x - p.x)) / den
        return Pt(p.x + dx * t, p.y + dy * t)
    }
}
