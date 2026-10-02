package com.xnotes.core.history

import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.TextMarkup

// Markup edits are built before the edit and applied with redo(), then pushed. None touches an item.

/** Put [markups] on top of [page]'s. */
class AddMarkups(private val page: Page, private val markups: List<TextMarkup>) : Command {
    override fun redo() {
        val now = page.markups
        val fresh = markups.filter { m -> now.none { it === m } }
        if (fresh.isNotEmpty()) page.markups = now + fresh
    }

    override fun undo() {
        page.markups = page.markups.filter { m -> markups.none { it === m } }
    }

    override fun touched(locate: (CanvasItem) -> Page?): List<Pair<Page, CanvasItem>> = emptyList()

    override fun touchedMarkups() = markups.map { page to it }
}

/** Take [markup] off [page]; undo puts it back where it was in the stack. */
class RemoveMarkup(private val page: Page, private val markup: TextMarkup) : Command {
    private val index = page.markups.indexOfFirst { it === markup }

    override fun redo() {
        page.markups = page.markups.filter { it !== markup }
    }

    override fun undo() {
        val now = page.markups
        if (now.any { it === markup }) return
        page.markups = now.toMutableList().apply { add(index.coerceIn(0, size), markup) }
    }

    override fun touched(locate: (CanvasItem) -> Page?): List<Pair<Page, CanvasItem>> = emptyList()

    override fun touchedMarkups() = listOf(page to markup)
}

/** Put [after] in [before]'s place on [page]: a restyle or a note. */
class ReplaceMarkup(private val page: Page, private val before: TextMarkup, private val after: TextMarkup) : Command {
    override fun redo() = swap(before, after)

    override fun undo() = swap(after, before)

    private fun swap(from: TextMarkup, to: TextMarkup) {
        val now = page.markups
        val i = now.indexOfFirst { it === from }
        if (i >= 0) page.markups = now.toMutableList().also { it[i] = to }
    }

    override fun touched(locate: (CanvasItem) -> Page?): List<Pair<Page, CanvasItem>> = emptyList()

    override fun touchedMarkups() = listOf(page to before, page to after)
}
