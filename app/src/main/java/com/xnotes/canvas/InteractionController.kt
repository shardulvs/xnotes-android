package com.xnotes.canvas

import android.os.Build
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
import com.xnotes.core.history.EditLabel
import com.xnotes.core.history.EditText
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.RefillShapes
import com.xnotes.core.history.ReorderItems
import com.xnotes.core.history.Regroup
import com.xnotes.core.infinite.sameOrder
import com.xnotes.core.model.Arrange
import com.xnotes.core.model.arrangeOrder
import com.xnotes.core.history.RemoveMarkup
import com.xnotes.core.history.ReplacePageItems
import com.xnotes.core.history.ResizeItem
import com.xnotes.core.history.RestyleItems
import com.xnotes.core.history.RestyleText
import com.xnotes.core.history.TransferItems
import com.xnotes.core.history.TransformItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorDraft
import com.xnotes.core.model.ConnectorEdit
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.EraseRule
import com.xnotes.core.model.deepCopy
import com.xnotes.core.model.deepCopyAll
import com.xnotes.core.model.GeoHandle
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.RectHandle
import com.xnotes.core.model.Resizable
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.ShapeHandle
import com.xnotes.core.model.GeometrySnapshot
import com.xnotes.core.model.Glue
import com.xnotes.core.model.GlueFollow
import com.xnotes.core.model.Labelled
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TapeHandle
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TapeMode
import com.xnotes.core.model.TapePattern
import com.xnotes.core.tape.TapeGeometry
import com.xnotes.core.model.TextHandle
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.stroke.RecognizedShape
import com.xnotes.core.stroke.Scribble
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.ShapeRecognizer
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.MarkupMode
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
    TEXT_DRAG, RULER_MOVE, RULER_TRANSFORM, RULER_ROTATE, PDF_TEXT, VERTEX, POLYGON, CONNECT, JOINT, SLIDE, TAPE,
    TABLE_LINE, TABLE_GRIP, TABLE_PICK, TABLE_DRAW,
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
    /** The turn (deg cw): the view's page rotation, plus a label's shape; the overlay spins by this around (x, y). */
    val rotation: Double = 0.0,
    /** Every line centred, as a shape's label sets. */
    val centred: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val align: com.xnotes.core.pal.HAlign = com.xnotes.core.pal.HAlign.LEFT,
) {
    /** The field's on-screen bounding box, turned corners and all. */
    fun screenBounds(): Rect {
        val a = Math.toRadians(rotation)
        val cs = kotlin.math.cos(a)
        val sn = kotlin.math.sin(a)
        val w = width * zoom
        val h = height * zoom
        return Rect.bounding(listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, h), Pt(0.0, h)).map { Pt(x + it.x * cs - it.y * sn, y + it.x * sn + it.y * cs) })
    }
}

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
    /** Long-press on empty space: open a context menu at (viewport, content). */
    /** Long press on empty space, or on a locked item: the third argument is that item, if any. */
    private val onContextMenu: (Pt, Pt, CanvasItem?) -> Unit = { _, _, _ -> },
    /** Pulled past the document's bottom end far enough and released: append a new page. */
    private val onAddPageAtEnd: () -> Unit = {},
    /** A short haptic tick (e.g. the overscroll pull crossed the add-page threshold). */
    private val onHaptic: () -> Unit = {},
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

    /** Host hook for a finger tap on a canvas item's or the flow text's own hyperlink, tried before
     *  selecting, markups and PDF links since those sit on top. True when it opened one. */
    var onItemLinkTap: ((pageIndex: Int, pageLocal: Pt) -> Boolean)? = null

    /** The view was panned or pinched by hand, as distinct from moved by the app. */
    var onUserMovedView: (() -> Unit)? = null

    /** A finger tap that may land on an audio pill, at [pageLocal] on page [pageIndex]; true when one took it. */
    var onAudioTap: ((pageIndex: Int, pageLocal: Pt) -> Boolean)? = null

    /**
     * A tap that may open a markup, at viewport point [at]: a pan tap ([withLink], so a menu can offer
     * the link under it) or the markup tool's. True when a markup or its note icon took it.
     */
    var onMarkupTap: ((at: Pt, withLink: Boolean) -> Boolean)? = null

    /** A pan tap or a markup tool tap at viewport point [at], told before anything acts on it. */
    var onTap: ((at: Pt) -> Unit)? = null

    /** PDF text selection: a free pointer's long press on a PDF page selects there (installed by the Editor). */
    var pdfText: PdfTextController? = null

    /** The open search's matches, tinted under the selections. */
    var searchTints: SearchTints? = null

    /** Text markups the page layers don't show yet, under everything else in the overlay. */
    var markupOverlay: MarkupOverlay? = null

    /** The icons over noted markups, above all the page's content. */
    var noteIcons: NoteIcons? = null
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

    /** Reader mode: every pointer pans and follows links, and a long press only selects PDF text. */
    var readOnly: Boolean = false

    /** The side button, latched as on the infinite canvas; read only at touch-down, so a press
     *  after the pen is already down does not activate the mapped tool. */
    private val stylusButtons = StylusButtonLatch()

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

    // PAN + inertial fling
    private var lastPan = Pt.ZERO
    private var lastMoveMs = 0L
    private var panVel = Pt.ZERO // smoothed finger velocity, viewport px/s
    private var panDownViewport = Pt.ZERO // where the current pan began (viewport px), for tap-to-dismiss
    private var panFromPenButton = false // a side-button pan parks where the pen lifted: no glide
    private var downStoppedFling = false // this touch landed on a moving glide, so its lift isn't a dismiss tap
    private var panMayCommitText = false // pan begun off an open text box: a tap commits it, a drag scrolls
    private var downView: CanvasState.ViewSnapshot? = null // the view as this touch went down, put back if Android takes the touch back
    private var touchTakenBack = false // Android took back a finger of this touch, so the rest of it is ignored
    // Framework singletons, created lazily on first use (always a gesture on the main thread) so
    // the controller's selection/edit logic stays constructible — and unit-testable — off-device.
    private val choreographer by lazy { Choreographer.getInstance() }
    private var flinging = false
    private var flingVel = Pt.ZERO // scroll-space velocity, viewport px/s
    private var lastFlingMs = 0L
    private val flingFrame = Choreographer.FrameCallback { frameTimeNanos -> stepFling(frameTimeNanos) }

    // Animate the incoming spread from the side after a paginated swipe.
    private var pageFlipAnimating = false
    private var pageFlipStartMs = 0L
    private var pageFlipStartOffset = 0.0
    private var pageFlipTargetOffset = 0.0
    private var pageFlipDestinationRow: Int? = null
    private var pageFlipDurationMs = 0.0
    private val pageFlipFrame = Choreographer.FrameCallback { frameTimeNanos -> stepPageFlip(frameTimeNanos) }

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
    /** The text markups this erase took off, one [RemoveMarkup] each in the order taken, so undo puts them back in place. */
    private val eraseMarkups = mutableListOf<RemoveMarkup>()
    /** Filled shapes whose fill alone this erase took, with the fill each had. */
    private val eraseUnfilled = mutableListOf<RefillShapes.Entry>()
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

    fun hasClipboardItems(): Boolean = ItemClipboard.clip != null

    // SHAPE
    var shapeConfig: ShapeConfig = ShapeConfig()
        set(value) {
            val marked = connecting
            field = value
            // Arming a connector chip shows where connectors glue; choosing a shape hides it again.
            if (marked != connecting) requestRender()
        }

    // A connector being drawn with a connector chip armed, on the page it set off from.
    private var connectorDraft: ConnectorDraft? = null
    private var connectorPage = -1

    /** The connection points lit for the chrome, with their pages: under a hovering pen, at a drawn connector's ends. */
    private var connectHot: List<Pair<Int, Pt>> = emptyList()

    /** True when the shape tool has a connector chip armed, so the points connectors glue to show. */
    val connecting: Boolean get() = tool == Tool.SHAPE && shapeConfig.connector != null

    /** True while the points connectors glue to show: a connector armed, or an end being dragged to glue again. */
    private val marking: Boolean
        get() = connecting || jointEdit?.grab.let { it == ConnectorEdit.Grab.START || it == ConnectorEdit.Grab.END }

    // Connectors kept joined to what a gesture moves, page by page, drawn live (lifted) while it runs.
    private var follows: List<Pair<Int, GlueFollow>> = emptyList()
    private var followed: Set<CanvasItem> = emptySet()
    private var followedFrom: List<Rect> = emptyList()

    // A drag of a lone connector's own handle.
    private var jointEdit: ConnectorEdit? = null
    private var jointPage = -1
    private var jointDownAt = Pt.ZERO
    private var jointMoved = false
    private var lastWaypointTap = -1
    private var lastWaypointTapAt = 0L

    // A lone connector dragged by its line lets go of what it joined; how it was, for undo.
    private var looseBefore: GeometrySnapshot? = null

    // A label being slid along its line, on its page, and the item as the slide found it.
    private var slideSel: Selected? = null
    private var slideBefore: GeometrySnapshot? = null
    private var pendingShape: ShapeItem? = null
    private var shapePageIndex: Int? = null

    // TABLE (drawn like a shape). The defaults come from the editor's table-tool settings, read live.
    var tableToolDefaults: () -> TableToolDefaults = { TableToolDefaults(3, 3, 2.0) }
    private var pendingTable: TableItem? = null
    private var tableDrawPageIndex: Int? = null
    private var tableDrawStart = Pt.ZERO

    // TAPE (laid by a drag; a tap on one peels it back or covers it again)
    private var pendingTape: TapeItem? = null
    private var tapePageIndex: Int? = null
    private var tapeStart: Pt? = null                // the press, page space
    private val tapePath = ArrayList<Pt>()           // a freehand band's path so far, page space
    private var tapeReach = 0.0                      // farthest the drag has gone from the press, page px

    // RESIZE
    private var resizeItem: CanvasItem? = null
    private var resizeHandle: HandleId? = null
    private var resizeOldGeom: GeoHandle? = null
    private var resizePageIndex: Int = -1

    // TABLE_LINE (dragging an interior row/column boundary of the selected table)
    private var tableLineItem: TableItem? = null
    private var tableLineBefore: GeometrySnapshot? = null
    private var tableLineIsColumn = false
    private var tableLineIndex = -1
    private var tableLinePageIndex = -1

    // The columns and rows picked on the selected table's handle bars; null means all of them.
    private var tableColPick: IntRange? = null
    private var tableRowPick: IntRange? = null
    // TABLE_GRIP (dragging a bar end, resizing the picked range) and TABLE_PICK (dragging along a bar).
    private var tableGripAtEnd = true
    private var tablePickAnchor = -1
    private var tablePickPrior: IntRange? = null

    // GENERIC TRANSFORM (resize + rotate for any single non-line / multi / mixed selection)
    private var selObb: Obb? = null // the tilting selection box; null when nothing is selected
    private var txItems: List<Selected> = emptyList()
    private var txSnaps: List<GeometrySnapshot> = emptyList()
    private var txStartObb: Obb? = null
    private var txMargin = 0.0 // how far outside the box its handles sat, kept behind the pointer
    private var txHandle: HandleId? = null // non-null = scale via this box handle; null = rotate

    // A point drag on a lone shape: what it grabbed, the shape before (for a tap that changes
    // nothing) and the free, maybe split shape the drag measures from.
    private var vertexSel: Selected? = null
    private var vertexSpot: com.xnotes.core.shapes.PathSpot? = null
    private var vertexBefore: GeometrySnapshot? = null
    private var vertexBase: GeometrySnapshot? = null
    private var vertexDownAt = Pt.ZERO
    private var vertexMoved = false
    private var vertexWasEdge = false
    private var knobIndex = -1 // set when the point drag is a knob's rather than a vertex's

    // The last tap on a vertex dot, for the double tap that deletes it.
    private var lastVertexTap: com.xnotes.core.shapes.PathSpot? = null
    private var lastVertexTapAt = 0L
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

    /** The page whose PDF text the armed long press would select, -1 for none. */
    private var longPressTextPage = -1

    /** A press zoom lock kept from panning, where it went down: a tap there still ends a PDF text selection. */
    private var lockedPressAt: Pt? = null

    /** A Shape tool press that only put the selection away, where it went down: a tap there is still a Shape tool tap. */
    private var dismissPressAt: Pt? = null

    /** Where the markup tool pressed, in the viewport, so a lift there counts as a tap. */
    private var markupPressAt: Pt? = null

    // TEXT EDITING
    private var editingText: TextItem? = null
    private var editingIsNew = false
    private var editingOldText = ""
    private var editingPageIndex = -1
    val editingItem: TextItem? get() = editingText

    /** True while a text box or a shape's label is open in the overlay. */
    val isEditingText: Boolean get() = editingText != null || editingLabel != null

    /** True while a shape's label is open in the overlay. */
    val isEditingLabel: Boolean get() = editingLabel != null
    val editingPage: Int get() = editingPageIndex

    // LABEL EDITING (a shape's label, typed in the same overlay a text box uses)
    private var editingLabel: Labelled? = null
    private var labelPageIndex = -1
    private var labelBefore: ShapeLabel? = null

    // DOUBLE TAP (a shape's label)
    private var tapCandidate = false
    private var tapDownViewport = Pt.ZERO
    private var tapDownTime = 0L
    private var lastTapViewport: Pt? = null
    private var lastTapTime = 0L

    // What was selected as each tap went down: a double tap labels only a shape that was not.
    private var tapDownSelected: Set<CanvasItem> = emptySet()
    private var lastTapSelected: Set<CanvasItem> = emptySet()

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
            item === editingText || item === editingLabel || item in followed || frontInk?.holding(item) == true ||
                selection.any { it.item === item }
        }
        // A selected fill under the typed text stays under the ink, moving with the drag.
        state.paintLiftedUnderText = { page, r ->
            for (sel in selection) {
                if (sel.item in followed || state.document.pages.getOrNull(sel.pageIndex) !== page) continue
                if (!state.isUnderText(page, sel.item)) continue
                r.withSave {
                    r.translate(moveOffset.x, moveOffset.y)
                    state.applyPageTransform(r, page)
                    state.paintItem(sel.item, r)
                }
            }
        }
    }

    val hasSelection: Boolean get() = selection.isNotEmpty()

    /** The selected items, in the order they were picked. */
    fun selectedItems(): List<CanvasItem> = selection.map { it.item }

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
        finishPolygonDraft()
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

    /** Peel back every tape in the note, or cover them all again, repainting only where they lie. */
    fun setAllTapePeeled(peeled: Boolean) {
        for (page in state.document.pages) {
            var dirty: Rect? = null
            for (item in page.items) {
                if (item !is TapeItem || item.peeled == peeled) continue
                item.peeled = peeled
                dirty = dirty?.union(item.paintBounds()) ?: item.paintBounds()
            }
            dirty?.let { repairRegion(page, it) }
        }
        requestRender()
    }

    /** Toggle disappearing-ink mode; turning it off vanishes anything currently held or fading. */
    fun toggleWand() {
        wandMode = !wandMode
        if (!wandMode) clearFading()
        requestRender()
    }

    // --- touch entry point ---

    fun onTouch(e: MotionEvent): Boolean {
        if (touchTakenBack && e.actionMasked != MotionEvent.ACTION_DOWN) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTakenBack = false
                tapDown(e)
                handleDown(e)
                downView = state.snapshotView()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapCandidate = false
                handlePointerDown(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (tapCandidate && Pt(e.x.toDouble(), e.y.toDouble()).distanceTo(tapDownViewport) > TAP_SLOP) tapCandidate = false
                handleMove(e)
            }
            MotionEvent.ACTION_POINTER_UP ->
                if (movingView() && isCancelledLift(e)) {
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

    private fun movingView(): Boolean = mode == PointerMode.PAN || mode == PointerMode.PINCH

    /** Android took this touch back, as its palm rejection does with a resting hand: drop the
     *  gesture, and put back a view it scrolled or zoomed, since the hand never meant to. */
    private fun takeBackTouch() {
        tapCandidate = false
        val movedView = movingView()
        abortGesture()
        if (movedView) downView?.let { state.restoreView(it); onViewChanged() }
        requestRender()
    }

    fun onHover(e: MotionEvent): Boolean {
        if (handleHoverAction(e)) return true
        if (hoverConnect(e)) return true
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
        val buttonNow = e.actionMasked != MotionEvent.ACTION_HOVER_EXIT && stylusButtons.heldFor(e)
        // A press that may still be a click waits, so a click never erases under the hovering pen.
        val want = penButtonHover && buttonNow && !StylusButtonLatch.clicks.mayStillClick(e.eventTime) &&
            (penButtonTool == Tool.ERASER && !readOnly || penButtonTool == Tool.PAN)
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
        if (stylusButtons.onGenericMotion(e) && hoverActionTool != null) endHoverAction()
    }

    /** Feeder C: Bluetooth/USI pens often deliver the side button only as a KeyEvent, never in any
     *  MotionEvent buttonState. Latch those ([down] true on key-down); a key-up also ends a live
     *  hover gesture. Returns true if the key was a stylus side button, so the host consumes it. */
    fun onStylusButtonKey(keyCode: Int, down: Boolean): Boolean {
        if (!stylusButtons.onKey(keyCode, down)) return false
        if (!stylusButtons.held && hoverActionTool != null) endHoverAction()
        return true
    }

    private fun handleDown(e: MotionEvent) {
        if (hoverActionTool != null) endHoverAction() // a hovering side-button gesture yields to contact
        downStoppedFling = flinging // captured before stopping: a tap that only halts a glide must not dismiss
        stopFling() // a new touch halts any in-progress glide
        if (pageFlipAnimating) finishPageFlip() // a touch mid-turn lands it rather than undoing it
        if (!state.verticalScroll) clearFlipPull()
        stopOverscrollSettle() // ...and lets a re-grab take over the elastic mid-spring
        panMayCommitText = false // a fresh gesture; the editing branch below re-arms it if it applies
        lockedPressAt = null
        dismissPressAt = null
        markupPressAt = null
        val toolType = e.getToolType(0)
        val vx = e.getX(0).toDouble()
        val vy = e.getY(0).toDouble()
        val content = state.viewportToContent(Pt(vx, vy))
        drawingPointerId = e.getPointerId(0)
        drawingIsStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS


        // Resolve which tool this pointer drives:
        //  - the stylus eraser end, or the held side button, force the eraser/side-button tool;
        //  - a finger pans unless finger-draw is enabled;
        //  - otherwise the armed tool.
        val buttonHeld = stylusButtons.heldFor(e)
        val effectiveTool: Tool = when {
            readOnly -> Tool.PAN
            toolType == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            buttonHeld && penButtonTool != null -> penButtonTool!!
            // While something is selected, the stylus grabs that selection (resize on a handle,
            // move on the body) instead of inking through it, matching the finger. Off the
            // selection a pen or tape draws and dismisses it (see beginDraw); the Shape tool only dismisses.
            hasSelection && drawingIsStylus && (tool.isStroke || tool == Tool.SHAPE || tool == Tool.TAPE) &&
                drawingGrabsSelection(content) -> Tool.SELECT
            hasSelection && tool == Tool.TABLE && fingerHitsSelection(content) -> Tool.SELECT
            // A finger may grab/resize the ACTIVE selection even when finger-draw is off;
            // off the selection it still pans.
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff &&
                fingerHitsSelection(content) -> Tool.SELECT
            // A finger otherwise pans instead of drawing/selecting/shaping/erasing (text stays usable by finger).
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff -> Tool.PAN
            else -> tool
        }
        val isFinger = toolType == MotionEvent.TOOL_TYPE_FINGER
        val free = FreePointer.isFree(tool, effectiveTool, isFinger)

        // A press off the box while editing. With a finger we defer: a tap commits (re-arming the
        // prior tool), a drag scrolls the page with the edit kept live (decided in handleUp's PAN
        // branch). A stylus / side-button / eraser press commits now and proceeds as usual. A Text
        // press never spawns a new box on the same gesture (that double-create was a bug).
        if (editingText != null || editingLabel != null) {
            if (toolType == MotionEvent.TOOL_TYPE_FINGER && (effectiveTool == Tool.TEXT_BOX || editingLabel != null)) {
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

        // Taken before a draft is finished below, since that selects its polygon.
        val selected = hasSelection

        // With a connector armed, a press on a point it can glue to starts one, or only dismisses what is selected.
        val inks = toolType != MotionEvent.TOOL_TYPE_FINGER || fingerDraws
        if (connecting && inks && !buttonHeld && toolType != MotionEvent.TOOL_TYPE_ERASER && glueHitAt(content) != null) {
            cancelLongPress()
            if (polygonDraft != null) finishPolygonDraft()
            if (selected) dismissFirst(content) else beginConnector(content)
            return
        }

        // A PDF text selection's handle drags with any pointer. Otherwise only a pan or a pinch keeps
        // the selection: anything else ends it now, and a tap ends it on release.
        pdfText?.let { text ->
            if (text.press(Pt(vx, vy))) {
                mode = PointerMode.PDF_TEXT
                cancelLongPress()
                return
            }
            if (effectiveTool != Tool.PAN) text.clear()
        }

        // Zoom-lock pan preference: swallow a single-finger pan when locked and set to "double"/"none".
        // The pointer stays free, so a long press still picks up an item or selects text.
        if (effectiveTool == Tool.PAN && !singleFingerPanAllowed()) {
            mode = PointerMode.IDLE
            lockedPressAt = Pt(vx, vy)
            armLongPress(Pt(vx, vy), content, isFinger, free)
            return
        }

        // A draft only lives through Free polygon presses; anything else that draws or edits ends it.
        if (polygonDraft != null && effectiveTool != Tool.PAN && !(effectiveTool == Tool.SHAPE && shapeConfig.placesCorners)) {
            finishPolygonDraft()
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
            effectiveTool == Tool.SHAPE && selected -> dismissFirst(content)
            effectiveTool == Tool.SHAPE && shapeConfig.placesCorners -> beginPolygonTap(content)
            effectiveTool == Tool.SHAPE && shapeConfig.connector != null -> beginConnector(content)
            effectiveTool == Tool.SHAPE -> beginShape(content)
            effectiveTool == Tool.TABLE -> beginTable(content)
            effectiveTool == Tool.TEXT -> beginTextGesture(content, Pt(vx, vy))
            effectiveTool == Tool.TEXT_BOX -> beginTextBoxGesture(content)
            effectiveTool == Tool.MARKUP -> beginMarkup(content)
            effectiveTool == Tool.TAPE -> beginTape(content)
            else -> Unit
        }
        // The flow caret owns its own long press (word selection), mid text-drag it is suppressed so
        // a hold-then-drag still sizes a box, and a markup drag held still is just a slow drag.
        if (mode != PointerMode.FLOW_TEXT && mode != PointerMode.TEXT_DRAG && mode != PointerMode.PDF_TEXT &&
            mode != PointerMode.POLYGON
        ) {
            armLongPress(Pt(vx, vy), content, isFinger, free)
        }
    }

    /** The markup tool's press: selects, or marks, the PDF text it then drags over; anywhere else nothing. */
    private fun beginMarkup(content: Pt) {
        val text = pdfText ?: return
        val (page, local) = state.pagePointAt(content) ?: return
        if (state.document.pages.getOrNull(page)?.pdfPage == null) return
        mode = PointerMode.PDF_TEXT
        markupPressAt = state.contentToViewport(content)
        text.beginToolDrag(page, local, mark = configFor(Tool.MARKUP).markupMode != MarkupMode.SELECT)
    }

    private fun handlePointerDown(e: MotionEvent) {
        cancelLongPress()
        // A second finger turns a PDF text drag into a pinch; the selection stays, a mark in the making goes.
        if (mode == PointerMode.PDF_TEXT) {
            pdfText?.interrupt()
            mode = PointerMode.IDLE
        }
        // A second finger on a ruler being moved twists/translates it instead of pinch-zooming.
        if (mode == PointerMode.RULER_MOVE && e.pointerCount >= 2) {
            beginRulerTransform(e)
            return
        }
        if (mode == PointerMode.RULER_ROTATE) return // handle-drag rotation ignores extra fingers
        if (mode == PointerMode.DRAW && drawingIsStylus) return
        // Tape laid with the pen ignores a resting hand as its ink does; laid with a finger, it yields to the pinch.
        if (mode == PointerMode.TAPE && drawingIsStylus) return
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
            PointerMode.TABLE_LINE -> extendTableLine(content)
            PointerMode.TABLE_GRIP -> extendTableGrip(content)
            PointerMode.TABLE_PICK -> extendTablePick(content)
            PointerMode.TABLE_DRAW -> extendTable(content)
            PointerMode.TRANSFORM -> extendTransform(content)
            PointerMode.VERTEX -> extendVertex(content)
            PointerMode.POLYGON -> showPolygonDraft(content)
            PointerMode.SHAPE -> extendShape(content)
            PointerMode.TAPE -> extendTape(content)
            PointerMode.CONNECT -> extendConnector(content)
            PointerMode.JOINT -> extendJoint(content)
            PointerMode.SLIDE -> extendSlide(content)
            PointerMode.FLOW_TEXT ->
                // A plain drag with the text tool scrolls the document; only a long-pressed
                // drag extends the selection (dragTo returns true to request the pan handoff).
                if (flowText?.dragTo(content, Pt(vx, vy)) == true) beginPan(vx, vy)
            PointerMode.TEXT_DRAG -> extendTextDrag(content)
            PointerMode.PDF_TEXT -> pdfText?.dragTo(Pt(vx, vy))
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
                if (tap) onTap?.invoke(upViewport)
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
                    // A tap ends a PDF text selection and does nothing else.
                    val textCleared = tap && pdfText?.selection != null
                    if (textCleared) pdfText?.clear()
                    // A finger tap (no drag) that didn't just halt a glide, landing off the current
                    // selection, dismisses it — the finger's counterpart to the stylus's empty-tap clear.
                    val linkHandled = !textCleared && tap && state.overscrollY <= 0.0 && tryLinkTap(content)
                    when {
                        textCleared || linkHandled -> Unit
                        state.overscrollY > 0.0 -> releaseOverscroll()
                        tap && hasSelection && selectionChromeBox()?.contains(content) != true -> clearSelection()
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
            PointerMode.TABLE_LINE, PointerMode.TABLE_GRIP -> endTableLine()
            PointerMode.TABLE_PICK -> endTablePick()
            PointerMode.TABLE_DRAW -> endTable()
            PointerMode.TRANSFORM -> endTransform()
            PointerMode.VERTEX -> endVertex(e.eventTime)
            PointerMode.POLYGON -> endPolygonTap(content, e.eventTime)
            PointerMode.SHAPE -> endShape()
            PointerMode.TAPE -> endTape()
            PointerMode.CONNECT -> endConnector()
            PointerMode.JOINT -> endJoint(e.eventTime)
            PointerMode.SLIDE -> endSlide()
            PointerMode.FLOW_TEXT -> {
                mode = PointerMode.IDLE
                flowText?.release(content, Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble()), e.eventTime)
            }
            PointerMode.TEXT_DRAG -> endTextDrag(content)
            PointerMode.PDF_TEXT -> {
                mode = PointerMode.IDLE
                // A markup tool tap marks nothing; on a markup it opens that markup's menu.
                val pressAt = markupPressAt
                markupPressAt = null
                pdfText?.release()
                val up = Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble())
                if (pressAt != null && up.distanceTo(pressAt) <= TAP_SLOP) {
                    onTap?.invoke(pressAt)
                    onMarkupTap?.invoke(pressAt, false)
                }
            }
            PointerMode.IDLE -> {
                lockedPressAt?.let { at ->
                    lockedPressAt = null
                    val up = Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble())
                    if (up.distanceTo(at) <= TAP_SLOP) {
                        onTap?.invoke(up)
                        pdfText?.clear()
                    }
                }
                dismissPressAt?.let { at ->
                    dismissPressAt = null
                    if (tapCandidate) state.pageIndexAtContent(at)?.let { shapeTap(it, state.toPageSpace(it, at)) }
                }
            }
            PointerMode.RULER_MOVE -> { mode = PointerMode.IDLE; requestRender() }
            PointerMode.RULER_TRANSFORM -> { mode = PointerMode.IDLE; requestRender() }
            PointerMode.RULER_ROTATE -> { mode = PointerMode.IDLE; requestRender() }
            else -> Unit
        }
    }

    /**
     * Offer a finger tap at [content], top layer first: to [onItemLinkTap], to [fingerTapSelect] with
     * the Select tool, to [onMarkupTap], then as a page + page-space point to [onLinkTap].
     */
    private fun tryLinkTap(content: Pt): Boolean {
        state.pageIndexAtContent(content)?.let { i ->
            // A tape lies over whatever else the tap could reach, so it takes the tap first.
            if (peelTapeAt(i, state.toPageSpace(i, content))) return true
            if (onAudioTap?.invoke(i, state.toPageSpace(i, content)) == true) return true
            if (onItemLinkTap?.invoke(i, state.toPageSpace(i, content)) == true) return true
        }
        if (tool == Tool.SELECT && fingerTapSelect(content)) return true
        if (onMarkupTap?.invoke(state.contentToViewport(content), true) == true) return true
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
            val end = if (start != null && !ruler.visible) ResizeMath.snapAxis(Pt(start.x, start.y), local) else local
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
        if (stroke != null && pi != null && !stroke.isEmpty) {
            // The pen is up: rebuild with lift-time rules on (a dot-sized calligraphy stroke
            // takes the nib's broad face) before the stroke is committed or held for fading.
            stroke.finished = true
            when {
                // A bare tap whose only job was to dismiss the selection: drop the dot it would leave.
                strokeDismissedSelection && tapCandidate -> Unit
                // A bare tap on tape peels it back or covers it again, and leaves no dot.
                tapCandidate && e.eventTime - tapDownTime < LONG_PRESS_MS && peelTapeAt(pi, Pt(stroke.xAt(0), stroke.yAt(0))) -> Unit
                wandMode && stroke.tool.isStroke -> {
                    // Disappearing ink: held ephemerally, never committed to the model/undo/cache/save.
                    // A stroke drawn mid-fade cancels the fade and re-solidifies the whole held batch.
                    fadingStrokes.add(FadingStroke(stroke, pi))
                    frontInk?.holdFading(stroke)
                    stopFade()
                    fadeAlpha = 1.0
                    scheduleFade()
                }
                scribbleErase(stroke, pi) -> Unit
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
        // A neon pen snaps to a neon shape (the highlighter never snaps).
        val shape = rec.toShape(
            color, strokeWidth, shapeConfig.fillFor(rec.kind, color), stroke.config.neon, stroke.config.neonStrength,
            dashed, stroke.config.dashLength, stroke.config.dashGap,
        )
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

    /**
     * Scribble to erase: when [stroke] reads as a scribble over ink, take that ink off instead of
     * filing the scribble. The undo stack gets the scribble and then its erase, so one undo says
     * "that was not an erase" and gives both back. False when the stroke stays ink.
     */
    private fun scribbleErase(stroke: Stroke, pageIndex: Int): Boolean {
        val eraser = configFor(Tool.ERASER)
        if (!eraser.scribbleErase || !stroke.tool.isStroke || stroke.tool == Tool.HIGHLIGHTER) return false
        if (crossedSegments.isNotEmpty()) return false // it ran across a page edge
        val page = state.document.pages.getOrNull(pageIndex) ?: return false
        val dpPerUnit = state.zoom / state.devicePxPerDp
        val path = List(stroke.sampleCount) { Pt(stroke.xAt(it), stroke.yAt(it)) }
        val passes = Scribble.passes(path, dpPerUnit) ?: return false
        val radius = stroke.config.baseWidth / 2.0 + Scribble.TOUCH_DP / dpPerUnit
        val reach = Rect.bounding(path).outset(radius)
        val candidates = ArrayList<Any>()
        page.items.filterTo(candidates) { EraseRule.erasable(it) && it.paintBounds().intersects(reach) }
        if (eraser.eraseMarkups) candidates.addAll(page.markups)
        val ptPerPx = 72.0 / state.document.dpi
        // A scribble is ink, so it takes only lines, never a fill.
        val taken = Scribble.taken(passes, radius, candidates) { c, x, y, r ->
            when (c) {
                is CanvasItem -> EraseRule.touchesLine(c, x, y, r)
                is TextMarkup -> MarkupPainter.touches(c, x * ptPerPx, y * ptPerPx, r * ptPerPx)
                else -> false
            }
        }
        if (taken.isEmpty()) return false
        simplifyForCommit(stroke)
        val items = taken.filterIsInstance<CanvasItem>().sortedBy { item -> page.items.indexOfFirst { it === item } }
        var dirty: Rect? = null
        for (item in items) {
            page.items.remove(item)
            val b = item.paintBounds()
            dirty = dirty?.union(b) ?: b
        }
        dirty?.let { if (!state.repairRegion(page, it.outset(REPAIR_PAD))) state.invalidatePage(page) }
        // The scribble rides with the erase, so undoing it comes back on top of the ink, as drawn.
        val cmds = ArrayList<Command>()
        cmds += EraseItems(items.map { page to it } + (page to stroke))
        val marks = taken.filterIsInstance<TextMarkup>()
        for (m in marks) cmds += RemoveMarkup(page, m).also { it.redo() }
        if (marks.isNotEmpty()) state.rebakeBackground(page, emptyList())
        history.push(AddItem(page, stroke))
        history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
        state.document.dirty = true
        onContentChanged()
        onHaptic()
        return true
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
        eraseMarkups.clear()
        eraseUnfilled.clear()
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
            if (eraseMarkupsFromPage(page, cx, cy, radius)) changed = true
        }
        if (changed) onContentChanged()
        requestRender()
    }

    /** Takes off [page]'s text markups the eraser circle touches, when the eraser is set to; true if any. */
    private fun eraseMarkupsFromPage(page: Page, cx: Double, cy: Double, radius: Double): Boolean {
        if (page.markups.isEmpty() || !configFor(Tool.ERASER).eraseMarkups) return false
        val ptPerPx = 72.0 / state.document.dpi
        val hit = page.markups.filter { MarkupPainter.touches(it, cx * ptPerPx, cy * ptPerPx, radius * ptPerPx) }
        if (hit.isEmpty()) return false
        for (m in hit) eraseMarkups += RemoveMarkup(page, m).also { it.redo() }
        state.rebakeBackground(page, emptyList())
        return true
    }

    /** STROKE mode: remove every stroke/shape the eraser circle touches, and every fill it touches at
     *  all. Images and text boxes are deliberately-placed and protected (delete those via select +
     *  delete). Returns the repaint region, or null if nothing changed. */
    private fun eraseStrokesFromPage(page: Page, cx: Double, cy: Double, radius: Double): Rect? {
        var dirty = unfillOnPage(page, cx, cy, radius)
        val toRemove = page.items.filter { EraseRule.takes(it, cx, cy, radius) }
        if (toRemove.isEmpty()) return dirty
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
        var dirty = unfillOnPage(page, cx, cy, radius)
        var i = 0
        while (i < page.items.size) {
            val item = page.items[i]
            val frags: List<CanvasItem>? = if (!EraseRule.erasable(item)) {
                null
            } else if (EraseRule.whole(item)) {
                // A fill or a tape is never cut: whatever the eraser touches of it, it takes whole.
                if (EraseRule.takes(item, cx, cy, radius)) emptyList() else null
            } else {
                when (item) {
                    is Stroke -> item.erasedBy(cx, cy, radius)
                    // A cut shape's label stays behind as a text box.
                    is ShapeItem -> item.erasedBy(cx, cy, radius)?.let { it + listOfNotNull(item.labelAsTextBox()) }
                    // So does a cut connector's; its glue goes with it.
                    is ConnectorItem -> item.erasedBy(cx, cy, radius)?.let { it + listOfNotNull(item.labelAsTextBox()) }
                    else -> null
                }
            }
            if (frags == null) {
                i++
                continue
            }
            // Snapshot the page's items on first contact this gesture, before mutating it.
            if (!eraseSnapshots.containsKey(page)) eraseSnapshots[page] = page.items.toList()
            for (f in frags) {
                f.sync = item.sync // pieces were written when the whole was
                f.group = item.group
            }
            val b = frags.fold(item.paintBounds()) { acc, f -> acc.union(f.paintBounds()) }
            dirty = dirty?.union(b) ?: b
            page.items.removeAt(i)
            page.items.addAll(i, frags)
            i += frags.size // step past the freshly-inserted fragments
        }
        return dirty
    }

    /** Take the fill alone off the filled shapes the eraser touches inside but not on their outline; the repaint region, or null. */
    private fun unfillOnPage(page: Page, cx: Double, cy: Double, radius: Double): Rect? {
        var dirty: Rect? = null
        for (item in page.items) {
            if (!EraseRule.takesFill(item, cx, cy, radius)) continue
            val shape = item as ShapeItem
            eraseUnfilled += RefillShapes.Entry(shape, shape.fillRgba, null)
            shape.fillRgba = null
            val b = shape.paintBounds()
            dirty = dirty?.union(b) ?: b
        }
        return dirty
    }

    private fun endErase() {
        val cmds = ArrayList<Command>()
        // A fill came off before any cut or removal of its shape, so it goes back after them.
        if (eraseUnfilled.isNotEmpty()) cmds += RefillShapes(eraseUnfilled.toList())
        if (areaErase()) {
            // One drag may split/trim many strokes across pages; commit each touched page's
            // net before/after as one undo step.
            eraseSnapshots.mapNotNullTo(cmds) { (page, before) ->
                val after = page.items.toList()
                if (after != before) ReplacePageItems(page, before, after) else null
            }
        } else if (eraseRemovals.isNotEmpty()) {
            cmds += EraseItems(eraseRemovals.toList())
        }
        cmds += eraseMarkups
        if (cmds.isNotEmpty()) {
            history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
            state.document.dirty = true
            onContentChanged()
        }
        eraseRemovals.clear()
        eraseSnapshots.clear()
        eraseMarkups.clear()
        eraseUnfilled.clear()
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

    /** The single selected line/arrow or straight tape whose two ends are its own resize handles, or
     *  null. Every other selection (single non-line, multi, mixed) uses the generic box handles. */
    private fun singleEndpointShape(): Selected? {
        val sel = selection.singleOrNull() ?: return null
        val item = sel.item
        return if ((item is ShapeItem && item.shape.isEndpointShape) || (item is TapeItem && item.mode == TapeMode.STRAIGHT)) sel else null
    }

    /** The lone selected rectangle of tape, whose box is its own turned frame and whose corners move freely. */
    private fun loneTapeBox(): TapeItem? = (selection.singleOrNull()?.item as? TapeItem)?.takeIf { it.mode == TapeMode.RECTANGLE }

    /** Resize handles for the current selection in content space: a single line/arrow's two
     *  endpoint handles, else the handles of the oriented selection box (corners only for a lone
     *  shape that keeps its aspect). */
    private fun selectionResizeHandles(): List<ResizeHandle> {
        if (singleSelectedTable() != null) return emptyList()
        val endpoint = singleEndpointShape()
        if (endpoint != null) return endpointHandles(endpoint)
        val all = selectionChromeBox()?.let { ResizeMath.obbHandles(it) } ?: return emptyList()
        // A pill scales by its corners and lengthens by its sides; nothing stretches it upright.
        if (selection.all { it.item is AudioItem }) return all.filter { it.id != HandleId.T && it.id != HandleId.B }
        val square = (selection.singleOrNull()?.item as? ShapeItem)?.shape?.keepsAspect == true
        return if (square || loneTapeBox() != null) all.filter { it.id in CORNER_HANDLES } else all
    }

    /** The lone selected connector, whose chrome is its own handles rather than a box. */
    private fun loneConnector(): Selected? = selection.singleOrNull()?.takeIf { it.item is ConnectorItem }

    /** Whether content point [content] lands on the lone connector: a handle, its line or its label. */
    private fun grabsConnector(sel: Selected, content: Pt): Boolean {
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return false
        val c = sel.item as ConnectorItem
        val local = state.toPageSpace(sel.pageIndex, content)
        return ConnectorEdit.grab(c, local, dotReach()) != null || c.touches(local, OUTLINE_GRAB / state.zoom)
    }

    /** The lone selected shape that shows its own frame, or null. */
    private fun framedShape(): Selected? =
        selection.singleOrNull()?.takeIf { (it.item as? ShapeItem)?.frameObb() != null }

    /** The lone selected shape whose point dots can be grabbed: framed, or a line or arrow. */
    private fun pointShape(): Selected? = selection.singleOrNull()?.takeIf { it.item is ShapeItem }

    /**
     * How far the chrome sits outside [selObb]: nothing for a group, whose box already wraps the
     * ink, but a lone shape's frame gets its outline's reach plus a fixed on-screen gap, so the box
     * corners clear the shape's own corners and their dots.
     */
    private fun selectionChromeMargin(): Double {
        val shape = framedShape()?.item as? ShapeItem ?: return 0.0
        return shape.framePad + com.xnotes.core.infinite.OverlayTessellator.FRAME_MARGIN_PX / state.zoom
    }

    /** The box the chrome draws and grabs by: [selObb] pushed out by [selectionChromeMargin]. */
    private fun selectionChromeBox(): Obb? = if (loneConnector() != null) null else selObb?.grow(selectionChromeMargin())

    /** A line/arrow's or a strip of tape's two end handles, mapped from page space into content space. */
    private fun endpointHandles(sel: Selected): List<ResizeHandle> {
        val (start, end) = when (val item = sel.item) {
            is ShapeItem -> item.start to item.end
            is TapeItem -> item.shape.points[0] to item.shape.points[1]
            else -> return emptyList()
        }
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return emptyList()
        return listOf(
            ResizeHandle(HandleId.START, state.fromPageSpace(sel.pageIndex, start)),
            ResizeHandle(HandleId.END, state.fromPageSpace(sel.pageIndex, end)),
        )
    }

    /** The move grip under a selection too small to grab by its body, in content space, or null. */
    private fun selectionMoveGrip(): Pt? {
        val obb = selectionChromeBox() ?: return null
        return ResizeMath.obbMoveGrip(obb, state.zoom, ROTATE_ARM / state.zoom)
    }

    /** Strokes, shapes, connectors and images rotate; text doesn't. A mixed selection rotates only
     *  when every member is rotatable. */
    private fun selectionIsRotatable(): Boolean =
        selection.isNotEmpty() && selection.all { it.item is Stroke || it.item is ShapeItem || it.item is ConnectorItem || it.item is ImageItem || it.item is TapeItem }

    /** Rotate-grip centre (content space, no move offset), out past the oriented box's top edge, or
     *  null when the selection can't rotate or is a single line/arrow (reoriented by an endpoint). */
    private fun selectionRotatePoint(): Pt? {
        if (!selectionIsRotatable() || singleEndpointShape() != null) return null
        val obb = selectionChromeBox() ?: return null
        return ResizeMath.obbRotateGrip(obb, ROTATE_ARM / state.zoom)
    }

    /** True when [content] lands on the active selection — a resize or rotate handle, or inside the
     *  oriented box. Lets a finger grab the selection even when finger-draw is off (off the
     *  selection the finger still pans). */
    private fun fingerHitsSelection(content: Pt): Boolean {
        if (selection.isEmpty()) return false
        loneConnector()?.let { return grabsConnector(it, content) }
        val tol = HANDLE_HIT / state.zoom
        selectionRotatePoint()?.let { if (it.distanceTo(content) <= tol) return true }
        selectionMoveGrip()?.let { if (it.distanceTo(content) <= tol) return true }
        if (tableChromeHit(content)) return true
        if (ResizeMath.hitHandle(selectionResizeHandles(), content, tol) != null) return true
        // A dot can sit outside the box: a knob out on a callout's tail, a thin line's middle.
        pointShape()?.let { sel ->
            if (state.pageRects.getOrNull(sel.pageIndex) != null) {
                if ((sel.item as ShapeItem).dotNear(state.toPageSpace(sel.pageIndex, content), dotReach())) return true
            }
        }
        return selectionChromeBox()?.contains(content) == true
    }

    /**
     * Whether a drawing tool's press at [content] grabs the selection instead of drawing. A lone
     * shape is grabbed only by its handles, its dots, its outline or its fill: inside an unfilled
     * one, or anywhere else in its box, a pen writes and the Shape tool dismisses. Any other
     * selection is grabbed anywhere in its box, as a finger grabs everything.
     */
    private fun drawingGrabsSelection(content: Pt): Boolean {
        loneConnector()?.let { return grabsConnector(it, content) }
        val lone = pointShape() ?: return fingerHitsSelection(content)
        if (state.pageRects.getOrNull(lone.pageIndex) == null) return false
        val tol = HANDLE_HIT / state.zoom
        selectionRotatePoint()?.let { if (it.distanceTo(content) <= tol) return true }
        if (ResizeMath.hitHandle(selectionResizeHandles(), content, tol) != null) return true
        val local = state.toPageSpace(lone.pageIndex, content)
        val shape = lone.item as ShapeItem
        return shape.dotNear(local, dotReach()) || shape.touches(local, OUTLINE_GRAB / state.zoom)
    }

    /** If [content] grabs a resize or rotate handle of the settled selection, begin that gesture
     *  and return true. Handles only; the caller handles an inside-the-bounds move. Shared by the
     *  select and lasso tools so both grab handles the same way. */
    private fun tryGrabSelectionHandle(content: Pt): Boolean {
        if (selection.isEmpty()) return false
        loneConnector()?.let { sel ->
            if (state.pageRects.getOrNull(sel.pageIndex) == null) return false
            val edit = ConnectorEdit.grab(sel.item as ConnectorItem, state.toPageSpace(sel.pageIndex, content), dotReach()) ?: return false
            beginJoint(sel, edit, content)
            return true
        }
        if (tryGrabDot(content)) return true
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
        val endpoint = singleEndpointShape()
        if (endpoint != null) {
            val id = ResizeMath.hitHandle(endpointHandles(endpoint), content, tol) ?: return false
            beginResize(endpoint, id)
            return true
        }
        val id = ResizeMath.hitHandle(selectionResizeHandles(), content, tol) ?: return false
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
        if (beginTableChrome(content)) return
        if (tryGrabSelectionHandle(content)) return
        if (trySlideLabel(content)) return
        // Inside the settled selection the press moves it, whatever it landed on. Hit-testing
        // first would re-pick the stroke under the finger, and you meant to drag what is
        // selected, not to select what happens to sit inside it.
        if (selectionChromeBox()?.contains(content) == true) {
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
                SelectionMath.bandMembers(state.document.pages, state.pageRects, band, configFor(Tool.SELECT).selectWholeItems) { i, p ->
                    state.toPageSpace(i, p)
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
        if (beginTableChrome(content)) return
        if (tryGrabSelectionHandle(content)) return
        if (trySlideLabel(content)) return
        if (selectionChromeBox()?.contains(content) == true) {
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
                state.toPageSpace(i, p)
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
        // A lone connector dragged by its line lets go of what it joined; anything else keeps it.
        val lone = loneConnector()
        looseBefore = lone?.item?.snapshotGeometry()
        beginFollow(rigid = true, pin = lone == null)
        onSelectionMenu(null) // hide while dragging
    }

    private fun extendMove(content: Pt) {
        moveOffset = content - moveOrigin
        if (follows.isNotEmpty()) {
            val local = state.vectorToPageSpace(moveOffset)
            val shift = Affine.translateBy(local.x, local.y)
            for ((_, f) in follows) f.follow(shift) { shift }
        }
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
            val shift = Affine.translateBy(local.x, local.y)
            for ((_, f) in follows) {
                f.follow(shift)
                f.settle()
            }
            selObb = selObb?.translate(moveOffset.x, moveOffset.y)
            // A lone connector let go: recorded whole, so undo glues it back.
            val loose = loneConnector()?.item as? ConnectorItem
            val looseWas = looseBefore
            if (loose != null) loose.letGo()
            val moving = items.filter { it !in followed && !(loose != null && it === loose) }
            val move: Command? = if (moving.isEmpty()) null else MoveItems(moving, local.x, local.y)
            val looseCmd = if (loose != null && looseWas != null) TransformItems(listOf(loose), listOf(looseWas), listOf(loose.snapshotGeometry())) else null
            val joined = followCommand()
            val transfer = reassignSelectionPages()
            val cmds = listOfNotNull(move, looseCmd, joined, transfer)
            history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
            state.document.dirty = true
            // Moved items stay lifted (drawn live in the overlay), so the ink cache — which
            // already excludes them — needs no repair; it is repainted at their final spot
            // when the selection is later cleared.
            onContentChanged()
        }
        // A press that never really moved leaves the connectors as it found them.
        if (!moved) for ((_, f) in follows) f.restore()
        moveOffset = Pt.ZERO
        looseBefore = null
        endFollow()
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
            is TapeItem -> {
                val s = item.shape
                val (a, b) = ResizeMath.resizeOpenShape(s.points[0], s.points[1], handle, local)
                // The end being dragged snaps level or upright against the one held still, as when laid.
                val ends = if (handle == HandleId.START) listOf(TapeGeometry.snap(b, a), b) else listOf(a, TapeGeometry.snap(a, b))
                item.setGeometry(TapeHandle(s.copy(points = ends)))
            }
            is ShapeItem -> {
                val (s, en) = when {
                    item.shape.isEndpointShape -> ResizeMath.resizeOpenShape(item.start, item.end, handle, local)
                    item.shape.keepsAspect -> ResizeMath.resizeSquareShape(item.start, item.end, handle, local)
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
        if (item != null && old != null) {
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
        // The endpoint resize (a single line/arrow) reshapes the box; refit it upright.
        selObb = selectionBox()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
        if (changed) maybeSwitchBackAfterSelect()
    }

    // --- POINT EDITING ---

    /**
     * A press on one of a lone shape's dots: a vertex dot drags that point, an edge dot splits its
     * edge and drags the new point. The dots sit inside the box, so they are tried before it.
     */
    private fun tryGrabDot(content: Pt): Boolean {
        val sel = pointShape() ?: return false
        val shape = sel.item as ShapeItem
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return false
        val local = state.toPageSpace(sel.pageIndex, content)
        val reach = com.xnotes.core.infinite.OverlayTessellator.DOT_TOUCH_PX / state.zoom
        // Knobs first: one can sit right on a vertex, and the knob is the finer control.
        val knob = shape.knobDots().withIndex().minByOrNull { it.value.distanceTo(local) }?.takeIf { it.value.distanceTo(local) <= reach }
        if (knob != null) {
            beginFollow(rigid = false, pin = false)
            vertexBefore = shape.snapshotGeometry()
            vertexBase = vertexBefore
            vertexSel = sel
            vertexSpot = null
            knobIndex = knob.index
            vertexDownAt = content
            vertexMoved = false
            vertexWasEdge = false
            mode = PointerMode.VERTEX
            onSelectionMenu(null)
            requestRender()
            return true
        }
        val vertex = shape.vertexDots().minByOrNull { it.at.distanceTo(local) }?.takeIf { it.at.distanceTo(local) <= reach }
        val edge = if (vertex != null) null else {
            shape.edgeDots(com.xnotes.core.infinite.OverlayTessellator.EDGE_DOT_MIN_PX / state.zoom)
                .minByOrNull { it.at.distanceTo(local) }?.takeIf { it.at.distanceTo(local) <= reach }
        }
        val dot = vertex ?: edge ?: return false
        beginFollow(rigid = false, pin = false)
        vertexBefore = shape.snapshotGeometry()
        shape.toFree()
        vertexSpot = if (edge != null) shape.splitEdge(dot.spot) ?: dot.spot else dot.spot
        vertexBase = shape.snapshotGeometry()
        vertexSel = sel
        vertexDownAt = content
        vertexMoved = false
        vertexWasEdge = edge != null
        selObb = selectionBox()
        mode = PointerMode.VERTEX
        onSelectionMenu(null)
        requestRender()
        return true
    }

    private fun extendVertex(content: Pt) {
        val sel = vertexSel ?: return
        val shape = sel.item as? ShapeItem ?: return
        val base = vertexBase ?: return
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return
        // A dot is small: a press that wobbles a pixel or two is still a tap, not an edit.
        if (!vertexMoved && content.distanceTo(vertexDownAt) * state.zoom < LONG_PRESS_SLOP) return
        vertexMoved = true
        shape.restoreGeometry(base)
        val local = state.toPageSpace(sel.pageIndex, content)
        if (knobIndex >= 0) shape.dragKnob(knobIndex, local) else shape.moveVertex(vertexSpot ?: return, local)
        for ((_, f) in follows) f.follow(Affine.IDENTITY)
        selObb = selectionBox()
        requestRender()
    }

    /** Pen up on a point drag: an edit is one undo step; a tap changes nothing, and a second one deletes. */
    private fun endVertex(eventTime: Long) {
        val sel = vertexSel
        val shape = sel?.item as? ShapeItem
        val before = vertexBefore
        val spot = vertexSpot
        if (shape != null && before != null) {
            if (vertexMoved) {
                commitPointEdit(shape, before)
                lastVertexTap = null
            } else {
                shape.restoreGeometry(before)
                for ((_, f) in follows) f.restore()
                if (knobIndex < 0 && !vertexWasEdge && spot != null && spot == lastVertexTap &&
                    eventTime - lastVertexTapAt <= DOUBLE_TAP_MS
                ) {
                    lastVertexTap = null
                    shape.toFree()
                    if (shape.deleteVertex(spot)) {
                        for ((_, f) in follows) f.follow(Affine.IDENTITY)
                        commitPointEdit(shape, before)
                    } else {
                        shape.restoreGeometry(before)
                    }
                } else {
                    lastVertexTap = if (vertexWasEdge) null else spot
                    lastVertexTapAt = eventTime
                }
            }
        }
        clearVertex()
        endFollow()
        selObb = selectionBox()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
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
        selObb = selectionBox()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    /** One undo step for a shape [before] a point edit, and the connectors that followed it. */
    private fun commitPointEdit(shape: ShapeItem, before: GeometrySnapshot) {
        val cmds = listOfNotNull(TransformItems(listOf(shape), listOf(before), listOf(shape.snapshotGeometry())), followCommand())
        history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
        state.document.dirty = true
        onContentChanged()
    }

    private fun abandonVertex() {
        val shape = vertexSel?.item as? ShapeItem
        vertexBefore?.let { shape?.restoreGeometry(it) }
        for ((_, f) in follows) f.restore()
        clearVertex()
        selObb = selectionBox()
    }

    private fun clearVertex() {
        vertexSel = null
        vertexSpot = null
        vertexBefore = null
        vertexBase = null
        vertexMoved = false
        knobIndex = -1
    }

    private fun dotReach(): Double = com.xnotes.core.infinite.OverlayTessellator.DOT_TOUCH_PX / state.zoom

    /** A lone shape's point dots over its chrome: faint ones halfway along edges, solid ones on vertices. */
    private fun drawEditDots(r: Renderer) {
        if (mode == PointerMode.MOVE || mode == PointerMode.TRANSFORM || mode == PointerMode.RESIZE) return
        val sel = pointShape() ?: return
        val shape = sel.item as ShapeItem
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return
        val ot = com.xnotes.core.infinite.OverlayTessellator
        val accent = state.selectionAccent(sel.pageIndex)
        for (d in shape.edgeDots(ot.EDGE_DOT_MIN_PX / state.zoom)) {
            r.fillCircle(state.fromPageSpace(sel.pageIndex, d.at), ot.EDGE_DOT_PX / 2.0 / state.zoom, accent.withAlpha(140))
        }
        for (d in shape.vertexDots()) {
            val c = state.fromPageSpace(sel.pageIndex, d.at)
            r.fillCircle(c, ot.VERTEX_DOT_PX / 2.0 / state.zoom, accent)
            r.fillCircle(c, ot.VERTEX_CORE_PX / 2.0 / state.zoom, ot.DOT_CORE)
        }
        for (k in shape.knobDots()) {
            val diamond = ot.knobDiamond(state.fromPageSpace(sel.pageIndex, k), ot.KNOB_PX / 2.0 / state.zoom)
            r.fillPolygon(diamond, ot.KNOB_FILL)
            r.strokePolygon(diamond, Pen(ot.KNOB_EDGE, ot.KNOB_EDGE_PX, cosmetic = true))
        }
    }

    /** Page-local geometry of a selected table's handle bars: a bar above the columns and one left
     *  of the rows, each with grips at the ends of the picked range and a -/+ pair past its end. */
    class TableChrome(
        val topBar: Rect, val leftBar: Rect,
        val barY: Double, val barX: Double,
        val colStart: Pt, val colEnd: Pt,
        val rowStart: Pt, val rowEnd: Pt,
        val colMinus: Rect, val colPlus: Rect,
        val rowMinus: Rect, val rowPlus: Rect,
    )

    private fun colPick(t: TableItem): IntRange = tableColPick?.takeIf { it.first >= 0 && it.last < t.cols } ?: 0 until t.cols
    private fun rowPick(t: TableItem): IntRange = tableRowPick?.takeIf { it.first >= 0 && it.last < t.rows } ?: 0 until t.rows

    private fun tableChrome(t: TableItem): TableChrome {
        val gap = HANDLE_SIZE * 0.9 / state.zoom
        val hit = HANDLE_HIT / state.zoom
        val bs = HANDLE_SIZE * 1.1 / state.zoom
        fun sq(cx: Double, cy: Double) = Rect(cx - bs / 2, cy - bs / 2, bs, bs)
        val barY = t.rect.top - gap
        val barX = t.rect.left - gap
        val cols = colPick(t)
        val rows = rowPick(t)
        return TableChrome(
            topBar = Rect(t.rect.left, barY - hit / 2, t.rect.w, hit),
            leftBar = Rect(barX - hit / 2, t.rect.top, hit, t.rect.h),
            barY = barY, barX = barX,
            colStart = Pt(t.colX(cols.first), barY), colEnd = Pt(t.colX(cols.last + 1), barY),
            rowStart = Pt(barX, t.rowY(rows.first)), rowEnd = Pt(barX, t.rowY(rows.last + 1)),
            colMinus = sq(t.rect.right + gap + bs * 0.5, barY), colPlus = sq(t.rect.right + gap + bs * 1.7, barY),
            rowMinus = sq(barX, t.rect.bottom + gap + bs * 0.5), rowPlus = sq(barX, t.rect.bottom + gap + bs * 1.7),
        )
    }

    /** The selected table and [content] in its page space, or null when the selection isn't one table. */
    private fun tableAt(content: Pt): Pair<TableItem, Pt>? {
        val sel = selection.singleOrNull() ?: return null
        val t = sel.item as? TableItem ?: return null
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return null
        return t to state.toPageSpace(sel.pageIndex, content)
    }

    /** True when [content] lands on the selected table's bars, grips or -/+ buttons. */
    private fun tableChromeHit(content: Pt): Boolean {
        val (t, local) = tableAt(content) ?: return false
        val c = tableChrome(t)
        val tol = HANDLE_HIT / state.zoom
        return c.topBar.outset(tol / 2).contains(local) || c.leftBar.outset(tol / 2).contains(local) ||
            listOf(c.colMinus, c.colPlus, c.rowMinus, c.rowPlus).any { it.contains(local) }
    }

    /**
     * Handle a press on the selected table's chrome: a -/+ button, a bar-end grip (resize the picked
     * columns or rows), the bar itself (pick columns or rows), or an interior line. Returns false when
     * the press missed all of them, so the caller moves or reselects as usual.
     */
    private fun beginTableChrome(content: Pt): Boolean {
        val (t, local) = tableAt(content) ?: return false
        val c = tableChrome(t)
        val tol = HANDLE_HIT / state.zoom
        when {
            c.colPlus.contains(local) -> {
                val at = tableColPick?.let { colPick(t).last + 1 } ?: t.cols
                editSelectedTable { it.insertColumn(at) }
                if (tableColPick != null) tableColPick = at..at
                return true
            }
            c.colMinus.contains(local) -> {
                val drop = tableColPick?.let { colPick(t) } ?: (t.cols - 1..t.cols - 1)
                editSelectedTable { it.removeColumns(drop) }
                tableColPick = null
                return true
            }
            c.rowPlus.contains(local) -> {
                val at = tableRowPick?.let { rowPick(t).last + 1 } ?: t.rows
                editSelectedTable { it.insertRow(at) }
                if (tableRowPick != null) tableRowPick = at..at
                return true
            }
            c.rowMinus.contains(local) -> {
                val drop = tableRowPick?.let { rowPick(t) } ?: (t.rows - 1..t.rows - 1)
                editSelectedTable { it.removeRows(drop) }
                tableRowPick = null
                return true
            }
        }
        val grips = listOf(c.colStart, c.colEnd, c.rowStart, c.rowEnd)
        val g = grips.indices.minByOrNull { grips[it].distanceTo(local) }
        if (g != null && grips[g].distanceTo(local) <= tol) {
            tableLineIsColumn = g < 2
            tableGripAtEnd = g % 2 == 1
            beginTableDrag(t, PointerMode.TABLE_GRIP)
            return true
        }
        if (c.topBar.contains(local) || c.leftBar.contains(local)) {
            tableLineIsColumn = c.topBar.contains(local)
            tablePickPrior = if (tableLineIsColumn) tableColPick else tableRowPick
            tablePickAnchor = if (tableLineIsColumn) columnAt(t, local.x) else rowAt(t, local.y)
            setTablePick(tablePickAnchor..tablePickAnchor)
            beginTableDrag(t, PointerMode.TABLE_PICK)
            return true
        }
        return tryGrabTableLine(content)
    }

    private fun beginTableDrag(t: TableItem, dragMode: PointerMode) {
        tableLineItem = t
        tableLinePageIndex = selection.single().pageIndex
        tableLineBefore = t.snapshotGeometry()
        mode = dragMode
        onSelectionMenu(null)
        requestRender()
    }

    private fun columnAt(t: TableItem, x: Double): Int = (0 until t.cols).firstOrNull { x < t.colX(it + 1) } ?: (t.cols - 1)
    private fun rowAt(t: TableItem, y: Double): Int = (0 until t.rows).firstOrNull { y < t.rowY(it + 1) } ?: (t.rows - 1)

    private fun setTablePick(range: IntRange?) {
        if (tableLineIsColumn) tableColPick = range else tableRowPick = range
    }

    /** Drag a bar-end grip: the picked columns (or rows) share the new span, the rest keep their size. */
    private fun extendTableGrip(content: Pt) {
        val t = tableLineItem ?: return
        if (state.pageRects.getOrNull(tableLinePageIndex) == null) return
        val local = state.toPageSpace(tableLinePageIndex, content)
        if (tableLineIsColumn) {
            val r = colPick(t)
            t.resizeColumnRange(r.first, r.last, local.x, tableGripAtEnd)
        } else {
            val r = rowPick(t)
            t.resizeRowRange(r.first, r.last, local.y, tableGripAtEnd)
        }
        selObb = selectionBox()
        requestRender()
    }

    /** Drag along a bar: pick the columns (or rows) between the press and the pointer. */
    private fun extendTablePick(content: Pt) {
        val t = tableLineItem ?: return
        if (state.pageRects.getOrNull(tableLinePageIndex) == null) return
        val local = state.toPageSpace(tableLinePageIndex, content)
        val at = if (tableLineIsColumn) columnAt(t, local.x) else rowAt(t, local.y)
        setTablePick(minOf(at, tablePickAnchor)..maxOf(at, tablePickAnchor))
        requestRender()
    }

    /** A pick covering the whole side means all of it; tapping the lone picked segment again clears it. */
    private fun endTablePick() {
        val t = tableLineItem
        if (t != null) {
            val picked = if (tableLineIsColumn) tableColPick else tableRowPick
            val all = if (tableLineIsColumn) 0 until t.cols else 0 until t.rows
            if (picked == all || (picked == tablePickPrior && picked?.first == picked?.last)) setTablePick(null)
        }
        tableLineItem = null
        tableLineBefore = null
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    /** Draw the selected table's handle bars (content space): each bar with a tick at every line,
     *  the picked range solid between its two grips, and the -/+ pair past the bar's end. */
    private fun drawTableChrome(r: Renderer) {
        val t = singleSelectedTable() ?: return
        val pi = selection.firstOrNull()?.pageIndex ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        val c = tableChrome(t)
        val sel = state.selectionAccent(pi)
        val onSel = state.onSelectionAccent(pi)
        fun at(p: Pt): Pt = state.fromPageSpace(pi, p).let { Pt(it.x + moveOffset.x, it.y + moveOffset.y) }
        val tick = HANDLE_SIZE * 0.3 / state.zoom
        val grip = HANDLE_SIZE * 0.42 / state.zoom
        r.strokePolyline(listOf(at(Pt(t.rect.left, c.barY)), at(Pt(t.rect.right, c.barY))), Pen(sel.withAlpha(110), 4.0, cosmetic = true))
        r.strokePolyline(listOf(at(Pt(c.barX, t.rect.top)), at(Pt(c.barX, t.rect.bottom))), Pen(sel.withAlpha(110), 4.0, cosmetic = true))
        val tickPen = Pen(sel, 1.3, cosmetic = true)
        for (i in 0..t.cols) r.strokePolyline(listOf(at(Pt(t.colX(i), c.barY - tick)), at(Pt(t.colX(i), c.barY + tick))), tickPen)
        for (j in 0..t.rows) r.strokePolyline(listOf(at(Pt(c.barX - tick, t.rowY(j))), at(Pt(c.barX + tick, t.rowY(j)))), tickPen)
        r.strokePolyline(listOf(at(c.colStart), at(c.colEnd)), Pen(sel, 4.0, cosmetic = true))
        r.strokePolyline(listOf(at(c.rowStart), at(c.rowEnd)), Pen(sel, 4.0, cosmetic = true))
        for (p in listOf(c.colStart, c.colEnd, c.rowStart, c.rowEnd)) r.fillCircle(at(p), grip, sel)
        fun button(localRect: Rect, plus: Boolean) {
            val tl = at(localRect.topLeft)
            val box = Rect(tl.x, tl.y, localRect.w, localRect.h)
            r.fillRect(box, sel)
            val pad = box.w * 0.28
            val pen = Pen(onSel, 1.6, cosmetic = true)
            r.strokePolyline(listOf(Pt(box.left + pad, box.centerY), Pt(box.right - pad, box.centerY)), pen)
            if (plus) r.strokePolyline(listOf(Pt(box.centerX, box.top + pad), Pt(box.centerX, box.bottom - pad)), pen)
        }
        button(c.colMinus, false)
        button(c.colPlus, true)
        button(c.rowMinus, false)
        button(c.rowPlus, true)
    }

    // --- TRANSFORM (generic resize + rotate) ---

    /** Begin a resize ([handle] non-null) or rotate ([handle] null) of the whole selection. Snapshots
     *  every member and the oriented box so each move can restore-then-rebake without drift. */
    private fun beginTransform(handle: HandleId?, content: Pt) {
        txItems = selection.toList()
        txSnaps = txItems.map { it.item.snapshotGeometry() }
        beginFollow(rigid = true, pin = true)
        txStartObb = selObb
        txMargin = selectionChromeMargin()
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
            val res = if (loneTapeBox() != null && handle in CORNER_HANDLES) {
                ResizeMath.obbResizeFree(obb0, handle, content)
            } else {
                ResizeMath.obbResize(obb0, handle, content, txMargin)
            }
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
        // A pill stops at its smallest by itself, so its box is read back from it.
        if (txItems.isNotEmpty() && txItems.all { it.item is AudioItem }) selObb = selectionBox()
        for ((pi, f) in follows) {
            if (state.pageRects.getOrNull(pi) == null) continue
            f.follow(state.affineToPageSpace(pi, world))
        }
        requestRender()
    }

    private fun endTransform() {
        val items = txItems.map { it.item }
        var changed = false
        if (items.isNotEmpty()) {
            val after = items.map { it.snapshotGeometry() }
            if (after == txSnaps) for ((_, f) in follows) f.restore()
            if (after != txSnaps) {
                changed = true
                for ((_, f) in follows) f.settle()
                val tx: Command = TransformItems(items, txSnaps, items.map { it.snapshotGeometry() })
                val joined = followCommand()
                val transfer = reassignSelectionPages()
                val cmds = listOfNotNull(tx, joined, transfer)
                history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
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
        endFollow()
        // A lone shape knows its own frame, so its box is refound rather than kept as dragged.
        if ((selection.singleOrNull()?.item as? ShapeItem)?.frameObb() != null) selObb = selectionBox()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
        if (changed) maybeSwitchBackAfterSelect()
    }

    // --- SHAPE ---

    /** A Shape tool press while something is selected only puts it away; the next press draws. */
    private fun dismissFirst(content: Pt) {
        clearSelection()
        mode = PointerMode.IDLE
        // Free polygon taps place corners, so only the other chips pick up a shape on a tap.
        if (!shapeConfig.placesCorners) dismissPressAt = content
    }

    private fun beginShape(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        // A draft this press finished left its polygon selected; the new shape takes over from it.
        clearSelection()
        val startLocal = state.toPageSpace(pageIndex, content)
        pendingShape = shapeConfig.startShape(startLocal, inkColor, shapeConfig.strokeWidth * SHAPE_PEN_PARITY)
        shapeAnchor = startLocal
        shapeAspect = pendingShape?.function?.naturalAspect()?.takeIf { it > 1e-9 }?.let { 1.0 / it }
        shapePageIndex = pageIndex
        mode = PointerMode.SHAPE
        requestRender()
    }

    // Where the shape drag began (a circle's centre) and a function's true width over height.
    private var shapeAnchor = Pt.ZERO
    private var shapeAspect: Double? = null

    private fun extendShape(content: Pt) {
        val shape = pendingShape ?: return
        val pi = shapePageIndex ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        val raw = state.toPageSpace(pi, content)
        if (shape.shape == ShapeKind.CIRCLE) {
            // Circle: grown out from its centre, so the drag ends on the rim.
            val (s, e) = ResizeMath.circleFromCentre(shapeAnchor, raw)
            shape.start = s
            shape.end = e
        } else {
            // A function graph keeps equal x and y scales while drawn; its handles stretch it later.
            shape.end = shapeAspect?.let { ResizeMath.aspectCorner(shape.start, raw, it) } ?: ResizeMath.drawnEnd(shape, raw, shapeConfig.perfect)
        }
        shapeConfig.followDrag(shape)
        requestRender()
    }

    private fun endShape() {
        val shape = pendingShape
        val pi = shapePageIndex
        pendingShape = null
        shapePageIndex = null
        mode = PointerMode.IDLE
        if (shape != null && pi != null && shape.start.distanceTo(shape.end) > SHAPE_MIN_DRAG) {
            val page = state.document.pages[pi]
            page.items.add(shape)
            state.appendToCache(page, shape)
            history.push(AddItem(page, shape))
            state.document.dirty = true
            onContentChanged()
            // A new shape stays selected, so it can be resized, turned or reshaped straight away.
            setSelection(listOf(Selected(pi, shape)))
            refreshSelectionMenu()
        } else if (shape != null && pi != null) {
            shapeTap(pi, shape.start)
        }
        requestRender()
    }

    /** A tap with the shape tool peels a tape lying on top there, or else picks up the shape under it. */
    private fun shapeTap(pi: Int, local: Pt) {
        if (tapeOnTopAt(pi, local)) peelTapeAt(pi, local) else selectShapeAt(pi, local)
    }

    // --- TAPE ---

    private fun beginTape(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        clearSelection()
        val local = state.toPageSpace(pageIndex, content)
        tapePageIndex = pageIndex
        tapeStart = local
        tapePath.clear()
        tapePath.add(local)
        tapeReach = 0.0
        pendingTape = null
        mode = PointerMode.TAPE
    }

    private fun extendTape(content: Pt) {
        val pi = tapePageIndex ?: return
        val start = tapeStart ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        val at = state.toPageSpace(pi, content)
        tapeReach = maxOf(tapeReach, start.distanceTo(at))
        val cfg = configFor(Tool.TAPE)
        val width = if (cfg.scale) cfg.baseWidth else cfg.baseWidth / state.zoom
        if (cfg.tapeMode == TapeMode.FREEHAND && at.distanceTo(tapePath.last()) * state.zoom >= TAPE_STEP) tapePath.add(at)
        val shape = TapeGeometry.laid(cfg.tapeMode, start, at, tapePath.toList(), width)
        val tape = pendingTape ?: TapeItem(shape, cfg.colorOverride ?: inkColor, cfg.tapePattern).also { pendingTape = it }
        tape.shape = shape
        requestRender()
    }

    private fun endTape() {
        val tape = pendingTape
        val pi = tapePageIndex
        val start = tapeStart
        pendingTape = null
        tapePageIndex = null
        tapeStart = null
        mode = PointerMode.IDLE
        // Only a real drag lays tape; a press that stayed within a tap's slop was a tap.
        val laid = tape != null && tapeReach * state.zoom > TAP_SLOP
        if (tape != null && pi != null && laid) {
            if (tape.mode == TapeMode.FREEHAND) {
                val eps = (TapeItem.FREEHAND_TOLERANCE / state.zoom).coerceAtMost(TapeItem.FREEHAND_TOLERANCE)
                tape.shape = tape.shape.copy(points = TapeGeometry.simplify(tape.shape.points, eps))
            }
            val page = state.document.pages[pi]
            page.items.add(tape)
            state.appendToCache(page, tape)
            history.push(AddItem(page, tape))
            state.document.dirty = true
            onContentChanged()
        } else if (pi != null && start != null) {
            // A tap with the tape tool peels the tape under it back, or covers it again.
            peelTapeAt(pi, start)
        }
        tapePath.clear()
        requestRender()
    }

    private fun dropTapeDraft() {
        pendingTape = null
        tapePageIndex = null
        tapeStart = null
        tapePath.clear()
    }

    /** The topmost tape on page [pi] at page point [local], within a fingertip of its edge, or null. */
    private fun tapeAt(pi: Int, local: Pt): TapeItem? {
        val reach = TAPE_TAP_REACH * state.devicePxPerDp / state.zoom
        return state.document.pages.getOrNull(pi)?.items?.lastOrNull { it is TapeItem && it.under(local, reach) } as? TapeItem
    }

    /** Whether the tape at [local] on page [pi] lies over every shape a shape-tool tap there would pick. */
    private fun tapeOnTopAt(pi: Int, local: Pt): Boolean {
        val items = state.document.pages.getOrNull(pi)?.items ?: return false
        val tape = tapeAt(pi, local) ?: return false
        val reach = OUTLINE_GRAB / state.zoom
        val above = items.subList(items.indexOf(tape) + 1, items.size)
        return above.none { !it.locked && ((it is ShapeItem && it.touches(local, reach)) || (it is ConnectorItem && it.touches(local, reach))) }
    }

    /** Peel back the tape at page point [local] on page [pi], or cover it again; false when there is none. Never an edit. */
    private fun peelTapeAt(pi: Int, local: Pt): Boolean {
        val tape = tapeAt(pi, local) ?: return false
        tape.peeled = !tape.peeled
        if (!state.isLiftedItem(tape)) repairRegion(state.document.pages[pi], tape.paintBounds())
        requestRender()
        return true
    }

    /** Select the topmost shape on page [pi] whose outline or fill is under page point [local], if any. */
    private fun selectShapeAt(pi: Int, local: Pt) {
        val reach = OUTLINE_GRAB / state.zoom
        val hit = state.document.pages.getOrNull(pi)?.items?.lastOrNull {
            !it.locked && ((it is ShapeItem && it.touches(local, reach)) || (it is ConnectorItem && it.touches(local, reach)))
        } ?: return
        setSelection(listOf(Selected(pi, hit)))
        refreshSelectionMenu()
    }

    // --- CONNECTORS ---

    /** The connection point within reach of content point [content], on the page under it, with that page. */
    private fun glueHitAt(content: Pt): Pair<Int, Glue.Hit>? {
        val pi = state.pageIndexAtContent(content) ?: return null
        val page = state.document.pages.getOrNull(pi) ?: return null
        return Glue.nearest(page.items, state.toPageSpace(pi, content), dotReach())?.let { pi to it }
    }

    /** How far a route keeps clear of what it goes round: a fixed distance on screen. */
    private fun routeMargin(): Double = ROUTE_MARGIN_DP * state.devicePxPerDp / state.zoom

    /** A hovering pen lights the connection point under it while a connector is armed. */
    private fun hoverConnect(e: MotionEvent): Boolean {
        val on = connecting && mode == PointerMode.IDLE && e.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        val hit = if (on) glueHitAt(state.viewportToContent(Pt(e.x.toDouble(), e.y.toDouble()))) else null
        val hot = hit?.let { listOf(it.first to it.second.point.at) } ?: emptyList()
        if (hot != connectHot) {
            connectHot = hot
            requestRender()
        }
        return on
    }

    /** A press with a connector chip: the connector sets off on the page under it, glued to the point under the pen if any. */
    private fun beginConnector(content: Pt) {
        val pi = state.pageIndexAtContent(content) ?: return
        val page = state.document.pages[pi]
        clearSelection()
        val width = shapeConfig.strokeWidth * SHAPE_PEN_PARITY
        val draft = ConnectorDraft.startAt(state.toPageSpace(pi, content), page.items, dotReach()) {
            shapeConfig.startConnector(it, inkColor, width)
        } ?: return
        connectorDraft = draft
        connectorPage = pi
        connectHot = listOfNotNull(draft.connector.start.takeIf { it.glued }?.let { pi to it.at })
        mode = PointerMode.CONNECT
        onSelectionMenu(null)
        requestRender()
    }

    /** The far end follows the pen on the starting page, gluing near a point there, the route planned afresh. */
    private fun extendConnector(content: Pt) {
        val draft = connectorDraft ?: return
        val pi = connectorPage
        val page = state.document.pages.getOrNull(pi) ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        draft.drag(state.toPageSpace(pi, content), page.items, dotReach(), routeMargin())
        val c = draft.connector
        connectHot = listOfNotNull(c.start.takeIf { it.glued }?.let { pi to it.at }, draft.hot?.let { pi to it.point.at })
        requestRender()
    }

    /** Pen up: a connector worth keeping goes on its page and stays selected; a tap picks up what is under it. */
    private fun endConnector() {
        val draft = connectorDraft ?: return
        val pi = connectorPage
        dropConnectorDraft()
        mode = PointerMode.IDLE
        val page = state.document.pages.getOrNull(pi)
        if (page != null && draft.worthKeeping(SHAPE_MIN_DRAG)) {
            val c = draft.connector
            page.items.add(c)
            state.appendToCache(page, c)
            history.push(AddItem(page, c))
            state.document.dirty = true
            onContentChanged()
            setSelection(listOf(Selected(pi, c)))
            refreshSelectionMenu()
        } else if (page != null) {
            selectShapeAt(pi, draft.connector.start.at)
        }
        requestRender()
    }

    private fun dropConnectorDraft() {
        connectorDraft = null
        connectorPage = -1
        connectHot = emptyList()
    }

    /** A cross on every connection point of the pages in view, and a ring on each lit one. */
    private fun drawConnectionMarks(r: Renderer) {
        val ot = com.xnotes.core.infinite.OverlayTessellator
        val accent = state.selectionAccent()
        val view = Rect.fromPoints(state.viewportToContent(Pt.ZERO), state.viewportToContent(Pt(state.viewportW.toDouble(), state.viewportH.toDouble())))
        val pen = Pen(accent, ot.MARK_LINE_PX, cosmetic = true)
        val arm = ot.MARK_PX / 2.0 / state.zoom
        val drawing = connectorDraft?.connector ?: jointEdit?.connector
        var count = 0
        marks@ for (pi in state.pageRects.indices) {
            if (!state.pageRects[pi].intersects(view)) continue
            val page = state.document.pages.getOrNull(pi) ?: continue
            for (item in page.items) {
                if (item === drawing) continue
                for (cp in Glue.points(item)) {
                    val c = state.fromPageSpace(pi, cp.at)
                    r.strokePolyline(listOf(Pt(c.x - arm, c.y - arm), Pt(c.x + arm, c.y + arm)), pen)
                    r.strokePolyline(listOf(Pt(c.x - arm, c.y + arm), Pt(c.x + arm, c.y - arm)), pen)
                    if (++count > MAX_MARKS) break@marks
                }
            }
        }
        for ((pi, p) in connectHot) {
            if (state.pageRects.getOrNull(pi) == null) continue
            val c = state.fromPageSpace(pi, p)
            r.fillCircle(c, ot.HOT_PX / 2.0 / state.zoom, accent)
            r.fillCircle(c, ot.HOT_CORE_PX / 2.0 / state.zoom, ot.DOT_CORE)
        }
    }

    // --- FREE POLYGON ---

    private var polygonDraft: com.xnotes.core.shapes.PolygonDraft? = null
    private var polygonPage: Int? = null

    /** A press with the Free polygon chip: the first starts a draft on its page, each one a corner on release. */
    private fun beginPolygonTap(content: Pt) {
        if (polygonDraft == null) {
            polygonPage = state.pageIndexAtContent(content) ?: return
            polygonDraft = com.xnotes.core.shapes.PolygonDraft()
            clearSelection()
        }
        mode = PointerMode.POLYGON
        showPolygonDraft(content)
    }

    /** Show the draft as a line through its corners, with a band out to [content] while pressed. */
    private fun showPolygonDraft(content: Pt?) {
        val draft = polygonDraft ?: return
        val pi = polygonPage ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        val pts = draft.preview(content?.let { state.toPageSpace(pi, it) })
        pendingShape = if (pts.size >= 2) shapeConfig.copy(fill = false).placedShape(pts, false, inkColor, shapeConfig.strokeWidth * SHAPE_PEN_PARITY) else null
        shapePageIndex = pi
        requestRender()
    }

    private fun endPolygonTap(content: Pt, eventTime: Long) {
        val draft = polygonDraft ?: return
        val pi = polygonPage ?: return
        mode = PointerMode.IDLE
        if (state.pageRects.getOrNull(pi) == null) return
        val reach = com.xnotes.core.infinite.OverlayTessellator.DOT_TOUCH_PX / state.zoom
        when (draft.tap(state.toPageSpace(pi, content), eventTime, reach, DOUBLE_TAP_MS)) {
            com.xnotes.core.shapes.PolygonDraft.Outcome.CONTINUE -> showPolygonDraft(null)
            com.xnotes.core.shapes.PolygonDraft.Outcome.CLOSED -> commitPolygon(closed = true)
            com.xnotes.core.shapes.PolygonDraft.Outcome.OPEN -> commitPolygon(closed = false)
        }
    }

    private fun commitPolygon(closed: Boolean) {
        val draft = polygonDraft
        val pi = polygonPage
        polygonDraft = null
        polygonPage = null
        pendingShape = null
        shapePageIndex = null
        if (draft != null && pi != null && draft.points.size >= (if (closed) 3 else 2)) {
            val page = state.document.pages[pi]
            val shape = shapeConfig.placedShape(draft.points, closed, inkColor, shapeConfig.strokeWidth * SHAPE_PEN_PARITY)
            page.items.add(shape)
            state.appendToCache(page, shape)
            history.push(AddItem(page, shape))
            state.document.dirty = true
            onContentChanged()
            setSelection(listOf(Selected(pi, shape)))
            refreshSelectionMenu()
        }
        requestRender()
    }

    /** End a draft something else interrupts: two corners or more stay, as an open line. */
    fun finishPolygonDraft() {
        if (polygonDraft != null) commitPolygon(closed = false)
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
        editingLabel?.let { shape ->
            shape.label = shape.label?.withText(text)
            // The lines stay centred on the shape, so the field moves as they wrap.
            onTextEditStart(editingField())
            return
        }
        editingText?.text = text
    }

    /**
     * Pan the document while a text edit stays open: a one-finger drag over the editor scrolls the
     * page (the overlay re-tracks the box through [editingField]) instead of scrolling the field's
     * own text. [dxFinger]/[dyFinger] are viewport-space finger deltas; the content follows the
     * finger, as in [extendPan]. A plain drag with no fling/overscroll.
     */
    fun panWhileEditing(dxFinger: Double, dyFinger: Double) {
        if (editingText == null && editingLabel == null) return
        state.scrollBy(-dxFinger, -dyFinger)
        onViewChanged()
        requestRender()
    }

    /** Current on-screen geometry of the editor field, or null when not editing. */
    fun editingField(): EditingField? {
        editingLabel?.let { return labelField(it) }
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
            rotation = state.rotationDeg.toDouble(),
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
        if (editingLabel != null) return commitLabelEdit(finalText)
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
                // A box that grew or shrank takes the connectors glued to it along.
                val present = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CanvasItem, Boolean>()).apply { addAll(page.items) }
                val follow = GlueFollow.of(listOf(item), page.items, { it in present }, rigid = false, pin = false)
                val was = follow.items.map { it.paintBounds() }
                follow.follow(Affine.IDENTITY)
                val joined = if (follow.isEmpty) null else TransformItems(follow.items, follow.startSnapshots, follow.items.map { it.snapshotGeometry() })
                val cmds = listOfNotNull(EditText(item, editingOldText, item.text), joined)
                history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
                for ((i, c) in follow.items.withIndex()) repairRegion(page, was[i].union(c.paintBounds()))
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
        editingLabel?.let { shape ->
            val f = editingField() ?: return null
            val l = shape.label ?: return null
            return TextBar(f.screenBounds(), l.style.face, l.style.pointSize, l.style.rgba, editing = true)
        }
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
        if (restyleLabel { it.copy(face = face) }) return
        restyleActive { it.face = face }
    }

    fun setTextPointSize(size: Double) {
        val s = size.coerceIn(TEXT_MIN_PT, TEXT_MAX_PT)
        textPointSize = s
        if (restyleLabel { it.copy(pointSize = s) }) return
        restyleActive { it.pointSize = s }
    }

    /** Set the ink colour (for new strokes/boxes) and recolour the active text box or label, if any. */
    fun pickInk(c: Rgba) {
        inkColor = c
        if (restyleLabel { it.copy(rgba = c) }) return
        restyleActive { it.rgba = c }
    }

    /** Restyle the label being typed, false when none is; the whole edit is one undo step, taken at commit. */
    private fun restyleLabel(change: (TextStyle) -> TextStyle): Boolean {
        val shape = editingLabel ?: return false
        val l = shape.label ?: return false
        shape.label = l.withStyle(change(l.style))
        onTextEditStart(editingField())
        requestRender()
        return true
    }

    // --- connectors following what they join ---

    /**
     * Collect, page by page, the connectors glued to what the gesture starting now moves (the
     * selection), and lift them out of the cache: the overlay draws them live
     * until [endFollow]. [rigid] for a move, turn or resize; [pin] unless a lone connector lets go.
     */
    private fun beginFollow(rigid: Boolean, pin: Boolean) {
        val out = ArrayList<Pair<Int, GlueFollow>>()
        for ((pi, sels) in selection.groupBy { it.pageIndex }) {
            val page = state.document.pages.getOrNull(pi) ?: continue
            val present = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CanvasItem, Boolean>()).apply { addAll(page.items) }
            val f = GlueFollow.of(sels.map { it.item }, page.items, { it in present }, rigid, pin)
            if (!f.isEmpty) out += pi to f
        }
        follows = out
        followed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CanvasItem, Boolean>()).apply { for ((_, f) in out) addAll(f.items) }
        followedFrom = out.map { (_, f) -> f.items.map { it.paintBounds() }.fold(null as Rect?) { a, b -> a?.union(b) ?: b } ?: Rect(0.0, 0.0, 0.0, 0.0) }
        for ((i, pf) in out.withIndex()) state.document.pages.getOrNull(pf.first)?.let { repairRegion(it, followedFrom[i]) }
    }

    /** The connectors the gesture placed, from how it found them to how they are, as one undoable step; null when none. */
    private fun followCommand(): Command? {
        val items = follows.flatMap { it.second.items }
        if (items.isEmpty()) return null
        return TransformItems(items, follows.flatMap { it.second.startSnapshots }, items.map { it.snapshotGeometry() })
    }

    /** Put the connectors a gesture placed back into the cache, where they were and where they are. */
    private fun endFollow() {
        if (follows.isEmpty()) return
        val done = follows
        val was = followedFrom
        follows = emptyList()
        followed = emptySet()
        followedFrom = emptyList()
        for ((i, pf) in done.withIndex()) {
            val page = state.document.pages.getOrNull(pf.first) ?: continue
            repairRegion(page, was[i])
            for (c in pf.second.items) repairRegion(page, c.paintBounds())
        }
    }

    // --- sliding a line's label ---

    /** A press on the label of a lone line or connector slides it along; true when it did. */
    private fun trySlideLabel(content: Pt): Boolean {
        val sel = selection.singleOrNull() ?: return false
        val item = sel.item as? Labelled ?: return false
        if (!item.labelSlides || state.pageRects.getOrNull(sel.pageIndex) == null) return false
        if (!item.labelContains(state.toPageSpace(sel.pageIndex, content))) return false
        slideSel = sel
        slideBefore = item.snapshotGeometry()
        mode = PointerMode.SLIDE
        onSelectionMenu(null)
        return true
    }

    private fun extendSlide(content: Pt) {
        val sel = slideSel ?: return
        val item = sel.item as? Labelled ?: return
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return
        slideBefore?.let { item.restoreGeometry(it) }
        item.slideLabel(state.toPageSpace(sel.pageIndex, content))
        selObb = selectionBox()
        requestRender()
    }

    private fun endSlide() {
        val item = slideSel?.item
        val before = slideBefore
        slideSel = null
        slideBefore = null
        if (item != null && before != null && item.snapshotGeometry() != before) {
            history.push(TransformItems(listOf(item), listOf(before), listOf(item.snapshotGeometry())))
            state.document.dirty = true
            onContentChanged()
        }
        selObb = selectionBox()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    // --- a lone connector's own handles ---

    private fun beginJoint(sel: Selected, edit: ConnectorEdit, content: Pt) {
        jointEdit = edit
        jointPage = sel.pageIndex
        jointDownAt = content
        jointMoved = false
        beginFollow(rigid = false, pin = false)
        mode = PointerMode.JOINT
        onSelectionMenu(null)
        requestRender()
    }

    private fun extendJoint(content: Pt) {
        val edit = jointEdit ?: return
        val page = state.document.pages.getOrNull(jointPage) ?: return
        if (state.pageRects.getOrNull(jointPage) == null) return
        // A handle is small: a press that wobbles a pixel or two is still a tap.
        if (!jointMoved && content.distanceTo(jointDownAt) * state.zoom < LONG_PRESS_SLOP) return
        jointMoved = true
        edit.drag(state.toPageSpace(jointPage, content), page.items, dotReach(), routeMargin())
        for ((_, f) in follows) f.follow(Affine.IDENTITY)
        connectHot = listOfNotNull(edit.hot?.let { jointPage to it.point.at })
        selObb = selectionBox()
        requestRender()
    }

    /** Pen up: a drag is one undo step; a tap changes nothing, and a second on a waypoint removes it. */
    private fun endJoint(eventTime: Long) {
        val edit = jointEdit ?: return
        val c = edit.connector
        val removing = !jointMoved && edit.grab == ConnectorEdit.Grab.WAYPOINT && edit.index == lastWaypointTap &&
            eventTime - lastWaypointTapAt <= DOUBLE_TAP_MS
        if (!jointMoved) {
            edit.restore()
            for ((_, f) in follows) f.restore()
            if (removing) {
                c.removeWaypoint(edit.index)
                for ((_, f) in follows) f.follow(Affine.IDENTITY)
            }
            lastWaypointTap = if (edit.grab == ConnectorEdit.Grab.WAYPOINT && !removing) edit.index else -1
            lastWaypointTapAt = eventTime
        } else {
            lastWaypointTap = -1
        }
        if (jointMoved || removing) {
            val cmds = listOfNotNull(TransformItems(listOf(c), listOf(edit.before), listOf(c.snapshotGeometry())), followCommand())
            history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
            state.document.dirty = true
            onContentChanged()
        }
        jointEdit = null
        jointPage = -1
        connectHot = emptyList()
        endFollow()
        selObb = selectionBox()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    /** True when the lone selection is a connector whose route can be planned afresh. */
    fun canRerouteSelection(): Boolean =
        (loneConnector()?.item as? ConnectorItem)?.let { !it.locked && (it.routing.orthogonal || it.waypoints.isNotEmpty()) } == true

    /** Plan the lone connector's route afresh round what is in its way, as one undo step. */
    fun rerouteSelection() {
        val sel = loneConnector() ?: return
        val c = sel.item as ConnectorItem
        val page = state.document.pages.getOrNull(sel.pageIndex) ?: return
        beginFollow(rigid = false, pin = false)
        val before = c.snapshotGeometry()
        Glue.reroute(c, page.items, routeMargin())
        for ((_, f) in follows) f.follow(Affine.IDENTITY)
        val cmds = listOfNotNull(TransformItems(listOf(c), listOf(before), listOf(c.snapshotGeometry())), followCommand())
        history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
        endFollow()
        state.document.dirty = true
        selObb = selectionBox()
        onContentChanged()
        refreshSelectionMenu()
        requestRender()
    }

    /** True when the selection can be mirrored: it holds no text, which would read backwards. */
    fun canMirrorSelection(): Boolean = selection.isNotEmpty() && selection.all { it.item.mirrorable }

    /** Mirror the selection about its box's centre, left to right or [upsideDown] on screen, as one undo step. */
    fun mirrorSelection(upsideDown: Boolean) {
        if (!canMirrorSelection()) return
        val box = selObb ?: return
        val world = if (upsideDown) Affine.scaleAbout(box.center, 1.0, -1.0) else Affine.scaleAbout(box.center, -1.0, 1.0)
        val members = selection.toList()
        val before = members.map { it.item.snapshotGeometry() }
        beginFollow(rigid = true, pin = true)
        for (sel in members) {
            if (state.pageRects.getOrNull(sel.pageIndex) == null) continue
            sel.item.applyTransform(state.affineToPageSpace(sel.pageIndex, world))
        }
        for ((pi, f) in follows) {
            if (state.pageRects.getOrNull(pi) == null) continue
            f.follow(state.affineToPageSpace(pi, world))
        }
        for ((_, f) in follows) f.settle()
        val tx = TransformItems(members.map { it.item }, before, members.map { it.item.snapshotGeometry() })
        val cmds = listOfNotNull(tx, followCommand(), reassignSelectionPages())
        history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
        endFollow()
        state.document.dirty = true
        // Members stay lifted, so only the box needs to follow: mirrored about its own centre.
        selObb = if ((selection.singleOrNull()?.item as? ShapeItem)?.frameObb() != null) selectionBox() else box.copy(angle = -box.angle)
        onContentChanged()
        refreshSelectionMenu()
        requestRender()
    }

    /** A lone connector's own chrome: its ends (solid when glued), middle segments, waypoints and stretch middles. */
    private fun drawConnectorChrome(r: Renderer) {
        if (mode == PointerMode.MOVE) return
        val sel = loneConnector() ?: return
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return
        val c = sel.item as ConnectorItem
        val page = state.document.pages[sel.pageIndex]
        val ot = com.xnotes.core.infinite.OverlayTessellator
        val accent = state.selectionAccent(sel.pageIndex)
        val z = state.zoom
        fun at(p: Pt) = state.fromPageSpace(sel.pageIndex, p)
        for (d in c.midpointDots()) r.fillCircle(at(d), ot.EDGE_DOT_PX / 2.0 / z, accent.withAlpha(140))
        val half = ot.HANDLE_PX / 2.0 / z
        for (h in c.segmentHandles()) {
            val p = at(h)
            r.fillRect(Rect(p.x - half, p.y - half, 2 * half, 2 * half), accent)
        }
        for (w in c.waypoints) {
            r.fillCircle(at(w), ot.VERTEX_DOT_PX / 2.0 / z, accent)
            r.fillCircle(at(w), ot.VERTEX_CORE_PX / 2.0 / z, ot.DOT_CORE)
        }
        for (end in listOf(c.start, c.end)) {
            r.fillCircle(at(end.at), ot.END_PX / 2.0 / z, accent)
            val glued = end.target?.let { t -> page.items.any { it === t } } == true
            if (!glued) r.fillCircle(at(end.at), ot.END_CORE_PX / 2.0 / z, ot.DOT_CORE)
        }
    }

    // --- shape labels ---

    /** True when the lone selection is a shape that can take a label. */
    fun canLabelSelection(): Boolean = (selection.singleOrNull()?.item as? Labelled)?.let { it.takesLabel && !it.locked } == true

    /** Type the lone selected shape's label. */
    fun labelSelection() {
        val sel = selection.singleOrNull() ?: return
        val shape = sel.item as? Labelled ?: return
        if (shape.takesLabel && !shape.locked) startLabelEdit(shape, sel.pageIndex)
    }

    /**
     * Open the overlay on [shape]'s label, giving it one in the text box defaults if it has none.
     * The shape is lifted out of the cache and drawn live without its words while the field shows them.
     */
    private fun startLabelEdit(shape: Labelled, pi: Int) {
        commitTextEdit()
        flowText?.endSession()
        clearSelection()
        val page = state.document.pages.getOrNull(pi) ?: return
        editingLabel = shape
        labelPageIndex = pi
        labelBefore = shape.label
        val l = shape.label ?: ShapeLabel("", ShapeLabel.styleFor(shape, textPointSize, textFace), textMeasurer).also { shape.label = it }
        textFace = l.style.face
        textPointSize = l.style.pointSize
        shape.labelHidden = true
        repairRegion(page, shape.paintBounds())
        onTextEditStart(editingField())
        requestRender()
    }

    /** End the label edit with its current (or [finalText]) words: blank clears the label; any change is one undo step. */
    private fun commitLabelEdit(finalText: String?) {
        val shape = editingLabel ?: return
        finalText?.let { t -> shape.label = shape.label?.withText(t) }
        val page = state.document.pages.getOrNull(labelPageIndex)
        val before = labelBefore
        val typed = shape.label
        val after = typed?.takeIf { it.text.isNotBlank() }
        val was = shape.paintBounds()
        shape.label = after
        shape.labelHidden = false
        editingLabel = null
        labelPageIndex = -1
        labelBefore = null
        val changed = if (after == null) before != null else !after.sameAs(before)
        if (changed && page != null) {
            history.push(EditLabel(shape, before, after))
            state.document.dirty = true
            onContentChanged()
        }
        page?.let { repairRegion(it, was.union(shape.paintBounds())) }
        onTextEditEnd()
        requestRender()
    }

    /** The overlay over [shape]'s label: its top-left corner on screen, turned with the shape and the view. */
    private fun labelField(shape: Labelled): EditingField? {
        if (state.pageRects.getOrNull(labelPageIndex) == null) return null
        val l = shape.label ?: return null
        val r = shape.labelFrame() ?: return null
        val topLeft = state.contentToViewport(state.fromPageSpace(labelPageIndex, shape.labelToPage(r.topLeft)))
        return EditingField(
            x = topLeft.x,
            y = topLeft.y,
            width = r.w,
            height = r.h,
            fontPx = l.style.pointSize * com.xnotes.platform.AndroidText.POINTS_TO_PX,
            zoom = state.zoom,
            face = l.style.face,
            rgba = l.style.rgba,
            text = l.text,
            rotation = state.rotationDeg + Math.toDegrees(shape.labelTurn),
            centred = true,
        )
    }

    private fun repairRegion(page: Page, rect: Rect) {
        if (!state.repairRegion(page, rect.outset(REPAIR_PAD))) state.invalidatePage(page)
    }

    private fun tapDown(e: MotionEvent) {
        tapCandidate = true
        tapDownViewport = Pt(e.x.toDouble(), e.y.toDouble())
        tapDownTime = e.eventTime
        tapDownSelected = selection.mapTo(HashSet()) { it.item }
    }

    /** A tap that follows another close by and soon after is a double tap. */
    private fun tapUp(e: MotionEvent) {
        val at = Pt(e.x.toDouble(), e.y.toDouble())
        val tap = tapCandidate && e.eventTime - tapDownTime < LONG_PRESS_MS
        tapCandidate = false
        if (!tap) {
            lastTapViewport = null
            return
        }
        val prev = lastTapViewport
        if (prev != null && tapDownTime - lastTapTime <= DOUBLE_TAP_MS && prev.distanceTo(at) <= DOUBLE_TAP_SLOP_DP * state.devicePxPerDp) {
            lastTapViewport = null
            onDoubleTap(at, e.getToolType(0), lastTapSelected)
            return
        }
        lastTapViewport = at
        lastTapTime = e.eventTime
        lastTapSelected = tapDownSelected
    }

    /**
     * A double tap on a closed shape, with the Shape or Select tool or a finger that pans, types its
     * label. Only on a shape that was not selected when the first tap went down: on a selected shape
     * a double tap is a vertex's, so it never labels one, whatever its kind. [selectedBefore] is that
     * selection; the first tap may well have selected the shape itself.
     */
    /** Whether page point [local] lands on [item] for a label: a shape's outline, fill, label or inside; a line's line. */
    private fun labelTargetHit(item: CanvasItem, local: Pt, reach: Double): Boolean = when (item) {
        is ShapeItem -> item.takesLabel && (item.touches(local, reach) || (item.shape.isClosed && item.encloses(local)))
        is ConnectorItem -> item.touches(local, reach)
        else -> false
    }

    private fun onDoubleTap(viewport: Pt, toolType: Int, selectedBefore: Set<CanvasItem>) {
        val fingerPans = toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff
        if (tool != Tool.SHAPE && tool != Tool.SELECT && !fingerPans) return
        // The free polygon's own double tap closes its outline.
        if (tool == Tool.SHAPE && shapeConfig.placesCorners) return
        if (editingText != null || editingLabel != null) return
        val content = state.viewportToContent(viewport)
        val pi = state.pageIndexAtContent(content) ?: return
        val local = state.toPageSpace(pi, content)
        val reach = OUTLINE_GRAB / state.zoom
        val shape = state.document.pages[pi].items.lastOrNull { !it.locked && labelTargetHit(it, local, reach) } as? Labelled ?: return
        if (shape in selectedBefore) return
        startLabelEdit(shape, pi)
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

    private fun armLongPress(viewport: Pt, content: Pt, isFinger: Boolean, free: Boolean) {
        cancelLongPress()
        // Long press (grab an item, select PDF text, or the paste menu on empty space) is a finger
        // gesture. A stylus or mouse only has it when free, under the PAN tool, where it mirrors the
        // finger; otherwise it draws, so resting it never grabs or pops a menu.
        if (!isFinger && !free) return
        val grabEligible = !readOnly && (tool.isStroke || tool == Tool.PAN || tool == Tool.SELECT ||
            tool == Tool.LASSO || tool == Tool.SHAPE || tool == Tool.TEXT || tool == Tool.TEXT_BOX || tool == Tool.TAPE)
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
        longPressLocked = hit?.takeIf { it.locked && !readOnly }
        longPressCandidate =
            if (grabEligible && hit != null && !hit.locked) Selected(pageIndex!!, hit) else null
        // An item on top wins; a free pointer on the bare PDF selects its text.
        val text = pdfText
        if (free && text != null && hit == null && pageIndex != null && state.document.pages[pageIndex].pdfPage != null) {
            longPressTextPage = pageIndex
            text.prefetch(pageIndex)
        }
        // Arm to grab an item, to unlock one, to select text, or (on empty space) to open the paste
        // menu when there is content to paste.
        val showEmptyMenu = !readOnly && hit == null && (hasClipboardItems() || clipboardHasImage())
        if (longPressCandidate == null && longPressLocked == null && longPressTextPage < 0 && !showEmptyMenu) return
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
        longPressTextPage = -1
    }

    private fun triggerLongPress() {
        longPressRunnable = null
        val candidate = longPressCandidate
        longPressCandidate = null
        val locked = longPressLocked
        val textPage = longPressTextPage
        longPressTextPage = -1
        dismissPressAt = null
        // Abort the in-progress gesture (keep eraser removals); commit any text edit.
        commitTextEdit()
        liveStroke = null
        strokePageIndex = null
        pendingShape = null
        shapePageIndex = null
        dropTapeDraft()
        dropConnectorDraft()
        bandRect = null
        lassoPoints.clear()
        if (candidate != null) {
            // Grab the item: switch to select, select it, and start a move.
            longPressPrevTool = tool
            tool = Tool.SELECT
            onToolChanged(tool)
            setSelection(listOf(candidate))
            beginMove(state.viewportToContent(longPressStart))
        } else if (textPage >= 0 && pdfText != null) {
            // Select the word under the press, which may drag on to extend it; no text there means
            // empty space, so the paste menu as before.
            mode = PointerMode.PDF_TEXT
            val start = longPressStart
            val content = longPressContent
            pdfText?.longPress(textPage, state.toPageSpace(textPage, content)) {
                if (!readOnly && (hasClipboardItems() || clipboardHasImage())) onContextMenu(start, content, null)
            }
        } else {
            // Empty space, or a locked item: open the context menu at the press point.
            mode = PointerMode.IDLE
            onContextMenu(longPressStart, longPressContent, locked)
        }
        longPressLocked = null
        requestRender()
    }

    // --- selection management ---

    private fun setSelection(picked: List<Selected>) {
        // A member picked brings its whole group along, from its own page: a group never spans pages.
        val onPages = picked.map { it.pageIndex }.distinct().asSequence()
            .flatMap { i -> state.document.pages.getOrNull(i)?.items.orEmpty().asSequence().map { Selected(i, it) } }
        val items = com.xnotes.core.model.Groups.withMembers(picked, onPages.asIterable()) { it.item }
        // Items whose lifted state flips: those leaving the old selection (repainted back
        // into the cache) and those entering it (lifted out of it). Update the selection
        // first so the in-place repair below sees the new lifted set.
        val touched = selection + items
        // The picked table columns and rows belong to that table's selection; a new one starts over.
        if (items.singleOrNull()?.item !== selection.singleOrNull()?.item) {
            tableColPick = null
            tableRowPick = null
        }
        selection.clear()
        selection.addAll(items)
        // A fresh selection starts upright around the items, unless it is a lone shape in its frame.
        selObb = selectionBox()
        repairRegions(dirtyRegions(touched))
        onSelectionChanged(selection.isNotEmpty())
        requestRender()
    }

    /** Select just [item] on [pageIndex] (used right after inserting a table). */
    fun selectSingle(pageIndex: Int, item: CanvasItem) = setSelection(listOf(Selected(pageIndex, item)))

    /** The single selected table, or null. */
    fun singleSelectedTable(): TableItem? = selection.singleOrNull()?.item as? TableItem

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
        selObb = selectionBox()
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

    /** Apply [mutate] to the single selected table as one undoable edit (add/remove line, etc.). */
    fun editSelectedTable(mutate: (TableItem) -> Unit) {
        val t = singleSelectedTable() ?: return
        val before = t.snapshotGeometry()
        mutate(t)
        val after = t.snapshotGeometry()
        if (after == before) return
        history.push(TransformItems(listOf(t), listOf(before), listOf(after)))
        state.document.dirty = true
        selObb = selectionBox()
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
        selObb = selectionBox()
        onContentChanged()
        refreshSelectionMenu()
        requestRender()
        return true
    }

    /** Select [items] just placed on page [pageIndex], as a paste leaves its clones, so they can be dragged at once. */
    fun selectPlaced(pageIndex: Int, items: List<CanvasItem>) {
        setSelection(items.map { Selected(pageIndex, it) })
        refreshSelectionMenu()
    }

    fun clearSelection() {
        // Restore the tool a long-press grab temporarily switched away from.
        longPressPrevTool?.let {
            tool = it
            longPressPrevTool = null
            onToolChanged(tool)
        }
        onSelectionMenu(null)
        tableColPick = null
        tableRowPick = null
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

    /** A lone shape's or rectangle of tape's own turned frame, or else the upright box around everything selected. */
    private fun selectionBox(): Obb? {
        val one = selection.singleOrNull()
        val frame = (one?.item as? ShapeItem)?.frameObb() ?: loneTapeBox()?.shape?.frame()
        if (one != null && frame != null && state.pageRects.getOrNull(one.pageIndex) != null) {
            val turn = frame.angle + Math.toRadians(state.rotationDeg.toDouble())
            return Obb(state.fromPageSpace(one.pageIndex, frame.center), frame.halfW, frame.halfH, turn)
        }
        // A single rotated image gets a box hugging its turned rect, not the larger upright one around it.
        val img = one?.item as? ImageItem
        if (img != null && img.angle != 0.0 && state.pageRects.getOrNull(one.pageIndex) != null) {
            val cs = img.corners().map { state.fromPageSpace(one.pageIndex, it) }
            val center = Pt((cs[0].x + cs[2].x) / 2.0, (cs[0].y + cs[2].y) / 2.0)
            val halfW = cs[0].distanceTo(cs[1]) / 2.0
            val halfH = cs[0].distanceTo(cs[3]) / 2.0
            val angle = atan2(cs[1].y - cs[0].y, cs[1].x - cs[0].x)
            return Obb(center, halfW, halfH, angle)
        }
        return selectionBoundsContent()?.let { Obb.fromAabb(it) }
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

    /**
     * Restack the selection on each page it covers, as one undo step. The selection is let go
     * afterwards: selected items are painted above the page, so their new depth only shows once
     * they are back in its cache.
     */
    fun arrange(how: Arrange) {
        if (selection.isEmpty()) return
        val cmds = ArrayList<Command>()
        for ((pageIndex, sels) in selection.groupBy { it.pageIndex }) {
            val page = state.document.pages.getOrNull(pageIndex) ?: continue
            val picked = sels.mapTo(HashSet()) { it.item }
            val old = page.items.toList()
            val new = arrangeOrder(old, { it in picked }, how) { it.paintBounds() }
            if (sameOrder(old, new)) continue
            cmds.add(ReorderItems(page, old, new))
            page.items.clear()
            page.items.addAll(new)
        }
        if (cmds.isEmpty()) return
        history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
        state.document.dirty = true
        clearSelection()
        onContentChanged()
        maybeSwitchBackAfterSelect()
    }

    // --- groups ---

    fun canGroupSelection(): Boolean = com.xnotes.core.model.Groups.canGroup(selection.map { it.item })

    fun canUngroupSelection(): Boolean = com.xnotes.core.model.Groups.hasGroup(selection.map { it.item })

    /** Put the selection in one group, page by page since a group never spans pages, any groups in it merged; one undo step. */
    fun groupSelection() {
        if (!canGroupSelection()) return
        var next = com.xnotes.core.model.Groups.nextId(state.document.pages.flatMap { it.items })
        val cmds = selection.groupBy { it.pageIndex }.values.filter { it.size >= 2 }
            .map { sels -> Regroup(sels.map { it.item }, next++).also { it.redo() } }
        if (cmds.isEmpty()) return
        history.push(cmds.singleOrNull() ?: CompositeCommand(cmds))
        state.document.dirty = true
        onContentChanged()
        refreshSelectionMenu()
    }

    /** Free every item of the groups in the selection; it stays selected, now as loose items. */
    fun ungroupSelection() {
        val grouped = selection.map { it.item }.filter { it.group != null }
        if (grouped.isEmpty()) return
        history.push(Regroup(grouped, null).also { it.redo() })
        state.document.dirty = true
        onContentChanged()
        refreshSelectionMenu()
    }

    /** The styles the selection's drawn items carry, for the restyle popup to open on. */
    fun selectionStyles(): List<DrawStyle> = selection.mapNotNull { DrawStyle.of(it.item) }

    /** The fills of the selected shapes that have one. */
    fun selectionFills(): List<Rgba> = selection.mapNotNull { (it.item as? ShapeItem)?.fillRgba }

    /** The first selected tape's pattern, or null when no tape is selected. */
    fun selectionTapePattern(): TapePattern? = selection.firstNotNullOfOrNull { (it.item as? TapeItem)?.pattern }

    /** Print [pattern] on every selected tape, as one undo step. */
    fun setSelectionTapePattern(pattern: TapePattern) {
        val entries = com.xnotes.core.history.RepatternTape.of(selection.mapNotNull { it.item as? TapeItem }, pattern)
        if (entries.isEmpty()) return
        val command = com.xnotes.core.history.RepatternTape(entries)
        command.redo()
        history.push(command)
        state.document.dirty = true
        onContentChanged()
        requestRender()
    }

    /** Recolour the selected shapes' fills in [color], each keeping its opacity; null matches each outline. */
    fun refillSelection(color: Rgba?, preview: Boolean = false) {
        val shapes = selection.mapNotNull { (it.item as? ShapeItem)?.takeIf { s -> s.fillRgba != null } }
        if (shapes.isEmpty()) return
        val baseline = refillBaseline ?: shapes.associateWith { it.fillRgba }
        refillBaseline = if (preview) baseline else null
        com.xnotes.core.history.RefillShapes(com.xnotes.core.history.RefillShapes.of(shapes, color)).redo()
        if (!preview) {
            val entries = baseline.mapNotNull { (s, before) ->
                s.fillRgba.takeIf { it != before }?.let { com.xnotes.core.history.RefillShapes.Entry(s, before, it) }
            }
            if (entries.isNotEmpty()) history.push(com.xnotes.core.history.RefillShapes(entries))
        }
        state.document.dirty = true
        onContentChanged()
        requestRender()
    }

    /** The fills as a refill preview found them, so its settling call records them as one step. */
    private var refillBaseline: Map<ShapeItem, Rgba?>? = null

    /** The lone selected shape whose points or sides a minus/plus can change, or null. */
    fun countedShape(): ShapeItem? = (selection.singleOrNull()?.item as? ShapeItem)?.takeIf { it.shape.nRange != null }

    /** Set the lone selected star's points or polygon's sides, as one undo step. */
    fun setSelectionCount(n: Int) {
        val shape = countedShape() ?: return
        val range = shape.shape.nRange ?: return
        val next = n.coerceIn(range.first, range.last)
        if (next == shape.n) return
        beginFollow(rigid = false, pin = false)
        val before = shape.snapshotGeometry()
        shape.n = next
        for ((_, f) in follows) f.follow(Affine.IDENTITY)
        commitPointEdit(shape, before)
        endFollow()
        refreshSelectionMenu()
        requestRender()
    }

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

    /** Put copies of the selection on the shared item clipboard; a [cut]'s are spent by the paste that lands them. */
    private fun copyToClipboard(cut: Boolean) {
        if (selection.isEmpty()) return
        ItemClipboard.clip = ItemClipboard.Clip(selection.map { it.item }.deepCopyAll(textMeasurer), state.document.dpi, state.document, cut)
    }

    fun copySelection() {
        if (selection.isEmpty()) return
        copyToClipboard(cut = false)
        maybeSwitchBackAfterSelect()
    }

    fun cutSelection() {
        if (selection.isEmpty()) return
        copyToClipboard(cut = true)
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
        val pageIndex = selection.first().pageIndex
        // Offset slightly from the originals, leaving the clipboard as it was.
        val clones = selection.map { it.item }.deepCopyAll(textMeasurer)
        for (c in clones) c.translate(DUPLICATE_NUDGE, DUPLICATE_NUDGE)
        landClones(pageIndex, clones, moves = false)
        maybeSwitchBackAfterSelect()
    }

    /** Paste the clipboard items so their top-left lands at the given content point. */
    fun pasteItemsAt(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: state.currentPageIndex()
        pasteOnPage(pageIndex, content, centred = false)
    }

    /** Paste the clipboard items centred in the view, on the page there. */
    fun pasteAtViewCentre() {
        val centre = state.viewportToContent(Pt(state.viewportW / 2.0, state.viewportH / 2.0))
        val pageIndex = state.pageIndexAtContent(centre) ?: state.currentPageIndex()
        pasteOnPage(pageIndex, centre, centred = true)
    }

    /** Land fresh copies of the clipboard's items on page [pageIndex], their box's top-left (or centre, when [centred]) at [target]. */
    private fun pasteOnPage(pageIndex: Int, target: Pt, centred: Boolean) {
        val clip = ItemClipboard.clip ?: return
        if (state.pageRects.getOrNull(pageIndex) == null) return
        val clones = ItemClipboard.copiesFor(clip, state.document.dpi, clip.isFrom(state.document), textMeasurer)
        // Collective bounds (page-local) of the clones.
        var box: Rect? = null
        for (c in clones) box = box?.union(c.bounds()) ?: c.bounds()
        val b = box ?: return
        val targetLocal = state.toPageSpace(pageIndex, target)
        val dx = targetLocal.x - (if (centred) b.center.x else b.left)
        val dy = targetLocal.y - (if (centred) b.center.y else b.top)
        for (c in clones) c.translate(dx, dy)
        // A cut's clipboard is spent by the paste that lands it: the items were taken from where
        // they were, so putting them down finishes the move rather than starting a series of copies.
        if (clip.cut && ItemClipboard.clip === clip) ItemClipboard.clip = null
        landClones(pageIndex, clones, moves = clip.cut)
    }

    /** Put [clones] on page [pageIndex] as one undo step, in groups of their own, and leave them selected. */
    private fun landClones(pageIndex: Int, clones: List<CanvasItem>, moves: Boolean) {
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        val existing = state.document.pages.flatMap { it.items }
        com.xnotes.core.model.Groups.refresh(clones, existing)
        com.xnotes.core.audio.RecordingIds.adopt(clones, existing, moves = moves)
        page.items.addAll(clones)
        history.push(AddItems(page, clones))
        state.document.dirty = true
        // The clones are immediately selected (lifted) below; setSelection repairs their
        // region in place, so no separate page rebuild is needed here.
        setSelection(clones.map { Selected(pageIndex, it) })
        refreshSelectionMenu()
        onContentChanged()
    }

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
        // A table's column bar sits above it: lift the bar's anchor clear of it too.
        if (singleSelectedTable() != null) {
            val lift = HANDLE_SIZE * 0.9 + HANDLE_HIT / 2
            rect = Rect(rect.left, rect.top - lift, rect.w, rect.h + lift)
        }
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
        onUserMovedView?.invoke()
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
     * Pan inside the current row until its horizontal edge. Beyond that, keep the
     * existing and incoming rows attached to the finger without elastic resistance.
     * Only the document ends retain a damped pull.
     */
    private fun extendPanPaginated(dx0: Double, dy: Double) {
        var dx = dx0
        val pull = state.flipOffsetX
        if (pull != 0.0 && dx * pull < 0.0) {
            // Retracting unwinds at the rate the pull was built, so dragging back to the start lands where it began.
            val (relaxed, rest) = PaginatedFlipPolicy.retract(pull, dx, pullGain(if (pull > 0.0) 1 else -1))
            state.flipOffsetX = relaxed
            dx = rest
        }
        val beforeX = state.scrollX
        state.scrollBy(dx, dy)
        val leftover = dx - (state.scrollX - beforeX)
        if (leftover != 0.0) {
            val direction = if (leftover > 0.0) 1 else -1
            val hasNeighbour = state.currentRow + direction in state.rowRanges().indices
            val previous = state.flipOffsetX
            val cap = if (hasNeighbour) {
                state.linkedPageTravel(state.currentRow + direction)
            } else state.viewportW.toDouble()
            // A mild boundary resistance indicates that there is no existing next row.
            // The separate, longer add-page threshold protects against accidental creation.
            val isAddPageEdge = direction > 0 && state.currentRow == state.rowRanges().lastIndex
            state.flipOffsetX = (previous + leftover * pullGain(direction)).coerceIn(-cap, cap)
            val threshold = if (isAddPageEdge) {
                PaginatedFlipPolicy.addPageThreshold(state.viewportW, state.devicePxPerDp)
            } else pageFlipThreshold()
            if (abs(previous) < threshold && abs(state.flipOffsetX) >= threshold) onHaptic()
        }
    }

    /** Share of finger travel past the row edge that becomes pull: all of it toward a neighbour, damped at the note's ends. */
    private fun pullGain(direction: Int): Double = when {
        state.currentRow + direction in state.rowRanges().indices -> 1.0
        direction > 0 -> ADD_PAGE_PULL_GAIN
        else -> FLIP_RESIST
    }

    /** A page changes after a full edge pull or a shorter deliberate outward flick. */
    private fun pageFlipThreshold(): Double =
        PaginatedFlipPolicy.threshold(state.viewportW, state.devicePxPerDp, state.zoom)

    /** Release a paginated drag without changing the vertical-scrolling gesture path. */
    private fun endPanPaginated() {
        val rows = state.rowRanges().size
        val pull = state.flipOffsetX
        // A finger that stopped before lifting has no flick left, however fast it moved earlier.
        val stopped = System.nanoTime() / 1_000_000L - lastMoveMs > PaginatedFlipPolicy.RELEASE_MOTION_MAX_AGE_MS
        val v = if (stopped) Pt.ZERO else panVel
        val deliberate = PaginatedFlipPolicy.shouldCommitOnRelease(
            pull, state.viewportW, state.devicePxPerDp, v.x, v.y, state.zoom,
        )
        when {
            // Appending a new page remains distance-only to avoid unintended document edits.
            pull >= PaginatedFlipPolicy.addPageThreshold(state.viewportW, state.devicePxPerDp) &&
                state.currentRow >= rows - 1 -> {
                stopPageFlipAnimation()
                clearFlipPull()
                onAddPageAtEnd()
                val grown = state.rowRanges().size
                if (grown > rows) flipTo(grown - 1) else { onViewChanged(); requestRender() }
            }
            deliberate && pull > 0.0 && state.currentRow < rows - 1 ->
                flipTo(state.currentRow + 1)
            deliberate && pull < 0.0 && state.currentRow > 0 ->
                flipTo(state.currentRow - 1)
            pull != 0.0 -> {
                animateFlipPullBack()
                onViewChanged()
                requestRender()
            }
            else -> startPanFling()
        }
    }

    /** Continue the existing drag until the linked neighbouring row fully occupies the viewport. */
    private fun flipTo(rowIndex: Int) {
        stopFling()
        val direction = if (rowIndex > state.currentRow) 1.0 else -1.0
        val target = direction * state.linkedPageTravel(rowIndex)
        beginPageFlipAnimation(state.flipOffsetX, target, rowIndex)
    }

    /** A cancelled drag simply slides the same two pages back into their starting positions. */
    private fun animateFlipPullBack() {
        if (state.flipOffsetX == 0.0) return
        stopFling()
        beginPageFlipAnimation(state.flipOffsetX, 0.0, null)
    }

    private fun stopPageFlipAnimation() {
        pageFlipAnimating = false
        state.pageFlipAnimating = false
        pageFlipDestinationRow = null
        choreographer.removeFrameCallback(pageFlipFrame)
    }

    /** Land the turn in flight now: on its new page, or at rest for a cancelled pull. */
    private fun finishPageFlip() {
        val destination = pageFlipDestinationRow
        stopPageFlipAnimation()
        destination?.let { state.landFlip(it) }
        state.flipOffsetX = 0.0
        onViewChanged()
        requestRender()
    }

    private fun beginPageFlipAnimation(from: Double, to: Double, destination: Int?) {
        stopPageFlipAnimation()
        pageFlipStartOffset = from
        pageFlipTargetOffset = to
        pageFlipDestinationRow = destination
        state.flipOffsetX = from
        pageFlipStartMs = System.nanoTime() / 1_000_000L
        // The release continues the drag instead of feeling like a second, fixed-duration
        // transition. A near-complete pull settles almost immediately; a shorter flick
        // uses a longer but still brisk ease-out.
        val remainingFraction = abs(to - from) / maxOf(abs(to), state.viewportW * 0.5)
        pageFlipDurationMs = (75.0 + remainingFraction * 125.0).coerceIn(80.0, 205.0)
        pageFlipAnimating = true
        state.pageFlipAnimating = true
        choreographer.postFrameCallback(pageFlipFrame)
    }

    private fun stepPageFlip(frameTimeNanos: Long) {
        if (!pageFlipAnimating) return
        val now = frameTimeNanos / 1_000_000L
        val t = ((now - pageFlipStartMs) / pageFlipDurationMs).coerceIn(0.0, 1.0)
        val remaining = 1.0 - t
        val eased = 1.0 - remaining * remaining * remaining
        state.flipOffsetX = pageFlipStartOffset +
            (pageFlipTargetOffset - pageFlipStartOffset) * eased
        if (t >= 1.0 || abs(state.flipOffsetX - pageFlipTargetOffset) < 0.35) {
            finishPageFlip()
        } else {
            choreographer.postFrameCallback(pageFlipFrame)
        }
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
        stopPageFlipAnimation()
        state.flipOffsetX = 0.0
        clearOverscroll()
        cancelDwell()
        clearFading()
        crossedSegments.clear() // they belong to the outgoing document, whose history is gone
        dwellEligible = false
        mode = PointerMode.IDLE
        dismissPressAt = null
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
        dropTapeDraft()
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
        onUserMovedView?.invoke()
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
        val threshold = pageFlipThreshold()
        val armedBefore = abs(state.flipOffsetX) >= threshold
        state.flipOffsetX = (overX * FLIP_RESIST).coerceIn(-cap, cap)
        if (!armedBefore && abs(state.flipOffsetX) >= threshold) onHaptic()
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
        if (mode == PointerMode.ERASE) endErase() // what a cancelled erase took stays one undo step
        if (mode == PointerMode.VERTEX) abandonVertex()
        if (mode == PointerMode.JOINT) jointEdit?.restore()
        jointEdit = null
        if (mode == PointerMode.SLIDE) slideBefore?.let { slideSel?.item?.restoreGeometry(it) }
        slideSel = null
        slideBefore = null
        if (mode == PointerMode.POLYGON) showPolygonDraft(null)
        if (mode == PointerMode.MOVE) moveOffset = Pt.ZERO
        if (mode == PointerMode.MOVE || mode == PointerMode.TRANSFORM || mode == PointerMode.VERTEX || mode == PointerMode.JOINT) {
            for ((_, f) in follows) f.restore()
        }
        looseBefore = null
        endFollow()
        pushStrokeEdit(null) // a cancelled crossing still left segments on the pages behind it
        liveStroke = null
        strokePageIndex = null
        snapEngaged = false
        snapRunStartEdge = null
        snapCurrentEdge = null
        snapPenViewport = null
        pendingShape = null
        shapePageIndex = null
        dropTapeDraft()
        dropConnectorDraft()
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

            markupOverlay?.draw(r)

            // Lifted (selected) items, drawn live at the move offset; one that bends to stay joined is drawn below.
            for (sel in selection) {
                if (sel.item in followed) continue
                val pr = state.pageRects.getOrNull(sel.pageIndex) ?: continue
                if (state.isUnderText(state.document.pages[sel.pageIndex], sel.item)) continue // painted under the ink
                r.withSave {
                    r.translate(pr.left + moveOffset.x, pr.top + moveOffset.y)
                    state.applyPageTransform(r, state.document.pages[sel.pageIndex])
                    state.paintItem(sel.item, r)
                }
            }

            // Connectors kept joined to what moves, placed in the model frame by frame.
            for ((pi, f) in follows) {
                val pr = state.pageRects.getOrNull(pi) ?: continue
                r.withSave {
                    r.translate(pr.left, pr.top)
                    state.applyPageTransform(r, state.document.pages[pi])
                    for (item in f.items) state.paintItem(item, r)
                }
            }

            // A shape whose label is being typed, out of the cache and drawn here without its words.
            editingLabel?.let { shape ->
                val pr = state.pageRects.getOrNull(labelPageIndex)
                if (pr != null) {
                    r.withSave {
                        r.translate(pr.left, pr.top)
                        state.applyPageTransform(r, state.document.pages[labelPageIndex])
                        shape.paint(r)
                    }
                }
            }

            // Live in-progress stroke / shape preview, clipped to its page. The stroke goes through
            // the wet cache, which blits whatever has stopped moving instead of refilling it.
            if (frontInk?.live != true) {
                liveStroke?.let { stroke -> paintClippedToPage(r, strokePageIndex) { state.paintLiveStroke(r, stroke) } }
            }
            pendingShape?.let { shape -> paintClippedToPage(r, shapePageIndex) { shape.paint(r) } }
            pendingTape?.let { tape -> paintClippedToPage(r, tapePageIndex) { tape.paint(r) } }
            connectorDraft?.let { d -> paintClippedToPage(r, connectorPage) { d.connector.paint(r) } }
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
            // Search matches, then the flow caret + selection highlight, under the selection chrome; then the PDF's.
            searchTints?.draw(r)
            flowText?.drawOverlay(r)
            pdfText?.drawOverlay(r)

            when {
                mode == PointerMode.BAND -> bandRect?.let { r.strokeRect(it, accent) }
                mode == PointerMode.TEXT_DRAG -> textDragRect?.let { r.strokeRect(it, accent) }
                mode == PointerMode.LASSO_DRAW && lassoPoints.size >= 2 ->
                    r.strokePolyline(lassoPoints, chromePen(1.3))
                selection.isNotEmpty() ->
                    selectionChromeBox()?.let { obb ->
                        r.strokePolygon(obb.corners().map { Pt(it.x + moveOffset.x, it.y + moveOffset.y) }, accent)
                    }
            }

            // Resize + rotate handles for the settled selection (single, multi, or mixed).
            if (selection.isNotEmpty() && mode != PointerMode.BAND && mode != PointerMode.LASSO_DRAW) {
                val side = HANDLE_SIZE / state.zoom
                val sel = state.selectionAccent(selectionPageIndex())
                // Rotate grip: a short stem out from the box's top edge up to a round handle.
                selectionRotatePoint()?.let { rp ->
                    selectionChromeBox()?.let { obb ->
                        val base = ResizeMath.obbTopMid(obb)
                        val stemTop = Pt(base.x + moveOffset.x, base.y + moveOffset.y)
                        val grip = Pt(rp.x + moveOffset.x, rp.y + moveOffset.y)
                        r.strokePolyline(listOf(grip, stemTop), Pen(sel, 1.3, cosmetic = true))
                        r.fillCircle(grip, side * 0.6, sel)
                    }
                }
                for (h in selectionResizeHandles()) {
                    val c = Pt(h.content.x + moveOffset.x, h.content.y + moveOffset.y)
                    r.fillRect(Rect(c.x - side / 2, c.y - side / 2, side, side), sel)
                }
                drawTableChrome(r)
                selectionMoveGrip()?.let { g ->
                    val c = Pt(g.x + moveOffset.x, g.y + moveOffset.y)
                    r.fillCircle(c, side * 0.8, sel)
                    val glyph = Pen(state.onSelectionAccent(selectionPageIndex()), 1.6, cosmetic = true)
                    for (run in ResizeMath.moveGlyph(c, side * 0.5)) r.strokePolyline(run, glyph)
                }
                drawEditDots(r)
                drawConnectorChrome(r)
            }

            // Where a connector can glue, while one is armed or an end is being dragged.
            if (marking) drawConnectionMarks(r)
        }

        // Note icons: chrome of one size at any zoom, so in viewport space.
        noteIcons?.draw(r)

        // Eraser cursor (viewport space, after the transform is restored).
        eraserCursor?.let {
            val radius = eraserRadius() * state.zoom
            r.strokeEllipse(it, radius, radius, Pen(state.palette.textDim, 1.3, cosmetic = true))
        }

        // Ruler (viewport space; floats above all content and the live stroke).
        if (ruler.visible) drawRuler(r)
    }

    /** The page the selection sits on, or null (the current page) when nothing is selected. */
    private fun selectionPageIndex(): Int? = selection.firstOrNull()?.pageIndex

    /**
     * A marquee pen: dashed, so chrome reads as chrome rather than as ink, off the same dp runs
     * the infinite canvas tessellates its own from, so a band, a lasso and a selection box look
     * like each other and like themselves on the other canvas. A cosmetic pen takes its dash runs
     * in device px, which is what the conversion here produces.
     */
    private fun chromePen(width: Double): Pen = Pen(
        state.selectionAccent(selectionPageIndex()),
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

        /** Content px a duplicate lands from its original, so the copy is visibly a copy. */
        const val DUPLICATE_NUDGE = 24.0

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

        /** A finger lifting that Android judged accidental, a resting palm say (FLAG_CANCELED, Android 13 on). */
        fun isCancelledLift(e: MotionEvent): Boolean = isCancelledLift(e.flags, Build.VERSION.SDK_INT)

        internal fun isCancelledLift(flags: Int, sdk: Int): Boolean =
            sdk >= Build.VERSION_CODES.TIRAMISU && (flags and MotionEvent.FLAG_CANCELED) != 0

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

        /** How far outside a tape's edge a tap still peels it, in dp. */
        const val TAPE_TAP_REACH = 4.0

        /** A freehand band takes a point of the hand's path each time it moves this far on screen, in px. */
        const val TAPE_STEP = 3.0

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

        /** How far a drawn connector's route keeps clear of what it goes round, in dp. */
        const val ROUTE_MARGIN_DP = 12.0

        /** Past this many connection points in view, no more are marked. */
        const val MAX_MARKS = 4000

        /** Min capture size (content px, both axes) for the screenshot tool; below it a drag is a tap. */
        const val SHOT_MIN = 6.0

        /** Min drag (viewport px, either axis) for a text-box gesture to size a box rather than tap-create. */
        const val TEXT_DRAG_SLOP = 14.0

        /** Text point-size clamp for the style bar. */
        const val TEXT_MIN_PT = 6.0
        const val TEXT_MAX_PT = 96.0
        const val LONG_PRESS_MS = 450L
        const val LONG_PRESS_SLOP = 6.0

        /** How near a lone selected shape's outline (device px) a drawing tool's press grabs it. */
        const val OUTLINE_GRAB = 14.0

        /** Two taps on the same vertex dot within this long (ms) delete the vertex. */
        const val DOUBLE_TAP_MS = 320L

        /** How far apart, in dp, the two taps of a double tap may land. */
        const val DOUBLE_TAP_SLOP_DP = 24.0

        private val CORNER_HANDLES = setOf(HandleId.TL, HandleId.TR, HandleId.BL, HandleId.BR)

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

        /** With the Perfect switch on, a line/arrow keeps its angle to whole steps of this (deg). */
        const val SHAPE_PERFECT_STEP_DEG = 15.0

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
        const val ADD_PAGE_PULL_GAIN = 0.85
        const val FLIP_RESIST = 0.48 // keep movement free near the edge without arming the flip too soon
        const val FLIP_MAX_FRACTION = 0.78 // maximum visual pull before the page settles
    }
}
