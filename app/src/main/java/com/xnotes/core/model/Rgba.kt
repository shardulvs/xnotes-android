package com.xnotes.core.model

/** An RGBA colour with each channel an integer in `[0, 255]` (spec README §4). */
data class Rgba(val r: Int, val g: Int, val b: Int, val a: Int = 255) {

    /** Same colour with its alpha multiplied by [factor] (e.g. highlighter ×0.35). */
    fun scaleAlpha(factor: Double): Rgba = copy(a = (a * factor).toInt().coerceIn(0, 255))

    fun withAlpha(newAlpha: Int): Rgba = copy(a = newAlpha.coerceIn(0, 255))

    /** Packed ARGB (the platform-neutral 0xAARRGGBB packing the Renderer consumes). */
    fun toArgb(): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun toList(): List<Int> = listOf(r, g, b, a)

    companion object {
        fun fromArgb(argb: Int): Rgba =
            Rgba((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF)

        /** Forgiving parse of a `[r,g,b(,a)]` list; `null`/too-short yields `null`. */
        fun fromList(list: List<Int>?): Rgba? {
            if (list == null || list.size < 3) return null
            return Rgba(
                list[0].coerceIn(0, 255),
                list[1].coerceIn(0, 255),
                list[2].coerceIn(0, 255),
                (list.getOrNull(3) ?: 255).coerceIn(0, 255),
            )
        }

        /** Parse a `#rrggbb` (or `#aarrggbb`) hex string; `null` on malformed input. */
        fun fromHex(hex: String?): Rgba? {
            val s = hex?.trim()?.removePrefix("#") ?: return null
            return try {
                when (s.length) {
                    6 -> Rgba(
                        s.substring(0, 2).toInt(16),
                        s.substring(2, 4).toInt(16),
                        s.substring(4, 6).toInt(16),
                    )
                    8 -> Rgba(
                        s.substring(2, 4).toInt(16),
                        s.substring(4, 6).toInt(16),
                        s.substring(6, 8).toInt(16),
                        s.substring(0, 2).toInt(16),
                    )
                    else -> null
                }
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun toHex(c: Rgba): String = "#%02x%02x%02x".format(c.r, c.g, c.b)
    }
}
