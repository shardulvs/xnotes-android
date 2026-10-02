package com.xnotes.core.history

import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.GeoHandle
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.GeometrySnapshot
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Resizable
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.model.TextStyle

/**
 * A reversible edit (spec 07). A command is pushed onto the history **after**
 * the edit it represents has already been applied, so `redo()` only ever
 * re-applies something previously undone. Guards keep redo/undo idempotent.
 */
interface Command {
    fun redo()
    fun undo()

    /**
     * The items this command's undo/redo can change, each paired with the page it sits on, so the
     * view repairs just those regions instead of re-rasterizing every cached page. The caller reads
     * the items' bounds on **both** sides of the swap, so a moved or resized item reports where it
     * was as well as where it lands; a command only has to name the items it will disturb.
     *
     * Null means "can't say" and the caller repaints everything, which is what a command whose edit
     * isn't item-shaped (a page insert, a text-flow reflow) must return. [locate] finds the page an
     * item currently sits on, for commands that hold items but not pages.
     */
    fun touched(locate: (CanvasItem) -> Page?): List<Pair<Page, CanvasItem>>? = null

    /**
     * The markups this command's undo/redo puts on or takes off, each with its page, so the view
     * repaints the page backgrounds just where they lie. Markups are immutable, so a restyle names
     * the old and the new one.
     */
    fun touchedMarkups(): List<Pair<Page, TextMarkup>> = emptyList()
}

/** Append an item to a page (finishing a stroke, pasting/inserting, new text). */
class AddItem(private val page: Page, private val item: CanvasItem) : Command {
    override fun redo() {
        if (!page.items.containsRef(item)) page.items.add(item)
    }

    override fun undo() {
        page.items.removeRef(item)
    }

    override fun touched(locate: (CanvasItem) -> Page?) = listOf(page to item)
}

/** Append several items to a page as one edit (paste / duplicate). */
class AddItems(private val page: Page, private val items: List<CanvasItem>) : Command {
    override fun redo() {
        for (item in items) if (!page.items.containsRef(item)) page.items.add(item)
    }

    override fun undo() {
        for (item in items) page.items.removeRef(item)
    }

    override fun touched(locate: (CanvasItem) -> Page?) = items.map { page to it }
}

/** Remove a set of (page, item) pairs (object erase, or delete selection). */
class EraseItems(private val removals: List<Pair<Page, CanvasItem>>) : Command {
    override fun redo() {
        for ((page, item) in removals) page.items.removeRef(item)
    }

    override fun undo() {
        for ((page, item) in removals) if (!page.items.containsRef(item)) page.items.add(item)
    }

    override fun touched(locate: (CanvasItem) -> Page?) = removals
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

    /** Only the fragments the split added or removed moved; the untouched majority of the page didn't. */
    override fun touched(locate: (CanvasItem) -> Page?): List<Pair<Page, CanvasItem>> {
        val out = ArrayList<Pair<Page, CanvasItem>>()
        val kept = identitySetOf(after)
        for (item in before) if (item !in kept) out.add(page to item)
        val was = identitySetOf(before)
        for (item in after) if (item !in was) out.add(page to item)
        return out
    }
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

    override fun touched(locate: (CanvasItem) -> Page?) = pagedItems(items, locate)
}

/**
 * Hand items over to another page (a selection dragged across a page boundary). Each item leaves
 * its old page's list, shifts by the delta between the two page spaces, and lands on top of the
 * new page. Paired with the [MoveItems] of the same drag in one composite step.
 */
class TransferItems(private val transfers: List<Transfer>) : Command {
    class Transfer(
        val from: Page,
        val to: Page,
        val item: CanvasItem,
        val dx: Double,
        val dy: Double,
    )

    override fun redo() {
        for (t in transfers) {
            if (!t.from.items.removeRef(t.item)) continue
            t.item.translate(t.dx, t.dy)
            if (!t.to.items.containsRef(t.item)) t.to.items.add(t.item)
        }
    }

    override fun undo() {
        for (t in transfers.asReversed()) {
            if (!t.to.items.removeRef(t.item)) continue
            t.item.translate(-t.dx, -t.dy)
            if (!t.from.items.containsRef(t.item)) t.from.items.add(t.item)
        }
    }

    /** Both pages: the item has to be rubbed out of the one it left and painted onto the one it joins. */
    override fun touched(locate: (CanvasItem) -> Page?) =
        transfers.flatMap { listOf(it.from to it.item, it.to to it.item) }
}

/** Resize an item by swapping its opaque geometry handle. */
class ResizeItem(
    private val item: Resizable,
    private val oldGeom: GeoHandle,
    private val newGeom: GeoHandle,
) : Command {
    override fun redo() = item.setGeometry(newGeom)
    override fun undo() = item.setGeometry(oldGeom)

    override fun touched(locate: (CanvasItem) -> Page?) =
        (item as? CanvasItem)?.let { pagedItems(listOf(it), locate) }
}

/** Replace an image's bitmap + rect (used by Crop), reversibly. */
class ReplaceImage(
    private val item: ImageItem,
    private val oldImage: ImageData,
    private val oldRect: Rect,
    private val newImage: ImageData,
    private val newRect: Rect,
) : Command {
    override fun redo() { item.image = newImage; item.rect = newRect }
    override fun undo() { item.image = oldImage; item.rect = oldRect }

    override fun touched(locate: (CanvasItem) -> Page?) = pagedItems(listOf(item), locate)
}

/**
 * Scale or rotate a selection: each item swaps between its before/after geometry snapshots. Used by
 * the unified resize/rotate handles, which can change a shape's kind (a rotated box shape becomes a
 * polygon), so a plain geometry handle isn't enough — full snapshots restore the exact prior state.
 */
class TransformItems(
    private val items: List<CanvasItem>,
    private val before: List<GeometrySnapshot>,
    private val after: List<GeometrySnapshot>,
) : Command {
    override fun redo() = items.forEachIndexed { i, it -> it.restoreGeometry(after[i]) }
    override fun undo() = items.forEachIndexed { i, it -> it.restoreGeometry(before[i]) }

    override fun touched(locate: (CanvasItem) -> Page?) = pagedItems(items, locate)
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

    override fun touched(locate: (CanvasItem) -> Page?) = pagedItems(listOf(item), locate)
}

/** Restyle a text box (colour, point size, face) — geometry and text are untouched. */
class RestyleText(
    private val item: TextItem,
    private val oldStyle: TextStyle,
    private val newStyle: TextStyle,
) : Command {
    override fun redo() = newStyle.applyTo(item)
    override fun undo() = oldStyle.applyTo(item)

    override fun touched(locate: (CanvasItem) -> Page?) = pagedItems(listOf(item), locate)
}

/**
 * Restyle drawn items (ink colour and stroke width) in place. Each item keeps its own before/after
 * pair, so undoing a mixed selection puts every item back to the style it actually had.
 */
class RestyleItems(private val entries: List<Entry>) : Command {
    class Entry(val item: CanvasItem, val before: DrawStyle, val after: DrawStyle)

    override fun redo() {
        for (e in entries) e.after.applyTo(e.item)
    }

    override fun undo() {
        for (e in entries) e.before.applyTo(e.item)
    }

    override fun touched(locate: (CanvasItem) -> Page?) = pagedItems(entries.map { it.item }, locate)
}

/**
 * Pin items in place, or release them. One command for the whole selection, so a lock and its undo
 * are a single step however much was held.
 */
class LockItems(private val items: List<CanvasItem>, private val locked: Boolean) : Command {
    override fun redo() {
        for (item in items) item.locked = locked
    }

    override fun undo() {
        for (item in items) item.locked = !locked
    }

    override fun touched(locate: (CanvasItem) -> Page?) = pagedItems(items, locate)
}

/** Replace a page's item list (bring-to-front), via full before/after snapshots. */
class ReorderItems(
    private val page: Page,
    private val oldOrder: List<CanvasItem>,
    private val newOrder: List<CanvasItem>,
) : Command {
    override fun redo() = page.items.replaceWith(newOrder)
    override fun undo() = page.items.replaceWith(oldOrder)

    /** Nothing moved, so only where the items that changed depth overlap can look different. */
    override fun touched(locate: (CanvasItem) -> Page?): List<Pair<Page, CanvasItem>> {
        val out = ArrayList<Pair<Page, CanvasItem>>()
        for (i in 0 until maxOf(oldOrder.size, newOrder.size)) {
            val was = oldOrder.getOrNull(i)
            val now = newOrder.getOrNull(i)
            if (was === now) continue
            if (was != null) out.add(page to was)
            if (now != null) out.add(page to now)
        }
        return out
    }
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

/** Swap the note's source PDF (another note's PDF merged in); [onSwap] reloads what draws it. */
class SetPdfFile(
    private val document: Document,
    private val before: java.io.File?,
    private val after: java.io.File?,
    private val onSwap: () -> Unit,
) : Command {
    override fun redo() {
        document.pdfFile = after
        onSwap()
    }

    override fun undo() {
        document.pdfFile = before
        onSwap()
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

    /** One vague step makes the whole composite vague: the caller has to repaint everything. */
    override fun touched(locate: (CanvasItem) -> Page?): List<Pair<Page, CanvasItem>>? {
        val out = ArrayList<Pair<Page, CanvasItem>>()
        for (c in commands) out.addAll(c.touched(locate) ?: return null)
        return out
    }

    override fun touchedMarkups(): List<Pair<Page, TextMarkup>> = commands.flatMap { it.touchedMarkups() }
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

// --- touched-region helpers ---

/**
 * Pair each item with the page it currently sits on. An item on no page (never happens for a live
 * command, but a stale reference would) has no cache to repair, so it drops out rather than forcing
 * the whole command to give up.
 */
private fun pagedItems(
    items: List<CanvasItem>,
    locate: (CanvasItem) -> Page?,
): List<Pair<Page, CanvasItem>> = items.mapNotNull { item -> locate(item)?.let { it to item } }

/** Items never override equals, so a plain hash set already compares by reference. */
private fun identitySetOf(items: List<CanvasItem>): Set<CanvasItem> = HashSet(items)

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
