package com.xnotes.settings

import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.core.tools.ToolbarWorkspaces
import com.xnotes.core.tools.prepareToolbarTransfer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarWorkspacePresetsTest {
    @Test fun transferToCanvasFiltersOnlyIncompatibleToolsAndPreservesGeometryAndSections() {
        val original = ToolbarBar(
            id = "note-primary",
            name = "Minhas ferramentas",
            layout = ToolbarLayout(
                sections = listOf(
                    ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN, sizeDp = 44),
                        ToolbarEntry(ToolbarItem.TEXT_BOX),
                        ToolbarEntry(ToolbarItem.COLORS, colorPart = 1, colorParts = 2))),
                    ToolbarSection(listOf(ToolbarEntry(ToolbarItem.ERASER))),
                ),
                distribution = ToolbarDistribution.REGIONS,
            ),
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.BOTTOM_LEFT, spacingDp = 9, columns = 2),
        )
        val destination = ToolbarWorkspace(listOf(ToolbarBar("canvas-primary", "Main", ToolbarLayout.CANVAS_DEFAULT)))
        val draft = prepareToolbarTransfer(original, destination, ToolbarContext.CANVAS)
        assertEquals(listOf(ToolbarItem.TEXT_BOX), draft.omitted)
        assertEquals(3, draft.retainedCount)
        assertEquals(original.geometry, draft.bar.geometry)
        assertEquals(original.layout.distribution, draft.bar.layout.distribution)
        assertEquals(2, draft.bar.layout.sections.size)
        assertEquals(44, draft.bar.layout.sections[0].entries[0].sizeDp)
        assertEquals(2, draft.bar.layout.sections[0].entries[1].colorParts)
        assertEquals(3, original.layout.sections.sumOf { it.entries.size } - draft.omitted.size)
        assertTrue(original.layout.sections[0].entries.any { it.item == ToolbarItem.TEXT_BOX })
        assertNotEquals(original.id, draft.bar.id)
    }

    @Test fun transferToNoteRemovesCanvasOnlyElementsWithoutReorderingOthers() {
        val source = ToolbarBar("source", "Navigation", ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.MINIMAP),
                ToolbarEntry(ToolbarItem.PAN), ToolbarEntry(ToolbarItem.WAYPOINTS),
                ToolbarEntry(ToolbarItem.PEN))),
        )))
        val dest = ToolbarWorkspace(listOf(ToolbarBar("primary", "Main", ToolbarLayout.DEFAULT)))
        val draft = prepareToolbarTransfer(source, dest, ToolbarContext.NOTE)
        assertEquals(listOf(ToolbarItem.MINIMAP, ToolbarItem.WAYPOINTS), draft.omitted)
        assertEquals(listOf(ToolbarItem.PAN, ToolbarItem.PEN), draft.bar.layout.sections[0].entries.map { it.item })
        val second = prepareToolbarTransfer(source, dest.addBar(draft.bar), ToolbarContext.NOTE)
        assertNotEquals(draft.bar.id, second.bar.id)
    }

    @Test fun namedSnapshotRoundTripsBothProfilesWithoutTouchingCurrentLayout() {
        val workspace = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
            .copy(note = ToolbarWorkspace.fromLegacy("note-primary", "Principal", ToolbarLayout.DEFAULT)
                .addBar(ToolbarBar("note-colors", "Cores", ToolbarLayout.DEFAULT,
                    geometry = ToolbarGeometry(anchor = ToolbarAnchor.BOTTOM_RIGHT, floating = true))),
                shared = true)
        val saved = ToolbarWorkspacePreset(
            "saved-1", "Estudos", workspace,
            look = ToolbarLook(position = ToolbarPosition.BOTTOM, size = ToolbarSize.COMPACT, floating = true),
            colors = com.xnotes.core.tools.InkPalette.legacyPresets.toMutableList().also {
                it[0] = com.xnotes.core.model.Rgba(12, 33, 44, 255)
            },
            colorCount = 1,
        )
        val current = ToolbarWorkspaces.fromLegacy(
            ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.ERASER))))),
            ToolbarLayout.CANVAS_DEFAULT,
        )
        val settings = Settings(toolbarWorkspaces = current, toolbarWorkspacePresets = listOf(saved))
        val restored = Settings.fromJson(JSONObject(settings.toJson().toString()))
        assertEquals(current, restored.effectiveToolbarWorkspaces())
        assertEquals(listOf(saved), restored.toolbarWorkspacePresets)
    }

    @Test fun malformedAndDuplicatePresetsAreIgnored() {
        val workspace = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val valid = ToolbarWorkspacePresetsJson.write(listOf(ToolbarWorkspacePreset("a", "Estudos", workspace)))
            .getJSONObject(0)
        val json = JSONArray().put(valid).put(valid).put(JSONObject().put("id", "invalid").put("name", "Broken"))
        assertEquals(1, ToolbarWorkspacePresetsJson.read(json).size)
        assertFalse(ToolbarWorkspacePresetsJson.read(json).isEmpty())
    }

    @Test fun applyingPresetToNoteDoesNotReplaceCanvasEvenWhenPreviouslyShared() {
        val current = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
            .copy(shared = true)
        val preset = ToolbarWorkspaces(
            note = ToolbarWorkspace.fromLegacy("study-note", "Estudo", ToolbarLayout.DEFAULT),
            canvas = ToolbarWorkspace.fromLegacy("drawing-canvas", "Desenho", ToolbarLayout.CANVAS_DEFAULT),
        )
        val applied = current.replaceContextFrom(preset, ToolbarContext.NOTE)
        assertEquals("study-note", applied.note.bars.first().id)
        assertEquals(current.canvas, applied.canvas)
        assertFalse(applied.shared)
        assertTrue(current.shared)
    }

    @Test fun applyingPresetToCanvasUsesItsVisibleProfileAndPreservesNote() {
        val current = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val preset = ToolbarWorkspaces(
            note = ToolbarWorkspace.fromLegacy("shared-note", "Escrita", ToolbarLayout(listOf(
                ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN), ToolbarEntry(ToolbarItem.TEXT_BOX))),
            ))),
            canvas = ToolbarWorkspace.fromLegacy("ignored-canvas", "Separado", ToolbarLayout.CANVAS_DEFAULT),
            shared = true,
        )
        val applied = current.replaceContextFrom(preset, ToolbarContext.CANVAS)
        assertEquals(current.note, applied.note)
        assertFalse(applied.shared)
        assertEquals("shared-note", applied.canvas.bars.first().id)
        assertEquals(listOf(ToolbarItem.PEN), applied.canvas.bars.first().layout.sections.first().entries.map { it.item })
    }
}
