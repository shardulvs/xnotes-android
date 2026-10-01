package com.xnotes.ui

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.SplineTapTracker
import com.xnotes.canvas.StylusButtonLatch
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.canvas.HandleId
import com.xnotes.canvas.ResizeMath
import com.xnotes.canvas.SelectionMath
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasSelection
import com.xnotes.core.infinite.EraseSession
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.ShapeRecognizer
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max

/** What the current gesture is doing. */
enum class CanvasPointerMode { IDLE, PAN, PINCH, DRAW, ERASE, SHAPE, BAND, LASSO, MOVE, RESIZE, ROTATE, POINT }

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
    /** Begin an eraser drag; the host owns the document and the undo stack. */
    private val onEraseBegin: () -> EraseSession? = { null },
    /** The eraser drag ended: push its single undo command. */
    private val onEraseEnd: (EraseSession) -> Unit = {},
    /** Where the eraser cursor sits in viewport pixels, and how wide, or null to hide it. */
    private val onEraserCursor: (Pt?, Double) -> Unit = { _, _ -> },
    /** The shape being dragged out, re-tessellated as it grows, or null to clear the preview. */
    private val onPendingShape: (ShapeItem?) -> Unit = {},
    /** A finished shape: add it to the document and push the undo command. */
    private val onCommitShape: (ShapeItem) -> Unit = {},
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
) {

    private val choreographer = Choreographer.getInstance()

    companion object {
        /** How near a handle a press has to land to grab it, in device pixels. */
        const val HANDLE_TOUCH_PX = 33.0

        /** Least on-screen distance between lasso vertices, so a slow drag stays cheap. */
        const val LASSO_MIN_STEP_PX = 3.0

        /** How far a press may wander and still count as a tap, in device pixels. */
        const val TAP_SLOP_PX = 12.0
    }

    /** The armed tool. */
    var tool: Tool = Tool.PEN

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

    // Band and lasso, in content space, live only while their gesture runs.
    var bandRect: Rect? = null
        private set
    val lassoPoints = ArrayList<Pt>()
    private var bandAnchor = Pt.ZERO

    // The live transform drag.
    private var grabHandle: HandleId? = null
    private var grabPoint = -1
    // A quick press on a spline that barely travels is a tap: two quick taps on a point remove it, and
    // two quick taps on the empty space around it add a point there.
    private var pointDownAt = Pt.ZERO
    private var pointTravel = 0.0
    private var splineDownMs = 0L
    private val splineTaps = SplineTapTracker()
    private var moveAnchor = Pt.ZERO
    private var movedBy = Pt.ZERO
    // The press that started this move also selected what it moves.
    private var movePicked = false

    // Where a handle or the rotate grip is being dragged to, and whether the renderer is doing the
    // work. Ink, shapes and placed SVGs map faithfully in the shader, since all three are triangles.
    // A photo or a text box is rebuilt by the model rather than mapped, so a selection holding one is
    // transformed the slow way and stays honest.
    private var transformPointer = Pt.ZERO
    private var liftedTransform = false

    // A finger press landed off a live selection and became a pan; a tap there dismisses it, a
    // real drag pans and leaves it alone.
    private var panMayDismiss = false
    private var panDownAt = Pt.ZERO

    // A finger held still grabs the item under it, or opens the paste menu on empty canvas.
    private var longPressRunnable: Runnable? = null
    private var longPressAt = Pt.ZERO
    private var longPressCandidate: CanvasItem? = null
    private var longPressLocked: CanvasItem? = null

    // The tool a long-press grab borrowed the canvas from, given back when the selection goes.
    private var longPressPrevTool: Tool? = null

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

    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(e)
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(e)
            MotionEvent.ACTION_UP -> handleUp(e)
            MotionEvent.ACTION_CANCEL -> abortGesture()
        }
        return true
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
        panVel = Pt.ZERO
        liveStroke = null
        liftedTransform = false
        onWetStroke(null)
        onLiftSelection(emptyList(), LiftTransform.NONE)
        stylusButtons.reset()
    }

    // --- pointer handling ---

    private fun handleDown(e: MotionEvent) {
        stopFling() // a new touch halts any in-progress glide
        // The minimap sits over the canvas, so a press on it navigates rather than draws.
        if (onMinimapPress(e.getX(0).toDouble(), e.getY(0).toDouble())) {
            mode = CanvasPointerMode.IDLE
            return
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
        val onSelection = hitsSelection(viewport.viewportToContent(Pt(vx, vy)))
        val effective: Tool = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            buttonHeld && penButtonTool != null -> penButtonTool!!
            // While something is selected, a press on it grabs it rather than inking through it,
            // and a finger may grab it even with finger-draw off. Both are the paged canvas's
            // rules; without them a selection could only be handled by a stylus.
            onSelection && tool.isStroke && toolType != MotionEvent.TOOL_TYPE_FINGER -> Tool.SELECT
            onSelection && tool == Tool.SHAPE -> Tool.SELECT
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff &&
                onSelection -> Tool.SELECT
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff -> Tool.PAN
            else -> tool
        }
        // A press that lands off the selection with anything but the selection tools dismisses it,
        // which is what makes an empty tap put the chrome away.
        panMayDismiss = false
        if (!onSelection && effective != Tool.SELECT && effective != Tool.LASSO) {
            if (effective == Tool.PAN) panMayDismiss = hasSelection() else clearSelection()
        }

        armLongPress(Pt(vx, vy), onSelection, toolType == MotionEvent.TOOL_TYPE_FINGER)

        when {
            effective.isStroke -> beginDraw(vx, vy, effective, e)
            effective == Tool.ERASER -> beginErase(vx, vy)
            effective == Tool.SHAPE -> beginShape(vx, vy)
            effective == Tool.SELECT -> beginSelect(vx, vy)
            effective == Tool.LASSO -> beginLasso(vx, vy)
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
        // The gesture underway is only ever a pan or a stroke that has not moved; drop it so what
        // follows is not fighting a drag, and stop the press from also dismissing the selection.
        if (mode == CanvasPointerMode.DRAW) abandonStroke()
        if (mode == CanvasPointerMode.SHAPE) abandonShape()
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
        if (e.pointerCount >= 2) {
            if (mode == CanvasPointerMode.DRAW) abandonStroke()
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
            CanvasPointerMode.BAND -> extendBand(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.LASSO -> extendLasso(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.MOVE -> extendMove(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.RESIZE -> extendResize(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.ROTATE -> extendRotate(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.POINT -> extendPoint(e.getX(0).toDouble(), e.getY(0).toDouble())
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
        // A finger tap off the selection puts it away; a tap is the only way to say so with a
        // finger, since a drag there is a pan.
        if (mode == CanvasPointerMode.PAN && panWasTap(e.getX(0).toDouble(), e.getY(0).toDouble())) {
            val picked = tool == Tool.SELECT && tapSelect(viewport.viewportToContent(Pt(e.getX(0).toDouble(), e.getY(0).toDouble())))
            if (!picked && panMayDismiss) clearSelection()
        }
        panMayDismiss = false
        if (mode == CanvasPointerMode.DRAW) endDraw(e)
        if (mode == CanvasPointerMode.ERASE) endErase()
        if (mode == CanvasPointerMode.SHAPE) endShape()
        when (mode) {
            CanvasPointerMode.BAND -> endBand()
            CanvasPointerMode.LASSO -> endLasso()
            CanvasPointerMode.MOVE -> endMove()
            CanvasPointerMode.RESIZE, CanvasPointerMode.ROTATE -> endTransform()
            CanvasPointerMode.POINT -> endPoint()
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
        if (mode == CanvasPointerMode.POINT) endPoint()
        // A cancelled drag never happened: the model was never touched, so putting the box back and
        // dropping the lift is the whole undo.
        if (mode == CanvasPointerMode.MOVE) {
            selection()?.previewMove(0.0, 0.0)
            onLiftSelection(emptyList(), LiftTransform.NONE)
            onSelectionChanged()
        }
        if ((mode == CanvasPointerMode.ROTATE || mode == CanvasPointerMode.RESIZE) && liftedTransform) {
            selection()?.previewBack()
            onLiftSelection(emptyList(), LiftTransform.NONE)
            liftedTransform = false
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
        if (stroke == null || stroke.isEmpty) {
            onWetStroke(null)
            return
        }
        // The pen is up: rebuild with lift-time rules on before the stroke is committed. The wet
        // buffer is deliberately not cleared here: the commit releases it in the same step, so no
        // frame can land between the two and blink.
        stroke.finished = true
        simplifyForCommit(stroke)
        onCommitStroke(stroke)
    }

    // --- selection ---

    fun hasSelection(): Boolean = selection()?.isEmpty == false

    /**
     * Whether [at] grabs the settled selection: its rotate grip, one of its handles, or its body.
     * A press that does is routed to the selection whatever tool is armed and whatever is touching,
     * which is what lets a finger drag a selection while finger-draw is off.
     */
    private fun hitsSelection(at: Pt): Boolean {
        val sel = selection() ?: return false
        if (sel.isEmpty) return false
        val tolerance = HANDLE_TOUCH_PX / viewport.zoom
        val grip = sel.rotateGrip(OverlayTessellator.GRIP_ARM_PX / viewport.zoom)
        if (grip != null && at.distanceTo(grip) <= tolerance) return true
        sel.moveGrip(viewport.zoom, OverlayTessellator.GRIP_ARM_PX / viewport.zoom)?.let { if (at.distanceTo(it) <= tolerance) return true }
        if (sel.hitSplinePoint(at, tolerance) >= 0) return true
        if (sel.hitHandle(at, tolerance) != null) return true
        return sel.contains(at)
    }

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
        val tolerance = HANDLE_TOUCH_PX / viewport.zoom
        val grip = sel.rotateGrip(OverlayTessellator.GRIP_ARM_PX / viewport.zoom)
        if (grip != null && at.distanceTo(grip) <= tolerance) {
            sel.beginTransform(at)
            beginLiftedTransform(sel, at)
            mode = CanvasPointerMode.ROTATE
            return true
        }
        val moveGrip = sel.moveGrip(viewport.zoom, OverlayTessellator.GRIP_ARM_PX / viewport.zoom)
        if (moveGrip != null && at.distanceTo(moveGrip) <= tolerance) {
            beginMoveAt(sel, at)
            return true
        }
        val point = sel.hitSplinePoint(at, tolerance)
        if (point >= 0) {
            pointDownAt = at
            pointTravel = 0.0
            splineDownMs = android.os.SystemClock.uptimeMillis()
            grabPoint = point
            sel.beginTransform()
            mode = CanvasPointerMode.POINT
            return true
        }
        val handle = sel.hitHandle(at, tolerance)
        if (handle != null) {
            grabHandle = handle
            sel.beginTransform()
            beginLiftedTransform(sel, at)
            mode = CanvasPointerMode.RESIZE
            return true
        }
        if (sel.contains(at)) {
            beginMoveAt(sel, at)
            return true
        }
        return false
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
            val picked = sel.items.none { it === hit }
            if (picked) sel.select(listOf(hit))
            beginMoveAt(sel, at)
            movePicked = picked
            onSelectionChanged()
            return
        }
        sel.clear()
        bandAnchor = at
        bandRect = Rect(at.x, at.y, 0.0, 0.0)
        mode = CanvasPointerMode.BAND
        onSelectionChanged()
    }

    /** Drag the grabbed spline control point; the model moves live, as a text box resize does. */
    private fun extendPoint(vx: Double, vy: Double) {
        val sel = selection() ?: return
        val at = viewport.viewportToContent(Pt(vx, vy))
        pointTravel = maxOf(pointTravel, at.distanceTo(pointDownAt))
        sel.moveSplinePointLive(grabPoint, at)
        onSelectionChanged()
        requestRender()
    }

    private fun endPoint() {
        val index = grabPoint
        grabPoint = -1
        val sel = selection() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val curve = sel.spline()
        if (curve != null && pointTravel * viewport.zoom <= TAP_SLOP_PX && splineTaps.isTap(splineDownMs, now)) {
            sel.restoreStart()
            if (splineTaps.tapPoint(curve, index, splineDownMs, now)) onCommitSelection(sel.editSpline { it.removeControlPointAt(index) })
        } else {
            onCommitSelection(sel.buildCommand(movedOnly = false))
        }
        onSelectionChanged()
        requestRender()
    }

    /** A finger tap with the select tool picks the item under it; a drag still pans. */
    private fun tapSelect(at: Pt): Boolean {
        val sel = selection() ?: return false
        val slop = TAP_SLOP_PX / viewport.zoom
        val hit = grabbableAt(at)
            ?: itemsIn(Rect(at.x - slop, at.y - slop, slop * 2, slop * 2)).lastOrNull { !it.locked && it.intersectsCircle(at.x, at.y, slop) }
            ?: return false
        sel.select(listOf(hit))
        onSelectionChanged()
        requestRender()
        return true
    }

    /** Start dragging the selection from [at], the tail of every press that grabs one. */
    private fun beginMoveAt(sel: CanvasSelection, at: Pt) {
        sel.beginTransform()
        moveAnchor = at
        movedBy = Pt.ZERO
        movePicked = false
        splineDownMs = android.os.SystemClock.uptimeMillis()
        mode = CanvasPointerMode.MOVE
        onLiftSelection(sel.items, LiftTransform.NONE)
    }

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
            sel.select(SelectionMath.bandMembers(itemsIn(rect), rect, configFor(Tool.SELECT).selectWholeItems))
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
            sel.select(SelectionMath.lassoMembers(itemsIn(bounds), lassoPoints.toList(), configFor(Tool.LASSO).selectWholeItems))
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
        onLiftSelection(sel.items, LiftTransform.shift(movedBy.x, movedBy.y))
        onSelectionChanged()
        requestRender()
    }

    /** Finger up: apply the whole move to the model once, then hand the drawing back to it. */
    private fun endMove() {
        val sel = selection() ?: return
        if (movedBy.length() * viewport.zoom <= TAP_SLOP_PX && splineTapped(sel, moveAnchor)) return
        sel.moveLive(movedBy.x, movedBy.y)
        onLiftSelection(emptyList(), LiftTransform.NONE)
        onCommitSelection(sel.buildCommand(movedOnly = true, dx = movedBy.x, dy = movedBy.y))
        onSelectionChanged()
        requestRender()
    }

    /**
     * A tap inside the selected spline is not a tiny move. The second of two quick taps on one spot
     * adds a point there; the tap that only just selected the curve does not count towards that.
     */
    private fun splineTapped(sel: CanvasSelection, at: Pt): Boolean {
        val curve = sel.spline() ?: return false
        val now = android.os.SystemClock.uptimeMillis()
        if (!splineTaps.isTap(splineDownMs, now)) return false
        sel.previewMove(0.0, 0.0)
        onLiftSelection(emptyList(), LiftTransform.NONE)
        when {
            movePicked -> splineTaps.reset()
            splineTaps.tapEmpty(curve, at, HANDLE_TOUCH_PX / viewport.zoom, splineDownMs, now) ->
                onCommitSelection(sel.editSpline { it.addControlPointAt(at); true })
        }
        onSelectionChanged()
        requestRender()
        return true
    }

    /**
     * Arm a handle or grip drag to be drawn by the renderer rather than baked by the model, when
     * everything selected maps faithfully. Ink and shapes do: their geometry is mapped through the
     * transform, which is exactly what the shader does. A placed SVG does too, since it is drawn
     * from triangles rather than from a texture, and it has the most to gain: baking would re-mesh
     * the whole drawing on every pointer sample. A photo or a text box is rebuilt instead of mapped,
     * so those keep the slow path and stay honest.
     */
    private fun beginLiftedTransform(sel: CanvasSelection, at: Pt) {
        transformPointer = at
        liftedTransform = sel.items.all { it is Stroke || it is ShapeItem || isVectorImage(it) }
        if (liftedTransform) onLiftSelection(sel.items, LiftTransform.NONE)
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
            val map = sel.previewResize(handle, at) ?: return
            onLiftSelection(sel.items, LiftTransform.of(map, sel.transformPivot ?: Pt.ZERO))
        } else {
            sel.resizeLive(handle, at)
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
            onLiftSelection(sel.items, LiftTransform.turn(sel.transformPivot ?: Pt.ZERO, swept))
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
            if (handle != null) sel.resizeLive(handle, transformPointer)
            else sel.rotateLive(transformPointer)
            onLiftSelection(emptyList(), LiftTransform.NONE)
            liftedTransform = false
        }
        onCommitSelection(sel.buildCommand(movedOnly = false))
        // A resize of an upright box snaps onto the real bounds, which is what keeps the chrome
        // honest around a text box that refused to shrink past its own text. A turned box cannot be
        // re-derived at all: item bounds are axis aligned, so measuring one and then tilting the
        // result grew the box by its own rotation and made every release jump.
        val upright = sel.box?.let { kotlin.math.abs(it.angle) < 1e-9 } == true
        if (wasResize && upright) sel.refreshBox()
        onSelectionChanged()
        requestRender()
    }

    // --- shapes ---

    private fun beginShape(vx: Double, vy: Double) {
        val cfg = shapeConfig()
        val at = viewport.viewportToContent(Pt(vx, vy))
        val ink = inkColor()
        val fill = if (cfg.fill && cfg.shape.isClosed) ink.scaleAlpha(cfg.fillAlpha) else null
        pendingShape = ShapeItem(
            cfg.shape, at, at, ink, cfg.strokeWidth * InteractionController.SHAPE_PEN_PARITY, fill,
            cfg.neon, cfg.neonStrength,
            dashed = cfg.dashed, dashLength = cfg.dashLength, dashGap = cfg.dashGap,
            function = if (cfg.shape == ShapeKind.FUNCTION) FunctionSpec.preset(cfg.function) else null,
        )
        shapeAnchor = at
        shapeAspect = pendingShape?.function?.naturalAspect()
        mode = CanvasPointerMode.SHAPE
        setInteractive(false, false)
        onPendingShape(pendingShape)
        requestRender()
    }

    // Where the shape drag began (a circle's centre) and a function's true height/width.
    private var shapeAnchor = Pt.ZERO
    private var shapeAspect: Double? = null

    private fun extendShape(vx: Double, vy: Double) {
        val shape = pendingShape ?: return
        val raw = viewport.viewportToContent(Pt(vx, vy))
        if (shape.shape == ShapeKind.CIRCLE) {
            // A circle grows out from its centre, so the drag ends on the rim.
            val (s, e) = ResizeMath.circleFromCentre(shapeAnchor, raw)
            shape.start = s
            shape.end = e
        } else {
            shape.end = when {
                // Line and arrow pin flat when the dragged end lands near an axis.
                shape.shape.isEndpointShape -> snapAxisEndpoint(shape.start, raw)
                // A function graph keeps equal x and y scales while drawn; its handles stretch it later.
                shape.shape == ShapeKind.FUNCTION -> ResizeMath.aspectCorner(shape.start, raw, shapeAspect)
                else -> raw
            }
        }
        onPendingShape(shape)
        requestRender()
    }

    private fun endShape() {
        val shape = pendingShape
        pendingShape = null
        onPendingShape(null)
        // A tap makes no shape; only a real drag commits one.
        if (shape != null && shape.start.distanceTo(shape.end) > InteractionController.SHAPE_MIN_DRAG) {
            onCommitShape(shape)
            // Left selected so it can be moved or resized straight away, as a snapped shape is.
            selection()?.select(listOf(shape))
            onSelectionChanged()
        }
        requestRender()
    }

    private fun abandonShape() {
        pendingShape = null
        onPendingShape(null)
    }

    /** Snap a line or arrow's dragged end to an exact horizontal or vertical run from [anchor]. */
    private fun snapAxisEndpoint(anchor: Pt, p: Pt): Pt {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        if (dx == 0.0 && dy == 0.0) return p
        val snap = Math.toRadians(InteractionController.SHAPE_AXIS_SNAP_DEG)
        val fromHoriz = atan2(abs(dy), abs(dx)) // 0 is horizontal, PI/2 is vertical
        return when {
            fromHoriz <= snap -> Pt(p.x, anchor.y)
            fromHoriz >= Math.PI / 2.0 - snap -> Pt(anchor.x, p.y)
            else -> p
        }
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
        val verts = rec.vertices
        val shape = if (verts != null) {
            ShapeItem.poly(
                rec.kind, verts, color, width, null, stroke.config.neon, stroke.config.neonStrength,
                dashed, stroke.config.dashLength, stroke.config.dashGap,
            )
        } else {
            ShapeItem(
                shape = rec.kind,
                start = rec.start,
                end = rec.end,
                strokeRgba = color,
                strokeWidth = width,
                fillRgba = null,
                neon = stroke.config.neon,
                neonStrength = stroke.config.neonStrength,
                dashed = dashed,
                dashLength = stroke.config.dashLength,
                dashGap = stroke.config.dashGap,
                function = rec.function,
            )
        }
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
