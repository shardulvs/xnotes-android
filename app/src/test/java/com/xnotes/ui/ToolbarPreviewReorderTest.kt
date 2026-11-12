package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.*
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.*
import org.junit.Test

class ToolbarPreviewReorderTest {
    private val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
    private fun dock(id: String, alignment: ToolbarDockAlignment = ToolbarDockAlignment.CENTER) =
        ToolbarBar(id, id, layout, geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP,
            dockAlignment = alignment, widthDp = 120, heightDp = 52))
    private fun floating(id: String) = ToolbarBar(id, id, layout, geometry = ToolbarGeometry(
        floating = true, anchor = ToolbarAnchor.TOP_CENTER, widthDp = 120, heightDp = 52))

    @Test fun threeDockedCenterBarsReorderWithoutChangingPrimaryOrLane() {
        val workspace = ToolbarWorkspace(listOf(dock("primary"), dock("second"), dock("third"),
            dock("start", ToolbarDockAlignment.START)))
        val original = toolbarDockPlan(workspace, ToolbarLook(), 1200.dp, 700.dp)
        assertEquals(listOf("primary", "second", "third"),
            toolbarPreviewReorderPeers(workspace, "third", original,
                toolbarFloatingPlan(workspace, ToolbarLook(), 1200.dp, 700.dp, 5)))
        val updated = workspace.reorderVisualBar("third", "primary")
        assertEquals("primary", updated.bars.first().id)
        assertEquals(workspace.bars.map { it.id }, updated.bars.map { it.id })
        val changed = toolbarDockPlan(updated, ToolbarLook(), 1200.dp, 700.dp)
        assertTrue(changed.rejected.isEmpty())
        assertTrue(changed.placement("third")!!.x < changed.placement("primary")!!.x)
        assertTrue(changed.placement("primary")!!.x < changed.placement("second")!!.x)
        assertEquals(original.placement("start")!!.x, changed.placement("start")!!.x, 0.01f)
    }

    @Test fun floatingCenterPeersReorderWithoutTouchingOtherEdges() {
        val w = ToolbarWorkspace(listOf(floating("main"), floating("one"), floating("two"),
            ToolbarBar("elsewhere", "Other", layout, geometry = ToolbarGeometry(
                floating = true, anchor = ToolbarAnchor.BOTTOM_RIGHT, widthDp = 90, heightDp = 52))))
        val emptyDock = toolbarDockPlan(w, ToolbarLook(floating = true), 1200.dp, 700.dp)
        val before = toolbarFloatingPlan(w, ToolbarLook(floating = true), 1200.dp, 700.dp, 5)
        assertEquals(listOf("main", "one", "two"), toolbarPreviewReorderPeers(w,
            "two", emptyDock, before))
        val after = toolbarFloatingPlan(w.reorderVisualBar("two", "main"),
            ToolbarLook(floating = true), 1200.dp, 700.dp, 5)
        assertTrue(after.placement("two")!!.x < after.placement("main")!!.x)
        assertEquals(before.placement("elsewhere")!!.y, after.placement("elsewhere")!!.y, 0.01f)
    }

    @Test fun dragTargetDoesNotJumpAcrossLanesOrScreen() {
        val bounds = mapOf("a" to Rect(0f, 0f, 120f, 40f),
            "b" to Rect(130f, 0f, 250f, 40f),
            "c" to Rect(260f, 0f, 380f, 40f),
            "different" to Rect(130f, 100f, 250f, 140f))
        val peers = listOf("a", "b", "c")
        assertEquals("c", toolbarPreviewReorderHit("a", peers, Offset(310f, 20f), bounds, false))
        assertNull(toolbarPreviewReorderHit("a", peers, Offset(180f, 125f), bounds, false))
        assertNull(toolbarPreviewReorderHit("a", peers, Offset(900f, 900f), bounds, false))
        assertNull(toolbarPreviewReorderHit("a", listOf("a"), Offset(190f, 20f), bounds, false))
    }

    @Test fun legacyWorkspacesKeepSourceOrderingUntilExplicitReorder() {
        val initial = ToolbarWorkspace(listOf(dock("one"), dock("two"), dock("three")))
        assertTrue(initial.bars.all { it.geometry.visualOrder == null })
        assertSame(initial, initial.reorderVisualBar("missing", "one"))
        val after = initial.reorderVisualBar("three", "one")
        assertEquals(listOf("three", "one", "two"),
            after.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        assertEquals(listOf("one", "two", "three"), after.bars.map { it.id })
    }
}
