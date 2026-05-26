package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw stylus samples into a smooth, variable-width ink ribbon (spec 03).
 * Pure, deterministic and unit-tested against the spec's conformance vectors.
 */
object StrokeEngine {
    /** EMA low-pass smoothing factor (1.0 = passthrough, ->0 = heavy lag). */
    const val ALPHA = 0.5

    /** Below this difference length a sample is degenerate; reuse last tangent. */
    const val MIN_TANGENT_LEN = 1e-6

    /** Floor on the calligraphic direction term so width stays positive. */
    const val MIN_DIRECTION = 0.1

    /** Speed pen: dp/ms at/below which the line stays full width, and the speed
     *  at/above which it reaches its thinnest (≈1.25 and ≈5 in/s of hand travel).
     *  Measuring in dp — not page pixels — makes the effect independent of both zoom
     *  and screen density; see [speedFactors] and the per-stroke speed scale. */
    const val SPEED_LO = 0.2
    const val SPEED_HI = 0.8

    /** Speed pen: a heavier low-pass on the speed channel than [ALPHA] (position), so the
     *  width glides between thick and thin over ~1/this samples instead of snapping. */
    const val SPEED_ALPHA = 0.15

    /** Speed pen: minimum per-segment dt (ms) so a duplicate-timestamp pair can't
     *  divide by ~zero and spike the speed. */
    const val MIN_DT = 1.0

    /** Taper pen: strokes shorter than this (page px of arc length) are left
     *  un-tapered, so a quick tick doesn't collapse to nothing. */
    const val TAPER_MIN_LEN = 8.0

    /** Taper pen: floor on the per-end taper fraction (avoids a zero-width divide). */
    const val MIN_TAPER_FRAC = 0.02

    /** One-pole IIR low-pass (exponential moving average). */
    fun ema(values: List<Double>, alpha: Double = ALPHA): List<Double> {
        if (values.isEmpty()) return emptyList()
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
        }
        return out.asList()
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
        val pEff = if (pressureEnabled) pressure else 1.0
        val wBase = baseWidth * (m + (1 - m) * pEff)
        val direction = max(1 + ds * ty, MIN_DIRECTION)
        return wBase * direction / 2.0
    }

    /** Hermite smoothstep: 0 below [lo], 1 above [hi], an S-curve between. */
    private fun smoothstep(lo: Double, hi: Double, x: Double): Double {
        if (hi <= lo) return if (x >= hi) 1.0 else 0.0
        val t = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /**
     * Per-point width multipliers in `[1 − speedStrength, 1]` for the **speed pen**:
     * the faster the nib travels across the page, the thinner the line (ink has less
     * time to lay down). Speed is `‖Δcenter‖ · speedScale / Δt` in dp/ms from the
     * sample times, where [speedScale] (zoom ÷ density, captured at pen-down) converts
     * page pixels to dp so the effect is zoom- and device-independent. Smoothed with a
     * heavier low-pass than position. Returns all-`1.0` when off or the samples carry
     * no usable timing.
     */
    fun speedFactors(centers: List<Pt>, samples: List<Sample>, speedStrength: Double, speedScale: Double): List<Double> {
        val n = centers.size
        if (speedStrength <= 0.0 || n < 2) return List(n) { 1.0 }
        if (samples.last().t - samples.first().t <= 0.0) return List(n) { 1.0 }
        val raw = DoubleArray(n)
        for (i in 1 until n) {
            val dist = (centers[i] - centers[i - 1]).length() * speedScale
            val dt = max(samples[i].t - samples[i - 1].t, MIN_DT)
            raw[i] = dist / dt
        }
        raw[0] = raw[1]
        val speed = ema(raw.asList(), SPEED_ALPHA)
        return speed.map { 1.0 - speedStrength * smoothstep(SPEED_LO, SPEED_HI, it) }
    }

    /**
     * Per-point width multipliers in `[0, 1]` for the **taper pen**: the line eases
     * out of a point at each end and reaches full width in the middle, by arc-length
     * position. [taperAmount] in `(0, 1]` is the share of the stroke that tapers,
     * split across the two ends. Returns all-`1.0` when off or the stroke is too short.
     */
    fun taperFactors(centers: List<Pt>, taperAmount: Double): List<Double> {
        val n = centers.size
        if (taperAmount <= 0.0 || n < 2) return List(n) { 1.0 }
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + (centers[i] - centers[i - 1]).length()
        val total = cum[n - 1]
        if (total < TAPER_MIN_LEN) return List(n) { 1.0 }
        val frac = (taperAmount * 0.5).coerceIn(MIN_TAPER_FRAC, 0.5)
        return (0 until n).map { i ->
            val u = cum[i] / total
            val edge = (min(u, 1.0 - u) / frac).coerceIn(0.0, 1.0)
            edge * edge * (3 - 2 * edge)
        }
    }

    /**
     * Builds [StrokeGeometry] from [samples] and the style fields. [speedStrength]
     * and [taperAmount] default to off, in which case the output is identical to
     * the four-field pen/calligraphy pipeline (spec 03 conformance).
     */
    fun build(
        samples: List<Sample>,
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        speedStrength: Double = 0.0,
        taperAmount: Double = 0.0,
        speedScale: Double = 1.0,
    ): StrokeGeometry {
        val n = samples.size
        if (n == 0) return StrokeGeometry.EMPTY

        // 2. Smooth each channel independently.
        val sx = ema(samples.map { it.x })
        val sy = ema(samples.map { it.y })
        val sp = ema(samples.map { it.pressure })
        val centers = (0 until n).map { Pt(sx[it], sy[it]) }

        fun hw(i: Int, ty: Double) = halfWidth(baseWidth, pressureEnabled, m, ds, sp[i], ty)

        // 3. Single sample -> a filled dot (pure-pressure half-width, no direction).
        if (n == 1) {
            val h = hw(0, 0.0)
            return StrokeGeometry(emptyList(), listOf(Cap(centers[0], h)), centers, listOf(h))
        }

        // 4. Per-point unit tangent via finite differences.
        var lastGood = Pt(1.0, 0.0)
        val tangents = ArrayList<Pt>(n)
        for (i in 0 until n) {
            val diff = when (i) {
                0 -> centers[1] - centers[0]
                n - 1 -> centers[i] - centers[i - 1]
                else -> centers[i + 1] - centers[i - 1]
            }
            val len = diff.length()
            val t = if (len < MIN_TANGENT_LEN) lastGood else (diff / len).also { lastGood = it }
            tangents.add(t)
        }

        // Optional width multipliers: speed thins fast travel, taper points the ends.
        val sf = speedFactors(centers, samples, speedStrength, speedScale)
        val tf = taperFactors(centers, taperAmount)

        // 5–7. Half-widths, normals, and the two ribbon edges.
        val left = ArrayList<Pt>(n)
        val right = ArrayList<Pt>(n)
        val halfWidths = ArrayList<Double>(n)
        for (i in 0 until n) {
            val t = tangents[i]
            val h = hw(i, t.y) * sf[i] * tf[i]
            halfWidths.add(h)
            val normal = Pt(-t.y, t.x) // tangent rotated 90°, already unit length
            left.add(centers[i] - normal * h)
            right.add(centers[i] + normal * h)
        }

        // 8. Outline = left in order + right reversed (single closed polygon).
        val outline = ArrayList<Pt>(2 * n)
        outline.addAll(left)
        for (i in right.indices.reversed()) outline.add(right[i])

        // 9. Caps from the pure-pressure half-width at the first/last points,
        //    carrying the same speed/taper multiplier so tapered ends come to a point.
        val caps = listOf(
            Cap(centers[0], hw(0, 0.0) * sf[0] * tf[0]),
            Cap(centers[n - 1], hw(n - 1, 0.0) * sf[n - 1] * tf[n - 1]),
        )

        return StrokeGeometry(
            outline = if (outline.size >= 3) outline else emptyList(),
            caps = caps,
            centerline = centers,
            halfWidths = halfWidths,
        )
    }
}
