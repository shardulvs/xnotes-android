package com.xnotes.sync

import android.content.Context
import com.xnotes.platform.JsonStore
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Settings
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
 * the local file's modified time, which is fine for a small opaque settings blob.
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

    /** Reconcile the settings blob. Returns true if local settings were replaced from the cloud. */
    fun sync(context: Context, storage: CloudStorage, rootId: String, state: SyncState): Boolean {
        val store = JsonStore.settings(context)
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
            remoteChanged && !localChanged -> {
                val bytes = storage.download(file)
                val merged = merge(store.read(), JSONObject(String(bytes)))
                store.write(merged)
                LiveSettings.set(Settings.fromJson(merged))
                val newMod = if (localFile.exists()) localFile.lastModified() else localMod
                state.put(STATE_PATH, SyncState.Entry(file.id, file.lastModified, newMod, bytes.size.toLong()))
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

    /** The settings JSON with device-local fields stripped, for upload. */
    private fun portableOf(full: JSONObject): JSONObject {
        val out = JSONObject(full.toString())
        for (k in LOCAL_TOP_KEYS) out.remove(k)
        out.optJSONObject("prefs")?.let { p -> for (k in LOCAL_PREF_KEYS) p.remove(k) }
        return out
    }

    /** The incoming cloud settings, with this device's local-only fields kept as-is. */
    private fun merge(local: JSONObject, incoming: JSONObject): JSONObject {
        val out = JSONObject(incoming.toString())
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
