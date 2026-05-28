package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt

/**
 * One captured stylus point, page-local; `pressure` in `[0, 1]`. [t] is the
 * milliseconds elapsed since the stroke's first sample (0 for that first one),
 * used only by velocity-aware tools (the speed pen); 0 everywhere else.
 */
data class Sample(val x: Double, val y: Double, val pressure: Double, val t: Double = 0.0) {
    val pos: Pt get() = Pt(x, y)
}

/** A round end-cap (head/tail or a single-sample dot). */
data class Cap(val center: Pt, val radius: Double)

/**
 * The geometry derived from a stroke's samples (spec 03). Rendering fills
 * [outline] (nonzero winding) **and** the [caps] discs in the ink colour, with
 * no outline pen.
 */
data class StrokeGeometry(
    val outline: List<Pt>,
    val caps: List<Cap>,
    val centerline: List<Pt>,
    val halfWidths: List<Double>,
) {
    /**
     * A concentric inner ribbon scaled to [frac] of this ribbon's half-widths about
     * the same centerline (1.0 reproduces [outline]). The neon white-hot core fills
     * this rather than the full outline, so the outer `1 − frac` of the tube keeps
     * its saturated ink colour. Empty when there's no ribbon (a single-sample dot)
     * or [frac] ≤ 0. [outline] is `left[0..n-1] ++ right[n-1..0]` about [centerline],
     * so each edge point is moved a fraction of the way back toward its centre.
     */
    fun coreOutline(frac: Double): List<Pt> {
        val n = centerline.size
        if (frac <= 0.0 || n < 2 || outline.size != 2 * n) return emptyList()
        val f = frac.coerceAtMost(1.0)
        val core = ArrayList<Pt>(2 * n)
        for (i in 0 until n) core.add(centerline[i] + (outline[i] - centerline[i]) * f)
        for (i in n - 1 downTo 0) core.add(centerline[i] + (outline[2 * n - 1 - i] - centerline[i]) * f)
        return core
    }

    companion object {
        val EMPTY = StrokeGeometry(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
