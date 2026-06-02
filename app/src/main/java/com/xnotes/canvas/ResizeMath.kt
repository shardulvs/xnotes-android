package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.TextItem
import kotlin.math.abs
import kotlin.math.max

/** Which resize handle is being dragged (spec 06 §8). T/B are the top/bottom side mid-handles. */
enum class HandleId { TL, TR, BL, BR, L, R, T, B, START, END }

data class ResizeHandle(val id: HandleId, val content: Pt)

/** Pure resize-handle geometry and per-type resize updates (spec 06 §8). */
object ResizeMath {
    const val MIN_SIZE = 24.0

    /** Handle positions in content space for [item] on a page at [pageTopLeft]. */
    fun handles(item: CanvasItem, pageTopLeft: Pt): List<ResizeHandle> = when (item) {
        is ImageItem -> rectCorners(item.rect, pageTopLeft)
        is TextItem -> {
            val b = item.bounds()
            val l = b.left + pageTopLeft.x
            val r = b.right + pageTopLeft.x
            val t = b.top + pageTopLeft.y
            val bot = b.bottom + pageTopLeft.y
            val cx = b.centerX + pageTopLeft.x
            val cy = b.centerY + pageTopLeft.y
            // Eight handles: four corners + four side midpoints.
            listOf(
                ResizeHandle(HandleId.TL, Pt(l, t)),
                ResizeHandle(HandleId.T, Pt(cx, t)),
                ResizeHandle(HandleId.TR, Pt(r, t)),
                ResizeHandle(HandleId.R, Pt(r, cy)),
                ResizeHandle(HandleId.BR, Pt(r, bot)),
                ResizeHandle(HandleId.B, Pt(cx, bot)),
                ResizeHandle(HandleId.BL, Pt(l, bot)),
                ResizeHandle(HandleId.L, Pt(l, cy)),
            )
        }
        is ShapeItem ->
            if (item.shape.isClosed) {
                rectCorners(item.box, pageTopLeft)
            } else {
                listOf(
                    ResizeHandle(HandleId.START, Pt(item.start.x + pageTopLeft.x, item.start.y + pageTopLeft.y)),
                    ResizeHandle(HandleId.END, Pt(item.end.x + pageTopLeft.x, item.end.y + pageTopLeft.y)),
                )
            }
        else -> emptyList()
    }

    private fun rectCorners(rect: Rect, ptl: Pt) = listOf(
        ResizeHandle(HandleId.TL, Pt(rect.left + ptl.x, rect.top + ptl.y)),
        ResizeHandle(HandleId.TR, Pt(rect.right + ptl.x, rect.top + ptl.y)),
        ResizeHandle(HandleId.BL, Pt(rect.left + ptl.x, rect.bottom + ptl.y)),
        ResizeHandle(HandleId.BR, Pt(rect.right + ptl.x, rect.bottom + ptl.y)),
    )

    fun hitHandle(handles: List<ResizeHandle>, content: Pt, tolerance: Double): HandleId? =
        handles.firstOrNull { it.content.distanceTo(content) <= tolerance }?.id

    private fun cornerAnchor(box: Rect, handle: HandleId): Pt = when (handle) {
        HandleId.TL -> Pt(box.right, box.bottom)
        HandleId.TR -> Pt(box.left, box.bottom)
        HandleId.BL -> Pt(box.right, box.top)
        else -> Pt(box.left, box.top) // BR
    }

    /** Aspect-locked image resize (page-local pointer). */
    fun resizeImage(old: Rect, handle: HandleId, pointer: Pt): Rect {
        val anchor = cornerAnchor(old, handle)
        val rawW = abs(pointer.x - anchor.x)
        val rawH = abs(pointer.y - anchor.y)
        var scale = max(rawW / old.w, rawH / old.h)
        scale = max(scale, max(MIN_SIZE / old.w, MIN_SIZE / old.h))
        val newW = old.w * scale
        val newH = old.h * scale
        val left = if (handle == HandleId.TL || handle == HandleId.BL) anchor.x - newW else anchor.x
        val top = if (handle == HandleId.TL || handle == HandleId.TR) anchor.y - newH else anchor.y
        return Rect(left, top, newW, newH)
    }

    /** Free (per-axis) closed-shape resize; returns new (start, end). */
    fun resizeClosedShape(start: Pt, end: Pt, handle: HandleId, pointer: Pt): Pair<Pt, Pt> {
        val anchor = cornerAnchor(Rect.fromPoints(start, end), handle)
        var px = pointer.x
        var py = pointer.y
        if (abs(px - anchor.x) < MIN_SIZE) px = anchor.x + if (px >= anchor.x) MIN_SIZE else -MIN_SIZE
        if (abs(py - anchor.y) < MIN_SIZE) py = anchor.y + if (py >= anchor.y) MIN_SIZE else -MIN_SIZE
        return anchor to Pt(px, py)
    }

    /** Open-shape resize: drag the grabbed endpoint; the other stays fixed. */
    fun resizeOpenShape(start: Pt, end: Pt, handle: HandleId, pointer: Pt): Pair<Pt, Pt> =
        if (handle == HandleId.START) pointer to end else start to pointer

    /**
     * Text box rect resize via any of the 8 handles (page-local pointer). [width] is
     * the wrap width; [height] is the reserved minimum (the box still grows to fit its
     * text). The dragged edge follows the pointer, the opposite edge stays fixed, and
     * both axes clamp to [MIN_SIZE]. Returns new (pos, width, height).
     */
    fun resizeText(pos: Pt, width: Double, height: Double, handle: HandleId, pointer: Pt): Triple<Pt, Double, Double> {
        var left = pos.x
        var top = pos.y
        var right = pos.x + width
        var bottom = pos.y + height
        val movesLeft = handle == HandleId.TL || handle == HandleId.BL || handle == HandleId.L
        val movesRight = handle == HandleId.TR || handle == HandleId.BR || handle == HandleId.R
        val movesTop = handle == HandleId.TL || handle == HandleId.TR || handle == HandleId.T
        val movesBottom = handle == HandleId.BL || handle == HandleId.BR || handle == HandleId.B
        if (movesLeft) left = pointer.x
        if (movesRight) right = pointer.x
        if (movesTop) top = pointer.y
        if (movesBottom) bottom = pointer.y
        if (right - left < MIN_SIZE) { if (movesLeft) left = right - MIN_SIZE else right = left + MIN_SIZE }
        if (bottom - top < MIN_SIZE) { if (movesTop) top = bottom - MIN_SIZE else bottom = top + MIN_SIZE }
        return Triple(Pt(left, top), right - left, bottom - top)
    }
}
