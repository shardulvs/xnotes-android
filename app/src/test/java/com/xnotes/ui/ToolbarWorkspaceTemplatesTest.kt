package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.settings.ToolbarWorkspaceJson
import com.xnotes.settings.ToolbarWorkspaceTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarWorkspaceTemplatesTest {
    @Test fun builtinsKeepProfilesIndependentCompatibleAndRoundTrippable() {
        val presets = ToolbarWorkspaceTemplates.presets
        assertTrue(presets.size >= 7)
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        for (preset in presets) {
            assertEquals(preset.workspaces, ToolbarWorkspaceJson.read(ToolbarWorkspaceJson.write(preset.workspaces)))
            for (context in ToolbarContext.entries) {
                val workspace = preset.workspaces.forContext(context)
                val permitted = if (context == ToolbarContext.NOTE) ToolbarLayout.NOTE_ITEMS
                    else ToolbarLayout.CANVAS_ITEMS
                assertTrue(preset.name, workspace.bars.isNotEmpty())
                assertEquals(preset.name, workspace.bars.size, workspace.bars.map { it.id }.distinct().size)
                workspace.bars.forEach { bar ->
                    assertTrue(preset.name, bar.layout.sections.flatMap { it.entries }.all { it.item in permitted })
                }
            }
        }
    }

    @Test fun builtinsFitTabletViewportWithNoUnplacedBar() {
        for (preset in ToolbarWorkspaceTemplates.presets) {
            for (context in ToolbarContext.entries) {
                val workspace = preset.workspaces.forContext(context)
                val dock = toolbarDockPlan(workspace, preset.look, 960.dp, 640.dp)
                val floating = toolbarFloatingPlan(workspace, preset.look,
                    960.dp - dock.leftInset.dp - dock.rightInset.dp,
                    640.dp - dock.topInset.dp - dock.bottomInset.dp,
                    preset.colorCount)
                assertTrue("${preset.name} $context dock: ${dock.rejected}", dock.rejected.isEmpty())
                assertTrue("${preset.name} $context float: ${floating.rejected}", floating.rejected.isEmpty())
            }
        }
    }
}
