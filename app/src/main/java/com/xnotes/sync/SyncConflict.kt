package com.xnotes.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Names of the copies sync keeps when a note changed on two devices. */
object SyncConflict {
    // Also matches the older "(Filen conflict 2026-01-01 1200)" copies.
    private val COPY = Regex("""\((sync|filen) conflict \d{4}-\d{2}-\d{2} \d{4}\)""", RegexOption.IGNORE_CASE)

    /** "notes/a.xnote" -> "notes/a (Sync Conflict 2026-11-02 1430).xnote". */
    fun copyPath(path: String, now: Date = Date()): String {
        val tag = " (Sync Conflict ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(now)})"
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        return if (dot <= slash + 1) path + tag else path.substring(0, dot) + tag + path.substring(dot)
    }

    fun isCopy(name: String): Boolean = COPY.containsMatchIn(name)
}
