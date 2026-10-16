package com.xnotes.core.tools

/** The tool set (spec 04 §1). */
enum class Tool(val id: String) {
    PEN("pen"),
    DASHED("dashed"),
    CALLIGRAPHY("calligraphy"),
    SPEED("speed"),
    TAPER("taper"),
    HIGHLIGHTER("highlighter"),
    ERASER("eraser"),
    PAN("pan"),
    SELECT("select"),
    LASSO("lasso"),
    SCREENSHOT("screenshot"),
    SHAPE("shape"),
    TEXT("text"),
    TEXT_BOX("text_box"),
    TABLE("table"),
    IMAGE("image"),

    /** Marks a PDF's text: highlights and lines along it, or selects it ([MarkupMode]). */
    MARKUP("markup");

    /** Tools that produce ink via the stroke engine. */
    val isStroke: Boolean get() = this == PEN || this == DASHED || this == CALLIGRAPHY ||
        this == SPEED || this == TAPER || this == HIGHLIGHTER

    /**
     * Tools the "draw with finger" gate covers: when finger-draw is off, a finger pans
     * instead of activating these (the stroke tools plus select/lasso/shape/eraser/markup). The
     * stylus always uses the armed tool (its eraser tip still erases); text and pan stay
     * usable by finger either way.
     */
    val fingerPansWhenOff: Boolean get() = isStroke ||
        this == SELECT || this == LASSO || this == SCREENSHOT || this == SHAPE || this == ERASER || this == MARKUP ||
        this == TABLE

    /** Render-time ink alpha scale: the highlighter is translucent (spec 03 §3). */
    val alphaScale: Double get() = if (this == HIGHLIGHTER) 0.35 else 1.0

    companion object {
        fun fromId(id: String?): Tool? = entries.firstOrNull { it.id == id }

        /** Armed at startup. */
        val DEFAULT = PAN

        /** Quick-tool-wheel order (spec 06 §11 / 10 §7). */
        val wheelOrder = listOf(PEN, DASHED, CALLIGRAPHY, SPEED, TAPER, HIGHLIGHTER, ERASER, SELECT, LASSO, SCREENSHOT, SHAPE, TEXT, MARKUP, PAN)
    }
}
