package com.xnotes.settings

import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspaces
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolbarWorkspaceExchangeJsonTest {
    @Test fun exportedPackRoundTripsAllSavedWorkspaces() {
        val profiles = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)
        val presets = listOf(
            ToolbarWorkspacePreset("one", "Aula", profiles),
            ToolbarWorkspacePreset("two", "Desenho", profiles.copy(shared = true)),
        )
        assertEquals(presets, ToolbarWorkspaceExchangeJson.import(ToolbarWorkspaceExchangeJson.export(presets)))
    }

    @Test fun incompatibleFilesAreRejectedWithoutImporting() {
        val wrongFormat = JSONObject()
            .put("format", "other-app")
            .put("version", 1)
            .put("presets", org.json.JSONArray())
        val wrongVersion = JSONObject()
            .put("format", "xnotes-toolbar-workspaces")
            .put("version", 99)
        assertThrows(IllegalArgumentException::class.java) {
            ToolbarWorkspaceExchangeJson.import(wrongFormat.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolbarWorkspaceExchangeJson.import(wrongVersion.toString())
        }
    }
}
