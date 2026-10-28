package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Renderer

/**
 * Anything that lives on a page (spec 02 §5). Strokes, images, text boxes and
 * shapes share this interface so the canvas can render, hit-test, select, move
 * and erase them uniformly.
 *
 * Items are **mutable** and compared by **identity** (never override equals) so
 * undo commands can hold stable references across an undo/redo cycle.
 */
interface CanvasItem {
    /** Short tag used by the file format: "stroke" | "image" | "text" | "shape". */
    val kind: String

    /** Whether the item exposes resize handles (images, text boxes, shapes do). */
    val resizable: Boolean

    /** False for text, which a mirror would turn backwards, so a selection holding any is not mirrored. */
    val mirrorable: Boolean get() = true

    /**
     * Pinned in place: it cannot be selected, moved or erased until it is unlocked. A held finger
     * over a locked item offers to unlock it rather than picking it up, which is the only way back.
     *
     * A property of the item rather than of the selection, because the whole point is to survive
     * the selection being cleared, the document being closed and the file being reopened.
     */
    var locked: Boolean

    /** An optional hyperlink (http/https/mailto); tapping the item with the pan tool opens it. None by default. */
    var link: String?
        get() = null
        set(_) {}

    /** The moment of a recording this item was added at, or null. Kinds that never sync keep the default. */
    var sync: SyncMark?
        get() = null
        set(_) {}

    /** The group it is in, an id shared by its fellow members on its page or canvas, or null for none. */
    var group: Int?
        get() = null
        set(_) {}

    /** Whether exports draw it: PDFs, page images and captures. Recordings and tape never print. */
    val exported: Boolean get() = true

    /** Draw the item; [r] is already translated to the page origin and scaled. */
    fun paint(r: Renderer)

    /** The item's page-local AABB. */
    fun bounds(): Rect

    /** The region this item actually paints into, including soft-effect overflow such as neon glow. Defaults to [bounds]. */
    fun paintBounds(): Rect = bounds()

    /** Shift the item by a page-local delta. */
    fun translate(dx: Double, dy: Double)

    /** Does a page-local point hit the item? (click-select) */
    fun contains(p: Pt): Boolean

    /** A representative page-local point. */
    fun centroid(): Pt

    /** Does the eraser circle (page-local) touch the item? */
    fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean

    /**
     * Capture all geometry the transform tools (resize + rotate) can change — positions, size,
     * line width — so a live drag can restore-then-reapply each frame and undo can revert it.
     */
    fun snapshotGeometry(): GeometrySnapshot

    /** Restore a snapshot taken by [snapshotGeometry]. */
    fun restoreGeometry(snap: GeometrySnapshot)

    /**
     * Bake an affine (a selection scale or rotation, page-local) into the item's geometry.
     * Rotating a box-parametric shape converts it to a polygon/polyline; strokes scale their line
     * width by the transform's linear factor while shapes keep theirs; images and text never rotate.
     */
    fun applyTransform(t: Affine)
}

/** An opaque per-item geometry snapshot exchanged by the transform tools (resize + rotate). */
sealed interface GeometrySnapshot

/** An opaque geometry handle exchanged by resize (spec 02, 07). */
sealed interface GeoHandle

data class RectHandle(val rect: Rect) : GeoHandle
data class TextHandle(val pos: Pt, val width: Double, val height: Double) : GeoHandle
data class ShapeHandle(val start: Pt, val end: Pt) : GeoHandle

/** A resizable item exchanges its geometry through a [GeoHandle]. */
interface Resizable {
    fun geometry(): GeoHandle
    fun setGeometry(handle: GeoHandle)
}

/** [this] as an export draws it: without the items that never print (see [CanvasItem.exported]). */
fun List<CanvasItem>.forExport(): List<CanvasItem> = if (all { it.exported }) this else filter { it.exported }
