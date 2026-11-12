package com.xnotes.core.tools

import com.xnotes.core.model.Rgba

/** The preset ink colours (spec 04 §4). Ink colour is global across stroke tools. */
object InkPalette {
    private fun hex(rgb: Int) = Rgba((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)

    /** The toolbar swatches on dark chrome: the brand's five first, then the old set tuned to read on dark. */
    val dark = listOf(
        hex(0xFF6B9A), hex(0xF2EEE6), hex(0xA893FF), hex(0x7DF4EA), hex(0xFFC247),
        hex(0x00E676), hex(0xFF5C5C), hex(0x5C8CFF), hex(0xFF913C), hex(0xBEF03C),
        hex(0x58C4FF), hex(0x8E8E96), hex(0xB98257), hex(0x7B8FE6), hex(0x141414),
    )

    /** The same slots on light chrome, each the partner of its [dark] slot, so a mode switch keeps the slot. */
    val light = listOf(
        hex(0xD6336C), hex(0x1A1726), hex(0x5B3FD6), hex(0x0F8A8A), hex(0xB7791F),
        hex(0x1E8C45), hex(0xD93030), hex(0x2457E0), hex(0xE0661A), hex(0x5E9A0A),
        hex(0x0A84C2), hex(0x6E6E78), hex(0x8B5A2E), hex(0x283C8C), hex(0xECECEC),
    )

    val DEFAULT = dark[0]

    /** What a stroke, tape or shape saved without a colour has always loaded as; pinned, it must not follow [DEFAULT]. */
    val LEGACY = Rgba(0, 230, 118, 255)

    /** The one swatch set before light and dark had their own, to tell an untouched set from an edited one. */
    val legacyPresets = listOf(
        LEGACY, hex(0xECECEC), hex(0xFF5C5C), hex(0xFFC740), hex(0x58C4FF), hex(0xC786FF), hex(0x808080),
        hex(0xFF913C), hex(0xFF69B4), hex(0x4678FF), hex(0xBEF03C), hex(0x00BEB4), hex(0xA06E46), hex(0x283C8C), hex(0x141414),
    )

    /** Most swatches the toolbar can show; one per preset. */
    val MAX_SWATCHES = dark.size
}
