package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.RefillShapes
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.EraseRule
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import java.util.IdentityHashMap

/**
 * One eraser drag, from pen down to pen up.
 *
 * Both modes reduce to the same shape: an item is swapped for whatever survives it, which is
 * nothing at all in whole-item mode and the surviving fragments in area mode. That gives one undo
 * command for both, and one argument for why it is correct.
 *
 * The work per step is bounded by what the eraser is over, not by the size of the canvas: the
 * spatial index narrows the document to the items under the circle, and the record keeps only the
 * ones actually cut. The paged canvas can afford to snapshot its whole page list per gesture; here
 * the page is the entire document, so it cannot.
 *
 * A fragment can be cut again later in the same drag, so entries are coalesced as it runs: each
 * holds the item as it was at pen down and the fragments left at pen up, never the states between.
 *
 * Pure Kotlin, so the whole of the eraser's behaviour is unit-testable without a canvas.
 */
class EraseSession(private val doc: InfiniteDocument) {

    private class Entry(val at: Int, val original: CanvasItem, val fragments: MutableList<CanvasItem>)

    /** Cut items in the order they were first touched, which is the order undo has to reverse. */
    private val entries = ArrayList<Entry>()
    private val byOriginal = IdentityHashMap<CanvasItem, Entry>()

    /** Which original each live fragment came from, so a re-cut updates the right entry. */
    private val originOf = IdentityHashMap<CanvasItem, CanvasItem>()

    /** Filled shapes whose fill alone this drag took, with the fill each had. */
    private val unfilled = ArrayList<RefillShapes.Entry>()

    /** Whether the drag has taken anything at all. */
    val isEmpty: Boolean get() = entries.isEmpty() && unfilled.isEmpty()

    /**
     * Pass the eraser over ([cx], [cy]) with [radius], in content space. Returns the region that
     * changed, or null when nothing was under it.
     *
     * Images and text are left alone in both modes, matching the paged canvas: they are placed
     * deliberately and are deleted through a selection instead. So is anything locked, which is
     * what a lock is for. A fill it touches at all goes whole, in both modes; a filled shape it
     * touches inside but not on its outline loses just its fill (see [EraseRule]).
     */
    fun erase(cx: Double, cy: Double, radius: Double, area: Boolean): Rect? {
        val box = Rect(cx - radius, cy - radius, radius * 2, radius * 2)
        var dirty: Rect? = null
        for (item in doc.itemsIn(box)) {
            if (!EraseRule.erasable(item)) continue
            if (EraseRule.takesFill(item, cx, cy, radius)) {
                val shape = item as ShapeItem
                unfilled.add(RefillShapes.Entry(shape, shape.fillRgba, null))
                shape.fillRgba = null
                doc.itemsChanged(listOf(shape))
                dirty = dirty?.union(shape.paintBounds()) ?: shape.paintBounds()
                continue
            }
            val whole = EraseRule.whole(item)
            val fragments: List<CanvasItem> = if (area && !whole) {
                when (item) {
                    is Stroke -> item.erasedBy(cx, cy, radius) ?: continue
                    // A cut shape's label stays behind as drawn text.
                    is ShapeItem -> item.erasedBy(cx, cy, radius)?.let { it + listOfNotNull(item.labelAsGlyphs()) } ?: continue
                    is ConnectorItem -> item.erasedBy(cx, cy, radius)?.let { it + listOfNotNull(item.labelAsGlyphs()) } ?: continue
                    else -> continue
                }
            } else {
                if (!EraseRule.takes(item, cx, cy, radius)) continue
                emptyList()
            }
            for (f in fragments) {
                f.sync = item.sync // pieces were written when the whole was
                f.group = item.group
            }
            val touched = item.paintBounds()
            val at = doc.replaceItem(item, fragments)
            if (at < 0) continue
            record(item, at, fragments)
            dirty = dirty?.union(touched) ?: touched
        }
        return dirty
    }

    /**
     * The single undoable edit for the whole drag, or null when it took nothing. A fill comes off
     * before any cut, as it did in the drag, so undo puts it back on a shape that is on the canvas.
     */
    fun buildCommand(): Command? {
        val parts = ArrayList<Command>(2)
        if (unfilled.isNotEmpty()) parts += OnCanvas(doc, RefillShapes(unfilled.toList()), unfilled.map { it.shape })
        if (entries.isNotEmpty()) parts += SplitCanvasItems(doc, entries.map { SplitCanvasItems.Split(it.at, it.original, it.fragments.toList()) })
        return when (parts.size) {
            0 -> null
            1 -> parts[0]
            else -> CompositeCommand(parts)
        }
    }

    /** Fold this cut into the entry for whichever item it ultimately came from. */
    private fun record(item: CanvasItem, at: Int, fragments: List<CanvasItem>) {
        val original = originOf[item]
        val entry = original?.let { byOriginal[it] }
        if (entry == null) {
            // First contact: this item is itself the original, so the slot recorded is its own.
            val fresh = Entry(at, item, fragments.toMutableList())
            entries.add(fresh)
            byOriginal[item] = fresh
            for (fragment in fragments) originOf[fragment] = item
            return
        }
        // A fragment cut again: swap it for its own fragments inside the entry, in place, so the
        // entry always describes the original's net result rather than a chain of intermediates.
        val slot = entry.fragments.indexOfFirst { it === item }
        if (slot >= 0) {
            entry.fragments.removeAt(slot)
            entry.fragments.addAll(slot, fragments)
        }
        originOf.remove(item)
        for (fragment in fragments) originOf[fragment] = entry.original
    }
}
