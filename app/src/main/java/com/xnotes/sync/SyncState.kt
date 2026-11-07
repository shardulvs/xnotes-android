package com.xnotes.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The per-file sync baseline: what we last saw on each side, keyed by the note's path
 * relative to the browse root. Change detection compares each side to its own baseline,
 * so clock skew between device and cloud does not matter.
 */
class SyncState(private val map: MutableMap<String, Entry> = HashMap()) {
    /** [pages] and [doc] are a note's fingerprints at this sync (see XnoteBundle), null for other files and older baselines. */
    data class Entry(
        val remoteUuid: String,
        val remoteLastModified: Long,
        val localModified: Long,
        val size: Long,
        val pages: List<String>? = null,
        val doc: String? = null,
    )

    fun get(path: String): Entry? = map[path]
    fun put(path: String, entry: Entry) { map[path] = entry }
    fun remove(path: String) { map.remove(path) }
    fun paths(): Set<String> = map.keys.toSet()

    /** File baselines; folder baselines are kept under their path plus a trailing slash. */
    fun filePaths(): Set<String> = map.keys.filterTo(HashSet()) { !it.endsWith("/") }
    fun folderPaths(): Set<String> = map.keys.filter { it.endsWith("/") }.mapTo(HashSet()) { it.removeSuffix("/") }
    fun hasFolder(path: String): Boolean = "$path/" in map
    fun putFolder(path: String, uuid: String) { map["$path/"] = Entry(uuid, 0, 0, 0) }
    fun removeFolder(path: String) { map.remove("$path/") }

    fun save(context: Context, serviceId: String) {
        val root = JSONObject()
        for ((path, e) in map) {
            val o = JSONObject()
                .put("remoteUuid", e.remoteUuid).put("remoteLastModified", e.remoteLastModified)
                .put("localModified", e.localModified).put("size", e.size)
            if (e.pages != null && e.doc != null) o.put("pages", JSONArray(e.pages)).put("doc", e.doc)
            root.put(path, o)
        }
        runCatching {
            val target = file(context, serviceId)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(target)
        }
    }

    companion object {
        fun load(context: Context, serviceId: String): SyncState {
            val f = file(context, serviceId)
            if (!f.exists()) return SyncState()
            return runCatching {
                val root = JSONObject(f.readText())
                val map = HashMap<String, Entry>()
                for (key in root.keys()) {
                    val o = root.getJSONObject(key)
                    val pages = o.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    map[key] = Entry(
                        o.getString("remoteUuid"), o.optLong("remoteLastModified"), o.optLong("localModified"), o.optLong("size"),
                        pages, o.optString("doc").takeIf { it.isNotEmpty() },
                    )
                }
                SyncState(map)
            }.getOrDefault(SyncState())
        }

        private fun file(context: Context, serviceId: String): File {
            val dir = File(context.filesDir, "config").apply { mkdirs() }
            return File(dir, "${serviceId}_sync_state.json")
        }
    }
}
