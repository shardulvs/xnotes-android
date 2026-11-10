package com.xnotes.settings

import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarScale
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.core.tools.ToolbarWorkspaces
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarWorkspaceJsonTest {
    @Test fun primaryLayoutEditsKeepLegacySettingsLegacyOnly() {
        val initial = Settings()
        val note = ToolbarLayout.DEFAULT.moveSection(0, 2)
        val canvas = ToolbarLayout.CANVAS_DEFAULT.moveSection(0, 2)
        val updated = initial.withToolbarLayout(ToolbarContext.NOTE, note)
            .withToolbarLayout(ToolbarContext.CANVAS, canvas)
        val restored = Settings.fromJson(updated.toJson())
        assertNull(updated.toolbarWorkspaces)
        assertNull(restored.toolbarWorkspaces)
        assertEquals(note, restored.toolbarLayout)
        assertEquals(canvas, restored.canvasToolbarLayout)
        assertEquals(ToolbarLayout.DEFAULT, initial.toolbarLayout)
    }

    @Test fun primaryEditsRoundTripWithoutChangingSecondaryOrInactiveProfile() {
        val base = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val secondary = ToolbarBar("colors", "Colors", compactLayout(ToolbarItem.COLORS),
            geometry = ToolbarGeometry(floating = true, anchor = ToolbarAnchor.BOTTOM_RIGHT))
        val profiles = base.copy(note = base.note.addBar(secondary), shared = true)
        val initial = Settings().withToolbarWorkspaces(profiles)
        val layout = ToolbarLayout.DEFAULT.moveSection(0, 2)
        val updated = initial.withToolbarLayout(ToolbarContext.NOTE, layout)
        val restored = Settings.fromJson(updated.toJson())
        assertEquals(layout, restored.toolbarLayout)
        assertEquals(layout, restored.toolbarWorkspaces!!.note.bars.first().layout)
        assertEquals(secondary, restored.toolbarWorkspaces!!.note.bars.last())
        assertEquals(profiles.canvas, restored.toolbarWorkspaces!!.canvas)
        assertTrue(restored.toolbarWorkspaces!!.shared)
        assertEquals(profiles, initial.toolbarWorkspaces)
    }

    @Test fun fullWorkspaceSaveSynchronizesBothLegacyPrimariesAfterReordering() {
        val base = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val note = ToolbarLayout.DEFAULT.moveSection(0, 2)
        val canvas = ToolbarLayout.CANVAS_DEFAULT.moveSection(0, 2)
        val profiles = base.copy(
            note = base.note.addBar(ToolbarBar("note-new", "Note", note)).moveBar("note-new", 0),
            canvas = base.canvas.addBar(ToolbarBar("canvas-new", "Canvas", canvas)).moveBar("canvas-new", 0))
        val updated = Settings().withToolbarWorkspaces(profiles)
        val restored = Settings.fromJson(updated.toJson())
        assertEquals(profiles, restored.toolbarWorkspaces)
        assertEquals(note, restored.toolbarLayout)
        assertEquals(canvas, restored.canvasToolbarLayout)
        val legacyOnly = restored.toJson().apply { remove("toolbar_workspaces") }
        val oldReader = Settings.fromJson(legacyOnly).effectiveToolbarWorkspaces()
        assertEquals(note, oldReader.note.bars.single().layout)
        assertEquals(canvas, oldReader.canvas.bars.single().layout)
    }

    @Test fun experimentalGroupSettingsRollbackKeepsExistingBarsAndElements() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(
            ToolbarEntry(ToolbarItem.PEN), ToolbarEntry(ToolbarItem.ERASER),
        ))))
        val profiles = ToolbarWorkspaces.fromLegacy(layout, ToolbarLayout.CANVAS_DEFAULT)
        val experimental = ToolbarWorkspaceJson.write(profiles).put("version", 7)
        experimental.getJSONObject("note").getJSONArray("bars").getJSONObject(0)
            .getJSONArray("sections").getJSONObject(0).getJSONArray("entries")
            .put(JSONObject().put("id", "tool_group")
                .put("group", JSONObject().put("id", "group-1")
                    .put("tools", JSONArray().put("pen").put("eraser"))))
        val restored = ToolbarWorkspaceJson.read(experimental)!!
        assertEquals(layout, restored.note.bars.single().layout)
        assertEquals(ToolbarLayout.CANVAS_DEFAULT, restored.canvas.bars.single().layout)
        assertEquals(6, ToolbarWorkspaceJson.write(restored).getInt("version"))
    }

    private fun compactLayout(vararg items: ToolbarItem): ToolbarLayout =
        ToolbarLayout(listOf(ToolbarSection(items.map { ToolbarEntry(it) })))

    @Test fun spacersRoundTripAsIndependentOccurrencesWithSizes() {
        val layout = compactLayout(ToolbarItem.PEN)
            .addItem(0, 0, ToolbarItem.SPACER)
            .addItem(0, 0, ToolbarItem.SPACER)
            .setEntrySize(0, 1, 16)
            .setEntrySize(0, 2, 240)
        val workspaces = ToolbarWorkspaces.fromLegacy(layout, ToolbarLayout.CANVAS_DEFAULT)
        val restored = ToolbarWorkspaceJson.read(ToolbarWorkspaceJson.write(workspaces))!!
        assertEquals(layout, restored.note.bars.single().layout)
        assertEquals(listOf(16, 240), restored.note.bars.single().layout.sections.single().entries
            .filter { it.item == ToolbarItem.SPACER }.map { it.sizeDp })
    }

    @Test fun oldSettingsMigrateWithoutChangingOriginalLayoutOrPlacement() {
        val legacy = Settings(
            toolbarLayout = ToolbarLayout.DEFAULT.moveSection(0, 2),
            canvasToolbarLayout = ToolbarLayout.CANVAS_DEFAULT.moveSection(0, 2),
            prefs = Preferences(toolbarLook = ToolbarLook(
                position = ToolbarPosition.RIGHT,
                size = ToolbarSize.COMPACT,
                floating = true,
            )),
        )
        val restored = Settings.fromJson(legacy.toJson())
        assertNull(restored.toolbarWorkspaces)
        assertEquals(legacy.toolbarLayout, restored.toolbarLayout)
        assertEquals(legacy.canvasToolbarLayout, restored.canvasToolbarLayout)
        val primary = restored.effectiveToolbarWorkspaces().note.bars.single()
        assertEquals(ToolbarAnchor.CENTER_RIGHT, primary.geometry.anchor)
        assertEquals(ToolbarScale.COMPACT, primary.geometry.scale)
        assertTrue(primary.geometry.floating)
        assertEquals(legacy.toolbarLayout, primary.layout)
    }

    @Test fun workspaceSettingsRoundTripKeepsSecondaryBarsAndIndependentProfiles() {
        val legacy = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val second = ToolbarBar(
            id = "colors-1",
            name = "Quick colors",
            layout = compactLayout(ToolbarItem.COLORS, ToolbarItem.PEN).setEntrySize(0, 0, 57),
            geometry = ToolbarGeometry(
                anchor = ToolbarAnchor.BOTTOM_RIGHT,
                floating = false,
                widthDp = 245,
                heightDp = 100,
                paddingDp = 7,
                spacingDp = 5,
                elementSizeDp = 48,
                columns = 3,
                dockEdge = ToolbarDockEdge.RIGHT,
                dockLane = 2,
                dockAlignment = ToolbarDockAlignment.END,
            ),
        )
        val profiles = legacy.copy(note = legacy.note.addBar(second), shared = true)
        val persisted = Settings(toolbarWorkspaces = profiles)
        val restored = Settings.fromJson(JSONObject(persisted.toJson().toString()))
        assertEquals(profiles, restored.toolbarWorkspaces)
        assertEquals(2, restored.effectiveToolbarWorkspaces().note.bars.size)
        assertEquals(2, restored.effectiveToolbarWorkspaces().forContext(ToolbarContext.CANVAS).bars.size)
        assertEquals(245, restored.toolbarWorkspaces!!.note.bars[1].geometry.widthDp)
    }

    @Test fun sectionsRoundTripAndLegacyGridAndV1ArraysMigrate() {
        val group = compactLayout(ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY)
            .setSectionLanes(0, 2).moveItem(0, 2, 0, 3, toLane = 1)
        val profiles = ToolbarWorkspaces.fromLegacy(group, ToolbarLayout.CANVAS_DEFAULT)
        val json = ToolbarWorkspaceJson.write(profiles)
        assertEquals(6, json.getInt("version"))
        assertEquals(group, ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout)

        // Legacy v1 section arrays have no section metadata.
        val oldJson = ToolbarWorkspaceJson.write(profiles).put("version", 1)
        val first = oldJson.getJSONObject("note").getJSONArray("bars").getJSONObject(0)
        first.put("sections", JSONArray().put(JSONArray()
            .put(JSONObject().put("id", "pen"))
            .put(JSONObject().put("id", "dashed"))))
        val oldSection = ToolbarWorkspaceJson.read(oldJson)!!.note.bars.single().layout.sections.single()
        assertEquals(1, oldSection.lanes)
        assertEquals(listOf(ToolbarItem.PEN, ToolbarItem.DASHED), oldSection.entries.map { it.item })

        // v2 grid of 2 columns migrated to two separate rows, preserving original order.
        val oldGrid = ToolbarWorkspaceJson.write(profiles).put("version", 2)
        oldGrid.getJSONObject("note").getJSONArray("bars").getJSONObject(0)
            .put("sections", JSONArray().put(JSONObject().put("grid_columns", 2)
                .put("entries", JSONArray()
                    .put(JSONObject().put("id", "pen"))
                    .put(JSONObject().put("id", "dashed"))
                    .put(JSONObject().put("id", "calligraphy")))))
        val migrated = ToolbarWorkspaceJson.read(oldGrid)!!.note.bars.single().layout.sections.single()
        assertEquals(2, migrated.lanes)
        assertEquals(listOf(0, 0, 1), migrated.entries.map { it.lane })
    }

    @Test fun regionDistributionRoundTripsWithSectionsAndOldSettingsStayContinuous() {
        val layout = compactLayout(ToolbarItem.PEN, ToolbarItem.DASHED)
            .setSectionLanes(0, 3)
            .setSectionRegion(0, ToolbarRegion.END)
            .setDistribution(ToolbarDistribution.REGIONS)
        val json = ToolbarWorkspaceJson.write(ToolbarWorkspaces.fromLegacy(layout, ToolbarLayout.CANVAS_DEFAULT))
        val restored = ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout
        assertEquals(layout, restored)
        val legacy = JSONObject(json.toString()).put("version", 5)
        val oldBar = legacy.getJSONObject("note").getJSONArray("bars").getJSONObject(0)
        oldBar.remove("distribution")
        oldBar.getJSONArray("sections").getJSONObject(0).remove("region")
        val oldLayout = ToolbarWorkspaceJson.read(legacy)!!.note.bars.single().layout
        assertEquals(ToolbarDistribution.CONTINUOUS, oldLayout.distribution)
        assertEquals(ToolbarRegion.START, oldLayout.sections.single().region)
        assertEquals(3, oldLayout.sections.single().lanes)
    }

    @Test fun colorBlocksPersistAndV3AbsoluteRangesMigrateToDynamic() {
        val layout = compactLayout(ToolbarItem.COLORS).setSectionLanes(0, 2).setColorBlocks(2)
        val json = ToolbarWorkspaceJson.write(
            ToolbarWorkspaces.fromLegacy(layout, ToolbarLayout.CANVAS_DEFAULT),
        )
        val restored = ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout
        assertEquals(layout, restored)
        assertEquals((0..4).toList(), restored.sections.single().entries[0].colorIndices(10).toList())
        assertEquals((5..9).toList(), restored.sections.single().entries[1].colorIndices(10).toList())

        // v3 saved absolute ranges, and must be converted to fractions on load.
        val oldV3 = JSONObject(json.toString()).put("version", 3)
        val entries = oldV3.getJSONObject("note").getJSONArray("bars")
            .getJSONObject(0).getJSONArray("sections").getJSONObject(0).getJSONArray("entries")
        entries.getJSONObject(0).put("color_start", 0).put("color_count", 3)
            .remove("color_part")
        entries.getJSONObject(0).remove("color_parts")
        entries.getJSONObject(1).put("color_start", 3).put("color_count", 2)
            .remove("color_part")
        entries.getJSONObject(1).remove("color_parts")
        val migrated = ToolbarWorkspaceJson.read(oldV3)!!.note.bars.single().layout
        assertEquals(layout, migrated)
        assertEquals((0..4).toList(), migrated.sections.single().entries[0].colorIndices(10).toList())
        assertEquals((5..9).toList(), migrated.sections.single().entries[1].colorIndices(10).toList())
    }

    @Test fun unsupportedSchemaFallsBackToLegacySettingsSafely() {
        val json = Settings().toJson()
        json.put("toolbar_workspaces", ToolbarWorkspaceJson.write(
            ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT),
        ).put("version", 99))
        val restored = Settings.fromJson(json)
        assertNull(restored.toolbarWorkspaces)
        assertEquals(ToolbarLayout.DEFAULT, restored.effectiveToolbarWorkspaces().note.bars.single().layout)
    }

    @Test fun malformedAndWrongContextEntriesAreFilteredWithoutAppendingMissingTools() {
        val legacy = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val json = ToolbarWorkspaceJson.write(legacy)
        val noteBars = json.getJSONObject("note").getJSONArray("bars")
        val duplicate = JSONObject(noteBars.getJSONObject(0).toString()).put("name", "collision")
        noteBars.put(duplicate)
        val canvas = json.getJSONObject("canvas").getJSONArray("bars").getJSONObject(0)
        canvas.put("sections", JSONArray().put(JSONArray()
            .put(JSONObject().put("id", "pen").put("visible", true))
            .put(JSONObject().put("id", "page_nav").put("visible", true))
            .put(JSONObject().put("id", "unrecognized").put("visible", true))
            .put(JSONObject().put("id", "pen").put("visible", true))))
        val parsed = ToolbarWorkspaceJson.read(json)!!
        assertEquals(1, parsed.note.bars.size)
        assertEquals(listOf(ToolbarItem.PEN), parsed.canvas.bars.single()
            .layout.sections.single().entries.map { it.item })
        assertFalse(parsed.shared)
    }

    @Test fun invalidDimensionValuesAreClampedDuringLoading() {
        val json = ToolbarWorkspaceJson.write(
            ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT),
        )
        val geometry = json.getJSONObject("note").getJSONArray("bars")
            .getJSONObject(0).getJSONObject("geometry")
        geometry.put("width_dp", -80)
        geometry.put("height_dp", 99999)
        geometry.put("columns", 999)
        val dimensions = ToolbarWorkspaceJson.read(json)!!.note.bars.single().geometry
        assertEquals(48, dimensions.widthDp)
        assertEquals(2048, dimensions.heightDp)
        assertEquals(6, dimensions.columns)
    }
}
