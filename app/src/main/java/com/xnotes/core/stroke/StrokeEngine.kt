package com.xnotes.core.stroke

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw stylus samples into a smooth, variable-width ink ribbon (spec 03).
 * Pure, deterministic and unit-tested against the spec's conformance vectors.
 * All math runs in doubles; only the packed [StrokeGeometry] output is floats.
 */
object StrokeEngine {
    /** EMA low-pass smoothing factor (1.0 = passthrough, ->0 = heavy lag). Per *sample*: the
     *  ribbon smooths per unit of travel instead (see [emaByArc] and [SMOOTH_LEN]), and this is
     *  what that is tuned to reproduce at [REFERENCE_SPACING]. */
    const val ALPHA = 0.5

    /** Below this difference length a sample is degenerate; reuse last tangent. */
    const val MIN_TANGENT_LEN = 1e-6

    /** Below this travel a step carries no arc for [emaByArc] to integrate over. */
    const val MIN_STEP = 1e-9

    /** Floor on the calligraphic direction term so width stays positive. */
    const val MIN_DIRECTION = 0.1

    /** Steepness of the pressure response S-curve (see [logisticEase]). Raw stylus
     *  pressure is reshaped by a logistic before it sets the width: the light and hard
     *  ends move width gently, the mid-range moves it fast, so the small pressure swings
     *  of normal writing produce more visible width variation. 0 keeps the old linear
     *  response; higher = a sharper S. */
    const val PRESSURE_CURVE_K = 8.0

    /** Sample spacing (content px) the smoothing lengths below are tuned at: about what a stylus
     *  reports while writing at normal speed at 100% zoom, through the capture gate. */
    const val REFERENCE_SPACING = 1.5

    /** Low-pass length (content px) for position and pressure: the distance [emaByArc] leaves the
     *  smoothed centreline trailing the raw path by, whatever the spacing. Set to the lag the
     *  per-sample [ALPHA] produced at [REFERENCE_SPACING], `d · (1 - alpha) / alpha`, so ink drawn
     *  at 100% zoom looks as it always has. */
    val SMOOTH_LEN = REFERENCE_SPACING * (1.0 - ALPHA) / ALPHA

    /** Calligraphy pen: the travel that decides the stroke's **head**, the one stretch no causal
     *  rule can judge, because at pen-down the samples that would tell a jitter from a genuinely
     *  broad opening have not arrived yet. Every sample inside it is drawn at the direction of the
     *  chord across it (see [headDirection]). Long enough to outvote a pen-down flick, short enough
     *  that a stroke which really starts broad opens broad. It is the dot threshold too: a finished
     *  stroke that never fills the window is a tap. The tail borrows it as well, as the travel a
     *  widening has to hold before the pen lifts to count (see [capTail]). */
    const val HEAD_LEN = 8.0

    /** Calligraphy pen: the travel the nib takes to widen across its whole range, thin face to broad
     *  (see [nibDirection]). A rate, not a threshold: a stray sample only buys its own fraction of
     *  it, while a real downstroke reaches full width within a letter's height. */
    const val OPEN_LEN = 8.0

    /** [OPEN_LEN]'s counterpart for thinning, so the two directions can be tuned apart: ink that
     *  swells too eagerly reads as a blot, ink that thins too slowly reads as a smear. */
    const val CLOSE_LEN = 8.0

    /** The nib's arc constants are hand gestures, so they are quoted at 100% zoom and scaled by the
     *  stroke's [smoothScale] to the page. Without it a nib drawn at 4x had to be dragged four times
     *  as far across the glass before it would thicken, since the page it was writing on was a
     *  quarter the size. */
    fun headLen(smoothScale: Double): Double = HEAD_LEN * max(smoothScale, 0.0)

    /** [OPEN_LEN] at the stroke's draw zoom; see [headLen]. */
    fun openLen(smoothScale: Double): Double = OPEN_LEN * max(smoothScale, 0.0)

    /** [CLOSE_LEN] at the stroke's draw zoom; see [headLen]. */
    fun closeLen(smoothScale: Double): Double = CLOSE_LEN * max(smoothScale, 0.0)

    /** Calligraphy pen: the direction-y a dot is built at. Past the broad face's 1.0 on purpose,
     *  so a dot lands slightly bigger than the thickest line and reads as a deliberate mark. */
    const val DOT_DIR_Y = 1.5

    /** Speed pen: the default top speed in dp/ms, where the line is thinnest (about 10 cm/s of hand travel). */
    const val SPEED_HI = 0.6

    /** Speed pen: half the moving time (ms) the nib's speed is measured over around each sample. */
    const val SPEED_WINDOW_MS = 25.0

    /** Speed pen: a step crossed slower than this fraction of the top speed counts as the pen holding still. */
    const val SPEED_FLOOR = 0.1

    /** Speed pen: the default blur of the width along the path, in pen widths (the Gaussian's sigma). */
    const val DEFAULT_SPEED_SMOOTHING = 0.25

    /** Speed pen: how many sigmas the blur reaches either side of a point. */
    const val SPEED_BLUR_REACH = 3.0

    /** Taper pen: strokes shorter than this arc are left un-tapered, so a quick tick doesn't
     *  collapse to nothing. Quoted at 100% zoom and scaled by the stroke's draw zoom, since what
     *  makes a tick a tick is how far the hand went, not how much page it landed on. */
    const val TAPER_MIN_LEN = 8.0

    /** Pens that hold their ends ([holdEndPressure]) do so over this many samples at each end,
     *  enough to cover the EMA pressure ramp so the swept end disc meets the line at the body width. */
    const val CAP_HOLD_SAMPLES = 4

    /** Taper falloff shape: the tail ease is the [logisticEase] sigmoid clipped to its
     *  `[TAPER_TAIL, 1 - TAPER_TAIL]` band, since a true sigmoid only reaches 0 and 1 at +/-inf;
     *  the clipped band is then stretched back to a real point and full width. A smaller tail
     *  hugs the rails harder: a longer thin hold near the tip, then a quicker opening, than the
     *  old cubic smoothstep. [TAPER_CURVE_K] is the logistic steepness that spans exactly that
     *  band (sigma(+-k/2) = 1 - TAPER_TAIL / TAPER_TAIL). */
    const val TAPER_TAIL = 0.01
    val TAPER_CURVE_K = 2.0 * ln((1.0 - TAPER_TAIL) / TAPER_TAIL)

    /** Holds the pen/highlighter's first/last [CAP_HOLD_SAMPLES] samples up to the settled pressure
     *  just inside each end (in place), so a light pen-down/up can't shrink the swept end disc
     *  thinner than the line. Only raises width, never lowers it, so the heavier middle and any
     *  deliberate mid-stroke pressure dip are untouched. The window halves on very short strokes so
     *  head and tail can't cross. A light lift-off is the same signal as a pinch, so these pens end
     *  full and round rather than easing to a thin tip. */
    private fun holdEndPressure(p: DoubleArray) {
        val n = p.size
        val w = min(CAP_HOLD_SAMPLES, (n - 1) / 2)
        if (w < 1) return
        val headFloor = p[w]
        for (i in 0 until w) if (p[i] < headFloor) p[i] = headFloor
        val tailFloor = p[n - 1 - w]
        for (i in n - w until n) if (p[i] < tailFloor) p[i] = tailFloor
    }

    /** One-pole IIR low-pass (exponential moving average). */
    fun ema(values: DoubleArray, alpha: Double = ALPHA): DoubleArray {
        if (values.isEmpty()) return values
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
        }
        return out
    }

    /** [ema] over a boxed list — the spec-vector form; [build] runs on the array one. */
    fun ema(values: List<Double>, alpha: Double = ALPHA): List<Double> =
        ema(values.toDoubleArray(), alpha).asList()

    /**
     * The same one-pole low-pass as [ema], measured in travel rather than in samples: the
     * continuous filter `dy/ds = (x(s) - y) / lambda` over arc length `s`, integrated exactly
     * across each step with the input read as a straight line between the two samples. [steps]
     * holds each sample's distance from the one before it; `steps[0]` is unused.
     *
     * This is what lets a committed stroke match the wet one. Pen-up sample reduction
     * ([StrokeSimplify]) changes the spacing, and a fixed per-sample factor turns that into a
     * different curve: its lag is one sample spacing, so a thinned stroke trails less and cuts its
     * corners deeper than the one that was drawn. Here the lag is [lambda] whatever the spacing,
     * so dropping a sample leaves the curve where it was. It also makes the ink independent of the
     * pen's report rate, and of how fast the hand was moving when the samples were spaced out.
     *
     * Reading the input as a line across the step rather than as a constant is what buys the last
     * of it. A constant would hold each sample over the whole step it ends, which biases the lag by
     * half a spacing, and half a spacing is exactly the quantity the reduction changes.
     */
    fun emaByArc(values: DoubleArray, steps: DoubleArray, lambda: Double): DoubleArray {
        if (values.isEmpty()) return values
        if (lambda <= 0.0) return values.copyOf() // no smoothing length: pass the samples through
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) {
            out[i] = emaStep(out[i - 1], values[i - 1], values[i], steps[i], lambda)
        }
        return out
    }

    /** One step of [emaByArc]: the filter carried across a step of [d] travel. The whole filter is
     *  this line applied in order, which is what lets [WetRibbon] extend it a sample at a time. */
    internal fun emaStep(
        prevOut: Double,
        prevIn: Double,
        curIn: Double,
        d: Double,
        lambda: Double,
    ): Double {
        if (lambda <= 0.0) return curIn
        if (d <= MIN_STEP) return prevOut // the pen did not move: no arc to integrate over
        val decay = exp(-d / lambda)
        val slope = (curIn - prevIn) * lambda * (1.0 - decay) / d
        return decay * prevOut + curIn - decay * prevIn - slope
    }

    /**
     * Half-width at a point (spec 03 step 5), given smoothed [pressure] and the
     * tangent's y-component [ty]. The pure-pressure half-width (caps and the
     * single-sample dot) uses `ty = 0`.
     */
    fun halfWidth(
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        pressure: Double,
        ty: Double,
    ): Double {
        val pEff = if (pressureEnabled) logisticEase(pressure, PRESSURE_CURVE_K) else 1.0
        val wBase = baseWidth * (m + (1 - m) * pEff)
        val direction = max(1 + ds * ty, MIN_DIRECTION)
        return wBase * direction / 2.0
    }

    /**
     * Normalized logistic S-curve on `[0, 1]`, centred at 0.5 and rescaled so the endpoints
     * are exact (`0 -> 0`, `1 -> 1`) while only the middle bends. [k] sets the steepness: the
     * curve spans the logistic's `[sigma(-k/2), sigma(k/2)]` band, so a larger [k] both steepens
     * the middle and clips the rails nearer 0 and 1. `k <= 0` is the identity (a linear ramp).
     * Shared by the pressure response ([PRESSURE_CURVE_K]) and the taper ease ([TAPER_CURVE_K]).
     */
    fun logisticEase(x: Double, k: Double): Double {
        if (k <= 0.0) return x
        val lo = 1.0 / (1.0 + exp(k * 0.5))
        val hi = 1.0 / (1.0 + exp(-k * 0.5))
        val raw = 1.0 / (1.0 + exp(-k * (x - 0.5)))
        return (raw - lo) / (hi - lo)
    }

    /** Hermite smoothstep: 0 below [lo], 1 above [hi], an S-curve between. */
    internal fun smoothstep(lo: Double, hi: Double, x: Double): Double {
        if (hi <= lo) return if (x >= hi) 1.0 else 0.0
        val t = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** The speed pen's width factor per sample, exactly as [build] applies it; a stroke keeps these once the pen lifts. */
    fun speedWidths(
        rawX: DoubleArray,
        rawY: DoubleArray,
        rawT: DoubleArray?,
        baseWidth: Double,
        speedStrength: Double,
        speedTop: Double,
        speedSmoothing: Double,
        speedScale: Double,
        smooth: Boolean,
        smoothScale: Double,
    ): DoubleArray {
        val n = rawX.size
        val steps = DoubleArray(n)
        for (i in 1 until n) steps[i] = hypot(rawX[i] - rawX[i - 1], rawY[i] - rawY[i - 1])
        val smoothLen = SMOOTH_LEN * max(smoothScale, 0.0)
        val sx = if (smooth) emaByArc(rawX, steps, smoothLen) else rawX
        val sy = if (smooth) emaByArc(rawY, steps, smoothLen) else rawY
        val path = DoubleArray(n)
        for (i in 1 until n) path[i] = path[i - 1] + hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
        return speedFactorsOver(rawT, steps, path, n, speedStrength, speedTop, speedSmoothing * baseWidth, speedScale)
    }

    /** [speedWidths] over worked-out channels: raw travel per moving ms in a centred window, blurred along [path] by [sigma]. */
    internal fun speedFactorsOver(
        times: DoubleArray?,
        steps: DoubleArray,
        path: DoubleArray,
        n: Int,
        strength: Double,
        top: Double,
        sigma: Double,
        speedScale: Double,
    ): DoubleArray {
        val out = DoubleArray(n) { 1.0 }
        if (strength <= 0.0 || n < 2) return out
        val floor = floorSpeed(top, speedScale)
        val tau = DoubleArray(n)
        val cum = DoubleArray(n)
        for (i in 1 until n) {
            tau[i] = tau[i - 1] + movingStep(if (times == null) 0.0 else times[i] - times[i - 1], steps[i], floor)
            cum[i] = cum[i - 1] + steps[i]
        }
        val f = DoubleArray(n)
        val window = IntArray(2)
        for (i in 0 until n) f[i] = speedFactor(windowSpeed(tau, cum, n, i, window), strength, top, speedScale)
        val blur = IntArray(1)
        for (i in 0 until n) out[i] = blurAt(f, path, n, i, sigma, blur)
        return out
    }

    /** Content px per ms below which a step counts as the pen holding still. */
    internal fun floorSpeed(top: Double, speedScale: Double): Double =
        if (speedScale > 0.0) SPEED_FLOOR * top / speedScale else 0.0

    /** Moving time across one step: its elapsed ms, but never more than crossing it at [floor] would take. */
    internal fun movingStep(dt: Double, step: Double, floor: Double): Double {
        val t = max(dt, 0.0)
        return if (floor > 0.0) min(t, step / floor) else t
    }

    /** Raw travel per moving ms across ±[SPEED_WINDOW_MS] around sample [i], slid inward at the ends; [cursor] only moves on. */
    internal fun windowSpeed(tau: DoubleArray, cum: DoubleArray, n: Int, i: Int, cursor: IntArray): Double {
        val total = tau[n - 1]
        if (total <= 0.0) return 0.0
        var a = tau[i] - SPEED_WINDOW_MS
        var b = tau[i] + SPEED_WINDOW_MS
        if (a < 0.0) { b -= a; a = 0.0 }
        if (b > total) { a = max(a - (b - total), 0.0); b = total }
        if (b <= a) return 0.0
        return (arcAt(tau, cum, n, b, cursor, 1) - arcAt(tau, cum, n, a, cursor, 0)) / (b - a)
    }

    /** Raw arc at moving time [x], read linearly between the samples either side of it. */
    private fun arcAt(tau: DoubleArray, cum: DoubleArray, n: Int, x: Double, cursor: IntArray, end: Int): Double {
        var k = cursor[end]
        while (k < n - 1 && tau[k] < x) k++
        cursor[end] = k
        if (k == 0 || tau[k] < x) return cum[k]
        val t0 = tau[k - 1]
        val span = tau[k] - t0
        return if (span > 0.0) cum[k - 1] + (cum[k] - cum[k - 1]) * (x - t0) / span else cum[k]
    }

    /** The width factor a speed of [v] content px/ms draws at. */
    internal fun speedFactor(v: Double, strength: Double, top: Double, speedScale: Double): Double =
        1.0 - strength * smoothstep(0.0, top, v * speedScale)

    /** [f] blurred along [path] at point [i] by a Gaussian of [sigma], each sample weighted by the arc around it. */
    internal fun blurAt(f: DoubleArray, path: DoubleArray, n: Int, i: Int, sigma: Double, cursor: IntArray): Double {
        if (sigma <= 0.0) return f[i]
        val reach = SPEED_BLUR_REACH * sigma
        val at = path[i]
        var lo = cursor[0]
        while (lo < i && path[lo] < at - reach) lo++
        cursor[0] = lo
        val k = -0.5 / (sigma * sigma)
        var num = 0.0
        var den = 0.0
        var j = lo
        while (j < n) {
            val d = path[j] - at
            if (d > reach) break
            val own = (if (j > 0) path[j] - path[j - 1] else 0.0) + (if (j < n - 1) path[j + 1] - path[j] else 0.0)
            val w = exp(k * d * d) * own
            num += w * f[j]
            den += w
            j++
        }
        return if (den > 0.0) num / den else f[i]
    }

    /**
     * Per-point width multipliers in `[taperMinFactor, 1]` for the **taper pen**: the width eases
     * across the **whole stroke**, full at the head and easing down to [taperMinFactor] of full at
     * the tip (a sharp point when that is 0). Longer strokes just stretch the same profile. Returns
     * all-`1.0` when the stroke is too short ([TAPER_MIN_LEN]). [cum] is cumulative arc along the
     * smoothed centreline, from [build].
     */
    fun taperFactors(
        cum: DoubleArray,
        taperMinFactor: Double,
        smoothScale: Double = 1.0,
    ): DoubleArray {
        val n = cum.size
        val out = DoubleArray(n) { 1.0 }
        if (n < 2) return out
        val total = cum[n - 1]
        if (total < TAPER_MIN_LEN * max(smoothScale, 0.0)) return out
        for (i in 0 until n) {
            // Fractional arc position: 1 at the head, easing to 0 at the tip. The whole stroke is
            // the taper; the tip bottoms out at taperMinFactor of full instead of a sharp point.
            val edge = (total - cum[i]) / total
            out[i] = taperMinFactor + (1.0 - taperMinFactor) * logisticEase(edge, TAPER_CURVE_K)
        }
        return out
    }

    /**
     * The calligraphy nib's **head**: the direction of the straight line from where the pen landed to
     * where the stroke first passes [headLen] of travel, paired with the last sample inside that
     * window. Every sample in the window takes that one value.
     *
     * A chord and not a statistic over the samples, because it asks the only question the head has to
     * answer, which way the stroke actually went, and answers it in the units that matter. A wobble
     * at pen-down displaces the far end by exactly its own half-pixel and no more. A per-sample rule
     * cannot do that: a tangent is only as trustworthy as the distance it was measured over, and at
     * pen-down the smoothed centreline is still catching up, so its first steps run a tenth as long
     * as the ones just after and their tangents are mostly digitiser noise.
     *
     * What it gives up is the deliberate flick that goes out and comes back. The chord reads only the
     * net displacement, so a pen-down flick longer than half the window leaves a net heading its own
     * way and opens broader than it should. Under half the window, which is the ordinary case, the
     * run that follows still wins the net.
     *
     * Until the window fills there is no answer, so a live stroke draws the safe value (thin) and is
     * rewritten once, when it fills. A *finished* stroke that never fills it is a dot and takes
     * [DOT_DIR_Y]. [sx], [sy] and [cum] are the smoothed centreline and its cumulative arc;
     * [headLen] is already scaled to the stroke's draw zoom.
     */
    private fun headDirection(
        sx: DoubleArray,
        sy: DoubleArray,
        cum: DoubleArray,
        headLen: Double,
        finished: Boolean,
    ): Pair<Double, Int> {
        val k = cum.indexOfFirst { it >= headLen }
        if (k < 0) return (if (finished) DOT_DIR_Y else -1.0) to cum.lastIndex
        val dx = sx[k] - sx[0]
        val dy = sy[k] - sy[0]
        val len = hypot(dx, dy)
        // A stroke that came back to where it started has no net direction to read, so take the safe
        // value rather than a heading made of rounding.
        return (if (len < MIN_TANGENT_LEN) -1.0 else dy / len) to k
    }

    /**
     * The calligraphy nib's direction channel: a slew limiter over the raw heading. Widening is
     * earned over [openLen] of travel and thinning is spent over [closeLen], so the width can never
     * change faster than the writer has earned. This bounds the *rate*, not the duration: a short
     * heading change is flattened in proportion to how sharp it is, and a gentle one passes through.
     *
     * The channel spans 2.0 (thin face -1 to broad face +1), so the per-sample cap is
     * `step · 2 / rate`, and travelling exactly [openLen] at full cap carries the width from one
     * extreme to the other. Nothing here reads time: a fast stroke reports sparser samples, each
     * step is longer, and each gets a proportionally larger allowance, so the same path draws the
     * same widths however fast it was written.
     *
     * Samples up to [holdUntil] are pinned to [seed], which is the head: a rate cannot decide the
     * start, having no travel behind it to measure. [steps] is travel along the smoothed centreline,
     * and both lengths are already scaled to the stroke's draw zoom.
     */
    /**
     * [headDirection]'s mirror at the other end, run once the pen has lifted. Across the stroke's
     * last [window] of travel the nib may thin but not widen: a lift-off skid and a downstroke that
     * began one sample ago are the same signal, and the stroke ended before either could hold it.
     * The last sample is the worst of it, since its tangent is one-sided and nothing follows to spend
     * the width back. Everything before the window keeps whatever it earned.
     *
     * A ceiling rather than the head's flat pin, because the tail is not short of information the way
     * the start is. Only the widening is unconfirmable. The thinning either side of it was measured
     * against real travel, so a stroke that curves out of the broad face still thins across the tail
     * exactly as it did before, and one that was already broad the whole way is untouched.
     *
     * Only for a *finished* stroke: while the pen is down the last sample is the pen itself, and
     * capping it would stop the ink widening at all.
     *
     * The ceiling is read at exactly [window] back from the end, between samples rather than at one,
     * so where the window opens does not depend on how densely the pen happened to report. Anchored
     * at a sample instead, the same gesture drawn faster would cap at a different width.
     */
    private fun capTail(dir: DoubleArray, cum: DoubleArray, window: Double) {
        val n = dir.size
        val start = cum[n - 1] - window
        var i = 1
        while (i < n - 1 && cum[i] < start) i++
        var ceiling = dir[i - 1]
        val span = cum[i] - cum[i - 1]
        if (start > cum[i - 1] && span > MIN_STEP) {
            ceiling += (dir[i] - dir[i - 1]) * (start - cum[i - 1]) / span
        }
        for (j in i until n) {
            if (dir[j] > ceiling) dir[j] = ceiling else ceiling = dir[j]
        }
    }

    private fun nibDirection(
        ty: DoubleArray,
        steps: DoubleArray,
        openLen: Double,
        closeLen: Double,
        seed: Double,
        holdUntil: Int,
    ): DoubleArray {
        val out = DoubleArray(ty.size)
        var d = seed
        for (i in ty.indices) {
            if (i <= holdUntil) {
                out[i] = seed
                continue
            }
            d = nibStep(d, ty[i], if (i == 0) 0.0 else steps[i], openLen, closeLen)
            out[i] = d
        }
        return out
    }

    /** One step of [nibDirection]'s slew limiter: [d] moved toward [target] by at most what [step]
     *  of travel has earned. Shared with [WetRibbon], which carries [d] forward sample by sample. */
    internal fun nibStep(
        d: Double,
        target: Double,
        step: Double,
        openLen: Double,
        closeLen: Double,
    ): Double {
        val rate = if (target > d) openLen else closeLen
        val limit = if (rate > 0.0) step * 2.0 / rate else Double.MAX_VALUE
        return d + (target - d).coerceIn(-limit, limit)
    }

    /**
     * The direction the calligraphy head is pinned to, and the last sample it covers, read off a
     * centreline that already fills [headLen]. [WetRibbon]'s incremental form of [headDirection]:
     * it asks the same question of a live stroke once, at the sample the window first fills, and
     * keeps the answer. Returns null while the window is still short.
     */
    internal fun headDirectionAt(
        sx: DoubleArray,
        sy: DoubleArray,
        k: Int,
    ): Double {
        val dx = sx[k] - sx[0]
        val dy = sy[k] - sy[0]
        val len = hypot(dx, dy)
        // A stroke that came back to where it started has no net direction to read, so take the safe
        // value rather than a heading made of rounding.
        return if (len < MIN_TANGENT_LEN) -1.0 else dy / len
    }

    /**
     * The end-pressure hold ([holdEndPressure]) read at one point instead of applied to the array:
     * the pressure a stroke of [n] samples draws point [i] at, given the smoothed channel [p].
     * [WetRibbon] uses it because the hold's tail window moves as the stroke grows, so the last few
     * points have to be asked again every sample while the ones before them stay put.
     */
    internal fun heldPressureAt(p: DoubleArray, n: Int, i: Int): Double {
        val w = min(CAP_HOLD_SAMPLES, (n - 1) / 2)
        if (w < 1) return p[i]
        if (i < w) return max(p[i], p[w])
        if (i >= n - w) return max(p[i], p[n - 1 - w])
        return p[i]
    }

    /**
     * Builds [StrokeGeometry] from [samples] and the style fields. [speedStrength]
     * and [taperEnabled] default to off, in which case the output is identical to
     * the four-field pen/calligraphy pipeline (spec 03 conformance).
     */
    fun build(
        samples: List<Sample>,
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        speedStrength: Double = 0.0,
        taperEnabled: Boolean = false,
        taperMinFactor: Double = 0.0,
        speedScale: Double = 1.0,
        smooth: Boolean = true,
        holdEnds: Boolean = false,
        finished: Boolean = true,
        smoothScale: Double = 1.0,
        speedTop: Double = SPEED_HI,
        speedSmoothing: Double = DEFAULT_SPEED_SMOOTHING,
    ): StrokeGeometry {
        val n = samples.size
        if (n == 0) return StrokeGeometry.EMPTY
        val rawX = DoubleArray(n)
        val rawY = DoubleArray(n)
        val rawP = DoubleArray(n)
        var timed = false
        var widths = true
        for (i in 0 until n) {
            val s = samples[i]
            rawX[i] = s.x
            rawY[i] = s.y
            rawP[i] = s.pressure
            if (s.t != 0.0) timed = true
            if (s.widthFactor <= 0.0) widths = false
        }
        val rawT = if (timed) DoubleArray(n) { samples[it].t } else null
        val rawF = if (widths) DoubleArray(n) { samples[it].widthFactor } else null
        return build(
            rawX, rawY, rawP, rawT, baseWidth, pressureEnabled, m, ds, speedStrength,
            taperEnabled, taperMinFactor, speedScale, smooth, holdEnds, finished, smoothScale,
            speedTop, speedSmoothing, rawF,
        )
    }

    /**
     * [build] over the caller's own primitive channels: [rawX]/[rawY]/[rawP] are one entry per
     * sample and [rawT] is the elapsed-ms channel, null when the ink carries no timing (everything
     * but the speed pen). [Stroke] stores its samples packed and calls this directly, so painting a
     * dense page never materializes a `Sample` per point.
     */
    fun build(
        rawX: DoubleArray,
        rawY: DoubleArray,
        rawP: DoubleArray,
        rawT: DoubleArray?,
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        speedStrength: Double = 0.0,
        taperEnabled: Boolean = false,
        taperMinFactor: Double = 0.0,
        speedScale: Double = 1.0,
        smooth: Boolean = true,
        holdEnds: Boolean = false,
        finished: Boolean = true,
        smoothScale: Double = 1.0,
        speedTop: Double = SPEED_HI,
        speedSmoothing: Double = DEFAULT_SPEED_SMOOTHING,
        rawF: DoubleArray? = null,
    ): StrokeGeometry {
        val n = rawX.size
        if (n == 0) return StrokeGeometry.EMPTY

        // Travel between consecutive samples: what the low-pass measures itself against, so the
        // smoothing is set by the path and not by how many samples describe it.
        val steps = DoubleArray(n)
        for (i in 1 until n) steps[i] = hypot(rawX[i] - rawX[i - 1], rawY[i] - rawY[i - 1])
        val smoothLen = SMOOTH_LEN * max(smoothScale, 0.0)

        // 2. Smooth each channel independently. Straight-line strokes skip the position low-pass
        //    so the ribbon spans the raw samples exactly (EMA would pull a 2-point line's far end
        //    toward the midpoint, leaving it short of the pointer).
        val sx = if (smooth) emaByArc(rawX, steps, smoothLen) else rawX
        val sy = if (smooth) emaByArc(rawY, steps, smoothLen) else rawY
        // The pens that hold their ends (pen, highlighter) land and lift light, so the swept end
        // disc would shrink to a thin tip; hold the body width out to each end so it meets the line
        // at full width. The other ribbon pens take their ends at the raw pressure.
        val sp = emaByArc(rawP, steps, smoothLen)
        if (holdEnds && pressureEnabled) holdEndPressure(sp)

        fun hw(i: Int, ty: Double) = halfWidth(baseWidth, pressureEnabled, m, ds, sp[i], ty)

        // 3. Single sample -> a filled dot: one swept disc at the pure-pressure half-width. A
        //    finished calligraphy tap takes the dot width (past the broad face) so it stays visible.
        if (n == 1) {
            val h = hw(0, if (finished && ds > 0.0) DOT_DIR_Y else 0.0)
            return StrokeGeometry(
                floatArrayOf(sx[0].toFloat(), sy[0].toFloat()),
                floatArrayOf(h.toFloat()),
            )
        }

        // 4. Per-point unit tangent via finite differences.
        var lastTx = 1.0
        var lastTy = 0.0
        val tx = DoubleArray(n)
        val ty = DoubleArray(n)
        for (i in 0 until n) {
            val dx: Double
            val dy: Double
            when (i) {
                0 -> { dx = sx[1] - sx[0]; dy = sy[1] - sy[0] }
                n - 1 -> { dx = sx[i] - sx[i - 1]; dy = sy[i] - sy[i - 1] }
                else -> { dx = sx[i + 1] - sx[i - 1]; dy = sy[i + 1] - sy[i - 1] }
            }
            val len = hypot(dx, dy)
            if (len < MIN_TANGENT_LEN) {
                tx[i] = lastTx
                ty[i] = lastTy
            } else {
                tx[i] = dx / len
                ty[i] = dy / len
                lastTx = tx[i]
                lastTy = ty[i]
            }
        }

        // Travel along the smoothed centreline and its running total: the path the ink follows, and
        // what every arc-length rule below is measured in. Only the nib, the taper and the speed blur read it.
        val speedOn = speedStrength > 0.0 && (rawF != null || rawT != null)
        val needArc = ds > 0.0 || taperEnabled || (speedOn && rawF == null)
        val dirSteps = DoubleArray(if (needArc) n else 0)
        val cum = DoubleArray(dirSteps.size)
        if (needArc) {
            for (i in 1 until n) {
                dirSteps[i] = hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
                cum[i] = cum[i - 1] + dirSteps[i]
            }
        }

        // Optional width multipliers: speed thins fast travel, taper points the ends. Neither pen is
        // the common one, so the arrays are built only for the strokes that use them.
        val sf = when {
            !speedOn -> null
            rawF != null -> rawF
            else -> speedFactorsOver(rawT, steps, cum, n, speedStrength, speedTop, speedSmoothing * baseWidth, speedScale)
        }
        val tf = if (taperEnabled) taperFactors(cum, taperMinFactor, smoothScale) else null

        // Calligraphy: the tangent-y that sets nib width, in three pieces. The head is decided once
        // over the first HEAD_LEN of travel and pinned flat across it (a pen-down jitter loses to the
        // run that follows it, and a finished stroke too short to fill the window is a dot). The body
        // is a slew limiter capping how fast the width may change per px travelled, OPEN_LEN to widen
        // and CLOSE_LEN to thin, so a stray sample only buys its own fraction of the range. The tail
        // is the head's mirror at pen-up: over the last HEAD_LEN the nib may thin but not widen,
        // since a lift-off skid ends before it can hold. Nothing here looks ahead past the two ends,
        // and nothing reads time. Orientation still follows the true tangent; only the width
        // magnitude is held back. A no-op when ds = 0.
        val dirY = if (ds > 0.0) {
            val window = headLen(smoothScale)
            val (head, holdUntil) = headDirection(sx, sy, cum, window, finished)
            val d = nibDirection(ty, dirSteps, openLen(smoothScale), closeLen(smoothScale), head, holdUntil)
            if (finished) capTail(d, cum, window)
            d
        } else null

        // 5–8. Half-widths, normals, and the two ribbon edges, packed straight into the output.
        // No separate end caps: the swept brush disc at each sample (the head and tail included)
        // already rounds every end and join, so [holdEnds] only shapes the end half-widths.
        val centerline = FloatArray(2 * n)
        val halfWidths = FloatArray(n)
        val leftRail = FloatArray(2 * n)
        val rightRail = FloatArray(2 * n)
        for (i in 0 until n) {
            // Clamped to [-1, DOT_DIR_Y], not to [-1, 1]: a dot is 1.5 on purpose, past the broad
            // face, so a cap at 1.0 would quietly shrink every tap.
            val dir = if (dirY != null) dirY[i].coerceIn(-1.0, DOT_DIR_Y) else ty[i]
            var h = hw(i, dir)
            if (sf != null) h *= sf[i]
            if (tf != null) h *= tf[i]
            writePoint(centerline, halfWidths, leftRail, rightRail, i, sx[i], sy[i], tx[i], ty[i], h)
        }
        return StrokeGeometry(centerline, halfWidths, leftRail, rightRail)
    }

    /**
     * One ribbon point written into the packed output arrays: its centre, its half-width, and the
     * rail vertex either side of it along the normal. Shared with [WetRibbon] so a live stroke's
     * growing ribbon and a finished stroke's rebuilt one are laid out by the same code.
     */
    internal fun writePoint(
        centerline: FloatArray,
        halfWidths: FloatArray,
        leftRail: FloatArray,
        rightRail: FloatArray,
        i: Int,
        x: Double,
        y: Double,
        tx: Double,
        ty: Double,
        h: Double,
    ) {
        halfWidths[i] = h.toFloat()
        centerline[2 * i] = x.toFloat()
        centerline[2 * i + 1] = y.toFloat()
        val nx = -ty // tangent rotated 90°, already unit length
        val ny = tx
        leftRail[2 * i] = (x - nx * h).toFloat()
        leftRail[2 * i + 1] = (y - ny * h).toFloat()
        rightRail[2 * i] = (x + nx * h).toFloat()
        rightRail[2 * i + 1] = (y + ny * h).toFloat()
    }
}
