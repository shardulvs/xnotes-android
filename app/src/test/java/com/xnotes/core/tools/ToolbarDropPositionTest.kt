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

    @Test fun hysteresisPreventsEdgeFlickerNearCorner() {
        val bottom = ToolbarDropPosition(ToolbarAnchor.BOTTOM_CENTER,
            ToolbarDockAlignment.CENTER)
        // Right edge becomes 12 px closer but must not steal the selection.
        assertEquals(ToolbarAnchor.BOTTOM_RIGHT,
            ToolbarAnchorResolver.stablePlacement(940f, 568f, 960f, 600f,
                bottom)!!.anchor)
        val switched = ToolbarAnchorResolver.stablePlacement(957f, 530f,
            960f, 600f, bottom)!!
        assertEquals(ToolbarAnchor.CENTER_RIGHT, switched.anchor)
        // Returning near the corner must keep RIGHT until BOTTOM clearly wins.
        assertEquals(ToolbarAnchor.CENTER_RIGHT,
            ToolbarAnchorResolver.stablePlacement(940f, 568f, 960f, 600f,
                switched)!!.anchor)
        assertEquals(ToolbarAnchor.BOTTOM_RIGHT,
            ToolbarAnchorResolver.stablePlacement(910f, 594f, 960f, 600f,
                switched)!!.anchor)
    }

    @Test fun hysteresisKeepsAlignmentStableAroundOneThirdBoundary() {
        val center = ToolbarDropPosition(ToolbarAnchor.BOTTOM_CENTER,
            ToolbarDockAlignment.CENTER)
        assertEquals(center,
            ToolbarAnchorResolver.stablePlacement(300f, 598f, 960f, 600f,
                center))
        val start = ToolbarAnchorResolver.stablePlacement(255f, 598f, 960f, 600f,
            center)!!
        assertEquals(ToolbarDockAlignment.START, start.alignment)
        assertEquals(start, ToolbarAnchorResolver.stablePlacement(350f, 598f,
            960f, 600f, start))
        assertEquals(center, ToolbarAnchorResolver.stablePlacement(390f, 598f,
            960f, 600f, start))
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
