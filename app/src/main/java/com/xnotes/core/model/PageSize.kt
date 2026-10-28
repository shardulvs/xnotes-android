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
 *
 * [CUSTOM] is the exception: its side lengths are typed in, which the enum cannot see, so it
 * carries A4's as a fallback and callers resolve it through [PageSizeChoice.pixels] instead of
 * through [pixels].
 */
enum class PageSize(val displayName: String, val shortMm: Double, val longMm: Double) {
    CUSTOM("Custom", 210.0, 297.0),
    A4("A4", 210.0, 297.0),
    A5("A5", 148.0, 210.0),
    LETTER("Letter", 215.9, 279.4),
    LEGAL("Legal", 215.9, 355.6),
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

        /** Settable range of a custom page's sides, in millimetres. */
        const val CUSTOM_MIN_MM = 10.0
        const val CUSTOM_MAX_MM = 2000.0

        fun mmToPx(mm: Double, dpi: Int): Double = mm / 25.4 * dpi
        fun pxToMm(px: Double, dpi: Int): Double = px / dpi * 25.4

        /** Forgiving lookup by display name or enum name; defaults to [A4]. */
        fun fromName(name: String?): PageSize =
            entries.firstOrNull {
                it.displayName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true)
            } ?: A4
    }
}

/** A page size as picked in a menu: a named [size] turned to [orientation], or Custom's sides as typed. */
data class PageSizeChoice(
    val size: PageSize,
    val orientation: Orientation = Orientation.PORTRAIT,
    val customWidthMm: Double = PageSize.A4.shortMm,
    val customHeightMm: Double = PageSize.A4.longMm,
) {
    /** Returns `(width, height)` in document pixels at [dpi]. */
    fun pixels(dpi: Int): Pair<Double, Double> =
        if (size == PageSize.CUSTOM) {
            PageSize.mmToPx(customWidthMm, dpi) to PageSize.mmToPx(customHeightMm, dpi)
        } else {
            size.pixels(orientation, dpi)
        }

    companion object {
        /** How near, in millimetres, a page's sides must be to a named size's to count as that size. */
        private const val MATCH_MM = 0.5

        /** What a [w] by [h] page at [dpi] is: a named size either way round when its sides match one, else Custom with its sides. */
        fun of(w: Double, h: Double, dpi: Int): PageSizeChoice {
            val wMm = PageSize.pxToMm(w, dpi)
            val hMm = PageSize.pxToMm(h, dpi)
            val orientation = if (wMm > hMm) Orientation.LANDSCAPE else Orientation.PORTRAIT
            val short = minOf(wMm, hMm)
            val long = maxOf(wMm, hMm)
            val named = PageSize.entries.firstOrNull {
                it != PageSize.CUSTOM && kotlin.math.abs(it.shortMm - short) <= MATCH_MM && kotlin.math.abs(it.longMm - long) <= MATCH_MM
            }
            return named?.let { PageSizeChoice(it, orientation) } ?: PageSizeChoice(PageSize.CUSTOM, orientation, wMm, hMm)
        }
    }
}
