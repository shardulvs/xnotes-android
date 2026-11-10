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

    @Test fun regionModeAndAnchorsSurviveContextFiltering() {
        val layout = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PAGE_NAV)), region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)), region = ToolbarRegion.CENTER),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.IMAGE)), region = ToolbarRegion.END),
        ), distribution = ToolbarDistribution.REGIONS)
        val note = ToolbarWorkspace(listOf(ToolbarBar("primary", "Main", layout)))
        val profiles = ToolbarWorkspaces(note, note, shared = true)

        for (context in ToolbarContext.entries) {
            val filtered = profiles.forContext(context).bars.single().layout
            assertEquals(ToolbarDistribution.REGIONS, filtered.distribution)
            assertEquals(listOf(ToolbarRegion.START, ToolbarRegion.CENTER, ToolbarRegion.END),
                filtered.sections.map { it.region })
            assertEquals(listOf(ToolbarItem.PEN), filtered.sections[1].visibleEntries.map { it.item })
        }
        assertTrue(profiles.forContext(ToolbarContext.CANVAS).bars.single()
            .layout.sections[0].entries.isEmpty())
        assertEquals(layout, note.bars.single().layout)
    }

    @Test fun individualToolSizesDoNotLeakAcrossRepeatedColors() {
        val colors = ToolbarLayout(listOf(ToolbarSection(listOf(
            ToolbarEntry(ToolbarItem.COLORS, colorPart = 0, colorParts = 2),
            ToolbarEntry(ToolbarItem.COLORS, colorPart = 1, colorParts = 2),
        ))))
        val changed = colors.setEntrySize(0, 1, 60)
        assertEquals(null, colors.sections[0].entries[0].sizeDp)
        assertEquals(null, changed.sections[0].entries[0].sizeDp)
        assertEquals(60, changed.sections[0].entries[1].sizeDp)
        assertEquals(null, changed.setEntrySize(0, 1, null).sections[0].entries[1].sizeDp)
        assertSame(changed, changed.setEntrySize(0, 1, 200))
    }

    @Test fun dimensionsAreClampedAndAutoSizeRemainsAuto() {
        assertEquals(ToolbarGeometry(), ToolbarGeometry().constrained())
        val limited = ToolbarGeometry(
            widthDp = 10, heightDp = 9000, paddingDp = -5,
            spacingDp = 99, elementSizeDp = 10, columns = 10,
        ).constrained()
        assertEquals(48, limited.widthDp)
        assertEquals(2048, limited.heightDp)
        assertEquals(0, limited.paddingDp)
        assertEquals(32, limited.spacingDp)
        assertEquals(28, limited.elementSizeDp)
        assertEquals(6, limited.columns)
        assertFalse(limited.floating)
        assertTrue(limited.movable)
        assertEquals(ToolbarAnchor.TOP_CENTER,
            ToolbarGeometry(anchor = ToolbarAnchor.CENTER).constrained().anchor)
    }

    @Test fun editingEitherStoredProfilePreservesTheOtherAndSharingChoice() {
        for (shared in listOf(false, true)) {
            for (context in ToolbarContext.entries) {
                val note = ToolbarWorkspace(listOf(shortBar("note", ToolbarItem.PAGE_NAV)))
                val canvas = ToolbarWorkspace(listOf(shortBar("canvas", ToolbarItem.MINIMAP)))
                val original = ToolbarWorkspaces(note, canvas, shared)
                val changed = original.updateWorkspace(context) { workspace ->
                    workspace.addBar(shortBar("palette", ToolbarItem.COLORS))
                        .moveBar("palette", 0)
                }
                assertEquals(shared, changed.shared)
                if (context == ToolbarContext.NOTE) {
                    assertSame(canvas, changed.canvas)
                    assertEquals(listOf("palette", "note"), changed.note.bars.map { it.id })
                    assertEquals(ToolbarItem.PAGE_NAV, changed.note.bars.last().layout
                        .sections.single().entries.single().item)
                } else {
                    assertSame(note, changed.note)
                    assertEquals(listOf("palette", "canvas"), changed.canvas.bars.map { it.id })
                    assertEquals(ToolbarItem.MINIMAP, changed.canvas.bars.last().layout
                        .sections.single().entries.single().item)
                }
                assertEquals(listOf("note"), original.note.bars.map { it.id })
                assertEquals(listOf("canvas"), original.canvas.bars.map { it.id })
            }
        }
    }

    @Test fun editingSharedSourcePreservesNoteOnlyToolsAndIndependentCanvas() {
        val note = ToolbarWorkspace(listOf(shortBar("note", ToolbarItem.PAGE_NAV)))
        val canvas = ToolbarWorkspace(listOf(shortBar("canvas", ToolbarItem.MINIMAP)))
        val original = ToolbarWorkspaces(note, canvas, shared = true)
        val changed = original.updateWorkspace(ToolbarContext.NOTE) { workspace ->
            val bar = workspace.bars.single()
            workspace.replaceBar(bar.copy(geometry = bar.geometry.movedTo(
                ToolbarAnchor.BOTTOM_RIGHT, floating = true)))
        }
        assertEquals(ToolbarAnchor.BOTTOM_RIGHT, changed.forContext(ToolbarContext.CANVAS)
            .bars.single().geometry.anchor)
        assertEquals(ToolbarItem.PAGE_NAV, changed.note.bars.single().layout
            .sections.single().entries.single().item)
        assertTrue(changed.forContext(ToolbarContext.CANVAS).bars.single().layout
            .sections.single().entries.isEmpty())
        assertSame(canvas, changed.copy(shared = false).canvas)
        assertEquals(ToolbarAnchor.TOP_CENTER, original.note.bars.single().geometry.anchor)
    }
}
