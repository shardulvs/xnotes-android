package com.xnotes.canvas

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.TextItem
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Which resize handle is being dragged (spec 06 §8). T/B are the top/bottom side mid-handles. */
enum class HandleId { TL, TR, BL, BR, L, R, T, B, START, END }

data class ResizeHandle(val id: HandleId, val content: Pt)

/** Pure resize-handle geometry and per-type resize updates (spec 06 §8). */
object ResizeMath {
    const val MIN_SIZE = 24.0

    /** Lowest scale a generic box-handle drag yields, so a selection can't collapse to zero or
     *  flip. A ratio floor (vs the absolute [MIN_SIZE]) keeps thin selections from exploding. */
    const val MIN_SCALE = 0.05

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
            if (item.shape.isEndpointShape) {
                listOf(
                    ResizeHandle(HandleId.START, Pt(item.start.x + pageTopLeft.x, item.start.y + pageTopLeft.y)),
                    ResizeHandle(HandleId.END, Pt(item.end.x + pageTopLeft.x, item.end.y + pageTopLeft.y)),
                )
            } else {
                rectCorners(item.box, pageTopLeft)
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

    /** The eight generic box handles (four corners + four edge midpoints) for [box], in box's
     *  own coordinate space. Used by the unified resize handles for any multi/mixed selection. */
    fun boxHandles(box: Rect): List<ResizeHandle> = listOf(
        ResizeHandle(HandleId.TL, box.topLeft),
        ResizeHandle(HandleId.T, Pt(box.centerX, box.top)),
        ResizeHandle(HandleId.TR, Pt(box.right, box.top)),
        ResizeHandle(HandleId.R, Pt(box.right, box.centerY)),
        ResizeHandle(HandleId.BR, Pt(box.right, box.bottom)),
        ResizeHandle(HandleId.B, Pt(box.centerX, box.bottom)),
        ResizeHandle(HandleId.BL, Pt(box.left, box.bottom)),
        ResizeHandle(HandleId.L, Pt(box.left, box.centerY)),
    )

    /** A scale about [anchor] by ([sx], [sy]) — the transform a box-handle drag maps to. */
    data class ScaleSpec(val anchor: Pt, val sx: Double, val sy: Double)

    /** The eight handles of an oriented box, in world space. */
    fun obbHandles(obb: Obb): List<ResizeHandle> = listOf(
        ResizeHandle(HandleId.TL, obb.localToWorld(Pt(-obb.halfW, -obb.halfH))),
        ResizeHandle(HandleId.T, obb.localToWorld(Pt(0.0, -obb.halfH))),
        ResizeHandle(HandleId.TR, obb.localToWorld(Pt(obb.halfW, -obb.halfH))),
        ResizeHandle(HandleId.R, obb.localToWorld(Pt(obb.halfW, 0.0))),
        ResizeHandle(HandleId.BR, obb.localToWorld(Pt(obb.halfW, obb.halfH))),
        ResizeHandle(HandleId.B, obb.localToWorld(Pt(0.0, obb.halfH))),
        ResizeHandle(HandleId.BL, obb.localToWorld(Pt(-obb.halfW, obb.halfH))),
        ResizeHandle(HandleId.L, obb.localToWorld(Pt(-obb.halfW, 0.0))),
    )

    /** Top-edge midpoint of an oriented box in world space (the rotate stem's base). */
    fun obbTopMid(obb: Obb): Pt = obb.localToWorld(Pt(0.0, -obb.halfH))

    /** The rotate-grip centre, [arm] (content px) out past the box's top edge along its local up. */
    fun obbRotateGrip(obb: Obb, arm: Double): Pt = obb.localToWorld(Pt(0.0, -obb.halfH - arm))

    /** The affine to bake into the items, plus the resulting oriented box. */
    data class ObbResize(val transform: Affine, val obb: Obb)

    /**
     * Drag a box handle of an oriented box [obb] to world [pointer]: scale in the box's own frame
     * (corner uniform, edge single-axis) about the opposite handle, returning the world-space affine
     * to bake into the items and the new oriented box. The two stay consistent — applying the affine
     * to [obb]'s corners reproduces the returned box's corners.
     */
    fun obbResize(obb: Obb, handle: HandleId, pointer: Pt): ObbResize {
        val local = obb.worldToLocal(pointer)
        val localBox = Rect(-obb.halfW, -obb.halfH, obb.halfW * 2.0, obb.halfH * 2.0)
        val sc = scaleForHandle(localBox, handle, local)
        val transform = Affine.scaleAlongAxes(obb.localToWorld(sc.anchor), obb.angle, sc.sx, sc.sy)
        // The local box was centred at the origin; scaling about sc.anchor moves that centre.
        val newCenterLocal = Pt(sc.anchor.x * (1.0 - sc.sx), sc.anchor.y * (1.0 - sc.sy))
        val newObb = Obb(obb.localToWorld(newCenterLocal), obb.halfW * sc.sx, obb.halfH * sc.sy, obb.angle)
        return ObbResize(transform, newObb)
    }

    /**
     * As [obbResize] for a corner, but free: each axis scales on its own about the opposite corner,
     * so a corner sets width and height apart. A rectangle of tape resizes this way.
     */
    fun obbResizeFree(obb: Obb, handle: HandleId, pointer: Pt): ObbResize {
        val local = obb.worldToLocal(pointer)
        val box = Rect(-obb.halfW, -obb.halfH, obb.halfW * 2.0, obb.halfH * 2.0)
        val anchor = cornerAnchor(box, handle)
        val grab = grabbedCorner(box, handle)
        val sx = axisScale(local.x, anchor.x, grab.x)
        val sy = axisScale(local.y, anchor.y, grab.y)
        val transform = Affine.scaleAlongAxes(obb.localToWorld(anchor), obb.angle, sx, sy)
        val newCenterLocal = Pt(anchor.x * (1.0 - sx), anchor.y * (1.0 - sy))
        return ObbResize(transform, Obb(obb.localToWorld(newCenterLocal), obb.halfW * sx, obb.halfH * sy, obb.angle))
    }

    /**
     * As [obbResize], for handles drawn [margin] outside the box that scales: a lone shape's frame,
     * whose chrome sits past its outline. The pointer is read as if pulled back by the margin, so
     * the box edge stays exactly the margin behind the finger rather than scaling the gap.
     */
    fun obbResize(obb: Obb, handle: HandleId, pointer: Pt, margin: Double): ObbResize {
        if (margin <= 0.0) return obbResize(obb, handle, pointer)
        val local = obb.worldToLocal(pointer)
        val dir = handleDirection(handle)
        return obbResize(obb, handle, obb.localToWorld(Pt(local.x - dir.x * margin, local.y - dir.y * margin)))
    }

    /** Which way a handle sits from the box centre, one unit per axis it moves along. */
    fun handleDirection(handle: HandleId): Pt = when (handle) {
        HandleId.TL -> Pt(-1.0, -1.0)
        HandleId.TR -> Pt(1.0, -1.0)
        HandleId.BL -> Pt(-1.0, 1.0)
        HandleId.BR -> Pt(1.0, 1.0)
        HandleId.L -> Pt(-1.0, 0.0)
        HandleId.R -> Pt(1.0, 0.0)
        HandleId.T -> Pt(0.0, -1.0)
        HandleId.B -> Pt(0.0, 1.0)
        else -> Pt.ZERO
    }

    /**
     * The scale a box-handle drag produces: a corner scales both axes by one factor (aspect-locked,
     * anchored at the opposite corner); an edge scales a single axis (anchored at the opposite
     * edge). Each axis is floored positive so the box can't shrink past [MIN_SIZE] or flip.
     */
    fun scaleForHandle(box: Rect, handle: HandleId, pointer: Pt): ScaleSpec = when (handle) {
        HandleId.L, HandleId.R -> {
            val anchorX = if (handle == HandleId.L) box.right else box.left
            val grabX = if (handle == HandleId.L) box.left else box.right
            ScaleSpec(Pt(anchorX, box.top), axisScale(pointer.x, anchorX, grabX), 1.0)
        }
        HandleId.T, HandleId.B -> {
            val anchorY = if (handle == HandleId.T) box.bottom else box.top
            val grabY = if (handle == HandleId.T) box.top else box.bottom
            ScaleSpec(Pt(box.left, anchorY), 1.0, axisScale(pointer.y, anchorY, grabY))
        }
        else -> {
            // A corner is aspect-locked: project the pointer onto the box diagonal from the opposite
            // (anchored) corner, so the one factor follows the drag and shrinks as well as grows.
            val anchor = cornerAnchor(box, handle)
            val s = diagonalScale(anchor, grabbedCorner(box, handle), pointer)
            ScaleSpec(anchor, s, s)
        }
    }

    private fun grabbedCorner(box: Rect, handle: HandleId): Pt = when (handle) {
        HandleId.TL -> box.topLeft
        HandleId.TR -> Pt(box.right, box.top)
        HandleId.BL -> Pt(box.left, box.bottom)
        else -> Pt(box.right, box.bottom) // BR
    }

    /** Single-axis scale from [anchor] toward [grab], floored at [MIN_SCALE] so the box can't
     *  collapse or flip. A ratio floor (not an absolute one) keeps thin selections from exploding. */
    private fun axisScale(pointer: Double, anchor: Double, grab: Double): Double {
        val denom = grab - anchor
        if (abs(denom) < 1e-9) return 1.0
        return max((pointer - anchor) / denom, MIN_SCALE)
    }

    /** Uniform scale from projecting [pointer] onto the [anchor]->[grab] diagonal, floored at
     *  [MIN_SCALE]. */
    private fun diagonalScale(anchor: Pt, grab: Pt, pointer: Pt): Double {
        val dx = grab.x - anchor.x
        val dy = grab.y - anchor.y
        val denom = dx * dx + dy * dy
        if (denom < 1e-9) return 1.0
        return max(((pointer.x - anchor.x) * dx + (pointer.y - anchor.y) * dy) / denom, MIN_SCALE)
    }

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

    /** Square-locked closed-shape resize (the perfect circle): the box stays square, sized by the
     *  larger pointer delta from the anchored corner. Returns new (start, end). */
    fun resizeSquareShape(start: Pt, end: Pt, handle: HandleId, pointer: Pt): Pair<Pt, Pt> {
        val anchor = cornerAnchor(Rect.fromPoints(start, end), handle)
        val side = max(max(abs(pointer.x - anchor.x), abs(pointer.y - anchor.y)), MIN_SIZE)
        val sx = if (pointer.x >= anchor.x) 1.0 else -1.0
        val sy = if (pointer.y >= anchor.y) 1.0 else -1.0
        return anchor to Pt(anchor.x + sx * side, anchor.y + sy * side)
    }

    /** A shape being drawn out puts its far end here for [pen]; [perfect] (the shape tool's Perfect
     *  switch) steps a line or arrow's angle and gives anything else its kind's perfect proportions. */
    fun drawnEnd(shape: ShapeItem, pen: Pt, perfect: Boolean): Pt {
        val kind = shape.shape
        return when {
            kind.isEndpointShape && perfect -> stepAngle(shape.start, pen, InteractionController.SHAPE_PERFECT_STEP_DEG)
            kind.isEndpointShape -> snapAxis(shape.start, pen)
            perfect -> aspectCorner(shape.start, pen, kind.perfectAspect(shape.n))
            kind.keepsAspect -> aspectCorner(shape.start, pen, 1.0)
            else -> pen
        }
    }

    /** [p] pinned to an exact horizontal or vertical run from [anchor] when it lands within
     *  [InteractionController.SHAPE_AXIS_SNAP_DEG] of one (mirrors the recognizer's axis snap). */
    fun snapAxis(anchor: Pt, p: Pt): Pt {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        if (dx == 0.0 && dy == 0.0) return p
        val snap = Math.toRadians(InteractionController.SHAPE_AXIS_SNAP_DEG)
        val fromHoriz = atan2(abs(dy), abs(dx)) // 0 = horizontal, PI/2 = vertical
        return when {
            fromHoriz <= snap -> Pt(p.x, anchor.y)
            fromHoriz >= Math.PI / 2.0 - snap -> Pt(anchor.x, p.y)
            else -> p
        }
    }

    /** [p] turned about [anchor] onto the nearest whole step of [stepDeg], as far along it as [p] reaches. */
    fun stepAngle(anchor: Pt, p: Pt, stepDeg: Double): Pt {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        if (dx == 0.0 && dy == 0.0) return p
        val a = Math.toRadians(Math.round(Math.toDegrees(atan2(dy, dx)) / stepDeg) * stepDeg)
        // Rounded, so a quarter turn comes out exact and a flat line stays exactly flat.
        val ux = cos(a).let { if (abs(it) < 1e-12) 0.0 else it }
        val uy = sin(a).let { if (abs(it) < 1e-12) 0.0 else it }
        val along = dx * ux + dy * uy
        return Pt(anchor.x + ux * along, anchor.y + uy * along)
    }

    /** The far corner from [anchor] toward [p] of the smallest box [aspect] times as wide as tall that reaches [p]. */
    fun aspectCorner(anchor: Pt, p: Pt, aspect: Double): Pt {
        val h = max(abs(p.x - anchor.x) / aspect, abs(p.y - anchor.y))
        val sx = if (p.x >= anchor.x) 1.0 else -1.0
        val sy = if (p.y >= anchor.y) 1.0 else -1.0
        return Pt(anchor.x + sx * h * aspect, anchor.y + sy * h)
    }

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
