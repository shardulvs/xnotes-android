package com.xnotes.core.infinite

import com.xnotes.canvas.HandleId
import com.xnotes.canvas.ResizeHandle
import com.xnotes.canvas.ResizeMath
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.TransformItems
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.GeometrySnapshot
import com.xnotes.core.model.GlueFollow
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TapeMode
import com.xnotes.core.tape.TapeGeometry

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

    /** The connectors the drag under way keeps joined to what it moves. */
    var follow: GlueFollow = GlueFollow.NONE
        private set

    /** The lone selected connector, whose chrome is its own handles rather than a box. */
    val loneConnector: ConnectorItem? get() = items.singleOrNull() as? ConnectorItem

    /** Select [next], and with it every unlocked fellow member of the groups it touches. */
    fun select(next: List<CanvasItem>) {
        items = com.xnotes.core.model.Groups.withMembers(next, doc.items) { it }
        box = boxOf(items)
    }

    fun clear() {
        items = emptyList()
        box = null
        follow = GlueFollow.NONE
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
     * the finger came off the grip. A lone shape is the exception: it knows its own frame.
     */
    fun refreshBox() {
        box = boxOf(items)
    }

    /** True when the selection is one shape boxed by its own frame, which a drag keeps turned. */
    val isFramedShape: Boolean get() = (items.singleOrNull() as? ShapeItem)?.frameObb() != null || loneTapeBox != null

    /** The lone selected rectangle of tape, boxed by its own turned frame, whose corners move freely. */
    private val loneTapeBox: TapeItem? get() = (items.singleOrNull() as? TapeItem)?.takeIf { it.mode == TapeMode.RECTANGLE }

    /** The lone selected strip of tape, which its two ends drag. */
    private val loneTapeStrip: TapeItem? get() = (items.singleOrNull() as? TapeItem)?.takeIf { it.mode == TapeMode.STRAIGHT }

    private val CORNERS = setOf(HandleId.TL, HandleId.TR, HandleId.BL, HandleId.BR)

    private fun boxOf(of: List<CanvasItem>): Obb? =
        (of.singleOrNull() as? ShapeItem)?.frameObb()
            ?: (of.singleOrNull() as? TapeItem)?.takeIf { it.mode == TapeMode.RECTANGLE }?.shape?.frame()
            ?: boundsOf(of)?.let { Obb.fromAabb(it) }

    /**
     * How far the chrome sits outside [box]: nothing for a group, whose box already wraps the ink,
     * but a lone shape's frame gets its outline's reach plus [screenMargin], so the box corners
     * clear the shape's own corners and their vertex dots.
     */
    fun chromeMargin(screenMargin: Double): Double =
        (items.singleOrNull() as? ShapeItem)?.takeIf { it.frameObb() != null }?.let { it.framePad + screenMargin } ?: 0.0

    /** The box the chrome draws and grabs by: [box] pushed out by [chromeMargin]; none round a lone connector. */
    fun chromeBox(screenMargin: Double = 0.0): Obb? = if (loneConnector != null) null else box?.grow(chromeMargin(screenMargin))

    /** True when [p] is inside the selection, so a press there grabs it rather than starting a band. */
    fun contains(p: Pt, screenMargin: Double = 0.0): Boolean = chromeBox(screenMargin)?.contains(p) == true

    /** The resize handles, in content space; a shape that keeps its aspect gets corners only, a strip of tape its two ends. */
    fun handles(screenMargin: Double = 0.0): List<ResizeHandle> {
        loneTapeStrip?.let { t -> return listOf(ResizeHandle(HandleId.START, t.shape.points[0]), ResizeHandle(HandleId.END, t.shape.points[1])) }
        val all = chromeBox(screenMargin)?.let { ResizeMath.obbHandles(it) } ?: return emptyList()
        // A pill scales by its corners and lengthens by its sides; nothing stretches it upright.
        if (items.all { it is AudioItem }) return all.filter { it.id != HandleId.T && it.id != HandleId.B }
        val square = (items.singleOrNull() as? ShapeItem)?.shape?.keepsAspect == true
        return if (square || loneTapeBox != null) all.filter { it.id in CORNERS } else all
    }

    /** The rotate grip's centre, [arm] content pixels past the box's top edge. */
    fun rotateGrip(arm: Double, screenMargin: Double = 0.0): Pt? =
        if (items.any { it is AudioItem } || loneTapeStrip != null) null else chromeBox(screenMargin)?.let { ResizeMath.obbRotateGrip(it, arm) }

    /** The move grip under a selection too small to grab by its body, seen at [zoom], or null. */
    fun moveGrip(zoom: Double, arm: Double, screenMargin: Double = 0.0): Pt? =
        chromeBox(screenMargin)?.let { ResizeMath.obbMoveGrip(it, zoom, arm) }

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
    fun hitHandle(p: Pt, tolerance: Double, screenMargin: Double = 0.0): HandleId? =
        ResizeMath.hitHandle(handles(screenMargin), p, tolerance)

    // --- transforms ---

    /**
     * Capture the state a drag will be measured against. Call once, at pointer down. [grabAt] is
     * where the finger actually landed, which a rotation needs: it turns by the angle swept from
     * there, so a press a few pixels off the grip's centre no longer snaps the selection.
     */
    fun beginTransform(grabAt: Pt? = null, rigid: Boolean = false, pin: Boolean = true) {
        startSnapshots = items.map { it.snapshotGeometry() }
        // A move, turn or resize moves everything by one map; a point edit reshapes what it edits.
        follow = followOf(items, rigid = rigid, pin = pin)
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
        follow.follow(transform)
        doc.itemsChanged(items)
        doc.itemsChanged(follow.items)
    }

    /** [applyLive] for [only] some of the items, the ones a renderer-driven drag cannot carry. */
    fun applyLiveTo(only: Set<CanvasItem>, transform: Affine) {
        val changed = ArrayList<CanvasItem>(only.size)
        for (i in items.indices) {
            val item = items[i]
            if (item !in only) continue
            item.restoreGeometry(startSnapshots[i])
            item.applyTransform(transform)
            changed.add(item)
        }
        // The rest the renderer is scaling, so connectors glued to them follow where they are drawn.
        follow.follow(transform) { if (it in only) null else transform }
        doc.itemsChanged(changed)
        doc.itemsChanged(follow.items)
    }

    /**
     * Keep connectors joined through a frame the renderer is drawing displaced by [shown] past the
     * model, which the model has not had applied yet ([moved] is what it will be).
     */
    fun followShown(moved: Affine, shown: Affine) {
        if (follow.isEmpty) return
        follow.follow(moved) { shown }
        doc.itemsChanged(follow.items)
    }

    /** Keep connectors joined to items a point edit has just reshaped in place. */
    fun followEdit() {
        if (follow.isEmpty) return
        follow.follow(Affine.IDENTITY)
        doc.itemsChanged(follow.items)
    }

    /** Put the connectors a cancelled drag moved back where it found them. */
    fun unfollow() {
        if (follow.isEmpty) return
        follow.restore()
        doc.itemsChanged(follow.items)
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
        follow.follow(Affine.translateBy(dx, dy))
        startBox?.let { box = it.translate(dx, dy) }
        doc.itemsChanged(items)
        doc.itemsChanged(follow.items)
    }

    /**
     * Scale a handle drag in the box's own frame, anchored at the opposite handle. [margin] is how
     * far outside the box the handle sits ([chromeMargin]), which the box edge keeps behind it.
     */
    fun resizeLive(handle: HandleId, pointer: Pt, margin: Double = 0.0) {
        if (handle == HandleId.START || handle == HandleId.END) return dragTapeEnd(handle, pointer)
        val from = startBox ?: return
        val result = boxResize(from, handle, pointer, margin)
        box = result.obb
        applyLive(result.transform)
        // A pill stops at its smallest by itself, so its box is read back from it.
        if (items.all { it is AudioItem }) box = boxOf(items)
    }

    /**
     * Scale the box alone and report the map, for a drag the renderer is scaling rather than the
     * model. The counterpart of [previewMove] and [previewRotate].
     */
    fun previewResize(handle: HandleId, pointer: Pt, margin: Double = 0.0): Affine? {
        val from = startBox ?: return null
        val result = boxResize(from, handle, pointer, margin)
        box = result.obb
        return result.transform
    }

    /** A rectangle of tape's corner moves freely; every other box handle scales as [ResizeMath.obbResize] does. */
    private fun boxResize(from: Obb, handle: HandleId, pointer: Pt, margin: Double): ResizeMath.ObbResize =
        if (loneTapeBox != null && handle in CORNERS) ResizeMath.obbResizeFree(from, handle, pointer) else ResizeMath.obbResize(from, handle, pointer, margin)

    /** Drag the lone strip of tape's [handle] end to [pointer], snapped level or upright against the other end, as when laid. */
    private fun dragTapeEnd(handle: HandleId, pointer: Pt) {
        val tape = loneTapeStrip ?: return
        tape.restoreGeometry(startSnapshots[0])
        val (a, b) = tape.shape.points
        val ends = if (handle == HandleId.START) listOf(TapeGeometry.snap(b, pointer), b) else listOf(a, TapeGeometry.snap(a, pointer))
        tape.shape = tape.shape.copy(points = ends)
        box = boxOf(items)
        doc.itemsChanged(items)
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

    /** Re-file [item] after a change the selection did not make itself, a point edit, and refit the box. */
    fun itemChanged(item: CanvasItem) {
        doc.itemsChanged(listOf(item))
        refreshBox()
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
        follow.settle()
        // Connectors are recorded whole, glue and all; a plain move of anything else stays a move.
        val moved = ArrayList<CanvasItem>()
        val shaped = ArrayList<CanvasItem>()
        val before = ArrayList<GeometrySnapshot>()
        for (i in items.indices) {
            val item = items[i]
            if (follow.places(item)) continue
            if (movedOnly && item !is ConnectorItem) {
                moved += item
            } else {
                shaped += item
                before += startSnapshots[i]
            }
        }
        shaped += follow.items
        before += follow.startSnapshots
        val after = shaped.map { it.snapshotGeometry() }
        val cmds = listOfNotNull(
            if (moved.isNotEmpty() && (dx != 0.0 || dy != 0.0)) MoveItems(moved, dx, dy) else null,
            if (shaped.isNotEmpty() && (!movedOnly || before != after)) TransformItems(shaped, before, after) else null,
        )
        if (cmds.isEmpty()) return null
        return OnCanvas(doc, cmds.singleOrNull() ?: CompositeCommand(cmds), moved + shaped)
    }

    /**
     * Mirror everything selected about the box's centre, left to right or [upsideDown], with the
     * connectors glued to it, and the box with them; the undoable edit, or null when nothing is selected.
     */
    fun mirror(upsideDown: Boolean): Command? {
        val b = box ?: return null
        val c = b.center
        beginTransform(rigid = true)
        applyLive(if (upsideDown) Affine.scaleAbout(c, 1.0, -1.0) else Affine.scaleAbout(c, -1.0, 1.0))
        val command = buildCommand(movedOnly = false)
        box = if (isFramedShape) boxOf(items) else b.copy(angle = -b.angle)
        return command
    }

    /** True when [item] is still on the canvas, so glue to it counts. */
    fun holds(item: CanvasItem): Boolean = doc.holds(item)

    /** The connectors that keep joined to [moving] as it changes; see [GlueFollow.of]. */
    fun followOf(moving: List<CanvasItem>, rigid: Boolean, pin: Boolean): GlueFollow =
        GlueFollow.of(moving, doc.items.filter { it is ConnectorItem }, doc::holds, rigid, pin)

    /** The undoable edit that took [items] from [before] to how they are now, re-filed on undo and redo. */
    fun commandFor(items: List<CanvasItem>, before: List<GeometrySnapshot>): Command =
        OnCanvas(doc, TransformItems(items, before, items.map { it.snapshotGeometry() }), items)

    private fun boundsOf(of: List<CanvasItem>): Rect? {
        var acc: Rect? = null
        for (item in of) {
            val b = item.bounds()
            acc = acc?.union(b) ?: b
        }
        return acc
    }
}
