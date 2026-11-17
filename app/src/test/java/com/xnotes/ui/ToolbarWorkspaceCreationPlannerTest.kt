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

/** Immutable planning; this suite never needs Editor, persistence, or Compose composition. */
class ToolbarWorkspaceCreationPlannerTest {
    private val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
    private val primary = ToolbarBar("main", "Primary", layout, geometry = ToolbarGeometry(
        anchor = ToolbarAnchor.TOP_LEFT, floating = true, widthDp = 150))
    private val look = ToolbarLook(floating = true)

    @Test fun newBarUsesFirstAvailableStableIdAndPreservesPrimary() {
        val existing = primary.copy(id = "bar-1")
        val original = ToolbarWorkspace(listOf(primary, existing))
        for (context in ToolbarContext.entries) {
            val result = planToolbarAdd(original, context, "Bar", look, 1000.dp, 900.dp, 5)
            assertNotNull(result)
            val created = result!!.workspace.bars.last()
            assertEquals("bar-2", result.selectedBarId)
            assertEquals("Bar 3", created.name)
            assertEquals(primary, result.workspace.bars.first())
            assertTrue(created.geometry.floating)
            assertEquals(ToolbarAnchor.BOTTOM_RIGHT, created.geometry.anchor)
            assertTrue(created.layout.sections.flatMap { it.entries }.isNotEmpty())
            assertTrue(created.layout.sections.flatMap { it.entries }
                .all { it.visible == (it.item == ToolbarItem.COLORS) })
            assertEquals(2, original.bars.size)
        }
    }

    @Test fun duplicatePreservesSourceToolsButNotItsVisualOrder() {
        val original = ToolbarWorkspace(listOf(primary.copy(
            geometry = primary.geometry.copy(visualOrder = 8))))
        val result = planToolbarDuplicate(original, "main", "Bar", look, 1000.dp, 900.dp, 5)
        assertNotNull(result)
        assertEquals("bar-1", result!!.selectedBarId)
        val copied = result.workspace.bars.last()
        assertEquals("Bar 2", copied.name)
        assertEquals(layout, copied.layout)
        assertNull(copied.geometry.visualOrder)
        assertEquals(original.bars.first(), result.workspace.bars.first())
        assertEquals(1, original.bars.size)
    }

    @Test fun duplicatingUnknownIdDoesNotMutateWorkspace() {
        val workspace = ToolbarWorkspace(listOf(primary))
        assertNull(planToolbarDuplicate(workspace, "missing", "Bar", look, 1000.dp, 900.dp, 5))
        assertFalse(workspace.bars.any { it.id.startsWith("bar-") })
    }
}
