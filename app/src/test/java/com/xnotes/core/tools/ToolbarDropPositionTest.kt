package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolbarDropPositionTest {
    @Test fun everyEdgeSupportsAllThreePositions() {
        val resolver = ToolbarAnchorResolver
        val expected = listOf(
            0.12f to ToolbarDockAlignment.START,
            0.50f to ToolbarDockAlignment.CENTER,
            0.88f to ToolbarDockAlignment.END,
        )
        for ((ratio, alignment) in expected) {
            assertEquals(ToolbarDropPosition(ToolbarAnchor.CENTER_LEFT, alignment),
                resolver.nearestPlacement(3f, 600f * ratio, 1000f, 600f, ToolbarAnchor.CENTER_LEFT))
            assertEquals(ToolbarDropPosition(ToolbarAnchor.CENTER_RIGHT, alignment),
                resolver.nearestPlacement(997f, 600f * ratio, 1000f, 600f, ToolbarAnchor.CENTER_RIGHT))
            assertEquals(ToolbarDropPosition(
                when (alignment) {
                    ToolbarDockAlignment.START -> ToolbarAnchor.TOP_LEFT
                    ToolbarDockAlignment.CENTER -> ToolbarAnchor.TOP_CENTER
                    ToolbarDockAlignment.END -> ToolbarAnchor.TOP_RIGHT
                }, alignment), resolver.nearestPlacement(1000f * ratio, 3f,
                    1000f, 600f, ToolbarAnchor.TOP_CENTER))
            assertEquals(ToolbarDropPosition(
                when (alignment) {
                    ToolbarDockAlignment.START -> ToolbarAnchor.BOTTOM_LEFT
                    ToolbarDockAlignment.CENTER -> ToolbarAnchor.BOTTOM_CENTER
                    ToolbarDockAlignment.END -> ToolbarAnchor.BOTTOM_RIGHT
                }, alignment), resolver.nearestPlacement(1000f * ratio, 597f,
                    1000f, 600f, ToolbarAnchor.BOTTOM_CENTER))
        }
    }

    @Test fun draggingWithinLeftEdgeCanReturnFromStartToCenterAndEnd() {
        val start = ToolbarAnchorResolver.nearestPlacement(5f, 60f, 960f, 600f,
            ToolbarAnchor.CENTER_LEFT)
        val center = ToolbarAnchorResolver.nearestPlacement(5f, 300f, 960f, 600f,
            start!!.anchor)
        val end = ToolbarAnchorResolver.nearestPlacement(5f, 540f, 960f, 600f,
            center!!.anchor)
        assertEquals(ToolbarDropPosition(ToolbarAnchor.CENTER_LEFT, ToolbarDockAlignment.START), start)
        assertEquals(ToolbarDropPosition(ToolbarAnchor.CENTER_LEFT, ToolbarDockAlignment.CENTER), center)
        assertEquals(ToolbarDropPosition(ToolbarAnchor.CENTER_LEFT, ToolbarDockAlignment.END), end)
    }

    @Test fun cornerTieStaysOnPreviouslySelectedEdge() {
        assertEquals(ToolbarDropPosition(ToolbarAnchor.CENTER_LEFT, ToolbarDockAlignment.START),
            ToolbarAnchorResolver.nearestPlacement(0f, 0f, 960f, 600f, ToolbarAnchor.CENTER_LEFT))
        assertEquals(ToolbarDropPosition(ToolbarAnchor.TOP_LEFT, ToolbarDockAlignment.START),
            ToolbarAnchorResolver.nearestPlacement(0f, 0f, 960f, 600f, ToolbarAnchor.TOP_CENTER))
    }

    @Test fun impossiblePaneDoesNotProduceDropPosition() {
        assertNull(ToolbarAnchorResolver.nearestPlacement(Float.NaN, 0f, 960f, 600f,
            ToolbarAnchor.CENTER_LEFT))
        assertNull(ToolbarAnchorResolver.nearestPlacement(0f, 0f, 960f, 0f,
            ToolbarAnchor.TOP_LEFT))
    }
}
