package com.xnotes.settings

import com.xnotes.core.model.Rgba

/** A colour row offering editable circles; each role keeps its own five, shared wherever it shows. */
enum class SwatchRole(val id: String, val defaults: List<Rgba>) {
    PAPER("paper", listOf(Rgba(22, 22, 22), Rgba(13, 13, 13), Rgba(255, 255, 255), Rgba(247, 243, 233), Rgba(232, 232, 232))),
    LINES("lines", listOf(Rgba(230, 230, 230), Rgba(40, 40, 40), Rgba(70, 120, 255), Rgba(0, 170, 160), Rgba(214, 72, 72))),
    ACCENT("accent", listOf(Rgba(214, 72, 72), Rgba(70, 120, 255), Rgba(60, 170, 90), Rgba(230, 160, 40), Rgba(150, 100, 220))),

    /** A template's own colour parameters, such as a cover's board. */
    TEMPLATE("template", listOf(Rgba(34, 56, 90), Rgba(29, 58, 51), Rgba(90, 35, 40), Rgba(200, 169, 126), Rgba(239, 232, 216)));

    companion object {
        const val COUNT = 5
    }
}
