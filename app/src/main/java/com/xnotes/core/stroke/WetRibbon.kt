package com.xnotes.core.stroke

import com.xnotes.core.geometry.Rect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The ribbon of a stroke still under the pen, grown a sample at a time instead of rebuilt.
 *
 * [StrokeEngine.build] is a batch: every sample re-runs a dozen passes over the whole stroke and
 * allocates a dozen arrays to hold them, so the cost of one frame of ink is proportional to how
 * much ink is already down. Written slowly, a long stroke ends up re-deriving thousands of points
 * to draw the one under the nib, and the line starts trailing the hand.
 *
 * It does not have to. Almost every rule the engine applies is **causal**: the arc-length low-pass
 * ([StrokeEngine.emaByArc]) and the nib's slew limiter ([StrokeEngine.nibStep]) each read one
 * sample and their own previous output, so their answer for a point is fixed the moment that point
 * arrives. What is left looks a bounded distance ahead, and that distance is what this class
 * tracks: [settledCount] is how many leading points can no longer change, whatever the writer does
 * next. Points behind it are computed once and kept. Points in front are the only ones recomputed,
 * and there are never many of them.
 *
 * What holds a point back:
 *  - its **tangent**, a central difference, so the last point is always provisional;
 *  - the **end-pressure hold** ([StrokeEngine.heldPressureAt]), whose window sits over the last
 *    [StrokeEngine.CAP_HOLD_SAMPLES] points and moves with them;
 *  - the **speed** pen's centred window, [StrokeEngine.SPEED_WINDOW_MS] of moving time ahead, and
 *    its blur, [StrokeEngine.SPEED_BLUR_REACH] sigmas of path ahead;
 *  - the **calligraphy** head, which pins every point flat until the stroke first fills
 *    [StrokeEngine.HEAD_LEN] and then rewrites them once;
 *  - the **calligraphy** reversal, which can only be ruled in or out once the stroke has run
 *    [StrokeEngine.reversalWait] past the turn.
 *
 * The taper pen settles nothing: its profile is a fraction of the *total* arc, so every point it
 * has ever drawn moves each time the stroke gets longer. [supports] turns it down.
 *
 * The output is exactly what [StrokeEngine.build] returns for the same samples with
 * `finished = false`, point for point, which the tests hold it to. Lift-time rules (the calligraphy
 * dot and its tail cap) are not modelled here at all: they only fire once the pen is up, and the
 * stroke rebuilds through [StrokeEngine.build] then.
 */
class WetRibbon(
    private val baseWidth: Double,
    private val pressureEnabled: Boolean,
    private val m: Double,
    private val ds: Double,
    private val speedStrength: Double,
    private val speedScale: Double,
    private val smooth: Boolean,
    private val holdEnds: Boolean,
    private val smoothScale: Double,
    private val speedTop: Double = StrokeEngine.SPEED_HI,
    speedSmoothing: Double = StrokeEngine.DEFAULT_SPEED_SMOOTHING,
) : RibbonPoints {
    /** Samples taken so far; one ribbon point each. */
    override var pointCount = 0
        private set

    /**
     * Leading points whose centre and half-width are final. Everything below this index can be
     * baked into a raster or uploaded to the GPU once and never revisited; everything at or above
     * it is redrawn each frame. 0 while the stroke is too young for anything to have settled.
     */
    var settledCount = 0
        private set

    // --- the causal channels, one entry per sample, grown by doubling ---

    private var sx = EMPTY_D
    private var sy = EMPTY_D
    private var sp = EMPTY_D
    private var tx = EMPTY_D
    private var ty = EMPTY_D

    /** Elapsed ms, cumulative *raw* travel and cumulative moving time: the speed pen's window reads them. */
    private var times = EMPTY_D
    private var rawCum = EMPTY_D
    private var tau = EMPTY_D

    /** The raw samples, which the calligraphy nib judges a reversal by, along with [rawCum]. */
    private var rawX = EMPTY_D
    private var rawY = EMPTY_D

    /** Smoothed travel up to each sample, and each sample's speed factor before the blur. */
    private var pathArc = EMPTY_D
    private var fSpeed = EMPTY_D

    /** Speed factors below this index are final: their windows no longer reach the newest sample. */
    private var fFinal = 0

    /** The nib's slew-limited direction, final up to [nibDone]; calligraphy only. */
    private var nib = EMPTY_D

    // --- the packed output, over-allocated; only [0, pointCount) is meaningful ---

    private var centerline = EMPTY_F
    private var halfWidths = EMPTY_F
    private var leftRail = EMPTY_F
    private var rightRail = EMPTY_F

    // --- carried state, so no pass ever restarts at the head of the stroke ---

    private var lastRawX = 0.0
    private var lastRawY = 0.0
    private var lastRawP = 0.0

    /** Cumulative travel along the *smoothed* centreline: what the nib's head window is measured in. */
    private var arc = 0.0

    private val smoothLen = StrokeEngine.SMOOTH_LEN * max(smoothScale, 0.0)
    private val headLen = StrokeEngine.headLen(smoothScale)
    private val openLen = StrokeEngine.openLen(smoothScale, baseWidth)
    private val closeLen = StrokeEngine.closeLen(smoothScale, baseWidth)
    private val reversalWait = StrokeEngine.reversalWait(smoothScale, baseWidth)
    private val turnScratch = DoubleArray(StrokeEngine.REVERSAL_SCRATCH)

    /** Tangents are final below this; the last one is always a backward difference and provisional. */
    private var tanDone = 0
    private var carryTx = 1.0
    private var carryTy = 0.0

    /** The sample the calligraphy head window first filled at, or -1 while it is still short. */
    private var headK = -1
    private var headDir = -1.0
    private var nibValue = 0.0
    private var nibDone = 0

    /** The speed window's two sample cursors as they stand entering [fFinal], and the blur's entering [settledCount]. */
    private val speedCursor = IntArray(2)
    private val speedScratch = IntArray(2)
    private val blurCursor = IntArray(1)
    private val blurScratch = IntArray(1)

    private val floor = StrokeEngine.floorSpeed(speedTop, speedScale)
    private val sigma = speedSmoothing * baseWidth

    /** Disc box of the settled points; [bounds] unions the live tail into it. */
    private var minX = Double.POSITIVE_INFINITY
    private var minY = Double.POSITIVE_INFINITY
    private var maxX = Double.NEGATIVE_INFINITY
    private var maxY = Double.NEGATIVE_INFINITY

    @Volatile
    private var snapshot: StrokeGeometry? = null

    /** Whether this pen reads its speed; a stroke with no timing yet reads as still, and draws at full width. */
    private val timed = speedStrength > 0.0

    // --- reading the ribbon ---

    override fun cx(i: Int): Double = centerline[2 * i].toDouble()
    override fun cy(i: Int): Double = centerline[2 * i + 1].toDouble()
    override fun hw(i: Int): Double = halfWidths[i].toDouble()

    /** The packed centres, over-allocated: read `2 * pointCount` floats. */
    fun centerlineArray(): FloatArray = centerline

    /** The packed radii, over-allocated: read [pointCount] floats. */
    fun halfWidthArray(): FloatArray = halfWidths

    override fun leftX(i: Int): Double = leftRail[2 * i].toDouble()
    override fun leftY(i: Int): Double = leftRail[2 * i + 1].toDouble()
    override fun rightX(i: Int): Double = rightRail[2 * i].toDouble()
    override fun rightY(i: Int): Double = rightRail[2 * i + 1].toDouble()

    /** A ribbon of two points or more has a body; a lone sample is a dot with no rails. */
    override val hasRails get() = pointCount >= 2

    /**
     * The ribbon as a standalone [StrokeGeometry], copied out at its exact length. Kept until the
     * next sample lands, so the cold call sites that want a whole geometry (bounds for a neon
     * halo, a hit test, the pen-up reduction) can have one without forcing the ribbon back into a
     * batch rebuild.
     */
    fun geometry(): StrokeGeometry {
        snapshot?.let { return it }
        val n = pointCount
        if (n == 0) return StrokeGeometry.EMPTY.also { snapshot = it }
        val c = centerline.copyOf(2 * n)
        val h = halfWidths.copyOf(n)
        // A single sample is a dot: one swept disc, no rails, exactly as the batch build returns it.
        val g = if (n == 1) {
            StrokeGeometry(c, h)
        } else {
            StrokeGeometry(c, h, leftRail.copyOf(2 * n), rightRail.copyOf(2 * n))
        }
        return g.also { snapshot = it }
    }

    /** Disc box of the whole ribbon: every centre grown by its own half-width. */
    fun bounds(): Rect {
        if (pointCount == 0) return Rect(0.0, 0.0, 0.0, 0.0)
        var lo0 = minX
        var lo1 = minY
        var hi0 = maxX
        var hi1 = maxY
        for (i in settledCount until pointCount) {
            val x = cx(i)
            val y = cy(i)
            val r = hw(i)
            if (x - r < lo0) lo0 = x - r
            if (x + r > hi0) hi0 = x + r
            if (y - r < lo1) lo1 = y - r
            if (y + r > hi1) hi1 = y + r
        }
        return Rect(lo0, lo1, hi0 - lo0, hi1 - lo1)
    }

    // --- growing the ribbon ---

    /** Take one more sample: extend every causal channel by a step, then redraw the live tail. */
    fun append(x: Double, y: Double, pressure: Double, t: Double) {
        val i = pointCount
        ensure(i + 1)
        val step = if (i == 0) 0.0 else hypot(x - lastRawX, y - lastRawY)
        if (i == 0) {
            sx[0] = x
            sy[0] = y
            sp[0] = pressure
            if (timed) {
                times[0] = t
                tau[0] = 0.0
            }
            if (timed || ds > 0.0) {
                rawCum[0] = 0.0
                pathArc[0] = 0.0
            }
            if (ds > 0.0) {
                rawX[0] = x
                rawY[0] = y
            }
        } else {
            sx[i] = if (smooth) StrokeEngine.emaStep(sx[i - 1], lastRawX, x, step, smoothLen) else x
            sy[i] = if (smooth) StrokeEngine.emaStep(sy[i - 1], lastRawY, y, step, smoothLen) else y
            sp[i] = StrokeEngine.emaStep(sp[i - 1], lastRawP, pressure, step, smoothLen)
            arc += hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
            if (timed) {
                times[i] = t
                tau[i] = tau[i - 1] + StrokeEngine.movingStep(t - times[i - 1], step, floor)
            }
            if (timed || ds > 0.0) {
                rawCum[i] = rawCum[i - 1] + step
                pathArc[i] = arc
            }
            if (ds > 0.0) {
                rawX[i] = x
                rawY[i] = y
            }
        }
        lastRawX = x
        lastRawY = y
        lastRawP = pressure
        pointCount = i + 1
        snapshot = null

        extendTangents()
        freezeHeadIfFilled()
        extendNib()
        rebuildTail()
    }

    /**
     * Tangents by finite difference, exactly as the batch build takes them. Only the last one is a
     * one-sided difference, so each new sample turns the previous last into an interior point and
     * fixes it for good. [carryTx]/[carryTy] hold the last usable direction entering the first
     * point still in play, which is what a run of coincident samples falls back on.
     */
    private fun extendTangents() {
        val n = pointCount
        if (n < 2) return
        var lx = carryTx
        var ly = carryTy
        for (i in tanDone until n) {
            val dx: Double
            val dy: Double
            when (i) {
                0 -> { dx = sx[1] - sx[0]; dy = sy[1] - sy[0] }
                n - 1 -> { dx = sx[i] - sx[i - 1]; dy = sy[i] - sy[i - 1] }
                else -> { dx = sx[i + 1] - sx[i - 1]; dy = sy[i + 1] - sy[i - 1] }
            }
            val len = hypot(dx, dy)
            if (len < StrokeEngine.MIN_TANGENT_LEN) {
                tx[i] = lx
                ty[i] = ly
            } else {
                tx[i] = dx / len
                ty[i] = dy / len
                lx = tx[i]
                ly = ty[i]
            }
            if (i == n - 2) {
                carryTx = lx
                carryTy = ly
            }
        }
        tanDone = n - 1
    }

    /**
     * The calligraphy head, decided once. Until the stroke's smoothed travel first reaches
     * [headLen] there is no heading to read and every point draws thin, so nothing settles; the
     * sample that fills it fixes both the direction and the run of points pinned to it.
     */
    private fun freezeHeadIfFilled() {
        if (ds <= 0.0 || headK >= 0) return
        if (arc < headLen) return
        val k = pointCount - 1
        headK = k
        headDir = StrokeEngine.headDirectionAt(sx, sy, k)
        for (i in 0..k) nib[i] = headDir
        nibValue = headDir
        nibDone = k + 1
    }

    /** Re-run the nib from its last final value: the rate, and any reversal the newest samples confirm or rule out. */
    private fun extendNib() {
        if (ds <= 0.0 || headK < 0) return
        val n = pointCount
        // A point's nib is final once no reversal at or before it can still be confirmed or ruled out.
        var f = nibDone
        while (f + 1 < n && rawCum[n - 1] > rawCum[f + 1] + reversalWait) f++
        var d = nibValue
        for (i in nibDone until n) {
            if (i == f) nibValue = d
            d = StrokeEngine.nibStep(d, ty[i], stepAlong(i), openLen, closeLen)
            d = StrokeEngine.backfill(d, rawX, rawY, rawCum, n, i, reversalWait, turnScratch)
            nib[i] = d
        }
        nibDone = f
    }

    /** Travel along the smoothed centreline into point [i]; what the nib measures its rate against. */
    private fun stepAlong(i: Int): Double =
        if (i == 0) 0.0 else hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])

    /**
     * Recompute every point from [settledCount] to the nib, then move the settled mark up to
     * whatever the rules now allow. This is the only loop that runs per frame, and its length is
     * bounded by the lookahead the pen needs rather than by the stroke.
     */
    private fun rebuildTail() {
        val n = pointCount
        val from = settledCount
        if (timed) refreshSpeed(n)
        val target = settleTarget()

        val held = holdEnds && pressureEnabled
        blurScratch[0] = blurCursor[0]
        for (i in from until n) {
            if (i == target) blurCursor[0] = blurScratch[0]
            // A lone sample is a dot: no tangent, no nib, and the pure-pressure half-width.
            val dir = when {
                n == 1 -> 0.0
                ds <= 0.0 -> ty[i]
                headK < 0 -> -1.0
                else -> nib[i].coerceIn(-1.0, StrokeEngine.DOT_DIR_Y)
            }
            val pressure = if (held) StrokeEngine.heldPressureAt(sp, n, i) else sp[i]
            var h = StrokeEngine.halfWidth(baseWidth, pressureEnabled, m, ds, pressure, dir)
            if (timed) h *= StrokeEngine.blurAt(fSpeed, pathArc, n, i, sigma, blurScratch)
            if (n == 1) {
                halfWidths[0] = h.toFloat()
                centerline[0] = sx[0].toFloat()
                centerline[1] = sy[0].toFloat()
            } else {
                StrokeEngine.writePoint(
                    centerline, halfWidths, leftRail, rightRail, i, sx[i], sy[i], tx[i], ty[i], h,
                )
            }
        }
        for (i in from until target) {
            val x = cx(i)
            val y = cy(i)
            val r = hw(i)
            if (x - r < minX) minX = x - r
            if (x + r > maxX) maxX = x + r
            if (y - r < minY) minY = y - r
            if (y + r > maxY) maxY = y + r
        }
        settledCount = target
    }

    /** Recompute the speed factors whose windows still reach the newest sample, and move [fFinal] past the rest. */
    private fun refreshSpeed(n: Int) {
        val tauN = tau[n - 1]
        var jf = fFinal
        if (tauN > 2.0 * StrokeEngine.SPEED_WINDOW_MS) {
            while (jf < n - 1 && tau[jf] < tauN - StrokeEngine.SPEED_WINDOW_MS) jf++
        }
        speedScratch[0] = speedCursor[0]
        speedScratch[1] = speedCursor[1]
        for (j in fFinal until n) {
            if (j == jf) {
                speedCursor[0] = speedScratch[0]
                speedCursor[1] = speedScratch[1]
            }
            val v = StrokeEngine.windowSpeed(tau, rawCum, n, j, speedScratch)
            fSpeed[j] = StrokeEngine.speedFactor(v, speedStrength, speedTop, speedScale)
        }
        fFinal = jf
    }

    /**
     * How many leading points can no longer change, as the tightest of the pen's lookaheads. Each
     * rule is stated as the first index it still has a claim on; the settled mark is the smallest.
     */
    private fun settleTarget(): Int {
        val n = pointCount
        if (n < 2) return 0
        // The newest point's tangent is one-sided, and the next sample will rewrite it.
        var s = n - 1
        if (holdEnds && pressureEnabled) {
            // The hold's window is min(CAP_HOLD_SAMPLES, (n-1)/2) wide at each end, so until it
            // reaches full width the head it covers is still moving too.
            if (n < 2 * StrokeEngine.CAP_HOLD_SAMPLES + 1) return 0
            s = min(s, n - StrokeEngine.CAP_HOLD_SAMPLES)
        }
        // Calligraphy pins every point to the head until the head exists, then rewrites them once.
        if (ds > 0.0 && headK < 0) return 0
        if (ds > 0.0) s = min(s, nibDone)
        if (timed) {
            // The head's window is clamped to the stroke's first span, so nothing is final until that span is past.
            if (tau[n - 1] <= 2.0 * StrokeEngine.SPEED_WINDOW_MS) return 0
            // A point is final once its blur reaches no speed factor that can still change.
            var k = settledCount
            if (sigma > 0.0) {
                val edge = pathArc[fFinal]
                val reach = StrokeEngine.SPEED_BLUR_REACH * sigma
                while (k < n && pathArc[k] + reach < edge) k++
            } else {
                k = max(k, fFinal)
            }
            s = min(s, k)
        }
        return max(s, 0)
    }

    private fun ensure(capacity: Int) {
        if (sx.size >= capacity) return
        var c = if (sx.isEmpty()) INITIAL_CAPACITY else sx.size
        while (c < capacity) c *= 2
        sx = sx.copyOf(c)
        sy = sy.copyOf(c)
        sp = sp.copyOf(c)
        tx = tx.copyOf(c)
        ty = ty.copyOf(c)
        if (timed) {
            times = times.copyOf(c)
            tau = tau.copyOf(c)
            fSpeed = fSpeed.copyOf(c)
        }
        if (timed || ds > 0.0) {
            rawCum = rawCum.copyOf(c)
            pathArc = pathArc.copyOf(c)
        }
        if (ds > 0.0) {
            rawX = rawX.copyOf(c)
            rawY = rawY.copyOf(c)
        }
        if (ds > 0.0) nib = nib.copyOf(c)
        centerline = centerline.copyOf(2 * c)
        halfWidths = halfWidths.copyOf(c)
        leftRail = leftRail.copyOf(2 * c)
        rightRail = rightRail.copyOf(2 * c)
    }

    companion object {
        private val EMPTY_D = DoubleArray(0)
        private val EMPTY_F = FloatArray(0)

        private const val INITIAL_CAPACITY = 64

        /**
         * Whether a stroke of this style can be grown rather than rebuilt. The taper pen cannot:
         * [StrokeEngine.taperFactors] scales the whole profile by the total arc, so a point drawn
         * at pen-down is still changing width at pen-up. A straight-line tool cannot either, but
         * for the opposite reason — it holds two samples and replaces the second, so there is
         * nothing to grow and nothing to gain.
         */
        fun supports(taperEnabled: Boolean, straight: Boolean): Boolean = !taperEnabled && !straight
    }
}
