package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarEditorInteractionStateTest {
    private val layout = ToolbarLayout(listOf(
        ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN))),
    ))

    private fun target(state: ToolbarEditorInteractionState) = toolbarItemDropTarget(
        layout, Offset(40f, 40f), state.chipBounds, state.laneBounds,
        state.sectionBounds, state.regionBounds,
    )

    @Test fun switchingLayoutsCancelsDragAndPreventsDropsUsingOldMeasurements() {
        val state = ToolbarEditorInteractionState()
        val bounds = Rect(0f, 0f, 100f, 100f)
        state.selectedEntry.value = 0 to 0
        state.dragItem.value = 0 to 0
        state.dragSection.value = 0
        state.dropTarget.value = Triple(0, 0, 0)
        state.dragRegionTarget.value = ToolbarRegion.END
        state.sectionDropTarget.value = ToolbarSectionTarget(ToolbarRegion.END, 0)
        state.chipBounds[0 to 0] = bounds
        state.laneBounds[0 to 0] = bounds
        state.sectionBounds[0] = bounds
        state.sectionGrabBounds[0] = bounds
        state.regionBounds[ToolbarRegion.END] = bounds
        assertNotNull(target(state))

        state.resetForSelectionChange()

        assertNull(target(state))
        assertNull(state.selectedEntry.value)
        assertNull(state.dragItem.value)
        assertNull(state.dragSection.value)
        assertNull(state.dropTarget.value)
        assertNull(state.dragRegionTarget.value)
        assertNull(state.sectionDropTarget.value)
        assertTrue(state.chipBounds.isEmpty())
        assertTrue(state.laneBounds.isEmpty())
        assertTrue(state.sectionGrabBounds.isEmpty())
        assertTrue(state.regionBounds.isEmpty())

        // The new layout becomes droppable only after its own bounds are measured.
        state.sectionBounds[0] = bounds
        state.chipBounds[0 to 0] = bounds
        assertEquals(Triple(0, 0, 0), target(state)?.slot)
    }

    @Test fun clearingOneConfigurationScreenDoesNotCancelAnotherScreensDrag() {
        val first = ToolbarEditorInteractionState()
        val second = ToolbarEditorInteractionState()
        second.dragItem.value = 1 to 2
        second.sectionBounds[1] = Rect(0f, 0f, 100f, 100f)

        first.resetForSelectionChange()

        assertEquals(1 to 2, second.dragItem.value)
        assertTrue(second.sectionBounds.containsKey(1))
    }

    @Test fun retargetUsesLatestMeasurementsAndClearsAnUnavailableDestination() {
        val state = ToolbarEditorInteractionState()
        state.dragFinger.value = Offset(40f, 40f)
        state.sectionBounds[0] = Rect(0f, 0f, 100f, 100f)
        state.chipBounds[0 to 0] = Rect(0f, 0f, 100f, 100f)
        state.retargetItem(layout)
        assertEquals(Triple(0, 0, 0), state.dropTarget.value)

        // Scrolling moves the measured chip above the finger, changing the insertion slot.
        state.chipBounds[0 to 0] = Rect(0f, 0f, 100f, 20f)
        state.retargetItem(layout)
        assertEquals(Triple(0, 0, 1), state.dropTarget.value)

        state.resetForSelectionChange()
        state.retargetItem(layout)
        assertNull(state.dropTarget.value)
        assertNull(state.dragRegionTarget.value)
    }
}
