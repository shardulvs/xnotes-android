package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.*
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarDockEdgeRegressionTest {
    private val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))

    @Test fun bottomAndTopDocksTouchMeasuredPaneEdgesRegardlessOfInternalPadding() {
        for (height in listOf(640, 800, 1200)) for (padding in listOf(0, 4, 24)) {
            val workspace = ToolbarWorkspace(listOf(
                ToolbarBar("top", "Top", layout, geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP, paddingDp = padding)),
                ToolbarBar("bottom", "Bottom", layout, geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.BOTTOM, paddingDp = padding)),
            ))
            val plan = toolbarDockPlan(workspace, ToolbarLook(), 1000.dp, height.dp)
            assertTrue(plan.rejected.isEmpty())
            val top = plan.placement("top")!!
            val bottom = plan.placement("bottom")!!
            assertEquals(0f, top.y, 0.001f)
            assertEquals(height.toFloat(), bottom.y + bottom.height, 0.001f)
            assertEquals(top.height, bottom.height, 0.001f)
            assertEquals(bottom.height, plan.bottomInset, 0.001f)
        }
    }

    @Test fun legacyBottomDockAlsoUsesPaneEdgeWithoutFloatingMargin() {
        val workspace = ToolbarWorkspace(listOf(ToolbarBar("primary", "Main", layout)))
        val plan = toolbarDockPlan(workspace, ToolbarLook(position = ToolbarPosition.BOTTOM), 1000.dp, 800.dp)
        val bottom = plan.placement("primary")!!
        assertEquals(800f, bottom.y + bottom.height, 0.001f)
    }
}
