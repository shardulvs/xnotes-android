package com.xnotes.core.infinite

import com.xnotes.canvas.HandleId
import com.xnotes.canvas.ResizeHandle
import com.xnotes.canvas.ResizeMath
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.TransformItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.GeometrySnapshot
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.tools.ShapeKind

/** Half-width of the sticky zone around each 90-degree step when snap-rotation is on. */
private val SNAP_STICK_RAD = Math.toRadians(6.0)

/**
 * What is selected on the canvas, and the arithmetic of moving, scaling and rotating it.
 *
 * A live drag restores each item's gesture-start geometry and re-applies the whole transform every
 * frame, rather than applying an incremental one. That is what keeps a drag from compounding
 * rounding error, and it is what lets a rotation be undone as a single step.
 *
 * The box is oriented rather than axis-aligned, so a selection that has been turned keeps its own
 * frame and its handles scale along its own axes. Both come from [ResizeMath], shared with the
 * paged canvas, so a resize behaves the same on either surface.
 *
 * Pure Kotlin: no view, no renderer, so all of it unit-tests.
 */
class CanvasSelection(private val doc: InfiniteDocument) {

    var items: List<CanvasItem> = emptyList()
        private set

    /** The oriented box around the selection, or null when nothing is selected. */
    var box: Obb? = null
        private set

    val isEmpty: Boolean get() = items.isEmpty()

    /** When set, a rotate drag snaps the box angle to the nearest 90 degrees. */
    var snapRotation90: Boolean = false

    /** Gesture-start geometry, captured by [beginTransform] and restored every frame of a drag. */
    private var startSnapshots: List<GeometrySnapshot> = emptyList()
    private var startBox: Obb? = null

    /** Where the finger came down, as an angle about the box's centre. Null when it was not given. */
    private var startGrabAngle: Double? = null

    fun select(next: List<CanvasItem>) {
        items = next
        box = boundsOf(next)?.let { Obb.fromAabb(it) }
    }

    fun clear() {
        items = emptyList()
        box = null
        startSnapshots = emptyList()
        startBox = null
        startGrabAngle = null
    }

    /**
     * Re-derive the box from the items, after something outside a drag changed them.
     *
     * The box comes back upright, because item bounds are axis aligned and cannot say what angle
     * the content is at. Keeping the old angle and tilting the fresh bounds was worse than useless:
     * every rotation grew the box by its own turn, so a selection ballooned and skewed the moment
     * the finger came off the grip.
     */
    fun refreshBox() {
        box = boundsOf(items)?.let { Obb.fromAabb(it) }
    }

    /** True when [p] is inside the selection, so a press there grabs it rather than starting a band. */
    fun contains(p: Pt): Boolean = box?.contains(p) == true

    /** The eight resize handles, in content space; none for a lone spline, whose points are its handles. */
    fun handles(): List<ResizeHandle> =
        if (spline() != null) emptyList() else box?.let { ResizeMath.obbHandles(it) } ?: emptyList()

    /** The rotate grip's centre, [arm] content pixels past the box's top edge. */
    fun rotateGrip(arm: Double): Pt? = if (spline() != null) null else box?.let { ResizeMath.obbRotateGrip(it, arm) }

    /** The move grip under a selection too small to grab by its body, seen at [zoom], or null. */
    fun moveGrip(zoom: Double, arm: Double): Pt? = box?.let { ResizeMath.obbMoveGrip(it, zoom, arm) }

    /** The lone selected spline, or null. */
    fun spline(): ShapeItem? = (items.singleOrNull() as? ShapeItem)?.takeIf { it.shape == ShapeKind.SPLINE }

    /** Index of the spline control point within [tolerance] of [p], or -1. */
    fun hitSplinePoint(p: Pt, tolerance: Double): Int {
        val pts = spline()?.controlPoints() ?: return -1
        val i = pts.indices.minByOrNull { pts[it].distanceTo(p) } ?: return -1
        return if (pts[i].distanceTo(p) <= tolerance) i else -1
    }

    /** Drag one spline control point; the model moves, so pair with [beginTransform] for the undo. */
    fun moveSplinePointLive(index: Int, to: Pt) {
        spline()?.moveControlPoint(index, to) ?: return
        doc.itemsChanged(items)
        refreshBox()
    }

    /** Change the lone spline's control points with [edit], returning the undo step or null when nothing changed. */
    fun editSpline(edit: (ShapeItem) -> Boolean): Command? {
        val sp = spline() ?: return null
        val before = sp.snapshotGeometry()
        if (!edit(sp)) return null
        doc.itemsChanged(items)
        refreshBox()
        return OnCanvas(doc, TransformItems(items, listOf(before), listOf(sp.snapshotGeometry())), items)
    }

    /** The lone selected function curve, or null. */
    fun function(): ShapeItem? = (items.singleOrNull() as? ShapeItem)?.takeIf { it.shape == ShapeKind.FUNCTION }

    /** Replot the lone function curve, returning the undo step, or null when [spec] cannot be drawn. */
    fun editFunction(spec: FunctionSpec): Command? {
        val fn = function() ?: return null
        val before = fn.snapshotGeometry()
        if (!fn.setFunction(spec)) return null
        doc.itemsChanged(items)
        refreshBox()
        return OnCanvas(doc, TransformItems(items, listOf(before), listOf(fn.snapshotGeometry())), items)
    }

    /** Which handle [p] lands on, within [tolerance] content pixels, or null. */
    fun hitHandle(p: Pt, tolerance: Double): HandleId? =
        ResizeMath.hitHandle(handles(), p, tolerance)

    // --- transforms ---

    /**
     * Capture the state a drag will be measured against. Call once, at pointer down. [grabAt] is
     * where the finger actually landed, which a rotation needs: it turns by the angle swept from
     * there, so a press a few pixels off the grip's centre no longer snaps the selection.
     */
    fun beginTransform(grabAt: Pt? = null) {
        startSnapshots = items.map { it.snapshotGeometry() }
        startBox = box
        val centre = box?.center
        startGrabAngle = if (grabAt == null || centre == null) null
        else kotlin.math.atan2(grabAt.y - centre.y, grabAt.x - centre.x)
    }

    /** Put every item back where the drag began, for a press that turned out to be a tap. */
    fun restoreStart() {
        for (i in items.indices) items[i].restoreGeometry(startSnapshots[i])
        doc.itemsChanged(items)
        refreshBox()
    }

    /**
     * Put every item back where the drag began and bake [transform] over it. Called per frame, so
     * the drag is always one transform of the original rather than a chain of small ones.
     */
    fun applyLive(transform: Affine) {
        for (i in items.indices) items[i].restoreGeometry(startSnapshots[i])
        for (item in items) item.applyTransform(transform)
        doc.itemsChanged(items)
    }

    /**
     * Move the box alone, for a drag the renderer is offsetting rather than the model.
     *
     * A drag used to move the items themselves on every touch sample, which meant re-tessellating
     * and re-uploading every selected item several times a frame. The model now stays put until the
     * finger lifts, and [moveLive] applies the whole move once.
     */
    fun previewMove(dx: Double, dy: Double) {
        startBox?.let { box = it.translate(dx, dy) }
    }

    /** Move by a delta from the gesture start, restoring first so the drag cannot compound. */
    fun moveLive(dx: Double, dy: Double) {
        for (i in items.indices) items[i].restoreGeometry(startSnapshots[i])
        for (item in items) item.translate(dx, dy)
        startBox?.let { box = it.translate(dx, dy) }
        doc.itemsChanged(items)
    }

    /** Scale a handle drag in the box's own frame, anchored at the opposite handle. */
    fun resizeLive(handle: HandleId, pointer: Pt) {
        val from = startBox ?: return
        val result = ResizeMath.obbResize(from, handle, pointer)
        box = result.obb
        applyLive(result.transform)
    }

    /**
     * Scale the box alone and report the map, for a drag the renderer is scaling rather than the
     * model. The counterpart of [previewMove] and [previewRotate].
     */
    fun previewResize(handle: HandleId, pointer: Pt): Affine? {
        val from = startBox ?: return null
        val result = ResizeMath.obbResize(from, handle, pointer)
        box = result.obb
        return result.transform
    }

    /**
     * Turn the selection about its own centre by the angle the pointer has swept since the grab.
     *
     * Swept, not absolute: pointing the box's local up straight at the pointer means a press that
     * lands anywhere but the grip's exact centre jerks the selection round before the finger has
     * moved, and the grip is a 22 device pixel target 34 pixels out from the box.
     */
    fun rotateLive(pointer: Pt) {
        val from = startBox ?: return
        val swept = snapSwept(from, sweptAngle(from, pointer))
        box = from.copy(angle = from.angle + swept)
        applyLive(Affine.rotateAbout(from.center, swept))
    }

    /**
     * Turn the box alone and report the angle, for a drag the renderer is turning rather than the
     * model. The counterpart of [previewMove]: the model stays put until the finger lifts, and
     * [rotateLive] then applies the whole turn once.
     */
    fun previewRotate(pointer: Pt): Double {
        val from = startBox ?: return 0.0
        val swept = snapSwept(from, sweptAngle(from, pointer))
        box = from.copy(angle = from.angle + swept)
        return swept
    }

    /**
     * Give each 90-degree step a small sticky zone, if enabled: the box clings to the nearest
     * quarter turn only while it is within [SNAP_STICK_RAD] of it, and rotates freely otherwise.
     * Not a hard quantiser, so angles between the steps stay reachable.
     */
    private fun snapSwept(from: Obb, swept: Double): Double {
        if (!snapRotation90) return swept
        val quarter = Math.PI / 2.0
        val target = from.angle + swept
        val nearest = Math.round(target / quarter) * quarter
        return if (Math.abs(target - nearest) <= SNAP_STICK_RAD) nearest - from.angle else swept
    }

    /** Put the box back where the drag started, for a gesture that was cancelled rather than ended. */
    fun previewBack() {
        startBox?.let { box = it }
    }

    /** The point a transform turns and scales about: the box as it was when the gesture began. */
    val transformPivot: Pt? get() = startBox?.center

    private fun sweptAngle(from: Obb, pointer: Pt): Double {
        val centre = from.center
        // Without a recorded grab, fall back to the grip's own direction, which is the box's local
        // up: that reduces to pointing up at the pointer.
        val grab = startGrabAngle ?: (from.angle - Math.PI / 2.0)
        return kotlin.math.atan2(pointer.y - centre.y, pointer.x - centre.x) - grab
    }

    /**
     * The undoable edit for the drag just finished, or null when nothing actually moved. A move is
     * recorded as a move so it stays cheap; anything that changed shape is recorded as before and
     * after geometry snapshots, because a rotation can change a shape's very kind.
     */
    fun buildCommand(movedOnly: Boolean, dx: Double = 0.0, dy: Double = 0.0): Command? {
        if (items.isEmpty()) return null
        if (movedOnly) {
            if (dx == 0.0 && dy == 0.0) return null
            return OnCanvas(doc, MoveItems(items, dx, dy), items)
        }
        val after = items.map { it.snapshotGeometry() }
        return OnCanvas(doc, TransformItems(items, startSnapshots, after), items)
    }

    private fun boundsOf(of: List<CanvasItem>): Rect? {
        var acc: Rect? = null
        for (item in of) {
            val b = item.bounds()
            acc = acc?.union(b) ?: b
        }
        return acc
    }
}
