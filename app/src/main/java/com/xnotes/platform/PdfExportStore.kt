package com.xnotes.platform

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The PDFs with work left to do on them, by document key, kept across launches: those whose pages
 * are behind their note and wait for an export, and those a finished file was being copied over
 * when the app last stopped.
 */
class PdfExportStore(private val store: JsonStore) {

    /** A finished PDF waiting in app storage to replace the file, and what that file was before: its length and end hash. */
    class Staged(val file: String, val oldSize: Long, val oldEnd: String)

    /** One PDF: where it is, what it is called, whether its pages [wait] for an export, and a copy [staged] for it. */
    class Entry(val key: String, val uri: String, val name: String, val waits: Boolean, val staged: Staged?)

    private val entries: LinkedHashMap<String, Entry> = load()

    private fun load(): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        val o = store.read()
        for (key in o.keys()) {
            val e = o.optJSONObject(key) ?: continue
            val uri = e.optString("uri").ifEmpty { null } ?: continue
            val staged = e.optString("staged").ifEmpty { null }?.let { Staged(it, e.optLong("oldSize", -1L), e.optString("oldEnd")) }
            out[key] = Entry(key, uri, e.optString("name"), e.optBoolean("waits", false), staged)
        }
        return out
    }

    private fun save() {
        val o = JSONObject()
        for (e in entries.values) {
            val j = JSONObject().put("uri", e.uri).put("name", e.name).put("waits", e.waits)
            e.staged?.let { j.put("staged", it.file).put("oldSize", it.oldSize).put("oldEnd", it.oldEnd) }
            o.put(e.key, j)
        }
        store.write(o)
    }

    @Synchronized fun all(): List<Entry> = entries.values.toList()

    @Synchronized fun get(key: String): Entry? = entries[key]

    /** [key]'s pages are behind its note. */
    @Synchronized fun wait(key: String, uri: String, name: String) {
        val old = entries[key]
        if (old != null && old.waits && old.uri == uri && old.name == name) return
        entries[key] = Entry(key, uri, name, true, old?.staged)
        save()
    }

    /** [key]'s pages are up to date, or it is not a file to export. A copy staged for it stays. */
    @Synchronized fun done(key: String) {
        val old = entries[key] ?: return
        if (!old.waits) return
        if (old.staged == null) entries.remove(key) else entries[key] = Entry(key, old.uri, old.name, false, old.staged)
        save()
    }

    /** [staged] is about to be copied over [key]'s file. */
    @Synchronized fun stage(key: String, uri: String, name: String, staged: Staged) {
        entries[key] = Entry(key, uri, name, entries[key]?.waits ?: false, staged)
        save()
    }

    /** The copy staged for [key] has landed, or is not to be made. */
    @Synchronized fun unstage(key: String) {
        val old = entries[key] ?: return
        if (old.staged == null) return
        if (old.waits) entries[key] = Entry(key, old.uri, old.name, true, null) else entries.remove(key)
        save()
    }

    /** The file is gone. */
    @Synchronized fun remove(key: String) {
        if (entries.remove(key) != null) save()
    }

    /** The file was renamed or moved. */
    @Synchronized fun move(oldKey: String, newKey: String, newUri: String, newName: String) {
        val old = entries.remove(oldKey) ?: return
        entries[newKey] = Entry(newKey, newUri, newName, old.waits, old.staged)
        save()
    }

    companion object {
        fun of(context: Context): PdfExportStore =
            PdfExportStore(JsonStore(File(File(context.filesDir, "config"), "pdf_exports.json")))
    }
}
