package com.xnotes.canvas

import com.xnotes.core.model.Rgba

/** How pages are grouped into rows on the canvas (the View menu's viewing mode). */
enum class ViewingMode(val id: String) {
    /** One page per row (the classic column). */
    SINGLE("single"),

    /** Two pages side by side: 1-2, 3-4, ... */
    DOUBLE("double"),

    /** Page 1 alone (the cover), then pairs: 2-3, 4-5, ... */
    COVER("cover");

    companion object {
        fun fromId(id: String?): ViewingMode = entries.firstOrNull { it.id == id } ?: SINGLE
    }
}

/**
 * A complete set of View-menu settings. Used in two roles: the app-wide **defaults**
 * (saved via the menu's "Default for all notes" checkbox, persisted in
 * [com.xnotes.settings.Settings]) and the **resolved** effective settings of the open
 * note ([ViewOverrides.resolve]). The colour filters follow CSS filter semantics and are
 * applied to the PDF page raster only, in the fixed order contrast, invert, brightness, sepia,
 * multiply, screen.
 */
data class ViewSettings(
    val mode: ViewingMode = ViewingMode.SINGLE,
    /** True = continuous vertical scroll; false = paginated horizontal page-flip. */
    val verticalScroll: Boolean = true,
    /** Paginated only: pages move up and down freely and pinches never snap to a fit. */
    val freeMovement: Boolean = false,
    val contrast: Int = 100, // 0..200, 100 = untouched
    val invert: Int = 0, // 0..100
    val brightness: Int = 100, // 0..200, 100 = untouched
    val sepia: Int = 0, // 0..200, 0 = untouched (CSS stops at 100, past it the tint keeps going)
    /** MULTIPLY blend colour, like laying a highlighter over the page: white = untouched. */
    val multiply: Rgba = PdfColorFilter.MULTIPLY_OFF,
    /** SCREEN blend colour, the inverse highlighter (lifts the blacks): black = untouched. */
    val screen: Rgba = PdfColorFilter.SCREEN_OFF,
    /** Embedded PDF images keep their original pixels instead of taking the filters. */
    val keepImages: Boolean = false,
    /** Whole-file page rotation, clockwise degrees: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    /** Whether the canvas-area scrollbar is shown (off by default). */
    val scrollbar: Boolean = false,
) {
    companion object {
        val ROTATIONS = listOf(0, 90, 180, 270)

        /** Snap any degree value onto the supported quarter turns. */
        fun normalizeRotation(deg: Int): Int = (((deg % 360) + 360) % 360) / 90 * 90
    }
}

/**
 * Per-note View-menu overrides: null fields inherit the global [ViewSettings]
 * defaults, set fields shadow them for this note. Scoped to the
 * open file but stored app-side — in [com.xnotes.platform.ViewStateStore] for folder
 * notes and the session sidecar for the open note, exactly like zoom and scroll —
 * never in the .xnote format.
 */
data class ViewOverrides(
    val mode: ViewingMode? = null,
    val verticalScroll: Boolean? = null,
    val freeMovement: Boolean? = null,
    val contrast: Int? = null,
    val invert: Int? = null,
    val brightness: Int? = null,
    val sepia: Int? = null,
    val multiply: Rgba? = null,
    val screen: Rgba? = null,
    val keepImages: Boolean? = null,
    val rotation: Int? = null,
    val scrollbar: Boolean? = null,
) {
    /** The note's effective settings: every null falls back to [defaults]. */
    fun resolve(defaults: ViewSettings): ViewSettings = ViewSettings(
        mode = mode ?: defaults.mode,
        verticalScroll = verticalScroll ?: defaults.verticalScroll,
        freeMovement = freeMovement ?: defaults.freeMovement,
        contrast = contrast ?: defaults.contrast,
        invert = invert ?: defaults.invert,
        brightness = brightness ?: defaults.brightness,
        sepia = sepia ?: defaults.sepia,
        multiply = multiply ?: defaults.multiply,
        screen = screen ?: defaults.screen,
        keepImages = keepImages ?: defaults.keepImages,
        rotation = rotation ?: defaults.rotation,
        scrollbar = scrollbar ?: defaults.scrollbar,
    )
}
