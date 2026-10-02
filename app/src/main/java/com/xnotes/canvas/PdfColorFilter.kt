package com.xnotes.canvas

import com.xnotes.core.model.Rgba

/**
 * Composes the View menu's PDF colour filters (CSS filter semantics) into one 4x5 colour
 * matrix in android.graphics.ColorMatrix layout: row-major, offsets in the fifth column,
 * channel range 0..255. Pure math so it is JVM-testable; the platform layer wraps the
 * result in a real ColorMatrix.
 *
 * The two blend filters take a colour rather than a percentage. Blending a pixel with a
 * *constant* colour is affine per channel for both MULTIPLY and SCREEN, so they fold into
 * the same single matrix as the rest of the chain and cost nothing extra per pixel.
 */
object PdfColorFilter {

    /** Multiply by white is the identity, so white is this filter's "off". */
    val MULTIPLY_OFF = Rgba(255, 255, 255)

    /** Screen with black is the identity, so black is this filter's "off". */
    val SCREEN_OFF = Rgba(0, 0, 0)

    fun isIdentity(
        contrast: Int,
        invert: Int,
        brightness: Int,
        sepia: Int,
        multiply: Rgba = MULTIPLY_OFF,
        screen: Rgba = SCREEN_OFF,
    ): Boolean = contrast == 100 && invert == 0 && brightness == 100 && sepia == 0 &&
        multiply.rgbEquals(MULTIPLY_OFF) && screen.rgbEquals(SCREEN_OFF)

    /** The composed matrix: contrast, then invert, brightness, sepia, multiply, screen. */
    fun matrix(
        contrast: Int,
        invert: Int,
        brightness: Int,
        sepia: Int,
        multiply: Rgba = MULTIPLY_OFF,
        screen: Rgba = SCREEN_OFF,
    ): FloatArray {
        var m = IDENTITY
        if (contrast != 100) m = concat(contrastMatrix(contrast / 100f), m)
        if (invert != 0) m = concat(invertMatrix(invert / 100f), m)
        if (brightness != 100) m = concat(brightnessMatrix(brightness / 100f), m)
        if (sepia != 0) m = concat(sepiaMatrix(sepia / 100f), m)
        if (!multiply.rgbEquals(MULTIPLY_OFF)) m = concat(multiplyMatrix(multiply), m)
        if (!screen.rgbEquals(SCREEN_OFF)) m = concat(screenMatrix(screen), m)
        return m
    }

    /** [c] through matrix [m], as a pixel of that colour comes out. */
    fun apply(m: FloatArray, c: Rgba): Rgba {
        fun channel(row: Int): Int =
            (m[row * 5] * c.r + m[row * 5 + 1] * c.g + m[row * 5 + 2] * c.b + m[row * 5 + 3] * c.a + m[row * 5 + 4])
                .toInt().coerceIn(0, 255)
        return Rgba(channel(0), channel(1), channel(2), c.a)
    }

    /** Whether [m] turns black light, as an invert does: a multiply on the page then shows as a screen. */
    fun lightensBlack(m: FloatArray): Boolean = (m[4] + m[9] + m[14]) / 3f > 127.5f

    /** Alpha is meaningless for a blend colour, so only the channels are compared. */
    private fun Rgba.rgbEquals(o: Rgba): Boolean = r == o.r && g == o.g && b == o.b

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** CSS contrast(c): scale each channel about mid-grey. */
    private fun contrastMatrix(c: Float): FloatArray {
        val t = 127.5f * (1f - c)
        return floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** CSS invert(i): each channel interpolates toward its complement; i = 1 is a full invert. */
    private fun invertMatrix(i: Float): FloatArray {
        val s = 1f - 2f * i
        val t = 255f * i
        return floatArrayOf(
            s, 0f, 0f, 0f, t,
            0f, s, 0f, 0f, t,
            0f, 0f, s, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** CSS brightness(b): linear channel scale. */
    private fun brightnessMatrix(b: Float): FloatArray = floatArrayOf(
        b, 0f, 0f, 0f, 0f,
        0f, b, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** CSS sepia(s): the filter-effects sepia matrix interpolated with identity. CSS stops at
     *  s = 1; past it the same interpolation keeps going, pushing the warm tint further. */
    private fun sepiaMatrix(s: Float): FloatArray = floatArrayOf(
        1f - 0.607f * s, 0.769f * s, 0.189f * s, 0f, 0f,
        0.349f * s, 1f - 0.314f * s, 0.168f * s, 0f, 0f,
        0.272f * s, 0.534f * s, 1f - 0.869f * s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** MULTIPLY with constant [c]: out = in * c, so white stays white and the page takes the
     *  tint the way a highlighter stroke does. Per channel a plain scale. */
    private fun multiplyMatrix(c: Rgba): FloatArray = floatArrayOf(
        c.r / 255f, 0f, 0f, 0f, 0f,
        0f, c.g / 255f, 0f, 0f, 0f,
        0f, 0f, c.b / 255f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** SCREEN with constant [c]: out = 1 - (1 - in)(1 - c), so black takes the tint and white
     *  stays white — the inverse highlighter. Per channel a scale plus an offset. */
    private fun screenMatrix(c: Rgba): FloatArray = floatArrayOf(
        1f - c.r / 255f, 0f, 0f, 0f, c.r.toFloat(),
        0f, 1f - c.g / 255f, 0f, 0f, c.g.toFloat(),
        0f, 0f, 1f - c.b / 255f, 0f, c.b.toFloat(),
        0f, 0f, 0f, 1f, 0f,
    )

    /** a ∘ b: apply [b] first, then [a] (android ColorMatrix setConcat order). */
    private fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0 until 4) {
            val ar = r * 5
            for (c in 0 until 4) {
                var v = 0f
                for (k in 0 until 4) v += a[ar + k] * b[k * 5 + c]
                out[ar + c] = v
            }
            var off = a[ar + 4]
            for (k in 0 until 4) off += a[ar + k] * b[k * 5 + 4]
            out[ar + 4] = off
        }
        return out
    }
}

/**
 * The colour treatment a PDF page is rendered through: an optional page-wide matrix, and
 * whether embedded images are re-stamped raw over the filtered page (the View menu's
 * "don't filter images" setting) so they keep their original colours.
 */
class PdfPageFilter private constructor(
    val pageMatrix: FloatArray?,
    val stampImages: Boolean,
) {
    companion object {
        val NONE = PdfPageFilter(null, false)

        fun of(
            contrast: Int,
            invert: Int,
            brightness: Int,
            sepia: Int,
            multiply: Rgba = PdfColorFilter.MULTIPLY_OFF,
            screen: Rgba = PdfColorFilter.SCREEN_OFF,
            keepImages: Boolean,
        ): PdfPageFilter {
            if (PdfColorFilter.isIdentity(contrast, invert, brightness, sepia, multiply, screen)) return NONE
            return PdfPageFilter(
                PdfColorFilter.matrix(contrast, invert, brightness, sepia, multiply, screen),
                keepImages,
            )
        }
    }
}
