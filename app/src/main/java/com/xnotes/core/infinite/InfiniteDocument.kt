package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.Paths
import java.util.IdentityHashMap

/**
 * The infinite canvas background: one procedural ruling drawn straight in a fragment shader, with
 * no page rectangle to be inside of. Unlike [PageStyle] nothing here inherits, because there is no
 * page level below the document, so every field carries a real value.
 */
data class CanvasBackground(
    val pattern: PagePattern = PagePattern.GRID,
    val patternColor: Rgba = DEFAULT_PATTERN_COLOR,
    /** Pattern period in content pixels, at the canvas's base scale. */
    val spacing: Double = PageStyle.DEFAULT_SPACING,
    /** Paper colour, or null to take the theme's. */
    val paperColor: Rgba? = null,
) {
    val clampedSpacing: Double
        get() = spacing.coerceIn(PageStyle.MIN_SPACING, PageStyle.MAX_SPACING)

    companion object {
        /** The canvas grid's own default, grey at about 25%; page templates default to more. */
        val DEFAULT_PATTERN_COLOR = Rgba(150, 150, 150, 64)
    }
}

/**
 * A document with no pages: one flat, z-ordered list of items in content space, plus the canvas
 * metadata the paged model has no place for. Items are the same mutable, identity-compared
 * [CanvasItem]s the paged editor uses, so the stroke engine, history commands and geometry all
 * carry over untouched.
 *
 * Every structural change goes through this class rather than straight onto the list, because two
 * derived structures have to stay in step with it: the [index] that culls a frame down to what is
 * visible, and the renderer's GPU buffers. The [listener] is how the renderer hears about a change
 * it must patch, which is also what makes undo and redo update the screen for free: history
 * commands mutate through these same methods.
 */
class InfiniteDocument(
    var dpi: Int = PageSize.DEFAULT_DPI,
    var path: String? = null,
    /** Real file name from the storage provider, when known (overrides [path]-derived title). */
    var displayName: String? = null,
    var dirty: Boolean = false,
    var background: CanvasBackground = CanvasBackground(),
    val waypoints: MutableList<Waypoint> = mutableListOf(),
    /** The view the canvas was last left at, restored on open. */
    var lastView: Waypoint? = null,
    /** When the canvas was created (epoch ms), or null for files written before this was recorded. */
    var created: Long? = null,
) {

    /** Something that must be patched when the item list changes: the GL geometry store. */
    interface Listener {
        fun onItemAdded(item: CanvasItem) {}
        fun onItemRemoved(item: CanvasItem) {}

        /** [item]'s geometry changed in place (moved, resized, erased into fragments, restyled). */
        fun onItemChanged(item: CanvasItem) {}

        /**
         * The z order changed. Fired once per structural edit, after the per-item callbacks, so a
         * change touching many items publishes one ordering rather than one per item.
         */
        fun onOrderChanged() {}

        /** The whole list was replaced; rebuild everything. */
        fun onReset() {}
    }

    var listener: Listener? = null

    private val backing = ArrayList<CanvasItem>()

    /** Z-ordered items, back to front. Read-only: mutate through this class so the index tracks. */
    val items: List<CanvasItem> get() = backing

    val index = SpatialIndex()

    /** Lazily rebuilt map from item to z position, used to sort a culled query back into order. */
    private var order: IdentityHashMap<CanvasItem, Int>? = null

    private var cachedContentBounds: Rect? = null
    private var contentBoundsValid = false

    val isEmpty: Boolean get() = backing.isEmpty()

    val itemCount: Int get() = backing.size

    /** Derived: the storage display name (or path) base name without extension, or "Untitled". */
    val title: String
        get() = displayName?.let { Paths.stem(it) }
            ?: path?.let { Paths.stem(it) }
            ?: "Untitled"

    /**
     * A pointer snapshot for the writer: this document's metadata, its own copy of the item list and
     * waypoints, and the very same items. The autosave serializes it off the main thread while the
     * pen keeps drawing here, which iterating [items] directly could not survive: a stroke added
     * mid-write threw ConcurrentModificationException, and the writer's runCatching swallowed it, so
     * the save silently did nothing.
     *
     * It carries no spatial index and no listener. It exists to be serialized and dropped, never
     * drawn or edited.
     */
    fun snapshotForWrite(): InfiniteDocument {
        val copy = InfiniteDocument(dpi, path, displayName, dirty, background, waypoints.toMutableList(), lastView, created)
        copy.backing.addAll(backing)
        return copy
    }

    // --- structural mutation ---

    fun add(item: CanvasItem) {
        backing.add(item)
        index.insert(item)
        invalidateDerived()
        listener?.onItemAdded(item)
        listener?.onOrderChanged()
    }

    /** Insert at a z position, clamped into range. Used by undo to restore an exact slot. */
    fun add(at: Int, item: CanvasItem) {
        backing.add(at.coerceIn(0, backing.size), item)
        index.insert(item)
        invalidateDerived()
        listener?.onItemAdded(item)
        listener?.onOrderChanged()
    }

    fun addAll(newItems: List<CanvasItem>) {
        if (newItems.isEmpty()) return
        for (item in newItems) {
            backing.add(item)
            index.insert(item)
            listener?.onItemAdded(item)
        }
        invalidateDerived()
        listener?.onOrderChanged()
    }

    fun remove(item: CanvasItem): Boolean {
        if (!backing.removeRef(item)) return false
        index.remove(item)
        invalidateDerived()
        listener?.onItemRemoved(item)
        listener?.onOrderChanged()
        return true
    }

    fun removeAll(gone: List<CanvasItem>) {
        var any = false
        for (item in gone) {
            if (!backing.removeRef(item)) continue
            index.remove(item)
            listener?.onItemRemoved(item)
            any = true
        }
        if (any) {
            invalidateDerived()
            listener?.onOrderChanged()
        }
    }

    /**
     * Replace the whole list, keeping the index and renderer in step. The area eraser and the
     * z-order commands work this way: they capture before and after snapshots of the list, so
     * undo and redo restore exact ordering however many splits happened in one drag.
     */
    fun replaceAll(newItems: List<CanvasItem>) {
        backing.clear()
        backing.addAll(newItems)
        index.clear()
        for (item in backing) index.insert(item)
        invalidateDerived()
        listener?.onReset()
    }

    fun clear() = replaceAll(emptyList())

    /**
     * Splice [replacements] into [item]'s own z slot, which is what the area eraser needs: a stroke
     * it cuts becomes the fragments that survived, and they must sit exactly where the original was
     * rather than on top of everything. Returns the slot they landed in, or -1 when [item] is
     * absent. An empty [replacements] simply removes the item.
     */
    fun replaceItem(item: CanvasItem, replacements: List<CanvasItem>): Int {
        val at = indexOfRef(item)
        if (at < 0) return -1
        backing.removeAt(at)
        index.remove(item)
        listener?.onItemRemoved(item)
        for (i in replacements.indices) {
            val fragment = replacements[i]
            backing.add(at + i, fragment)
            index.insert(fragment)
            listener?.onItemAdded(fragment)
        }
        invalidateDerived()
        listener?.onOrderChanged()
        return at
    }

    /**
     * The inverse of [replaceItem]: drop [replacements] and put [item] back at [at]. Used by undo,
     * which needs the recorded slot because a fully erased item leaves no fragment to find it by.
     */
    fun restoreItem(item: CanvasItem, replacements: List<CanvasItem>, at: Int) {
        for (fragment in replacements) {
            if (!backing.removeRef(fragment)) continue
            index.remove(fragment)
            listener?.onItemRemoved(fragment)
        }
        if (!containsRef(item)) {
            val slot = at.coerceIn(0, backing.size)
            backing.add(slot, item)
            index.insert(item)
            listener?.onItemAdded(item)
        }
        invalidateDerived()
        listener?.onOrderChanged()
    }

    fun containsRef(item: CanvasItem): Boolean = backing.any { it === item }

    /** [containsRef] in constant time, off the order map the culling already keeps. */
    fun holds(item: CanvasItem): Boolean = orderMap().containsKey(item)

    fun indexOfRef(item: CanvasItem): Int = backing.indexOfFirst { it === item }

    // --- in-place mutation ---

    /** Announce that [item]'s geometry changed in place, so the index re-files it. */
    fun itemChanged(item: CanvasItem) {
        index.update(item)
        contentBoundsValid = false
        listener?.onItemChanged(item)
    }

    fun itemsChanged(changed: List<CanvasItem>) {
        for (item in changed) {
            index.update(item)
            listener?.onItemChanged(item)
        }
        if (changed.isNotEmpty()) contentBoundsValid = false
    }

    // --- queries ---

    /** Items whose paint bounds meet the viewport, back to front. See [itemsIn]. */
    fun visibleItems(rect: Rect): List<CanvasItem> = itemsIn(rect)

    /**
     * Items whose paint bounds meet [rect], back to front. This is the per-frame cull and the
     * eraser's candidate query both: the index narrows the document to a candidate set, and the z
     * sort puts them back in painter order, dropping the duplicates a multi-cell item produced
     * along the way.
     */
    fun itemsIn(rect: Rect): List<CanvasItem> {
        val raw = ArrayList<CanvasItem>()
        index.queryRaw(rect, raw)
        if (raw.isEmpty()) return raw
        val pos = orderMap()
        raw.sortBy { pos[it] ?: 0 }
        val out = ArrayList<CanvasItem>(raw.size)
        var last: CanvasItem? = null
        for (item in raw) {
            if (item !== last) out.add(item)
            last = item
        }
        return out
    }

    /** Union of every item's paint bounds, or null on an empty canvas. Cached between edits. */
    fun contentBounds(): Rect? {
        if (contentBoundsValid) return cachedContentBounds
        var acc: Rect? = null
        for (item in backing) {
            val b = item.paintBounds()
            if (!b.isFinite()) continue
            acc = acc?.union(b) ?: b
        }
        cachedContentBounds = acc
        contentBoundsValid = true
        return acc
    }

    private fun orderMap(): IdentityHashMap<CanvasItem, Int> {
        order?.let { return it }
        val map = IdentityHashMap<CanvasItem, Int>(backing.size * 2 + 1)
        for (i in backing.indices) map[backing[i]] = i
        order = map
        return map
    }

    private fun invalidateDerived() {
        order = null
        contentBoundsValid = false
    }

    companion object {
        /** Extension of the on-disk bundle. */
        const val EXTENSION = "xcanvas"
    }
}
