package com.xnotes.core.model

/**
 * When an item was added during a recording: the recording's id ([AudioItem.rid], unique within its
 * document) and how far into it, in milliseconds of recorded time (pauses left out). Playback fades
 * the items of its recording whose [ms] is still ahead, and a tap on one seeks there.
 */
data class SyncMark(val rid: Int, val ms: Int)
