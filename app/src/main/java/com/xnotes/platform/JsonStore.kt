package com.xnotes.platform

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Reads and atomically writes a single UTF-8 JSON file (PAL §11). Failure
 * tolerant: a missing/corrupt file yields an empty object; a failed write is
 * swallowed (settings are a convenience and must never crash the app).
 */
class JsonStore(private val file: File) {

    fun read(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    fun write(obj: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // ignore — settings persistence is best-effort
        }
    }

    companion object {
        /** The app's per-user settings file under the private config directory. */
        fun settings(context: Context): JsonStore =
            JsonStore(File(File(context.filesDir, "config"), "settings.json"))
    }
}
