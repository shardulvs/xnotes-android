package com.xnotes.core.history

import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.GeoHandle
import com.xnotes.core.model.Page
import com.xnotes.core.model.Resizable
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextStyle

/**
 * A reversible edit (spec 07). A command is pushed onto the history **after**
 * the edit it represents has already been applied, so `redo()` only ever
 * re-applies something previously undone. Guards keep redo/undo idempotent.
 */
interface Command {
    fun redo()
    fun undo()
}

/** Append an item to a page (finishing a stroke, pasting/inserting, new text). */
class AddItem(private val page: Page, private val item: CanvasItem) : Command {
    override fun redo() {
        if (!page.items.containsRef(item)) page.items.add(item)
    }

    override fun undo() {
        page.items.removeRef(item)
    }
}

/** Append several items to a page as one edit (paste / duplicate). */
class AddItems(private val page: Page, private val items: List<CanvasItem>) : Command {
    override fun redo() {
        for (item in items) if (!page.items.containsRef(item)) page.items.add(item)
    }

    override fun undo() {
        for (item in items) page.items.removeRef(item)
    }
}

/** Remove a set of (page, item) pairs (object erase, or delete selection). */
class EraseItems(private val removals: List<Pair<Page, CanvasItem>>) : Command {
    override fun redo() {
        for ((page, item) in removals) page.items.removeRef(item)
    }

    override fun undo() {
        for ((page, item) in removals) if (!page.items.containsRef(item)) page.items.add(item)
    }
}

/**
 * Replace a page's whole item list via before/after snapshots. Used by the area eraser, which
 * splits strokes into fragments in place: the snapshots capture the net result of a drag (robust
 * to a fragment being re-split later in the same gesture) and restore exact z-order on undo/redo.
 */
class ReplacePageItems(
    private val page: Page,
    private val before: List<CanvasItem>,
    private val after: List<CanvasItem>,
) : Command {
    override fun redo() = page.items.replaceWith(after)
    override fun undo() = page.items.replaceWith(before)
}

/** Translate a selection by a fixed delta. */
class MoveItems(
    private val items: List<CanvasItem>,
    private val dx: Double,
    private val dy: Double,
) : Command {
    override fun redo() {
        for (it in items) it.translate(dx, dy)
    }

    override fun undo() {
        for (it in items) it.translate(-dx, -dy)
    }
}

/** Resize an item by swapping its opaque geometry handle. */
class ResizeItem(
    private val item: Resizable,
    private val oldGeom: GeoHandle,
    private val newGeom: GeoHandle,
) : Command {
    override fun redo() = item.setGeometry(newGeom)
    override fun undo() = item.setGeometry(oldGeom)
}

/** Change the text of an existing text box. */
class EditText(
    private val item: TextItem,
    private val oldText: String,
    private val newText: String,
) : Command {
    override fun redo() {
        item.text = newText
    }

    override fun undo() {
        item.text = oldText
    }
}

/** Restyle a text box (colour, point size, face) — geometry and text are untouched. */
class RestyleText(
    private val item: TextItem,
    private val oldStyle: TextStyle,
    private val newStyle: TextStyle,
) : Command {
    override fun redo() = newStyle.applyTo(item)
    override fun undo() = oldStyle.applyTo(item)
}

/** Replace a page's item list (bring-to-front), via full before/after snapshots. */
class ReorderItems(
    private val page: Page,
    private val oldOrder: List<CanvasItem>,
    private val newOrder: List<CanvasItem>,
) : Command {
    override fun redo() = page.items.replaceWith(newOrder)
    override fun undo() = page.items.replaceWith(oldOrder)
}

/** Insert a page at an index. */
class AddPage(
    private val document: Document,
    private val page: Page,
    private val index: Int,
) : Command {
    override fun redo() {
        if (!document.pages.containsRef(page)) {
            document.pages.add(index.coerceIn(0, document.pages.size), page)
        }
    }

    override fun undo() {
        document.pages.removeRef(page)
    }
}

/** Several commands applied as one undoable unit: redo in order, undo in reverse. */
class CompositeCommand(private val commands: List<Command>) : Command {
    override fun redo() {
        for (c in commands) c.redo()
    }

    override fun undo() {
        for (c in commands.asReversed()) c.undo()
    }
}

/** Delete a page (reversible by re-inserting at its original index). */
class DeletePage(
    private val document: Document,
    private val page: Page,
    private val index: Int,
) : Command {
    override fun redo() {
        document.pages.removeRef(page)
    }

    override fun undo() {
        if (!document.pages.containsRef(page)) {
            document.pages.add(index.coerceIn(0, document.pages.size), page)
        }
    }
}

// --- identity-based list helpers (items/pages compare by reference) ---

private fun <T> List<T>.containsRef(target: T): Boolean = any { it === target }

private fun <T> MutableList<T>.removeRef(target: T): Boolean {
    val i = indexOfFirst { it === target }
    if (i < 0) return false
    removeAt(i)
    return true
}

private fun <T> MutableList<T>.replaceWith(items: List<T>) {
    clear()
    addAll(items)
}
