package com.xnotes.sync

import android.content.Context
import java.io.File

/** A plain text log of recent sync runs, kept in the app's config folder and shown from the sync settings. */
object SyncLog {
    private const val MAX_LINES = 2000

    private fun file(context: Context) = File(File(context.filesDir, "config").apply { mkdirs() }, "sync_log.txt")

    @Synchronized
    fun append(context: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        runCatching { file(context).appendText(lines.joinToString("\n", postfix = "\n")) }
    }

    /** Drops the oldest lines past the limit; once per run, so appends stay cheap. */
    @Synchronized
    fun trim(context: Context) {
        runCatching {
            val f = file(context)
            if (!f.exists()) return
            val lines = f.readLines()
            if (lines.size > MAX_LINES) f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    fun lastModified(context: Context): Long = file(context).lastModified()

    @Synchronized
    fun read(context: Context): List<String> = runCatching { file(context).readLines() }.getOrDefault(emptyList())

    @Synchronized
    fun clear(context: Context) { file(context).delete() }
}
