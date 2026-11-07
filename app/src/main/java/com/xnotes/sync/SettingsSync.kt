package com.xnotes.sync

import android.content.Context
import com.xnotes.platform.JsonStore
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Syncs the user's settings (`config/settings.json`) to a hidden `.xnote-config/settings.json` in
 * the cloud sync root, so preferences follow the account across devices. The folder starts with a dot,
 * so the note-sync engine skips it (it ignores dot-folders), and the baseline entry is kept under a
 * dot-prefixed key that [SyncEngine.reconcile] leaves alone.
 *
 * Device-local fields are never overwritten from the cloud: the SAF browse root and the cloud sync
 * config (which service and folder, how often) stay per-device. Conflicts resolve last-writer-wins by
 * the local file's modified time, which is fine for a small opaque settings blob. Recent documents
 * are the exception: they travel as paths under the notes folder and both devices' lists are merged.
 */
object SettingsSync {
    const val REMOTE_DIR = ".xnote-config"
    const val REMOTE_FILE = "settings.json"
    private const val STATE_PATH = "$REMOTE_DIR/$REMOTE_FILE"

    /** Top-level settings keys that stay device-local (never pulled from the cloud). */
    private val LOCAL_TOP_KEYS = listOf("browse_root")

    /** Preference keys that stay device-local: the cloud sync config itself. */
    private val LOCAL_PREF_KEYS = listOf(
        "cloud_service", "cloud_folders",
        "sync_auto", "sync_wifi_only", "sync_interval_minutes", "sync_on_app_open",
    )

    private const val RECENT_DOCS = "recent_docs"
    private const val RECENT_PATHS = "recent_paths"
    /** As many as the explorer's Recent keeps. */
    private const val RECENT_MAX = 40

    /**
     * Reconcile the settings blob. [localUris] gives each note's document uri by its path under the notes
     * folder, so recents can travel between devices. Returns true if local settings were replaced from the cloud.
     */
    fun sync(context: Context, storage: CloudStorage, rootId: String, state: SyncState, localUris: Map<String, String>): Boolean {
        val store = JsonStore.settings(context)
        val localPaths = localUris.entries.associate { (path, uri) -> docId(uri) to path }
        fun portableOf(full: JSONObject) = portableOf(full, localPaths)
        val localFile = File(File(context.filesDir, "config"), "settings.json")
        val localMod = if (localFile.exists()) localFile.lastModified() else 0L

        val configFolder = storage.list(rootId).folders.firstOrNull { it.name == REMOTE_DIR }
        val remote = configFolder?.let { f ->
            storage.list(f.id).files.firstOrNull { it.name == REMOTE_FILE }
        }
        val st = state.get(STATE_PATH)

        if (remote == null) {
            if (localMod == 0L) return false // nothing local to publish yet
            val folderUuid = configFolder?.id ?: storage.ensureFolderPath(rootId, listOf(REMOTE_DIR))
            val bytes = portableOf(store.read()).toString().toByteArray()
            val sent = storage.upload(folderUuid, REMOTE_FILE, bytes, localMod)
            state.put(STATE_PATH, SyncState.Entry(sent.id, sent.lastModified, localMod, bytes.size.toLong()))
            return false
        }

        val file = remote
        val localChanged = st == null || localMod != st.localModified
        val remoteChanged = st == null || file.lastModified != st.remoteLastModified
        return when {
            remoteChanged -> {
                val bytes = storage.download(file)
                val incoming = JSONObject(String(bytes))
                // The live settings, not the file, read right before they are replaced: a change the editor made since must not be lost.
                val local = LiveSettings.get(com.xnotes.settings.SettingsRepository(context)).toJson()
                // A two-sided change keeps this device's settings (sent up below) but still takes in the other's recents.
                val merged = if (localChanged) JSONObject(local.toString()) else merge(local, incoming)
                merged.put(RECENT_DOCS, mergedRecents(localUris, local.optJSONArray(RECENT_DOCS), incoming.optJSONArray(RECENT_PATHS)))
                LiveSettings.set(Settings.fromJson(merged))
                store.write(merged)
                val newMod = if (localFile.exists()) localFile.lastModified() else localMod
                if (localChanged) {
                    val out = portableOf(merged).toString().toByteArray()
                    val sent = storage.upload(configFolder!!.id, REMOTE_FILE, out, newMod)
                    runCatching { storage.trashFile(file.id) }
                    state.put(STATE_PATH, SyncState.Entry(sent.id, sent.lastModified, newMod, out.size.toLong()))
                } else {
                    state.put(STATE_PATH, SyncState.Entry(file.id, file.lastModified, newMod, bytes.size.toLong()))
                }
                true
            }
            localChanged -> {
                // Local newer (or a two-sided change): local wins.
                val folderUuid = configFolder!!.id
                val bytes = portableOf(store.read()).toString().toByteArray()
                val sent = storage.upload(folderUuid, REMOTE_FILE, bytes, localMod)
                runCatching { storage.trashFile(file.id) }
                state.put(STATE_PATH, SyncState.Entry(sent.id, sent.lastModified, localMod, bytes.size.toLong()))
                false
            }
            else -> false
        }
    }

    /** The settings JSON with device-local fields stripped and recents as paths under the notes folder, for upload. */
    private fun portableOf(full: JSONObject, localPaths: Map<String, String>): JSONObject {
        val out = JSONObject(full.toString())
        for (k in LOCAL_TOP_KEYS) out.remove(k)
        out.optJSONObject("prefs")?.let { p -> for (k in LOCAL_PREF_KEYS) p.remove(k) }
        val paths = JSONArray()
        out.optJSONArray(RECENT_DOCS)?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                val path = localPaths[docId(r.optString("uri"))] ?: continue
                paths.put(JSONObject().put("path", path).put("name", r.optString("name")).put("opened", r.optLong("opened")))
            }
        }
        out.remove(RECENT_DOCS)
        out.put(RECENT_PATHS, paths)
        return out
    }

    /** This device's recents with the other device's (paths found here), newest open of each document first. */
    private fun mergedRecents(localUris: Map<String, String>, local: JSONArray?, incoming: JSONArray?): JSONArray {
        val byDoc = LinkedHashMap<String, JSONObject>()
        fun add(r: JSONObject) {
            val key = docId(r.optString("uri"))
            val had = byDoc[key]
            if (had == null || r.optLong("opened") > had.optLong("opened")) byDoc[key] = r
        }
        local?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::add) }
        incoming?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                val uri = localUris[r.optString("path")] ?: continue
                add(JSONObject().put("uri", uri).put("name", r.optString("name")).put("opened", r.optLong("opened")))
            }
        }
        return JSONArray(byDoc.values.sortedByDescending { it.optLong("opened") }.take(RECENT_MAX))
    }

    private fun docId(uri: String): String =
        runCatching { android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(uri)) }.getOrDefault(uri)

    /** The incoming cloud settings, with this device's local-only fields kept as-is. */
    private fun merge(local: JSONObject, incoming: JSONObject): JSONObject {
        val out = JSONObject(incoming.toString())
        out.remove(RECENT_PATHS)
        for (k in LOCAL_TOP_KEYS) {
            if (local.has(k)) out.put(k, local.get(k)) else out.remove(k)
        }
        val localPrefs = local.optJSONObject("prefs")
        if (localPrefs != null) {
            val outPrefs = out.optJSONObject("prefs") ?: JSONObject().also { out.put("prefs", it) }
            for (k in LOCAL_PREF_KEYS) if (localPrefs.has(k)) outPrefs.put(k, localPrefs.get(k))
        }
        return out
    }
}
