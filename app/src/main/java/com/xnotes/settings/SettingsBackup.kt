package com.xnotes.settings

import org.json.JSONObject

/**
 * What a settings backup carries from one device to another: the settings as [Settings.toJson]
 * writes them, less what belongs to this device or its folders. An import takes on every key a
 * backup holds and keeps the rest, so an older or newer backup still reads.
 */
object SettingsBackup {

    const val FORMAT = "xnotes-settings"
    const val VERSION = 1

    /** Settings about this device or its folders: the notes folder, pins, recents, per-folder views, window state. */
    private val DEVICE_SETTINGS = setOf(
        "browse_root", "sidebar_visible", "backstage_rail", "pinned_folders", "folder_views", "recent_docs",
        "last_folder", "render_scale", "finger_draw_auto_checked",
    )

    /** The same among the preferences: performance switches, and paths to this device's own files. */
    private val DEVICE_PREFS = setOf(
        "max_cache_resolution", "disable_front_buffering", "start_fullscreen", "code_theme_path", "code_theme_name", "page_template_pdf",
    )

    /** [settings] as [Settings.toJson] wrote them, with what belongs to this device taken out. */
    fun portable(settings: JSONObject): JSONObject {
        val out = JSONObject(settings.toString())
        for (k in DEVICE_SETTINGS) out.remove(k)
        out.optJSONObject("prefs")?.let { p -> for (k in DEVICE_PREFS) p.remove(k) }
        return out
    }

    /** [current] with every key of [incoming] that may travel put in its place, preferences one by one. */
    fun merged(current: JSONObject, incoming: JSONObject): JSONObject {
        val out = JSONObject(current.toString())
        for (k in incoming.keys()) {
            if (k in DEVICE_SETTINGS) continue
            if (k == "prefs") {
                val mine = out.optJSONObject("prefs") ?: JSONObject()
                val theirs = incoming.optJSONObject("prefs") ?: continue
                for (pk in theirs.keys()) if (pk !in DEVICE_PREFS) mine.put(pk, theirs.get(pk))
                out.put("prefs", mine)
            } else {
                out.put(k, incoming.get(k))
            }
        }
        return out
    }
}
