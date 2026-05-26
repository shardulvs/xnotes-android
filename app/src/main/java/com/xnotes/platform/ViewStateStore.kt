package com.xnotes.platform

import org.json.JSONObject

/**
 * Remembers each note's last view — zoom and scroll — keyed by document identity, so a
 * note in the granted folder reopens exactly where the user left it. Held in memory and
 * mirrored to a small JSON file ([JsonStore.viewStates]); [clear]ed when the user forgets
 * the folder, since the keys are only meaningful for that folder's documents.
 */
class ViewStateStore(private val store: JsonStore) {

    class View(val zoom: Double, val scrollX: Double, val scrollY: Double)

    private val views: MutableMap<String, View> = load()

    private fun load(): MutableMap<String, View> {
        val out = HashMap<String, View>()
        val o = store.read()
        for (key in o.keys()) {
            val e = o.optJSONObject(key) ?: continue
            out[key] = View(e.optDouble("zoom", 0.0), e.optDouble("scrollX", 0.0), e.optDouble("scrollY", 0.0))
        }
        return out
    }

    fun get(key: String): View? = views[key]

    fun put(key: String, zoom: Double, scrollX: Double, scrollY: Double) {
        views[key] = View(zoom, scrollX, scrollY)
        store.write(toJson())
    }

    /** Forget every remembered view (e.g. when the granted folder is released). */
    fun clear() {
        views.clear()
        store.write(JSONObject())
    }

    private fun toJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in views) {
            o.put(k, JSONObject().put("zoom", v.zoom).put("scrollX", v.scrollX).put("scrollY", v.scrollY))
        }
        return o
    }
}
