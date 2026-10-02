package com.xnotes.core.pdf

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Numbers the way a PDF content stream wants them: plain decimals with no exponent and no
 * trailing zeros, so `12.500` is written `12.5` and `-0.0001` rounds to `0`. Locale-free,
 * since a content stream is ASCII whatever language the device is set to.
 */
object PdfNumbers {

    private val POW10 = LongArray(7).also { p -> p[0] = 1; for (i in 1 until p.size) p[i] = p[i - 1] * 10 }

    /** [v] rounded to [decimals] places (0..6). Non-finite values are written as 0. */
    fun format(v: Double, decimals: Int = 3): String = StringBuilder(12).also { append(it, v, decimals) }.toString()

    /** [format] appended to [sb], for writers that build whole operator lines. */
    fun append(sb: StringBuilder, v: Double, decimals: Int = 3) {
        val d = decimals.coerceIn(0, POW10.size - 1)
        if (!v.isFinite()) {
            sb.append('0')
            return
        }
        val scale = POW10[d]
        val scaled = (abs(v) * scale).roundToLong()
        if (scaled == 0L) {
            sb.append('0')
            return
        }
        if (v < 0) sb.append('-')
        sb.append(scaled / scale)
        var frac = scaled % scale
        if (frac == 0L) return
        var digits = d
        while (frac % 10 == 0L) {
            frac /= 10
            digits--
        }
        sb.append('.')
        val s = frac.toString()
        repeat(digits - s.length) { sb.append('0') }
        sb.append(s)
    }
}
