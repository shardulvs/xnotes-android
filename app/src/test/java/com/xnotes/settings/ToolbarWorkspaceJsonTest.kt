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

    @Test fun visualOrderRoundTripsWithoutChangingWhichBarIsPrimary() {
        val initial = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val note = initial.note
            .addBar(ToolbarBar("extra-1", "Extra 1", ToolbarLayout.DEFAULT,
                geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP, widthDp = 100)))
            .addBar(ToolbarBar("extra-2", "Extra 2", ToolbarLayout.DEFAULT,
                geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP, widthDp = 100)))
        val reordered = note.reorderVisualBar("extra-2", note.bars.first().id)
        assertEquals(note.bars.first().id, reordered.bars.first().id)
        val saved = ToolbarWorkspaceJson.write(initial.copy(note = reordered))
        val restored = ToolbarWorkspaceJson.read(saved)!!
        assertEquals(reordered, restored.note)
        assertEquals(listOf("extra-2", note.bars.first().id, "extra-1"),
            restored.note.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        val original = ToolbarWorkspaceJson.write(initial)
        assertFalse(original.getJSONObject("note").getJSONArray("bars").getJSONObject(0)
            .getJSONObject("geometry").has("visual_order"))
    }

    @Test fun firstPinnedRegionEditMaterializesWorkspaceAndSurvivesRestart() {
        val initial = Settings()
        assertNull(initial.toolbarWorkspaces)
        val pinned = ToolbarLayout.DEFAULT.copy(
            distribution = ToolbarDistribution.REGIONS, pinStart = true, pinEnd = true)
        val updated = initial.withToolbarLayout(ToolbarContext.NOTE, pinned)
        assertEquals(pinned, updated.toolbarWorkspaces!!.note.bars.first().layout)
        val restored = Settings.fromJson(updated.toJson())
        assertEquals(pinned, restored.effectiveToolbarWorkspaces().note.bars.first().layout)
        assertEquals(ToolbarLayout.CANVAS_DEFAULT,
            restored.effectiveToolbarWorkspaces().canvas.bars.first().layout)
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
            note = ToolbarWorkspace(listOf(ToolbarBar("note-new", "Note", note)) + base.note.bars),
            canvas = ToolbarWorkspace(listOf(ToolbarBar("canvas-new", "Canvas", canvas)) + base.canvas.bars))
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

    @Test fun experimentalVersionsAreRejectedWithoutCrashingSettings() {
        val saved = ToolbarWorkspaceJson.write(
            ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT),
        )
        for (version in listOf(1, 2, 3, 4, 5, 7, 99)) {
            val unsupported = JSONObject(saved.toString()).put("version", version)
            assertNull("Experimental version $version should not be migrated",
                ToolbarWorkspaceJson.read(unsupported))
            val stored = Settings().toJson().put("toolbar_workspaces", unsupported)
            val result = Settings.fromJson(stored)
            assertNull(result.toolbarWorkspaces)
            assertEquals(ToolbarLayout.DEFAULT,
                result.effectiveToolbarWorkspaces().note.bars.single().layout)
            assertEquals(ToolbarLayout.CANVAS_DEFAULT,
                result.effectiveToolbarWorkspaces().canvas.bars.single().layout)
        }
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

    @Test fun sectionsAndLanesRoundTripInCurrentSchema() {
        val group = compactLayout(ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY)
            .setSectionLanes(0, 2).moveItem(0, 2, 0, 3, toLane = 1)
        val profiles = ToolbarWorkspaces.fromLegacy(group, ToolbarLayout.CANVAS_DEFAULT)
        val json = ToolbarWorkspaceJson.write(profiles)
        assertEquals(6, json.getInt("version"))
        assertEquals(group, ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout)
    }

    @Test fun regionDistributionRoundTripsWithValidDefaultsForMissingFields() {
        val layout = compactLayout(ToolbarItem.PEN, ToolbarItem.DASHED)
            .setSectionLanes(0, 3)
            .setSectionRegion(0, ToolbarRegion.END)
            .setDistribution(ToolbarDistribution.REGIONS)
        val json = ToolbarWorkspaceJson.write(ToolbarWorkspaces.fromLegacy(layout, ToolbarLayout.CANVAS_DEFAULT))
        assertEquals(layout, ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout)
        val incomplete = JSONObject(json.toString())
        val bar = incomplete.getJSONObject("note").getJSONArray("bars").getJSONObject(0)
        bar.remove("distribution")
        bar.getJSONArray("sections").getJSONObject(0).remove("region")
        val defaulted = ToolbarWorkspaceJson.read(incomplete)!!.note.bars.single().layout
        assertEquals(ToolbarDistribution.CONTINUOUS, defaulted.distribution)
        assertEquals(ToolbarRegion.START, defaulted.sections.single().region)
        assertEquals(3, defaulted.sections.single().lanes)
    }

    @Test fun pinnedStartAndEndPersistSeparatelyWithoutChangingOldWorkspaces() {
        val layout = ToolbarLayout(listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.HOME)), region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)), region = ToolbarRegion.CENTER),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.ERASER)), region = ToolbarRegion.END),
        ), distribution = ToolbarDistribution.REGIONS, pinStart = true, pinEnd = true)
        val json = ToolbarWorkspaceJson.write(ToolbarWorkspaces.fromLegacy(layout, ToolbarLayout.CANVAS_DEFAULT))
        assertEquals(layout, ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout)
        val rawBar = json.getJSONObject("note").getJSONArray("bars").getJSONObject(0)
        assertTrue(rawBar.getBoolean("pin_start"))
        assertTrue(rawBar.getBoolean("pin_end"))
        rawBar.remove("pin_start")
        rawBar.remove("pin_end")
        val old = ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout
        assertFalse(old.pinStart)
        assertFalse(old.pinEnd)
        assertEquals(layout.sections, old.sections)
    }

    @Test fun colorBlocksPersistAsDynamicFractions() {
        val layout = compactLayout(ToolbarItem.COLORS).setSectionLanes(0, 2).setColorBlocks(2)
        val json = ToolbarWorkspaceJson.write(
            ToolbarWorkspaces.fromLegacy(layout, ToolbarLayout.CANVAS_DEFAULT),
        )
        val restored = ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout
        assertEquals(layout, restored)
        assertEquals((0..4).toList(), restored.sections.single().entries[0].colorIndices(10).toList())
        assertEquals((5..9).toList(), restored.sections.single().entries[1].colorIndices(10).toList())
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
        canvas.put("sections", JSONArray().put(JSONObject().put("lanes", 1)
            .put("entries", JSONArray()
                .put(JSONObject().put("id", "pen").put("visible", true))
                .put(JSONObject().put("id", "page_nav").put("visible", true))
                .put(JSONObject().put("id", "unrecognized").put("visible", true))
                .put(JSONObject().put("id", "pen").put("visible", true)))))
        val parsed = ToolbarWorkspaceJson.read(json)!!
        assertEquals(1, parsed.note.bars.size)
        assertEquals(listOf(ToolbarItem.PEN), parsed.canvas.bars.single()
            .layout.sections.single().entries.map { it.item })
        assertFalse(parsed.shared)
    }

    @Test fun malformedOrEmptyWorkspaceFallsBackToOriginalToolbarsWithoutCrash() {
        val previous = Settings(
            toolbarLayout = ToolbarLayout.DEFAULT.moveSection(0, 2),
            canvasToolbarLayout = ToolbarLayout.CANVAS_DEFAULT.moveSection(0, 2),
        )
        val valid = ToolbarWorkspaceJson.write(ToolbarWorkspaces.fromLegacy(
            ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT,
        ))
        val invalid = listOf(
            JSONObject(),
            JSONObject(valid.toString()).put("note", JSONObject()),
            JSONObject(valid.toString()).put("canvas", JSONObject()),
            JSONObject(valid.toString()).getJSONObject("note")
                .put("bars", JSONArray()).let { JSONObject(valid.toString()).put("note", it) },
            JSONObject(valid.toString()).put("version", "invalid"),
            JSONObject(valid.toString()).put("note", JSONArray()),
        )
        invalid.forEachIndexed { index, malformed ->
            val parsed = Settings.fromJson(previous.toJson().put("toolbar_workspaces", malformed))
            assertNull("Malformed workspace $index should not be used", parsed.toolbarWorkspaces)
            val fallback = parsed.effectiveToolbarWorkspaces()
            assertEquals(previous.toolbarLayout, fallback.note.bars.single().layout)
            assertEquals(previous.canvasToolbarLayout, fallback.canvas.bars.single().layout)
        }
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
