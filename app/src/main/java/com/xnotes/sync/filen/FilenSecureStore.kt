package com.xnotes.sync.filen

import android.content.Context
import com.xnotes.sync.SecureBlob
import org.json.JSONArray
import org.json.JSONObject

/** Stores the Filen session (apiKey + master keys) encrypted in a [SecureBlob]. */
class FilenSecureStore(context: Context) {
    private val blob = SecureBlob(context, "filen_secure", "n_notes_filen_key")

    fun save(session: FilenSession) {
        val json = JSONObject()
            .put("email", session.email)
            .put("apiKey", session.apiKey)
            .put("masterKeys", JSONArray(session.masterKeys))
            .put("authVersion", session.authVersion)
            .put("baseFolderUuid", session.baseFolderUuid)
            .toString()
        blob.save(json)
    }

    fun load(): FilenSession? {
        val json = try {
            JSONObject(blob.load() ?: return null)
        } catch (e: Exception) {
            return null
        }
        val keysArr = json.optJSONArray("masterKeys") ?: return null
        val keys = (0 until keysArr.length()).map { keysArr.getString(it) }
        if (keys.isEmpty()) return null
        return FilenSession(
            json.optString("email"), json.optString("apiKey"), keys,
            json.optInt("authVersion", 2), json.optString("baseFolderUuid"),
        )
    }

    fun clear() = blob.clear()
}
