package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import kotlin.math.max

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

    /** Builds [StrokeGeometry] from [samples] and the four style fields. */
    fun build(
        samples: List<Sample>,
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
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

        // 5–7. Half-widths, normals, and the two ribbon edges.
        val left = ArrayList<Pt>(n)
        val right = ArrayList<Pt>(n)
        val halfWidths = ArrayList<Double>(n)
        for (i in 0 until n) {
            val t = tangents[i]
            val h = hw(i, t.y)
            halfWidths.add(h)
            val normal = Pt(-t.y, t.x) // tangent rotated 90°, already unit length
            left.add(centers[i] - normal * h)
            right.add(centers[i] + normal * h)
        }

        // 8. Outline = left in order + right reversed (single closed polygon).
        val outline = ArrayList<Pt>(2 * n)
        outline.addAll(left)
        for (i in right.indices.reversed()) outline.add(right[i])

        // 9. Caps from the pure-pressure half-width at the first/last points.
        val caps = listOf(Cap(centers[0], hw(0, 0.0)), Cap(centers[n - 1], hw(n - 1, 0.0)))

        return StrokeGeometry(
            outline = if (outline.size >= 3) outline else emptyList(),
            caps = caps,
            centerline = centers,
            halfWidths = halfWidths,
        )
    }
}
