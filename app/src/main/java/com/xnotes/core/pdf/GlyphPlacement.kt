package com.xnotes.core.pdf

/**
 * Pins each glyph of a run to the x the layout gave it, as the numbers of a `TJ` array. A glyph
 * advances the pen by its own width; the number before the next glyph pulls the pen back by
 * `n / 1000` of the font size, so each adjustment is the gap between where the pen landed and
 * where the next glyph belongs. It is measured against the pen the *written* (rounded) numbers
 * produce, so rounding never accumulates along a line.
 */
object GlyphPlacement {

    /**
     * Adjustments for glyphs of [widths] (glyph space, 1000 units per em) whose left edges belong
     * at [targets] (px from the run's origin), in a font of [sizePx]. Entry `i` goes before glyph
     * `i`; entry 0 places the first glyph relative to the origin. Rounded to [decimals] places.
     */
    fun adjustments(widths: DoubleArray, targets: DoubleArray, sizePx: Double, decimals: Int = 1): DoubleArray {
        require(widths.size == targets.size)
        val out = DoubleArray(widths.size)
        if (sizePx <= 0.0) return out
        val unit = sizePx / 1000.0
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        var pen = 0.0
        for (i in widths.indices) {
            val adj = Math.round((pen - targets[i]) / unit * scale) / scale
            out[i] = adj
            pen -= adj * unit
            pen += widths[i] * unit
        }
        return out
    }
}
