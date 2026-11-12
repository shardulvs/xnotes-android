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

    @Test fun rotatingMainToolbarBetweenAxesRotatesItsUserDimensions() {
        val starting = ToolbarGeometry(
            dockEdge = ToolbarDockEdge.TOP,
            anchor = ToolbarAnchor.TOP_CENTER,
            widthDp = 560, heightDp = 76, paddingDp = 14,
        )
        for (side in listOf(ToolbarAnchor.CENTER_LEFT, ToolbarAnchor.CENTER_RIGHT)) {
            val rotated = starting.movedTo(side, floating = false)
            assertEquals(76, rotated.widthDp)
            assertEquals(560, rotated.heightDp)
            assertEquals(14, rotated.paddingDp)
            val restored = rotated.movedTo(ToolbarAnchor.BOTTOM_CENTER, floating = false)
            assertEquals(560, restored.widthDp)
            assertEquals(76, restored.heightDp)
            assertEquals(ToolbarDockEdge.BOTTOM, restored.dockEdge)
        }
    }

    @Test fun anAutoLengthBecomesAutoDepthAfterCrossAxisMove() {
        val top = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP,
            anchor = ToolbarAnchor.TOP_CENTER, widthDp = 460, heightDp = null)
        val left = top.movedTo(ToolbarAnchor.CENTER_LEFT, floating = false)
        assertNull(left.widthDp)
        assertEquals(460, left.heightDp)
        val reverted = left.movedTo(ToolbarAnchor.TOP_CENTER, floating = false)
        assertEquals(top.widthDp, reverted.widthDp)
        assertEquals(top.heightDp, reverted.heightDp)
    }

    @Test fun settingsDockSelectionOverridesStaleMainEdgeAndAlignsFlush() {
        val layouts = listOf(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        for (layout in layouts) for ((width, height) in listOf(1120 to 720, 720 to 1120, 1100 to 540)) {
            val previous = ToolbarBar("main", "Main", layout,
                geometry = ToolbarGeometry(anchor = ToolbarAnchor.TOP_CENTER,
                    dockEdge = ToolbarDockEdge.TOP, widthDp = 430, heightDp = 70, paddingDp = 5))
            val look = ToolbarLook(position = ToolbarPosition.TOP)
            for ((target, anchor) in listOf(
                ToolbarDockEdge.LEFT to ToolbarAnchor.CENTER_LEFT,
                ToolbarDockEdge.RIGHT to ToolbarAnchor.CENTER_RIGHT,
                ToolbarDockEdge.BOTTOM to ToolbarAnchor.BOTTOM_CENTER,
            )) {
                val newGeometry = toolbarGeometryForDockSelection(previous, 0, look, target)
                assertEquals(target, newGeometry.dockEdge)
                assertEquals(anchor, newGeometry.anchor)
                assertEquals(5, newGeometry.paddingDp)
                val updated = previous.copy(geometry = newGeometry)
                val plan = toolbarDockPlan(ToolbarWorkspace(listOf(updated)), look, width.dp, height.dp)
                assertTrue("rejected $target on ${width}x$height: ${plan.rejected}", plan.rejected.isEmpty())
                val placed = plan.placement("main")!!
                assertEquals(target, placed.edge)
                if (target == ToolbarDockEdge.LEFT) {
                    assertEquals(0f, placed.x, 0.01f)
                    assertEquals(70f, placed.width, 0.01f)
                    assertEquals(430f, placed.height, 0.01f)
                }
                if (target == ToolbarDockEdge.RIGHT) assertEquals(width.toFloat(), placed.x + placed.width, 0.01f)
                if (target == ToolbarDockEdge.BOTTOM) assertEquals(height.toFloat(), placed.y + placed.height, 0.01f)
            }
        }
    }

    @Test fun legacyLookIsUsedAsSourceOfDimensionsNotStaleAnchor() {
        val bar = ToolbarBar("main", "Main", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.TOP_CENTER,
                widthDp = 68, heightDp = 460))
        val look = ToolbarLook(position = ToolbarPosition.LEFT)
        val moved = toolbarGeometryForDockSelection(bar, 0, look, ToolbarDockEdge.BOTTOM)
        // Even though the stored anchor says TOP, the effective previous orientation is LEFT.
        assertEquals(68, moved.heightDp)
        assertEquals(460, moved.widthDp)
        assertEquals(ToolbarDockEdge.BOTTOM, moved.dockEdge)
    }

    @Test fun oversizedFixedDockClipsToAvailableEdgeInsteadOfRejectingPosition() {
        val item = ToolbarLayout(listOf(com.xnotes.core.tools.ToolbarSection(listOf(
            com.xnotes.core.tools.ToolbarEntry(com.xnotes.core.tools.ToolbarItem.PEN)))))
        for ((width, height) in listOf(980 to 700, 1100 to 495)) {
            val top = ToolbarBar("top", "Top", item,
                geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP, heightDp = 64))
            val bottom = ToolbarBar("bottom", "Bottom", item,
                geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.BOTTOM, heightDp = 64))
            for (edge in listOf(ToolbarDockEdge.LEFT, ToolbarDockEdge.RIGHT)) {
                val main = ToolbarBar("main", "Main", item,
                    geometry = ToolbarGeometry(dockEdge = edge, widthDp = 72, heightDp = 1200))
                val workspace = ToolbarWorkspace(listOf(top, bottom, main))
                val plan = toolbarDockPlan(workspace, ToolbarLook(), width.dp, height.dp)
                assertTrue("unexpected dock rejection at ${width}x$height for $edge: ${plan.rejected}",
                    plan.rejected.isEmpty())
                val placed = plan.placement(main.id)!!
                assertEquals(edge, placed.edge)
                assertEquals(plan.topInset, placed.y, 0.01f)
                assertEquals(height.toFloat() - plan.topInset - plan.bottomInset,
                    placed.height, 0.01f)
                assertTrue(toolbarPlacementFits(workspace, ToolbarLook(), width.dp, height.dp, 5))
            }
        }
    }

    @Test fun secondaryDockSelectionAlsoRotatesExplicitAndAutoDimensions() {
        val primary = ToolbarBar("main", "Main", ToolbarLayout.DEFAULT)
        val secondary = ToolbarBar("nav", "Navigation", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.BOTTOM,
                anchor = ToolbarAnchor.BOTTOM_CENTER, widthDp = 380, heightDp = null))
        val moved = toolbarGeometryForDockSelection(secondary, 1, ToolbarLook(), ToolbarDockEdge.RIGHT)
        assertEquals(null, moved.widthDp)
        assertEquals(380, moved.heightDp)
        assertEquals(ToolbarDockEdge.RIGHT, moved.dockEdge)
        assertEquals(ToolbarAnchor.CENTER_RIGHT, moved.anchor)
        val plan = toolbarDockPlan(ToolbarWorkspace(listOf(primary, secondary.copy(geometry = moved))),
            ToolbarLook(), 900.dp, 700.dp)
        assertTrue(plan.rejected.isEmpty())
        assertEquals(ToolbarDockEdge.RIGHT, plan.placement("nav")!!.edge)
    }

    @Test fun legacyPrimaryStillUsesGlobalDockPreference() {
        val moved = ToolbarGeometry().movedTo(ToolbarAnchor.CENTER_LEFT, floating = false)
        assertNull(moved.dockEdge)
        val bar = ToolbarBar("canvas-primary", "Main", ToolbarLayout.CANVAS_DEFAULT, geometry = moved)
        val plan = toolbarDockPlan(ToolbarWorkspace(listOf(bar)), ToolbarLook(position = ToolbarPosition.LEFT), 960.dp, 600.dp)
        assertEquals(ToolbarDockEdge.LEFT, plan.placement(bar.id)!!.edge)
    }
}
