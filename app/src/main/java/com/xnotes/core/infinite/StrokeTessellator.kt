package com.xnotes.core.infinite

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.stroke.RibbonPoints
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A triangle mesh in content space. Positions are doubles because the canvas is unbounded: a float
 * absolute position has already lost visible precision a million pixels from the origin, and the
 * uploader splits each coordinate into a coarse chunk index plus a small local offset precisely so
 * the GPU never has to hold one. Indices are relative to this mesh's own first vertex.
 */
class MeshData(
    /** Interleaved x,y in content space; `positions.size / 2` vertices. */
    val positions: DoubleArray,
    /**
     * Interleaved x,y displacement of each vertex from the line it belongs to, zero for a fill.
     * The renderer uses it to keep a sub-pixel line visible rather than letting it shimmer.
     */
    val offsets: DoubleArray,
    /** Triangle list, three indices per triangle, zero-based within this mesh. */
    val indices: IntArray,
    /**
     * Packed ARGB per vertex, or null when the whole mesh is one colour and the uploader supplies
     * it. Set only by a gradient fill, which is per-vertex colour on a subdivided mesh: the vertex
     * format already carries RGBA and the rasterizer already interpolates it, so a ramp costs
     * nothing per frame beyond the extra triangles it took to resolve.
     */
    val colors: IntArray? = null,
) {
    val vertexCount: Int get() = positions.size / 2
    val triangleCount: Int get() = indices.size / 3
    val isEmpty: Boolean get() = indices.isEmpty()

    companion object {
        val EMPTY = MeshData(DoubleArray(0), DoubleArray(0), IntArray(0))
    }
}

/**
 * Turns a ribbon's points into triangles once, at commit time. Every frame then draws those
 * triangles with the current zoom pushed in as a uniform, so ink is resolution independent: there
 * is no raster to be at the wrong scale, and no blur to resolve when a pinch settles.
 *
 * The silhouette is the paged painter's ([com.xnotes.core.pal.Renderer.fillDiskRibbon]): a brush
 * disc swept down the centreline, each segment the hull of its two end discs. A hull's sides are
 * tangent to both discs, so they lean back where the brush grows and forward where it shrinks. Rails
 * laid square across the centreline miss exactly that lean, which is most of what a calligraphy nib
 * does, and drew its swells thinner and more angular here than on a page.
 *
 * Each hull's straight part is one quad between its four tangent points. Where two meet at a
 * sample their tangent points nearly coincide, so they share one vertex on the disc's rim there,
 * and a smooth stroke costs a quad per segment. Where the edge really kinks, at a turn or where the
 * nib stops widening, each keeps its own and the rim between them is filled. The ends, and a disc
 * that holds its neighbour whole, are drawn as whole discs.
 *
 * Antialiasing comes from multisampling on these triangle edges rather than from coverage computed
 * in a shader, because MSAA is the only one of the two that composes correctly where a stroke
 * overlaps itself: a sample is covered or not, so the same colour written twice is still that
 * colour. That makes the silhouette's fidelity the whole of the quality, which is why the round
 * parts are tessellated to a chord tolerance rather than to a fixed segment count.
 */
object StrokeTessellator {

    /**
     * Content-space chord error allowed on a round cap or join. Chosen so a curve stays under half
     * a device pixel of error at [CanvasViewport.MAX_ZOOM], since geometry is baked once and the
     * canvas zooms far past what a page ever does.
     */
    const val DEFAULT_TOLERANCE = 0.5 / CanvasViewport.MAX_ZOOM

    /** Fewest and most segments a full circle is ever cut into. */
    const val MIN_CIRCLE_SEGMENTS = MeshBuilder.MIN_CIRCLE_SEGMENTS
    const val MAX_CIRCLE_SEGMENTS = MeshBuilder.MAX_CIRCLE_SEGMENTS

    /**
     * Turn angle, in radians, past which a join shows its own disc. Below it the two quads meeting
     * there share one vertex and cut the rim between them by under half a percent of the radius;
     * above it the rim is filled. Smoothed handwriting turns a fraction of this per sample.
     */
    const val JOIN_DISC_ANGLE = 0.18

    /** Edge turn, in radians, past which a join is drawn as the sample's whole disc. */
    private const val WHOLE_DISC_ANGLE = 2.0 * PI / 3.0

    /** Half-widths at or below this contribute nothing and are skipped. */
    private const val MIN_HALF_WIDTH = 1e-6

    /**
     * [widthScale] narrows the ribbon about its own centreline, which is how neon's white-hot core
     * is drawn: the same path at a fraction of the width, so it rounds with the body on every pen.
     */
    fun tessellate(
        g: RibbonPoints,
        tolerance: Double = DEFAULT_TOLERANCE,
        widthScale: Double = 1.0,
    ): MeshData = tessellate(g, 0, g.pointCount, tolerance, widthScale)

    /**
     * [tessellate] over the [count] points starting at [from]. A stroke still under the pen is
     * meshed in runs — the settled ones once each, the moving tail every frame — so that what a
     * frame costs stops growing with the stroke. Consecutive runs are asked to overlap by a point,
     * which makes the quad bridging them fall to the later run and leaves no gap on the join.
     *
     * The discs a run puts at its own two ends are the same discs the sweep would place there, so
     * they sit inside the ribbon and change nothing about its silhouette. A run reads nothing past
     * its own points, so a run meshed once stays right however the stroke goes on.
     */
    fun tessellate(
        g: RibbonPoints,
        from: Int,
        count: Int,
        tolerance: Double,
        widthScale: Double = 1.0,
    ): MeshData {
        if (count <= 0) return MeshData.EMPTY
        val b = MeshBuilder(estimateVertices(count), estimateIndices(count))
        if (count == 1) {
            val h = g.hw(from) * widthScale
            if (h > MIN_HALF_WIDTH) b.circle(g.cx(from), g.cy(from), h, tolerance)
            return b.build()
        }
        if (widthScale != 1.0) return scaledRibbon(g, from, count, tolerance, widthScale)

        // Each segment's tangent normals, left x, y then right x, y; none where one disc holds the
        // other, and that disc is drawn whole instead. A disc held by its neighbour is never drawn
        // whole itself: down a run of them the largest holds every one.
        val segs = count - 1
        val normals = DoubleArray(4 * segs)
        val body = BooleanArray(segs)
        val whole = BooleanArray(count)
        val held = BooleanArray(count)
        whole[0] = true
        whole[count - 1] = true
        val n = DoubleArray(4)
        for (k in 0 until segs) {
            val i = from + k
            val r0 = g.hw(i)
            val r1 = g.hw(i + 1)
            if (r0 <= MIN_HALF_WIDTH && r1 <= MIN_HALF_WIDTH) continue
            if (Geometry.capsule(g.cx(i), g.cy(i), r0, g.cx(i + 1), g.cy(i + 1), r1, n) == Geometry.SWALLOWED) {
                val big = if (r0 >= r1) k else k + 1
                whole[big] = true
                held[if (big == k) k + 1 else k] = true
                continue
            }
            body[k] = true
            n.copyInto(normals, 4 * k)
        }

        // The rim vertices each quad ends on (back) and starts from (front) at every sample.
        val backL = IntArray(count)
        val backR = IntArray(count)
        val frontL = IntArray(count)
        val frontR = IntArray(count)
        for (k in 0 until count) {
            val i = from + k
            val x = g.cx(i)
            val y = g.cy(i)
            val r = g.hw(i)
            val back = 4 * (k - 1)
            val front = 4 * k
            val hasBack = k > 0 && body[k - 1]
            val hasFront = k < segs && body[k]
            if (hasBack && hasFront && !whole[k]) {
                val turnL = turn(normals, back, front)
                val turnR = turn(normals, back + 2, front + 2)
                if (abs(turnL) <= WHOLE_DISC_ANGLE && abs(turnR) <= WHOLE_DISC_ANGLE) {
                    join(b, x, y, r, normals, back, front, turnL, turnR, tolerance, k, backL, backR, frontL, frontR)
                    continue
                }
                whole[k] = true
            }
            if (hasBack) {
                backL[k] = rim(b, x, y, r, normals[back], normals[back + 1])
                backR[k] = rim(b, x, y, r, normals[back + 2], normals[back + 3])
            }
            if (hasFront) {
                frontL[k] = rim(b, x, y, r, normals[front], normals[front + 1])
                frontR[k] = rim(b, x, y, r, normals[front + 2], normals[front + 3])
            }
            if (whole[k] && !held[k] && r > MIN_HALF_WIDTH) b.circle(x, y, r, tolerance)
        }

        for (k in 0 until segs) {
            if (!body[k]) continue
            b.triangle(frontL[k], frontR[k], backR[k + 1])
            b.triangle(frontL[k], backR[k + 1], backL[k + 1])
        }
        return b.build()
    }

    /**
     * Where the two quads meeting at sample [k] attach to its disc, and the rim between them.
     *
     * A side whose edge turns less than [JOIN_DISC_ANGLE], or by less than [tolerance] of rim, gets
     * one vertex both quads share, half way round. Otherwise each quad keeps its own tangent point
     * and the rim between them is filled as one fan. The fan's points all lie on the disc, so it
     * never paints outside the ink, and from back left round the front to back right it covers the
     * notch on whichever side the stroke turns away from.
     */
    private fun join(
        b: MeshBuilder,
        x: Double,
        y: Double,
        r: Double,
        normals: DoubleArray,
        back: Int,
        front: Int,
        turnL: Double,
        turnR: Double,
        tolerance: Double,
        k: Int,
        backL: IntArray,
        backR: IntArray,
        frontL: IntArray,
        frontR: IntArray,
    ) {
        if (r <= MIN_HALF_WIDTH) {
            val c = b.vertex(x, y)
            backL[k] = c
            backR[k] = c
            frontL[k] = c
            frontR[k] = c
            return
        }
        val shareL = shares(r, turnL, tolerance)
        val shareR = shares(r, turnR, tolerance)
        if (shareL) {
            val v = midRim(b, x, y, r, normals, back, front)
            backL[k] = v
            frontL[k] = v
        } else {
            backL[k] = rim(b, x, y, r, normals[back], normals[back + 1])
            frontL[k] = rim(b, x, y, r, normals[front], normals[front + 1])
        }
        if (shareR) {
            val v = midRim(b, x, y, r, normals, back + 2, front + 2)
            backR[k] = v
            frontR[k] = v
        } else {
            backR[k] = rim(b, x, y, r, normals[back + 2], normals[back + 3])
            frontR[k] = rim(b, x, y, r, normals[front + 2], normals[front + 3])
        }
        if (shareL && shareR) return

        val step = 2.0 * PI / circleSegments(r, tolerance)
        val anchor = backL[k]
        var last = -1
        fun fan(v: Int) {
            if (v == anchor || v == last) return
            if (last >= 0) b.triangle(anchor, last, v)
            last = v
        }
        if (!shareL) arc(b, x, y, r, normals[back], normals[back + 1], turnL, step) { fan(it) }
        fan(frontL[k])
        fan(frontR[k])
        if (!shareR) arc(b, x, y, r, normals[front + 2], normals[front + 3], -turnR, step) { fan(it) }
        fan(backR[k])
    }

    /** Whether one vertex can stand for both quads' tangent points on a side whose edge turns [turn]. */
    private fun shares(r: Double, turn: Double, tolerance: Double): Boolean =
        abs(turn) <= JOIN_DISC_ANGLE || r * (1.0 - cos(turn / 2.0)) <= tolerance

    /** The rim points strictly between normal ([nx], [ny]) and that normal turned by [sweep]. */
    private inline fun arc(
        b: MeshBuilder,
        x: Double,
        y: Double,
        r: Double,
        nx: Double,
        ny: Double,
        sweep: Double,
        step: Double,
        emit: (Int) -> Unit,
    ) {
        val parts = ceil(abs(sweep) / step).toInt()
        for (t in 1 until parts) {
            val a = sweep * t / parts
            val c = cos(a)
            val s = sin(a)
            emit(rim(b, x, y, r, nx * c - ny * s, nx * s + ny * c))
        }
    }

    /** Signed angle from the normal at [a] to the one at [c], both packed x, y in [normals]. */
    private fun turn(normals: DoubleArray, a: Int, c: Int): Double {
        val ax = normals[a]
        val ay = normals[a + 1]
        val cx = normals[c]
        val cy = normals[c + 1]
        return atan2(ax * cy - ay * cx, ax * cx + ay * cy)
    }

    /** A vertex on the rim of disc ([x], [y], [r]) along unit normal ([nx], [ny]), carrying its offset. */
    private fun rim(b: MeshBuilder, x: Double, y: Double, r: Double, nx: Double, ny: Double): Int =
        b.vertex(x + r * nx, y + r * ny, r * nx, r * ny)

    /** The rim vertex half way between the normals at [a] and [c] in [normals]. */
    private fun midRim(b: MeshBuilder, x: Double, y: Double, r: Double, normals: DoubleArray, a: Int, c: Int): Int {
        val mx = normals[a] + normals[c]
        val my = normals[a + 1] + normals[c + 1]
        val len = hypot(mx, my)
        return rim(b, x, y, r, mx / len, my / len)
    }

    /**
     * The dashed pen: the smoothed centreline cut into on/off runs, each drawn as a constant-width
     * ribbon with round ends. Same runs, same width and same round caps the paged renderer's dashed
     * pen paints, so a dashed stroke breaks in the same places on either canvas.
     *
     * A stroke too short to have a line is left to [tessellate], which draws it as the dot the paged
     * painter falls back to.
     */
    fun tessellateDashed(
        g: RibbonPoints,
        dashLength: Double,
        dashGap: Double,
        halfWidth: Double,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): MeshData {
        val n = g.pointCount
        if (n < 2) return tessellate(g, tolerance)
        return tessellateDashed(g, 0, n, dashLength, dashGap, halfWidth, 0.0, tolerance)
    }

    /**
     * [tessellateDashed] over a run, picking the pattern up [phase] units in. Splitting a dashed
     * line into runs would otherwise restart its rhythm at every seam; the phase is the arc the
     * runs before it spent, so the dashes land where an unbroken line would have put them.
     */
    fun tessellateDashed(
        g: RibbonPoints,
        from: Int,
        count: Int,
        dashLength: Double,
        dashGap: Double,
        halfWidth: Double,
        phase: Double,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): MeshData {
        if (count < 2) return tessellate(g, from, count, tolerance)
        if (halfWidth <= MIN_HALF_WIDTH) return MeshData.EMPTY
        val path = ArrayList<Pt>(count)
        for (i in from until from + count) path.add(Pt(g.cx(i), g.cy(i)))
        val b = MeshBuilder(estimateVertices(count), estimateIndices(count))
        for (run in MeshBuilder.dashRuns(path, dashLength, dashGap, closed = false, phase = phase)) {
            b.polylineRibbon(run, halfWidth, closed = false, tolerance = tolerance)
        }
        return b.build()
    }

    /**
     * The ribbon at a fraction of its width, built from the centreline and scaled half-widths
     * rather than from the rails, since the rails only exist at full width.
     */
    private fun scaledRibbon(g: RibbonPoints, from: Int, count: Int, tolerance: Double, widthScale: Double): MeshData {
        val end = from + count
        val b = MeshBuilder(estimateVertices(count), estimateIndices(count))
        for (i in from until end - 1) {
            val h0 = g.hw(i) * widthScale
            val h1 = g.hw(i + 1) * widthScale
            if (h0 <= MIN_HALF_WIDTH && h1 <= MIN_HALF_WIDTH) continue
            val dx = g.cx(i + 1) - g.cx(i)
            val dy = g.cy(i + 1) - g.cy(i)
            val len = hypot(dx, dy)
            if (len < 1e-9) continue
            val nx = -dy / len
            val ny = dx / len
            val l0 = b.vertex(g.cx(i) + nx * h0, g.cy(i) + ny * h0, nx * h0, ny * h0)
            val r0 = b.vertex(g.cx(i) - nx * h0, g.cy(i) - ny * h0, -nx * h0, -ny * h0)
            val l1 = b.vertex(g.cx(i + 1) + nx * h1, g.cy(i + 1) + ny * h1, nx * h1, ny * h1)
            val r1 = b.vertex(g.cx(i + 1) - nx * h1, g.cy(i + 1) - ny * h1, -nx * h1, -ny * h1)
            b.triangle(l0, r0, r1)
            b.triangle(l0, r1, l1)
        }
        // A disc at every sample, since a segment-normal ribbon gaps at its joins.
        for (i in from until end) {
            val h = g.hw(i) * widthScale
            if (h > MIN_HALF_WIDTH) b.circle(g.cx(i), g.cy(i), h, tolerance)
        }
        return b.build()
    }

    /** Segments a circle of [radius] needs to stay within [tolerance] of true. */
    fun circleSegments(radius: Double, tolerance: Double): Int =
        MeshBuilder.circleSegments(radius, tolerance)

    private fun estimateVertices(n: Int): Int = 2 * n + 2 * (MIN_CIRCLE_SEGMENTS + 2) + 16

    private fun estimateIndices(n: Int): Int = 6 * maxOf(0, n - 1) + 6 * (MIN_CIRCLE_SEGMENTS + 2) + 48
}
