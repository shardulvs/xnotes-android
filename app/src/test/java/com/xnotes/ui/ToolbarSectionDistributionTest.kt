package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarSectionDistributionTest {
    @Test fun groupsUseOriginalIndicesWithoutMixingRegions() {
        val layout = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)), region = ToolbarRegion.END),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.HOME)), region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.TITLE, visible = false)), region = ToolbarRegion.CENTER),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.COLORS)), region = ToolbarRegion.CENTER),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.ERASER)), region = ToolbarRegion.START),
        ), distribution = ToolbarDistribution.REGIONS)
        val groups = toolbarRegionGroups(layout)
        assertEquals(listOf(1, 4), groups.getValue(ToolbarRegion.START).map { it.index })
        assertEquals(listOf(3), groups.getValue(ToolbarRegion.CENTER).map { it.index })
        assertEquals(listOf(0), groups.getValue(ToolbarRegion.END).map { it.index })
        // The saved order stays untouched; only the compact rendering groups by region.
        assertEquals(listOf(1, 4, 3, 0), toolbarCompactRegionSections(layout).map { it.index })
        assertEquals(ToolbarRegion.END, layout.sections.first().region)
    }

    @Test fun pinningStartAndEndLeavesOnlyCenterOnTheScrollingTrack() {
        val base = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)), region = ToolbarRegion.END),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.HOME)), region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.COLORS)), region = ToolbarRegion.CENTER),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.ERASER)), region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.TITLE, visible = false)), region = ToolbarRegion.END),
        ), distribution = ToolbarDistribution.REGIONS)
        val both = base.copy(pinStart = true, pinEnd = true)
        assertEquals(listOf(1, 3), toolbarPinnedRegionGroups(both).start.map { it.index })
        assertEquals(listOf(2), toolbarPinnedRegionGroups(both).scrolling.map { it.index })
        assertEquals(listOf(0), toolbarPinnedRegionGroups(both).end.map { it.index })
        assertTrue(both.hasPinnedRegions)
        assertFalse(toolbarCompactRegions(both, floating = true, explicitLengthDp = null))

        val startOnly = base.copy(pinStart = true)
        assertEquals(listOf(2, 0), toolbarPinnedRegionGroups(startOnly).scrolling.map { it.index })
        assertTrue(toolbarPinnedRegionGroups(startOnly).end.isEmpty())
        val endOnly = base.copy(pinEnd = true)
        assertEquals(listOf(1, 3, 2), toolbarPinnedRegionGroups(endOnly).scrolling.map { it.index })
        assertTrue(toolbarPinnedRegionGroups(endOnly).start.isEmpty())
        assertFalse(base.hasPinnedRegions)
        assertTrue(toolbarCompactRegions(base, floating = true, explicitLengthDp = null))
    }

    @Test fun pinnedEdgesPreserveNaturalWidthUntilTheBarRunsOutOfRoom() {
        // Unconstrained: all tools can fit, no clipped or overlapping edge allocation.
        assertEquals(440, toolbarPinnedScrollBudget(600, 80, 80, true, 20))
        assertEquals(100, toolbarPinnedScrollBudget(400, 150, 150, true, 20))
        // Constrained: keep a small track behind the fixed controls; the fade marks the seam.
        assertEquals(20, toolbarPinnedScrollBudget(200, 120, 100, true, 20))
        assertEquals(0, toolbarPinnedScrollBudget(200, 120, 100, false, 20))
        assertEquals(10, toolbarPinnedScrollBudget(10, 8, 7, true, 20))
    }

    @Test fun constrainedMiddleUnderlapsOnlyWhenNecessary() {
        assertEquals(200, toolbarPinnedMiddleOffset(600, 100, 200, 50))
        assertEquals(260, toolbarPinnedMiddleOffset(400, 260, 80, 30))
        assertEquals(110, toolbarPinnedMiddleOffset(300, 120, 80, 130))
    }

    @Test fun pinnedFlagsWithHiddenOrMissingRegionDoNotForceFullLengthFloatingBar() {
        val regions = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN, visible = false)), region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.COLORS)), region = ToolbarRegion.CENTER),
        ), distribution = ToolbarDistribution.REGIONS, pinStart = true, pinEnd = true)
        assertFalse(regions.hasPinnedRegions)
        assertTrue(toolbarCompactRegions(regions, floating = true, explicitLengthDp = null))
        assertFalse(regions.setDistribution(ToolbarDistribution.CONTINUOUS).hasPinnedRegions)
    }

    @Test fun onlyFloatingAutoLengthUsesCompactRegions() {
        val regions = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))),
            distribution = ToolbarDistribution.REGIONS)
        assertTrue(toolbarCompactRegions(regions, floating = true, explicitLengthDp = null))
        assertFalse(toolbarCompactRegions(regions, floating = true, explicitLengthDp = 520))
        assertFalse(toolbarCompactRegions(regions, floating = false, explicitLengthDp = null))
        assertFalse(toolbarCompactRegions(regions.setDistribution(ToolbarDistribution.CONTINUOUS),
            floating = true, explicitLengthDp = null))
    }

    @Test fun emptyCenterAndEndKeepTheirSeparateSlots() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))),
            distribution = ToolbarDistribution.REGIONS)
        val groups = toolbarRegionGroups(layout)
        assertEquals(1, groups.getValue(ToolbarRegion.START).size)
        assertTrue(groups.getValue(ToolbarRegion.CENTER).isEmpty())
        assertTrue(groups.getValue(ToolbarRegion.END).isEmpty())
    }

    @Test fun sectionCanBeDroppedInAnEmptyCenterColumn() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.HOME)))),
            distribution = ToolbarDistribution.REGIONS)
        val bounds = mapOf(ToolbarRegion.START to Rect(0f, 0f, 90f, 400f),
            ToolbarRegion.CENTER to Rect(100f, 0f, 190f, 400f),
            ToolbarRegion.END to Rect(200f, 0f, 290f, 400f))
        val target = toolbarSectionDropTarget(layout, Offset(130f, 80f),
            mapOf(0 to Rect(0f, 40f, 90f, 100f)), bounds)
        assertEquals(ToolbarSectionTarget(ToolbarRegion.CENTER), target)
    }

    @Test fun toolCanBeDroppedInAnEmptyCenterColumn() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))),
            distribution = ToolbarDistribution.REGIONS)
        val regions = mapOf(
            ToolbarRegion.START to Rect(0f, 0f, 100f, 400f),
            ToolbarRegion.CENTER to Rect(110f, 0f, 210f, 400f),
            ToolbarRegion.END to Rect(220f, 0f, 320f, 400f),
        )
        val target = toolbarItemDropTarget(layout, Offset(160f, 80f), emptyMap(), emptyMap(),
            mapOf(0 to Rect(0f, 40f, 100f, 150f)), regions)
        assertEquals(ToolbarItemTarget(ToolbarRegion.CENTER, null), target)
    }

    @Test fun toolDropTargetsOnlySectionsInsideTheHoveredRegion() {
        val layout = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)), region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.ERASER)), region = ToolbarRegion.END),
        ), distribution = ToolbarDistribution.REGIONS)
        val regions = mapOf(
            ToolbarRegion.START to Rect(0f, 0f, 100f, 400f),
            ToolbarRegion.CENTER to Rect(110f, 0f, 210f, 400f),
            ToolbarRegion.END to Rect(220f, 0f, 320f, 400f),
        )
        val sections = mapOf(0 to Rect(0f, 40f, 100f, 160f), 1 to Rect(220f, 40f, 320f, 160f))
        assertEquals(1, toolbarItemDropTarget(layout, Offset(260f, 80f), emptyMap(), emptyMap(),
            sections, regions)?.slot?.first)
        assertEquals(ToolbarItemTarget(ToolbarRegion.CENTER, null),
            toolbarItemDropTarget(layout, Offset(160f, 80f), emptyMap(), emptyMap(), sections, regions))
    }

    @Test fun verticalLayerDropChecksRowMidpointNotHorizontalPosition() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(
            ToolbarEntry(ToolbarItem.HOME), ToolbarEntry(ToolbarItem.TITLE)))))
        val section = mapOf(0 to Rect(0f, 0f, 200f, 200f))
        val lanes = mapOf((0 to 0) to Rect(0f, 0f, 200f, 200f))
        val chips = mapOf((0 to 0) to Rect(0f, 20f, 200f, 60f),
            (0 to 1) to Rect(0f, 70f, 200f, 110f))
        assertEquals(Triple(0, 0, 1), toolbarDropTarget(layout, Offset(190f, 72f), chips, lanes, section))
    }
}
