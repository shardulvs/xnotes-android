package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarModeConversionTest {
    private val compact = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
    private val primary = ToolbarBar("main", "Main", compact)

    @Test fun legacyFloatingPrimaryUsesItsDisplayedSideForBothConversions() {
        val look = ToolbarLook(position = ToolbarPosition.RIGHT, floating = true)
        assertEquals(ToolbarAnchor.CENTER_RIGHT, toolbarFloatingGeometry(primary, 0, look).anchor)
        assertEquals(ToolbarDockEdge.RIGHT, toolbarDockedGeometry(primary, 0, look).dockEdge)
    }

    @Test fun fullHeightLateralDockCanFloatDespiteAnOldTopAnchor() {
        for (edge in listOf(ToolbarDockEdge.LEFT, ToolbarDockEdge.RIGHT)) {
            for (index in 0..1) {
                val bar = primary.copy(id = "lateral", geometry = ToolbarGeometry(
                    dockEdge = edge, heightDp = 600, anchor = ToolbarAnchor.TOP_CENTER))
                val workspace = ToolbarWorkspace(if (index == 0) listOf(bar) else listOf(primary, bar))
                val oldFloating = bar.copy(geometry = bar.geometry.copy(floating = true, dockEdge = null))
                // Previously rejected because the saved vertical thickness was 600dp
                // on a 600dp-high screen. It now clips safely instead of disappearing.
                assertTrue(toolbarPlacementFits(workspace.replaceBar(oldFloating), ToolbarLook(), 1000.dp, 600.dp, 5))
                val converted = toolbarFloatingGeometry(bar, index, ToolbarLook())
                assertEquals(if (edge == ToolbarDockEdge.LEFT) ToolbarAnchor.CENTER_LEFT else ToolbarAnchor.CENTER_RIGHT,
                    converted.anchor)
                assertEquals(600, converted.heightDp)
                assertNull(converted.dockEdge)
                assertTrue(toolbarPlacementFits(workspace.replaceBar(bar.copy(geometry = converted)),
                    ToolbarLook(), 1000.dp, 600.dp, 5))
            }
        }
    }

    @Test fun floatingToDockedRoundTripRetainsEveryEdgeAndDimensions() {
        for ((edge, anchor) in listOf(
            ToolbarDockEdge.TOP to ToolbarAnchor.TOP_CENTER,
            ToolbarDockEdge.BOTTOM to ToolbarAnchor.BOTTOM_CENTER,
            ToolbarDockEdge.LEFT to ToolbarAnchor.CENTER_LEFT,
            ToolbarDockEdge.RIGHT to ToolbarAnchor.CENTER_RIGHT)) {
            for (index in 0..1) {
                val original = primary.copy(geometry = ToolbarGeometry(
                    floating = true, anchor = anchor, widthDp = 120, heightDp = 200))
                val docked = toolbarDockedGeometry(original, index, ToolbarLook())
                assertEquals(edge, docked.dockEdge)
                assertFalse(docked.floating)
                val floated = toolbarFloatingGeometry(original.copy(geometry = docked), index, ToolbarLook())
                assertEquals(original.geometry, floated)
            }
        }
    }
}
