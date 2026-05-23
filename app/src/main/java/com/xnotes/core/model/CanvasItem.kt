package com.xnotes.core.model

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

    /** Draw the item; [r] is already translated to the page origin and scaled. */
    fun paint(r: Renderer)

    /** The item's page-local AABB. */
    fun bounds(): Rect

    /** Shift the item by a page-local delta. */
    fun translate(dx: Double, dy: Double)

    /** Does a page-local point hit the item? (click-select) */
    fun contains(p: Pt): Boolean

    /** A representative page-local point (lasso membership). */
    fun centroid(): Pt

    /** Does the eraser circle (page-local) touch the item? */
    fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean
}

/** An opaque geometry handle exchanged by resize (spec 02, 07). */
sealed interface GeoHandle

data class RectHandle(val rect: Rect) : GeoHandle
data class TextHandle(val pos: Pt, val width: Double) : GeoHandle
data class ShapeHandle(val start: Pt, val end: Pt) : GeoHandle

/** A resizable item exchanges its geometry through a [GeoHandle]. */
interface Resizable {
    fun geometry(): GeoHandle
    fun setGeometry(handle: GeoHandle)
}
