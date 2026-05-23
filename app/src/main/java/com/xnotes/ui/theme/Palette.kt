package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba
import kotlin.math.abs

/**
 * The design tokens for one appearance (spec 11 §1). An [accent] overrides the
 * palette's shipped accent and survives a dark/light switch.
 */
data class Palette(
    val bg: Rgba,
    val panel: Rgba,
    val paper: Rgba,
    val paperBorder: Rgba,
    val accent: Rgba,
    val accentDim: Rgba,
    val border: Rgba,
    val text: Rgba,
    val textDim: Rgba,
    val surface: Rgba,
    val surfaceHi: Rgba,
    val menuBg: Rgba,
    val isDark: Boolean,
) {
    /** The accent lightened ~28% (hover highlights). */
    val accentLight: Rgba get() = ColorMath.lighten(accent, 0.28)

    fun accentAlpha(alpha: Int): Rgba = accent.withAlpha(alpha)

    companion object {
        val DEFAULT_ACCENT = Rgba(0, 230, 118) // #00e676
        val DISABLED_ICON = Rgba(58, 58, 58)    // #3a3a3a

        fun dark(accent: Rgba = DEFAULT_ACCENT): Palette = Palette(
            bg = hex(0x0a0a0a),
            panel = hex(0x0d0d0d),
            paper = hex(0x161616),
            paperBorder = hex(0x2c2c2c),
            accent = accent,
            accentDim = ColorMath.dim(accent),
            border = hex(0x242424),
            text = hex(0xc8c8c8),
            textDim = hex(0x6f6f6f),
            surface = hex(0x151515),
            surfaceHi = hex(0x1c1c1c),
            menuBg = hex(0x111111),
            isDark = true,
        )

        fun light(accent: Rgba = Rgba(0, 161, 82)): Palette = Palette(
            bg = hex(0xe8e8e8),
            panel = hex(0xf4f4f4),
            paper = hex(0xffffff),
            paperBorder = hex(0xc4c4c4),
            accent = accent,
            accentDim = ColorMath.dim(accent),
            border = hex(0xd0d0d0),
            text = hex(0x1c1c1c),
            textDim = hex(0x6f6f6f),
            surface = hex(0xe4e4e4),
            surfaceHi = hex(0xd8d8d8),
            menuBg = hex(0xfbfbfb),
            isDark = false,
        )

        fun forAppearance(dark: Boolean, accent: Rgba): Palette =
            if (dark) dark(accent) else light(accent)

        private fun hex(rgb: Int): Rgba =
            Rgba((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)
    }
}

/** Small pure colour math for accent derivations (spec 11 §1). */
object ColorMath {
    /** ACCENT_DIM = the accent's hue at 72% saturation and 55% value. */
    fun dim(c: Rgba): Rgba {
        val hsv = rgbToHsv(c)
        return hsvToRgb(hsv[0], 0.72, 0.55, c.a)
    }

    /** Lighten toward white by [amount] (0..1). */
    fun lighten(c: Rgba, amount: Double): Rgba {
        val a = amount.coerceIn(0.0, 1.0)
        fun mix(v: Int) = (v + (255 - v) * a).toInt().coerceIn(0, 255)
        return Rgba(mix(c.r), mix(c.g), mix(c.b), c.a)
    }

    fun rgbToHsv(c: Rgba): DoubleArray {
        val r = c.r / 255.0
        val g = c.g / 255.0
        val b = c.b / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        var h = when {
            delta < 1e-9 -> 0.0
            max == r -> 60.0 * (((g - b) / delta) % 6.0)
            max == g -> 60.0 * (((b - r) / delta) + 2.0)
            else -> 60.0 * (((r - g) / delta) + 4.0)
        }
        if (h < 0) h += 360.0
        val s = if (max < 1e-9) 0.0 else delta / max
        return doubleArrayOf(h, s, max)
    }

    fun hsvToRgb(h: Double, s: Double, v: Double, alpha: Int = 255): Rgba {
        val c = v * s
        val x = c * (1 - abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(v0: Double) = ((v0 + m) * 255).toInt().coerceIn(0, 255)
        return Rgba(ch(r1), ch(g1), ch(b1), alpha)
    }
}
