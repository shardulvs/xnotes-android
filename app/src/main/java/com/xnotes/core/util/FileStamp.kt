package com.xnotes.core.util

import kotlin.math.abs

/** A file's size and last-modified time as its provider reports them, -1 for either one it won't report. */
data class FileStamp(val size: Long, val modified: Long) {

    /** Whether [now] is still this file: the same size, and an mtime less than [MTIME_WINDOW_MS] away. */
    fun matches(now: FileStamp): Boolean = size == now.size && abs(modified - now.modified) < MTIME_WINDOW_MS

    companion object {
        /** FAT and exFAT cards keep sub-second mtimes in memory but whole or even seconds on disk; Syncthing allows 2 s too. */
        const val MTIME_WINDOW_MS = 2_000L
    }
}
