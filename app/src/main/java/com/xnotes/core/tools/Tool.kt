package com.xnotes.core.tools

/** The tool set (spec 04 §1). */
enum class Tool(val id: String) {
    PEN("pen"),
    CALLIGRAPHY("calligraphy"),
    SPEED("speed"),
    TAPER("taper"),
    HIGHLIGHTER("highlighter"),
    ERASER("eraser"),
    PAN("pan"),
    SELECT("select"),
    LASSO("lasso"),
    SHAPE("shape"),
    TEXT("text"),
    IMAGE("image");

    /** Tools that produce ink via the stroke engine. */
    val isStroke: Boolean get() = this == PEN || this == CALLIGRAPHY || this == SPEED ||
        this == TAPER || this == HIGHLIGHTER

    /** Render-time ink alpha scale: the highlighter is translucent (spec 03 §3). */
    val alphaScale: Double get() = if (this == HIGHLIGHTER) 0.35 else 1.0

    companion object {
        fun fromId(id: String?): Tool? = entries.firstOrNull { it.id == id }

        /** Armed at startup (reference default, spec 04 §1). */
        val DEFAULT = CALLIGRAPHY

        /** Quick-tool-wheel order (spec 06 §11 / 10 §7). */
        val wheelOrder = listOf(PEN, CALLIGRAPHY, SPEED, TAPER, HIGHLIGHTER, ERASER, SELECT, LASSO, SHAPE, TEXT, PAN)
    }
}
