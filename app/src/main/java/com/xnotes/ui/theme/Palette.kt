package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba
import com.xnotes.settings.Preferences
import kotlin.math.abs
import kotlin.math.pow

/** The design tokens for one appearance (spec 11 §1), built from a Material 3 scheme. */
data class Palette(
    val bg: Rgba,
    val panel: Rgba,
    val paper: Rgba,
    val paperBorder: Rgba,
    val accent: Rgba,
    val border: Rgba,
    val text: Rgba,
    val textDim: Rgba,
    val surface: Rgba,
    val surfaceHi: Rgba,
    val menuBg: Rgba,
    val isDark: Boolean,
    val materialColors: MaterialColors,
) {
    fun accentAlpha(alpha: Int): Rgba = accent.withAlpha(alpha)

    val onAccent: Rgba get() = materialColors.onPrimary

    /** The selection chrome's colour on [bg]: the accent, pushed darker on light paper or lighter on dark. */
    fun selectionAccent(bg: Rgba): Rgba = ColorMath.contrastingOn(accent, bg)

    /** The glyphs drawn on [selectionAccent], kept readable once the accent has been pushed. */
    fun onSelectionAccent(bg: Rgba): Rgba {
        val sel = selectionAccent(bg)
        return if (sel == accent) onAccent else ColorMath.readableOn(sel)
    }
    val selectionBackground: Rgba get() = materialColors.primaryContainer
    val selectionForeground: Rgba get() = materialColors.onPrimaryContainer

    /** Material's disabled content: onSurface at 38%. */
    val disabled: Rgba get() = materialColors.onSurface.withAlpha(97)

    /** The destructive tint, for delete actions sat among ordinary ones. */
    val danger: Rgba get() = materialColors.error

    companion object {
        /** Stands in wherever no theme is provided yet (and in tests). */
        val DEFAULT: Palette by lazy { materialDark(MaterialColors.seeded(Preferences.DEFAULT_MATERIAL_DUAL, dark = true)) }

        /** Build the full chrome from a Material 3 scheme's tonal surfaces. */
        fun material(appearance: String, m: MaterialColors): Palette = when (appearance) {
            "light" -> materialLight(m)
            "oled" -> materialOled(m)
            else -> materialDark(m)
        }

        /** Light sits a few tone steps below white so panels and the backstage keep visible
         *  depth against white paper instead of washing out near the top of the ladder. */
        fun materialLight(m: MaterialColors): Palette = Palette(
            bg = m.surfaceContainerHighest,
            panel = m.surfaceContainerHigh,
            paper = m.surfaceContainerLowest,
            paperBorder = m.outline,
            accent = m.primary,
            // Raw outlineVariant reads too hard against the chrome; sink it a third of the way in.
            border = ColorMath.mix(m.outlineVariant, m.surfaceContainer, 0.35),
            text = m.onSurface,
            textDim = m.onSurfaceVariant,
            surface = m.surfaceDim,
            surfaceHi = ColorMath.darken(m.surfaceDim, 0.06),
            menuBg = m.surfaceContainer,
            isDark = false,
            materialColors = m,
        )

        fun materialDark(m: MaterialColors): Palette = Palette(
            bg = m.surfaceContainerLowest,
            panel = m.surface,
            paper = m.surfaceContainerLow,
            paperBorder = m.outlineVariant,
            accent = m.primary,
            // Raw outlineVariant reads too hard against the chrome; sink it a third of the way in.
            border = ColorMath.mix(m.outlineVariant, m.surfaceContainerLowest, 0.35),
            text = m.onSurface,
            textDim = m.onSurfaceVariant,
            surface = m.surfaceContainerHigh,
            surfaceHi = m.surfaceContainerHighest,
            menuBg = m.surfaceContainerLow,
            isDark = true,
            materialColors = m,
        )

        /** Material OLED: the big surfaces drop to pure black, small lifts step down one slot. */
        fun materialOled(m: MaterialColors): Palette {
            val black = hex(0x000000)
            return materialDark(m).copy(
                bg = black,
                panel = black,
                paper = black,
                menuBg = black,
                surface = m.surfaceContainerLow,
                surfaceHi = m.surfaceContainer,
            )
        }

        private fun hex(rgb: Int): Rgba =
            Rgba((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)
    }
}

/** Small pure colour math for accent derivations (spec 11 §1). */
object ColorMath {

    /**
     * Cap an accent's perceived luminance for the light theme. Colours brighter
     * than [maxLuminance] are scaled down in place (hue and saturation kept),
     * so a bright yellow deepens to gold; colours already dark enough pass through.
     */
    fun darkenForLight(c: Rgba, maxLuminance: Double = 0.35): Rgba {
        val lum = (0.299 * c.r + 0.587 * c.g + 0.114 * c.b) / 255.0
        if (lum <= maxLuminance) return c
        val k = maxLuminance / lum
        fun ch(v: Int) = (v * k).toInt().coerceIn(0, 255)
        return Rgba(ch(c.r), ch(c.g), ch(c.b), c.a)
    }


    /** WCAG relative luminance, 0 for black up to 1 for white. */
    fun relativeLuminance(c: Rgba): Double {
        fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    }

    /** WCAG contrast ratio between [a] and [b], 1 to 21. */
    fun contrastRatio(a: Rgba, b: Rgba): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * [c] mixed toward black on a light [bg] or toward white on a dark one, just far enough to reach
     * [minRatio] contrast against it; colours that already stand out pass through.
     */
    fun contrastingOn(c: Rgba, bg: Rgba, minRatio: Double = 3.0): Rgba {
        if (contrastRatio(c, bg) >= minRatio) return c
        val target = if (relativeLuminance(bg) > DARK_LIGHT_SPLIT) Rgba(0, 0, 0, c.a) else Rgba(255, 255, 255, c.a)
        var t = 0.0
        var out = c
        while (t < 1.0 && contrastRatio(out, bg) < minRatio) {
            t += 0.05
            out = mix(c, target, t)
        }
        return out
    }

    /** Black or white, whichever contrasts more with [c]. */
    fun readableOn(c: Rgba): Rgba =
        if (relativeLuminance(c) > DARK_LIGHT_SPLIT) Rgba(0, 0, 0, 255) else Rgba(255, 255, 255, 255)

    /** The luminance where black and white contrast equally. */
    private const val DARK_LIGHT_SPLIT = 0.179

    /** Darken toward black by [amount] (0..1). */
    fun darken(c: Rgba, amount: Double): Rgba {
        val a = amount.coerceIn(0.0, 1.0)
        fun mix(v: Int) = (v * (1 - a)).toInt().coerceIn(0, 255)
        return Rgba(mix(c.r), mix(c.g), mix(c.b), c.a)
    }

    /** Linear mix of [a] toward [b] by [t] (0..1). */
    fun mix(a: Rgba, b: Rgba, t: Double): Rgba {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(x: Int, y: Int) = (x + (y - x) * k).toInt().coerceIn(0, 255)
        return Rgba(ch(a.r, b.r), ch(a.g, b.g), ch(a.b, b.b), ch(a.a, b.a))
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
