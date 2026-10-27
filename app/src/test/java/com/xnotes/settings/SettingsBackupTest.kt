package com.xnotes.settings

import com.xnotes.core.model.Rgba
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private val mine = Settings(
        browseRoot = "content://tree/mine",
        toolbarColorCount = 3,
        recentDocs = listOf(RecentDoc("content://a", "a", 1L)),
        prefs = Preferences(fingerDraws = true, maxCacheResolution = 4096, codeThemePath = "/data/mine.toml"),
    )

    @Test fun anExportLeavesThisDeviceBehind() {
        val out = SettingsBackup.portable(mine.toJson())
        assertFalse(out.has("browse_root"))
        assertFalse(out.has("recent_docs"))
        assertEquals(3, out.getInt("toolbar_color_count"))
        val prefs = out.getJSONObject("prefs")
        assertTrue(prefs.getBoolean("finger_draws"))
        assertFalse(prefs.has("max_cache_resolution"))
        assertFalse(prefs.has("code_theme_path"))
    }

    @Test fun anImportTakesWhatItCarriesAndKeepsTheRest() {
        val theirs = Settings(
            browseRoot = "content://tree/theirs",
            toolbarColorCount = 9,
            prefs = Preferences(fingerDraws = false, maxCacheResolution = 1024, pageColor = Rgba(1, 2, 3, 255)),
        )
        val incoming = SettingsBackup.portable(theirs.toJson())
        incoming.remove("toolbar_layout")
        incoming.put("from_a_newer_version", true)
        val next = Settings.fromJson(SettingsBackup.merged(mine.toJson(), incoming))
        assertEquals(9, next.toolbarColorCount)
        assertFalse(next.prefs.fingerDraws)
        assertEquals(Rgba(1, 2, 3, 255), next.prefs.pageColor)
        // This device's own folder, recents, cache size and code theme stay.
        assertEquals("content://tree/mine", next.browseRoot)
        assertEquals(1, next.recentDocs.size)
        assertEquals(4096, next.prefs.maxCacheResolution)
        assertEquals("/data/mine.toml", next.prefs.codeThemePath)
        assertEquals(mine.toolbarLayout, next.toolbarLayout)
    }

    @Test fun aBackupWithoutPreferencesKeepsThem() {
        val next = Settings.fromJson(SettingsBackup.merged(mine.toJson(), JSONObject().put("toolbar_color_count", 4)))
        assertEquals(4, next.toolbarColorCount)
        assertTrue(next.prefs.fingerDraws)
        assertNull(next.newNoteFlow)
    }
}
