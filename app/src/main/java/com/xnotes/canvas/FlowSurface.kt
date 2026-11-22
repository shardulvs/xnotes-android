package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.BoxEdit
import com.xnotes.core.history.Command
import com.xnotes.core.model.TextItem
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.TextFlow

/** Where the text being typed lives: flowing across the note's pages, or inside one text box. */
interface FlowSurface {
    val flow: TextFlow
    val frame: FlowFrame?

    /** A content point as a page of the frame and a point on it, or null off every page. */
    fun localAt(content: Pt): Pair<Int, Pt>?

    /** A rect on frame page [page] in content space, or null when that page is not laid out. */
    fun toContent(page: Int, rect: Rect): Rect?

    /** A session opened or closed: take the text out of the caches, or put it back. */
    fun lift(on: Boolean) {}

    /** An edit landed and the text is to be set afresh; [live] while a session is open. */
    fun changed(live: Boolean)

    /** Whatever the edit just made needed to hold it, already applied, as undo steps. */
    fun grow(): List<Command> = emptyList()

    /** [cmd] as an edit of this surface is recorded. */
    fun record(cmd: Command): Command = cmd

    /** The height of one empty line, when a tap below the text fills down to it with them; else 0. */
    fun fillHeight(): Double = 0.0
}

/**
 * One text box as a surface: its flow, laid out as a single page whose origin is the box's text.
 * [onChanged] follows every edit, once the box has set its text again.
 */
class BoxSurface(
    private val state: CanvasState,
    val item: TextItem,
    val pageIndex: Int,
    private val onChanged: () -> Unit,
) : FlowSurface {
    /** Every edit recorded through here, so the session that made them can be told from the rest. */
    val recorded: MutableSet<Command> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    override val flow: TextFlow get() = item.flow
    override val frame: FlowFrame get() = item.content.frame

    override fun localAt(content: Pt): Pair<Int, Pt>? {
        if (state.pageRects.getOrNull(pageIndex) == null) return null
        val local = state.toPageSpace(pageIndex, content)
        return 0 to Pt(local.x - item.pos.x, local.y - item.pos.y)
    }

    override fun toContent(page: Int, rect: Rect): Rect? {
        if (state.pageRects.getOrNull(pageIndex) == null) return null
        return state.fromPageSpaceRect(pageIndex, Rect(rect.left + item.pos.x, rect.top + item.pos.y, rect.w, rect.h))
    }

    override fun changed(live: Boolean) {
        item.retype()
        onChanged()
    }

    override fun record(cmd: Command): Command = BoxEdit(item, cmd).also { recorded += it }
}
