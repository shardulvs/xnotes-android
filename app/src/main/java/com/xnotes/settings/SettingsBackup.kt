package com.xnotes.settings

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Settings as one portable zip: `settings.json` plus the user's imported fonts, templates, stickers
 * and code theme. The Filen login is not in it (it stays in the keystore), nor the math add-on, which
 * downloads again; what only means something on this device (the chosen folder, recent files) is
 * left out on export and kept on import.
 */
object SettingsBackup {

    private const val SETTINGS_ENTRY = "settings.json"

    /** Folders under the app's files dir that travel with the settings. */
    private val ASSET_DIRS = listOf("fonts", "templates", "stamps", "theme")

    /** Top-level settings keys that only make sense on the device they were written on. */
    private val DEVICE_KEYS = listOf("browse_root", "recent_docs")

    private const val CODE_THEME = "theme/code.toml"

    /** Write [settings] and the asset folders under [filesDir] to [out] as a zip. */
    fun export(settings: JSONObject, filesDir: File, out: OutputStream) {
        val portable = JSONObject(settings.toString())
        for (k in DEVICE_KEYS) portable.remove(k)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
            zip.write(portable.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (dir in ASSET_DIRS) {
                val root = File(filesDir, dir)
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("$dir/" + f.relativeTo(root).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Read a zip made by [export], put its asset files under [filesDir] and return the settings to
     * adopt: the imported ones with [current]'s device-only keys kept. Null, with nothing written,
     * when [input] is not such a zip.
     */
    fun import(input: InputStream, current: JSONObject, filesDir: File): JSONObject? {
        val staged = HashMap<String, ByteArray>()
        var settings: JSONObject? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                val bytes = zip.readBytes()
                when {
                    name == SETTINGS_ENTRY -> settings = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    safeAssetPath(name) -> staged[name] = bytes
                }
            }
        }
        val incoming = settings ?: return null
        for ((name, bytes) in staged) {
            val file = File(filesDir, name)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        for (k in DEVICE_KEYS) if (current.has(k)) incoming.put(k, current.get(k)) else incoming.remove(k)
        // The code theme's path names the file on the device it came from; point it at the one just put down.
        incoming.optJSONObject("prefs")?.let { prefs ->
            if (prefs.has("code_theme_path")) {
                if (CODE_THEME in staged) prefs.put("code_theme_path", File(filesDir, CODE_THEME).path)
                else prefs.remove("code_theme_path")
            }
        }
        return incoming
    }

    /** Only files inside the travelling folders, never a path that climbs out of them. */
    private fun safeAssetPath(name: String): Boolean {
        val parts = name.split('/')
        return parts.size >= 2 && parts.first() in ASSET_DIRS && parts.none { it == ".." || it.isEmpty() }
    }
}
