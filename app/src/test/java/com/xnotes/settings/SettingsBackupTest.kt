package com.xnotes.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsBackupTest {

    private fun tempDir(): File = Files.createTempDirectory("backup").toFile()

    @Test fun roundTripKeepsSettingsAndAssets() {
        val from = tempDir()
        File(from, "fonts").mkdirs()
        File(from, "fonts/Mine.ttf").writeText("font")
        File(from, "theme").mkdirs()
        File(from, "theme/code.toml").writeText("theme")
        val settings = JSONObject()
            .put("browse_root", "content://old")
            .put("toolbar_color_count", 5)
            .put("prefs", JSONObject().put("filen_sync_enabled", true).put("code_theme_path", "/old/theme/code.toml"))
        val zip = ByteArrayOutputStream().also { SettingsBackup.export(settings, from, it) }.toByteArray()

        val to = tempDir()
        val current = JSONObject().put("browse_root", "content://here")
        val adopted = SettingsBackup.import(ByteArrayInputStream(zip), current, to)!!
        assertEquals(5, adopted.getInt("toolbar_color_count"))
        assertEquals("content://here", adopted.getString("browse_root"))
        assertTrue(adopted.getJSONObject("prefs").getBoolean("filen_sync_enabled"))
        assertEquals(File(to, "theme/code.toml").path, adopted.getJSONObject("prefs").getString("code_theme_path"))
        assertEquals("font", File(to, "fonts/Mine.ttf").readText())
    }

    @Test fun rejectsAZipWithoutSettings() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("fonts/a.ttf")); it.write(1); it.closeEntry() }
        }.toByteArray()
        val to = tempDir()
        assertNull(SettingsBackup.import(ByteArrayInputStream(bytes), JSONObject(), to))
        assertFalse(File(to, "fonts/a.ttf").exists())
    }

    @Test fun ignoresPathsThatClimbOut() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("settings.json")); it.write("{}".toByteArray()); it.closeEntry()
                it.putNextEntry(ZipEntry("fonts/../../evil")); it.write(1); it.closeEntry()
            }
        }.toByteArray()
        val to = tempDir()
        SettingsBackup.import(ByteArrayInputStream(bytes), JSONObject(), to)
        assertFalse(File(to.parentFile, "evil").exists())
    }
}
