package com.xnotes.core.history

import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.SyncMark

/** Rename a recording's pill. */
class RenameAudio(private val item: AudioItem, private val before: String, private val after: String) : Command {
    override fun redo() {
        item.name = after
    }

    override fun undo() {
        item.name = before
    }

    override fun touched(locate: (CanvasItem) -> Page?) = locate(item)?.let { listOf(it to item) } ?: emptyList()
}

/** Untie items from the recording they were added during, so playback no longer fades them or seeks by them. */
class UnlinkSync(private val marks: List<Pair<CanvasItem, SyncMark>>) : Command {
    override fun redo() {
        for ((item, _) in marks) item.sync = null
    }

    override fun undo() {
        for ((item, mark) in marks) item.sync = mark
    }

    override fun touched(locate: (CanvasItem) -> Page?) = marks.mapNotNull { (item, _) -> locate(item)?.let { it to item } }
}
