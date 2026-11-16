package com.xnotes.core.model

import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.SelectKind

/**
 * How both erasers, on both canvases, treat fills and tape: one the eraser touches at all goes whole,
 * whatever else it touches with it. Lines are ink, shape outlines, connectors and drawn text, and go
 * whole or are cut as the eraser's mode says; a filled shape touched inside but not on its outline
 * loses just its fill. Text boxes, images, tables and audio go whole too. Locked items are never the eraser's.
 */
object EraseRule {

    /** True when an eraser circle at ([cx], [cy]) with radius [r] touches a line of [item]. */
    fun touchesLine(item: CanvasItem, cx: Double, cy: Double, r: Double): Boolean = when (item) {
        is ShapeItem -> when {
            item.isFill -> false
            item.shape == ShapeKind.GLYPHS -> item.intersectsCircle(cx, cy, r)
            else -> item.outlineMeets(cx, cy, r)
        }
        is Stroke, is ConnectorItem -> item.intersectsCircle(cx, cy, r)
        else -> false
    }

    /** True when the circle touches [item]'s fill at all, even at its edge: a dropped colour, or a filled shape's inside. */
    fun touchesFill(item: CanvasItem, cx: Double, cy: Double, r: Double): Boolean =
        item is ShapeItem && item.shape != ShapeKind.GLYPHS && item.fillMeets(cx, cy, r)

    /** True when the eraser takes [item] whole rather than cutting it, in either mode: anything that is not a line. */
    fun whole(item: CanvasItem): Boolean = (item is ShapeItem && item.isFill) ||
        item is TapeItem || item is TextItem || item is ImageItem || item is TableItem || item is AudioItem

    /**
     * True when an eraser whose filter is [kinds] may take [item] at all. Every erase path goes
     * through this, so both canvases answer the same way. A shape's fill follows its shape's kind.
     */
    fun erasable(item: CanvasItem, kinds: Set<SelectKind>): Boolean =
        !item.locked && SelectKind.of(item) in kinds

    /** True when a whole-item eraser takes [item] at the circle: a line it touches, or a whole item it touches anywhere. */
    fun takes(item: CanvasItem, cx: Double, cy: Double, r: Double, kinds: Set<SelectKind>): Boolean =
        erasable(item, kinds) && (touchesLine(item, cx, cy, r) || wholeTouched(item, cx, cy, r))

    private fun wholeTouched(item: CanvasItem, cx: Double, cy: Double, r: Double): Boolean = when (item) {
        is ShapeItem -> item.isFill && touchesFill(item, cx, cy, r)
        else -> whole(item) && item.intersectsCircle(cx, cy, r)
    }

    /** True when the circle takes only [item]'s fill, leaving its outline: a filled shape touched inside but not on its outline. */
    fun takesFill(item: CanvasItem, cx: Double, cy: Double, r: Double, kinds: Set<SelectKind>): Boolean =
        item is ShapeItem && !item.isFill && erasable(item, kinds) && touchesFill(item, cx, cy, r) && !touchesLine(item, cx, cy, r)
}
