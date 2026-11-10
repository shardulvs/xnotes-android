package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolbarToolGroupTest {
    private val pen = ToolbarItem.PEN
    private val highlighter = ToolbarItem.HIGHLIGHTER

    @Test fun selectionPreservesMembershipAndIgnoresUnknown() {
        val original = requireNotNull(ToolbarToolGroup.create("pens", listOf(pen, highlighter, pen)))
        assertEquals(listOf(pen, highlighter), original.items)
        assertEquals(pen, original.lastUsed)
        val selected = original.select(highlighter)
        assertEquals(highlighter, selected.lastUsed)
        assertEquals(pen, original.lastUsed)
        assertEquals(selected, selected.select(ToolbarItem.ERASER))
    }

    @Test fun contextProjectionDoesNotMutateSource() {
        val source = requireNotNull(ToolbarToolGroup.create("pens", listOf(pen, highlighter), highlighter))
        val projected = source.forContext(setOf(pen))
        assertNotNull(projected)
        assertEquals(pen, projected?.lastUsed)
        assertEquals(listOf(pen), projected?.items)
        assertEquals(highlighter, source.lastUsed)
        assertNull(source.forContext(setOf(ToolbarItem.ERASER)))
    }

    @Test fun validationRejectsNonToolActionsAndInvalidIds() {
        assertNull(ToolbarToolGroup.create("", listOf(pen)))
        assertNull(ToolbarToolGroup.create("pens", emptyList()))
        assertNull(ToolbarToolGroup.create("pens", listOf(pen, ToolbarItem.UNDO)))
        assertFalse(ToolbarToolGroup.defaultWritingGroup().items.isEmpty())
    }
}
