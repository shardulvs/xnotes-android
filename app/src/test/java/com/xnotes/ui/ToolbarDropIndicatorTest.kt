package com.xnotes.ui

import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarDropPosition
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class ToolbarDropIndicatorTest {
    private val bottomCenter = ToolbarDropPosition(
        ToolbarAnchor.BOTTOM_CENTER, ToolbarDockAlignment.CENTER)

    @Test fun projectedDockedMovePreservesModeAndRotatesDimensions() {
        val bar = ToolbarBar("main", "Main", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.BOTTOM_CENTER,
                dockEdge = ToolbarDockEdge.BOTTOM, widthDp = 280, heightDp = 64))
        val workspace = ToolbarWorkspace(listOf(bar))
        val target = ToolbarDropPosition(ToolbarAnchor.CENTER_LEFT, ToolbarDockAlignment.END)
        val (projected, look) = toolbarProjectedMove(workspace,
            ToolbarLook(position = ToolbarPosition.BOTTOM), "main", target)!!
        val result = projected.bars.first().geometry
        assertFalse(result.floating)
        assertEquals(ToolbarDockEdge.LEFT, result.dockEdge)
        assertEquals(ToolbarDockAlignment.END, result.dockAlignment)
        assertEquals(64, result.widthDp)
        assertEquals(280, result.heightDp)
        assertEquals(ToolbarPosition.LEFT, look.position)
        assertNotNull(toolbarDockDropSlot(workspace, ToolbarLook(position = ToolbarPosition.BOTTOM),
            "main", target, 960.dp, 600.dp))
        assertEquals(ToolbarDockEdge.BOTTOM, bar.geometry.dockEdge)
    }

    @Test fun previewAndRuntimeUseSameDockedDestination() {
        val bar = ToolbarBar("main", "Main", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.BOTTOM_CENTER,
                dockEdge = ToolbarDockEdge.BOTTOM, widthDp = 210, heightDp = 56))
        val workspace = ToolbarWorkspace(listOf(bar))
        val look = ToolbarLook(position = ToolbarPosition.BOTTOM)
        val target = ToolbarDropPosition(ToolbarAnchor.TOP_RIGHT, ToolbarDockAlignment.END)
        val dock = toolbarDockDropSlot(workspace, look, "main", target, 960.dp, 600.dp)!!
        val preview = toolbarPreviewDropSlot(workspace, look, "main", target,
            960.dp, 600.dp, 5)!!
        assertEquals(dock.x, preview.x, .01f)
        assertEquals(dock.y, preview.y, .01f)
        assertEquals(dock.width, preview.width, .01f)
        assertEquals(dock.height, preview.height, .01f)
    }

    @Test fun shorterBottomBarUsesItsOwnDepthNotTallestLaneDepth() {
        val tall = ToolbarBar("tall", "Tall", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.BOTTOM_CENTER,
                dockEdge = ToolbarDockEdge.BOTTOM, widthDp = 180, heightDp = 112,
                dockAlignment = ToolbarDockAlignment.CENTER))
        val short = ToolbarBar("short", "Short", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.BOTTOM_CENTER,
                dockEdge = ToolbarDockEdge.BOTTOM, widthDp = 180, heightDp = 52,
                dockAlignment = ToolbarDockAlignment.CENTER))
        val workspace = ToolbarWorkspace(listOf(tall, short))
        val look = ToolbarLook(position = ToolbarPosition.BOTTOM)
        val targetSlot = toolbarDockDropSlot(workspace, look, "short", bottomCenter,
            960.dp, 600.dp)!!
        assertEquals(600f, targetSlot.y + targetSlot.height, .01f)
        assertEquals(52f, targetSlot.height, .01f)
    }

    @Test fun legacyFloatingPrimaryKeepsFloatingWhenProjected() {
        val primary = ToolbarBar("main", "Main", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.TOP_CENTER,
                widthDp = 200, heightDp = 56))
        val colors = ToolbarBar("colors", "Colors", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.TOP_RIGHT,
                floating = true, widthDp = 140, heightDp = 56))
        val workspace = ToolbarWorkspace(listOf(primary, colors))
        val look = ToolbarLook(floating = true)
        val destination = ToolbarDropPosition(ToolbarAnchor.BOTTOM_LEFT,
            ToolbarDockAlignment.START)
        val (projected, resultLook) = toolbarProjectedMove(workspace, look,
            "main", destination)!!
        assertTrue(projected.bars.first().geometry.floating)
        assertTrue(resultLook.floating)
        assertNotNull(toolbarFloatingDropSlot(workspace, look, "main", destination,
            960.dp, 600.dp, 5))
    }
}
