package com.xnotes.core.tools

/**
 * Pure snap-grid decision shared by the real toolbar surface and the configuration preview.
 * Coordinates are relative to the safe pane, never to the entire screen.
 */
object ToolbarAnchorResolver {
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
