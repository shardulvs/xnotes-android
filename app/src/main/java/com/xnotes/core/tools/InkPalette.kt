package com.xnotes.core.tools

import com.xnotes.core.model.Rgba

/** The preset ink colours (spec 04 §4). Ink colour is global across stroke tools. */
object InkPalette {
    val GREEN = Rgba(0, 230, 118, 255) // default "hacker green"
    val NEAR_WHITE = Rgba(236, 236, 236, 255)
    val RED = Rgba(255, 92, 92, 255)
    val AMBER = Rgba(255, 199, 64, 255)
    val CYAN = Rgba(88, 196, 255, 255)
    val VIOLET = Rgba(199, 134, 255, 255)
    val GREY = Rgba(128, 128, 128, 255)
    val ORANGE = Rgba(255, 145, 60, 255)
    val PINK = Rgba(255, 105, 180, 255)
    val BLUE = Rgba(70, 120, 255, 255)
    val LIME = Rgba(190, 240, 60, 255)
    val TEAL = Rgba(0, 190, 180, 255)
    val BROWN = Rgba(160, 110, 70, 255)
    val NAVY = Rgba(40, 60, 140, 255)
    val BLACK = Rgba(20, 20, 20, 255)

    val DEFAULT = GREEN

    /** Full preset palette offered by the colour controls; also the default toolbar swatches. */
    val presets = listOf(
        GREEN, NEAR_WHITE, RED, AMBER, CYAN, VIOLET, GREY,
        ORANGE, PINK, BLUE, LIME, TEAL, BROWN, NAVY, BLACK,
    )

    /** Most swatches the toolbar can show; one per preset. */
    val MAX_SWATCHES = presets.size
}
