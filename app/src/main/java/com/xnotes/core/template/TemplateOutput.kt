package com.xnotes.core.template

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One laid-out shape, in page millimetres. Point arrays alternate x and y. */
sealed class TPrim {
    abstract val bounds: Rect

    /** A stroked polyline; [dashOff] of 0 means solid. */
    class Stroke(val pts: DoubleArray, val closed: Boolean, val width: Double, val dashOn: Double, val dashOff: Double) : TPrim() {
        override val bounds = pointBounds(pts).outset(width / 2.0)
    }

    /** A nonzero fill of one or more rings, already joined into one point run. */
    class Fill(val pts: DoubleArray) : TPrim() {
        override val bounds = pointBounds(pts)
    }

    class FillRect(val rect: Rect) : TPrim() {
        override val bounds = rect
    }

    /** A filled ([fill]) or stroked ellipse. */
    class Ellipse(
        val cx: Double,
        val cy: Double,
        val rx: Double,
        val ry: Double,
        val fill: Boolean,
        val width: Double,
        val dashOn: Double,
        val dashOff: Double,
    ) : TPrim() {
        override val bounds: Rect = (if (fill) 0.0 else width / 2.0).let { m ->
            Rect(cx - rx - m, cy - ry - m, 2 * (rx + m), 2 * (ry + m))
        }
    }
}

/**
 * A picture laid out (SPEC §5.3): [box] is the rectangle `fit` and the position place it in, all
 * in page mm, and only the part inside [clip] shows.
 */
class TImage(val src: String, val box: Rect, val clip: Rect, val fit: ImageFit, val posX: Double, val posY: Double) {
    /** Where a picture [iw] by [ih] pixels lands. */
    fun dest(iw: Double, ih: Double): Rect {
        if (fit == ImageFit.STRETCH || iw <= 0.0 || ih <= 0.0) return box
        val s = if (fit == ImageFit.CONTAIN) min(box.w / iw, box.h / ih) else max(box.w / iw, box.h / ih)
        val w = iw * s
        val h = ih * s
        return Rect(box.x + (box.w - w) * posX, box.y + (box.h - h) * posY, w, h)
    }
}

/**
 * Every shape of one colour: painted opaque together, then composited at [color]'s alpha (SPEC §7.1).
 * An [image] is a layer of its own, composited at [color]'s alpha, with no prims.
 */
class TemplateLayer(val color: Rgba, val prims: List<TPrim>, val image: TImage? = null)

/** A template laid out for one page: its colour layers in compositing order. */
class TemplateOutput(val layers: List<TemplateLayer>) {
    val primCount: Int get() = layers.sumOf { it.prims.size }

    companion object {
        val EMPTY = TemplateOutput(emptyList())
    }
}

internal fun pointBounds(pts: DoubleArray): Rect {
    if (pts.isEmpty()) return Rect(0.0, 0.0, 0.0, 0.0)
    var l = pts[0]
    var t = pts[1]
    var r = l
    var b = t
    var i = 2
    while (i + 1 < pts.size) {
        l = min(l, pts[i]); r = max(r, pts[i])
        t = min(t, pts[i + 1]); b = max(b, pts[i + 1])
        i += 2
    }
    return Rect(l, t, r - l, b - t)
}

/**
 * Geometric clipping against a rectangle, as SPEC §7.2 defines it. The rectangle is closed, except
 * along the edges it shares with the paper: a line lying exactly on the paper's edge, or a dot
 * centred on it, is dropped, so a ruling never sits half-visible on the edge of the page.
 */
internal object TemplateClip {
    private const val EPS = 1e-9
    private const val LEFT = 1
    private const val RIGHT = 2
    private const val TOP = 4
    private const val BOTTOM = 8

    /** The sides of [c] that lie on the paper's edge. */
    fun open(c: Rect, paper: Rect): Int =
        (if (abs(c.left - paper.left) < EPS) LEFT else 0) or
            (if (abs(c.right - paper.right) < EPS) RIGHT else 0) or
            (if (abs(c.top - paper.top) < EPS) TOP else 0) or
            (if (abs(c.bottom - paper.bottom) < EPS) BOTTOM else 0)

    fun intersect(a: Rect, b: Rect): Rect? {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bo = min(a.bottom, b.bottom)
        return if (r > l && bo > t) Rect(l, t, r - l, bo - t) else null
    }

    /** Whether (x, y) is inside [c], counting its [open] sides as outside. */
    fun inside(x: Double, y: Double, c: Rect, open: Int): Boolean =
        side(x - c.left, open and LEFT) && side(c.right - x, open and RIGHT) &&
            side(y - c.top, open and TOP) && side(c.bottom - y, open and BOTTOM)

    private fun side(q: Double, open: Int) = if (open != 0) q > EPS else q >= -EPS

    /**
     * The parameter range of segment (x0,y0)-(x1,y1) inside [c], as `t0` and `t1` written to [out],
     * or false when none of it is. A piece lying along one of the [open] sides counts as outside.
     */
    fun segment(x0: Double, y0: Double, x1: Double, y1: Double, c: Rect, open: Int, out: DoubleArray): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        var t0 = 0.0
        var t1 = 1.0
        val ps = doubleArrayOf(-dx, dx, -dy, dy)
        val qs = doubleArrayOf(x0 - c.left, c.right - x0, y0 - c.top, c.bottom - y0)
        val sides = intArrayOf(LEFT, RIGHT, TOP, BOTTOM)
        for (k in 0 until 4) {
            val p = ps[k]
            val q = qs[k]
            if (abs(p) < 1e-12) {
                if (!side(q, open and sides[k])) return false
                continue
            }
            val r = q / p
            if (p < 0) { if (r > t0) t0 = r } else { if (r < t1) t1 = r }
            if (t0 > t1) return false
        }
        val len = kotlin.math.hypot(dx, dy)
        if ((t1 - t0) * len <= EPS) return false
        out[0] = t0
        out[1] = t1
        return true
    }

    /** [pts] cut to [c], handing each surviving run to [emit] with whether it is still a closed ring. */
    fun polyline(pts: DoubleArray, closed: Boolean, c: Rect, open: Int, emit: (DoubleArray, Boolean) -> Unit) {
        val n = pts.size / 2
        if (n < 2) return
        if (allInside(pts, c, open)) {
            emit(pts, closed)
            return
        }
        val segs = if (closed) n else n - 1
        val pieces = ArrayList<DoubleArrayBuilder>()
        var cur: DoubleArrayBuilder? = null
        var firstStartsAtZero = false
        var lastEndsAtOne = false
        val tt = DoubleArray(2)
        for (s in 0 until segs) {
            val ax = pts[2 * s]
            val ay = pts[2 * s + 1]
            val bi = (s + 1) % n
            val bx = pts[2 * bi]
            val by = pts[2 * bi + 1]
            if (!segment(ax, ay, bx, by, c, open, tt)) {
                cur = null
                continue
            }
            val sx = ax + (bx - ax) * tt[0]
            val sy = ay + (by - ay) * tt[0]
            val ex = ax + (bx - ax) * tt[1]
            val ey = ay + (by - ay) * tt[1]
            val continues = cur != null && tt[0] <= 0.0
            if (!continues) {
                cur = DoubleArrayBuilder().also { it.add(sx, sy); pieces.add(it) }
                if (s == 0 && tt[0] <= 0.0) firstStartsAtZero = true
            }
            cur!!.add(ex, ey)
            if (s == segs - 1) lastEndsAtOne = tt[1] >= 1.0
            if (tt[1] < 1.0) cur = null
        }
        // A ring cut open that runs through its own start point: rejoin its last run to its first.
        if (closed && pieces.size > 1 && firstStartsAtZero && lastEndsAtOne) {
            val last = pieces.removeAt(pieces.size - 1)
            val first = pieces[0]
            pieces[0] = last.also { it.appendSkippingFirst(first) }
        }
        for (p in pieces) if (p.size >= 4) emit(p.toArray(), false)
    }

    /** The part of polygon [pts] inside [c] (Sutherland-Hodgman against each edge). */
    fun polygon(pts: DoubleArray, c: Rect): DoubleArray {
        var cur = pts
        for (edge in 0 until 4) {
            if (cur.size < 6) return DoubleArray(0)
            val out = DoubleArrayBuilder()
            val n = cur.size / 2
            for (i in 0 until n) {
                val ax = cur[2 * i]
                val ay = cur[2 * i + 1]
                val j = (i + 1) % n
                val bx = cur[2 * j]
                val by = cur[2 * j + 1]
                val aIn = inside(ax, ay, edge, c)
                val bIn = inside(bx, by, edge, c)
                if (aIn) out.add(ax, ay)
                if (aIn != bIn) {
                    val t = cross(ax, ay, bx, by, edge, c)
                    out.add(ax + (bx - ax) * t, ay + (by - ay) * t)
                }
            }
            cur = out.toArray()
        }
        return if (cur.size >= 6) cur else DoubleArray(0)
    }

    private fun allInside(pts: DoubleArray, c: Rect, open: Int): Boolean {
        var i = 0
        while (i + 1 < pts.size) {
            if (!inside(pts[i], pts[i + 1], c, open)) return false
            i += 2
        }
        return true
    }

    private fun inside(x: Double, y: Double, edge: Int, c: Rect): Boolean = when (edge) {
        0 -> x >= c.left
        1 -> x <= c.right
        2 -> y >= c.top
        else -> y <= c.bottom
    }

    private fun cross(ax: Double, ay: Double, bx: Double, by: Double, edge: Int, c: Rect): Double = when (edge) {
        0 -> (c.left - ax) / (bx - ax)
        1 -> (c.right - ax) / (bx - ax)
        2 -> (c.top - ay) / (by - ay)
        else -> (c.bottom - ay) / (by - ay)
    }
}

/** A growable point run. */
internal class DoubleArrayBuilder(capacity: Int = 16) {
    private var a = DoubleArray(capacity)
    var size = 0
        private set

    fun add(x: Double, y: Double) {
        if (size + 2 > a.size) a = a.copyOf(maxOf(a.size * 2, size + 2))
        a[size++] = x
        a[size++] = y
    }

    fun addAll(pts: DoubleArray) {
        var i = 0
        while (i + 1 < pts.size) { add(pts[i], pts[i + 1]); i += 2 }
    }

    fun appendSkippingFirst(o: DoubleArrayBuilder) {
        var i = 2
        while (i + 1 < o.size) { add(o.a[i], o.a[i + 1]); i += 2 }
    }

    fun toArray(): DoubleArray = a.copyOf(size)
}
