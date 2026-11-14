package com.xnotes.ui

import androidx.compose.ui.unit.IntSize
import com.xnotes.core.tools.ToolbarRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarContainerMorphTest {
    @Test fun aRowBecomesAColumnWithoutMovingTheFirstTool() {
        val sizes = listOf(IntSize(40, 40), IntSize(50, 40), IntSize(40, 40))
        val lanes = listOf(0, 0, 0)
        val horizontal = toolbarMorphPlan(sizes, lanes, vertical = false, spacingPx = 2)
        val vertical = toolbarMorphPlan(sizes, lanes, vertical = true, spacingPx = 2)
        assertEquals(IntSize(134, 40), horizontal.size)
        assertEquals(IntSize(50, 124), vertical.size)
        assertEquals(ToolbarMorphPosition(0, 0), horizontal.positions[0])
        assertEquals(ToolbarMorphPosition(0, 0), vertical.positions[0])
        assertEquals(ToolbarMorphPosition(42, 0), horizontal.positions[1])
        assertEquals(ToolbarMorphPosition(0, 42), vertical.positions[1])
        assertEquals(21, toolbarMorphInterpolate(horizontal.positions[1].x,
            vertical.positions[1].x, 0.5f))
        assertEquals(21, toolbarMorphInterpolate(horizontal.positions[1].y,
            vertical.positions[1].y, 0.5f))
    }

    @Test fun twoRowsBecomeTwoColumnsAndKeepLaneIdentity() {
        val sizes = List(4) { IntSize(42, 40) }
        val lanes = listOf(0, 0, 1, 1)
        val horizontal = toolbarMorphPlan(sizes, lanes, vertical = false, spacingPx = 2)
        val vertical = toolbarMorphPlan(sizes, lanes, vertical = true, spacingPx = 2)
        assertEquals(IntSize(86, 82), horizontal.size)
        assertEquals(IntSize(86, 82), vertical.size)
        assertEquals(ToolbarMorphPosition(0, 42), horizontal.positions[2])
        assertEquals(ToolbarMorphPosition(44, 0), vertical.positions[2])
        assertEquals(ToolbarMorphPosition(44, 42), horizontal.positions[3])
        assertEquals(ToolbarMorphPosition(44, 42), vertical.positions[3])
        assertEquals(22, toolbarMorphInterpolate(horizontal.positions[2].x,
            vertical.positions[2].x, 0.5f))
        assertEquals(21, toolbarMorphInterpolate(horizontal.positions[2].y,
            vertical.positions[2].y, 0.5f))
    }

    @Test fun pinnedEndGroupStaysNearFarEdgeInBothOrientations() {
        val sizes = listOf(IntSize(40, 40), IntSize(40, 40), IntSize(40, 40))
        val regions = listOf(ToolbarRegion.START, ToolbarRegion.CENTER, ToolbarRegion.END)
        val row = toolbarMorphRegionPlan(sizes, regions, vertical = false, extent = 360,
            spacingPx = 2)
        val column = toolbarMorphRegionPlan(sizes, regions, vertical = true, extent = 360,
            spacingPx = 2)
        assertEquals(0, row.positions[0].x)
        assertEquals(320, row.positions[2].x)
        assertEquals(0, column.positions[0].y)
        assertEquals(320, column.positions[2].y)
        assertEquals(160, row.positions[1].x)
        assertEquals(160, column.positions[1].y)
    }

    @Test fun interpolationClampsOutOfRangeFractionsAndAvoidsOvershoot() {
        assertEquals(20, toolbarMorphInterpolate(20, 100, -1f))
        assertEquals(100, toolbarMorphInterpolate(20, 100, 5f))
        assertEquals(60, toolbarMorphInterpolate(20, 100, .5f))
        assertEquals(0, toolbarMorphInterpolate(100, 0, 1f))
    }

    @Test fun emptySectionsHaveNoInvalidLayoutSize() {
        val plan = toolbarMorphPlan(emptyList(), emptyList(), vertical = false, spacingPx = 4)
        assertEquals(IntSize.Zero, plan.size)
        assertTrue(plan.positions.isEmpty())
    }
}
