package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarCopyPlannerTest {
    private val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
    private val primary = ToolbarBar("main", "Main", layout)

    @Test fun copiedPrimaryMakesInheritedDockExplicitWithoutChangingSource() {
        val destination = ToolbarWorkspace(listOf(primary))
        val look = ToolbarLook(position = ToolbarPosition.RIGHT)
        val draft = planToolbarCopy(primary, true, destination, ToolbarContext.CANVAS,
            look, 1000.dp, 1000.dp, 5)!!
        assertEquals(ToolbarDockEdge.RIGHT, draft.bar.geometry.dockEdge)
        assertFalse(draft.bar.geometry.floating)
        assertNull(primary.geometry.dockEdge)
        assertEquals(listOf(primary), destination.bars)
        assertNotEquals(primary.id, draft.bar.id)
    }

    @Test fun fullDockFallsBackToFloatingWithoutChangingDimensions() {
        val destination = ToolbarWorkspace((0..2).map { index ->
            primary.copy(id = "dock-$index", geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP))
        })
        val source = primary.copy(geometry = ToolbarGeometry(widthDp = 250, paddingDp = 8))
        val draft = planToolbarCopy(source, true, destination, ToolbarContext.NOTE,
            ToolbarLook(), 1000.dp, 1000.dp, 5)!!
        assertTrue(draft.bar.geometry.floating)
        assertNull(draft.bar.geometry.dockEdge)
        assertEquals(source.geometry.widthDp, draft.bar.geometry.widthDp)
        assertEquals(source.geometry.paddingDp, draft.bar.geometry.paddingDp)
        assertTrue(toolbarPlacementFits(destination.addBar(draft.bar), ToolbarLook(),
            1000.dp, 1000.dp, 5))
    }

    @Test fun floatingCapacityAccountsForDocumentSpaceReservedByDocks() {
        val floating = primary.copy(id = "floating", geometry = ToolbarGeometry(
            floating = true, heightDp = 80, widthDp = 200))
        val docks = ToolbarWorkspace((0..2).map { index ->
            primary.copy(id = "dock-$index", geometry = ToolbarGeometry(
                dockEdge = ToolbarDockEdge.TOP, heightDp = 80))
        })
        assertTrue(toolbarPlacementFits(ToolbarWorkspace(listOf(floating)), ToolbarLook(),
            500.dp, 300.dp, 5))
        assertFalse(toolbarPlacementFits(docks.addBar(floating), ToolbarLook(),
            500.dp, 300.dp, 5))
    }
}
