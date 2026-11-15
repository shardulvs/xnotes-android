package com.xnotes.ui

import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarRoundnessTest {
    private fun layout(rows: Int) = ToolbarLayout(listOf(ToolbarSection(
        (0 until rows).map { ToolbarEntry(ToolbarItem.PEN, lane = it) },
        lanes = rows,
    )))

    @Test fun autoRoundnessGetsGentlerForTwoAndThreeLines() {
        val theme = 28f // default Rounded extraLarge theme radius
        val depth = 150f
        val one = toolbarCornerRadiusDp(null, 1, depth, theme, floating = true)
        val two = toolbarCornerRadiusDp(null, 2, depth, theme, floating = true)
        val three = toolbarCornerRadiusDp(null, 3, depth, theme, floating = true)
        assertEquals(28f, one, 0.01f)
        assertEquals(19.04f, two, 0.01f)
        assertEquals(14f, three, 0.01f)
        assertTrue(one > two && two > three)
        for (rows in 1..3) assertEquals(rows, toolbarCornerRows(layout(rows)))
    }

    @Test fun automaticStillFollowsSharpRoundedSoftGlobalStyles() {
        val themes = listOf(28f * 0.35f, 28f, 28f * 1.75f)
        for (rows in 1..3) {
            val values = themes.map { radius ->
                toolbarCornerRadiusDp(null, rows, 150f, radius, floating = true)
            }
            assertTrue("row=$rows should respond to global Corners: $values",
                values[0] < values[1] && values[1] < values[2])
        }
    }

    @Test fun manualRadiusStaysPerBarAndIsBoundedByThicknessAndRows() {
        for (rows in 1..3) {
            for (thickness in listOf(22f, 50f, 86f, 140f)) {
                val limit = toolbarCornerLimitDp(rows, thickness)
                assertTrue(limit <= thickness / 2f)
                assertTrue(limit <= when (rows) { 1 -> 40f; 2 -> 26f; else -> 20f })
                assertEquals(0f, toolbarCornerRadiusDp(0, rows, thickness, 28f, true), 0.01f)
                assertEquals(limit, toolbarCornerRadiusDp(40, rows, thickness, 28f, true), 0.01f)
                assertEquals(6f.coerceAtMost(limit),
                    toolbarCornerRadiusDp(6, rows, thickness, 28f, true), 0.01f)
            }
        }
        // A manually shortened bar cannot have a radius larger than half its length.
        assertEquals(24f, toolbarCornerLimitDp(2, 100f, 48f), 0.01f)
        assertEquals(24f, toolbarCornerRadiusDp(40, 2, 100f, 28f, true, 48f), 0.01f)
        val first = ToolbarGeometry(cornerRadiusDp = 4)
        val second = ToolbarGeometry(cornerRadiusDp = 18)
        assertEquals(4, first.cornerRadiusDp)
        assertEquals(18, second.cornerRadiusDp)
        assertEquals(null, ToolbarGeometry().cornerRadiusDp)
    }

    @Test fun dockedDefaultsToSquareButManualOverrideIsHonored() {
        for (rows in 1..3) {
            assertEquals(0f, toolbarCornerRadiusDp(null, rows, 120f, 28f, false), 0.01f)
            assertEquals(7f, toolbarCornerRadiusDp(7, rows, 120f, 28f, false), 0.01f)
        }
    }
}
