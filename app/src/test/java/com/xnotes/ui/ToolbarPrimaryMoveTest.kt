package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarPrimaryMoveTest {
    @Test fun explicitDockFollowsPrimaryDragOnEveryEdge() {
        val original = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP, paddingDp = 6)
        val targets = listOf(
            Triple(ToolbarAnchor.CENTER_LEFT, ToolbarDockEdge.LEFT, ToolbarPosition.LEFT),
            Triple(ToolbarAnchor.CENTER_RIGHT, ToolbarDockEdge.RIGHT, ToolbarPosition.RIGHT),
            Triple(ToolbarAnchor.BOTTOM_CENTER, ToolbarDockEdge.BOTTOM, ToolbarPosition.BOTTOM),
            Triple(ToolbarAnchor.TOP_CENTER, ToolbarDockEdge.TOP, ToolbarPosition.TOP),
        )
        for ((anchor, edge, position) in targets) {
            val moved = original.movedTo(anchor, floating = false)
            val bar = ToolbarBar("canvas-primary", "Main", ToolbarLayout.CANVAS_DEFAULT, geometry = moved)
            val plan = toolbarDockPlan(ToolbarWorkspace(listOf(bar)), ToolbarLook(position = position), 960.dp, 600.dp)
            assertTrue(plan.rejected.isEmpty())
            assertEquals(edge, plan.placement(bar.id)!!.edge)
            assertEquals(6, moved.paddingDp)
            assertFalse(moved.floating)
        }
        assertEquals(ToolbarDockEdge.TOP, original.dockEdge)
    }

    @Test fun floatingDropIsNotCapturedByOldExplicitDock() {
        val moved = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP)
            .movedTo(ToolbarAnchor.BOTTOM_RIGHT, floating = true)
        val bar = ToolbarBar("canvas-primary", "Main", ToolbarLayout.CANVAS_DEFAULT, geometry = moved)
        val workspace = ToolbarWorkspace(listOf(bar))
        assertNull(toolbarDockPlan(workspace, ToolbarLook(), 960.dp, 600.dp).placement(bar.id))
        assertNotNull(toolbarFloatingPlan(workspace, ToolbarLook(), 960.dp, 600.dp, 5).placement(bar.id))
    }

    @Test fun legacyPrimaryStillUsesGlobalDockPreference() {
        val moved = ToolbarGeometry().movedTo(ToolbarAnchor.CENTER_LEFT, floating = false)
        assertNull(moved.dockEdge)
        val bar = ToolbarBar("canvas-primary", "Main", ToolbarLayout.CANVAS_DEFAULT, geometry = moved)
        val plan = toolbarDockPlan(ToolbarWorkspace(listOf(bar)), ToolbarLook(position = ToolbarPosition.LEFT), 960.dp, 600.dp)
        assertEquals(ToolbarDockEdge.LEFT, plan.placement(bar.id)!!.edge)
    }
}
