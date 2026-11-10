package com.xnotes.settings

import com.xnotes.core.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolbarLegacyPenAliasTest {
    @Test fun legacyGroupKeepsAVisiblePenWhenTheRegularPenWasHidden() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN, false)))))
        val profiles = ToolbarWorkspaces.fromLegacy(layout, ToolbarLayout.CANVAS_DEFAULT)
        val json = ToolbarWorkspaceJson.write(profiles)
        json.getJSONObject("note").getJSONArray("bars").getJSONObject(0).getJSONArray("sections")
            .getJSONObject(0).getJSONArray("entries").put(JSONObject().put("id", "unified_pen").put("visible", true))
        val entries = ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout.sections.flatMap { it.entries }
        assertEquals(1, entries.count { it.item == ToolbarItem.PEN })
        assertTrue(entries.single { it.item == ToolbarItem.PEN }.visible)
    }
}
