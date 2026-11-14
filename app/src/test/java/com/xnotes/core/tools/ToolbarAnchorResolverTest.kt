package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolbarAnchorResolverTest {
    @Test fun allEightEdgeAndCornerAnchors() {
        val positions = listOf(10f, 150f, 290f)
        for (row in 0..2) for (column in 0..2) {
            if (row == 1 && column == 1) continue
            assertEquals(
                ToolbarAnchor.entries[row * 3 + column],
                ToolbarAnchorResolver.nearest(positions[column], positions[row], 300f, 300f),
            )
        }
        // Center is never a valid snap target; ties prefer the top edge.
        assertEquals(ToolbarAnchor.TOP_CENTER,
            ToolbarAnchorResolver.nearest(150f, 150f, 300f, 300f))
    }

    @Test fun independentPaneSizeAndOutOfBoundsMovement() {
        assertEquals(ToolbarAnchor.BOTTOM_RIGHT,
            ToolbarAnchorResolver.nearest(1500f, 800f, 600f, 400f))
        assertEquals(ToolbarAnchor.TOP_LEFT,
            ToolbarAnchorResolver.nearest(-60f, -90f, 600f, 400f))
        assertEquals(ToolbarAnchor.TOP_CENTER,
            ToolbarAnchorResolver.nearest(300f, 200f, 600f, 400f))
    }

    @Test fun rejectsInvalidStageGeometry() {
        assertNull(ToolbarAnchorResolver.nearest(1f, 1f, 0f, 300f))
        assertNull(ToolbarAnchorResolver.nearest(Float.NaN, 1f, 300f, 300f))
        assertNull(ToolbarAnchorResolver.nearest(1f, 1f, Float.POSITIVE_INFINITY, 300f))
    }
}
