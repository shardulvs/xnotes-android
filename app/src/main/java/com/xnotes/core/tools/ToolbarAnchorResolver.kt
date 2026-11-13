package com.xnotes.core.tools

/**
 * Pure snap-grid decision shared by the real toolbar surface and the configuration preview.
 * Coordinates are relative to the safe pane, never to the entire screen.
 */
/** The actual drag target includes alignment: a side anchor alone cannot encode it. */
data class ToolbarDropPosition(val anchor: ToolbarAnchor, val alignment: ToolbarDockAlignment)

object ToolbarAnchorResolver {
    /**
     * Twelve independent placements: four physical edges, each with start/center/end.
     * Choose the nearest edge first, so moving a left-edge toolbar upward doesn't
     * silently transfer it to TOP just because a corner anchor is closer.
     * Corner ties keep the edge the user was already manipulating.
     */
    fun nearestPlacement(
        centerX: Float, centerY: Float, paneWidth: Float, paneHeight: Float,
        previousAnchor: ToolbarAnchor,
    ): ToolbarDropPosition? {
        if (!centerX.isFinite() || !centerY.isFinite() ||
            !paneWidth.isFinite() || !paneHeight.isFinite() ||
            paneWidth <= 0f || paneHeight <= 0f) return null
        val x = centerX.coerceIn(0f, paneWidth)
        val y = centerY.coerceIn(0f, paneHeight)
        val edge = listOf(
            ToolbarDockEdge.TOP to y,
            ToolbarDockEdge.RIGHT to paneWidth - x,
            ToolbarDockEdge.BOTTOM to paneHeight - y,
            ToolbarDockEdge.LEFT to x,
        ).minWithOrNull(compareBy<Pair<ToolbarDockEdge, Float>> { it.second }
            .thenBy { if (it.first == previousAnchor.edge()) 0 else 1 })!!.first
        val ratio = if (edge == ToolbarDockEdge.TOP || edge == ToolbarDockEdge.BOTTOM)
            x / paneWidth else y / paneHeight
        val alignment = when {
            ratio < 1f / 3f -> ToolbarDockAlignment.START
            ratio > 2f / 3f -> ToolbarDockAlignment.END
            else -> ToolbarDockAlignment.CENTER
        }
        val anchor = when (edge) {
            ToolbarDockEdge.TOP -> when (alignment) {
                ToolbarDockAlignment.START -> ToolbarAnchor.TOP_LEFT
                ToolbarDockAlignment.CENTER -> ToolbarAnchor.TOP_CENTER
                ToolbarDockAlignment.END -> ToolbarAnchor.TOP_RIGHT
            }
            ToolbarDockEdge.BOTTOM -> when (alignment) {
                ToolbarDockAlignment.START -> ToolbarAnchor.BOTTOM_LEFT
                ToolbarDockAlignment.CENTER -> ToolbarAnchor.BOTTOM_CENTER
                ToolbarDockAlignment.END -> ToolbarAnchor.BOTTOM_RIGHT
            }
            ToolbarDockEdge.LEFT -> ToolbarAnchor.CENTER_LEFT
            ToolbarDockEdge.RIGHT -> ToolbarAnchor.CENTER_RIGHT
        }
        return ToolbarDropPosition(anchor, alignment)
    }

    fun nearest(
        centerX: Float,
        centerY: Float,
        paneWidth: Float,
        paneHeight: Float,
    ): ToolbarAnchor? {
        if (!centerX.isFinite() || !centerY.isFinite() ||
            !paneWidth.isFinite() || !paneHeight.isFinite() ||
            paneWidth <= 0f || paneHeight <= 0f
        ) return null

        val x = centerX.coerceIn(0f, paneWidth)
        val y = centerY.coerceIn(0f, paneHeight)
        // The middle of the pane is no longer an anchor. Choose the nearest of the
        // eight edge/corner targets in real pixels, including when released at center.
        val candidates = listOf(
            ToolbarAnchor.TOP_LEFT, ToolbarAnchor.TOP_CENTER, ToolbarAnchor.TOP_RIGHT,
            ToolbarAnchor.CENTER_LEFT, ToolbarAnchor.CENTER_RIGHT,
            ToolbarAnchor.BOTTOM_LEFT, ToolbarAnchor.BOTTOM_CENTER, ToolbarAnchor.BOTTOM_RIGHT,
        )
        return candidates.minByOrNull { anchor ->
            val snapX = (anchor.ordinal % 3) * paneWidth / 2f
            val snapY = (anchor.ordinal / 3) * paneHeight / 2f
            val dx = snapX - x
            val dy = snapY - y
            dx * dx + dy * dy
        }
    }
}

private fun ToolbarAnchor.edge(): ToolbarDockEdge = when (this) {
    ToolbarAnchor.TOP_LEFT, ToolbarAnchor.TOP_CENTER, ToolbarAnchor.TOP_RIGHT, ToolbarAnchor.CENTER -> ToolbarDockEdge.TOP
    ToolbarAnchor.BOTTOM_LEFT, ToolbarAnchor.BOTTOM_CENTER, ToolbarAnchor.BOTTOM_RIGHT -> ToolbarDockEdge.BOTTOM
    ToolbarAnchor.CENTER_LEFT -> ToolbarDockEdge.LEFT
    ToolbarAnchor.CENTER_RIGHT -> ToolbarDockEdge.RIGHT
}
