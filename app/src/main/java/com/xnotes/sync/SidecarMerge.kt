package com.xnotes.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Merges two versions of a sidecar file (colours, colour names, pins, a Trash note): maps are joined key by
 * key and lists item by item, this device's value winning where both have one. Nothing is lost, but an
 * entry removed on only one device comes back.
 */
object SidecarMerge {
    fun merge(local: String, remote: String): String {
        val mine = JSONObject(local)
        val theirs = JSONObject(remote)
        val out = JSONObject(theirs.toString())
        for (k in mine.keys()) {
            val a = mine.get(k)
            val b = theirs.opt(k)
            out.put(k, when {
                a is JSONObject && b is JSONObject -> JSONObject(b.toString()).also { o -> for (ik in a.keys()) o.put(ik, a.get(ik)) }
                a is JSONArray && b is JSONArray -> JSONArray().also { arr ->
                    val seen = HashSet<String>()
                    for (x in listOf(a, b)) for (i in 0 until x.length()) if (seen.add(x.get(i).toString())) arr.put(x.get(i))
                }
                else -> a
            })
        }
        return out.toString()
    }
}
