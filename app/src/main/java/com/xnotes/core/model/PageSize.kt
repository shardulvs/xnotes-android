package com.xnotes.core.model

/** Page orientation. Portrait = shorter width, longer height. */
enum class Orientation {
    PORTRAIT,
    LANDSCAPE;

    fun toName(): String = if (this == LANDSCAPE) "landscape" else "portrait"

    companion object {
        fun fromName(name: String?): Orientation =
            if (name?.equals("landscape", ignoreCase = true) == true) LANDSCAPE else PORTRAIT
    }
}

/**
 * Named page sizes (spec 02 §1). Side lengths are listed **shorter × longer** in
 * millimetres; [pixels] applies the orientation and DPI conversion.
 */
enum class PageSize(val displayName: String, val shortMm: Double, val longMm: Double) {
    A4("A4", 210.0, 297.0),
    A5("A5", 148.0, 210.0),
    LETTER("Letter", 215.9, 279.4),
    SLIDE_16_9("Slide 16:9", 190.5, 338.7),
    SLIDE_4_3("Slide 4:3", 190.5, 254.0);

    /** Returns `(width, height)` in document pixels for [orientation] at [dpi]. */
    fun pixels(orientation: Orientation, dpi: Int): Pair<Double, Double> {
        val shortPx = mmToPx(shortMm, dpi)
        val longPx = mmToPx(longMm, dpi)
        return if (orientation == Orientation.PORTRAIT) shortPx to longPx else longPx to shortPx
    }

    companion object {
        const val DEFAULT_DPI = 150

        fun mmToPx(mm: Double, dpi: Int): Double = mm / 25.4 * dpi
        fun pxToMm(px: Double, dpi: Int): Double = px / dpi * 25.4

        /** Forgiving lookup by display name or enum name; defaults to [A4]. */
        fun fromName(name: String?): PageSize =
            entries.firstOrNull {
                it.displayName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true)
            } ?: A4
    }
}
