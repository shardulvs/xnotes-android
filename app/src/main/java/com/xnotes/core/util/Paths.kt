package com.xnotes.core.util

/** Minimal, platform-neutral path-name helpers (no java.io dependency). */
object Paths {
    fun baseName(path: String): String {
        val i = path.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (i >= 0) path.substring(i + 1) else path
    }

    /** File base name without its extension. */
    fun stem(path: String): String {
        val base = baseName(path)
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) else base
    }
}
