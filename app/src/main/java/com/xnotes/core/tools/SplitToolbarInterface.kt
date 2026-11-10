package com.xnotes.core.tools

/** The layout to keep on screen for a mixed split. It never selects the action's document. */
enum class SplitToolbarInterface(val id: String) {
    NOTE("note"), CANVAS("canvas");

    companion object {
        fun fromId(id: String): SplitToolbarInterface = entries.find { it.id == id } ?: NOTE
    }
}

/** Intersects a workspace floating bar with a pane, in the same root coordinate space. */
data class ToolbarPaneBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)
data class ToolbarPaneInsets(val left: Double = 0.0, val top: Double = 0.0, val right: Double = 0.0, val bottom: Double = 0.0)

fun sharedToolbarInsets(bar: ToolbarPaneBounds, pane: ToolbarPaneBounds, edge: String): ToolbarPaneInsets {
    if (pane.right <= pane.left || pane.bottom <= pane.top ||
        bar.right <= pane.left || bar.left >= pane.right || bar.bottom <= pane.top || bar.top >= pane.bottom) return ToolbarPaneInsets()
    return when (edge) {
        "left" -> ToolbarPaneInsets(left = (bar.right - pane.left).coerceIn(0.0, pane.right - pane.left))
        "top" -> ToolbarPaneInsets(top = (bar.bottom - pane.top).coerceIn(0.0, pane.bottom - pane.top))
        "right" -> ToolbarPaneInsets(right = (pane.right - bar.left).coerceIn(0.0, pane.right - pane.left))
        "bottom" -> ToolbarPaneInsets(bottom = (pane.bottom - bar.top).coerceIn(0.0, pane.bottom - pane.top))
        else -> ToolbarPaneInsets()
    }
}
