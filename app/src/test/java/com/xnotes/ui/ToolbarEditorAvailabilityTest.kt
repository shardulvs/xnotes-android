package com.xnotes.ui

import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarEditorAvailabilityTest {
    private val items = linkedSetOf(
        ToolbarItem.PEN, ToolbarItem.ERASER, ToolbarItem.COLORS,
        ToolbarItem.SPACER, ToolbarItem.UNDO, ToolbarItem.TITLE,
    )

    @Test fun excludesVisibleUniqueItemsAcrossAllSectionsButPreservesRepeatableTools() {
        val layout = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN), ToolbarEntry(ToolbarItem.COLORS))),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.TITLE), ToolbarEntry(ToolbarItem.SPACER))),
        ))
        assertEquals(listOf(ToolbarItem.ERASER, ToolbarItem.COLORS, ToolbarItem.SPACER, ToolbarItem.UNDO),
            toolbarAddableItems(layout, items))
    }

    @Test fun hiddenEntriesDoNotReserveUniqueTools() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(
            ToolbarEntry(ToolbarItem.PEN, visible = false),
            ToolbarEntry(ToolbarItem.TITLE, visible = false),
            ToolbarEntry(ToolbarItem.ERASER),
        ))))
        assertEquals(listOf(ToolbarItem.PEN, ToolbarItem.COLORS, ToolbarItem.SPACER,
            ToolbarItem.UNDO, ToolbarItem.TITLE), toolbarAddableItems(layout, items))
    }

    @Test fun orderAndAvailabilityFollowTheInputs() {
        val empty = ToolbarLayout(listOf(ToolbarSection(emptyList())))
        assertEquals(items.toList(), toolbarAddableItems(empty, items))
        assertEquals(emptyList<ToolbarItem>(), toolbarAddableItems(empty, emptySet()))
        assertEquals(listOf(ToolbarItem.TITLE, ToolbarItem.PEN),
            toolbarAddableItems(empty, linkedSetOf(ToolbarItem.TITLE, ToolbarItem.PEN)))
    }
}
