package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt

/**
 * One captured stylus point, page-local; `pressure` in `[0, 1]`. [t] is the
 * milliseconds elapsed since the stroke's first sample (0 for that first one),
 * used only by velocity-aware tools (the speed pen); 0 everywhere else.
 * [widthFactor] is the speed pen's width kept at pen-up, 0 when there is none.
 */
data class Sample(
    val x: Double,
    val y: Double,
    val pressure: Double,
    val t: Double = 0.0,
    val widthFactor: Double = 0.0,
) {
    val pos: Pt get() = Pt(x, y)
}

/**
 * Read access to a ribbon's points and the two edges either side of them, whatever built it.
 * [StrokeGeometry] is the finished form and [WetRibbon] the growing one; a renderer that wants to
 * draw part of a stroke asks through this rather than through either, so meshing a run of a live
 * stroke never means copying its geometry out first.
 */
interface RibbonPoints {
    /** Points in the ribbon. A live ribbon's backing arrays are longer than this; do not read past it. */
    val pointCount: Int

    fun cx(i: Int): Double
    fun cy(i: Int): Double
    fun hw(i: Int): Double

    /** Whether both edges carry a vertex per point, i.e. the ribbon has a body rather than being a dot. */
    val hasRails: Boolean

    fun leftX(i: Int): Double
    fun leftY(i: Int): Double
    fun rightX(i: Int): Double
    fun rightY(i: Int): Double
}

/**
 * The geometry derived from a stroke's samples (spec 03), packed into primitive float arrays —
 * a dense document caches millions of these points, so per-point objects or boxing would multiply
 * heap several-fold (floats are far past render precision; the renderer draws in floats anyway).
 * The ink is painted by sweeping a brush disc down the [centerline] at the per-point [halfWidths]
 * (Renderer.fillDiskRibbon), so caps and joins round on every pen. Compared by identity (a rebuild
 * is a new instance), like the model items.
 *
 * The ribbon's two edges are stored as [leftRail] and [rightRail], each in centreline order, and
 * the closed polygon the neon bloom fills is derived from them by [outline]. Storing the rails and
 * not the polygon is what lets a live stroke *grow*: the polygon is the left rail followed by the
 * right one reversed, so its second half shifts by one slot every time a sample lands, while the
 * rails only ever gain an entry at the end. [outline] is built once, on the first caller that
 * genuinely needs a packed ring, so ordinary ink never pays for it. Nothing needs both:
 * [WetRibbon] grows the rails, and the neon painter reads the ring.
 */
class StrokeGeometry(
    /** Smoothed centerline, interleaved x,y; one point per input sample. */
    val centerline: FloatArray,
    /** Brush disc radius at each centerline point. */
    val halfWidths: FloatArray,
    /** Ribbon's left edge, interleaved x,y, in centreline order; empty when there is no body. */
    val leftRail: FloatArray = EMPTY_F,
    /** Ribbon's right edge, interleaved x,y, in the same order (not reversed). */
    val rightRail: FloatArray = EMPTY_F,
) : RibbonPoints {
    /** Number of centerline points (one per input sample). */
    override val pointCount get() = halfWidths.size

    /** Number of outline vertices (2 per centerline point when the ribbon has a body). */
    val outlineCount get() = (leftRail.size + rightRail.size) / 2

    override fun cx(i: Int): Double = centerline[2 * i].toDouble()
    override fun cy(i: Int): Double = centerline[2 * i + 1].toDouble()
    override fun hw(i: Int): Double = halfWidths[i].toDouble()

    override fun leftX(i: Int): Double = leftRail[2 * i].toDouble()
    override fun leftY(i: Int): Double = leftRail[2 * i + 1].toDouble()
    override fun rightX(i: Int): Double = rightRail[2 * i].toDouble()
    override fun rightY(i: Int): Double = rightRail[2 * i + 1].toDouble()

    /** True when both rails carry a vertex per centreline point, i.e. the ribbon has a body. */
    override val hasRails get() = leftRail.size == 2 * pointCount && rightRail.size == 2 * pointCount

    /** Built on demand and kept; volatile because painting and hit-testing can race, and the array
     *  is written before it is published, so the worst a race costs is one redundant build. */
    @Volatile
    private var packedOutline: FloatArray? = null

    /**
     * The ribbon as one closed polygon, interleaved x,y: the left edge in order, then the right
     * reversed. Only the neon bloom, which hands a packed ring to the renderer, needs it.
     */
    val outline: FloatArray
        get() {
            packedOutline?.let { return it }
            val n = pointCount
            if (!hasRails) return EMPTY_F.also { packedOutline = it }
            val out = FloatArray(4 * n)
            for (i in 0 until n) {
                out[2 * i] = leftRail[2 * i]
                out[2 * i + 1] = leftRail[2 * i + 1]
                val j = 2 * n - 1 - i
                out[2 * j] = rightRail[2 * i]
                out[2 * j + 1] = rightRail[2 * i + 1]
            }
            return out.also { packedOutline = it }
        }

    /**
     * [p] inside the ribbon body, by the crossing-number rule over the closed rail ring. Walks the
     * rails in place rather than through [outline], so a hit test never packs a ring it then throws
     * away — selection sweeps run this over every stroke on the page.
     */
    fun bodyContains(p: Pt): Boolean {
        val n = pointCount
        if (!hasRails || n < 2) return false
        val m = 2 * n
        var inside = false
        var j = m - 1
        for (i in 0 until m) {
            val ax = ringX(i, n)
            val ay = ringY(i, n)
            val bx = ringX(j, n)
            val by = ringY(j, n)
            val crosses = (ay > p.y) != (by > p.y) &&
                p.x < (bx - ax) * (p.y - ay) / (by - ay) + ax
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    /** Ring vertex [i] of `2n`: the left rail forward, then the right rail reversed. */
    private fun ringX(i: Int, n: Int): Double =
        if (i < n) leftRail[2 * i].toDouble() else rightRail[2 * (2 * n - 1 - i)].toDouble()

    private fun ringY(i: Int, n: Int): Double =
        if (i < n) leftRail[2 * i + 1].toDouble() else rightRail[2 * (2 * n - 1 - i) + 1].toDouble()

    companion object {
        private val EMPTY_F = FloatArray(0)

        val EMPTY = StrokeGeometry(EMPTY_F, EMPTY_F)
    }
}
