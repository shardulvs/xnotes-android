package com.xnotes.settings

/** How the editor toolbar is drawn; one setting for both the paged and the canvas bar. */
data class ToolbarLook(
    val position: ToolbarPosition = ToolbarPosition.TOP,
    val size: ToolbarSize = ToolbarSize.REGULAR,
    /** A rounded pill over the canvas instead of a strip along the pane's edge. */
    val floating: Boolean = false,
)

/** Which edge of its pane the toolbar runs along. */
enum class ToolbarPosition(val id: String) {
    TOP("top"),
    BOTTOM("bottom"),
    LEFT("left"),
    RIGHT("right");

    val vertical: Boolean get() = this == LEFT || this == RIGHT

    companion object {
        fun fromId(id: String): ToolbarPosition = entries.find { it.id == id } ?: TOP
    }
}

/** How big the toolbar's buttons are. */
enum class ToolbarSize(val id: String) {
    COMPACT("compact"),
    REGULAR("regular"),
    COMFORTABLE("comfortable");

    companion object {
        fun fromId(id: String): ToolbarSize = entries.find { it.id == id } ?: REGULAR
    }
}

/** Where a carried toolbar would be put down: its edge, and whether it floats there. */
data class ToolbarLanding(val position: ToolbarPosition, val floating: Boolean)

/**
 * Where a toolbar lifted [from] its place and carried to ([x], [y]) in a [w] x [h] pane lands: on
 * the nearest edge, docked within a band of it and floating further in. On the edge it came from
 * the band follows how it sat, so a docked bar docks until pulled [pull] clear and a floating one
 * floats until pushed within [push]; any other edge docks within [other]. Lengths are in px.
 */
fun toolbarLanding(x: Float, y: Float, w: Float, h: Float, from: ToolbarLanding, pull: Float, push: Float, other: Float): ToolbarLanding {
    val reach = listOf(ToolbarPosition.TOP to y, ToolbarPosition.BOTTOM to h - y, ToolbarPosition.LEFT to x, ToolbarPosition.RIGHT to w - x)
    val (edge, depth) = reach.minBy { it.second }
    val band = when {
        edge != from.position -> other
        from.floating -> push
        else -> pull
    }
    return ToolbarLanding(edge, floating = depth > band)
}
