package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarResetPlannerTest {
    private val compact = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
    private val primary = ToolbarBar("main", "Main", compact, geometry = ToolbarGeometry(
        floating = true, anchor = ToolbarAnchor.TOP_LEFT))

    @Test fun resetCannotEvictSecondaryBarsFromAPackedFloatingPreview() {
        val workspace = ToolbarWorkspace(listOf(primary) + (1..8).map { index ->
            primary.copy(id = "secondary-$index", geometry = primary.geometry.copy(widthDp = 150))
        })
        for (context in ToolbarContext.entries) {
            assertTrue(toolbarPlacementFits(workspace, ToolbarLook(), 500.dp, 1000.dp, 5))
            val defaults = if (context == ToolbarContext.NOTE) ToolbarLayout.DEFAULT else ToolbarLayout.CANVAS_DEFAULT
            val unchecked = workspace.replaceBar(primary.copy(layout = defaults))
            assertFalse(toolbarFloatingPlan(unchecked, ToolbarLook(), 500.dp, 1000.dp, 5).rejected.isEmpty())
            assertNull(planToolbarReset(workspace, "main", context, ToolbarLook(), 500.dp, 1000.dp, 5))
            assertEquals(9, workspace.bars.size)
            assertEquals(compact, workspace.bars.first().layout)
        }
    }

    @Test fun resetWithSpacePreservesSecondaryBarsAndPrimaryGeometry() {
        val secondary = primary.copy(id = "secondary", geometry = primary.geometry.copy(
            anchor = ToolbarAnchor.BOTTOM_RIGHT, widthDp = 150))
        val workspace = ToolbarWorkspace(listOf(primary, secondary))
        for (context in ToolbarContext.entries) {
            val reset = planToolbarReset(workspace, "main", context, ToolbarLook(), 1000.dp, 1000.dp, 5)
            assertNotNull(reset)
            assertEquals(secondary, reset!!.bars.last())
            assertEquals(primary.geometry, reset.bars.first().geometry)
            assertEquals(if (context == ToolbarContext.NOTE) ToolbarLayout.DEFAULT else ToolbarLayout.CANVAS_DEFAULT,
                reset.bars.first().layout)
            assertEquals(compact, workspace.bars.first().layout)
        }
    }
}
