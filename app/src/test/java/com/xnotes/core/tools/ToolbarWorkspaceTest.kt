package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarWorkspaceTest {
    private fun shortBar(id: String, item: ToolbarItem = ToolbarItem.PEN): ToolbarBar =
        ToolbarBar(
            id = id,
            name = "Bar $id",
            layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(item))))),
        )

    @Test fun legacyProfilesKeepTheirOriginalLayouts() {
        val migrated = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        assertEquals(1, migrated.note.bars.size)
        assertEquals(1, migrated.canvas.bars.size)
        assertEquals(ToolbarLayout.DEFAULT, migrated.forContext(ToolbarContext.NOTE).bars.single().layout)
        assertEquals(ToolbarLayout.CANVAS_DEFAULT, migrated.forContext(ToolbarContext.CANVAS).bars.single().layout)
    }

    @Test fun multipleBarsCanReuseTheSameTool() {
        val first = shortBar("primary")
        val workspace = ToolbarWorkspace(listOf(first)).addBar(shortBar("colors", ToolbarItem.PEN))
        assertEquals(2, workspace.bars.size)
        assertEquals(ToolbarItem.PEN, workspace.bars[0].layout.sections.single().entries.single().item)
        assertEquals(ToolbarItem.PEN, workspace.bars[1].layout.sections.single().entries.single().item)
    }

    @Test fun idsRemainUniqueAndRenamingDoesNotChangeIdentity() {
        val original = ToolbarWorkspace(listOf(shortBar("primary")))
        assertSame(original, original.addBar(shortBar("primary")))
        assertSame(original, original.addBar(shortBar("")))
        val renamed = original.replaceBar(original.bars.single().copy(name = "Handwriting"))
        assertEquals("primary", renamed.bars.single().id)
        assertEquals("Handwriting", renamed.bars.single().name)
    }

    @Test fun reorderAndRemoveKeepOtherBarsUntouched() {
        val original = ToolbarWorkspace(listOf(shortBar("a"), shortBar("b"), shortBar("c")))
        val moved = original.moveBar("a", 2)
        assertEquals(listOf("b", "c", "a"), moved.bars.map { it.id })
        assertEquals(listOf("a", "b", "c"), original.bars.map { it.id })
        val removed = moved.removeBar("c").removeBar("b")
        assertEquals(listOf("a"), removed.bars.map { it.id })
        assertSame(removed, removed.removeBar("a"))
    }

    @Test fun sharedWorkspaceFiltersUnavailableToolsWithoutFillingSecondaryBars() {
        val mixed = ToolbarLayout(listOf(ToolbarSection(listOf(
            ToolbarEntry(ToolbarItem.PEN),
            ToolbarEntry(ToolbarItem.PAGE_NAV),
        ))))
        val note = ToolbarWorkspace(listOf(
            ToolbarBar("primary", "Main", mixed),
            shortBar("secondary", ToolbarItem.IMAGE),
        ))
        val canvas = ToolbarWorkspace(listOf(shortBar("canvas", ToolbarItem.MINIMAP)))
        val profiles = ToolbarWorkspaces(note, canvas, shared = true)
        val filtered = profiles.forContext(ToolbarContext.CANVAS)

        assertEquals(2, filtered.bars.size)
        assertEquals(listOf(ToolbarItem.PEN), filtered.bars[0].layout.sections[0].entries.map { it.item })
        assertEquals(listOf(ToolbarItem.IMAGE), filtered.bars[1].layout.sections[0].entries.map { it.item })
        assertEquals(mixed, note.bars[0].layout)
        assertEquals(ToolbarItem.MINIMAP, profiles.copy(shared = false)
            .forContext(ToolbarContext.CANVAS).bars.single().layout.sections.single().entries.single().item)
    }

    @Test fun dimensionsAreClampedAndAutoSizeRemainsAuto() {
        assertEquals(ToolbarGeometry(), ToolbarGeometry().constrained())
        val limited = ToolbarGeometry(widthDp = 10, heightDp = 9000, columns = 10).constrained()
        assertEquals(48, limited.widthDp)
        assertEquals(2048, limited.heightDp)
        assertEquals(6, limited.columns)
        assertFalse(limited.floating)
        assertTrue(limited.movable)
    }
}
