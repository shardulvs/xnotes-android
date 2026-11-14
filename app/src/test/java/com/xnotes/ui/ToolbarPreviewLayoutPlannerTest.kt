package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.*
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.*
import org.junit.Test

class ToolbarPreviewLayoutPlannerTest {
    private val tools = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
    private fun dock(id: String) = ToolbarBar(id, id, tools,
        geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP, widthDp = 140, heightDp = 55))
    private fun float(id: String) = ToolbarBar(id, id, tools,
        geometry = ToolbarGeometry(anchor = ToolbarAnchor.BOTTOM_RIGHT,
            floating = true, widthDp = 120, heightDp = 52))

    @Test fun floatOffsetsFollowDockInsetsAndBarIdentityRemainsStable() {
        val workspace = ToolbarWorkspace(listOf(dock("main"), dock("second"), float("float")))
        val base = toolbarPreviewLayoutPlan(workspace, ToolbarLook(), 1000.dp, 650.dp, 5)
        assertTrue(base.docked.rejected.isEmpty())
        assertNotNull(base.position("main"))
        assertFalse(base.position("main")!!.floating)
        assertTrue(base.position("float")!!.floating)
        val actual = base.position("float")!!
        assertEquals(base.docked.leftInset + base.floating.placement("float")!!.x, actual.x, 0.01f)
        assertEquals(base.docked.topInset + base.floating.placement("float")!!.y, actual.y, 0.01f)
        assertNull(base.position("missing"))
        assertEquals(listOf("main", "second"), toolbarPreviewPeers(workspace, base)["main"])
    }

    @Test fun previewOrderDoesNotModifyBaseAndOnlyOtherBarsMove() {
        val workspace = ToolbarWorkspace(listOf(dock("main"), dock("one"), dock("two")))
        val base = toolbarPreviewLayoutPlan(workspace, ToolbarLook(), 1200.dp, 720.dp, 5)
        val preview = toolbarPreviewLayoutPlan(workspace.reorderVisualBar("two", "one"),
            ToolbarLook(), 1200.dp, 720.dp, 5)
        assertEquals(workspace.bars.map { it.id }, listOf("main", "one", "two"))
        assertEquals(base.position("main")!!.x, preview.position("main")!!.x, 0.01f)
        assertTrue(preview.position("two")!!.x < base.position("two")!!.x)
    }
}
