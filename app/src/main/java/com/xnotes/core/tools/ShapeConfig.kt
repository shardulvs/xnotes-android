package com.xnotes.core.tools

/** Shape kinds drawn by the shape tool (spec 02 §5.4, 04 §6). */
enum class ShapeKind(val id: String) {
    LINE("line"),
    ARROW("arrow"),
    RECTANGLE("rectangle"),
    ELLIPSE("ellipse"),
    TRIANGLE("triangle");

    /** Closed shapes are stroked and optionally filled; open shapes never fill. */
    val isClosed: Boolean get() = this == RECTANGLE || this == ELLIPSE || this == TRIANGLE
    val isOpen: Boolean get() = !isClosed

    companion object {
        fun fromId(id: String?): ShapeKind = entries.firstOrNull { it.id == id } ?: RECTANGLE
    }
}

/** The style carried by the shape tool (spec 04 §6); persisted between sessions. */
data class ShapeConfig(
    val shape: ShapeKind = ShapeKind.RECTANGLE,
    val strokeWidth: Double = 3.0,
    val fill: Boolean = false,
    /** Neon glow: a luminous halo + white-hot core on the shape's outline. */
    val neon: Boolean = false,
    /** Glow intensity in [0, 1] (halo size + brightness); used only when [neon]. */
    val neonStrength: Double = 0.6,
) {
    companion object {
        /** Reduced opacity applied to the ink colour when a closed shape is filled. */
        const val FILL_ALPHA = 0.25
    }
}
