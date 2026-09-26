package com.xnotes.canvas

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.AddItems
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.EraseItems
import com.xnotes.core.history.History
import com.xnotes.core.history.LockItems
import com.xnotes.core.history.EditText
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.ReorderItems
import com.xnotes.core.history.ReplacePageItems
import com.xnotes.core.history.ResizeItem
import com.xnotes.core.history.RestyleItems
import com.xnotes.core.history.RestyleText
import com.xnotes.core.history.TransferItems
import com.xnotes.core.history.TransformItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.deepCopy
import com.xnotes.core.model.GeoHandle
import com.xnotes.core.history.ReplaceImage
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.RectHandle
import com.xnotes.core.model.Resizable
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.ShapeHandle
import com.xnotes.core.model.GeometrySnapshot
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextHandle
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.stroke.RecognizedShape
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.ShapeRecognizer
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The pointer state machine modes (spec 06 §1). */
enum class PointerMode {
    IDLE, DRAW, ERASE, BAND, LASSO_DRAW, SHOT, SHAPE, MOVE, RESIZE, TRANSFORM, PAN, PINCH, FLOW_TEXT,
    TEXT_DRAG, RULER_MOVE, RULER_TRANSFORM, RULER_ROTATE, TABLE_LINE, TABLE_DRAW, CROP,
}

/** Column/row counts and line width for the next table drawn with the table tool. */
data class TableToolDefaults(val cols: Int, val rows: Int, val width: Double)

/**
 * Geometry of the live text editor field: [x]/[y] place its top-left in viewport pixels, while
 * [width]/[height]/[fontPx] are **content-space** (page pixels, pre-zoom) and [zoom] maps them
 * to the screen. The overlay lays text out at content scale and draws scaled by [zoom] — the
 * same mechanism as the baked painter — so its line wrapping matches the canvas exactly.
 */
data class EditingField(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val fontPx: Double,
    val zoom: Double,
    val face: FontFace,
    val rgba: Rgba,
    val text: String,
    /** The view's page rotation (deg cw); the overlay spins by this around (x, y). */
    val rotation: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val align: com.xnotes.core.pal.HAlign = com.xnotes.core.pal.HAlign.LEFT,
)

/** The floating text style bar's target: the active box's viewport rect + its style. */
data class TextBar(
    val rect: Rect,
    val face: FontFace,
    val pointSize: Double,
    val rgba: Rgba,
    /** True while the keyboard field is up (vs the box merely being selected). */
    val editing: Boolean,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val align: com.xnotes.core.pal.HAlign = com.xnotes.core.pal.HAlign.LEFT,
)

/**
 * Drives editing from pointer input (spec 06): drawing, the object eraser,
 * rubber-band and lasso selection, moving a selection, plus pan/zoom. Resize,
 * shapes, long-press and text are layered on in later commits.
 */
class InteractionController(
    private val state: CanvasState,
    val history: History,
    private val textMeasurer: TextMeasurer,
    private val requestRender: () -> Unit,
    private val onContentChanged: () -> Unit = {},
    private val onViewChanged: () -> Unit = {},
    /** A pinch just snapped the view to fit-to-width (newly): surface the lock hint. */
    private val onFitWidthSnapped: () -> Unit = {},
    /** A pinch broke past the fit-to-width magnet: dismiss the lock hint. */
    private val onFitWidthReleased: () -> Unit = {},
    private val onSelectionChanged: (Boolean) -> Unit = {},
    private val onToolChanged: (Tool) -> Unit = {},
    private val onTextEditStart: (EditingField?) -> Unit = {},
    private val onTextEditEnd: () -> Unit = {},
    /** Selection menu: a viewport rect to show it anchored to, or null to hide. */
    private val onSelectionMenu: (Rect?) -> Unit = {},
    /** Screenshot menu: a viewport rect to anchor the "copy as image" bar to, or null to hide. */
    private val onScreenshotMenu: (Rect?) -> Unit = {},
    /** Crop bar: a viewport rect to anchor the Apply/Cancel crop bar to, or null to hide. */
    private val onCropMenu: (Rect?) -> Unit = {},
    /** Long-press on empty space: open a context menu at (viewport, content). */
    /** Long press on empty space, or on a locked item: the third argument is that item, if any. */
    private val onContextMenu: (Pt, Pt, CanvasItem?) -> Unit = { _, _, _ -> },
    /** Pulled past the document's bottom end far enough and released: append a new page. */
    private val onAddPageAtEnd: () -> Unit = {},
    /** A short haptic tick (e.g. the overscroll pull crossed the add-page threshold). */
    private val onHaptic: () -> Unit = {},
    /** Table edit mode turned on/off (so the host can mirror it into a Compose state for the menu). */
    private val onTableEditingChanged: (Boolean) -> Unit = {},
) {
    /** Whether the system clipboard currently holds an image (provided by the host). */
    var clipboardHasImage: () -> Boolean = { false }

    /** The inline-flow caret controller; TEXT-tool gestures route here (installed by the Editor). */
    var flowText: FlowTextController? = null

    /** Host hook for tap-to-open PDF links. A finger tap landed on page [pageIndex] at [pageLocal]
     *  (page-local content px). Returns true if it hit a known link and was handled, so the tap is
     *  consumed (skipping the selection-dismiss / fling). The host parses link rects lazily off the
     *  main thread, so a tap on a not-yet-parsed page returns false and opens the link a moment later
     *  once it is ready. */
    var onLinkTap: ((pageIndex: Int, pageLocal: Pt) -> Boolean)? = null
    val document: Document get() = state.document

    var tool: Tool = Tool.DEFAULT
        private set

    /** The single immediately-prior Tool (not a stack); null until the first switch. Used by the
     *  tap-gesture toggles. Ruler/wand are never Tools, so they can't land here. Not persisted. */
    var previousTool: Tool? = null
        private set
    var inkColor: Rgba = InkPalette.DEFAULT

    /** Whether a finger draws (true) or pans (false). The stylus always uses the armed tool. */
    var fingerDraws: Boolean = false

    /** Panning allowed while zoom is locked: "single" (default) | "double" | "none". */
    var zoomLockPan: String = "single"

    /** Whether holding a freehand ink stroke still snaps it to a recognized shape (spec: "hold to snap"). */
    var detectShapes: Boolean = false

    /** Whether rotating a selection snaps to the nearest 90 degrees. */
    var snapRotation90: Boolean = false

    /** Tool the stylus side button activates while held, or null to ignore the button. */
    var penButtonTool: Tool? = Tool.ERASER

    /** Side button as last seen on the hover/generic-motion stream or a stylus-button KeyEvent
     *  (Feeder C, for Bluetooth pens that report it only there); read only at touch-down, so a
     *  press after the pen is already down does not activate the mapped tool. */
    private var stylusButtonHeld = false

    /** When true, the side-button tool also runs off the hover stream (no contact needed); eraser/pan only. */
    var penButtonHover: Boolean = false

    /** The side-button tool currently running off the hover stream, or null when no hover gesture is live. */
    private var hoverActionTool: Tool? = null

    private val toolConfigs: MutableMap<Tool, ToolConfig> =
        Tool.entries.associateWith { ToolDefaults.configFor(it) }.toMutableMap()

    private var mode = PointerMode.IDLE

    // DRAW
    private var liveStrokeField: Stroke? = null

    /**
     * The stroke under the pen. Clearing it is how every abort path in here ends, so that is where
     * the front buffer is told to give its pixels back; a stroke that reached [fileStroke] has
     * already handed them over and is not disturbed.
     */
    private var liveStroke: Stroke?
        get() = liveStrokeField
        set(value) {
            liveStrokeField = value
            if (value == null) frontInk?.abandon()
        }
    private var strokePageIndex: Int? = null

    /** Event time of the live stroke's first sample; later samples store `eventTime − this` (the speed pen reads it). */
    private var strokeStartTimeMs = 0L
    private var drawingPointerId = -1
    private var drawingIsStylus = false

    // SHAPE SNAP (hold a freehand stroke still → it becomes a real shape)
    /** Pending "pen held still" timer; non-null only while a stroke is eligible and armed. */
    private var dwellRunnable: Runnable? = null
    /** Viewport px of the last point that re-armed the dwell timer (sub-slop jitter doesn't reset it). */
    private var dwellAnchor = Pt.ZERO
    /** True for the current stroke when snapping is allowed (pref on, an ink pen, not a straight line). */
    private var dwellEligible = false
    /** A mid-stroke snap auto-selected a shape; show its menu once the pen lifts (endDraw). */
    private var snappedSelectionPendingMenu = false
    /** This stroke began by dismissing an active selection; a bare tap then leaves no dot. */
    private var strokeDismissedSelection = false

    /** Segments the current stroke left behind on pages it has already walked off, so the whole
     *  crossing lifts and re-lays as one undo step. Empty for a stroke that stayed on its page. */
    private val crossedSegments = mutableListOf<Command>()
    /** Viewport-px down point of the current stroke, for the tap-vs-drag dismiss test. */
    private var drawDownViewport = Pt.ZERO

    // PAN + inertial fling
    private var lastPan = Pt.ZERO
    private var lastMoveMs = 0L
    private var panVel = Pt.ZERO // smoothed finger velocity, viewport px/s
    private var panDownViewport = Pt.ZERO // where the current pan began (viewport px), for tap-to-dismiss
    private var panFromPenButton = false // a side-button pan parks where the pen lifted: no glide
    private var downStoppedFling = false // this touch landed on a moving glide, so its lift isn't a dismiss tap
    private var panMayCommitText = false // pan begun off an open text box: a tap commits it, a drag scrolls
    // Framework singletons, created lazily on first use (always a gesture on the main thread) so
    // the controller's selection/edit logic stays constructible — and unit-testable — off-device.
    private val choreographer by lazy { Choreographer.getInstance() }
    private var flinging = false
    private var flingVel = Pt.ZERO // scroll-space velocity, viewport px/s
    private var lastFlingMs = 0L
    private val flingFrame = Choreographer.FrameCallback { frameTimeNanos -> stepFling(frameTimeNanos) }

    // ELASTIC OVERSCROLL (pull past the bottom end to add a page)
    /** True once the live stretch has crossed the add-page threshold, so the haptic fires once. */
    private var overscrollArmed = false
    private var overscrollSettling = false
    private var lastOverscrollMs = 0L
    private val overscrollFrame = Choreographer.FrameCallback { frameTimeNanos -> stepOverscrollSettle(frameTimeNanos) }

    // PINCH
    private var pinchInitDist = 1.0
    private var pinchInitZoom = 1.0
    private var pinchAnchorContent = Pt.ZERO

    // RULER (transient screen-space straightedge; no model/undo state)
    val ruler = Ruler()
    private var rulerGrabOffset = Pt.ZERO            // ruler.center − grab point, for 1-finger move
    private var rulerXformStartCentroid = Pt.ZERO    // two-finger transform anchors
    private var rulerXformStartFingerAngle = 0.0
    private var rulerXformStartCenter = Pt.ZERO
    private var rulerXformStartRuler = 0.0
    private var rulerRotateSign = 1.0                // +1 if dragging the +direction handle, −1 the other
    // SNAP (per-sample magnet, live for the current stroke only)
    private var snapEngaged = false
    private var snapTopSide = false                  // which long edge the ink is riding
    private var snapRunStartEdge: Pt? = null         // start of the current engaged run, on the edge (viewport)
    private var snapCurrentEdge: Pt? = null          // current point on the edge (viewport)
    private var snapPenViewport: Pt? = null          // actual (unprojected) pen, for readout placement

    // MAGIC WAND (ephemeral "disappearing ink"; no model/undo/cache/save state)
    private var wandMode = false
    private val fadingStrokes = mutableListOf<FadingStroke>()
    private var fadeAlpha = 1.0                       // shared multiplier, 1 = solid, 0 = gone
    private var fading = false                        // true while the fade loop runs
    private var fadeStartMs = 0L
    private val fadeFrame = Choreographer.FrameCallback { t -> stepFade(t) }
    private var fadeTimerRunnable: Runnable? = null

    private class FadingStroke(val stroke: Stroke, val pageIndex: Int)

    // SELECTION
    private val selection = mutableListOf<Selected>()
    private val lassoPoints = mutableListOf<Pt>()
    private var bandRect: Rect? = null
    private var moveOrigin = Pt.ZERO
    private var moveOffset = Pt.ZERO

    // SCREENSHOT (drag a rectangle; on release it stays frozen and offers "copy as image")
    /** The capture rectangle in content space: live while dragging (mode == SHOT), then frozen
     *  with its menu showing until copied, dismissed, or the tool changes. */
    var screenshotRect: Rect? = null
        private set
    private var screenshotOrigin = Pt.ZERO

    // ERASE
    private val eraseRemovals = mutableListOf<Pair<Page, CanvasItem>>()
    /** AREA mode: each touched page's item list snapshotted on first contact this gesture, so the
     *  whole split-and-trim drag undoes/redoes as one [ReplacePageItems] step. */
    private val eraseSnapshots = linkedMapOf<Page, List<CanvasItem>>()
    private var eraserCursor: Pt? = null // viewport pixels
    /** Tool armed just before the eraser was selected, for the "switch back after erasing" option. */
    private var toolBeforeEraser: Tool? = null
    /** Tool armed just before the select tool, for the "switch back after a selection action" option. */
    private var toolBeforeSelect: Tool? = null
    /** Tool armed just before the screenshot tool, to return to after a capture is copied. */
    private var toolBeforeScreenshot: Tool? = null
    /** Tool armed just before the text tool, to return to once an edit is committed. */
    private var toolBeforeText: Tool? = null
    /** Whether the live erase is finger-driven (vs the stylus eraser tip / side button): a finger
     *  erase yields to a two-finger pinch, a stylus erase ignores incidental finger/palm contact. */
    private var erasingWithFinger = false

    // ITEM CLIPBOARD (in-app, for copy/cut/paste/duplicate)
    private val itemClipboard = mutableListOf<CanvasItem>()

    /** Whether the clipboard was filled by a cut. A cut moves the items rather than copying them,
     *  so the first paste puts them back and spends the clipboard; a copy's stays for as long as
     *  the user wants it. */
    private var clipboardFromCut = false
    fun hasClipboardItems(): Boolean = itemClipboard.isNotEmpty()

    // SHAPE
    var shapeConfig: ShapeConfig = ShapeConfig()
    private var pendingShape: ShapeItem? = null
    private var shapePageIndex: Int? = null

    // TABLE (drawn like a shape). The defaults come from the editor's table-tool settings, read live.
    var tableToolDefaults: () -> TableToolDefaults = { TableToolDefaults(3, 3, 2.0) }
    private var pendingTable: TableItem? = null
    private var tableDrawPageIndex: Int? = null
    private var tableDrawStart = Pt.ZERO

    // RESIZE
    private var resizeItem: CanvasItem? = null
    private var resizeHandle: HandleId? = null
    private var resizeOldGeom: GeoHandle? = null
    private var resizePageIndex: Int = -1
    // A spline control point being dragged, and the curve as it was, for the undo step.
    private var resizePointIndex: Int = -1
    private var resizeOldSnap: GeometrySnapshot? = null

    // CROP (adjusting an image's crop rectangle before baking it)
    private var cropItem: ImageItem? = null
    private var cropPageIndex = -1
    private var cropRect: Rect = Rect(0.0, 0.0, 0.0, 0.0) // page-local, within the image's rect
    private var cropHandle: HandleId? = null
    private var cropMoving = false
    private var cropMoveGrab = Pt.ZERO

    // TABLE_LINE (dragging an interior row/column boundary of the selected table)
    private var tableLineItem: TableItem? = null
    private var tableLineBefore: GeometrySnapshot? = null
    private var tableLineIsColumn = false
    private var tableLineIndex = -1
    private var tableLinePageIndex = -1

    // GENERIC TRANSFORM (resize + rotate for any single non-line / multi / mixed selection)
    private var selObb: Obb? = null // the tilting selection box; null when nothing is selected
    private var txItems: List<Selected> = emptyList()
    private var txSnaps: List<GeometrySnapshot> = emptyList()
    private var txStartObb: Obb? = null
    private var txHandle: HandleId? = null // non-null = scale via this box handle; null = rotate
    private var txCenter = Pt.ZERO
    private var txGrabAngle = 0.0
    private var txStartAngle = 0.0

    // LONG-PRESS GRAB
    private val handler by lazy { Handler(Looper.getMainLooper()) } // lazy: see [choreographer]
    private var longPressRunnable: Runnable? = null
    private var longPressStart = Pt.ZERO
    private var longPressContent = Pt.ZERO
    private var longPressCandidate: Selected? = null
    private var longPressLocked: CanvasItem? = null
    private var longPressPrevTool: Tool? = null

    // TEXT EDITING
    private var editingText: TextItem? = null
    private var editingIsNew = false
    private var editingOldText = ""
    private var editingPageIndex = -1
    val editingItem: TextItem? get() = editingText
    val editingPage: Int get() = editingPageIndex

    /** The style new text boxes are created with; mirrors the active box while one is open. */
    var textFace: FontFace = TextItem.DEFAULT_FACE
        private set
    var textPointSize: Double = TextItem.DEFAULT_POINT_SIZE
        private set

    // TEXT DRAG-CREATE (drag a rectangle to size a new box; a tap makes a default one)
    private var textDragStart = Pt.ZERO // content space
    private var textDragRect: Rect? = null // content space, for the live preview
    private var textDragPageIndex = -1

    /** Front-buffered wet ink, installed by the host when the device can do it. */
    var frontInk: FrontInk? = null

    init {
        state.isLiftedItem = { item ->
            item === editingText || frontInk?.holding(item) == true || selection.any { it.item === item }
        }
    }

    val hasSelection: Boolean get() = selection.isNotEmpty()

    fun configFor(t: Tool): ToolConfig = toolConfigs[t] ?: ToolDefaults.configFor(t)

    fun setToolConfig(t: Tool, config: ToolConfig) {
        toolConfigs[t] = config
    }

    fun setTool(t: Tool) {
        if (t == tool) {
            return
        }
        // Remember what to re-arm if the eraser/select/screenshot/text later switches back (the tool it replaced).
        if (t == Tool.ERASER) toolBeforeEraser = tool
        if (t == Tool.SELECT) toolBeforeSelect = tool
        if (t == Tool.SCREENSHOT) toolBeforeScreenshot = tool
        if (t == Tool.TEXT_BOX) toolBeforeText = tool
        commitTextEdit()
        if (t != Tool.TEXT) flowText?.endSession()
        abortGesture()
        clearSelection()
        clearScreenshot()
        eraserCursor = null
        previousTool = tool
        tool = t
        onToolChanged(t)
        requestRender()
    }

    fun rulerVisible(): Boolean = ruler.visible

    /** Toggle the on-screen ruler; on first show it places itself in the current viewport. */
    fun toggleRuler() {
        ruler.visible = !ruler.visible
        if (ruler.visible && !ruler.initialized) {
            ruler.placeDefault(state.viewportW.toDouble(), state.viewportH.toDouble(), state.devicePxPerDp)
        }
        requestRender()
    }

    fun wandEnabled(): Boolean = wandMode

    /** Toggle disappearing-ink mode; turning it off vanishes anything currently held or fading. */
    fun toggleWand() {
        wandMode = !wandMode
        if (!wandMode) clearFading()
        requestRender()
    }

    // --- touch entry point ---

    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(e)
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(e)
            MotionEvent.ACTION_UP -> handleUp(e)
            MotionEvent.ACTION_CANCEL -> {
                abortGesture()
                requestRender()
            }
        }
        return true
    }

    fun onHover(e: MotionEvent): Boolean {
        if (handleHoverAction(e)) return true
        val isEraserPointer = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        if (tool != Tool.ERASER && !isEraserPointer) return false
        eraserCursor = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            null
        } else {
            Pt(e.x.toDouble(), e.y.toDouble())
        }
        requestRender()
        return true
    }

    /** Drive the side-button tool (eraser/pan) off the hover stream while the button is held and
     *  "activate during hover" is on, so the pen erases or pans without touching the screen. */
    private fun handleHoverAction(e: MotionEvent): Boolean {
        val buttonNow = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
            e.actionMasked != MotionEvent.ACTION_HOVER_EXIT &&
            ((e.buttonState and STYLUS_BUTTON_MASK) != 0 || stylusButtonHeld)
        val want = penButtonHover && buttonNow &&
            (penButtonTool == Tool.ERASER || penButtonTool == Tool.PAN)
        val vx = e.x.toDouble()
        val vy = e.y.toDouble()
        return when {
            want && hoverActionTool == null -> { beginHoverAction(vx, vy); true }
            want && hoverActionTool != null -> { extendHoverAction(vx, vy); true }
            hoverActionTool != null -> { endHoverAction(); true }
            else -> false
        }
    }

    private fun beginHoverAction(vx: Double, vy: Double) {
        hoverActionTool = penButtonTool
        when (penButtonTool) {
            Tool.ERASER -> { clearSelection(); beginErase(vx, vy) }
            Tool.PAN -> beginPan(vx, vy, fromPenButton = true)
            else -> hoverActionTool = null
        }
        requestRender()
    }

    private fun extendHoverAction(vx: Double, vy: Double) {
        when (hoverActionTool) {
            Tool.ERASER -> eraseAt(vx, vy)
            Tool.PAN -> extendPan(vx, vy)
            else -> Unit
        }
    }

    /** End a live hover gesture: commit the erase, or stop the pan (with a flick if it was moving). */
    private fun endHoverAction() {
        when (hoverActionTool) {
            Tool.ERASER -> endErase()
            Tool.PAN -> { mode = PointerMode.IDLE; startPanFling() }
            else -> Unit
        }
        hoverActionTool = null
        requestRender()
    }

    /** Some pens report the side button only on the hovering generic-motion stream
     *  (ACTION_BUTTON_PRESS/RELEASE), never in the touch buttonState. Latch it here; a release
     *  here also ends an in-progress hover gesture even if the pen has not moved. */
    fun onGenericMotion(e: MotionEvent) {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            stylusButtonHeld = (e.buttonState and STYLUS_BUTTON_MASK) != 0
            if (!stylusButtonHeld && hoverActionTool != null) endHoverAction()
        }
    }

    /** Feeder C: Bluetooth/USI pens often deliver the side button only as a KeyEvent, never in any
     *  MotionEvent buttonState. Latch those into the same held flag ([down] true on key-down); a
     *  key-up also ends a live hover gesture. Returns true if the key was a stylus side button, so
     *  the host consumes it. */
    fun onStylusButtonKey(keyCode: Int, down: Boolean): Boolean {
        if (keyCode != KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY &&
            keyCode != KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY &&
            keyCode != KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY &&
            keyCode != KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL &&
            keyCode != VENDOR_HELD_BUTTON_KEYCODE
        ) {
            return false
        }
        stylusButtonHeld = down
        if (!down && hoverActionTool != null) endHoverAction()
        return true
    }

    private fun handleDown(e: MotionEvent) {
        if (hoverActionTool != null) endHoverAction() // a hovering side-button gesture yields to contact
        downStoppedFling = flinging // captured before stopping: a tap that only halts a glide must not dismiss
        stopFling() // a new touch halts any in-progress glide
        stopOverscrollSettle() // ...and lets a re-grab take over the elastic mid-spring
        panMayCommitText = false // a fresh gesture; the editing branch below re-arms it if it applies
        val toolType = e.getToolType(0)
        val vx = e.getX(0).toDouble()
        val vy = e.getY(0).toDouble()
        val content = state.viewportToContent(Pt(vx, vy))
        drawingPointerId = e.getPointerId(0)
        drawingIsStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS

        // Cropping an image is modal: a press adjusts the crop rect (handle drag or move), never ink.
        if (mode == PointerMode.CROP) { beginCropDrag(content); return }

        // Resolve which tool this pointer drives:
        //  - the stylus eraser end, or the held side button, force the eraser/side-button tool;
        //  - a finger pans unless finger-draw is enabled;
        //  - otherwise the armed tool.
        val buttonHeld = drawingIsStylus &&
            ((e.buttonState and STYLUS_BUTTON_MASK) != 0 || stylusButtonHeld)
        val effectiveTool: Tool = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            buttonHeld && penButtonTool != null -> penButtonTool!!
            // While something is selected, the stylus grabs that selection (resize on a handle,
            // move on the body) instead of inking through it, matching the finger. Off the
            // selection it falls through to draw and dismisses the selection (see beginDraw).
            hasSelection && (drawingIsStylus && tool.isStroke || tool == Tool.SHAPE || tool == Tool.TABLE) &&
                fingerHitsSelection(content) -> Tool.SELECT
            // A finger may grab/resize the ACTIVE selection even when finger-draw is off;
            // off the selection it still pans.
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff &&
                fingerHitsSelection(content) -> Tool.SELECT
            // A finger otherwise pans instead of drawing/selecting/shaping/erasing (text stays usable by finger).
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff -> Tool.PAN
            else -> tool
        }

        // A press off the box while editing. With a finger we defer: a tap commits (re-arming the
        // prior tool), a drag scrolls the page with the edit kept live (decided in handleUp's PAN
        // branch). A stylus / side-button / eraser press commits now and proceeds as usual. A Text
        // press never spawns a new box on the same gesture (that double-create was a bug).
        if (editingText != null) {
            if (toolType == MotionEvent.TOOL_TYPE_FINGER && effectiveTool == Tool.TEXT_BOX) {
                panMayCommitText = true
                beginPan(vx, vy)
                return
            }
            commitTextEdit(restoreTool = true)
            if (effectiveTool == Tool.TEXT_BOX) {
                mode = PointerMode.IDLE
                return
            }
        }

        // Touching the ruler grabs it before the normal tool dispatch — for the stylus too, so a
        // pen-down ON the body moves it. A pen-down OFF the body falls through to drawing, where the
        // magnet snaps the in-progress stroke to the edge. Its buttons toggle; its body moves it.
        if (ruler.visible) {
            val v = Pt(vx, vy)
            val hi = ruler.hitHandle(v, rulerHandleDist(), (RULER_HANDLE_HIT * state.devicePxPerDp).coerceAtLeast(ruler.handleRadiusPx()))
            if (hi != null && !ruler.lockAngle) {
                rulerRotateSign = if (hi == 0) 1.0 else -1.0
                mode = PointerMode.RULER_ROTATE
                cancelLongPress()
                requestRender()
                return
            }
            val btn = ruler.hitButton(v, (RULER_BTN_HIT * state.devicePxPerDp).coerceAtLeast(ruler.buttonRadiusPx()))
            if (btn != null) {
                when (btn) {
                    RulerButton.LOCK_POS -> ruler.lockPosition = !ruler.lockPosition
                    RulerButton.LOCK_ANGLE -> ruler.lockAngle = !ruler.lockAngle
                }
                mode = PointerMode.IDLE
                requestRender()
                return
            }
            // Move zone: the body, minus an inner margin (as wide as the magnet band) along each edge
            // for the STYLUS — so a pen drawing right along the edge never accidentally grabs the ruler.
            val band = RULER_SNAP_DP * state.devicePxPerDp
            val moveHalf =
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) (ruler.thicknessPx / 2.0 - band).coerceAtLeast(0.0)
                else ruler.thicknessPx / 2.0
            if (abs(ruler.signedAcross(v)) <= moveHalf) {
                if (ruler.lockPosition) {
                    mode = PointerMode.IDLE // locked: swallow so it neither pans nor draws under the ruler
                } else {
                    mode = PointerMode.RULER_MOVE
                    rulerGrabOffset = ruler.center - v
                }
                requestRender()
                return
            }
        }

        // Zoom-lock pan preference: swallow a single-finger pan when locked and set to "double"/"none".
        if (effectiveTool == Tool.PAN && !singleFingerPanAllowed()) {
            mode = PointerMode.IDLE
            return
        }

        when {
            effectiveTool == Tool.PAN -> beginPan(vx, vy, fromPenButton = buttonHeld && penButtonTool == Tool.PAN)
            effectiveTool.isStroke -> beginDraw(content, resolvePressure(e, 0, toolType), effectiveTool, e.eventTime, Pt(vx, vy))
            effectiveTool == Tool.ERASER -> {
                clearSelection()
                erasingWithFinger = toolType == MotionEvent.TOOL_TYPE_FINGER
                beginErase(vx, vy)
            }
            effectiveTool == Tool.SELECT -> beginSelect(content)
            effectiveTool == Tool.LASSO -> beginLasso(content)
            effectiveTool == Tool.SCREENSHOT -> beginScreenshot(content)
            effectiveTool == Tool.SHAPE -> beginShape(content)
            effectiveTool == Tool.TABLE -> beginTable(content)
            effectiveTool == Tool.TEXT -> beginTextGesture(content, Pt(vx, vy))
            effectiveTool == Tool.TEXT_BOX -> beginTextBoxGesture(content)
            else -> Unit
        }
        // The flow caret owns its own long press (word selection), and mid text-drag it is
        // suppressed so a hold-then-drag still sizes a box.
        if (mode != PointerMode.FLOW_TEXT && mode != PointerMode.TEXT_DRAG) {
            armLongPress(Pt(vx, vy), content, toolType == MotionEvent.TOOL_TYPE_FINGER)
        }
    }

    private fun handlePointerDown(e: MotionEvent) {
        cancelLongPress()
        // A second finger on a ruler being moved twists/translates it instead of pinch-zooming.
        if (mode == PointerMode.RULER_MOVE && e.pointerCount >= 2) {
            beginRulerTransform(e)
            return
        }
        if (mode == PointerMode.RULER_ROTATE) return // handle-drag rotation ignores extra fingers
        if (mode == PointerMode.DRAW && drawingIsStylus) return
        // A finger erase (finger-draw on) yields to a two-finger pinch: commit what was erased so
        // far as one undo step, then start the zoom. A stylus-eraser erase keeps ignoring incidental
        // finger/palm contact, so a resting hand never starts a zoom mid-erase.
        if (mode == PointerMode.ERASE) {
            if (erasingWithFinger && e.pointerCount >= 2) { endErase(); beginPinch(e) }
            return
        }
        if (e.pointerCount >= 2) beginPinch(e)
    }

    private fun handleMove(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        val vx = e.getX(idx).toDouble()
        val vy = e.getY(idx).toDouble()
        val content = state.viewportToContent(Pt(vx, vy))
        maybeCancelLongPress(Pt(vx, vy))
        when (mode) {
            PointerMode.DRAW -> extendDraw(e)
            PointerMode.PAN -> extendPan(vx, vy)
            PointerMode.PINCH -> updatePinch(e)
            PointerMode.RULER_MOVE -> { ruler.center = Pt(vx, vy) + rulerGrabOffset; requestRender() }
            PointerMode.RULER_TRANSFORM -> updateRulerTransform(e)
            PointerMode.RULER_ROTATE -> updateRulerRotate(e)
            PointerMode.ERASE -> eraseAt(vx, vy)
            PointerMode.BAND -> extendBand(content)
            PointerMode.LASSO_DRAW -> extendLasso(content)
            PointerMode.SHOT -> extendScreenshot(content)
            PointerMode.MOVE -> extendMove(content)
            PointerMode.RESIZE -> extendResize(content)
            PointerMode.CROP -> extendCrop(content)
            PointerMode.TABLE_LINE -> extendTableLine(content)
            PointerMode.TABLE_DRAW -> extendTable(content)
            PointerMode.TRANSFORM -> extendTransform(content)
            PointerMode.SHAPE -> extendShape(content)
            PointerMode.FLOW_TEXT ->
                // A plain drag with the text tool scrolls the document; only a long-pressed
                // drag extends the selection (dragTo returns true to request the pan handoff).
                if (flowText?.dragTo(content, Pt(vx, vy)) == true) beginPan(vx, vy)
            PointerMode.TEXT_DRAG -> extendTextDrag(content)
            else -> Unit
        }
    }

    private fun handlePointerUp(e: MotionEvent) {
        if (mode == PointerMode.RULER_TRANSFORM) {
            // One finger lifted: fall back to a single-finger move with whichever finger remains.
            val up = e.actionIndex
            val remaining = (0 until e.pointerCount).firstOrNull { it != up }
            if (remaining != null) {
                drawingPointerId = e.getPointerId(remaining)
                rulerGrabOffset = ruler.center - Pt(e.getX(remaining).toDouble(), e.getY(remaining).toDouble())
                mode = PointerMode.RULER_MOVE
            } else {
                mode = PointerMode.IDLE
            }
            requestRender()
            return
        }
        if (mode == PointerMode.PINCH && e.pointerCount <= 2) endPinch()
    }

    private fun handleUp(e: MotionEvent) {
        cancelLongPress()
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        val content = state.viewportToContent(Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble()))
        when (mode) {
            PointerMode.DRAW -> endDraw(e)
            PointerMode.PAN -> {
                mode = PointerMode.IDLE
                val upViewport = Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble())
                val tap = !downStoppedFling && upViewport.distanceTo(panDownViewport) <= TAP_SLOP
                if (panMayCommitText) {
                    // Press off the box while editing: only a tap commits (and re-arms the prior tool);
                    // a drag just scrolled with the edit kept live. Either way settle any elastic/glide.
                    panMayCommitText = false
                    when {
                        tap -> commitTextEdit(restoreTool = true)
                        state.overscrollY > 0.0 -> releaseOverscroll()
                        !state.verticalScroll -> endPanPaginated()
                        else -> startPanFling()
                    }
                } else {
                    // A finger tap (no drag) that didn't just halt a glide, landing off the current
                    // selection, dismisses it — the finger's counterpart to the stylus's empty-tap clear.
                    val linkHandled = tap && state.overscrollY <= 0.0 && tryLinkTap(content)
                    when {
                        linkHandled -> Unit
                        state.overscrollY > 0.0 -> releaseOverscroll()
                        tap && tool == Tool.SELECT && fingerTapSelect(content) -> Unit
                        tap && hasSelection && selectionBoundsContent()?.contains(content) != true -> clearSelection()
                        !state.verticalScroll -> endPanPaginated()
                        else -> startPanFling()
                    }
                }
            }
            PointerMode.PINCH -> endPinch()
            PointerMode.ERASE -> { endErase(); maybeSwitchBackAfterErase() }
            PointerMode.BAND -> endBand()
            PointerMode.LASSO_DRAW -> endLasso()
            PointerMode.SHOT -> endScreenshot()
            PointerMode.MOVE -> endMove(content)
            PointerMode.RESIZE -> endResize()
            PointerMode.CROP -> endCrop()
            PointerMode.TABLE_LINE -> endTableLine()
            PointerMode.TABLE_DRAW -> endTable()
            PointerMode.TRANSFORM -> endTransform()
            PointerMode.SHAPE -> endShape()
            PointerMode.FLOW_TEXT -> {
                mode = PointerMode.IDLE
                flowText?.release(content, Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble()), e.eventTime)
            }
            PointerMode.TEXT_DRAG -> endTextDrag(content)
            PointerMode.RULER_MOVE -> { mode = PointerMode.IDLE; requestRender() }
            PointerMode.RULER_TRANSFORM -> { mode = PointerMode.IDLE; requestRender() }
            PointerMode.RULER_ROTATE -> { mode = PointerMode.IDLE; requestRender() }
            else -> Unit
        }
    }

    /** Map a finger tap's content point to its page + page-space point and offer it to [onLinkTap]. */
    private fun tryLinkTap(content: Pt): Boolean {
        val cb = onLinkTap ?: return false
        val pageIndex = state.pageIndexAtContent(content) ?: return false
        return cb(pageIndex, state.toPageSpace(pageIndex, content))
    }

    // --- DRAW ---

    private fun beginDraw(content: Pt, pressure: Double, drawTool: Tool, timeMs: Long, downViewport: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        // Reaching here with a live selection means the press landed off it (on it the stylus would
        // have grabbed it): dismiss it. A bare tap that only dismissed is dropped in endDraw.
        strokeDismissedSelection = hasSelection
        drawDownViewport = downViewport
        if (hasSelection) clearSelection()
        // Capture content-px → dp scale now, so the speed pen judges gesture speed in
        // zoom- and density-independent dp regardless of how the stroke is later viewed.
        val speedScale = state.zoom / state.devicePxPerDp
        val cfg0 = configFor(drawTool)
        // SCALE off: normalise the stroke to its 100%-zoom size by dividing the spatial
        // dimensions by the draw-time zoom, so it draws at a constant on-screen thickness
        // whatever zoom you are at. Baked into the snapshot, so it is ordinary ink afterwards.
        // A pen with a colour override always draws in its own colour; otherwise it follows the
        // toolbar's active ink colour.
        val drawColor = cfg0.colorOverride ?: inkColor
        val cfg = if (cfg0.scale) {
            cfg0.copy(rgba = drawColor)
        } else {
            val z = state.zoom
            cfg0.copy(
                rgba = drawColor,
                baseWidth = cfg0.baseWidth / z,
                dashLength = cfg0.dashLength / z,
                dashGap = cfg0.dashGap / z,
                scale = true,
            )
        }
        val straight = drawTool == Tool.HIGHLIGHTER && cfg.straightLine
        val stroke = Stroke(
            drawTool, cfg, speedScale = speedScale, straight = straight,
            smoothScale = smoothScaleFor(state.zoom),
        )
        // Live until pen-up, so lift-time rules (the calligraphy dot swell) can't fire mid-draw.
        stroke.finished = false
        strokeStartTimeMs = timeMs
        // Pen back down: re-solidify the held batch, so a long stroke can't outlive the previous fade.
        if (wandMode && drawTool.isStroke) {
            stopFade()
            fadeAlpha = 1.0
            scheduleFade()
        }
        // Ruler magnet: reset per-stroke engagement, then snap the first sample if it lands in the zone.
        snapEngaged = false
        snapRunStartEdge = null
        snapCurrentEdge = null
        snapPenViewport = null
        val first = state.toPageSpace(pageIndex, state.viewportToContent(magnetize(downViewport)))
        stroke.addSample(Sample(first.x, first.y, pressure)) // first sample: t = 0
        liveStroke = stroke
        strokePageIndex = pageIndex
        mode = PointerMode.DRAW
        // Shape snap: only solid ink pens (not the highlighter or its straight-line mode) arm the
        // "hold still → shape" timer — and never while the ruler is up (you're drawing straight lines),
        // nor under the wand, whose strokes are ephemeral and must never commit a shape to the page.
        dwellEligible = detectShapes && drawTool.isStroke && drawTool != Tool.HIGHLIGHTER && !straight &&
            !ruler.visible && !wandMode
        if (dwellEligible) {
            dwellAnchor = downViewport
            armDwell()
        }
        frontInk?.wet(stroke, pageIndex)
        requestRender()
    }

    private fun extendDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId)
        if (idx < 0) return
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
            if (drawingIsStylus) e.getPressure(idx).toDouble() else 1.0, e.eventTime, force = false,
        )
        frontInk?.wet(liveStroke, strokePageIndex)
        requestRender()
    }

    private fun addStrokePoint(vx: Double, vy: Double, pressure: Double, timeMs: Long, force: Boolean) {
        val stroke = liveStroke ?: return
        val pi = strokePageIndex ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        val vp = magnetize(Pt(vx, vy))
        val content = state.viewportToContent(vp)
        // The pen has walked onto another page: carry the stroke over rather than let it slide
        // under the paper. A straight line is one segment by definition and never hands over.
        if (!stroke.straight) {
            val onPage = state.pageIndexAtContent(content)
            if (onPage != null && onPage != pi) {
                handOverStroke(stroke, pi, onPage, content, pressure, timeMs)
                return
            }
        }
        val local = state.toPageSpace(pi, content)
        if (stroke.straight) {
            // Straight-line mode: the stroke is always pen-down → current point, so the moving
            // endpoint just tracks the pointer (decimation/spacing gates don't apply). Near an axis
            // it snaps flat like a dragged line, unless the ruler is already steering the angle.
            val start = stroke.samples.firstOrNull()
            val end = if (start != null && !ruler.visible) snapAxisEndpoint(Pt(start.x, start.y), local) else local
            stroke.setStraightEnd(Sample(end.x, end.y, pressure.coerceIn(0.0, 1.0), (timeMs - strokeStartTimeMs).toDouble()))
            return
        }
        val last = stroke.samples.lastOrNull()
        // Decimate by on-screen spacing, not content spacing: the gate is MIN_SAMPLE_DIST
        // viewport px (content px ÷ zoom), capped so it never coarsens past the old 1-content-px
        // floor when zoomed out. A fixed content-px gate discarded ever-finer detail the more you
        // zoomed in, so strokes drawn while zoomed faceted into ~zoom-px chords.
        val gate = (MIN_SAMPLE_DIST / state.zoom).coerceAtMost(MIN_SAMPLE_DIST)
        if (force || last == null || Pt(last.x, last.y).manhattanTo(local) >= gate) {
            stroke.addSample(Sample(local.x, local.y, pressure.coerceIn(0.0, 1.0), (timeMs - strokeStartTimeMs).toDouble()))
        }
        // Real movement restarts the hold-to-snap clock; staying within the slop lets it mature,
        // so the snap fires only once the pen has actually come to rest. (Sub-slop jitter is ignored
        // even when a sample is decimated out above, so a trembling-but-still pen still triggers.)
        if (dwellEligible && Pt(vx, vy).distanceTo(dwellAnchor) > SHAPE_DWELL_SLOP) {
            dwellAnchor = Pt(vx, vy)
            armDwell()
        }
    }

    /**
     * Hand the stroke under the pen from one page to the next, so a line drawn across a page
     * boundary keeps going instead of disappearing under the paper.
     *
     * The part already drawn is retired onto the page it belongs to, and a fresh segment picks up
     * on the new one carrying the last point across: that bridging sample lands outside the new
     * page and is clipped there, so the ribbon meets the paper's edge rather than starting a
     * blunt millimetre inside it. Everything the crossing files is one undo step (see [endDraw]).
     */
    private fun handOverStroke(
        stroke: Stroke,
        fromPage: Int,
        toPage: Int,
        content: Pt,
        pressure: Double,
        timeMs: Long,
    ) {
        // A stroke long enough to reach the next page is a line, not a held shape.
        cancelDwell()
        dwellEligible = false
        val last = stroke.samples.lastOrNull()
        retireCrossedSegment(stroke, fromPage)
        val next = Stroke(
            stroke.tool,
            stroke.config,
            speedScale = stroke.speedScale,
            smoothScale = stroke.smoothScale,
        )
        next.finished = false
        if (last != null) {
            val bridge = state.toPageSpace(toPage, state.fromPageSpace(fromPage, Pt(last.x, last.y)))
            next.addSample(Sample(bridge.x, bridge.y, last.pressure, last.t))
        }
        val local = state.toPageSpace(toPage, content)
        next.addSample(
            Sample(local.x, local.y, pressure.coerceIn(0.0, 1.0), (timeMs - strokeStartTimeMs).toDouble()),
        )
        liveStroke = next
        strokePageIndex = toPage
        requestRender()
    }

    /** Put a crossed-off segment where it belongs: ephemeral ink waits for its fade, ordinary ink
     *  joins the page it was drawn on and holds its command back for the one composite undo. */
    private fun retireCrossedSegment(stroke: Stroke, pageIndex: Int) {
        stroke.finished = true
        if (wandMode && stroke.tool.isStroke) {
            fadingStrokes.add(FadingStroke(stroke, pageIndex))
            frontInk?.holdFading(stroke)
            return
        }
        crossedSegments.add(fileStroke(stroke, pageIndex))
    }

    /** Push this stroke's whole edit — every page it crossed plus [last], if any — as one undo
     *  step. A no-op when the stroke neither crossed a page nor committed anything. */
    private fun pushStrokeEdit(last: Command?) {
        val all = if (last == null) crossedSegments.toList() else crossedSegments + last
        crossedSegments.clear()
        if (all.isEmpty()) return
        history.push(if (all.size == 1) all[0] else CompositeCommand(all))
        onContentChanged()
    }

    /** Add a finished stroke to its page and its cache; returns the command that undoes it. */
    private fun fileStroke(stroke: Stroke, pageIndex: Int): Command {
        simplifyForCommit(stroke)
        val page = state.document.pages[pageIndex]
        page.items.add(stroke)
        // The front buffer is still showing this stroke, and drawing it here as well would put two
        // antialiased edges over each other. It goes into the cache once the pad has let go.
        if (frontInk?.hold(stroke, page) != true) state.appendToCache(page, stroke)
        state.document.dirty = true
        return AddItem(page, stroke)
    }

    private fun endDraw(e: MotionEvent) {
        // Drop the dwell timer before the final sample so the lift can't re-arm or fire a snap.
        cancelDwell()
        dwellEligible = false
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            if (drawingIsStylus) e.getPressure(idx).toDouble() else 1.0, e.eventTime, force = true,
        )
        // A mid-stroke snap already committed a shape and cleared liveStroke, so this block is
        // skipped and the freehand stroke is intentionally not also committed.
        val stroke = liveStroke
        val pi = strokePageIndex
        val up = Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble())
        if (stroke != null && pi != null && !stroke.isEmpty) {
            // The pen is up: rebuild with lift-time rules on (a dot-sized calligraphy stroke
            // takes the nib's broad face) before the stroke is committed or held for fading.
            stroke.finished = true
            when {
                // A bare tap whose only job was to dismiss the selection: drop the dot it would leave.
                strokeDismissedSelection && up.distanceTo(drawDownViewport) <= TAP_SLOP -> Unit
                wandMode && stroke.tool.isStroke -> {
                    // Disappearing ink: held ephemerally, never committed to the model/undo/cache/save.
                    // A stroke drawn mid-fade cancels the fade and re-solidifies the whole held batch.
                    fadingStrokes.add(FadingStroke(stroke, pi))
                    frontInk?.holdFading(stroke)
                    stopFade()
                    fadeAlpha = 1.0
                    scheduleFade()
                }
                else -> pushStrokeEdit(fileStroke(stroke, pi))
            }
        }
        // Nothing was committed here (a dismissing tap, or ephemeral ink), but a crossing may
        // still have filed segments that need their undo step.
        pushStrokeEdit(null)
        strokeDismissedSelection = false
        liveStroke = null
        strokePageIndex = null
        snapEngaged = false
        snapRunStartEdge = null
        snapCurrentEdge = null
        snapPenViewport = null
        mode = PointerMode.IDLE
        // A mid-stroke snap left a shape selected; now that the gesture is idle, surface its menu.
        if (snappedSelectionPendingMenu) {
            snappedSelectionPendingMenu = false
            refreshSelectionMenu()
        }
        requestRender()
    }

    /** Pen-up sample reduction: like the capture gate, the tolerance is screen-space — viewport
     *  px at the draw zoom (÷ zoom → content px), capped so zoomed-out ink keeps content fidelity.
     *  The stroke's just-built geometry supplies the half-width channel, so pressure/speed width
     *  variation survives the reduction. */
    private fun simplifyForCommit(stroke: Stroke) {
        if (StrokeSimplify.enabled && !stroke.straight) {
            val eps = (SIMPLIFY_EPS / state.zoom).coerceAtMost(SIMPLIFY_EPS)
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

    private fun armDwell() {
        cancelDwell()
        val r = Runnable { onDwellElapsed() }
        dwellRunnable = r
        handler.postDelayed(r, SHAPE_DWELL_MS)
    }

    private fun cancelDwell() {
        dwellRunnable?.let { handler.removeCallbacks(it) }
        dwellRunnable = null
    }

    /** Fired when the pen has held still: snap the live stroke to a shape if it's a confident match. */
    private fun onDwellElapsed() {
        dwellRunnable = null
        if (!dwellEligible) return
        val stroke = liveStroke ?: return
        val pi = strokePageIndex ?: return
        if (stroke.samples.size < SHAPE_MIN_SAMPLES) return // not enough yet; the next move re-arms
        val rec = ShapeRecognizer.recognize(stroke.samples) ?: return // not a shape; the next move re-arms
        commitRecognizedShape(stroke, pi, rec)
    }

    /** Replace the (uncommitted) live stroke with a recognized [ShapeItem], as one undoable add. */
    private fun commitRecognizedShape(stroke: Stroke, pageIndex: Int, rec: RecognizedShape) {
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        val strokeWidth = stroke.config.baseWidth * SHAPE_PEN_PARITY
        val color = stroke.config.rgba // the as-drawn ink colour (not renderColor's alpha-scaled one)
        val dashed = stroke.tool == Tool.DASHED // a dashed pen snaps to a dashed shape
        val verts = rec.vertices
        val shape = if (verts != null) {
            // Polygon/polyline: keep the recognized corners (neon/dash carried like the other kinds).
            ShapeItem.poly(
                rec.kind, verts, color, strokeWidth, null, stroke.config.neon, stroke.config.neonStrength,
                dashed, stroke.config.dashLength, stroke.config.dashGap,
            )
        } else {
            ShapeItem(
                shape = rec.kind,
                start = rec.start,
                end = rec.end,
                strokeRgba = color,
                strokeWidth = strokeWidth,
                fillRgba = null,
                neon = stroke.config.neon, // a neon pen snaps to a neon shape (highlighter never snaps)
                neonStrength = stroke.config.neonStrength,
                dashed = dashed,
                dashLength = stroke.config.dashLength,
                dashGap = stroke.config.dashGap,
            )
        }
        page.items.add(shape)
        state.appendToCache(page, shape)
        history.push(AddItem(page, shape))
        state.document.dirty = true
        // The stroke was never added to the page, so clearing liveStroke makes the live ink preview
        // vanish the instant it snaps; the eventual pen-up in endDraw then commits nothing.
        liveStroke = null
        dwellEligible = false
        cancelDwell()
        // Auto-select the new shape so it can be resized right away. The menu only shows once the
        // gesture settles, so flag it for the pen lift (endDraw) rather than mid-stroke here.
        setSelection(listOf(Selected(pageIndex, shape)))
        snappedSelectionPendingMenu = true
        onContentChanged()
        onHaptic()
        requestRender()
    }

    // --- ERASE ---

    // SCALE off: hold the eraser at a constant on-screen size by shrinking its content-space
    // radius as you zoom in (both the hit-test and the cursor circle derive from this).
    private fun eraserRadius(): Double {
        val cfg = configFor(Tool.ERASER)
        return if (cfg.scale) cfg.baseWidth else cfg.baseWidth / state.zoom
    }

    private fun areaErase(): Boolean = configFor(Tool.ERASER).eraseMode == EraseMode.AREA

    private fun beginErase(vx: Double, vy: Double) {
        eraseRemovals.clear()
        eraseSnapshots.clear()
        mode = PointerMode.ERASE
        eraseAt(vx, vy)
    }

    private fun eraseAt(vx: Double, vy: Double) {
        eraserCursor = Pt(vx, vy)
        val content = state.viewportToContent(Pt(vx, vy))
        val radius = eraserRadius()
        val eraserBox = Rect(content.x - radius, content.y - radius, radius * 2, radius * 2)
        val area = areaErase()
        var changed = false
        val drawable = state.drawablePageRange()
        for (pi in state.document.pages.indices) {
            if (pi !in drawable) continue // a hidden paginated neighbour can't be erased
            val pr = state.pageRects.getOrNull(pi) ?: continue
            if (!pr.intersects(eraserBox)) continue // skip pages the eraser isn't over
            val page = state.document.pages[pi]
            val local = state.toPageSpace(pi, content)
            val cx = local.x
            val cy = local.y
            val dirty = if (area) eraseAreaFromPage(page, cx, cy, radius)
            else eraseStrokesFromPage(page, cx, cy, radius)
            if (dirty != null) {
                // Repaint only the erased area in place; fall back to a full
                // rebuild only when the page has no live cache yet.
                val rect = dirty.outset(REPAIR_PAD)
                if (!state.repairRegion(page, rect)) state.invalidatePage(page)
                changed = true
            }
        }
        if (changed) onContentChanged()
        requestRender()
    }

    /** STROKE mode: remove every stroke/shape the eraser circle touches. Images and text boxes are
     *  deliberately-placed and protected (delete those via select + delete). Returns the repaint
     *  region, or null if nothing changed. */
    private fun eraseStrokesFromPage(page: Page, cx: Double, cy: Double, radius: Double): Rect? {
        val toRemove = page.items.filter {
            !it.locked && it !is ImageItem && it !is TextItem && it.intersectsCircle(cx, cy, radius)
        }
        if (toRemove.isEmpty()) return null
        var dirty: Rect? = null
        for (item in toRemove) {
            page.items.remove(item)
            eraseRemovals.add(page to item)
            val b = item.paintBounds()
            dirty = dirty?.union(b) ?: b
        }
        return dirty
    }

    /** AREA mode: replace each touched stroke or shape with the fragments that survive the eraser
     *  circle, spliced in at the original's z-position. Text and images are left untouched. Returns
     *  the repaint region, or null if nothing changed. */
    private fun eraseAreaFromPage(page: Page, cx: Double, cy: Double, radius: Double): Rect? {
        var dirty: Rect? = null
        var i = 0
        while (i < page.items.size) {
            val item = page.items[i]
            val frags: List<CanvasItem>? = if (item.locked) {
                null
            } else {
                when (item) {
                    is Stroke -> item.erasedBy(cx, cy, radius)
                    is ShapeItem -> item.erasedBy(cx, cy, radius)
                    else -> null
                }
            }
            if (frags == null) {
                i++
                continue
            }
            // Snapshot the page's items on first contact this gesture, before mutating it.
            if (!eraseSnapshots.containsKey(page)) eraseSnapshots[page] = page.items.toList()
            val b = item.paintBounds()
            dirty = dirty?.union(b) ?: b
            page.items.removeAt(i)
            page.items.addAll(i, frags)
            i += frags.size // step past the freshly-inserted fragments
        }
        return dirty
    }

    private fun endErase() {
        if (areaErase()) {
            // One drag may split/trim many strokes across pages; commit each touched page's
            // net before/after as one undo step.
            val cmds = eraseSnapshots.mapNotNull { (page, before) ->
                val after = page.items.toList()
                if (after != before) ReplacePageItems(page, before, after) else null
            }
            if (cmds.isNotEmpty()) {
                history.push(if (cmds.size == 1) cmds[0] else CompositeCommand(cmds))
                state.document.dirty = true
                onContentChanged()
            }
        } else if (eraseRemovals.isNotEmpty()) {
            history.push(EraseItems(eraseRemovals.toList()))
            state.document.dirty = true
            onContentChanged()
        }
        eraseRemovals.clear()
        eraseSnapshots.clear()
        eraserCursor = null
        mode = PointerMode.IDLE
        requestRender()
    }

    /**
     * "Switch back after erasing": once a toolbar-eraser drag lifts, re-arm the pen/highlighter
     * that was active before the eraser. Only when the armed tool is the eraser (so a stylus-tip or
     * side-button erase, which never changed the armed tool, is left alone) and the remembered tool
     * is a stroke tool (a pen or the highlighter).
     */
    private fun maybeSwitchBackAfterErase() {
        if (tool != Tool.ERASER || !configFor(Tool.ERASER).switchBackAfterErase) return
        val back = toolBeforeEraser ?: return
        if (back.isStroke) setTool(back)
    }

    /**
     * "Switch back after a selection action": once a select-tool action (move, resize, or a menu
     * op like delete/cut/copy/duplicate) completes, re-arm the pen/highlighter that was active
     * before the select tool. Only when the armed tool is the select tool and the remembered tool
     * is a stroke tool; a long-press temporary grab is left to its own restore on deselect.
     */
    private fun maybeSwitchBackAfterSelect() {
        if (longPressPrevTool != null) return
        if (tool != Tool.SELECT || !configFor(Tool.SELECT).switchBackAfterSelect) return
        val back = toolBeforeSelect ?: return
        if (back.isStroke) setTool(back)
    }

    // --- SELECT / BAND ---

    /** The single selected line/arrow whose two endpoints are its own resize handles, or null.
     *  Every other selection (single non-line, multi, mixed) uses the generic box handles. */
    private fun singleEndpointShape(): Selected? {
        val sel = selection.singleOrNull() ?: return null
        val item = sel.item
        return if (item is ShapeItem && item.shape.isEndpointShape) sel else null
    }

    /** Resize handles for the current selection in content space: a single line/arrow's two
     *  endpoint handles, else the eight handles of the oriented selection box. */
    private fun selectionResizeHandles(): List<ResizeHandle> {
        if (singleSpline() != null) return emptyList()
        val endpoint = singleEndpointShape()
        if (endpoint != null) return endpointHandles(endpoint)
        return selObb?.let { ResizeMath.obbHandles(it) } ?: emptyList()
    }

    /** A line/arrow's two endpoint handles, mapped from page space into content space. */
    private fun endpointHandles(sel: Selected): List<ResizeHandle> {
        val item = sel.item as? ShapeItem ?: return emptyList()
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return emptyList()
        return listOf(
            ResizeHandle(HandleId.START, state.fromPageSpace(sel.pageIndex, item.start)),
            ResizeHandle(HandleId.END, state.fromPageSpace(sel.pageIndex, item.end)),
        )
    }

    /** The single selected spline, whose control points are its handles, or null. */
    private fun singleSpline(): Selected? {
        val sel = selection.singleOrNull() ?: return null
        return if ((sel.item as? ShapeItem)?.shape == ShapeKind.SPLINE) sel else null
    }

    /** A spline's control points, mapped from page space into content space. */
    private fun splineHandles(sel: Selected): List<Pt> {
        val item = sel.item as? ShapeItem ?: return emptyList()
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return emptyList()
        return item.controlPoints().map { state.fromPageSpace(sel.pageIndex, it) }
    }

    /** Index of the spline control point under [content], or -1. */
    private fun hitSplinePoint(sel: Selected, content: Pt, tol: Double): Int {
        val pts = splineHandles(sel)
        val i = pts.indices.minByOrNull { pts[it].distanceTo(content) } ?: return -1
        return if (pts[i].distanceTo(content) <= tol) i else -1
    }

    /** The move grip under a selection too small to grab by its body, in content space, or null. */
    private fun selectionMoveGrip(): Pt? {
        val obb = selObb ?: return null
        return ResizeMath.obbMoveGrip(obb, state.zoom, ROTATE_ARM / state.zoom)
    }

    /** Strokes, shapes and images rotate; text doesn't. A mixed selection rotates only when every
     *  member is rotatable. */
    private fun selectionIsRotatable(): Boolean =
        selection.isNotEmpty() && selection.all { it.item is Stroke || it.item is ShapeItem || it.item is ImageItem }

    /** Rotate-grip centre (content space, no move offset), out past the oriented box's top edge, or
     *  null when the selection can't rotate or is a single line/arrow (reoriented by an endpoint). */
    private fun selectionRotatePoint(): Pt? {
        if (!selectionIsRotatable() || singleEndpointShape() != null || singleSpline() != null) return null
        val obb = selObb ?: return null
        return ResizeMath.obbRotateGrip(obb, ROTATE_ARM / state.zoom)
    }

    /** True when [content] lands on the active selection — a resize or rotate handle, or inside the
     *  oriented box. Lets a finger grab the selection even when finger-draw is off (off the
     *  selection the finger still pans). */
    private fun fingerHitsSelection(content: Pt): Boolean {
        if (selection.isEmpty()) return false
        val tol = HANDLE_HIT / state.zoom
        selectionRotatePoint()?.let { if (it.distanceTo(content) <= tol) return true }
        selectionMoveGrip()?.let { if (it.distanceTo(content) <= tol) return true }
        singleSpline()?.let { if (hitSplinePoint(it, content, tol) >= 0) return true }
        if (ResizeMath.hitHandle(selectionResizeHandles(), content, tol) != null) return true
        return selObb?.contains(content) == true
    }

    /** If [content] grabs a resize or rotate handle of the settled selection, begin that gesture
     *  and return true. Handles only; the caller handles an inside-the-bounds move. Shared by the
     *  select and lasso tools so both grab handles the same way. */
    private fun tryGrabSelectionHandle(content: Pt): Boolean {
        if (selection.isEmpty()) return false
        val tol = HANDLE_HIT / state.zoom
        selectionRotatePoint()?.let {
            if (it.distanceTo(content) <= tol) {
                beginTransform(null, content)
                return true
            }
        }
        selectionMoveGrip()?.let {
            if (it.distanceTo(content) <= tol) {
                beginMove(content)
                return true
            }
        }
        singleSpline()?.let { sp ->
            val i = hitSplinePoint(sp, content, tol)
            if (i < 0) return false
            beginResize(sp, HandleId.START)
            resizePointIndex = i
            resizeOldSnap = sp.item.snapshotGeometry()
            return true
        }
        val endpoint = singleEndpointShape()
        if (endpoint != null) {
            val id = ResizeMath.hitHandle(endpointHandles(endpoint), content, tol) ?: return false
            beginResize(endpoint, id)
            return true
        }
        val obb = selObb ?: return false
        val id = ResizeMath.hitHandle(ResizeMath.obbHandles(obb), content, tol) ?: return false
        beginTransform(id, content)
        return true
    }

    /** A finger tap with the select tool picks the item under it; a drag still pans. */
    private fun fingerTapSelect(content: Pt): Boolean {
        val pageIndex = state.pageIndexAtContent(content) ?: return false
        if (pageIndex !in state.drawablePageRange()) return false
        val local = state.toPageSpace(pageIndex, content)
        val slop = TAP_SLOP / state.zoom
        val items = state.document.pages[pageIndex].items
        val hit = items.lastOrNull { !it.locked && it.contains(local) }
            ?: items.lastOrNull { !it.locked && it.intersectsCircle(local.x, local.y, slop) }
            ?: return false
        setSelection(listOf(Selected(pageIndex, hit)))
        refreshSelectionMenu()
        return true
    }

    private fun beginSelect(content: Pt) {
        if (isTableEditing()) {
            if (beginTableEdit(content)) return
            setTableEditing(false) // tapped outside the table: leave edit mode, then select normally
        }
        if (tryGrabSelectionHandle(content)) return
        // Inside the settled selection the press moves it, whatever it landed on. Hit-testing
        // first would re-pick the stroke under the finger, and you meant to drag what is
        // selected, not to select what happens to sit inside it.
        if (selObb?.contains(content) == true) {
            beginMove(content)
            return
        }
        val pageIndex = state.pageIndexAtContent(content)
        if (pageIndex != null) {
            val local = state.toPageSpace(pageIndex, content)
            val hit = state.document.pages[pageIndex].items.lastOrNull { !it.locked && it.contains(local) }
            if (hit != null) {
                if (selection.none { it.item === hit }) setSelection(listOf(Selected(pageIndex, hit)))
                beginMove(content)
                return
            }
        }
        clearSelection()
        mode = PointerMode.BAND
        moveOrigin = content
        bandRect = Rect.fromPoints(content, content)
    }

    private fun extendBand(content: Pt) {
        bandRect = Rect.fromPoints(moveOrigin, content)
        requestRender()
    }

    private fun endBand() {
        bandRect?.let { band ->
            val drawable = state.drawablePageRange()
            setSelection(
                SelectionMath.bandMembers(state.document.pages, state.pageRects, band, configFor(Tool.SELECT).selectWholeItems) { i, r ->
                    state.fromPageSpaceRect(i, r)
                }.filter { it.pageIndex in drawable }, // hidden paginated neighbours don't select
            )
        }
        bandRect = null
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    // --- LASSO ---

    private fun beginLasso(content: Pt) {
        // A tap on the settled selection grabs it (a resize/rotate handle, or a move when inside the
        // oriented box) instead of starting a fresh lasso.
        if (tryGrabSelectionHandle(content)) return
        if (selObb?.contains(content) == true) {
            beginMove(content)
            return
        }
        clearSelection()
        lassoPoints.clear()
        lassoPoints.add(content)
        mode = PointerMode.LASSO_DRAW
    }

    private fun extendLasso(content: Pt) {
        lassoPoints.add(content)
        requestRender()
    }

    private fun endLasso() {
        if (lassoPoints.size >= 3) {
            val drawable = state.drawablePageRange()
            val members = SelectionMath.lassoMembers(state.document.pages, state.pageRects, lassoPoints, configFor(Tool.LASSO).selectWholeItems) { i, p ->
                state.fromPageSpace(i, p)
            }.filter { it.pageIndex in drawable } // hidden paginated neighbours don't select
            if (members.isEmpty()) {
                clearSelection()
            } else {
                setSelection(members)
            }
        } else {
            clearSelection()
        }
        lassoPoints.clear()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    // --- SCREENSHOT ---

    private fun beginScreenshot(content: Pt) {
        clearScreenshot() // drop any previous frozen capture + its menu
        mode = PointerMode.SHOT
        screenshotOrigin = content
        screenshotRect = Rect.fromPoints(content, content)
    }

    private fun extendScreenshot(content: Pt) {
        screenshotRect = Rect.fromPoints(screenshotOrigin, content)
        requestRender()
    }

    private fun endScreenshot() {
        mode = PointerMode.IDLE
        val rect = screenshotRect
        // A tap or a sliver isn't a capture: drop it. Otherwise freeze the rect and show its menu.
        if (rect == null || rect.w < SHOT_MIN || rect.h < SHOT_MIN) clearScreenshot()
        else refreshScreenshotMenu()
        requestRender()
    }

    /** Drop the capture rectangle and hide its menu (after a copy, a tool change, or a re-drag). */
    fun clearScreenshot() {
        if (screenshotRect == null) return
        screenshotRect = null
        onScreenshotMenu(null)
        requestRender()
    }

    /** After a capture is copied, return to the previous pen, mirroring the eraser's switch-back. */
    fun switchBackAfterScreenshot() {
        if (tool != Tool.SCREENSHOT) return
        val back = toolBeforeScreenshot ?: return
        if (back.isStroke) setTool(back)
    }

    private fun refreshScreenshotMenu() {
        val rect = screenshotRect
        onScreenshotMenu(if (rect != null && mode == PointerMode.IDLE) screenshotRectViewport(rect) else null)
    }

    private fun screenshotRectViewport(rect: Rect): Rect {
        val tl = state.contentToViewport(rect.topLeft)
        val br = state.contentToViewport(Pt(rect.right, rect.bottom))
        return Rect.fromPoints(tl, br)
    }

    // --- MOVE ---

    private fun beginMove(content: Pt) {
        mode = PointerMode.MOVE
        moveOrigin = content
        moveOffset = Pt.ZERO
        onSelectionMenu(null) // hide while dragging
    }

    private fun extendMove(content: Pt) {
        moveOffset = content - moveOrigin
        requestRender()
    }

    private fun endMove(content: Pt) {
        moveOffset = content - moveOrigin
        val moved = abs(moveOffset.x) > MOVE_EPS || abs(moveOffset.y) > MOVE_EPS
        if (moved) {
            val items = selection.map { it.item }
            // Items hold page-space geometry: rotate the on-screen offset into page space.
            val local = state.vectorToPageSpace(moveOffset)
            for (item in items) item.translate(local.x, local.y)
            selObb = selObb?.translate(moveOffset.x, moveOffset.y)
            val move: Command = MoveItems(items, local.x, local.y)
            val transfer = reassignSelectionPages()
            history.push(if (transfer == null) move else CompositeCommand(listOf(move, transfer)))
            state.document.dirty = true
            // Moved items stay lifted (drawn live in the overlay), so the ink cache — which
            // already excludes them — needs no repair; it is repainted at their final spot
            // when the selection is later cleared.
            onContentChanged()
        }
        moveOffset = Pt.ZERO
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
        if (moved) maybeSwitchBackAfterSelect()
    }

    // --- RESIZE ---

    private fun beginResize(sel: Selected, handle: HandleId) {
        resizeItem = sel.item
        resizeHandle = handle
        resizePageIndex = sel.pageIndex
        resizeOldGeom = (sel.item as Resizable).geometry()
        mode = PointerMode.RESIZE
        onSelectionMenu(null) // hide while resizing
    }

    private fun extendResize(content: Pt) {
        val item = resizeItem ?: return
        val handle = resizeHandle ?: return
        if (state.pageRects.getOrNull(resizePageIndex) == null) return
        val local = state.toPageSpace(resizePageIndex, content)
        when (item) {
            is ImageItem -> item.setGeometry(RectHandle(ResizeMath.resizeImage(item.rect, handle, local)))
            is TextItem -> {
                // Resize against the displayed bounds (grown-to-fit height) so handles track the box.
                val (pos, w, h) = ResizeMath.resizeText(item.pos, item.width, item.bounds().h, handle, local)
                item.setGeometry(TextHandle(pos, w, h))
            }
            is ShapeItem -> if (resizePointIndex >= 0) item.moveControlPoint(resizePointIndex, local) else {
                val (s, en) = when {
                    item.shape.isEndpointShape -> ResizeMath.resizeOpenShape(item.start, item.end, handle, local)
                    item.shape == ShapeKind.CIRCLE -> ResizeMath.resizeSquareShape(item.start, item.end, handle, local)
                    else -> ResizeMath.resizeClosedShape(item.start, item.end, handle, local)
                }
                item.setGeometry(ShapeHandle(s, en))
            }
        }
        requestRender()
    }

    private fun endResize() {
        val item = resizeItem as? Resizable
        val old = resizeOldGeom
        var changed = false
        val oldSnap = resizeOldSnap
        if (resizePointIndex >= 0 && oldSnap != null) {
            val moved = resizeItem!!
            val new = moved.snapshotGeometry()
            if (new != oldSnap) {
                changed = true
                history.push(TransformItems(listOf(moved), listOf(oldSnap), listOf(new)))
                state.document.dirty = true
                onContentChanged()
            }
        } else if (item != null && old != null) {
            val new = item.geometry()
            if (new != old) {
                changed = true
                history.push(ResizeItem(item, old, new))
                state.document.dirty = true
                // The resized item stays lifted (overlay-drawn); the ink cache it was already
                // lifted out of needs no repair — it is repainted at its new size on deselect.
                onContentChanged()
            }
        }
        resizeItem = null
        resizeHandle = null
        resizeOldGeom = null
        resizePointIndex = -1
        resizeOldSnap = null
        // The endpoint resize (a single line/arrow) reshapes the box; refit it upright.
        selObb = selectionObb()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
        if (changed) maybeSwitchBackAfterSelect()
    }

    // --- TABLE_LINE (drag an interior row/column boundary of the selected table) ---

    /** If [content] grabs an interior grid line of the single selected table, start dragging it. */
    private fun tryGrabTableLine(content: Pt): Boolean {
        val sel = selection.singleOrNull() ?: return false
        val t = sel.item as? TableItem ?: return false
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return false
        val local = state.toPageSpace(sel.pageIndex, content)
        val tol = HANDLE_HIT / state.zoom
        val col = t.columnLineNear(local, tol)
        val row = t.rowLineNear(local, tol)
        if (col < 0 && row < 0) return false
        tableLineItem = t
        tableLinePageIndex = sel.pageIndex
        tableLineBefore = t.snapshotGeometry()
        // When a press is near both a column and a row line, take the nearer one.
        if (col >= 0 && (row < 0 || abs(local.x - t.colX(col)) <= abs(local.y - t.rowY(row)))) {
            tableLineIsColumn = true; tableLineIndex = col
        } else {
            tableLineIsColumn = false; tableLineIndex = row
        }
        mode = PointerMode.TABLE_LINE
        onSelectionMenu(null)
        return true
    }

    private fun extendTableLine(content: Pt) {
        val t = tableLineItem ?: return
        if (state.pageRects.getOrNull(tableLinePageIndex) == null) return
        val local = state.toPageSpace(tableLinePageIndex, content)
        if (tableLineIsColumn) t.moveColumnLine(tableLineIndex, local.x) else t.moveRowLine(tableLineIndex, local.y)
        requestRender()
    }

    private fun endTableLine() {
        val t = tableLineItem
        val before = tableLineBefore
        if (t != null && before != null && t.snapshotGeometry() != before) {
            history.push(TransformItems(listOf(t), listOf(before), listOf(t.snapshotGeometry())))
            state.document.dirty = true
            onContentChanged()
        }
        tableLineItem = null
        tableLineBefore = null
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    /** Page-local geometry of the table edit chrome: per-line handle squares and the four
     *  add/remove buttons (columns below the bottom edge, rows past the right edge). */
    class TableChrome(
        val colSeps: List<Rect>,
        val rowSeps: List<Rect>,
        val colMinus: Rect, val colPlus: Rect,
        val rowMinus: Rect, val rowPlus: Rect,
    )

    private fun tableChrome(t: TableItem): TableChrome {
        val side = HANDLE_SIZE / state.zoom
        val bs = HANDLE_SIZE * 1.2 / state.zoom
        val arm = HANDLE_SIZE * 1.7 / state.zoom
        val gap = bs * 0.75
        fun sq(cx: Double, cy: Double, s: Double) = Rect(cx - s / 2, cy - s / 2, s, s)
        val cx = t.rect.centerX
        val cy = t.rect.centerY
        return TableChrome(
            colSeps = (1 until t.cols).map { sq(t.colX(it), t.rect.top, side) },
            rowSeps = (1 until t.rows).map { sq(t.rect.right, t.rowY(it), side) },
            colMinus = sq(cx - gap, t.rect.bottom + arm, bs), colPlus = sq(cx + gap, t.rect.bottom + arm, bs),
            rowPlus = sq(t.rect.right + arm, cy - gap, bs), rowMinus = sq(t.rect.right + arm, cy + gap, bs),
        )
    }

    /** Handle a press in table edit mode: an add/remove button, an interior line drag, or a move.
     *  Returns false when the press is outside the table and its chrome (so the caller can exit). */
    private fun beginTableEdit(content: Pt): Boolean {
        val sel = selection.singleOrNull() ?: return false
        val t = sel.item as? TableItem ?: return false
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return false
        val local = state.toPageSpace(sel.pageIndex, content)
        val chrome = tableChrome(t)
        when {
            chrome.colPlus.contains(local) -> { editSelectedTable { it.addColumn() }; return true }
            chrome.colMinus.contains(local) -> { editSelectedTable { it.removeColumn() }; return true }
            chrome.rowPlus.contains(local) -> { editSelectedTable { it.addRow() }; return true }
            chrome.rowMinus.contains(local) -> { editSelectedTable { it.removeRow() }; return true }
        }
        if (tryGrabTableLine(content)) return true
        if (t.rect.contains(local)) { beginMove(content); return true }
        return false
    }

    // --- CROP (adjust an image's crop rectangle, then bake it) ---

    /** Enter crop mode for the single selected image; the crop rect starts as the whole image. */
    fun beginCrop() {
        val sel = selection.singleOrNull() ?: return
        val img = sel.item as? ImageItem ?: return
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return
        cropItem = img
        cropPageIndex = sel.pageIndex
        cropRect = img.rect
        cropHandle = null
        cropMoving = false
        mode = PointerMode.CROP
        onSelectionMenu(null)
        refreshCropMenu()
        requestRender()
    }

    /** The image being cropped and its current (page-local) crop rect, for the host to bake. */
    fun cropTarget(): Pair<ImageItem, Rect>? = cropItem?.let { it to cropRect }

    /** Finish cropping: swap in the baked [newImage] at [newRect] (undoable), or just exit if null. */
    fun finishCrop(newImage: ImageData?, newRect: Rect?) {
        val img = cropItem
        if (img != null && newImage != null && newRect != null) {
            history.push(ReplaceImage(img, img.image, img.rect, newImage, newRect))
            img.image = newImage
            img.rect = newRect
            state.document.dirty = true
            onContentChanged()
        }
        exitCrop(reselect = img)
    }

    fun cancelCrop() = exitCrop(reselect = cropItem)

    private fun exitCrop(reselect: ImageItem?) {
        val pi = cropPageIndex
        cropItem = null
        cropHandle = null
        cropMoving = false
        mode = PointerMode.IDLE
        onCropMenu(null)
        if (reselect != null && pi in state.document.pages.indices) setSelection(listOf(Selected(pi, reselect)))
        else refreshSelectionMenu()
        requestRender()
    }

    private fun refreshCropMenu() {
        val img = cropItem ?: return onCropMenu(null)
        onCropMenu(screenshotRectViewport(state.fromPageSpaceRect(cropPageIndex, img.bounds())))
    }

    private fun beginCropDrag(content: Pt) {
        val img = cropItem ?: return
        if (state.pageRects.getOrNull(cropPageIndex) == null) return
        // Work in the image's upright frame so a rotated image crops correctly.
        val local = img.unturn(state.toPageSpace(cropPageIndex, content))
        val tol = HANDLE_HIT / state.zoom
        cropHandle = ResizeMath.hitHandle(ResizeMath.boxHandles(cropRect), local, tol)
        cropMoving = cropHandle == null && cropRect.contains(local)
        cropMoveGrab = local
        onCropMenu(null) // hide the bar while dragging
    }

    private fun extendCrop(content: Pt) {
        val img = cropItem ?: return
        if (state.pageRects.getOrNull(cropPageIndex) == null) return
        val local = img.unturn(state.toPageSpace(cropPageIndex, content))
        val bounds = img.rect
        when {
            cropHandle != null -> {
                val (pos, w, h) = ResizeMath.resizeText(cropRect.topLeft, cropRect.w, cropRect.h, cropHandle!!, local)
                cropRect = clampRectInside(Rect(pos.x, pos.y, w, h), bounds)
            }
            cropMoving -> {
                val dx = local.x - cropMoveGrab.x
                val dy = local.y - cropMoveGrab.y
                cropMoveGrab = local
                cropRect = clampRectInside(cropRect.translate(dx, dy), bounds)
            }
        }
        requestRender()
    }

    private fun endCrop() {
        cropHandle = null
        cropMoving = false
        refreshCropMenu()
        requestRender()
    }

    /** Keep [r] within [bounds] (image rect): clamp size then position; never smaller than a min cell. */
    private fun clampRectInside(r: Rect, bounds: Rect): Rect {
        val minW = minOf(24.0, bounds.w)
        val minH = minOf(24.0, bounds.h)
        val w = r.w.coerceIn(minW, bounds.w)
        val h = r.h.coerceIn(minH, bounds.h)
        val x = r.x.coerceIn(bounds.left, bounds.right - w)
        val y = r.y.coerceIn(bounds.top, bounds.bottom - h)
        return Rect(x, y, w, h)
    }

    /** Draw the crop overlay (content space): dim the trimmed border, outline the crop rect + handles.
     *  Everything is built in the image's upright frame then turned by its angle, so the overlay
     *  tracks a rotated image. */
    private fun drawCrop(r: Renderer) {
        val img = cropItem ?: return
        val pi = cropPageIndex
        if (state.pageRects.getOrNull(pi) == null) return
        // Map an upright-frame (page-space) point through the image's turn into content space.
        fun toContent(p: Pt): Pt = state.fromPageSpace(pi, img.turn(p))
        fun quad(rect: Rect): List<Pt> = listOf(
            toContent(Pt(rect.left, rect.top)), toContent(Pt(rect.right, rect.top)),
            toContent(Pt(rect.right, rect.bottom)), toContent(Pt(rect.left, rect.bottom)),
        )
        val outer = img.rect
        val c = cropRect
        val dim = Rgba(0, 0, 0, 120)
        // Four dim bands between the image edge and the crop rect, as turned quads.
        if (c.top > outer.top) r.fillPolygon(quad(Rect(outer.left, outer.top, outer.w, c.top - outer.top)), dim)
        if (c.bottom < outer.bottom) r.fillPolygon(quad(Rect(outer.left, c.bottom, outer.w, outer.bottom - c.bottom)), dim)
        if (c.left > outer.left) r.fillPolygon(quad(Rect(outer.left, c.top, c.left - outer.left, c.h)), dim)
        if (c.right < outer.right) r.fillPolygon(quad(Rect(c.right, c.top, outer.right - c.right, c.h)), dim)
        val sel = com.xnotes.core.model.Rgba.SELECTION
        r.strokePolygon(quad(c), Pen(sel, 1.6, cosmetic = true))
        val side = HANDLE_SIZE / state.zoom
        for (h in ResizeMath.boxHandles(c)) {
            val hc = toContent(h.content)
            r.fillRect(Rect(hc.x - side / 2, hc.y - side / 2, side, side), sel)
        }
    }

    /** Draw the table edit chrome (content space): per-line handle squares and the +/- buttons. */
    private fun drawTableEditChrome(r: Renderer) {
        val t = singleSelectedTable() ?: return
        val pi = selection.firstOrNull()?.pageIndex ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        val chrome = tableChrome(t)
        val sel = com.xnotes.core.model.Rgba.SELECTION
        val white = Rgba(255, 255, 255, 255)
        fun square(localRect: Rect, color: Rgba) = r.fillRect(state.fromPageSpaceRect(pi, localRect), color)
        fun glyph(localRect: Rect, plus: Boolean) {
            val c = state.fromPageSpaceRect(pi, localRect)
            val pad = c.w * 0.28
            val pen = Pen(white, 1.6, cosmetic = true)
            r.strokePolyline(listOf(Pt(c.left + pad, c.centerY), Pt(c.right - pad, c.centerY)), pen)
            if (plus) r.strokePolyline(listOf(Pt(c.centerX, c.top + pad), Pt(c.centerX, c.bottom - pad)), pen)
        }
        chrome.colSeps.forEach { square(it, sel) }
        chrome.rowSeps.forEach { square(it, sel) }
        square(chrome.colPlus, sel); glyph(chrome.colPlus, true)
        square(chrome.colMinus, sel); glyph(chrome.colMinus, false)
        square(chrome.rowPlus, sel); glyph(chrome.rowPlus, true)
        square(chrome.rowMinus, sel); glyph(chrome.rowMinus, false)
    }

    // --- TRANSFORM (generic resize + rotate) ---

    /** Begin a resize ([handle] non-null) or rotate ([handle] null) of the whole selection. Snapshots
     *  every member and the oriented box so each move can restore-then-rebake without drift. */
    private fun beginTransform(handle: HandleId?, content: Pt) {
        txItems = selection.toList()
        txSnaps = txItems.map { it.item.snapshotGeometry() }
        txStartObb = selObb
        txHandle = handle
        if (handle == null) {
            val c = selObb?.center ?: content
            txCenter = c
            txGrabAngle = atan2(content.y - c.y, content.x - c.x)
            txStartAngle = selObb?.angle ?: 0.0
        }
        mode = PointerMode.TRANSFORM
        onSelectionMenu(null) // hide while transforming
    }

    private fun extendTransform(content: Pt) {
        val obb0 = txStartObb ?: return
        // Restore every member to its gesture-start geometry, then bake the current transform so the
        // result is a pure function of the pointer (no per-frame compounding, e.g. of a rotation).
        txItems.forEachIndexed { i, sel -> sel.item.restoreGeometry(txSnaps[i]) }
        val world: Affine
        val handle = txHandle
        if (handle != null) {
            val res = ResizeMath.obbResize(obb0, handle, content)
            selObb = res.obb
            world = res.transform
        } else {
            val raw = atan2(content.y - txCenter.y, content.x - txCenter.x) - txGrabAngle
            // A sticky zone around each 90-degree step, not a hard quantiser: the box clings to the
            // nearest quarter turn only while within ROTATE_SNAP_STICK of it, and turns freely otherwise.
            val theta = if (snapRotation90) {
                val quarter = Math.PI / 2.0
                val target = txStartAngle + raw
                val nearest = Math.round(target / quarter) * quarter
                if (Math.abs(target - nearest) <= ROTATE_SNAP_STICK_RAD) nearest - txStartAngle else raw
            } else {
                raw
            }
            selObb = obb0.copy(angle = txStartAngle + theta)
            world = Affine.rotateAbout(txCenter, theta)
        }
        // Items hold page-space geometry, so express the content-space transform per page
        // (a translation shift, plus the display rotation when the view is rotated).
        for (sel in txItems) {
            if (state.pageRects.getOrNull(sel.pageIndex) == null) continue
            sel.item.applyTransform(state.affineToPageSpace(sel.pageIndex, world))
        }
        requestRender()
    }

    private fun endTransform() {
        val items = txItems.map { it.item }
        var changed = false
        if (items.isNotEmpty()) {
            val after = items.map { it.snapshotGeometry() }
            if (after != txSnaps) {
                changed = true
                val tx: Command = TransformItems(items, txSnaps, after)
                val transfer = reassignSelectionPages()
                history.push(if (transfer == null) tx else CompositeCommand(listOf(tx, transfer)))
                state.document.dirty = true
                // Members stay lifted (overlay-drawn); the ink cache they were lifted out of needs
                // no repair — it is repainted at their new geometry on deselect.
                onContentChanged()
            }
        }
        txItems = emptyList()
        txSnaps = emptyList()
        txStartObb = null
        txHandle = null
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
        if (changed) maybeSwitchBackAfterSelect()
    }

    // --- SHAPE ---

    private fun beginShape(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        // Off-selection press with the shape tool: dismiss first (a tap makes no shape; endShape's
        // min-drag gate drops it), so dragging out a new shape also clears the old selection.
        clearSelection()
        val startLocal = state.toPageSpace(pageIndex, content)
        val kind = shapeConfig.shape
        val fill = if (shapeConfig.fill && kind.isClosed) inkColor.scaleAlpha(shapeConfig.fillAlpha) else null
        pendingShape = ShapeItem(
            kind, startLocal, startLocal, inkColor, shapeConfig.strokeWidth * SHAPE_PEN_PARITY, fill,
            shapeConfig.neon, shapeConfig.neonStrength,
            dashed = shapeConfig.dashed, dashLength = shapeConfig.dashLength, dashGap = shapeConfig.dashGap,
            function = if (kind == ShapeKind.FUNCTION) FunctionSpec.preset(shapeConfig.function) else null,
        )
        shapePageIndex = pageIndex
        mode = PointerMode.SHAPE
        requestRender()
    }

    private fun extendShape(content: Pt) {
        val shape = pendingShape ?: return
        val pi = shapePageIndex ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        val raw = state.toPageSpace(pi, content)
        shape.end = when {
            // Line/arrow: pin the dragged end flat when it lands near an axis.
            shape.shape.isEndpointShape -> snapAxisEndpoint(shape.start, raw)
            // Circle: keep the box square so it stays a perfect circle.
            shape.shape == ShapeKind.CIRCLE -> squareCorner(shape.start, raw)
            else -> raw
        }
        requestRender()
    }

    /** Constrain a dragged corner [p] to a square box anchored at [anchor] (the perfect-circle shape). */
    private fun squareCorner(anchor: Pt, p: Pt): Pt {
        val side = max(abs(p.x - anchor.x), abs(p.y - anchor.y))
        val sx = if (p.x >= anchor.x) 1.0 else -1.0
        val sy = if (p.y >= anchor.y) 1.0 else -1.0
        return Pt(anchor.x + sx * side, anchor.y + sy * side)
    }

    /** Snap a line/arrow's dragged endpoint to an exactly horizontal or vertical run from [anchor]
     *  when it lands within [SHAPE_AXIS_SNAP_DEG] of one (mirrors the recognizer's axis snap). */
    private fun snapAxisEndpoint(anchor: Pt, p: Pt): Pt {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        if (dx == 0.0 && dy == 0.0) return p
        val snap = Math.toRadians(SHAPE_AXIS_SNAP_DEG)
        val fromHoriz = atan2(abs(dy), abs(dx)) // 0 = horizontal, PI/2 = vertical
        return when {
            fromHoriz <= snap -> Pt(p.x, anchor.y)
            fromHoriz >= Math.PI / 2.0 - snap -> Pt(anchor.x, p.y)
            else -> p
        }
    }

    private fun endShape() {
        val shape = pendingShape
        val pi = shapePageIndex
        if (shape != null && pi != null && shape.start.distanceTo(shape.end) > SHAPE_MIN_DRAG) {
            val page = state.document.pages[pi]
            page.items.add(shape)
            state.appendToCache(page, shape)
            history.push(AddItem(page, shape))
            state.document.dirty = true
            onContentChanged()
            selectSingle(pi, shape)
        }
        pendingShape = null
        shapePageIndex = null
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- TABLE (drawn) ---

    private fun beginTable(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        // Off-selection press with the table tool: like the shape tool, drop the current selection so
        // dragging out a new table also clears the old one (a tap makes nothing; endTable gates it).
        clearSelection()
        val d = tableToolDefaults()
        tableDrawStart = state.toPageSpace(pageIndex, content)
        pendingTable = TableItem.create(Rect(tableDrawStart.x, tableDrawStart.y, 0.0, 0.0), d.cols, d.rows, inkColor, d.width)
        tableDrawPageIndex = pageIndex
        mode = PointerMode.TABLE_DRAW
        requestRender()
    }

    private fun extendTable(content: Pt) {
        val table = pendingTable ?: return
        val pi = tableDrawPageIndex ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        table.rect = Rect.fromPoints(tableDrawStart, state.toPageSpace(pi, content))
        requestRender()
    }

    private fun endTable() {
        val table = pendingTable
        val pi = tableDrawPageIndex
        if (table != null && pi != null && table.rect.w > SHAPE_MIN_DRAG && table.rect.h > SHAPE_MIN_DRAG) {
            val page = state.document.pages[pi]
            page.items.add(table)
            state.appendToCache(page, table)
            history.push(AddItem(page, table))
            state.document.dirty = true
            onContentChanged()
            selectSingle(pi, table)
        }
        pendingTable = null
        tableDrawPageIndex = null
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- TEXT ---

    /**
     * A press with the Text tool (and no edit already open). Tapping an existing legacy
     * box is the text box tool's business; here everything routes to the inline-flow
     * caret (tap = place caret / toggle checkbox / fill empty lines, drag = select).
     */
    private fun beginTextGesture(content: Pt, viewport: Pt) {
        clearSelection()
        mode = PointerMode.FLOW_TEXT
        flowText?.pressAt(content, viewport)
        requestRender()
    }

    /**
     * A press with the Text box tool (and no edit already open). Tapping an existing box
     * edits it; otherwise this begins a tap-or-drag to create a new one ([endTextDrag]
     * decides which).
     */
    private fun beginTextBoxGesture(content: Pt) {
        val pi = state.pageIndexAtContent(content) ?: return
        val local = state.toPageSpace(pi, content)
        val page = state.document.pages[pi]
        val existing = page.items.lastOrNull { it is TextItem && it.contains(local) } as? TextItem
        if (existing != null) {
            // A formula is edited as LaTeX from its selection menu, so a tap selects it instead.
            if (existing.math) {
                selectSingle(pi, existing)
                mode = PointerMode.IDLE
                return
            }
            startEditing(existing, pi, isNew = false)
            return
        }
        clearSelection()
        mode = PointerMode.TEXT_DRAG
        textDragPageIndex = pi
        textDragStart = content
        textDragRect = null
        requestRender()
    }

    private fun extendTextDrag(content: Pt) {
        textDragRect = Rect.fromPoints(textDragStart, content)
        requestRender()
    }

    /** Finish a tap-or-drag: a real drag (either axis) sizes the box; a tap makes a default one. */
    private fun endTextDrag(content: Pt) {
        val pi = textDragPageIndex
        textDragRect = null
        mode = PointerMode.IDLE
        if (state.pageRects.getOrNull(pi) == null) return
        val page = state.document.pages[pi]
        val startLocal = state.toPageSpace(pi, textDragStart)
        val rect = Rect.fromPoints(startLocal, state.toPageSpace(pi, content))
        val draggedX = rect.w * state.zoom >= TEXT_DRAG_SLOP
        val draggedY = rect.h * state.zoom >= TEXT_DRAG_SLOP
        // Measured to the paper's right edge, margins included: a box may be dropped in one.
        val pageRight = state.footprint(page).right
        val item = if (draggedX || draggedY) {
            // Use the drawn rectangle for whichever axis was actually dragged.
            val left = if (draggedX) rect.left else startLocal.x
            val maxW = (pageRight - left - 8.0).coerceAtLeast(40.0)
            val w = if (draggedX) rect.w.coerceIn(40.0, maxW) else defaultTextWidth(pageRight, left)
            val h = if (draggedY) rect.h else 0.0
            newTextItem(Pt(left, rect.top), w, h)
        } else {
            newTextItem(startLocal, defaultTextWidth(pageRight, startLocal.x), 0.0)
        }
        startEditing(item, pi, isNew = true)
    }

    private fun defaultTextWidth(pageRight: Double, left: Double): Double =
        (pageRight - left - 14.0).coerceIn(80.0, 300.0)

    private fun newTextItem(pos: Pt, width: Double, height: Double): TextItem =
        TextItem(pos, width, height, "", inkColor, textPointSize, textFace, measurer = textMeasurer)

    /** Open the in-place editor on [item] (a new draft, or an existing box being re-edited). */
    private fun startEditing(item: TextItem, pi: Int, isNew: Boolean) {
        editingText = item
        editingIsNew = isNew
        editingOldText = item.text
        editingPageIndex = pi
        // The style bar / next new box follow the box being edited.
        textFace = item.face
        textPointSize = item.pointSize
        // An existing box is lifted out of the cache (isLiftedItem) while edited, so only the field
        // shows it. Repair just its region in place — a full invalidatePage flickered the ink layer.
        if (!isNew) repairTextRegion(state.document.pages[pi], item)
        onTextEditStart(editingField())
        requestRender()
    }

    /** Keep the model in sync with the live editor field (for auto-grow / commit). */
    fun updateEditingText(text: String) {
        editingText?.text = text
    }

    /**
     * Pan the document while a text edit stays open: a one-finger drag over the editor scrolls the
     * page (the overlay re-tracks the box through [editingField]) instead of scrolling the field's
     * own text. [dxFinger]/[dyFinger] are viewport-space finger deltas; the content follows the
     * finger, as in [extendPan]. A plain drag with no fling/overscroll.
     */
    fun panWhileEditing(dxFinger: Double, dyFinger: Double) {
        if (editingText == null) return
        state.scrollBy(-dxFinger, -dyFinger)
        onViewChanged()
        requestRender()
    }

    /** Current on-screen geometry of the editor field, or null when not editing. */
    fun editingField(): EditingField? {
        val item = editingText ?: return null
        if (state.pageRects.getOrNull(editingPageIndex) == null) return null
        // Anchor at the box's page-space top-left corner; a rotated view spins the overlay
        // around that anchor (EditingField.rotation) so it stays glued to the baked text.
        val topLeft = state.contentToViewport(state.fromPageSpace(editingPageIndex, item.pos))
        return EditingField(
            x = topLeft.x,
            y = topLeft.y,
            width = item.width,
            height = item.bounds().h,
            fontPx = item.pointSize * com.xnotes.platform.AndroidText.POINTS_TO_PX,
            zoom = state.zoom,
            face = item.face,
            rgba = item.rgba,
            text = item.text,
            rotation = state.rotationDeg,
            bold = item.bold,
            italic = item.italic,
            underline = item.underline,
            strike = item.strike,
            align = item.align,
        )
    }

    /**
     * Commit (tap outside / Escape / done / tool switch) using the model's current text.
     * An empty box is *deleted* (a new draft is simply dropped; an existing box is removed);
     * a box with content is kept and its change recorded. The single source of truth for
     * ending an edit — the field no longer commits itself, so there is no double-commit.
     */
    fun commitTextEdit(finalText: String? = null, restoreTool: Boolean = false) {
        val item = editingText ?: return
        finalText?.let { item.text = it }
        val pi = editingPageIndex
        val page = state.document.pages.getOrNull(pi)
        val empty = item.text.trim().isEmpty()
        if (editingIsNew) {
            if (!empty && page != null) {
                page.items.add(item)
                history.push(AddItem(page, item))
                state.document.dirty = true
                onContentChanged()
            }
        } else if (page != null) {
            if (empty) {
                page.items.remove(item)
                history.push(EraseItems(listOf(page to item)))
                state.document.dirty = true
                onContentChanged()
            } else if (item.text != editingOldText) {
                history.push(EditText(item, editingOldText, item.text))
                state.document.dirty = true
                onContentChanged()
            }
        }
        editingText = null
        editingIsNew = false
        editingPageIndex = -1
        editingOldText = ""
        // Repaint just the box's region in place (it is now unlifted, so it bakes back in) rather
        // than rebuilding the whole page — the same smart path selection uses, so no ink flicker.
        page?.let { repairTextRegion(it, item) }
        onTextEditEnd()
        requestRender()
        // Finishing an edit (tap outside / Escape / Back) re-arms whatever tool the text tool replaced,
        // so the pen/highlighter/pan is back without a manual switch. Skipped when the commit is itself
        // a tool switch (setTool passes restoreTool = false), which already arms the chosen tool.
        if (restoreTool) {
            val back = toolBeforeText
            toolBeforeText = null
            if (back != null && back != Tool.TEXT_BOX && tool == Tool.TEXT_BOX) setTool(back)
        }
    }

    /**
     * Repaint only the text box's region of the ink cache in place — the eraser/selection smart
     * path ([CanvasState.repairRegion]) — instead of a full-page rebuild, which flickered the whole
     * ink layer on every edit start/commit. The box is excluded while lifted (editing) and painted
     * back once unlifted, per isLiftedItem. Falls back to a rebuild only when there is no live cache.
     */
    private fun repairTextRegion(page: Page, box: TextItem) {
        if (!state.repairRegion(page, box.paintBounds().outset(REPAIR_PAD))) state.invalidatePage(page)
    }

    // --- text styling (driven by the floating style bar + the toolbar colour swatches) ---

    /** The text box currently being edited, or the lone selected one — what the style bar targets. */
    fun activeTextItem(): TextItem? = editingText ?: (selection.singleOrNull()?.item as? TextItem)

    /** Where to anchor the style bar and the box's current style, or null when no box is active. */
    fun computeTextBar(): TextBar? {
        val editing = editingText
        if (editing != null) {
            val f = editingField() ?: return null
            return TextBar(
                Rect(f.x, f.y, f.width * f.zoom, f.height * f.zoom), editing.face, editing.pointSize, editing.rgba,
                editing = true, bold = editing.bold, italic = editing.italic,
                underline = editing.underline, strike = editing.strike, align = editing.align,
            )
        }
        if (mode != PointerMode.IDLE) return null
        val sel = selection.singleOrNull()?.item as? TextItem ?: return null
        val rect = selectionBoundsViewport() ?: return null
        return TextBar(
            rect, sel.face, sel.pointSize, sel.rgba, editing = false,
            bold = sel.bold, italic = sel.italic, underline = sel.underline, strike = sel.strike, align = sel.align,
        )
    }

    fun toggleTextBold() = restyleActive { it.bold = !it.bold }
    fun toggleTextItalic() = restyleActive { it.italic = !it.italic }
    fun toggleTextUnderline() = restyleActive { it.underline = !it.underline }
    fun toggleTextStrike() = restyleActive { it.strike = !it.strike }

    /** Cycle a box's alignment left -> center -> right -> left. */
    fun cycleTextAlign() = restyleActive {
        it.align = when (it.align) {
            com.xnotes.core.pal.HAlign.LEFT -> com.xnotes.core.pal.HAlign.CENTER
            com.xnotes.core.pal.HAlign.CENTER -> com.xnotes.core.pal.HAlign.RIGHT
            com.xnotes.core.pal.HAlign.RIGHT -> com.xnotes.core.pal.HAlign.LEFT
        }
    }

    fun setTextFace(face: FontFace) {
        textFace = face
        restyleActive { it.face = face }
    }

    fun setTextPointSize(size: Double) {
        val s = size.coerceIn(TEXT_MIN_PT, TEXT_MAX_PT)
        textPointSize = s
        restyleActive { it.pointSize = s }
    }

    /** Set the ink colour (for new strokes/boxes) and recolour the active text box, if any. */
    fun pickInk(c: Rgba) {
        inkColor = c
        restyleActive { it.rgba = c }
    }

    /**
     * Apply a style change to the active text box. A *new draft* mutates directly (its final
     * style is captured by the AddItem on commit); a committed/selected box records a [RestyleText]
     * so it is undoable. Both editing and selected boxes are lifted, so a render shows the change.
     */
    private inline fun restyleActive(mutate: (TextItem) -> Unit) {
        val item = activeTextItem() ?: return
        val isDraft = item === editingText && editingIsNew
        val old = TextStyle.of(item)
        mutate(item)
        if (!isDraft && TextStyle.of(item) != old) {
            history.push(RestyleText(item, old, TextStyle.of(item)))
            state.document.dirty = true
            onContentChanged()
        }
        if (item === editingText) {
            onTextEditStart(editingField()) // refresh the live field's metrics/colour
        } else {
            refreshSelectionMenu() // a size change moved the box; re-anchor its menu
        }
        requestRender()
    }

    // --- LONG-PRESS GRAB ---

    private fun armLongPress(viewport: Pt, content: Pt, isFinger: Boolean) {
        cancelLongPress()
        // Long-press (grab an item, or the paste menu on empty space) is a finger-only gesture:
        // the stylus always draws, so resting it never grabs or pops a menu.
        if (!isFinger) return
        val grabEligible = tool.isStroke || tool == Tool.PAN || tool == Tool.SELECT ||
            tool == Tool.LASSO || tool == Tool.SHAPE || tool == Tool.TEXT || tool == Tool.TEXT_BOX
        val pageIndex = state.pageIndexAtContent(content)
        val hit = if (pageIndex != null) {
            val local = state.toPageSpace(pageIndex, content)
            state.document.pages[pageIndex].items.lastOrNull { it.contains(local) }
        } else {
            null
        }
        longPressStart = viewport
        longPressContent = content
        // A locked item cannot be picked up, so a held finger offers to release it instead. That is
        // the only way back: it is out of reach of the band, the lasso and every tap.
        longPressLocked = hit?.takeIf { it.locked }
        longPressCandidate =
            if (grabEligible && hit != null && !hit.locked) Selected(pageIndex!!, hit) else null
        // Arm to grab an item, to unlock one, or (on empty space) to open the paste menu when there
        // is content to paste.
        val showEmptyMenu = hit == null && (hasClipboardItems() || clipboardHasImage())
        if (longPressCandidate == null && longPressLocked == null && !showEmptyMenu) return
        val r = Runnable { triggerLongPress() }
        longPressRunnable = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun maybeCancelLongPress(viewport: Pt) {
        if (longPressRunnable != null && viewport.distanceTo(longPressStart) > LONG_PRESS_SLOP) cancelLongPress()
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
        // Abort the in-progress gesture (keep eraser removals); commit any text edit.
        commitTextEdit()
        liveStroke = null
        strokePageIndex = null
        pendingShape = null
        shapePageIndex = null
        bandRect = null
        lassoPoints.clear()
        if (candidate != null) {
            // Grab the item: switch to select, select it, and start a move.
            longPressPrevTool = tool
            tool = Tool.SELECT
            onToolChanged(tool)
            setSelection(listOf(candidate))
            beginMove(state.viewportToContent(longPressStart))
        } else {
            // Empty space, or a locked item: open the context menu at the press point.
            mode = PointerMode.IDLE
            onContextMenu(longPressStart, longPressContent, locked)
        }
        longPressLocked = null
        requestRender()
    }

    // --- selection management ---

    private fun setSelection(items: List<Selected>) {
        // Items whose lifted state flips: those leaving the old selection (repainted back
        // into the cache) and those entering it (lifted out of it). Update the selection
        // first so the in-place repair below sees the new lifted set.
        val touched = selection + items
        selection.clear()
        selection.addAll(items)
        setTableEditing(false) // a new selection always starts out of table edit mode
        // A fresh selection starts upright: its box is the items' AABB, angle 0.
        selObb = selectionObb()
        repairRegions(dirtyRegions(touched))
        onSelectionChanged(selection.isNotEmpty())
        requestRender()
    }

    /** Select just [item] on [pageIndex] (used right after inserting a table). */
    fun selectSingle(pageIndex: Int, item: CanvasItem) = setSelection(listOf(Selected(pageIndex, item)))

    /** The single selected table, or null. */
    fun singleSelectedTable(): TableItem? = selection.singleOrNull()?.item as? TableItem

    /** The single selected image, or null (enables the Crop action). */
    fun singleSelectedImage(): ImageItem? = selection.singleOrNull()?.item as? ImageItem

    /** The single selected text box, or null (enables the Edit action). */
    fun singleSelectedText(): TextItem? = selection.singleOrNull()?.item as? TextItem

    /** The single selected item of any kind, or null (backs the Add/Edit link action). */
    fun singleSelectedItem(): CanvasItem? = selection.singleOrNull()?.item

    /** Set or clear the single selected item's hyperlink. Not undoable (metadata, not geometry). */
    fun setSelectedLink(url: String?) {
        val item = selection.singleOrNull()?.item ?: return
        item.link = url
        state.document.dirty = true
    }

    /** Retype the selected formula box's LaTeX as one undoable edit; false (unchanged) when it will not set. */
    fun setSelectedMath(latex: String): Boolean {
        val item = singleSelectedText()?.takeIf { it.math } ?: return false
        val old = item.text
        item.text = latex
        if (item.mathBox() == null) {
            item.text = old
            return false
        }
        item.mathBox()?.let { item.width = it.width }
        history.push(EditText(item, old, latex))
        state.document.dirty = true
        selObb = selectionObb()
        onContentChanged()
        refreshSelectionMenu()
        requestRender()
        return true
    }

    /** Reopen the editor on the single selected text box (from the selection menu Edit action). */
    fun editSelectedText() {
        val sel = selection.singleOrNull() ?: return
        val item = sel.item as? TextItem ?: return
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return
        startEditing(item, sel.pageIndex, isNew = false)
    }

    /** The selected handwriting on one page, captured for an async convert-to-text. */
    class InkSelection(val pageIndex: Int, val strokes: List<Stroke>, val bounds: Rect)

    /**
     * The selected ink strokes on the primary selected page, or null when nothing selected is a
     * stroke. Snapshotted so the caller can run recognition off-thread while the selection stands.
     */
    fun inkSelection(): InkSelection? {
        if (selection.isEmpty()) return null
        val pi = selection.first().pageIndex
        val strokes = selection.filter { it.pageIndex == pi }.mapNotNull { it.item as? Stroke }
        if (strokes.isEmpty()) return null
        val bounds = strokes.map { it.bounds() }.reduce { a, b -> a.union(b) }
        return InkSelection(pi, strokes, bounds)
    }

    /**
     * Replace [sel]'s strokes with a text box holding [text], as one undoable step. The box sits at
     * the ink's top-left and wraps to its width. Strokes gone since the snapshot are simply skipped.
     */
    fun replaceInkWithText(sel: InkSelection, text: String) {
        val page = state.document.pages.getOrNull(sel.pageIndex) ?: return
        val strokes = sel.strokes.filter { s -> page.items.any { it === s } }
        if (strokes.isEmpty()) return
        val pageRight = state.footprint(page).right
        val maxW = (pageRight - sel.bounds.left - 8.0).coerceAtLeast(40.0)
        val width = sel.bounds.w.coerceIn(40.0, maxW)
        val box = TextItem(Pt(sel.bounds.left, sel.bounds.top), width, 0.0, text, inkColor, textPointSize, textFace, measurer = textMeasurer)
        swapInk(page, strokes, box)
    }

    /** Replace the selected handwriting with a formula box set about as tall as the writing was. */
    fun replaceInkWithMath(sel: InkSelection, latex: String) {
        val page = state.document.pages.getOrNull(sel.pageIndex) ?: return
        val strokes = sel.strokes.filter { s -> page.items.any { it === s } }
        if (strokes.isEmpty()) return
        val box = TextItem(Pt(sel.bounds.left, sel.bounds.top), sel.bounds.w, 0.0, latex, inkColor, textPointSize, textFace, measurer = textMeasurer, math = true)
        box.mathBox()?.let { m ->
            if (m.height > 0.0) box.pointSize = (textPointSize * sel.bounds.h / m.height).coerceIn(textPointSize, textPointSize * 6.0)
        }
        box.mathBox()?.let { box.width = it.width }
        swapInk(page, strokes, box)
    }

    private fun swapInk(page: Page, strokes: List<CanvasItem>, box: TextItem) {
        val removals = strokes.map { page to (it as CanvasItem) }
        for (s in strokes) page.items.remove(s)
        page.items.add(box)
        history.push(CompositeCommand(listOf(EraseItems(removals), AddItem(page, box))))
        state.document.dirty = true
        clearSelection()
        state.invalidatePage(page)
        onContentChanged()
        requestRender()
    }

    private var tableEditing = false

    /** In table edit mode: the resize box is replaced by add/remove buttons and per-line handles. */
    fun isTableEditing(): Boolean = tableEditing && singleSelectedTable() != null

    private fun setTableEditing(on: Boolean) {
        if (tableEditing == on) return
        tableEditing = on
        onTableEditingChanged(on)
    }

    /** Toggle table edit mode for the selected table (no-op if the selection isn't a single table). */
    fun toggleTableEditMode() {
        if (singleSelectedTable() == null) return
        setTableEditing(!tableEditing)
        refreshSelectionMenu()
        requestRender()
    }

    /** Apply [mutate] to the single selected table as one undoable edit (add/remove line, etc.). */
    fun editSelectedTable(mutate: (TableItem) -> Unit) {
        val t = singleSelectedTable() ?: return
        val before = t.snapshotGeometry()
        mutate(t)
        val after = t.snapshotGeometry()
        if (after == before) return
        history.push(TransformItems(listOf(t), listOf(before), listOf(after)))
        state.document.dirty = true
        selObb = selectionObb()
        onContentChanged()
        refreshSelectionMenu()
        requestRender()
    }

    /** The single selected spline, for the menu's add/remove point actions. */
    fun singleSelectedSpline(): ShapeItem? = singleSpline()?.item as? ShapeItem

    /** Add a control point to the selected spline, or remove one, as one undoable edit. */
    fun editSelectedSpline(add: Boolean) {
        val sp = singleSelectedSpline() ?: return
        val before = sp.snapshotGeometry()
        if (add) sp.addControlPoint() else if (!sp.removeControlPoint()) return
        history.push(TransformItems(listOf(sp), listOf(before), listOf(sp.snapshotGeometry())))
        state.document.dirty = true
        selObb = selectionObb()
        onContentChanged()
        refreshSelectionMenu()
        requestRender()
    }

    fun singleSelectedFunction(): ShapeItem? =
        (selection.singleOrNull()?.item as? ShapeItem)?.takeIf { it.shape == ShapeKind.FUNCTION }

    /** Replot the selected function curve as one undoable edit; false when [spec] cannot be drawn. */
    fun setSelectedFunction(spec: FunctionSpec): Boolean {
        val fn = singleSelectedFunction() ?: return false
        val before = fn.snapshotGeometry()
        if (!fn.setFunction(spec)) return false
        history.push(TransformItems(listOf(fn), listOf(before), listOf(fn.snapshotGeometry())))
        state.document.dirty = true
        selObb = selectionObb()
        onContentChanged()
        refreshSelectionMenu()
        requestRender()
        return true
    }

    fun clearSelection() {
        // Restore the tool a long-press grab temporarily switched away from.
        longPressPrevTool?.let {
            tool = it
            longPressPrevTool = null
            onToolChanged(tool)
        }
        onSelectionMenu(null)
        setTableEditing(false)
        selObb = null
        if (selection.isEmpty()) return
        val regions = dirtyRegions(selection) // where the now-unlifted items sit (before clearing)
        selection.clear()
        repairRegions(regions) // repaint them back into the cache in place — no full rebuild
        onSelectionChanged(false)
        requestRender()
    }

    /**
     * Page-local dirty rects keyed by page index, unioning each item's paint extent (incl.
     * soft overflow such as neon glow) — the regions whose cached ink must be repaired when
     * these items' lifted state changes (lifting clears them out, unlifting repaints them).
     */
    private fun dirtyRegions(items: List<Selected>): Map<Int, Rect> {
        val regions = HashMap<Int, Rect>()
        for (s in items) {
            val b = s.item.paintBounds()
            regions[s.pageIndex] = regions[s.pageIndex]?.union(b) ?: b
        }
        return regions
    }

    /**
     * Repaint just [regions] of each page's ink cache in place — the eraser's smart path
     * ([CanvasState.repairRegion]), which keeps the cache entry and the (PDF/template)
     * background layer intact, so a selection edit no longer blanks the whole ink layer.
     * Falls back to a full page rebuild only where there is no live cache to repair.
     */
    private fun repairRegions(regions: Map<Int, Rect>) {
        for ((pageIndex, rect) in regions) {
            val page = state.document.pages.getOrNull(pageIndex) ?: continue
            if (!state.repairRegion(page, rect.outset(REPAIR_PAD))) state.invalidatePage(page)
        }
    }

    /**
     * Re-home selected items that a drag or transform carried onto another page. Item geometry is
     * page-local and every page renders clipped to itself, so an item left behind on its old page
     * vanishes the moment it is unlifted. Returns the undoable transfer, or null if nothing crossed.
     */
    private fun reassignSelectionPages(): Command? {
        val transfers = ArrayList<TransferItems.Transfer>()
        val rehomed = ArrayList<Selected>(selection.size)
        for (sel in selection) {
            val from = state.document.pages.getOrNull(sel.pageIndex)
            val target = if (from == null || state.pageRects.getOrNull(sel.pageIndex) == null) {
                null
            } else {
                pageIndexForBounds(state.fromPageSpaceRect(sel.pageIndex, sel.item.bounds()))
            }
            if (from == null || target == null || target == sel.pageIndex) {
                rehomed.add(sel)
                continue
            }
            // Both pages share the view rotation, so the change of frame is a pure page-space shift.
            val d = state.toPageSpace(target, state.fromPageSpace(sel.pageIndex, Pt.ZERO))
            transfers.add(TransferItems.Transfer(from, state.document.pages[target], sel.item, d.x, d.y))
            rehomed.add(Selected(target, sel.item))
        }
        if (transfers.isEmpty()) return null
        val cmd = TransferItems(transfers)
        cmd.redo()
        // The items stay lifted, so no cache is out of date: the old page never held them, and the
        // new one bakes them in when the selection clears (dirtyRegions now names the new page).
        selection.clear()
        selection.addAll(rehomed)
        return cmd
    }

    /** The page an item belongs to after a move: the drawable page its content-space bounds cover
     *  most, falling back to the nearest page when it was dropped in a gap. */
    private fun pageIndexForBounds(b: Rect): Int? {
        val drawable = state.drawablePageRange()
        var best = -1
        var bestArea = 0.0
        var nearest = -1
        var nearestDist = Double.MAX_VALUE
        for (i in state.pageRects.indices) {
            if (i !in drawable) continue
            val pr = state.pageRects[i]
            val w = min(b.right, pr.right) - max(b.left, pr.left)
            val h = min(b.bottom, pr.bottom) - max(b.top, pr.top)
            if (w > 0 && h > 0 && w * h > bestArea) {
                bestArea = w * h
                best = i
            }
            val d = pr.distanceTo(b.center)
            if (d < nearestDist) {
                nearestDist = d
                nearest = i
            }
        }
        return if (best >= 0) best else nearest.takeIf { it >= 0 }
    }

    private fun selectionBoundsContent(): Rect? {
        if (selection.isEmpty()) return null
        var acc: Rect? = null
        for (sel in selection) {
            if (state.pageRects.getOrNull(sel.pageIndex) == null) continue
            val b = state.fromPageSpaceRect(sel.pageIndex, sel.item.bounds())
            acc = acc?.union(b) ?: b
        }
        return acc
    }

    /**
     * The selection's transform box. A single rotated image gets an oriented box hugging its turned
     * rect, so the box tracks the tilt instead of the larger upright AABB that circumscribes it.
     * Everything else keeps the axis-aligned box built from the content bounds.
     */
    private fun selectionObb(): Obb? {
        val single = selection.singleOrNull()
        val img = single?.item as? ImageItem
        if (img != null && img.angle != 0.0 && state.pageRects.getOrNull(single.pageIndex) != null) {
            val cs = img.corners().map { state.fromPageSpace(single.pageIndex, it) }
            val center = Pt((cs[0].x + cs[2].x) / 2.0, (cs[0].y + cs[2].y) / 2.0)
            val halfW = cs[0].distanceTo(cs[1]) / 2.0
            val halfH = cs[0].distanceTo(cs[3]) / 2.0
            val angle = atan2(cs[1].y - cs[0].y, cs[1].x - cs[0].x)
            return Obb(center, halfW, halfH, angle)
        }
        return selectionBoundsContent()?.let { Obb.fromAabb(it) }
    }

    // --- public edit operations (toolbar / context menu) ---

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val removals = selection.map { state.document.pages[it.pageIndex] to it.item }
        for ((page, item) in removals) page.items.remove(item)
        history.push(EraseItems(removals))
        state.document.dirty = true
        // clearSelection repairs the vacated regions in place; the removed items were lifted
        // (already out of the ink cache) so they simply stop being drawn. The background/PDF
        // cache is left untouched — no full flush, no flicker.
        clearSelection()
        onContentChanged()
        maybeSwitchBackAfterSelect()
    }

    fun selectAll() {
        val all = ArrayList<Selected>()
        state.document.pages.forEachIndexed { i, page ->
            page.items.forEach { if (!it.locked) all.add(Selected(i, it)) }
        }
        setSelection(all)
    }

    fun bringToFront() {
        if (selection.isEmpty()) return
        val byPage = selection.groupBy { it.pageIndex }
        for ((pageIndex, sels) in byPage) {
            val page = state.document.pages.getOrNull(pageIndex) ?: continue
            val selectedSet = sels.map { it.item }.toSet()
            val old = page.items.toList()
            val kept = old.filter { it !in selectedSet }
            val moved = old.filter { it in selectedSet }
            val new = kept + moved
            if (new != old) {
                history.push(ReorderItems(page, old, new))
                page.items.clear()
                page.items.addAll(new)
                // Selected items are lifted (excluded from the cache); the reorder moves only
                // those, leaving the cached non-lifted items' order unchanged. The new z-order
                // bakes into the cache when the selection is next cleared — no rebuild here.
            }
        }
        state.document.dirty = true
        onContentChanged()
        maybeSwitchBackAfterSelect()
    }

    /** The styles the selection's drawn items carry, for the restyle popup to open on. */
    fun selectionStyles(): List<DrawStyle> = selection.mapNotNull { DrawStyle.of(it.item) }

    /** Styles held from the first [restyleSelection] preview, so a slider drag undoes in one step. */
    private var restyleBaseline: MutableMap<CanvasItem, DrawStyle>? = null

    /**
     * Recolour and/or re-thicken the selection's strokes and shapes. A null [color] or [width]
     * leaves that half of each item's style alone, so a mixed selection can be recoloured without
     * flattening its widths.
     *
     * A [preview] call applies the change without touching history; the next call with [preview]
     * off records everything since as a single undo step. Passing both values null with [preview]
     * off does nothing but close a pending preview, which is how a dismissed popup settles up.
     * No cache repair is needed: a selected item is lifted, so it is drawn live rather than baked.
     */
    fun restyleSelection(color: Rgba?, width: Double?, preview: Boolean = false) {
        if (selection.isEmpty()) return
        val baseline = restyleBaseline ?: HashMap<CanvasItem, DrawStyle>().also { map ->
            for (s in selection) DrawStyle.of(s.item)?.let { map[s.item] = it }
        }
        restyleBaseline = if (preview) baseline else null
        if (color != null || width != null) {
            for (s in selection) {
                val current = DrawStyle.of(s.item) ?: continue
                DrawStyle(color ?: current.color, width ?: current.width).applyTo(s.item)
            }
        }
        if (!preview) {
            val entries = baseline.mapNotNull { (item, before) ->
                DrawStyle.of(item)?.takeIf { it != before }?.let { RestyleItems.Entry(item, before, it) }
            }
            // Pushed after the fact, like every command: redo only ever re-applies an undone edit.
            if (entries.isNotEmpty()) history.push(RestyleItems(entries))
        }
        state.document.dirty = true
        onContentChanged()
        requestRender()
    }

    fun escape() {
        val flow = flowText
        if (flow != null && flow.active) {
            flow.endSession()
            requestRender()
            return
        }
        commitTextEdit(restoreTool = true)
        clearSelection()
        requestRender()
    }

    // --- clipboard: copy / cut / duplicate / paste ---

    /** Copy the selection into the in-app clipboard. Pure: no tool/selection side effects, so
     *  cut/duplicate can reuse it without tripping the select tool's switch-back. */
    private fun copyToClipboard() {
        if (selection.isEmpty()) return
        itemClipboard.clear()
        clipboardFromCut = false
        selection.forEach { itemClipboard.add(cloneItem(it.item)) }
    }

    fun copySelection() {
        if (selection.isEmpty()) return
        copyToClipboard()
        maybeSwitchBackAfterSelect()
    }

    fun cutSelection() {
        if (selection.isEmpty()) return
        copyToClipboard()
        clipboardFromCut = true
        deleteSelection() // also runs the select tool's switch-back, once
    }

    /**
     * Pin the selection where it is, then put the selection away, since a locked item cannot stay
     * selected. Clearing also repaints the items back into the page cache, which lifting them for
     * the drag had taken them out of.
     */
    fun lockSelection() {
        if (selection.isEmpty()) return
        val items = selection.map { it.item }
        for (item in items) item.locked = true
        history.push(LockItems(items, true))
        clearSelection()
        maybeSwitchBackAfterSelect()
        onContentChanged()
        requestRender()
    }

    /** Release [item], so it can be selected again. Nothing else about it changes. */
    fun unlockItem(item: CanvasItem) {
        if (!item.locked) return
        item.locked = false
        history.push(LockItems(listOf(item), false))
        onContentChanged()
    }

    fun duplicateSelection() {
        if (selection.isEmpty()) return
        copyToClipboard()
        val pageIndex = selection.first().pageIndex
        // Paste offset slightly from the originals (not repositioned to a point).
        pasteClonesOnPage(pageIndex, Pt.ZERO, offsetFromBoundsTopLeft = false, nudge = 24.0)
        maybeSwitchBackAfterSelect()
    }

    /** Paste the clipboard items so their top-left lands at the given content point. */
    fun pasteItemsAt(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: state.currentPageIndex()
        pasteClonesOnPage(pageIndex, content, offsetFromBoundsTopLeft = true, nudge = 0.0)
    }

    private fun pasteClonesOnPage(pageIndex: Int, target: Pt, offsetFromBoundsTopLeft: Boolean, nudge: Double) {
        if (itemClipboard.isEmpty()) return
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        if (state.pageRects.getOrNull(pageIndex) == null) return
        val clones = itemClipboard.map { cloneItem(it) }
        // Collective bounds (page-local) of the clones.
        var box: Rect? = null
        for (c in clones) box = box?.union(c.bounds()) ?: c.bounds()
        val b = box ?: return
        val targetLocal = state.toPageSpace(pageIndex, target)
        val dx = if (offsetFromBoundsTopLeft) targetLocal.x - b.left + nudge else nudge
        val dy = if (offsetFromBoundsTopLeft) targetLocal.y - b.top + nudge else nudge
        for (c in clones) c.translate(dx, dy)
        page.items.addAll(clones)
        history.push(AddItems(page, clones))
        // A cut's clipboard is spent by the paste that lands it: the items were taken from the
        // page, so putting them down finishes the move rather than starting a series of copies.
        if (clipboardFromCut) {
            itemClipboard.clear()
            clipboardFromCut = false
        }
        state.document.dirty = true
        // The clones are immediately selected (lifted) below; setSelection repairs their
        // region in place, so no separate page rebuild is needed here.
        setSelection(clones.map { Selected(pageIndex, it) })
        refreshSelectionMenu()
        onContentChanged()
    }

    private fun cloneItem(item: CanvasItem): CanvasItem = item.deepCopy(textMeasurer)

    // --- selection menu ---

    /** Show the selection menu when a selection is settled (idle), else hide it. */
    private fun refreshSelectionMenu() {
        onSelectionMenu(if (selection.isNotEmpty() && mode == PointerMode.IDLE) selectionBoundsViewport() else null)
    }

    private fun selectionBoundsViewport(): Rect? {
        val content = selectionBoundsContent() ?: return null
        val tl = state.contentToViewport(content.topLeft)
        val br = state.contentToViewport(Pt(content.right, content.bottom))
        var rect = Rect.fromPoints(tl, br)
        // Raise the menu's anchor top above the rotate grip (drawn ROTATE_ARM + grip radius above the
        // box) so the floating action bar floats over the grip instead of covering it. Bottom is left
        // put, so the below-the-selection fallback placement is unchanged.
        val clearance = ROTATE_ARM + HANDLE_SIZE * 0.8
        if (selectionRotatePoint() != null) rect = Rect(rect.left, rect.top - clearance, rect.w, rect.h + clearance)
        // Likewise drop the bottom below the move grip, so the fallback placement clears it.
        if (selectionMoveGrip() != null) rect = Rect(rect.left, rect.top, rect.w, rect.h + clearance)
        return rect
    }

    // --- PAN ---

    // When zoom is locked, the zoomLockPan preference decides which pan gestures still move the
    // viewport: "single" keeps both, "double" drops the single finger, "none" freezes the page.
    private fun singleFingerPanAllowed(): Boolean = !(state.zoomLocked && zoomLockPan != "single")
    private fun pinchPanAllowed(): Boolean = !(state.zoomLocked && zoomLockPan == "none")

    private fun beginPan(vx: Double, vy: Double, fromPenButton: Boolean = false) {
        mode = PointerMode.PAN
        panDownViewport = Pt(vx, vy)
        panFromPenButton = fromPenButton
        startTrackingVelocity(vx, vy)
    }

    private fun extendPan(vx: Double, vy: Double) {
        trackVelocity(vx, vy)
        val dx = -(vx - lastPan.x)
        var dy = -(vy - lastPan.y)
        if (!state.verticalScroll) {
            extendPanPaginated(dx, dy)
            lastPan = Pt(vx, vy)
            onViewChanged()
            requestRender()
            return
        }
        // While the bottom elastic is stretched, finger motion works the elastic first (rubber-band)
        // rather than the scroll, so pulling back relaxes the stretch before the document scrolls.
        // Stretching it further needs the document end on screen: right after a pull adds a page
        // the end sits a full page below, so a quick swipe toward it must scroll, not re-arm.
        if (state.overscrollY > 0.0 && (dy < 0.0 || state.isDocumentEndVisible())) {
            val relaxed = (state.overscrollY + dy * OVERSCROLL_RESIST).coerceAtLeast(0.0)
            val consumed = (relaxed - state.overscrollY) / OVERSCROLL_RESIST
            state.overscrollY = relaxed.coerceAtMost(OVERSCROLL_MAX)
            dy -= consumed
            updateOverscrollArmed()
        }
        // Apply the remaining pan to the scroll; whatever the clamp rejects at the bottom feeds the elastic.
        val beforeY = state.scrollY
        state.scrollBy(dx, dy)
        val leftoverY = dy - (state.scrollY - beforeY)
        // Only feed the elastic when the document's end is actually on screen. Inferring "at the end"
        // from a rejected downward scroll alone is unsafe: a transient bad scroll/layout state right
        // after a document opens can make the clamp fire while there is still document below the fold,
        // spuriously arming add-page (seen on first open, even on long PDFs). isDocumentEndVisible()
        // checks the last page's bottom against the viewport through the same transform that draws the
        // frame, so the affordance can never appear while the user can still see more document below.
        if (leftoverY > 0.0 && state.isDocumentEndVisible()) {
            state.overscrollY = (state.overscrollY + leftoverY * OVERSCROLL_RESIST).coerceAtMost(OVERSCROLL_MAX)
            updateOverscrollArmed()
        }
        lastPan = Pt(vx, vy)
        onViewChanged()
        requestRender()
    }

    /** Fire the threshold haptic once as the live stretch crosses the add-page point. */
    private fun updateOverscrollArmed() {
        val past = state.overscrollY >= OVERSCROLL_TRIGGER
        if (past && !overscrollArmed) onHaptic()
        overscrollArmed = past
    }

    // --- paginated page flip ---

    /**
     * Paginated pan: pan freely inside the current row (the clamp pins the window to it);
     * whatever the clamp rejects horizontally works the edge-pull elastic instead, arming
     * the flip. Relaxing motion unwinds the pull before the scroll moves again, exactly
     * like the bottom overscroll. No add-page elastic in this mode.
     */
    private fun extendPanPaginated(dx0: Double, dy: Double) {
        var dx = dx0
        val pull = state.flipOffsetX
        if (pull > 0.0 && dx < 0.0 || pull < 0.0 && dx > 0.0) {
            val relaxed = pull + dx * FLIP_RESIST
            if (relaxed * pull <= 0.0) { // crossed zero: the leftover motion scrolls
                state.flipOffsetX = 0.0
                dx += pull / FLIP_RESIST
            } else {
                state.flipOffsetX = relaxed
                dx = 0.0
            }
        }
        val beforeX = state.scrollX
        state.scrollBy(dx, dy)
        val leftoverX = dx - (state.scrollX - beforeX)
        if (leftoverX != 0.0) {
            val cap = FLIP_MAX_FRACTION * state.viewportW
            val armedBefore = abs(state.flipOffsetX) >= FLIP_TRIGGER
            state.flipOffsetX = (state.flipOffsetX + leftoverX * FLIP_RESIST).coerceIn(-cap, cap)
            if (!armedBefore && abs(state.flipOffsetX) >= FLIP_TRIGGER) onHaptic()
        }
        // The pull reveals only empty background; the neighbouring row never joins the screen.
    }

    /** Decide what a lifted paginated pan does: flip instantly, add a page, drop the pull, or glide. */
    private fun endPanPaginated() {
        val rows = state.rowRanges().size
        val pull = state.flipOffsetX
        when {
            // Past the last row the pull adds a page, the horizontal counterpart of the bottom
            // elastic. The new page opens its own row, so flipping onto it lands the user there.
            pull >= FLIP_TRIGGER && state.currentRow >= rows - 1 -> {
                clearFlipPull()
                onAddPageAtEnd()
                val grown = state.rowRanges().size
                if (grown > rows) flipTo(grown - 1) else { onViewChanged(); requestRender() }
            }
            pull >= FLIP_TRIGGER && state.currentRow < rows - 1 -> flipTo(state.currentRow + 1)
            pull <= -FLIP_TRIGGER && state.currentRow > 0 -> flipTo(state.currentRow - 1)
            panVel.x <= -FLIP_FLING_VEL && state.atRowEdge(next = true) && state.currentRow < rows - 1 ->
                flipTo(state.currentRow + 1)
            panVel.x >= FLIP_FLING_VEL && state.atRowEdge(next = false) && state.currentRow > 0 ->
                flipTo(state.currentRow - 1)
            pull != 0.0 -> { clearFlipPull(); onViewChanged(); requestRender() }
            else -> startPanFling()
        }
    }

    /**
     * Jump to [rowIndex] with no animation: the new row simply appears at its landing spot
     * ([CanvasState.rowTargetScroll] — zoom kept, top-aligned).
     */
    private fun flipTo(rowIndex: Int) {
        stopFling()
        state.goToPage(state.rowRanges()[rowIndex].first)
        onViewChanged()
        requestRender()
    }

    // --- inertial fling ---

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
        panVel = Pt(panVel.x * VEL_SMOOTH + inst.x * (1 - VEL_SMOOTH), panVel.y * VEL_SMOOTH + inst.y * (1 - VEL_SMOOTH))
        lastMoveMs = now
    }

    /** Glide on after a lifted pan, unless the pen's side button drove it: that one stops dead. */
    private fun startPanFling() {
        if (!panFromPenButton) startFling(panVel)
    }

    private fun startFling(fingerVel: Pt) {
        if (fingerVel.length() < FLING_MIN_START) return
        flingVel = Pt(-fingerVel.x, -fingerVel.y) // scroll moves opposite the finger
        flinging = true
        lastFlingMs = System.nanoTime() / 1_000_000L
        choreographer.postFrameCallback(flingFrame)
    }

    private fun stopFling() {
        flinging = false
    }

    private fun stepFling(frameTimeNanos: Long) {
        if (!flinging) return
        val now = frameTimeNanos / 1_000_000L
        val dt = ((now - lastFlingMs).coerceIn(1L, 40L)) / 1000.0
        lastFlingMs = now
        val beforeX = state.scrollX
        val beforeY = state.scrollY
        state.scrollBy(flingVel.x * dt, flingVel.y * dt)
        val decay = exp(-FLING_FRICTION * dt)
        flingVel = Pt(flingVel.x * decay, flingVel.y * decay)
        onViewChanged()
        requestRender()
        val moved = state.scrollX != beforeX || state.scrollY != beforeY
        if (flingVel.length() < FLING_MIN_STOP || !moved) {
            flinging = false
        } else {
            choreographer.postFrameCallback(flingFrame)
        }
    }

    // --- disappearing ink (magic wand) ---

    /** Drop all held ephemeral strokes and stop any pending or running fade. */
    private fun clearFading() {
        cancelFadeTimer()
        stopFade()
        fadingStrokes.clear()
        fadeAlpha = 1.0
    }

    /** (Re)start the debounce so the held batch fades only once drawing has paused. */
    private fun scheduleFade() {
        cancelFadeTimer()
        val r = Runnable { startFade() }
        fadeTimerRunnable = r
        handler.postDelayed(r, WAND_HOLD_MS)
    }

    private fun cancelFadeTimer() {
        fadeTimerRunnable?.let { handler.removeCallbacks(it) }
        fadeTimerRunnable = null
    }

    private fun startFade() {
        fadeTimerRunnable = null
        if (fadingStrokes.isEmpty()) return
        // Still drawing: the batch only fades once the pen has been up for the whole hold.
        if (liveStroke != null) {
            scheduleFade()
            return
        }
        fading = true
        fadeAlpha = 1.0
        fadeStartMs = System.nanoTime() / 1_000_000L
        choreographer.postFrameCallback(fadeFrame)
    }

    private fun stopFade() {
        fading = false
    }

    private fun stepFade(frameTimeNanos: Long) {
        if (!fading) return
        val now = frameTimeNanos / 1_000_000L
        val t = ((now - fadeStartMs).toDouble() / WAND_FADE_MS).coerceIn(0.0, 1.0)
        fadeAlpha = (1.0 - t) * (1.0 - t) // ease-out so the batch melts away rather than blinking off
        requestRender()
        if (t >= 1.0) {
            fadingStrokes.clear()
            fadeAlpha = 1.0
            fading = false
        } else {
            choreographer.postFrameCallback(fadeFrame)
        }
    }

    // --- elastic overscroll release ---

    /** Finger lifted while the bottom elastic was stretched: add a page if pulled far enough, then spring back. */
    private fun releaseOverscroll() {
        stopFling()
        if (state.overscrollY >= OVERSCROLL_TRIGGER) {
            onAddPageAtEnd()
            // The stretch is spent: sink it below the trigger so a re-grab mid-spring
            // cannot release it as a second add.
            state.overscrollY = OVERSCROLL_TRIGGER - 1.0
        }
        overscrollArmed = false
        if (!overscrollSettling) {
            overscrollSettling = true
            lastOverscrollMs = System.nanoTime() / 1_000_000L
            choreographer.postFrameCallback(overscrollFrame)
        }
    }

    private fun stopOverscrollSettle() {
        overscrollSettling = false
    }

    /** Drop the elastic immediately (no spring) — used when a gesture is cancelled or supplanted. */
    private fun clearOverscroll() {
        overscrollSettling = false
        overscrollArmed = false
        state.overscrollY = 0.0
    }

    /** Fold any paginated edge-pull back into the (clamped) scroll, with no animation. */
    private fun clearFlipPull() {
        if (state.flipOffsetX != 0.0) {
            state.scrollX += state.flipOffsetX
            state.flipOffsetX = 0.0
            state.clampScroll()
        }
    }

    /**
     * Drop any in-flight scroll/zoom physics and partial gesture so the previous document's fling,
     * elastic stretch or half-finished pan can't bleed into a freshly opened one. The editor calls
     * this whenever it swaps the open document — otherwise a stale fling/overscroll could leave the
     * new document at (or believing it is at) its bottom, spuriously arming add-page on first scroll.
     */
    fun resetGestureState() {
        stopFling()
        state.flipOffsetX = 0.0
        clearOverscroll()
        cancelDwell()
        clearFading()
        crossedSegments.clear() // they belong to the outgoing document, whose history is gone
        dwellEligible = false
        mode = PointerMode.IDLE
        lastPan = Pt.ZERO
        panVel = Pt.ZERO
    }

    private fun stepOverscrollSettle(frameTimeNanos: Long) {
        if (!overscrollSettling) return
        val now = frameTimeNanos / 1_000_000L
        val dt = ((now - lastOverscrollMs).coerceIn(1L, 40L)) / 1000.0
        lastOverscrollMs = now
        state.overscrollY *= exp(-OVERSCROLL_SPRING * dt) // exponential ease toward rest
        if (state.overscrollY < 0.5) {
            state.overscrollY = 0.0
            overscrollSettling = false
        } else {
            choreographer.postFrameCallback(overscrollFrame)
        }
        onViewChanged()
        requestRender()
    }

    // --- PINCH ---

    private fun beginPinch(e: MotionEvent) {
        liveStroke = null
        strokePageIndex = null
        cancelDwell() // a second finger turns the gesture into a zoom; don't snap a shape mid-pinch
        dwellEligible = false
        bandRect = null
        lassoPoints.clear()
        if (mode == PointerMode.SHOT) clearScreenshot() // a second finger turns the drag into a zoom
        clearOverscroll() // a second finger ends any bottom-pull; the elastic snaps away
        clearFlipPull() // ...and any paginated edge-pull folds back into the scroll
        mode = PointerMode.PINCH
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val mid = (a + b) * 0.5
        pinchInitDist = a.distanceTo(b).coerceAtLeast(1.0)
        pinchInitZoom = state.zoom
        pinchAnchorContent = state.viewportToContent(mid)
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
        // Zoom lock: pan only (keep the initial zoom).
        val raw = (pinchInitZoom * (dist / pinchInitDist)).coerceIn(state.minZoom, state.maxZoom)
        val wasFit = state.fitWidthActive || state.fitHeightActive
        // Magnetic fit: the live zoom sticks to fit-width — and, paginated, fit-height — while
        // within the band (pinch past it to break free). The lock hint surfaces the moment a
        // magnet grabs and is dismissed the moment it breaks free. A locked pinch is pan-only,
        // so it never snaps.
        val z = if (state.zoomLocked) pinchInitZoom else state.snapZoomToFit(raw)
        // A two-finger pan held at its zoom (locked, or on a fit magnet) keeps prefetching like a one-finger one.
        if (z != pinchInitZoom) state.zoomingInProgress = true
        state.zoom = z
        if (!state.zoomLocked) {
            val nowFit = state.fitWidthActive || state.fitHeightActive
            if (!wasFit && nowFit) onFitWidthSnapped()
            else if (wasFit && !nowFit) onFitWidthReleased()
        }
        if (pinchPanAllowed()) {
            val targetX = pinchAnchorContent.x * z - mid.x
            val targetY = pinchAnchorContent.y * z - mid.y
            state.scrollX = targetX
            state.scrollY = targetY
            state.clampScroll()
            // Only a pan-only (zoom-locked) pinch works the elastics. While the zoom is changing the
            // clamp rejects scroll for reasons that have nothing to do with reaching past the end.
            if (state.zoomLocked) applyPinchElastic(targetX - state.scrollX, targetY - state.scrollY)
        }
        lastPan = mid
        onViewChanged() // live zoom %: refresh the toolbar each pinch frame, not just at the end
        requestRender()
    }

    /**
     * Work the scroll the clamp rejected into the same elastics a one-finger pan drives: the
     * add-page stretch scrolling vertically, the edge-pull paginated. Without this a two-finger
     * pan — the only pan there is with zoom locked to two fingers — could never reach either.
     *
     * The pinch positions the scroll from a fixed content anchor, so the rejected amount is the
     * whole overshoot rather than one frame's worth: the elastic is set, not accumulated.
     */
    private fun applyPinchElastic(overX: Double, overY: Double) {
        if (state.verticalScroll) {
            state.overscrollY =
                if (overY > 0.0 && state.isDocumentEndVisible()) (overY * OVERSCROLL_RESIST).coerceAtMost(OVERSCROLL_MAX)
                else 0.0
            updateOverscrollArmed()
            return
        }
        val cap = FLIP_MAX_FRACTION * state.viewportW
        val armedBefore = abs(state.flipOffsetX) >= FLIP_TRIGGER
        state.flipOffsetX = (overX * FLIP_RESIST).coerceIn(-cap, cap)
        if (!armedBefore && abs(state.flipOffsetX) >= FLIP_TRIGGER) onHaptic()
    }

    private fun endPinch() {
        mode = PointerMode.IDLE
        if (state.zoomingInProgress) {
            state.zoomingInProgress = false
            state.invalidateCachesForZoom() // keep stale surfaces to blit until the sharp rebuild lands
        }
        onViewChanged()
        requestRender()
        if (!pinchPanAllowed()) return
        when {
            state.overscrollY > 0.0 -> releaseOverscroll()
            !state.verticalScroll && state.flipOffsetX != 0.0 -> endPanPaginated()
            else -> startFling(panVel)
        }
    }

    private fun abortGesture() {
        cancelLongPress()
        cancelDwell()
        dwellEligible = false
        snappedSelectionPendingMenu = false
        strokeDismissedSelection = false
        panMayCommitText = false
        stopFling()
        clearFlipPull()
        clearOverscroll()
        pushStrokeEdit(null) // a cancelled crossing still left segments on the pages behind it
        liveStroke = null
        strokePageIndex = null
        snapEngaged = false
        snapRunStartEdge = null
        snapCurrentEdge = null
        snapPenViewport = null
        pendingShape = null
        shapePageIndex = null
        bandRect = null
        lassoPoints.clear()
        textDragRect = null
        // Cancel an in-progress capture drag; a frozen capture (mode IDLE) survives.
        if (mode == PointerMode.SHOT) { screenshotRect = null; onScreenshotMenu(null) }
        if (mode == PointerMode.PINCH && state.zoomingInProgress) {
            state.zoomingInProgress = false
            state.invalidateCachesForZoom()
        }
        mode = PointerMode.IDLE
    }

    private fun resolvePressure(e: MotionEvent, pointerIndex: Int, toolType: Int): Double =
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) e.getPressure(pointerIndex).toDouble().coerceIn(0.0, 1.0) else 1.0

    // --- overlay ---

    fun drawOverlay(r: Renderer) {
        val origin = state.origin()
        r.withSave {
            r.translate(origin.x, origin.y)
            r.scale(state.zoom, state.zoom)

            // Lifted (selected) items, drawn live at the move offset.
            for (sel in selection) {
                val pr = state.pageRects.getOrNull(sel.pageIndex) ?: continue
                r.withSave {
                    r.translate(pr.left + moveOffset.x, pr.top + moveOffset.y)
                    state.applyPageTransform(r, state.document.pages[sel.pageIndex])
                    sel.item.paint(r)
                }
            }

            // Live in-progress stroke / shape preview, clipped to its page. The stroke goes through
            // the wet cache, which blits whatever has stopped moving instead of refilling it.
            if (frontInk?.live != true) {
                liveStroke?.let { stroke -> paintClippedToPage(r, strokePageIndex) { state.paintLiveStroke(r, stroke) } }
            }
            pendingShape?.let { shape -> paintClippedToPage(r, shapePageIndex) { shape.paint(r) } }
            pendingTable?.let { table -> paintClippedToPage(r, tableDrawPageIndex) { table.paint(r) } }

            // Disappearing ink (magic wand): ephemeral strokes drawn live at the shared fade alpha,
            // without mutating their stored colour. Highlighters keep their MULTIPLY look while fading.
            for (fs in fadingStrokes) {
                // Still on the front buffer, which would otherwise draw it a second time.
                if (frontInk?.holding(fs.stroke) == true) continue
                paintClippedToPage(r, fs.pageIndex) {
                    r.saveLayerBlended(fs.stroke.paintBounds(), fadeAlpha, fs.stroke.blendMode)
                    fs.stroke.paint(r)
                    r.restore()
                }
            }

            // Screenshot capture region: the live drag rect, kept frozen until "copy as image" is used.
            screenshotRect?.let { r.strokeRect(it, chromePen(1.6)) }

            // Selection chrome.
            val accent = chromePen(1.3)
            // Flow caret + selection highlight, under the selection chrome.
            flowText?.drawOverlay(r)

            when {
                mode == PointerMode.BAND -> bandRect?.let { r.strokeRect(it, accent) }
                mode == PointerMode.TEXT_DRAG -> textDragRect?.let { r.strokeRect(it, accent) }
                mode == PointerMode.LASSO_DRAW && lassoPoints.size >= 2 ->
                    r.strokePolyline(lassoPoints, chromePen(1.3))
                selection.isNotEmpty() && mode != PointerMode.CROP ->
                    selObb?.let { obb ->
                        r.strokePolygon(obb.corners().map { Pt(it.x + moveOffset.x, it.y + moveOffset.y) }, accent)
                    }
            }

            // Crop mode: dim the trimmed border and show the crop rect + handles instead of the box.
            if (mode == PointerMode.CROP) {
                drawCrop(r)
            } else
            // Table edit mode: the resize box is replaced by add/remove buttons and per-line handles.
            if (isTableEditing() && mode != PointerMode.BAND && mode != PointerMode.LASSO_DRAW) {
                drawTableEditChrome(r)
            } else
            // Resize + rotate handles for the settled selection (single, multi, or mixed).
            if (selection.isNotEmpty() && mode != PointerMode.BAND && mode != PointerMode.LASSO_DRAW) {
                val side = HANDLE_SIZE / state.zoom
                // Rotate grip: a short stem out from the box's top edge up to a round handle.
                selectionRotatePoint()?.let { rp ->
                    selObb?.let { obb ->
                        val base = ResizeMath.obbTopMid(obb)
                        val stemTop = Pt(base.x + moveOffset.x, base.y + moveOffset.y)
                        val grip = Pt(rp.x + moveOffset.x, rp.y + moveOffset.y)
                        r.strokePolyline(listOf(grip, stemTop), Pen(com.xnotes.core.model.Rgba.SELECTION, 1.3, cosmetic = true))
                        r.fillCircle(grip, side * 0.6, com.xnotes.core.model.Rgba.SELECTION)
                    }
                }
                for (h in selectionResizeHandles()) {
                    val c = Pt(h.content.x + moveOffset.x, h.content.y + moveOffset.y)
                    r.fillRect(Rect(c.x - side / 2, c.y - side / 2, side, side), com.xnotes.core.model.Rgba.SELECTION)
                }
                singleSpline()?.let { sp ->
                    for (p in splineHandles(sp)) {
                        r.fillCircle(Pt(p.x + moveOffset.x, p.y + moveOffset.y), side * 0.5, com.xnotes.core.model.Rgba.SELECTION)
                    }
                }
                selectionMoveGrip()?.let { g ->
                    val c = Pt(g.x + moveOffset.x, g.y + moveOffset.y)
                    r.fillCircle(c, side * 0.8, com.xnotes.core.model.Rgba.SELECTION)
                    val glyph = Pen(com.xnotes.core.model.Rgba(255, 255, 255, 255), 1.6, cosmetic = true)
                    for (run in ResizeMath.moveGlyph(c, side * 0.5)) r.strokePolyline(run, glyph)
                }
            }
        }

        // Eraser cursor (viewport space, after the transform is restored).
        eraserCursor?.let {
            val radius = eraserRadius() * state.zoom
            r.strokeEllipse(it, radius, radius, Pen(state.palette.textDim, 1.3, cosmetic = true))
        }

        // Ruler (viewport space; floats above all content and the live stroke).
        if (ruler.visible) drawRuler(r)
    }

    /**
     * A marquee pen: dashed, so chrome reads as chrome rather than as ink, off the same dp runs
     * the infinite canvas tessellates its own from, so a band, a lasso and a selection box look
     * like each other and like themselves on the other canvas. A cosmetic pen takes its dash runs
     * in device px, which is what the conversion here produces.
     */
    private fun chromePen(width: Double): Pen = Pen(
        com.xnotes.core.model.Rgba.SELECTION,
        width,
        cosmetic = true,
        dashed = true,
        dashOn = com.xnotes.core.infinite.OverlayTessellator.DASH_ON_DP * state.devicePxPerDp,
        dashGap = com.xnotes.core.infinite.OverlayTessellator.DASH_GAP_DP * state.devicePxPerDp,
    )

    private inline fun paintClippedToPage(r: Renderer, pageIndex: Int?, crossinline paint: () -> Unit) {
        val pi = pageIndex ?: -1
        val pr = state.pageRects.getOrNull(pi) ?: return
        r.withSave {
            r.clipRect(pr)
            r.translate(pr.left, pr.top)
            state.applyPageTransform(r, state.document.pages[pi])
            paint()
        }
    }

    // --- ruler ---

    private fun beginRulerTransform(e: MotionEvent) {
        cancelLongPress()
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        rulerXformStartCentroid = (a + b) * 0.5
        rulerXformStartFingerAngle = atan2(b.y - a.y, b.x - a.x)
        rulerXformStartCenter = ruler.center
        rulerXformStartRuler = ruler.angleRad
        mode = PointerMode.RULER_TRANSFORM
        requestRender()
    }

    private fun updateRulerTransform(e: MotionEvent) {
        if (e.pointerCount < 2) return
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        if (!ruler.lockPosition) {
            ruler.center = rulerXformStartCenter + ((a + b) * 0.5 - rulerXformStartCentroid)
        }
        if (!ruler.lockAngle) {
            ruler.angleRad = Ruler.snapToAxes(rulerXformStartRuler + (atan2(b.y - a.y, b.x - a.x) - rulerXformStartFingerAngle))
        }
        requestRender()
    }

    /** How far the rotation handles sit from the ruler centre (kept on-screen). */
    private fun rulerHandleDist(): Double = 0.30 * minOf(state.viewportW, state.viewportH).toDouble()

    /** Drag a rotation handle: spin the ruler about its centre so the grabbed handle tracks the pointer. */
    private fun updateRulerRotate(e: MotionEvent) {
        if (ruler.lockAngle) return
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        val v = (Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble()) - ruler.center) * rulerRotateSign
        if (v.length() < 1e-3) return
        ruler.angleRad = Ruler.snapToAxes(atan2(v.y, v.x))
        requestRender()
    }

    /**
     * The ruler magnet for a viewport draw point. Engages when the pen enters the snap band beside a
     * long edge, then clamps the ink onto that edge — so a stroke can't pass through the body — and
     * releases only when the pen retreats back out past the band on the engaged side. Updates the
     * engagement state and returns the point to actually draw.
     */
    private fun magnetize(vp: Pt): Pt {
        if (!ruler.visible) return vp
        val ht = ruler.thicknessPx / 2.0
        val band = RULER_SNAP_DP * state.devicePxPerDp
        val across = ruler.signedAcross(vp)
        val active = when {
            snapEngaged -> {
                // Release only on an outward retreat past the band on the engaged side; pushing inward
                // (toward/through the body) stays clamped to the edge, so ink can't cross the ruler.
                val retreated = if (snapTopSide) across > ht + band else across < -(ht + band)
                if (retreated) {
                    snapEngaged = false
                    snapRunStartEdge = null
                    snapCurrentEdge = null
                }
                snapEngaged
            }
            abs(across) <= ht + band -> {
                snapTopSide = across >= 0.0
                snapEngaged = true
                true
            }
            else -> false
        }
        if (!active) return vp
        snapPenViewport = vp
        val edge = ruler.projectToEdge(vp, snapTopSide)
        if (snapRunStartEdge == null) snapRunStartEdge = edge
        snapCurrentEdge = edge
        return edge
    }

    /** Paint the ruler in viewport space: an infinite frosted band, dual-edge graduations and readouts. */
    private fun drawRuler(r: Renderer) {
        val pal = state.palette
        val density = state.devicePxPerDp
        // Visible along-range: project the four viewport corners onto the band's length axis.
        val d = ruler.direction()
        val vw = state.viewportW.toDouble()
        val vh = state.viewportH.toDouble()
        var sMin = Double.MAX_VALUE
        var sMax = -Double.MAX_VALUE
        for (c in listOf(Pt(0.0, 0.0), Pt(vw, 0.0), Pt(0.0, vh), Pt(vw, vh))) {
            val s = Geometry.dot(c - ruler.center, d)
            if (s < sMin) sMin = s
            if (s > sMax) sMax = s
        }
        val pad = 4.0 * density
        sMin -= pad
        sMax += pad

        // Body: a neutral frosted strip with thin neutral edges (no accent).
        val quad = ruler.bodyQuad(sMin, sMax)
        r.fillPolygon(quad, pal.panel.scaleAlpha(if (pal.isDark) 0.6 else 0.5))
        val edgePen = Pen(pal.textDim, 1.2, cosmetic = true)
        r.strokePolyline(listOf(quad[0], quad[1]), edgePen)
        r.strokePolyline(listOf(quad[3], quad[2]), edgePen)

        drawRulerTicks(r, density, pal, sMin, sMax)
        drawRulerButtons(r, pal)
        drawRulerHandles(r, density, pal)

        if (snapEngaged) {
            val a = snapRunStartEdge
            val b = snapCurrentEdge
            val pen = snapPenViewport
            if (a != null && b != null && pen != null) {
                val cm = RulerMath.viewportLenToCm(a.distanceTo(b), state.zoom, document.dpi)
                drawReadout(r, "%.1f cm".format(cm), pen + Pt(30.0, -30.0) * density, density, pal)
            }
        }
    }

    /** The two rotation handles with permanent angle readouts: counter-clockwise from −x on the +x
     *  side, clockwise from +x on the other. Dragging a handle spins the ruler about its centre. */
    private fun drawRulerHandles(r: Renderer, density: Double, pal: Palette) {
        val dist = rulerHandleDist()
        val radius = ruler.handleRadiusPx()
        // Readings are tied to the handle (not the screen side) so they never swap as the ruler turns:
        // the +direction handle reads counter-clockwise from +x, the −direction handle clockwise from −x.
        val phi = Math.toDegrees(atan2(ruler.direction().y, ruler.direction().x))
        val ccwFromPlusX = ((-phi) % 360 + 360) % 360
        val cwFromMinusX = (phi % 360 + 360) % 360
        val handles = ruler.handleCenters(dist)
        for (i in handles.indices) {
            val h = handles[i]
            r.fillCircle(h, radius, pal.menuBg.scaleAlpha(0.95))
            r.strokeEllipse(h, radius, radius, Pen(pal.text, 1.4, cosmetic = true))
            r.strokePolyline(arcPolyline(h, radius * 0.5, 25.0, 155.0, 10), Pen(pal.textDim, 1.3, cosmetic = true))
            r.strokePolyline(arcPolyline(h, radius * 0.5, 205.0, 335.0, 10), Pen(pal.textDim, 1.3, cosmetic = true))
            val deg = if (i == 0) ccwFromPlusX else cwFromMinusX
            val outward = (h - ruler.center).normalized()
            drawReadout(r, "%.0f°".format(deg), h + outward * (radius + 28.0 * density), density, pal)
        }
    }

    /** cm/mm graduations on BOTH long edges; spacing scales with zoom; origin (0) at the ruler centre. */
    private fun drawRulerTicks(r: Renderer, density: Double, pal: Palette, sMin: Double, sMax: Double) {
        val cmPx = RulerMath.contentPxPerCm(document.dpi) * state.zoom
        if (cmPx <= 0.0) return
        val d = ruler.direction()
        val n = ruler.normal()
        val ht = ruler.thicknessPx / 2.0
        val tickPen = Pen(pal.text, 1.0, cosmetic = true)
        val labelFont = FontSpec(5.0 * density)
        val showMinor = cmPx >= 46.0
        val showLabels = cmPx >= 26.0
        val unitPx = if (showMinor) cmPx / 10.0 else cmPx
        val unitsPerLabel = if (showMinor) 10 else 1
        val step = if (showMinor || cmPx >= 12.0) 1 else 5 // crowd guard when zoomed far out
        var j = Math.ceil(sMin / unitPx).toInt()
        val jMax = Math.floor(sMax / unitPx).toInt()
        while (j <= jMax) {
            if (step == 1 || j % step == 0) {
                val mid = ruler.center + d * (j * unitPx)
                val top = mid + n * ht
                val bot = mid - n * ht
                val len = when {
                    !showMinor || j % 10 == 0 -> ht * 0.46
                    j % 5 == 0 -> ht * 0.30
                    else -> ht * 0.18
                }
                r.strokePolyline(listOf(top, top - n * len), tickPen)
                r.strokePolyline(listOf(bot, bot + n * len), tickPen)
                if (showLabels && j % unitsPerLabel == 0) drawTickLabel(r, abs(j / unitsPerLabel), mid, labelFont, pal.textDim)
            }
            j++
        }
    }

    private fun drawTickLabel(r: Renderer, cm: Int, center: Pt, font: FontSpec, color: Rgba) {
        val s = cm.toString()
        val w = s.length * font.pointSize * 1.25 + 2.0
        val h = font.pointSize * 2.1
        r.drawText(s, Rect(center.x - w / 2.0, center.y - h / 2.0, w, h), font, color)
    }

    private fun drawRulerButtons(r: Renderer, pal: Palette) {
        val radius = ruler.buttonRadiusPx()
        for ((btn, c) in ruler.buttonCenters()) {
            val active = when (btn) {
                RulerButton.LOCK_POS -> ruler.lockPosition
                RulerButton.LOCK_ANGLE -> ruler.lockAngle
            }
            r.fillCircle(c, radius, pal.menuBg.scaleAlpha(0.95))
            r.strokeEllipse(c, radius, radius, Pen(if (active) pal.text else pal.border, 1.3, cosmetic = true))
            val pen = Pen(if (active) pal.text else pal.textDim, 1.4, cosmetic = true)
            when (btn) {
                RulerButton.LOCK_POS -> {
                    val rr = radius * 0.5
                    r.strokeEllipse(c, rr * 0.5, rr * 0.5, pen)
                    r.strokePolyline(listOf(Pt(c.x, c.y - rr), Pt(c.x, c.y - rr * 0.5)), pen)
                    r.strokePolyline(listOf(Pt(c.x, c.y + rr * 0.5), Pt(c.x, c.y + rr)), pen)
                    r.strokePolyline(listOf(Pt(c.x - rr, c.y), Pt(c.x - rr * 0.5, c.y)), pen)
                    r.strokePolyline(listOf(Pt(c.x + rr * 0.5, c.y), Pt(c.x + rr, c.y)), pen)
                }
                RulerButton.LOCK_ANGLE -> {
                    val s = radius * 0.5
                    val v = Pt(c.x - s, c.y + s)
                    r.strokePolyline(listOf(v, Pt(c.x + s, c.y + s)), pen)
                    r.strokePolyline(listOf(v, Pt(c.x + s, c.y - s)), pen)
                    r.strokePolyline(arcPolyline(v, s * 0.95, 0.0, -45.0, 6), pen)
                }
            }
        }
    }

    private fun arcPolyline(center: Pt, radius: Double, startDeg: Double, endDeg: Double, segments: Int): List<Pt> {
        val pts = ArrayList<Pt>(segments + 1)
        for (i in 0..segments) {
            val t = Math.toRadians(startDeg + (endDeg - startDeg) * i / segments)
            pts.add(Pt(center.x + radius * cos(t), center.y + radius * sin(t)))
        }
        return pts
    }

    /** A small pill + text readout in viewport space, kept on-screen. */
    private fun drawReadout(r: Renderer, text: String, at: Pt, density: Double, pal: Palette) {
        val font = FontSpec(7.0 * density, bold = true)
        val padX = 7.0 * density
        val padY = 4.0 * density
        val textW = text.length * font.pointSize * 1.25
        val textH = font.pointSize * 2.1
        val w = textW + padX * 2
        val h = textH + padY * 2
        val left = (at.x - w / 2.0).coerceIn(2.0, (state.viewportW - w - 2.0).coerceAtLeast(2.0))
        val top = (at.y - h / 2.0).coerceIn(2.0, (state.viewportH - h - 2.0).coerceAtLeast(2.0))
        r.fillRect(Rect(left, top, w, h), pal.menuBg.scaleAlpha(0.92))
        r.strokeRect(Rect(left, top, w, h), Pen(pal.textDim, 1.2, cosmetic = true))
        r.drawText(text, Rect(left + padX, top + padY, textW + 2.0, textH), font, pal.text)
    }

    companion object {
        const val MIN_SAMPLE_DIST = 1.0

        /** Pen-up reduction tolerance, viewport px at the draw zoom (see [simplifyForCommit]). */
        const val SIMPLIFY_EPS = 0.2

        /** Scale on the ink low-pass lengths for a stroke drawn at [zoom], captured at pen-down
         *  and carried on the stroke. Screen-space like the capture gate and the pen-up tolerance:
         *  the smoothing hides the digitizer's jitter, which is a fixed size on screen, so writing
         *  small at high zoom must not be smoothed as if it were written large. Capped at 1 so
         *  zooming out cannot smear content detail the page will keep. */
        fun smoothScaleFor(zoom: Double): Double =
            if (zoom.isFinite() && zoom > 0.0) (1.0 / zoom).coerceAtMost(1.0) else 1.0

        const val MOVE_EPS = 0.01

        /** Vendor key some pens send for a genuinely held side button (OnePlus Pad Go 2 Stylo,
         *  which reports a real down/up pair with auto-repeat rather than a momentary click). */
        const val VENDOR_HELD_BUTTON_KEYCODE = KeyEvent.KEYCODE_F21

        /** Stylus side-button bits, widened past the S-Pen primary so pens on the secondary/tertiary lines count too. */
        val STYLUS_BUTTON_MASK =
            MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or
                MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY

        /** Magic wand: idle time (ms) after the last stroke before the held batch fades. */
        const val WAND_HOLD_MS = 1000L

        /** Magic wand: fade-out duration (ms) once the batch starts disappearing. */
        const val WAND_FADE_MS = 500.0

        /** Ruler: a stylus-down within this (dp) of a long edge snaps the stroke to that edge. */
        const val RULER_SNAP_DP = 12.0

        /** Ruler: finger hit radius (dp) for the on-ruler control buttons. */
        const val RULER_BTN_HIT = 22.0

        /** Ruler: finger hit radius (dp) for the rotation handles. */
        const val RULER_HANDLE_HIT = 24.0

        /** Max finger drift (viewport px) from touch-down still counted as a tap (e.g. tap-to-dismiss). */
        const val TAP_SLOP = 12.0

        /** Padding (content px) added around erased items' bounds when repairing
         *  the cache, to cover stroke anti-aliasing at the dirty-rect edge. */
        const val REPAIR_PAD = 2.0

        /** Drawn side length (viewport px) of a resize-handle square. Also sizes the table edit
         *  chrome (per-line handles and add/remove buttons), which derives from it. */
        const val HANDLE_SIZE = 24.0

        /** Touch radius (viewport px) around a handle centre — larger than the drawn
         *  square so a fingertip can grab it without the squares obscuring content. */
        const val HANDLE_HIT = 36.0

        /** Gap (viewport px) from the selection's top edge up to the rotate grip. */
        const val ROTATE_ARM = 44.0
        const val SHAPE_MIN_DRAG = 3.0

        /** Min capture size (content px, both axes) for the screenshot tool; below it a drag is a tap. */
        const val SHOT_MIN = 6.0

        /** Min drag (viewport px, either axis) for a text-box gesture to size a box rather than tap-create. */
        const val TEXT_DRAG_SLOP = 14.0

        /** Text point-size clamp for the style bar. */
        const val TEXT_MIN_PT = 6.0
        const val TEXT_MAX_PT = 96.0
        const val LONG_PRESS_MS = 450L
        const val LONG_PRESS_SLOP = 6.0

        /** Hold-to-snap: how long the pen must rest (ms) before a freehand stroke snaps to a shape. */
        const val SHAPE_DWELL_MS = 500L

        /** Hold-to-snap: pen drift (viewport px) above which the dwell timer restarts rather than fires. */
        const val SHAPE_DWELL_SLOP = 4.0

        /** Hold-to-snap: minimum samples before recognition is even attempted (mirrors the recognizer). */
        const val SHAPE_MIN_SAMPLES = 8

        /** Shape size -> thickness: the midpoint of the default pen's pressure width range (m=0.35..1.0),
         *  so a shape reads as thick as a same-size pen instead of as a flat full-width line. */
        const val SHAPE_PEN_PARITY = 0.675

        /** A line/arrow drawn with the shape tool snaps flat when within this angle (deg) of an axis.
         *  Half the recognizer's hold-to-snap angle: a live drag is steadier, so it needs less help. */
        const val SHAPE_AXIS_SNAP_DEG = 4.0

        /** Half-width (rad) of the sticky zone around each 90-degree step for snap-rotation. */
        val ROTATE_SNAP_STICK_RAD = Math.toRadians(6.0)

        // Inertial fling tuning (viewport px/s).
        const val VEL_SMOOTH = 0.4 // EMA weight on the previous velocity estimate
        const val FLING_FRICTION = 2.5 // higher = stops sooner; lower = floatier
        const val FLING_MIN_START = 120.0 // minimum flick velocity to start a glide
        const val FLING_MIN_STOP = 24.0 // velocity at which the glide ends

        // Elastic overscroll tuning (pull past the bottom end to add a page).
        const val OVERSCROLL_RESIST = 0.5 // fraction of past-end finger travel that becomes stretch (tighter < 1)
        const val OVERSCROLL_MAX = 320.0 // hard cap on the visible stretch (viewport px)
        const val OVERSCROLL_TRIGGER = 250.0 // stretch at which releasing appends a page
        const val OVERSCROLL_SPRING = 14.0 // spring-back rate toward rest (1/s; higher = snappier)

        // Paginated page-flip tuning.
        const val FLIP_RESIST = 0.55 // fraction of past-edge finger travel that becomes pull
        const val FLIP_TRIGGER = 90.0 // pull (viewport px) at which releasing flips the page
        const val FLIP_FLING_VEL = 900.0 // finger velocity (viewport px/s) that flips from the edge
        const val FLIP_MAX_FRACTION = 0.6 // pull cap, as a fraction of the viewport width
    }
}
