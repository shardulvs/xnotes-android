package com.xnotes.ui

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.canvas.HandleId
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.ResizeMath
import com.xnotes.canvas.SelectionMath
import com.xnotes.canvas.StylusButtonLatch
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasSelection
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.EraseSession
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorDraft
import com.xnotes.core.model.ConnectorEdit
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.EraseRule
import com.xnotes.core.model.Glue
import com.xnotes.core.model.GlueFollow
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Labelled
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TapeMode
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.Scribble
import com.xnotes.core.stroke.ShapeRecognizer
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tape.TapeGeometry
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import kotlin.math.exp

/** What the current gesture is doing. */
enum class CanvasPointerMode { IDLE, PAN, PINCH, DRAW, ERASE, SHAPE, BAND, LASSO, MOVE, RESIZE, ROTATE, VERTEX, POLYGON, CONNECT, JOINT, SLIDE, TAPE }

/**
 * Gestures on the infinite canvas.
 *
 * The navigation half is what an infinite canvas needs before anything else, and it is
 * deliberately the paged canvas's feel rather than a second one: the velocity smoothing, fling
 * friction and start/stop thresholds are read from [InteractionController]'s constants rather than
 * copied, so tuning either canvas tunes both.
 *
 * Everything a pinch changes is two numbers on [CanvasViewport], and the renderer takes them as
 * shader uniforms. There is no cache to invalidate, no settle debounce, and no zoom clamp against
 * a page: zoom runs the full configured range and scroll runs forever in all four directions.
 */
class InfiniteInteraction(
    private val viewport: CanvasViewport,
    private val requestRender: () -> Unit,
    /** Called whenever the view moved, so the host can refresh a zoom readout or schedule a save. */
    private val onViewChanged: () -> Unit = {},
    /** True while a gesture or a glide is live, so the renderer can keep drawing every refresh. */
    private val setInteractive: (Boolean, Boolean) -> Unit = { _, _ -> },
    /** The style the armed tool draws with, including the toolbar's active ink colour. */
    private val configFor: (Tool) -> ToolConfig = { ToolConfig() },
    /** The wet stroke changed: re-tessellate it into the dynamic buffer, or clear it when null. */
    private val onWetStroke: (Stroke?) -> Unit = {},
    /** Pen up on a finished stroke: add it to the document and push the undo command. */
    private val onCommitStroke: (Stroke) -> Unit = {},
    /** Pen up on a scribble over ink: take what it touched off, the scribble with it. */
    private val onScribbleErase: (Stroke, List<CanvasItem>) -> Unit = { _, _ -> },
    /** Begin an eraser drag; the host owns the document and the undo stack. */
    private val onEraseBegin: () -> EraseSession? = { null },
    /** The eraser drag ended: push its single undo command. */
    private val onEraseEnd: (EraseSession) -> Unit = {},
    /** An eraser drag lifted, rather than being cut short by a pinch or a cancel. */
    private val onEraseLifted: () -> Unit = {},
    /** Where the eraser cursor sits in viewport pixels, and how wide, or null to hide it. */
    private val onEraserCursor: (Pt?, Double) -> Unit = { _, _ -> },
    /** The shape or connector being drawn, re-tessellated as it grows, or null to clear the preview. */
    private val onPendingShape: (CanvasItem?) -> Unit = {},
    /** A finished shape or connector: add it to the document and push the undo command. */
    private val onCommitShape: (CanvasItem) -> Unit = {},
    /** The shape tool's style, and the active ink colour it draws in. */
    private val shapeConfig: () -> ShapeConfig = { ShapeConfig() },
    private val inkColor: () -> Rgba = { InkPalette.DEFAULT },
    /** Whether a held pen stroke may snap to a recognized shape. */
    private val detectShapes: () -> Boolean = { true },
    /** The selection, owned by the host so the chrome can read it. */
    private val selection: () -> CanvasSelection? = { null },
    /** Items the selection may be drawn from, culled to a rect. */
    private val itemsIn: (Rect) -> List<CanvasItem> = { emptyList() },
    /** The selection or its overlay changed: rebuild the chrome geometry. */
    private val onSelectionChanged: () -> Unit = {},
    /** A finished selection drag: push its single undo command. */
    private val onCommitSelection: (com.xnotes.core.history.Command?) -> Unit = {},
    /** Draw [items] displaced by the drag so far, rather than moving them. An empty list ends it. */
    private val onLiftSelection: (List<CanvasItem>, LiftTransform) -> Unit = { _, _ -> },
    /** Content pixels per dp, so the speed pen judges gesture speed independently of zoom. */
    private val devicePxPerDp: () -> Double = { 1.0 },
    /** A press that landed on the minimap; returns true when it was consumed as navigation. */
    private val onMinimapPress: (Double, Double) -> Boolean = { _, _ -> false },
    /**
     * A finger held still: open the paste menu at this viewport and content point, or offer to
     * release the locked item it landed on, which arrives as the third argument.
     */
    private val onContextMenu: (Pt, Pt, CanvasItem?) -> Unit = { _, _, _ -> },
    /** Whether copied items or a clipboard image are waiting, so empty canvas has a menu to offer. */
    private val hasPasteContent: () -> Boolean = { false },
    /** A tool this layer armed by itself, so the chrome can follow: a long-press grab and its end. */
    private val onToolChanged: (Tool) -> Unit = {},
    /** Two quick taps in one place, at this viewport point, by this pointer type, with what was selected as the first went down. */
    private val onDoubleTap: (Pt, Int, Set<CanvasItem>) -> Unit = { _, _, _ -> },
    /** Whether a shape's label is open in the text overlay. */
    private val isEditingText: () -> Boolean = { false },
    /** End that edit, keeping what was typed. */
    private val onCommitText: () -> Unit = {},
    /** A pan that ended as a tap, at this content point, which an audio pill there may take. */
    private val onAudioTap: (Pt) -> Boolean = { false },
    /** Peel this tape back, or cover it again: a look, never an edit. */
    private val onPeelTape: (TapeItem) -> Unit = {},
    /** The view was panned or pinched by hand, as distinct from moved by the app. */
    private val onUserMovedView: () -> Unit = {},
) {

    private val choreographer = Choreographer.getInstance()

    companion object {
        /** How near a handle a press has to land to grab it, in device pixels. */
        const val HANDLE_TOUCH_PX = 22.0

        /** Least on-screen distance between lasso vertices, so a slow drag stays cheap. */
        const val LASSO_MIN_STEP_PX = 3.0

        /** How far a press may wander and still count as a tap, in device pixels. */
        const val TAP_SLOP_PX = 12.0

        /** How far a drawn connector's route keeps clear of what it goes round, in dp. */
        const val ROUTE_MARGIN_DP = 12.0

        /** Past this many connection points on screen, none are marked. */
        const val MAX_MARKS = 4000
    }

    /** The armed tool. */
    var tool: Tool = Tool.PEN
        set(value) {
            // Leaving the shape tool ends a polygon placed corner by corner, keeping what is down.
            if (value != field && value != Tool.SHAPE) finishPolygonDraft()
            field = value
        }

    /** Whether a finger draws, or pans instead. Mirrors the paged canvas's preference. */
    var fingerDraws: Boolean = false

    /** Tool the stylus side button arms while it is held; null leaves the button alone. */
    var penButtonTool: Tool? = Tool.ERASER

    /** Zoom lock: a pinch pans without changing the zoom, mirroring the paged canvas. */
    var zoomLocked: Boolean = false

    /** Disappearing ink is armed, so a held stroke must not become a committed shape. */
    var wandEnabled: Boolean = false

    /** Which pans still move a locked view: "single" (default) | "double" | "none". */
    var zoomLockPan: String = "single"

    private val stylusButtons = StylusButtonLatch()

    private var eraseSession: EraseSession? = null

    // The shape being dragged out with the shape tool.
    private var pendingShape: ShapeItem? = null

    // Tape being laid: the press, the farthest the drag has gone from it, and a freehand band's path.
    private var pendingTape: TapeItem? = null
    private var tapeStart: Pt? = null
    private var tapeReach = 0.0
    private val tapePath = ArrayList<Pt>()

    // A connector being drawn with a connector chip armed.
    private var connectorDraft: ConnectorDraft? = null

    // A drag of a lone connector's own handle, and the connectors glued to it that follow.
    private var jointEdit: ConnectorEdit? = null
    private var jointFollow: GlueFollow = GlueFollow.NONE
    private var jointDownAt = Pt.ZERO
    private var jointMoved = false

    // A label being slid along its line, and the item as the slide found it.
    private var slideItem: Labelled? = null
    private var slideBefore: com.xnotes.core.model.GeometrySnapshot? = null

    // The last tap on a straight connector's waypoint, for the double tap that removes it.
    private var lastWaypointTap = -1
    private var lastWaypointTapAt = 0L

    /** The connection points lit for the chrome: under a hovering pen, where a connector set off and where it is going. */
    var connectHot: List<Pt> = emptyList()
        private set

    /** True when the shape tool has a connector chip armed, so the points connectors glue to show. */
    val connecting: Boolean get() = tool == Tool.SHAPE && shapeConfig().connector != null

    /** True while the points connectors glue to show: a connector armed, or an end being dragged to glue again. */
    val marking: Boolean
        get() = connecting || jointEdit?.grab.let { it == ConnectorEdit.Grab.START || it == ConnectorEdit.Grab.END }

    // Band and lasso, in content space, live only while their gesture runs.
    var bandRect: Rect? = null
        private set
    val lassoPoints = ArrayList<Pt>()
    private var bandAnchor = Pt.ZERO

    // The live transform drag.
    private var grabHandle: HandleId? = null
    private var moveAnchor = Pt.ZERO
    private var movedBy = Pt.ZERO

    // Where a handle or the rotate grip is being dragged to, and whether the renderer is doing the
    // work. Ink, shapes and placed SVGs map faithfully in the shader, since all three are triangles.
    // A photo or a text box is rebuilt by the model rather than mapped, so a selection holding one is
    // transformed the slow way and stays honest.
    private var transformPointer = Pt.ZERO
    private var liftedTransform = false

    // A lifted resize leaves shapes out and bakes them every frame: they keep their outline width
    // through a resize, which a mesh scaled by the shader cannot.
    private var bakedInLift: Set<CanvasItem> = emptySet()
    private var liftItems: List<CanvasItem> = emptyList()

    // How far outside the box a resize's handle sat, which the box edge keeps behind the pointer.
    private var transformMargin = 0.0

    // A point drag on a lone shape: what it grabbed, the shape as it was before (for a tap that
    // changes nothing) and as the drag measures from (free, split if it began on an edge dot).
    private var vertexShape: ShapeItem? = null
    private var vertexSpot: com.xnotes.core.shapes.PathSpot? = null
    private var vertexBefore: com.xnotes.core.model.GeometrySnapshot? = null
    private var vertexBase: com.xnotes.core.model.GeometrySnapshot? = null
    private var vertexDownAt = Pt.ZERO
    private var vertexMoved = false
    private var vertexWasEdge = false

    // Set when the point drag is a knob's rather than a vertex's.
    private var knobIndex = -1

    // The last tap on a vertex dot, for the double tap that deletes it.
    private var lastVertexTap: com.xnotes.core.shapes.PathSpot? = null
    private var lastVertexTapAt = 0L

    // A finger press landed off a live selection and became a pan; a tap there dismisses it, a
    // real drag pans and leaves it alone.
    private var panMayDismiss = false
    private var panDownAt = Pt.ZERO

    // A finger press while a label is open became a pan; a tap there ends the edit.
    private var panMayCommit = false

    // This stroke began by dismissing a live selection; a bare tap then leaves no dot.
    private var strokeDismissedSelection = false

    // A Shape tool press that only put the selection away, where it went down: a tap there is still a Shape tool tap.
    private var dismissPressAt: Pt? = null

    // A finger held still grabs the item under it, or opens the paste menu on empty canvas.
    private var longPressRunnable: Runnable? = null
    private var longPressAt = Pt.ZERO
    private var longPressCandidate: CanvasItem? = null
    private var longPressLocked: CanvasItem? = null

    // The tool a long-press grab borrowed the canvas from, given back when the selection goes.
    private var longPressPrevTool: Tool? = null

    /** Whether a long-press grab is borrowing the selection tool, which it hands back by itself. */
    val grabbing: Boolean get() = longPressPrevTool != null

    // Hold-still-to-snap: a freehand stroke that stops moving becomes the shape it looks like.
    private val handler = Handler(Looper.getMainLooper())
    private var dwellRunnable: Runnable? = null
    private var dwellEligible = false
    private var dwellAnchor = Pt.ZERO

    var mode = CanvasPointerMode.IDLE
        private set

    // The stroke being drawn, live until the pen lifts.
    private var liveStroke: Stroke? = null
    private var drawingPointerId = -1
    private var drawingIsStylus = false
    private var strokeStartTimeMs = 0L

    // Pan and inertial fling, in viewport px and viewport px/s.
    private var lastPan = Pt.ZERO
    private var lastMoveMs = 0L
    private var panVel = Pt.ZERO
    private var panFromPenButton = false // a side-button pan parks where the pen lifted: no glide
    private var flinging = false
    private var flingVel = Pt.ZERO
    private var lastFlingMs = 0L
    private val flingFrame = Choreographer.FrameCallback { stepFling(it) }

    // Pinch.
    private var pinchInitDist = 1.0
    private var pinchInitZoom = 1.0
    private var pinchAnchorContent = Pt.ZERO

    // The view as this touch went down, put back if Android takes the touch back (see takeBackTouch).
    private var downScroll = Pt.ZERO
    private var downZoom = 1.0

    // Android took back a finger of this touch, so the rest of it is ignored.
    private var touchTakenBack = false

    fun onTouch(e: MotionEvent): Boolean {
        if (touchTakenBack && e.actionMasked != MotionEvent.ACTION_DOWN) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTakenBack = false
                tapCandidate = true
                tapDownAt = Pt(e.x.toDouble(), e.y.toDouble())
                tapDownTime = e.eventTime
                tapDownSelected = selection()?.items?.toHashSet() ?: emptySet()
                handleDown(e)
                downScroll = Pt(viewport.scrollX, viewport.scrollY)
                downZoom = viewport.zoom
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapCandidate = false
                handlePointerDown(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (tapCandidate && Pt(e.x.toDouble(), e.y.toDouble()).distanceTo(tapDownAt) > TAP_SLOP_PX) tapCandidate = false
                handleMove(e)
            }
            MotionEvent.ACTION_POINTER_UP ->
                if (movingView() && InteractionController.isCancelledLift(e)) {
                    touchTakenBack = true
                    takeBackTouch()
                } else {
                    handlePointerUp(e)
                }
            MotionEvent.ACTION_UP -> {
                handleUp(e)
                tapUp(e)
            }
            MotionEvent.ACTION_CANCEL -> takeBackTouch()
        }
        return true
    }

    private fun movingView(): Boolean = mode == CanvasPointerMode.PAN || mode == CanvasPointerMode.PINCH

    /** Android took this touch back, as its palm rejection does with a resting hand: drop the
     *  gesture, and put back a view it scrolled or zoomed, as the paged canvas does. */
    private fun takeBackTouch() {
        tapCandidate = false
        val movedView = movingView()
        abortGesture()
        if (movedView) {
            viewport.zoom = downZoom
            viewport.scrollX = downScroll.x
            viewport.scrollY = downScroll.y
            onViewChanged()
            requestRender()
        }
    }

    // Taps, for the double tap that types a shape's label.
    private var tapCandidate = false
    private var tapDownAt = Pt.ZERO
    private var tapDownTime = 0L
    private var lastTapAt: Pt? = null
    private var lastTapTime = 0L

    // What was selected as each tap went down: a double tap labels only a shape that was not.
    private var tapDownSelected: Set<CanvasItem> = emptySet()
    private var lastTapSelected: Set<CanvasItem> = emptySet()

    /** A tap that follows another close by and soon after is a double tap. */
    private fun tapUp(e: MotionEvent) {
        val at = Pt(e.x.toDouble(), e.y.toDouble())
        val tap = tapCandidate && e.eventTime - tapDownTime < InteractionController.LONG_PRESS_MS
        tapCandidate = false
        if (!tap) {
            lastTapAt = null
            return
        }
        val prev = lastTapAt
        if (prev != null && tapDownTime - lastTapTime <= InteractionController.DOUBLE_TAP_MS &&
            prev.distanceTo(at) <= InteractionController.DOUBLE_TAP_SLOP_DP * devicePxPerDp()
        ) {
            lastTapAt = null
            onDoubleTap(at, e.getToolType(0), lastTapSelected)
            return
        }
        lastTapAt = at
        lastTapTime = e.eventTime
        lastTapSelected = tapDownSelected
    }

    /**
     * The topmost unlocked shape at viewport [vp] that takes a label, for a double tap with the
     * Shape or Select tool or a finger that pans: on its outline, fill or label, or anywhere inside.
     * None when that shape was in [selectedBefore], the selection as the first tap went down: on a
     * selected shape a double tap is a vertex's, whatever its kind.
     */
    fun labelTargetAt(vp: Pt, toolType: Int, selectedBefore: Set<CanvasItem>): Labelled? {
        val fingerPans = toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff
        if (tool != Tool.SHAPE && tool != Tool.SELECT && !fingerPans) return null
        if (tool == Tool.SHAPE && shapeConfig().placesCorners) return null
        val at = viewport.viewportToContent(vp)
        val reach = InteractionController.OUTLINE_GRAB / viewport.zoom
        val near = Rect(at.x - reach, at.y - reach, 2 * reach, 2 * reach)
        val target = itemsIn(near).lastOrNull { item ->
            !item.locked && when (item) {
                is ShapeItem -> item.takesLabel && (item.touches(at, reach) || (item.shape.isClosed && item.encloses(at)))
                is ConnectorItem -> item.touches(at, reach)
                else -> false
            }
        } as? Labelled
        return target?.takeUnless { it in selectedBefore }
    }

    /**
     * Latch a side button reported only on the hovering generic-motion stream, which is the only
     * place some pens put it.
     */
    fun onGenericMotion(e: MotionEvent) {
        stylusButtons.onGenericMotion(e)
    }

    /** Latch a side button delivered as a key event, which is all Bluetooth and USI pens send. */
    fun onStylusButtonKey(keyCode: Int, down: Boolean): Boolean = stylusButtons.onKey(keyCode, down)

    /** Drop any in-flight gesture and stop a glide, so a document swap cannot bleed into the next. */
    fun resetGestureState() {
        stopFling()
        cancelLongPress()
        longPressPrevTool = null
        mode = CanvasPointerMode.IDLE
        dismissPressAt = null
        panVel = Pt.ZERO
        liveStroke = null
        endLift()
        onWetStroke(null)
        onLiftSelection(emptyList(), LiftTransform.NONE)
        stylusButtons.reset()
    }

    // --- pointer handling ---

    private fun handleDown(e: MotionEvent) {
        stopFling() // a new touch halts any in-progress glide
        panMayCommit = false
        dismissPressAt = null
        // The minimap sits over the canvas, so a press on it navigates rather than draws.
        if (onMinimapPress(e.getX(0).toDouble(), e.getY(0).toDouble())) {
            mode = CanvasPointerMode.IDLE
            return
        }
        // A press off an open label: a finger's tap ends the edit and its drag scrolls with it kept
        // open, as on a note; anything else ends it and goes on as usual.
        if (isEditingText()) {
            if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                drawingPointerId = e.getPointerId(0)
                drawingIsStylus = false
                panMayCommit = true
                beginPan(e.getX(0).toDouble(), e.getY(0).toDouble())
                return
            }
            onCommitText()
        }
        drawingPointerId = e.getPointerId(0)
        drawingIsStylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val vx = e.getX(0).toDouble()
        val vy = e.getY(0).toDouble()

        // Which tool this pointer actually drives: the pen's eraser end and its held side button
        // both override the armed tool, and a finger pans unless finger-draw is on. This mirrors
        // the paged canvas so a pen behaves the same on either surface.
        val toolType = e.getToolType(0)
        val buttonHeld = stylusButtons.heldFor(e)
        val at = viewport.viewportToContent(Pt(vx, vy))
        val drawing = (tool.isStroke || tool == Tool.SHAPE || tool == Tool.TAPE) && toolType != MotionEvent.TOOL_TYPE_FINGER
        // Taken before a draft is finished below, since that selects its polygon.
        val selected = hasSelection()
        // With a connector armed, a press on a point it can glue to starts one, or only dismisses what is selected.
        val inks = toolType != MotionEvent.TOOL_TYPE_FINGER || fingerDraws
        if (connecting && inks && !buttonHeld && toolType != MotionEvent.TOOL_TYPE_ERASER && glueHitAt(at) != null) {
            cancelLongPress()
            clearSelection()
            if (polygonDraft != null) finishPolygonDraft()
            if (selected) dismissFirst(at) else beginConnector(vx, vy)
            return
        }
        // A drawing tool grabs a lone shape only by the shape itself; a finger grabs the whole box.
        val onSelection = if (drawing) drawingGrabsSelection(at) else hitsSelection(at)
        val effective: Tool = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            buttonHeld && penButtonTool != null -> penButtonTool!!
            // While something is selected, a press on it grabs it rather than inking through it,
            // and a finger may grab it even with finger-draw off. Both are the paged canvas's
            // rules; without them a selection could only be handled by a stylus.
            onSelection && drawing -> Tool.SELECT
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff &&
                onSelection -> Tool.SELECT
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff -> Tool.PAN
            else -> tool
        }
        // A press that lands off the selection with anything but the selection tools dismisses it,
        // which is what makes an empty tap put the chrome away.
        panMayDismiss = false
        strokeDismissedSelection = false
        if (!onSelection && effective != Tool.SELECT && effective != Tool.LASSO) {
            if (effective == Tool.PAN) {
                panMayDismiss = hasSelection()
            } else {
                strokeDismissedSelection = effective.isStroke && hasSelection()
                clearSelection()
            }
        }

        armLongPress(Pt(vx, vy), onSelection, toolType == MotionEvent.TOOL_TYPE_FINGER)

        // A draft only lives through Free polygon presses; anything else that draws or edits ends it.
        val placing = effective == Tool.SHAPE && shapeConfig().placesCorners
        if (polygonDraft != null && !placing && effective != Tool.PAN) finishPolygonDraft()
        when {
            effective.isStroke -> beginDraw(vx, vy, effective, e)
            effective == Tool.ERASER -> beginErase(vx, vy)
            effective == Tool.SHAPE && selected -> dismissFirst(at)
            placing -> beginPolygonTap(vx, vy)
            effective == Tool.SHAPE && shapeConfig().connector != null -> beginConnector(vx, vy)
            effective == Tool.SHAPE -> beginShape(vx, vy)
            effective == Tool.SELECT -> beginSelect(vx, vy)
            effective == Tool.LASSO -> beginLasso(vx, vy)
            effective == Tool.TAPE -> beginTape(vx, vy)
            else -> beginPan(vx, vy, fromPenButton = buttonHeld && penButtonTool == Tool.PAN)
        }
    }

    // --- long press: grab an item, or the paste menu on empty canvas ---

    /**
     * Arm what a finger held still does: pick up the item under it, or open the paste menu when
     * there is nothing there.
     *
     * Finger only, like the paged canvas: the stylus always draws, so resting it never grabs or pops
     * a menu. A press on the live selection is left alone, because that already means something.
     */
    private fun armLongPress(at: Pt, onSelection: Boolean, isFinger: Boolean) {
        cancelLongPress()
        if (!isFinger || onSelection) return
        val hit = itemAt(viewport.viewportToContent(at))
        // The eraser is the one tool a grab would fight with, so it is the one that leaves an item
        // alone. Everything else hands it over, which is what makes a held finger pick something up
        // without having to reach for the selection tool first.
        val grabbable = tool.isStroke || tool == Tool.PAN || tool == Tool.SELECT ||
            tool == Tool.LASSO || tool == Tool.SHAPE
        // A locked item cannot be picked up, so a held finger offers to release it instead. That is
        // the only way back: it is out of reach of the band, the lasso and every tap. The offer
        // stands whatever tool is armed, since no tool can do anything else with one.
        longPressLocked = hit?.takeIf { it.locked }
        if (hit != null && longPressLocked == null && !grabbable) return
        // Like the paged canvas, empty space only pops the menu when there is something to paste.
        if (hit == null && !hasPasteContent()) return
        longPressCandidate = hit?.takeUnless { it.locked }
        longPressAt = at
        val r = Runnable { triggerLongPress() }
        longPressRunnable = r
        handler.postDelayed(r, InteractionController.LONG_PRESS_MS)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        longPressCandidate = null
        longPressLocked = null
    }

    private fun triggerLongPress() {
        longPressRunnable = null
        val candidate = longPressCandidate
        longPressCandidate = null
        val locked = longPressLocked
        longPressLocked = null
        dismissPressAt = null
        // The gesture underway is only ever a pan or a stroke that has not moved; drop it so what
        // follows is not fighting a drag, and stop the press from also dismissing the selection.
        if (mode == CanvasPointerMode.DRAW) abandonStroke()
        if (mode == CanvasPointerMode.SHAPE) abandonShape()
        if (mode == CanvasPointerMode.TAPE) abandonTape()
        if (mode == CanvasPointerMode.CONNECT) abandonConnector()
        mode = CanvasPointerMode.IDLE
        panMayDismiss = false
        if (candidate != null) {
            grabItem(candidate)
        } else {
            setInteractive(false, true)
            onContextMenu(longPressAt, viewport.viewportToContent(longPressAt), locked)
        }
        requestRender()
    }

    /**
     * Hand the held item to the selection tool and start moving it, so a long press picks something
     * up whatever tool is armed. The borrowed tool comes back when the selection is put away, and
     * the handles arrive with the chrome the moment the finger lifts.
     */
    private fun grabItem(item: CanvasItem) {
        val sel = selection() ?: return
        if (tool != Tool.SELECT) {
            longPressPrevTool = tool
            tool = Tool.SELECT
            onToolChanged(Tool.SELECT)
        }
        sel.select(listOf(item))
        setInteractive(false, false)
        beginMoveAt(sel, viewport.viewportToContent(longPressAt))
        onSelectionChanged()
    }

    private fun handlePointerDown(e: MotionEvent) {
        cancelLongPress() // a second finger is a pinch, never a held press
        // A stylus stroke ignores an incidental palm or second finger; a finger stroke yields to a
        // pinch, since two fingers can only mean a zoom.
        if (mode == CanvasPointerMode.DRAW && drawingIsStylus) return
        if (mode == CanvasPointerMode.ERASE && drawingIsStylus) return
        if (mode == CanvasPointerMode.TAPE && drawingIsStylus) return
        if (e.pointerCount >= 2) {
            if (mode == CanvasPointerMode.DRAW) abandonStroke()
            if (mode == CanvasPointerMode.TAPE) abandonTape()
            // A finger erase yields to a pinch: commit what it already removed rather than lose it.
            if (mode == CanvasPointerMode.ERASE) endErase()
            beginPinch(e)
        }
    }

    private fun handleMove(e: MotionEvent) {
        // A finger that wanders is panning or drawing, not holding still for the menu.
        if (longPressRunnable != null) {
            val moved = Pt(e.getX(0).toDouble(), e.getY(0).toDouble()).distanceTo(longPressAt)
            if (moved > InteractionController.LONG_PRESS_SLOP) cancelLongPress()
        }
        when (mode) {
            CanvasPointerMode.PAN -> extendPan(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.PINCH -> updatePinch(e)
            CanvasPointerMode.DRAW -> extendDraw(e)
            CanvasPointerMode.ERASE -> extendErase(e)
            CanvasPointerMode.SHAPE -> extendShape(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.TAPE -> extendTape(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.CONNECT -> extendConnector(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.JOINT -> extendJoint(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.SLIDE -> extendSlide(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.BAND -> extendBand(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.LASSO -> extendLasso(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.MOVE -> extendMove(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.RESIZE -> extendResize(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.ROTATE -> extendRotate(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.VERTEX -> extendVertex(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.POLYGON -> showPolygonDraft(viewport.viewportToContent(Pt(e.getX(0).toDouble(), e.getY(0).toDouble())))
            CanvasPointerMode.IDLE -> Unit
        }
    }

    private fun handlePointerUp(e: MotionEvent) {
        if (mode != CanvasPointerMode.PINCH) return
        // Dropping to one finger continues as a pan from wherever that finger is, so a pinch that
        // relaxes into a drag does not jump.
        if (e.pointerCount == 2) {
            val remaining = if (e.actionIndex == 0) 1 else 0
            beginPan(e.getX(remaining).toDouble(), e.getY(remaining).toDouble())
        } else {
            mode = CanvasPointerMode.IDLE
        }
    }

    private fun handleUp(e: MotionEvent) {
        cancelLongPress()
        val wasMoving = (mode == CanvasPointerMode.PAN && singleFingerPanAllowed() && !panFromPenButton) ||
            (mode == CanvasPointerMode.PINCH && pinchPanAllowed())
        // A pan that never moved is a tap, and a tap on a pill plays it, as a finger opens links on a note.
        val panTap = mode == CanvasPointerMode.PAN && !panFromPenButton && panWasTap(e.getX(0).toDouble(), e.getY(0).toDouble())
        // A tape lies over whatever else the tap could reach, so it takes the tap first.
        val tapeTapped = panTap && peelTapeAt(viewport.viewportToContent(Pt(e.getX(0).toDouble(), e.getY(0).toDouble())))
        val audioTapped = !tapeTapped && panTap && onAudioTap(viewport.viewportToContent(Pt(e.getX(0).toDouble(), e.getY(0).toDouble())))
        // A finger tap off the selection puts it away; a tap is the only way to say so with a
        // finger, since a drag there is a pan.
        if (!audioTapped && !tapeTapped && mode == CanvasPointerMode.PAN && panMayDismiss &&
            panWasTap(e.getX(0).toDouble(), e.getY(0).toDouble())
        ) {
            clearSelection()
        }
        panMayDismiss = false
        if (mode == CanvasPointerMode.PAN && panMayCommit && panWasTap(e.getX(0).toDouble(), e.getY(0).toDouble())) onCommitText()
        panMayCommit = false
        if (mode == CanvasPointerMode.DRAW) endDraw(e)
        if (mode == CanvasPointerMode.ERASE) {
            endErase()
            onEraseLifted()
        }
        if (mode == CanvasPointerMode.SHAPE) endShape()
        if (mode == CanvasPointerMode.TAPE) endTape()
        if (mode == CanvasPointerMode.CONNECT) endConnector()
        when (mode) {
            CanvasPointerMode.BAND -> endBand()
            CanvasPointerMode.LASSO -> endLasso()
            CanvasPointerMode.MOVE -> endMove()
            CanvasPointerMode.RESIZE, CanvasPointerMode.ROTATE -> endTransform()
            CanvasPointerMode.VERTEX -> endVertex(e.eventTime)
            CanvasPointerMode.JOINT -> endJoint(e.eventTime)
            CanvasPointerMode.SLIDE -> endSlide()
            CanvasPointerMode.POLYGON -> endPolygonTap(viewport.viewportToContent(Pt(e.getX(0).toDouble(), e.getY(0).toDouble())), e.eventTime)
            CanvasPointerMode.IDLE -> dismissPressAt?.let { at ->
                dismissPressAt = null
                if (tapCandidate) shapeTap(at)
            }
            else -> Unit
        }
        mode = CanvasPointerMode.IDLE
        setInteractive(false, true)
        if (wasMoving) startFling(panVel)
        onViewChanged()
        // Once more now the gesture has settled, so chrome that only shows on a still selection
        // gets its chance; during the drag the mode was still MOVE.
        onSelectionChanged()
        requestRender()
    }

    private fun abortGesture() {
        cancelLongPress()
        if (mode == CanvasPointerMode.DRAW) abandonStroke()
        if (mode == CanvasPointerMode.ERASE) endErase()
        if (mode == CanvasPointerMode.SHAPE) abandonShape()
        if (mode == CanvasPointerMode.TAPE) abandonTape()
        if (mode == CanvasPointerMode.CONNECT) abandonConnector()
        // A cancelled drag never happened: the model was never touched, so putting the box back and
        // dropping the lift is the whole undo.
        if (mode == CanvasPointerMode.MOVE) {
            selection()?.previewMove(0.0, 0.0)
            selection()?.unfollow()
            onLiftSelection(emptyList(), LiftTransform.NONE)
            onSelectionChanged()
        }
        if (mode == CanvasPointerMode.VERTEX) abandonVertex()
        if (mode == CanvasPointerMode.JOINT) abandonJoint()
        if (mode == CanvasPointerMode.SLIDE) abandonSlide()
        if (mode == CanvasPointerMode.POLYGON) showPolygonDraft(null)
        if ((mode == CanvasPointerMode.ROTATE || mode == CanvasPointerMode.RESIZE) && liftedTransform) {
            selection()?.previewBack()
            if (bakedInLift.isNotEmpty()) selection()?.applyLiveTo(bakedInLift, Affine.IDENTITY)
            selection()?.unfollow()
            onLiftSelection(emptyList(), LiftTransform.NONE)
            endLift()
            onSelectionChanged()
        }
        mode = CanvasPointerMode.IDLE
        setInteractive(false, true)
        stopFling()
        requestRender()
    }

    // --- drawing ---

    private fun beginDraw(vx: Double, vy: Double, drawTool: Tool, e: MotionEvent) {
        val base = configFor(drawTool)
        // SCALE off: divide the width by the draw-time zoom so the stroke keeps a constant
        // on-screen thickness whatever zoom it was drawn at. Baked in, so it is ordinary ink after.
        val z = viewport.zoom
        val config = if (base.scale) {
            base
        } else {
            base.copy(
                baseWidth = base.baseWidth / z,
                dashLength = base.dashLength / z,
                dashGap = base.dashGap / z,
                scale = true,
            )
        }
        val straight = drawTool == Tool.HIGHLIGHTER && config.straightLine
        val stroke = Stroke(
            drawTool, config,
            speedScale = z / devicePxPerDp().coerceAtLeast(1e-9),
            straight = straight,
            smoothScale = InteractionController.smoothScaleFor(z),
        )
        // Live until the pen lifts, so lift-time rules cannot fire mid-draw.
        stroke.finished = false
        // Inking wants the shortest path from nib to pixel, so it draws on demand: a render thread
        // left running keeps the buffer queue full and puts a frame of lag under the pen.
        setInteractive(false, false)
        strokeStartTimeMs = e.eventTime
        val p = viewport.viewportToContent(Pt(vx, vy))
        stroke.addSample(Sample(p.x, p.y, pressureOf(e, 0)))
        liveStroke = stroke
        mode = CanvasPointerMode.DRAW
        // Only solid pens arm the snap: a highlighter or a straight-line drag never becomes a shape.
        dwellEligible = detectShapes() && drawTool.isStroke && drawTool != Tool.HIGHLIGHTER &&
            !straight && !wandEnabled
        if (dwellEligible) armDwell(Pt(vx, vy))
        onWetStroke(stroke)
        requestRender()
    }

    private fun extendDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId)
        if (idx < 0) return
        // Historical points first: the digitizer batches several samples into one event, and
        // dropping them coarsens a fast stroke into visible chords.
        for (h in 0 until e.historySize) {
            addStrokePoint(
                e.getHistoricalX(idx, h).toDouble(),
                e.getHistoricalY(idx, h).toDouble(),
                if (drawingIsStylus) e.getHistoricalPressure(idx, h).toDouble() else 1.0,
                e.getHistoricalEventTime(h),
                force = false,
            )
        }
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            pressureOf(e, idx), e.eventTime, force = false,
        )
        onWetStroke(liveStroke)
        // Real movement restarts the clock; holding within the slop lets it mature, so the snap
        // fires only once the pen has actually come to rest.
        if (dwellEligible) {
            val here = Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble())
            if (here.distanceTo(dwellAnchor) > InteractionController.SHAPE_DWELL_SLOP) armDwell(here)
        }
        requestRender()
    }

    private fun addStrokePoint(vx: Double, vy: Double, pressure: Double, timeMs: Long, force: Boolean) {
        val stroke = liveStroke ?: return
        val p = viewport.viewportToContent(Pt(vx, vy))
        val t = (timeMs - strokeStartTimeMs).toDouble()
        if (stroke.straight) {
            stroke.setStraightEnd(Sample(p.x, p.y, pressure.coerceIn(0.0, 1.0), t))
            return
        }
        val last = stroke.samples.lastOrNull()
        // Decimate by on-screen spacing rather than content spacing, so drawing while zoomed in
        // keeps its detail instead of faceting into chords a zoom factor long.
        val gate = (InteractionController.MIN_SAMPLE_DIST / viewport.zoom)
            .coerceAtMost(InteractionController.MIN_SAMPLE_DIST)
        if (force || last == null || Pt(last.x, last.y).manhattanTo(p) >= gate) {
            stroke.addSample(Sample(p.x, p.y, pressure.coerceIn(0.0, 1.0), t))
        }
    }

    private fun endDraw(e: MotionEvent) {
        cancelDwell()
        dwellEligible = false
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            pressureOf(e, idx), e.eventTime, force = true,
        )
        val stroke = liveStroke
        liveStroke = null
        // A bare tap whose only job was to dismiss the selection drops the dot it would leave.
        val dismissTap = strokeDismissedSelection && tapCandidate
        strokeDismissedSelection = false
        // So does a bare tap on tape, which peels it back or covers it again.
        val tapeTap = !dismissTap && stroke != null && !stroke.isEmpty && tapCandidate &&
            e.eventTime - tapDownTime < InteractionController.LONG_PRESS_MS && peelTapeAt(Pt(stroke.xAt(0), stroke.yAt(0)))
        if (stroke == null || stroke.isEmpty || dismissTap || tapeTap) {
            onWetStroke(null)
            return
        }
        // The pen is up: rebuild with lift-time rules on before the stroke is committed. The wet
        // buffer is deliberately not cleared here: the commit releases it in the same step, so no
        // frame can land between the two and blink.
        stroke.finished = true
        simplifyForCommit(stroke)
        if (scribbleErase(stroke)) return
        onCommitStroke(stroke)
    }

    /** Scribble to erase: when [stroke] reads as a scribble over ink, erase that instead of committing it. */
    private fun scribbleErase(stroke: Stroke): Boolean {
        val eraser = configFor(Tool.ERASER)
        if (!eraser.scribbleErase || wandEnabled || !stroke.tool.isStroke || stroke.tool == Tool.HIGHLIGHTER) return false
        val dpPerUnit = viewport.zoom / devicePxPerDp().coerceAtLeast(1e-9)
        val path = List(stroke.sampleCount) { Pt(stroke.xAt(it), stroke.yAt(it)) }
        val passes = Scribble.passes(path, dpPerUnit) ?: return false
        val radius = stroke.config.baseWidth / 2.0 + Scribble.TOUCH_DP / dpPerUnit
        // Images, text and anything locked are spared, as the eraser spares them; a scribble is ink, so it takes only lines.
        val candidates = itemsIn(Rect.bounding(path).outset(radius)).filter { EraseRule.erasable(it) }
        val taken = Scribble.taken(passes, radius, candidates) { c, x, y, r -> EraseRule.touchesLine(c, x, y, r) }
        if (taken.isEmpty()) return false
        onWetStroke(null)
        onScribbleErase(stroke, taken)
        return true
    }

    // --- selection ---

    fun hasSelection(): Boolean = selection()?.isEmpty == false

    /** False while a move or a handle drag is in flight, when the shader carries the ink and dots would lag. */
    val showsEditDots: Boolean
        get() = mode != CanvasPointerMode.MOVE && mode != CanvasPointerMode.RESIZE && mode != CanvasPointerMode.ROTATE

    /**
     * Whether [at] grabs the settled selection: its rotate grip, one of its handles, or its body.
     * A press that does is routed to the selection whatever tool is armed and whatever is touching,
     * which is what lets a finger drag a selection while finger-draw is off.
     */
    private fun hitsSelection(at: Pt): Boolean {
        val sel = selection() ?: return false
        if (sel.isEmpty) return false
        sel.loneConnector?.let { return grabsConnector(it, at) }
        val tolerance = HANDLE_TOUCH_PX / viewport.zoom
        val margin = frameMargin()
        val grip = sel.rotateGrip(OverlayTessellator.GRIP_ARM_PX / viewport.zoom, margin)
        if (grip != null && at.distanceTo(grip) <= tolerance) return true
        if (sel.hitHandle(at, tolerance, margin) != null) return true
        // A dot can sit outside the box: a knob out on a callout's tail, a thin line's middle.
        val reach = OverlayTessellator.DOT_TOUCH_PX / viewport.zoom
        if ((sel.items.singleOrNull() as? ShapeItem)?.dotNear(at, reach) == true) return true
        return sel.contains(at, margin)
    }

    /**
     * Whether a drawing tool's press at [at] grabs the selection instead of drawing: a lone shape
     * by its handles, dots, outline or fill only, so a pen can write inside an unfilled one; any
     * other selection anywhere in its box. The paged canvas's rule.
     */
    private fun drawingGrabsSelection(at: Pt): Boolean {
        val sel = selection() ?: return false
        sel.loneConnector?.let { return grabsConnector(it, at) }
        val shape = sel.items.singleOrNull() as? ShapeItem ?: return hitsSelection(at)
        val tolerance = HANDLE_TOUCH_PX / viewport.zoom
        val margin = frameMargin()
        val grip = sel.rotateGrip(OverlayTessellator.GRIP_ARM_PX / viewport.zoom, margin)
        if (grip != null && at.distanceTo(grip) <= tolerance) return true
        if (sel.hitHandle(at, tolerance, margin) != null) return true
        return shape.dotNear(at, OverlayTessellator.DOT_TOUCH_PX / viewport.zoom) ||
            shape.touches(at, InteractionController.OUTLINE_GRAB / viewport.zoom)
    }

    /** Whether [at] lands on lone connector [c]: one of its handles, or its line or label. */
    private fun grabsConnector(c: ConnectorItem, at: Pt): Boolean =
        ConnectorEdit.grab(c, at, OverlayTessellator.DOT_TOUCH_PX / viewport.zoom) != null ||
            c.touches(at, InteractionController.OUTLINE_GRAB / viewport.zoom)

    /** The on-screen gap a lone shape's chrome keeps from its outline, in content pixels. */
    private fun frameMargin(): Double = OverlayTessellator.FRAME_MARGIN_PX / viewport.zoom

    /** Clear the selection and its chrome, for a tool change or a document swap. */
    fun clearSelection() {
        // A long-press grab only borrowed the selection tool; putting the selection away returns it.
        longPressPrevTool?.let {
            longPressPrevTool = null
            tool = it
            onToolChanged(it)
        }
        selection()?.clear()
        bandRect = null
        lassoPoints.clear()
        onLiftSelection(emptyList(), LiftTransform.NONE)
        onSelectionChanged()
        requestRender()
    }

    /**
     * A press on the settled selection grabs it: the grip rotates, a handle resizes, inside the box
     * moves. Shared by the select and lasso tools so both grab the same way, whatever is pressing.
     */
    private fun tryGrabSelection(sel: CanvasSelection, at: Pt): Boolean {
        if (sel.isEmpty) return false
        sel.loneConnector?.let { c ->
            val edit = ConnectorEdit.grab(c, at, OverlayTessellator.DOT_TOUCH_PX / viewport.zoom)
            if (edit != null) {
                beginJoint(edit, at)
                return true
            }
            if (c.labelContains(at)) {
                beginSlide(c)
                return true
            }
            if (!c.touches(at, InteractionController.OUTLINE_GRAB / viewport.zoom)) return false
            beginMoveAt(sel, at)
            return true
        }
        if (tryGrabDot(sel, at)) return true
        val tolerance = HANDLE_TOUCH_PX / viewport.zoom
        val margin = frameMargin()
        val grip = sel.rotateGrip(OverlayTessellator.GRIP_ARM_PX / viewport.zoom, margin)
        if (grip != null && at.distanceTo(grip) <= tolerance) {
            sel.beginTransform(at, rigid = true)
            beginLiftedTransform(sel, at, resize = false)
            mode = CanvasPointerMode.ROTATE
            return true
        }
        val handle = sel.hitHandle(at, tolerance, margin)
        if (handle != null) {
            grabHandle = handle
            transformMargin = sel.chromeMargin(margin)
            sel.beginTransform(rigid = true)
            beginLiftedTransform(sel, at, resize = true)
            mode = CanvasPointerMode.RESIZE
            return true
        }
        // A line's label slides along it, from the label itself.
        (sel.items.singleOrNull() as? Labelled)?.takeIf { it.labelSlides && it.labelContains(at) }?.let {
            beginSlide(it)
            return true
        }
        if (sel.contains(at, margin)) {
            beginMoveAt(sel, at)
            return true
        }
        return false
    }

    private fun beginSlide(item: Labelled) {
        slideItem = item
        slideBefore = item.snapshotGeometry()
        mode = CanvasPointerMode.SLIDE
        setInteractive(false, false)
        onSelectionChanged()
    }

    private fun extendSlide(vx: Double, vy: Double) {
        val item = slideItem ?: return
        slideBefore?.let { item.restoreGeometry(it) }
        item.slideLabel(viewport.viewportToContent(Pt(vx, vy)))
        selection()?.itemChanged(item)
        onSelectionChanged()
        requestRender()
    }

    private fun endSlide() {
        val item = slideItem ?: return
        val before = slideBefore
        slideItem = null
        slideBefore = null
        if (before != null && item.snapshotGeometry() != before) onCommitSelection(selection()?.commandFor(listOf(item), listOf(before)))
        selection()?.itemChanged(item)
    }

    private fun abandonSlide() {
        val item = slideItem ?: return
        slideBefore?.let { item.restoreGeometry(it) }
        selection()?.itemChanged(item)
        slideItem = null
        slideBefore = null
    }

    private fun beginSelect(vx: Double, vy: Double) {
        val sel = selection() ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        setInteractive(false, false)
        // A press on a handle or the grip transforms; inside the box it moves; anywhere else
        // starts a fresh band, which is what makes an empty patch of canvas deselect.
        if (tryGrabSelection(sel, at)) return
        // A press that lands on an item picks that item up, as it does on a note. A band is what
        // the empty canvas between items starts, not what selecting anything at all takes. A locked
        // item is not there as far as this is concerned, so a band can be started over one.
        val hit = grabbableAt(at)
        if (hit != null) {
            if (sel.items.none { it === hit }) sel.select(listOf(hit))
            beginMoveAt(sel, at)
            onSelectionChanged()
            return
        }
        sel.clear()
        bandAnchor = at
        bandRect = Rect(at.x, at.y, 0.0, 0.0)
        mode = CanvasPointerMode.BAND
        onSelectionChanged()
    }

    /** Start dragging the selection from [at], the tail of every press that grabs one. */
    private fun beginMoveAt(sel: CanvasSelection, at: Pt) {
        // A lone connector dragged by its line lets go of what it joined; anything else keeps it.
        sel.beginTransform(rigid = true, pin = sel.loneConnector == null)
        moveAnchor = at
        movedBy = Pt.ZERO
        mode = CanvasPointerMode.MOVE
        onLiftSelection(liftedOf(sel), LiftTransform.NONE)
    }

    /** What a move hands the renderer to offset: everything that moves whole, not what bends to stay joined. */
    private fun liftedOf(sel: CanvasSelection): List<CanvasItem> = sel.items.filter { !sel.follow.places(it) }

    /**
     * The topmost item under [content], or null. The index is queried with a slop-sized rect, but
     * whether the press really landed on something is the item's own answer.
     */
    private fun itemAt(content: Pt): CanvasItem? {
        val pad = TAP_SLOP_PX / viewport.zoom
        val near = Rect(content.x - pad, content.y - pad, pad * 2, pad * 2)
        // Last, not first: the index comes back in z-order, so the topmost item is the one hit.
        return itemsIn(near).lastOrNull { it.contains(content) }
    }

    /** [itemAt] but skipping anything pinned, which is what a tap or a drag may actually grab. */
    private fun grabbableAt(content: Pt): CanvasItem? = itemAt(content)?.takeUnless { it.locked }

    private fun extendBand(vx: Double, vy: Double) {
        val at = viewport.viewportToContent(Pt(vx, vy))
        bandRect = Rect.fromPoints(bandAnchor, at)
        onSelectionChanged()
        requestRender()
    }

    private fun endBand() {
        val sel = selection()
        val rect = bandRect
        bandRect = null
        if (sel != null && rect != null && (rect.w > 1e-6 || rect.h > 1e-6)) {
            sel.select(SelectionMath.bandMembers(itemsIn(rect), rect))
        }
        onSelectionChanged()
        requestRender()
    }

    private fun beginLasso(vx: Double, vy: Double) {
        val sel = selection() ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        setInteractive(false, false)
        // Grabbing the settled selection comes first, as it does for the select tool: a pen press
        // inside it moves it instead of throwing it away to draw another lasso.
        if (tryGrabSelection(sel, at)) return
        sel.clear()
        lassoPoints.clear()
        lassoPoints.add(at)
        mode = CanvasPointerMode.LASSO
        onSelectionChanged()
    }

    private fun extendLasso(vx: Double, vy: Double) {
        val at = viewport.viewportToContent(Pt(vx, vy))
        val last = lassoPoints.lastOrNull()
        // Decimate by on-screen spacing, so a slow drag does not pile up thousands of vertices.
        if (last == null || last.distanceTo(at) * viewport.zoom >= LASSO_MIN_STEP_PX) lassoPoints.add(at)
        onSelectionChanged()
        requestRender()
    }

    private fun endLasso() {
        val sel = selection()
        if (sel != null && lassoPoints.size >= 3) {
            val bounds = Rect.bounding(lassoPoints)
            sel.select(SelectionMath.lassoMembers(itemsIn(bounds), lassoPoints.toList()))
        }
        lassoPoints.clear()
        onSelectionChanged()
        requestRender()
    }

    /**
     * A drag in progress. The model is left exactly where it was and the renderer is told to draw
     * the selection offset, so a drag costs one uniform however much is selected. The box follows
     * so the chrome tracks the finger.
     */
    private fun extendMove(vx: Double, vy: Double) {
        val sel = selection() ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        movedBy = Pt(at.x - moveAnchor.x, at.y - moveAnchor.y)
        sel.previewMove(movedBy.x, movedBy.y)
        val shift = Affine.translateBy(movedBy.x, movedBy.y)
        sel.followShown(shift, shift)
        onLiftSelection(liftedOf(sel), LiftTransform.shift(movedBy.x, movedBy.y))
        onSelectionChanged()
        requestRender()
    }

    /** Finger up: apply the whole move to the model once, then hand the drawing back to it. */
    private fun endMove() {
        val sel = selection() ?: return
        sel.moveLive(movedBy.x, movedBy.y)
        if (movedBy != Pt.ZERO) sel.loneConnector?.letGo()
        onLiftSelection(emptyList(), LiftTransform.NONE)
        onCommitSelection(sel.buildCommand(movedOnly = true, dx = movedBy.x, dy = movedBy.y))
        onSelectionChanged()
        requestRender()
    }

    /**
     * Arm a handle or grip drag to be drawn by the renderer rather than baked by the model, when
     * everything selected maps faithfully. Ink and shapes do: their geometry is mapped through the
     * transform, which is exactly what the shader does. A placed SVG does too, since it is drawn
     * from triangles rather than from a texture, and it has the most to gain: baking would re-mesh
     * the whole drawing on every pointer sample. A photo or a text box is rebuilt instead of mapped,
     * so those keep the slow path and stay honest. A resize bakes the shapes alone, see [bakedInLift].
     */
    private fun beginLiftedTransform(sel: CanvasSelection, at: Pt, resize: Boolean) {
        transformPointer = at
        liftedTransform = sel.items.all { it is Stroke || it is ShapeItem || isVectorImage(it) }
        if (!liftedTransform) return
        bakedInLift = if (resize) sel.items.filterTo(HashSet()) { it is ShapeItem } else emptySet()
        liftItems = if (bakedInLift.isEmpty()) sel.items else sel.items.filter { it !in bakedInLift }
        onLiftSelection(liftItems, LiftTransform.NONE)
    }

    private fun endLift() {
        liftedTransform = false
        bakedInLift = emptySet()
        liftItems = emptyList()
    }

    private fun isVectorImage(item: CanvasItem): Boolean =
        item is ImageItem && com.xnotes.platform.ImageDecoder.isVector(item.image.file.path)

    /**
     * A resize in progress. The model is left alone and the renderer is handed the map, so a handle
     * drag costs a few uniforms however much is selected.
     */
    private fun extendResize(vx: Double, vy: Double) {
        val sel = selection() ?: return
        val handle = grabHandle ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        transformPointer = at
        if (liftedTransform) {
            val map = sel.previewResize(handle, at, transformMargin) ?: return
            if (bakedInLift.isNotEmpty()) sel.applyLiveTo(bakedInLift, map)
            onLiftSelection(liftItems, LiftTransform.of(map, sel.transformPivot ?: Pt.ZERO))
        } else {
            sel.resizeLive(handle, at, transformMargin)
        }
        onSelectionChanged()
        requestRender()
    }

    /** A turn in progress, drawn the same way a resize is. */
    private fun extendRotate(vx: Double, vy: Double) {
        val sel = selection() ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        transformPointer = at
        if (liftedTransform) {
            val swept = sel.previewRotate(at)
            val turn = Affine.rotateAbout(sel.transformPivot ?: Pt.ZERO, swept)
            sel.followShown(turn, turn)
            onLiftSelection(liftItems, LiftTransform.turn(sel.transformPivot ?: Pt.ZERO, swept))
        } else {
            sel.rotateLive(at)
        }
        onSelectionChanged()
        requestRender()
    }

    private fun endTransform() {
        val sel = selection() ?: return
        val handle = grabHandle
        val wasResize = handle != null
        grabHandle = null
        // Finger up on a lifted drag: apply the whole thing to the model once, then hand the drawing
        // back to it.
        if (liftedTransform) {
            if (handle != null) sel.resizeLive(handle, transformPointer, transformMargin)
            else sel.rotateLive(transformPointer)
            onLiftSelection(emptyList(), LiftTransform.NONE)
            endLift()
        }
        onCommitSelection(sel.buildCommand(movedOnly = false))
        // A resize of an upright box snaps onto the real bounds, which is what keeps the chrome
        // honest around a text box that refused to shrink past its own text. A turned box cannot be
        // re-derived at all: item bounds are axis aligned, so measuring one and then tilting the
        // result grew the box by its own rotation and made every release jump. A lone shape is the
        // exception, since it carries its own frame.
        val upright = sel.box?.let { kotlin.math.abs(it.angle) < 1e-9 } == true
        if ((wasResize && upright) || sel.isFramedShape) sel.refreshBox()
        onSelectionChanged()
        requestRender()
    }

    // --- point editing ---

    /**
     * A press on one of a lone shape's dots: a vertex dot starts a drag of that point, an edge dot
     * splits its edge and drags the new point. Dots sit inside the box, so they are tried first.
     */
    private fun tryGrabDot(sel: CanvasSelection, at: Pt): Boolean {
        val shape = sel.items.singleOrNull() as? ShapeItem ?: return false
        val reach = OverlayTessellator.DOT_TOUCH_PX / viewport.zoom
        // Knobs first: one can sit right on a vertex, and the knob is the finer control.
        val knob = shape.knobDots().withIndex().minByOrNull { it.value.distanceTo(at) }?.takeIf { it.value.distanceTo(at) <= reach }
        if (knob != null) {
            sel.beginTransform()
            vertexBefore = shape.snapshotGeometry()
            vertexBase = vertexBefore
            vertexShape = shape
            vertexSpot = null
            knobIndex = knob.index
            vertexDownAt = at
            vertexMoved = false
            vertexWasEdge = false
            mode = CanvasPointerMode.VERTEX
            onSelectionChanged()
            requestRender()
            return true
        }
        val vertex = shape.vertexDots().minByOrNull { it.at.distanceTo(at) }?.takeIf { it.at.distanceTo(at) <= reach }
        val edge = if (vertex != null) null else {
            shape.edgeDots(OverlayTessellator.EDGE_DOT_MIN_PX / viewport.zoom)
                .minByOrNull { it.at.distanceTo(at) }?.takeIf { it.at.distanceTo(at) <= reach }
        }
        val dot = vertex ?: edge ?: return false
        sel.beginTransform()
        vertexBefore = shape.snapshotGeometry()
        shape.toFree()
        vertexSpot = if (edge != null) shape.splitEdge(dot.spot) ?: dot.spot else dot.spot
        vertexBase = shape.snapshotGeometry()
        vertexShape = shape
        vertexDownAt = at
        vertexMoved = false
        vertexWasEdge = edge != null
        sel.itemChanged(shape)
        mode = CanvasPointerMode.VERTEX
        onSelectionChanged()
        requestRender()
        return true
    }

    private fun extendVertex(vx: Double, vy: Double) {
        val shape = vertexShape ?: return
        val base = vertexBase ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        // A dot is small: a press that wobbles a pixel or two is still a tap, not an edit.
        if (!vertexMoved && (at.distanceTo(vertexDownAt) * viewport.zoom) < InteractionController.LONG_PRESS_SLOP) return
        vertexMoved = true
        shape.restoreGeometry(base)
        if (knobIndex >= 0) shape.dragKnob(knobIndex, at) else shape.moveVertex(vertexSpot ?: return, at)
        selection()?.itemChanged(shape)
        selection()?.followEdit()
        onSelectionChanged()
        requestRender()
    }

    /** Finger up on a point drag: an edit is one undo step; a tap changes nothing, and a second one deletes. */
    private fun endVertex(eventTime: Long) {
        val shape = vertexShape ?: return
        val sel = selection()
        val spot = vertexSpot
        if (vertexMoved) {
            onCommitSelection(sel?.buildCommand(movedOnly = false))
            lastVertexTap = null
        } else {
            vertexBefore?.let { shape.restoreGeometry(it) }
            if (knobIndex < 0 && !vertexWasEdge && spot != null && spot == lastVertexTap &&
                eventTime - lastVertexTapAt <= InteractionController.DOUBLE_TAP_MS
            ) {
                lastVertexTap = null
                shape.toFree()
                if (shape.deleteVertex(spot)) {
                    sel?.followEdit()
                    onCommitSelection(sel?.buildCommand(movedOnly = false))
                } else {
                    vertexBefore?.let { shape.restoreGeometry(it) }
                }
            } else {
                lastVertexTap = if (vertexWasEdge) null else spot
                lastVertexTapAt = eventTime
            }
        }
        sel?.itemChanged(shape)
        clearVertex()
    }

    private fun abandonVertex() {
        val shape = vertexShape ?: return
        vertexBefore?.let { shape.restoreGeometry(it) }
        selection()?.itemChanged(shape)
        selection()?.unfollow()
        clearVertex()
        onSelectionChanged()
    }

    private fun clearVertex() {
        vertexShape = null
        vertexSpot = null
        vertexBefore = null
        vertexBase = null
        vertexMoved = false
        knobIndex = -1
    }

    // --- a lone connector's own handles ---

    private fun beginJoint(edit: ConnectorEdit, at: Pt) {
        jointEdit = edit
        jointFollow = selection()?.followOf(listOf(edit.connector), rigid = false, pin = false) ?: GlueFollow.NONE
        jointDownAt = at
        jointMoved = false
        mode = CanvasPointerMode.JOINT
        setInteractive(false, false)
        onSelectionChanged()
        requestRender()
    }

    private fun extendJoint(vx: Double, vy: Double) {
        val edit = jointEdit ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        // A handle is small: a press that wobbles a pixel or two is still a tap.
        if (!jointMoved && at.distanceTo(jointDownAt) * viewport.zoom < InteractionController.LONG_PRESS_SLOP) return
        jointMoved = true
        val reach = OverlayTessellator.DOT_TOUCH_PX / viewport.zoom
        val margin = routeMargin()
        val c = edit.connector
        val area = Glue.routeArea(c.start.at, c.end.at, margin).union(Glue.routeArea(at, at, margin))
        edit.drag(at, itemsIn(area), reach, margin)
        jointFollow.follow(Affine.IDENTITY)
        connectHot = listOfNotNull(edit.hot?.point?.at)
        selection()?.itemChanged(c)
        jointFollow.items.forEach { selection()?.itemChanged(it) }
        onSelectionChanged()
        requestRender()
    }

    /** Finger up: a drag is one undo step; a tap changes nothing, and a second on a waypoint removes it. */
    private fun endJoint(eventTime: Long) {
        val edit = jointEdit ?: return
        val c = edit.connector
        val removing = !jointMoved && edit.grab == ConnectorEdit.Grab.WAYPOINT && edit.index == lastWaypointTap &&
            eventTime - lastWaypointTapAt <= InteractionController.DOUBLE_TAP_MS
        if (!jointMoved) {
            edit.restore()
            jointFollow.restore()
            if (removing) {
                c.removeWaypoint(edit.index)
                jointFollow.follow(Affine.IDENTITY)
            }
            lastWaypointTap = if (edit.grab == ConnectorEdit.Grab.WAYPOINT && !removing) edit.index else -1
            lastWaypointTapAt = eventTime
        } else {
            lastWaypointTap = -1
        }
        if (jointMoved || removing) {
            val items = listOf<CanvasItem>(c) + jointFollow.items
            val before = listOf(edit.before) + jointFollow.startSnapshots
            onCommitSelection(selection()?.commandFor(items, before))
        }
        selection()?.itemChanged(c)
        jointFollow.items.forEach { selection()?.itemChanged(it) }
        jointEdit = null
        jointFollow = GlueFollow.NONE
        connectHot = emptyList()
        onSelectionChanged()
    }

    private fun abandonJoint() {
        val edit = jointEdit ?: return
        edit.restore()
        jointFollow.restore()
        selection()?.itemChanged(edit.connector)
        jointFollow.items.forEach { selection()?.itemChanged(it) }
        jointEdit = null
        jointFollow = GlueFollow.NONE
        connectHot = emptyList()
        onSelectionChanged()
    }

    /** The lone connector's chrome for the overlay: its ends (glued or not), middle segments, waypoints and stretch middles. */
    fun connectorChrome(): ConnectorChrome? {
        val sel = selection() ?: return null
        val c = sel.loneConnector ?: return null
        if (mode == CanvasPointerMode.MOVE) return null
        return ConnectorChrome(
            listOf(c.start.at to (c.start.target?.let(sel::holds) == true), c.end.at to (c.end.target?.let(sel::holds) == true)),
            c.segmentHandles(),
            c.waypoints,
            c.midpointDots(),
        )
    }

    // --- free polygon ---

    private var polygonDraft: com.xnotes.core.shapes.PolygonDraft? = null

    /** A press with the Free polygon chip: the first starts a draft, each one puts down a corner on release. */
    private fun beginPolygonTap(vx: Double, vy: Double) {
        cancelLongPress()
        if (polygonDraft == null) polygonDraft = com.xnotes.core.shapes.PolygonDraft()
        mode = CanvasPointerMode.POLYGON
        showPolygonDraft(viewport.viewportToContent(Pt(vx, vy)))
    }

    /** Show the draft as a line through its corners, with a band out to [at] while pressed. */
    private fun showPolygonDraft(at: Pt?) {
        val draft = polygonDraft ?: return
        val cfg = shapeConfig()
        val pts = draft.preview(at)
        pendingShape = if (pts.size >= 2) {
            cfg.copy(fill = false).placedShape(pts, false, inkColor(), cfg.strokeWidth * InteractionController.SHAPE_PEN_PARITY)
        } else {
            null
        }
        onPendingShape(pendingShape)
        requestRender()
    }

    private fun endPolygonTap(at: Pt, eventTime: Long) {
        val draft = polygonDraft ?: return
        val reach = OverlayTessellator.DOT_TOUCH_PX / viewport.zoom
        when (draft.tap(at, eventTime, reach, InteractionController.DOUBLE_TAP_MS)) {
            com.xnotes.core.shapes.PolygonDraft.Outcome.CONTINUE -> showPolygonDraft(null)
            com.xnotes.core.shapes.PolygonDraft.Outcome.CLOSED -> commitPolygon(closed = true)
            com.xnotes.core.shapes.PolygonDraft.Outcome.OPEN -> commitPolygon(closed = false)
        }
    }

    private fun commitPolygon(closed: Boolean) {
        val draft = polygonDraft ?: return
        polygonDraft = null
        pendingShape = null
        onPendingShape(null)
        if (draft.points.size >= (if (closed) 3 else 2)) {
            val cfg = shapeConfig()
            val shape = cfg.placedShape(draft.points, closed, inkColor(), cfg.strokeWidth * InteractionController.SHAPE_PEN_PARITY)
            onCommitShape(shape)
            selection()?.select(listOf(shape))
            onSelectionChanged()
        }
        requestRender()
    }

    /** End a draft something else interrupts: two corners or more stay, as an open line. */
    fun finishPolygonDraft() {
        if (polygonDraft != null) commitPolygon(closed = false)
    }

    // --- shapes ---

    /** A Shape tool press while something is selected only puts it away; the next press draws. */
    private fun dismissFirst(at: Pt) {
        clearSelection()
        mode = CanvasPointerMode.IDLE
        // Free polygon taps place corners, so only the other chips pick up a shape on a tap.
        if (!shapeConfig().placesCorners) dismissPressAt = at
    }

    private fun beginShape(vx: Double, vy: Double) {
        val cfg = shapeConfig()
        val at = viewport.viewportToContent(Pt(vx, vy))
        pendingShape = cfg.startShape(at, inkColor(), cfg.strokeWidth * InteractionController.SHAPE_PEN_PARITY)
        mode = CanvasPointerMode.SHAPE
        setInteractive(false, false)
        onPendingShape(pendingShape)
        requestRender()
    }

    private fun extendShape(vx: Double, vy: Double) {
        val shape = pendingShape ?: return
        val cfg = shapeConfig()
        shape.end = ResizeMath.drawnEnd(shape, viewport.viewportToContent(Pt(vx, vy)), cfg.perfect)
        cfg.followDrag(shape)
        onPendingShape(shape)
        requestRender()
    }

    private fun endShape() {
        val shape = pendingShape
        pendingShape = null
        onPendingShape(null)
        // A tap makes no shape; only a real drag commits one. A new shape stays selected, so it can
        // be resized, turned or reshaped straight away; a tap picks up the shape under it instead.
        if (shape != null && shape.start.distanceTo(shape.end) > InteractionController.SHAPE_MIN_DRAG) {
            onCommitShape(shape)
            selection()?.select(listOf(shape))
            onSelectionChanged()
        } else if (shape != null) {
            shapeTap(shape.start)
        }
        requestRender()
    }

    /** A tap with the shape tool peels a tape lying on top there, or else picks up the shape under it. */
    private fun shapeTap(at: Pt) {
        if (tapeOnTopAt(at)) peelTapeAt(at) else selectShapeAt(at)
    }

    // --- tape ---

    private fun beginTape(vx: Double, vy: Double) {
        val at = viewport.viewportToContent(Pt(vx, vy))
        tapeStart = at
        tapeReach = 0.0
        tapePath.clear()
        tapePath.add(at)
        pendingTape = null
        mode = CanvasPointerMode.TAPE
        setInteractive(false, false)
    }

    private fun extendTape(vx: Double, vy: Double) {
        val start = tapeStart ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        tapeReach = maxOf(tapeReach, start.distanceTo(at))
        val cfg = configFor(Tool.TAPE)
        val width = if (cfg.scale) cfg.baseWidth else cfg.baseWidth / viewport.zoom
        if (cfg.tapeMode == TapeMode.FREEHAND && at.distanceTo(tapePath.last()) * viewport.zoom >= InteractionController.TAPE_STEP) tapePath.add(at)
        val shape = TapeGeometry.laid(cfg.tapeMode, start, at, tapePath.toList(), width)
        val tape = pendingTape ?: TapeItem(shape, cfg.rgba, cfg.tapePattern).also { pendingTape = it }
        tape.shape = shape
        onPendingShape(tape)
        requestRender()
    }

    private fun endTape() {
        val tape = pendingTape
        val start = tapeStart
        abandonTape()
        // Only a real drag lays tape; a press that stayed within a tap's slop was a tap.
        if (tape != null && tapeReach * viewport.zoom > TAP_SLOP_PX) {
            if (tape.mode == TapeMode.FREEHAND) {
                val eps = (TapeItem.FREEHAND_TOLERANCE / viewport.zoom).coerceAtMost(TapeItem.FREEHAND_TOLERANCE)
                tape.shape = tape.shape.copy(points = TapeGeometry.simplify(tape.shape.points, eps))
            }
            onCommitShape(tape)
        } else if (start != null) {
            peelTapeAt(start)
        }
        requestRender()
    }

    private fun abandonTape() {
        pendingTape = null
        tapeStart = null
        tapePath.clear()
        onPendingShape(null)
    }

    /** The topmost tape at [at], within a fingertip of its edge, or null. */
    private fun tapeAt(at: Pt): TapeItem? {
        val reach = InteractionController.TAPE_TAP_REACH * devicePxPerDp() / viewport.zoom
        return itemsIn(Rect(at.x - reach, at.y - reach, 2 * reach, 2 * reach))
            .lastOrNull { it is TapeItem && it.under(at, reach) } as? TapeItem
    }

    /** Whether the tape at [at] lies over every shape a shape-tool tap there would pick. */
    private fun tapeOnTopAt(at: Pt): Boolean {
        val tape = tapeAt(at) ?: return false
        val reach = InteractionController.OUTLINE_GRAB / viewport.zoom
        val near = itemsIn(Rect(at.x - reach, at.y - reach, 2 * reach, 2 * reach))
        val above = near.subList(near.indexOf(tape) + 1, near.size)
        return above.none { !it.locked && ((it is ShapeItem && it.touches(at, reach)) || (it is ConnectorItem && it.touches(at, reach))) }
    }

    /** Peel back the tape at [at], or cover it again; false when there is none. */
    private fun peelTapeAt(at: Pt): Boolean {
        val tape = tapeAt(at) ?: return false
        onPeelTape(tape)
        return true
    }

    /** Select the topmost shape whose outline or fill is under [at], if any. */
    private fun selectShapeAt(at: Pt) {
        val reach = InteractionController.OUTLINE_GRAB / viewport.zoom
        val near = Rect(at.x - reach, at.y - reach, 2 * reach, 2 * reach)
        val hit = itemsIn(near).lastOrNull {
            !it.locked && ((it is ShapeItem && it.touches(at, reach)) || (it is ConnectorItem && it.touches(at, reach)))
        } ?: return
        selection()?.select(listOf(hit))
        onSelectionChanged()
    }

    private fun abandonShape() {
        pendingShape = null
        onPendingShape(null)
    }

    // --- connectors ---

    /** The glue targets near [at], for a pen within [reach] of their points. */
    private fun glueTargetsNear(at: Pt, reach: Double): List<CanvasItem> =
        itemsIn(Rect(at.x - reach, at.y - reach, 2 * reach, 2 * reach))

    /** The connection point within reach of [at], if a connector would glue there. */
    private fun glueHitAt(at: Pt): Glue.Hit? {
        val reach = OverlayTessellator.DOT_TOUCH_PX / viewport.zoom
        return Glue.nearest(glueTargetsNear(at, reach), at, reach)
    }

    /** How far a route keeps clear of what it goes round: a fixed distance on screen. */
    private fun routeMargin(): Double = ROUTE_MARGIN_DP * devicePxPerDp() / viewport.zoom

    /**
     * Every connection point on screen, for the chrome to mark while a connector is armed. A canvas
     * so dense that it would mark more than [MAX_MARKS] marks none.
     */
    fun connectionMarks(): List<Pt> {
        if (!marking) return emptyList()
        val out = ArrayList<Pt>()
        val drawing = connectorDraft?.connector ?: jointEdit?.connector
        for (item in itemsIn(viewport.visibleContentRect())) {
            if (item === drawing) continue
            for (cp in Glue.points(item)) out += cp.at
            if (out.size > MAX_MARKS) return emptyList()
        }
        return out
    }

    /** A hovering pen lights the connection point under it while a connector is armed. */
    fun onHover(e: MotionEvent): Boolean {
        val hovering = connecting && mode == CanvasPointerMode.IDLE && e.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        val hot = if (hovering) listOfNotNull(glueHitAt(viewport.viewportToContent(Pt(e.x.toDouble(), e.y.toDouble())))?.point?.at) else emptyList()
        if (hot != connectHot) {
            connectHot = hot
            onSelectionChanged()
        }
        return hovering
    }

    /** A press with a connector chip: the connector sets off, glued to the point under the pen if there is one. */
    private fun beginConnector(vx: Double, vy: Double) {
        val cfg = shapeConfig()
        val at = viewport.viewportToContent(Pt(vx, vy))
        val reach = OverlayTessellator.DOT_TOUCH_PX / viewport.zoom
        val width = cfg.strokeWidth * InteractionController.SHAPE_PEN_PARITY
        val draft = ConnectorDraft.startAt(at, glueTargetsNear(at, reach), reach) { cfg.startConnector(it, inkColor(), width) } ?: return
        connectorDraft = draft
        mode = CanvasPointerMode.CONNECT
        setInteractive(false, false)
        connectHot = listOfNotNull(draft.connector.start.takeIf { it.glued }?.at)
        onPendingShape(draft.connector)
        requestRender()
    }

    /** The far end follows the pen, gluing near a point, and the route is planned afresh round what is in the way. */
    private fun extendConnector(vx: Double, vy: Double) {
        val draft = connectorDraft ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        val reach = OverlayTessellator.DOT_TOUCH_PX / viewport.zoom
        val margin = routeMargin()
        val area = Glue.routeArea(draft.connector.start.at, at, margin).union(Rect(at.x - reach, at.y - reach, 2 * reach, 2 * reach))
        draft.drag(at, itemsIn(area), reach, margin)
        connectHot = listOfNotNull(draft.connector.start.takeIf { it.glued }?.at, draft.hot?.point?.at)
        onPendingShape(draft.connector)
        requestRender()
    }

    /** Pen up: a connector worth keeping goes in and stays selected; a tap picks up what is under it. */
    private fun endConnector() {
        val draft = connectorDraft ?: return
        connectorDraft = null
        connectHot = emptyList()
        onPendingShape(null)
        if (draft.worthKeeping(InteractionController.SHAPE_MIN_DRAG)) {
            onCommitShape(draft.connector)
            selection()?.select(listOf(draft.connector))
        } else {
            selectShapeAt(draft.connector.start.at)
        }
        onSelectionChanged()
        requestRender()
    }

    private fun abandonConnector() {
        connectorDraft = null
        connectHot = emptyList()
        onPendingShape(null)
        onSelectionChanged()
    }

    // --- hold still to snap a freehand stroke into a shape ---

    private fun armDwell(at: Pt) {
        cancelDwell()
        dwellAnchor = at
        val r = Runnable { onDwellElapsed() }
        dwellRunnable = r
        handler.postDelayed(r, InteractionController.SHAPE_DWELL_MS)
    }

    private fun cancelDwell() {
        dwellRunnable?.let { handler.removeCallbacks(it) }
        dwellRunnable = null
    }

    /** The pen has held still: if what it drew reads as a shape, swap the stroke for that shape. */
    private fun onDwellElapsed() {
        dwellRunnable = null
        if (!dwellEligible) return
        val stroke = liveStroke ?: return
        if (stroke.samples.size < InteractionController.SHAPE_MIN_SAMPLES) return
        val rec = ShapeRecognizer.recognize(stroke.samples) ?: return
        val width = stroke.config.baseWidth * InteractionController.SHAPE_PEN_PARITY
        val color = stroke.config.rgba // the as-drawn colour, not the alpha-scaled render one
        val dashed = stroke.tool == Tool.DASHED // a dashed pen snaps to a dashed shape
        val shape = rec.toShape(
            color, width, shapeConfig().fillFor(rec.kind, color), stroke.config.neon, stroke.config.neonStrength,
            dashed, stroke.config.dashLength, stroke.config.dashGap,
        )
        // The stroke was never committed, so dropping it makes the wet ink vanish the moment it
        // snaps; the eventual pen up then commits nothing.
        liveStroke = null
        onWetStroke(null)
        dwellEligible = false
        cancelDwell()
        onCommitShape(shape)
        mode = CanvasPointerMode.IDLE
        // Leave the new shape selected, as a note does, so it can be resized or turned straight
        // away. The chrome only shows over a settled selection, so the handles arrive at pen up.
        selection()?.select(listOf(shape))
        onSelectionChanged()
        requestRender()
    }

    // --- erasing ---

    /** Eraser radius in content pixels: the tool's width, or a constant on-screen size when off. */
    fun eraserRadius(): Double {
        val cfg = configFor(Tool.ERASER)
        return if (cfg.scale) cfg.baseWidth else cfg.baseWidth / viewport.zoom
    }

    private fun areaErase(): Boolean = configFor(Tool.ERASER).eraseMode == EraseMode.AREA

    private fun beginErase(vx: Double, vy: Double) {
        eraseSession = onEraseBegin() ?: return
        mode = CanvasPointerMode.ERASE
        eraseAt(vx, vy)
    }

    private fun extendErase(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId)
        if (idx < 0) return
        // The historical points matter here as much as when drawing: a fast sweep that only sampled
        // the newest event would skip over strokes between one frame and the next.
        for (h in 0 until e.historySize) {
            eraseAt(e.getHistoricalX(idx, h).toDouble(), e.getHistoricalY(idx, h).toDouble())
        }
        eraseAt(e.getX(idx).toDouble(), e.getY(idx).toDouble())
    }

    private fun eraseAt(vx: Double, vy: Double) {
        val session = eraseSession ?: return
        onEraserCursor(Pt(vx, vy), eraserRadius() * viewport.zoom)
        val content = viewport.viewportToContent(Pt(vx, vy))
        session.erase(content.x, content.y, eraserRadius(), areaErase())
        requestRender()
    }

    private fun endErase() {
        val session = eraseSession ?: return
        eraseSession = null
        onEraserCursor(null, 0.0)
        onEraseEnd(session)
        requestRender()
    }

    /** Drop the wet stroke without committing it, when a second finger turns the gesture into a zoom. */
    private fun abandonStroke() {
        liveStroke = null
        onWetStroke(null)
    }

    /** Shed the samples the ribbon does not need, at the tolerance the draw zoom justifies. */
    private fun simplifyForCommit(stroke: Stroke) {
        if (StrokeSimplify.enabled && !stroke.straight) {
            val eps = (InteractionController.SIMPLIFY_EPS / viewport.zoom)
                .coerceAtMost(InteractionController.SIMPLIFY_EPS)
            val slim = StrokeSimplify.simplify(
                stroke.samples, stroke.geometry().halfWidths, eps,
                stroke.smoothScale, stroke.config.directionStrength,
            )
            if (slim.size != stroke.sampleCount) {
                stroke.setSamples(slim) // allocates exactly, so no trim needed
                stroke.invalidate()
                return
            }
        }
        // Nothing was dropped, so the stroke still carries the slack capture doubling left behind.
        stroke.trimToSize()
    }

    private fun pressureOf(e: MotionEvent, index: Int): Double =
        if (drawingIsStylus) e.getPressure(index).toDouble() else 1.0

    // --- pan ---

    private fun beginPan(vx: Double, vy: Double, fromPenButton: Boolean = false) {
        mode = CanvasPointerMode.PAN
        panDownAt = Pt(vx, vy)
        panFromPenButton = fromPenButton
        // Moving the view is paced by the display, so the render thread stays up for it.
        setInteractive(true, true)
        startTrackingVelocity(vx, vy)
    }

    /** True when the pan never really moved, so it reads as a tap rather than a scroll. */
    private fun panWasTap(vx: Double, vy: Double): Boolean =
        Pt(vx, vy).distanceTo(panDownAt) <= TAP_SLOP_PX

    private fun extendPan(vx: Double, vy: Double) {
        trackVelocity(vx, vy)
        onUserMovedView()
        if (singleFingerPanAllowed()) viewport.panByViewport(vx - lastPan.x, vy - lastPan.y)
        lastPan = Pt(vx, vy)
        onViewChanged()
        requestRender()
    }

    // Once zoom is locked the zoomLockPan preference decides which pans still move the view; an
    // unlocked view always pans. Mirrors the paged canvas so one preference governs both surfaces.
    private fun singleFingerPanAllowed(): Boolean = !(zoomLocked && zoomLockPan != "single")

    private fun pinchPanAllowed(): Boolean = !(zoomLocked && zoomLockPan == "none")

    // --- pinch ---

    private fun beginPinch(e: MotionEvent) {
        mode = CanvasPointerMode.PINCH
        setInteractive(true, true)
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val mid = (a + b) * 0.5
        pinchInitDist = a.distanceTo(b).coerceAtLeast(1.0)
        pinchInitZoom = viewport.zoom
        pinchAnchorContent = viewport.viewportToContent(mid)
        startTrackingVelocity(mid.x, mid.y)
    }

    private fun updatePinch(e: MotionEvent) {
        if (e.pointerCount < 2) return
        onUserMovedView()
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val dist = a.distanceTo(b)
        if (dist < 1e-3) return
        val mid = (a + b) * 0.5
        trackVelocity(mid.x, mid.y)
        // Zoom about the pinch's own midpoint, and let that midpoint drag the canvas at the same
        // time: the content under the fingers stays under the fingers whether they spread or slide.
        // A locked zoom keeps the zoom it started at, so the pinch is a pan and nothing else.
        viewport.zoom = if (zoomLocked) pinchInitZoom else pinchInitZoom * (dist / pinchInitDist)
        if (pinchPanAllowed()) {
            viewport.scrollX = pinchAnchorContent.x - mid.x / viewport.zoom
            viewport.scrollY = pinchAnchorContent.y - mid.y / viewport.zoom
        }
        lastPan = mid
        onViewChanged()
        requestRender()
    }

    // --- velocity and fling ---

    private fun startTrackingVelocity(vx: Double, vy: Double) {
        stopFling()
        lastPan = Pt(vx, vy)
        lastMoveMs = System.nanoTime() / 1_000_000L
        panVel = Pt.ZERO
    }

    private fun trackVelocity(vx: Double, vy: Double) {
        val now = System.nanoTime() / 1_000_000L
        val dt = ((now - lastMoveMs).coerceAtLeast(1L)) / 1000.0
        val inst = Pt((vx - lastPan.x) / dt, (vy - lastPan.y) / dt)
        val k = InteractionController.VEL_SMOOTH
        panVel = Pt(panVel.x * k + inst.x * (1 - k), panVel.y * k + inst.y * (1 - k))
        lastMoveMs = now
    }

    private fun startFling(fingerVel: Pt) {
        if (fingerVel.length() < InteractionController.FLING_MIN_START) return
        setInteractive(true, true) // the glide is still motion, so the render thread stays up for it
        flingVel = fingerVel
        flinging = true
        lastFlingMs = System.nanoTime() / 1_000_000L
        choreographer.postFrameCallback(flingFrame)
    }

    fun stopFling() {
        flinging = false
    }

    private fun stepFling(frameTimeNanos: Long) {
        if (!flinging) return
        val now = frameTimeNanos / 1_000_000L
        val dt = ((now - lastFlingMs).coerceIn(1L, 40L)) / 1000.0
        lastFlingMs = now
        viewport.panByViewport(flingVel.x * dt, flingVel.y * dt)
        val decay = exp(-InteractionController.FLING_FRICTION * dt)
        flingVel = Pt(flingVel.x * decay, flingVel.y * decay)
        onViewChanged()
        requestRender()
        // Nothing bounds an infinite canvas, so a glide only ever ends by running out of speed.
        if (flingVel.length() < InteractionController.FLING_MIN_STOP) {
            flinging = false
            setInteractive(false, true)
        } else {
            choreographer.postFrameCallback(flingFrame)
        }
    }
}

/** What a lone selected connector shows instead of a box: its ends (glued or not), middle segments, waypoints and stretch middles. */
class ConnectorChrome(val ends: List<Pair<Pt, Boolean>>, val segments: List<Pt>, val waypoints: List<Pt>, val midpoints: List<Pt>)
