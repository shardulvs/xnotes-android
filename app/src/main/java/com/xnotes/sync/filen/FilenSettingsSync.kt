package com.xnotes.sync.filen

import android.content.Context
import com.xnotes.platform.JsonStore
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Settings
import org.json.JSONObject
import java.io.File

/**
 * Syncs the user's settings (`config/settings.json`) to a hidden `.xnote-config/settings.json` in
 * the Filen root, so preferences follow the account across devices. The folder starts with a dot,
 * so the note-sync engine skips it (it ignores dot-folders), and the baseline entry is kept under a
 * dot-prefixed key that [FilenSyncEngine.reconcile] leaves alone.
 *
 * Device-local fields are never overwritten from the cloud: the SAF browse root and the Filen sync
 * config (which folder, how often, on/off) stay per-device. Conflicts resolve last-writer-wins by
 * the local file's modified time, which is fine for a small opaque settings blob.
 */
object FilenSettingsSync {
    const val REMOTE_DIR = ".xnote-config"
    const val REMOTE_FILE = "settings.json"
    private const val STATE_PATH = "$REMOTE_DIR/$REMOTE_FILE"

    /** Top-level settings keys that stay device-local (never pulled from the cloud). */
    private val LOCAL_TOP_KEYS = listOf("browse_root")

    /** Preference keys that stay device-local: the Filen sync config itself. */
    private val LOCAL_PREF_KEYS = listOf(
        "filen_sync_enabled", "filen_folder_uuid", "filen_folder_name",
        "filen_auto_sync", "filen_wifi_only", "filen_sync_interval_minutes", "filen_sync_on_app_open",
    )

    /** Reconcile the settings blob. Returns true if local settings were replaced from the cloud. */
    fun sync(context: Context, client: FilenClient, rootUuid: String, state: FilenSyncState): Boolean {
        val store = JsonStore.settings(context)
        val localFile = File(File(context.filesDir, "config"), "settings.json")
        val localMod = if (localFile.exists()) localFile.lastModified() else 0L

        val configFolder = client.listDecrypted(rootUuid).folders.firstOrNull { it.first == REMOTE_DIR }?.second
        val remote = configFolder?.let { f ->
            client.listDecrypted(f.uuid).files.firstOrNull { it.first == REMOTE_FILE }
        }
        val st = state.get(STATE_PATH)

        if (remote == null) {
            if (localMod == 0L) return false // nothing local to publish yet
            val folderUuid = configFolder?.uuid ?: client.ensureFolderPath(rootUuid, listOf(REMOTE_DIR))
            val bytes = portableOf(store.read()).toString().toByteArray()
            val uuid = client.uploadFile(folderUuid, REMOTE_FILE, bytes, localMod)
            state.put(STATE_PATH, FilenSyncState.Entry(uuid, localMod, localMod, bytes.size.toLong()))
            return false
        }

        val (_, file, meta) = remote
        val localChanged = st == null || localMod != st.localModified
        val remoteChanged = st == null || meta.lastModified != st.remoteLastModified
        return when {
            remoteChanged && !localChanged -> {
                val bytes = client.downloadFile(file, meta)
                val merged = merge(store.read(), JSONObject(String(bytes)))
                store.write(merged)
                LiveSettings.set(Settings.fromJson(merged))
                val newMod = if (localFile.exists()) localFile.lastModified() else localMod
                state.put(STATE_PATH, FilenSyncState.Entry(file.uuid, meta.lastModified, newMod, bytes.size.toLong()))
                true
            }
            localChanged -> {
                // Local newer (or a two-sided change): local wins.
                val folderUuid = configFolder!!.uuid
                val bytes = portableOf(store.read()).toString().toByteArray()
                val uuid = client.uploadFile(folderUuid, REMOTE_FILE, bytes, localMod)
                runCatching { client.trashFile(file.uuid) }
                state.put(STATE_PATH, FilenSyncState.Entry(uuid, localMod, localMod, bytes.size.toLong()))
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
