package com.xnotes.platform

import android.content.Context
import org.json.JSONObject

/**
 * Where playback stood in each recording, so Play picks up where it left off. Kept on this device
 * beside the view states rather than in the note, so listening never edits a file. Keys name a
 * document and a recording in it; a recording played back to its end is forgotten.
 */
object AudioPositions {

    /** How many recordings are remembered; the oldest go first. */
    private const val LIMIT = 500

    private var store: JsonStore? = null
    private val positions = LinkedHashMap<String, Int>(16, 0.75f, true)

    fun init(context: Context) {
        if (store != null) return
        val s = JsonStore.audioPositions(context.applicationContext)
        store = s
        val o = s.read()
        for (key in o.keys()) positions[key] = o.optInt(key, 0)
    }

    @Synchronized
    fun get(key: String): Int = positions[key] ?: 0

    @Synchronized
    fun put(key: String, ms: Int) {
        if (ms <= 0) positions.remove(key) else positions[key] = ms
        while (positions.size > LIMIT) positions.remove(positions.keys.first())
        store?.write(JSONObject().apply { for ((k, v) in positions) put(k, v) })
    }
}
