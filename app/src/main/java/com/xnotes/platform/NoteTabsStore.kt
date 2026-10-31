package com.xnotes.platform

import android.content.Context
import com.xnotes.core.workspace.NoteTab
import com.xnotes.core.workspace.NoteTabs
import org.json.JSONArray
import org.json.JSONObject

/** Only lightweight tab metadata is stored; note contents remain in their original documents. */
class NoteTabsStore(context: Context) {
    private val prefs = context.getSharedPreferences("note_tabs_workspace", Context.MODE_PRIVATE)

    fun load(): NoteTabs = runCatching {
        val json = JSONObject(prefs.getString("workspace", "{}") ?: "{}")
        val array = json.optJSONArray("tabs") ?: JSONArray()
        val tabs = buildList<NoteTab> {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val uri = entry.optString("uri")
                if (uri.isNotBlank() && none { it.uri == uri }) {
                    add(NoteTab(uri, entry.optString("title", "Note")))
                }
            }
        }
        val primary = json.optString("primary").takeIf { id -> tabs.any { it.uri == id } }
        val secondary = json.optString("secondary").takeIf { id -> id != primary && tabs.any { it.uri == id } }
        NoteTabs(tabs, primary, secondary)
    }.getOrDefault(NoteTabs())

    fun save(workspace: NoteTabs) {
        val json = JSONObject().apply {
            put("tabs", JSONArray().apply {
                workspace.tabs.forEach { tab -> put(JSONObject().put("uri", tab.uri).put("title", tab.title)) }
            })
            put("primary", workspace.primaryUri ?: "")
            put("secondary", workspace.secondaryUri ?: "")
        }
        prefs.edit().putString("workspace", json.toString()).apply()
    }
}
