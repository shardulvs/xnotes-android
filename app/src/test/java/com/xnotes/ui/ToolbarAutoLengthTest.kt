package com.xnotes.ui

import com.xnotes.core.tools.*
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.dp

class ToolbarAutoLengthTest {
    private val look = ToolbarLook(floating = true)
    private fun bar(lanes: Int, entries: List<ToolbarEntry>, anchor: ToolbarAnchor = ToolbarAnchor.TOP_CENTER) =
        ToolbarBar("auto", "Auto", ToolbarLayout(listOf(ToolbarSection(entries, lanes = lanes))),
            geometry = ToolbarGeometry(floating = true, anchor = anchor))

    @Test fun colorsUseActualSwatchPaddingAndIndividualButtonOverride() {
        val colored = bar(1, listOf(
            ToolbarEntry(ToolbarItem.COLORS), ToolbarEntry(ToolbarItem.PEN),
            ToolbarEntry(ToolbarItem.ERASER, sizeDp = 72),
        ))
        val metrics = resolvedBarMetrics(toolbarBarSize(colored, 0, look), colored.geometry)
        val required = 12 * (metrics.swatch.value + 6f) + metrics.button.value + 72f
        assertTrue(toolbarEstimatedFloatingLength(colored, 0, look, 12) >=
            required + 2f * metrics.padding.value)
        assertTrue(toolbarEstimatedFloatingLength(colored, 0, look, 12) >
            toolbarEstimatedFloatingLength(colored, 0, look, 4))
    }

    @Test fun multiLaneEstimateCountsPerLaneSpacingButNotSumOfAllLanes() {
        for (rows in 1..3) {
            val entries = (0 until rows).flatMap { lane ->
                listOf(ToolbarEntry(ToolbarItem.PEN, lane = lane),
                    ToolbarEntry(ToolbarItem.ERASER, lane = lane))
            }
            val bar = bar(rows, entries)
            val metrics = resolvedBarMetrics(toolbarBarSize(bar, 0, look), bar.geometry)
            val expectedLane = 2 * metrics.button.value +
                if (rows > 1) metrics.spacing.value else 0f
            val actual = toolbarEstimatedFloatingLength(bar, 0, look, 3)
            assertTrue("$rows lanes should allocate each lane's full length: $actual",
                actual >= expectedLane + 2f * metrics.padding.value)
            assertTrue("$rows lanes should use the longest lane, not the sum: $actual",
                actual <= expectedLane + 2f * metrics.padding.value + 5f)
        }
    }

    @Test fun orientationAndCompositeControlsRemainFullyAllocated() {
        val bar = bar(2, listOf(
            ToolbarEntry(ToolbarItem.TITLE, lane = 0),
            ToolbarEntry(ToolbarItem.PAGE_NAV, lane = 1),
            ToolbarEntry(ToolbarItem.ZOOM, lane = 1),
        ))
        val horizontal = toolbarEstimatedFloatingLength(bar, 0, look, 3, vertical = false)
        val vertical = toolbarEstimatedFloatingLength(bar, 0, look, 3, vertical = true)
        assertTrue("Composite controls need room for text, not merely 3x button", horizontal > 170f)
        assertTrue("Rotating into a side bar must recalculate text and controls",
            vertical > 180f && kotlin.math.abs(vertical - horizontal) > 8f)
    }

    @Test fun explicitLengthOverridesAutoAndActualPlannerHonorsBudget() {
        val auto = bar(3, (0..2).flatMap { lane ->
            listOf(ToolbarEntry(ToolbarItem.COLORS, lane = lane), ToolbarEntry(ToolbarItem.PEN, lane = lane))
        })
        val natural = toolbarEstimatedFloatingLength(auto, 0, look, 9)
        val plan = toolbarFloatingPlan(ToolbarWorkspace(listOf(auto)), look, 700.dp, 500.dp, 9)
        val placement = requireNotNull(plan.placement("auto"))
        assertTrue(placement.width >= minOf(natural, 684f) - 1f)
        val fixed = auto.copy(geometry = auto.geometry.copy(widthDp = 164))
        assertEquals(164f, toolbarEstimatedFloatingLength(fixed, 0, look, 9), 0.01f)
    }
}
