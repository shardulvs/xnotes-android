package com.xnotes.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarRegion

/** Transient selection and drag data for one configuration screen; never persisted with a note. */
internal class ToolbarEditorInteractionState {
    val selectedEntry = mutableStateOf<Pair<Int, Int>?>(null)
    val dragItem = mutableStateOf<Pair<Int, Int>?>(null)
    val dragSection = mutableStateOf<Int?>(null)
    val dragFinger = mutableStateOf(Offset.Zero)
    val dragGrab = mutableStateOf(Offset.Zero)
    val dropTarget = mutableStateOf<Triple<Int, Int, Int>?>(null)
    val dragRegionTarget = mutableStateOf<ToolbarRegion?>(null)
    val sectionDropTarget = mutableStateOf<ToolbarSectionTarget?>(null)

    // Measurements are read only during gestures. Recording layout bounds must not recompose UI.
    val chipBounds = mutableMapOf<Pair<Int, Int>, Rect>()
    val laneBounds = mutableMapOf<Pair<Int, Int>, Rect>()
    val sectionBounds = mutableMapOf<Int, Rect>()
    val sectionGrabBounds = mutableMapOf<Int, Rect>()
    val regionBounds = mutableMapOf<ToolbarRegion, Rect>()

    fun retargetItem(layout: ToolbarLayout) {
        val target = toolbarItemDropTarget(layout, dragFinger.value, chipBounds, laneBounds,
            sectionBounds, regionBounds)
        dropTarget.value = target?.slot
        dragRegionTarget.value = target?.region
    }

    fun retargetSection(layout: ToolbarLayout) {
        sectionDropTarget.value = toolbarSectionDropTarget(layout, dragFinger.value, sectionBounds, regionBounds)
    }

    /** Old profile/bar measurements must never become drop targets in the newly selected layout. */
    fun resetForSelectionChange() {
        selectedEntry.value = null
        chipBounds.clear()
        laneBounds.clear()
        sectionBounds.clear()
        sectionGrabBounds.clear()
        regionBounds.clear()
        dragItem.value = null
        dragSection.value = null
        dropTarget.value = null
        dragRegionTarget.value = null
        sectionDropTarget.value = null
    }
}
