package com.xnotes.canvas

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.AddItems
import com.xnotes.core.history.EraseItems
import com.xnotes.core.history.History
import com.xnotes.core.history.EditText
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.ReorderItems
import com.xnotes.core.history.ResizeItem
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.GeoHandle
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.RectHandle
import com.xnotes.core.model.Resizable
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeHandle
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextHandle
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import kotlin.math.abs
import kotlin.math.exp

/** The pointer state machine modes (spec 06 §1). */
enum class PointerMode { IDLE, DRAW, ERASE, BAND, LASSO_DRAW, SHAPE, MOVE, RESIZE, PAN, PINCH }

/** On-screen geometry of the live text editor field (viewport pixels). */
data class EditingField(
    val x: Double,
    val y: Double,
    val width: Double,
    val fontPx: Double,
    val rgba: Rgba,
    val text: String,
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
    private val onSelectionChanged: (Boolean) -> Unit = {},
    private val onToolChanged: (Tool) -> Unit = {},
    private val onTextEditStart: (EditingField?) -> Unit = {},
    private val onTextEditEnd: () -> Unit = {},
    /** Selection menu: a viewport rect to show it anchored to, or null to hide. */
    private val onSelectionMenu: (Rect?) -> Unit = {},
    /** Long-press on empty space: open a context menu at (viewport, content). */
    private val onContextMenu: (Pt, Pt) -> Unit = { _, _ -> },
    /** Pulled past the document's bottom end far enough and released: append a new page. */
    private val onAddPageAtEnd: () -> Unit = {},
    /** A short haptic tick (e.g. the overscroll pull crossed the add-page threshold). */
    private val onHaptic: () -> Unit = {},
) {
    /** Whether the system clipboard currently holds an image (provided by the host). */
    var clipboardHasImage: () -> Boolean = { false }
    val document: Document get() = state.document

    var tool: Tool = Tool.DEFAULT
        private set
    var inkColor: Rgba = InkPalette.DEFAULT

    /** Whether a finger draws (true) or pans (false). The stylus always uses the armed tool. */
    var fingerDraws: Boolean = false

    /** Tool the S Pen side button activates while held, or null to ignore the button. */
    var penButtonTool: Tool? = Tool.ERASER

    private val toolConfigs: MutableMap<Tool, ToolConfig> =
        Tool.entries.associateWith { ToolDefaults.configFor(it) }.toMutableMap()

    private var mode = PointerMode.IDLE

    // DRAW
    private var liveStroke: Stroke? = null
    private var strokePageIndex: Int? = null

    /** Event time of the live stroke's first sample; later samples store `eventTime − this` (the speed pen reads it). */
    private var strokeStartTimeMs = 0L
    private var drawingPointerId = -1
    private var drawingIsStylus = false

    // PAN + inertial fling
    private var lastPan = Pt.ZERO
    private var lastMoveMs = 0L
    private var panVel = Pt.ZERO // smoothed finger velocity, viewport px/s
    private val choreographer = Choreographer.getInstance()
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

    // SELECTION
    private val selection = mutableListOf<Selected>()
    private var lassoPolygon: List<Pt>? = null
    private val lassoPoints = mutableListOf<Pt>()
    private var bandRect: Rect? = null
    private var moveOrigin = Pt.ZERO
    private var moveOffset = Pt.ZERO

    // ERASE
    private val eraseRemovals = mutableListOf<Pair<Page, CanvasItem>>()
    private var eraserCursor: Pt? = null // viewport pixels

    // ITEM CLIPBOARD (in-app, for copy/cut/paste/duplicate)
    private val itemClipboard = mutableListOf<CanvasItem>()
    fun hasClipboardItems(): Boolean = itemClipboard.isNotEmpty()

    // SHAPE
    var shapeConfig: ShapeConfig = ShapeConfig()
    private var pendingShape: ShapeItem? = null
    private var shapePageIndex: Int? = null

    // RESIZE
    private var resizeItem: CanvasItem? = null
    private var resizeHandle: HandleId? = null
    private var resizeOldGeom: GeoHandle? = null
    private var resizePageIndex: Int = -1

    // LONG-PRESS GRAB
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var longPressStart = Pt.ZERO
    private var longPressContent = Pt.ZERO
    private var longPressCandidate: Selected? = null
    private var longPressPrevTool: Tool? = null

    // TEXT EDITING
    private var editingText: TextItem? = null
    private var editingIsNew = false
    private var editingOldText = ""
    private var editingPageIndex = -1
    val editingItem: TextItem? get() = editingText
    val editingPage: Int get() = editingPageIndex

    init {
        state.isLiftedItem = { item -> item === editingText || selection.any { it.item === item } }
    }

    val hasSelection: Boolean get() = selection.isNotEmpty()

    /** The in-progress stroke and the page it is on (for the presentation frame). */
    val activeLiveStroke: Stroke? get() = liveStroke
    val activeLiveStrokePage: Int? get() = strokePageIndex

    fun configFor(t: Tool): ToolConfig = toolConfigs[t] ?: ToolDefaults.configFor(t)

    fun setToolConfig(t: Tool, config: ToolConfig) {
        toolConfigs[t] = config
    }

    fun setTool(t: Tool) {
        if (t == tool) {
            return
        }
        commitTextEdit()
        abortGesture()
        clearSelection()
        eraserCursor = null
        tool = t
        onToolChanged(t)
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

    private fun handleDown(e: MotionEvent) {
        stopFling() // a new touch halts any in-progress glide
        stopOverscrollSettle() // ...and lets a re-grab take over the elastic mid-spring
        val toolType = e.getToolType(0)
        val vx = e.getX(0).toDouble()
        val vy = e.getY(0).toDouble()
        val content = state.viewportToContent(Pt(vx, vy))
        drawingPointerId = e.getPointerId(0)
        drawingIsStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS

        // A press elsewhere commits an open text edit (the field's focus-out also fires).
        if (tool != Tool.TEXT) commitTextEdit()

        // Resolve which tool this pointer drives:
        //  - the stylus eraser end, or the held side button, force the eraser/side-button tool;
        //  - a finger pans unless finger-draw is enabled;
        //  - otherwise the armed tool.
        val buttonHeld = drawingIsStylus &&
            (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
        val effectiveTool: Tool = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            buttonHeld && penButtonTool != null -> penButtonTool!!
            // A finger pans instead of drawing freehand ink (other tools stay usable by finger).
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.isStroke -> Tool.PAN
            else -> tool
        }

        when {
            effectiveTool == Tool.PAN -> beginPan(vx, vy)
            effectiveTool.isStroke -> beginDraw(content, resolvePressure(e, 0, toolType), effectiveTool, e.eventTime)
            effectiveTool == Tool.ERASER -> { clearSelection(); beginErase(vx, vy) }
            effectiveTool == Tool.SELECT -> beginSelect(content)
            effectiveTool == Tool.LASSO -> beginLasso(content)
            effectiveTool == Tool.SHAPE -> beginShape(content)
            effectiveTool == Tool.TEXT -> beginText(content)
            else -> Unit
        }
        armLongPress(Pt(vx, vy), content)
    }

    private fun handlePointerDown(e: MotionEvent) {
        cancelLongPress()
        if (mode == PointerMode.DRAW && drawingIsStylus) return
        if (mode == PointerMode.ERASE) return
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
            PointerMode.ERASE -> eraseAt(vx, vy)
            PointerMode.BAND -> extendBand(content)
            PointerMode.LASSO_DRAW -> extendLasso(content)
            PointerMode.MOVE -> extendMove(content)
            PointerMode.RESIZE -> extendResize(content)
            PointerMode.SHAPE -> extendShape(content)
            else -> Unit
        }
    }

    private fun handlePointerUp(e: MotionEvent) {
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
                if (state.overscrollY > 0.0) releaseOverscroll() else startFling(panVel)
            }
            PointerMode.PINCH -> endPinch()
            PointerMode.ERASE -> endErase()
            PointerMode.BAND -> endBand()
            PointerMode.LASSO_DRAW -> endLasso()
            PointerMode.MOVE -> endMove(content)
            PointerMode.RESIZE -> endResize()
            PointerMode.SHAPE -> endShape()
            else -> Unit
        }
    }

    // --- DRAW ---

    private fun beginDraw(content: Pt, pressure: Double, drawTool: Tool, timeMs: Long) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        val pr = state.pageRects[pageIndex]
        // Capture content-px → dp scale now, so the speed pen judges gesture speed in
        // zoom- and density-independent dp regardless of how the stroke is later viewed.
        val speedScale = state.zoom / state.devicePxPerDp
        val stroke = Stroke(drawTool, configFor(drawTool).copy(rgba = inkColor), speedScale = speedScale)
        strokeStartTimeMs = timeMs
        stroke.addSample(Sample(content.x - pr.left, content.y - pr.top, pressure)) // first sample: t = 0
        liveStroke = stroke
        strokePageIndex = pageIndex
        mode = PointerMode.DRAW
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
        requestRender()
    }

    private fun addStrokePoint(vx: Double, vy: Double, pressure: Double, timeMs: Long, force: Boolean) {
        val stroke = liveStroke ?: return
        val pi = strokePageIndex ?: return
        val pr = state.pageRects.getOrNull(pi) ?: return
        val content = state.viewportToContent(Pt(vx, vy))
        val local = Pt(content.x - pr.left, content.y - pr.top)
        val last = stroke.samples.lastOrNull()
        if (force || last == null || Pt(last.x, last.y).manhattanTo(local) >= MIN_SAMPLE_DIST) {
            stroke.addSample(Sample(local.x, local.y, pressure.coerceIn(0.0, 1.0), (timeMs - strokeStartTimeMs).toDouble()))
        }
    }

    private fun endDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            if (drawingIsStylus) e.getPressure(idx).toDouble() else 1.0, e.eventTime, force = true,
        )
        val stroke = liveStroke
        val pi = strokePageIndex
        if (stroke != null && pi != null && !stroke.isEmpty) {
            val page = state.document.pages[pi]
            page.items.add(stroke)
            state.appendToCache(page, stroke)
            history.push(AddItem(page, stroke))
            state.document.dirty = true
            onContentChanged()
        }
        liveStroke = null
        strokePageIndex = null
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- ERASE ---

    private fun eraserRadius(): Double = configFor(Tool.ERASER).baseWidth

    private fun beginErase(vx: Double, vy: Double) {
        eraseRemovals.clear()
        mode = PointerMode.ERASE
        eraseAt(vx, vy)
    }

    private fun eraseAt(vx: Double, vy: Double) {
        eraserCursor = Pt(vx, vy)
        val content = state.viewportToContent(Pt(vx, vy))
        val radius = eraserRadius()
        val eraserBox = Rect(content.x - radius, content.y - radius, radius * 2, radius * 2)
        var changed = false
        for (pi in state.document.pages.indices) {
            val pr = state.pageRects.getOrNull(pi) ?: continue
            if (!pr.intersects(eraserBox)) continue // skip pages the eraser isn't over
            val page = state.document.pages[pi]
            val cx = content.x - pr.left
            val cy = content.y - pr.top
            // Erase strokes and shapes; images are deliberately-placed and protected.
            val toRemove = page.items.filter {
                it !is ImageItem && it.intersectsCircle(cx, cy, radius)
            }
            if (toRemove.isNotEmpty()) {
                var dirty: Rect? = null
                for (item in toRemove) {
                    page.items.remove(item)
                    eraseRemovals.add(page to item)
                    val b = item.paintBounds()
                    dirty = dirty?.union(b) ?: b
                }
                // Repaint only the erased area in place; fall back to a full
                // rebuild only when the page has no live cache yet.
                val rect = dirty?.outset(REPAIR_PAD)
                if (rect == null || !state.repairRegion(page, rect)) state.invalidatePage(page)
                changed = true
            }
        }
        if (changed) onContentChanged()
        requestRender()
    }

    private fun endErase() {
        if (eraseRemovals.isNotEmpty()) {
            history.push(EraseItems(eraseRemovals.toList()))
            state.document.dirty = true
            onContentChanged()
        }
        eraseRemovals.clear()
        eraserCursor = null
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- SELECT / BAND ---

    private fun beginSelect(content: Pt) {
        // 1. Resize handle under the point (single resizable item selected)?
        if (selection.size == 1 && selection[0].item is Resizable) {
            val sel = selection[0]
            val pr = state.pageRects.getOrNull(sel.pageIndex)
            if (pr != null) {
                val handles = ResizeMath.handles(sel.item, pr.topLeft)
                val tol = HANDLE_HIT / state.zoom
                val id = ResizeMath.hitHandle(handles, content, tol)
                if (id != null) {
                    beginResize(sel, id)
                    return
                }
            }
        }
        val pageIndex = state.pageIndexAtContent(content)
        if (pageIndex != null) {
            val pr = state.pageRects[pageIndex]
            val local = Pt(content.x - pr.left, content.y - pr.top)
            val hit = state.document.pages[pageIndex].items.lastOrNull { it.contains(local) }
            if (hit != null) {
                if (selection.none { it.item === hit }) setSelection(listOf(Selected(pageIndex, hit)))
                beginMove(content)
                return
            }
        }
        if (selection.isNotEmpty() && selectionBoundsContent()?.contains(content) == true) {
            beginMove(content)
            return
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
        bandRect?.let { setSelection(SelectionMath.bandMembers(state.document.pages, state.pageRects, it)) }
        bandRect = null
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    // --- LASSO ---

    private fun beginLasso(content: Pt) {
        val poly = lassoPolygon
        if (poly != null && selection.isNotEmpty() && com.xnotes.core.geometry.Geometry.pointInPolygon(poly, content)) {
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
            val members = SelectionMath.lassoMembers(state.document.pages, state.pageRects, lassoPoints)
            if (members.isEmpty()) {
                clearSelection()
            } else {
                setSelection(members)
                lassoPolygon = lassoPoints.toList()
            }
        } else {
            clearSelection()
        }
        lassoPoints.clear()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
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
        if (abs(moveOffset.x) > MOVE_EPS || abs(moveOffset.y) > MOVE_EPS) {
            val items = selection.map { it.item }
            for (item in items) item.translate(moveOffset.x, moveOffset.y)
            lassoPolygon = lassoPolygon?.map { Pt(it.x + moveOffset.x, it.y + moveOffset.y) }
            history.push(MoveItems(items, moveOffset.x, moveOffset.y))
            state.document.dirty = true
            invalidateSelectionPages()
            onContentChanged()
        }
        moveOffset = Pt.ZERO
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
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
        val pr = state.pageRects.getOrNull(resizePageIndex) ?: return
        val local = Pt(content.x - pr.left, content.y - pr.top)
        when (item) {
            is ImageItem -> item.setGeometry(RectHandle(ResizeMath.resizeImage(item.rect, handle, local)))
            is TextItem -> {
                val (pos, w) = ResizeMath.resizeText(item.pos, item.width, handle, local.x)
                item.setGeometry(TextHandle(pos, w))
            }
            is ShapeItem -> {
                val (s, en) = if (item.shape.isClosed) {
                    ResizeMath.resizeClosedShape(item.start, item.end, handle, local)
                } else {
                    ResizeMath.resizeOpenShape(item.start, item.end, handle, local)
                }
                item.setGeometry(ShapeHandle(s, en))
            }
        }
        requestRender()
    }

    private fun endResize() {
        val item = resizeItem as? Resizable
        val old = resizeOldGeom
        if (item != null && old != null) {
            val new = item.geometry()
            if (new != old) {
                history.push(ResizeItem(item, old, new))
                state.document.dirty = true
                state.document.pages.getOrNull(resizePageIndex)?.let(state::invalidatePage)
                onContentChanged()
            }
        }
        resizeItem = null
        resizeHandle = null
        resizeOldGeom = null
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    // --- SHAPE ---

    private fun beginShape(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        val pr = state.pageRects[pageIndex]
        val startLocal = Pt(content.x - pr.left, content.y - pr.top)
        val kind = shapeConfig.shape
        val fill = if (shapeConfig.fill && kind.isClosed) inkColor.scaleAlpha(ShapeConfig.FILL_ALPHA) else null
        pendingShape = ShapeItem(kind, startLocal, startLocal, inkColor, shapeConfig.strokeWidth, fill, shapeConfig.neon, shapeConfig.neonStrength)
        shapePageIndex = pageIndex
        mode = PointerMode.SHAPE
        requestRender()
    }

    private fun extendShape(content: Pt) {
        val shape = pendingShape ?: return
        val pr = state.pageRects.getOrNull(shapePageIndex ?: -1) ?: return
        shape.end = Pt(content.x - pr.left, content.y - pr.top)
        requestRender()
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
        }
        pendingShape = null
        shapePageIndex = null
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- TEXT ---

    private fun beginText(content: Pt) {
        commitTextEdit()
        val pi = state.pageIndexAtContent(content) ?: return
        val pr = state.pageRects[pi]
        val local = Pt(content.x - pr.left, content.y - pr.top)
        val page = state.document.pages[pi]
        val existing = page.items.lastOrNull { it is TextItem && it.contains(local) } as? TextItem
        if (existing != null) {
            editingText = existing
            editingIsNew = false
            editingOldText = existing.text
            editingPageIndex = pi
            state.invalidatePage(page)
        } else {
            val width = (page.width - local.x - 14.0).coerceIn(80.0, 300.0)
            editingText = TextItem(local, width, "", measurer = textMeasurer)
            editingIsNew = true
            editingOldText = ""
            editingPageIndex = pi
        }
        onTextEditStart(editingField())
        requestRender()
    }

    /** Keep the model in sync with the live editor field (for auto-resize/commit). */
    fun updateEditingText(text: String) {
        editingText?.text = text
    }

    /** Current on-screen geometry of the editor field, or null when not editing. */
    fun editingField(): EditingField? {
        val item = editingText ?: return null
        val pr = state.pageRects.getOrNull(editingPageIndex) ?: return null
        val topLeft = state.contentToViewport(Pt(pr.left + item.pos.x, pr.top + item.pos.y))
        return EditingField(
            x = topLeft.x,
            y = topLeft.y,
            width = item.width * state.zoom,
            fontPx = item.pointSize * com.xnotes.platform.AndroidText.POINTS_TO_PX * state.zoom,
            rgba = item.rgba,
            text = item.text,
        )
    }

    /** Commit (Escape / done / focus-out / tool switch). Uses the model's current text. */
    fun commitTextEdit(finalText: String? = null) {
        val item = editingText ?: return
        finalText?.let { item.text = it }
        val pi = editingPageIndex
        if (editingIsNew) {
            if (item.text.trim().isNotEmpty()) {
                val page = state.document.pages[pi]
                page.items.add(item)
                history.push(AddItem(page, item))
                state.document.dirty = true
                onContentChanged()
            }
        } else if (item.text != editingOldText) {
            history.push(EditText(item, editingOldText, item.text))
            state.document.dirty = true
            onContentChanged()
        }
        editingText = null
        editingIsNew = false
        editingPageIndex = -1
        editingOldText = ""
        state.document.pages.getOrNull(pi)?.let(state::invalidatePage)
        onTextEditEnd()
        requestRender()
    }

    // --- LONG-PRESS GRAB ---

    private fun armLongPress(viewport: Pt, content: Pt) {
        cancelLongPress()
        val grabEligible = tool.isStroke || tool == Tool.PAN || tool == Tool.LASSO || tool == Tool.SHAPE || tool == Tool.TEXT
        val pageIndex = state.pageIndexAtContent(content)
        val hit = if (pageIndex != null) {
            val pr = state.pageRects[pageIndex]
            val local = Pt(content.x - pr.left, content.y - pr.top)
            state.document.pages[pageIndex].items.lastOrNull { it.contains(local) }
        } else {
            null
        }
        longPressStart = viewport
        longPressContent = content
        longPressCandidate = if (grabEligible && hit != null) Selected(pageIndex!!, hit) else null
        // Arm to grab an item, or (on empty space) to open the paste menu when there is content to paste.
        val showEmptyMenu = hit == null && (hasClipboardItems() || clipboardHasImage())
        if (longPressCandidate == null && !showEmptyMenu) return
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
    }

    private fun triggerLongPress() {
        longPressRunnable = null
        val candidate = longPressCandidate
        longPressCandidate = null
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
            // Empty space: open the paste context menu at the press point.
            mode = PointerMode.IDLE
            onContextMenu(longPressStart, longPressContent)
        }
        requestRender()
    }

    // --- selection management ---

    private fun setSelection(items: List<Selected>) {
        val affected = HashSet<Int>()
        selection.forEach { affected.add(it.pageIndex) }
        selection.clear()
        selection.addAll(items)
        selection.forEach { affected.add(it.pageIndex) }
        lassoPolygon = null
        affected.forEach { idx -> state.document.pages.getOrNull(idx)?.let(state::invalidatePage) }
        onSelectionChanged(selection.isNotEmpty())
        requestRender()
    }

    fun clearSelection() {
        // Restore the tool a long-press grab temporarily switched away from.
        longPressPrevTool?.let {
            tool = it
            longPressPrevTool = null
            onToolChanged(tool)
        }
        onSelectionMenu(null)
        if (selection.isEmpty() && lassoPolygon == null) return
        val affected = selection.map { it.pageIndex }.toSet()
        selection.clear()
        lassoPolygon = null
        affected.forEach { state.document.pages.getOrNull(it)?.let(state::invalidatePage) }
        onSelectionChanged(false)
        requestRender()
    }

    private fun invalidateSelectionPages() {
        selection.map { it.pageIndex }.toSet().forEach {
            state.document.pages.getOrNull(it)?.let(state::invalidatePage)
        }
    }

    private fun selectionBoundsContent(): Rect? {
        if (selection.isEmpty()) return null
        var acc: Rect? = null
        for (sel in selection) {
            val pr = state.pageRects.getOrNull(sel.pageIndex) ?: continue
            val b = sel.item.bounds().translate(pr.left, pr.top)
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
        clearSelection()
        state.invalidateAllCaches()
        onContentChanged()
    }

    fun selectAll() {
        val all = ArrayList<Selected>()
        state.document.pages.forEachIndexed { i, page -> page.items.forEach { all.add(Selected(i, it)) } }
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
                state.invalidatePage(page)
            }
        }
        state.document.dirty = true
        onContentChanged()
    }

    fun escape() {
        commitTextEdit()
        clearSelection()
        requestRender()
    }

    // --- clipboard: copy / cut / duplicate / paste ---

    fun copySelection() {
        if (selection.isEmpty()) return
        itemClipboard.clear()
        selection.forEach { itemClipboard.add(cloneItem(it.item)) }
    }

    fun cutSelection() {
        if (selection.isEmpty()) return
        copySelection()
        deleteSelection()
    }

    fun duplicateSelection() {
        if (selection.isEmpty()) return
        copySelection()
        val pageIndex = selection.first().pageIndex
        // Paste offset slightly from the originals (not repositioned to a point).
        pasteClonesOnPage(pageIndex, Pt.ZERO, offsetFromBoundsTopLeft = false, nudge = 24.0)
    }

    /** Paste the clipboard items so their top-left lands at the given content point. */
    fun pasteItemsAt(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: state.currentPageIndex()
        pasteClonesOnPage(pageIndex, content, offsetFromBoundsTopLeft = true, nudge = 0.0)
    }

    private fun pasteClonesOnPage(pageIndex: Int, target: Pt, offsetFromBoundsTopLeft: Boolean, nudge: Double) {
        if (itemClipboard.isEmpty()) return
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        val pr = state.pageRects.getOrNull(pageIndex) ?: return
        val clones = itemClipboard.map { cloneItem(it) }
        // Collective bounds (page-local) of the clones.
        var box: Rect? = null
        for (c in clones) box = box?.union(c.bounds()) ?: c.bounds()
        val b = box ?: return
        val targetLocal = Pt(target.x - pr.left, target.y - pr.top)
        val dx = if (offsetFromBoundsTopLeft) targetLocal.x - b.left + nudge else nudge
        val dy = if (offsetFromBoundsTopLeft) targetLocal.y - b.top + nudge else nudge
        for (c in clones) c.translate(dx, dy)
        page.items.addAll(clones)
        history.push(AddItems(page, clones))
        state.document.dirty = true
        state.invalidatePage(page)
        setSelection(clones.map { Selected(pageIndex, it) })
        refreshSelectionMenu()
        onContentChanged()
    }

    private fun cloneItem(item: CanvasItem): CanvasItem = when (item) {
        is Stroke -> Stroke(item.tool, item.config, item.samples.toMutableList(), item.speedScale)
        is ImageItem -> ImageItem(item.raster, item.rect)
        is TextItem -> TextItem(item.pos, item.width, item.text, item.rgba, item.pointSize, textMeasurer)
        is ShapeItem -> ShapeItem(item.shape, item.start, item.end, item.strokeRgba, item.strokeWidth, item.fillRgba, item.neon, item.neonStrength)
        else -> item
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
        return Rect.fromPoints(tl, br)
    }

    // --- PAN ---

    private fun beginPan(vx: Double, vy: Double) {
        mode = PointerMode.PAN
        startTrackingVelocity(vx, vy)
    }

    private fun extendPan(vx: Double, vy: Double) {
        trackVelocity(vx, vy)
        val dx = -(vx - lastPan.x)
        var dy = -(vy - lastPan.y)
        // While the bottom elastic is stretched, finger motion works the elastic first (rubber-band)
        // rather than the scroll, so pulling back relaxes the stretch before the document scrolls.
        if (state.overscrollY > 0.0) {
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
        if (leftoverY > 0.0) {
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

    // --- elastic overscroll release ---

    /** Finger lifted while the bottom elastic was stretched: add a page if pulled far enough, then spring back. */
    private fun releaseOverscroll() {
        stopFling()
        if (state.overscrollY >= OVERSCROLL_TRIGGER) onAddPageAtEnd()
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
        bandRect = null
        lassoPoints.clear()
        clearOverscroll() // a second finger ends any bottom-pull; the elastic snaps away
        mode = PointerMode.PINCH
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val mid = (a + b) * 0.5
        pinchInitDist = a.distanceTo(b).coerceAtLeast(1.0)
        pinchInitZoom = state.zoom
        pinchAnchorContent = state.viewportToContent(mid)
        startTrackingVelocity(mid.x, mid.y)
        state.zoomingInProgress = true
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
        val z = if (state.zoomLocked) pinchInitZoom
        else (pinchInitZoom * (dist / pinchInitDist)).coerceIn(CanvasState.MIN_ZOOM, CanvasState.MAX_ZOOM)
        state.zoom = z
        state.scrollX = pinchAnchorContent.x * z - mid.x
        state.scrollY = pinchAnchorContent.y * z - mid.y
        state.clampScroll()
        lastPan = mid
        onViewChanged() // live zoom %: refresh the toolbar each pinch frame, not just at the end
        requestRender()
    }

    private fun endPinch() {
        mode = PointerMode.IDLE
        state.zoomingInProgress = false
        state.invalidateCachesForZoom() // keep stale surfaces to blit until the sharp rebuild lands
        onViewChanged()
        requestRender()
        startFling(panVel)
    }

    private fun abortGesture() {
        cancelLongPress()
        stopFling()
        clearOverscroll()
        liveStroke = null
        strokePageIndex = null
        pendingShape = null
        shapePageIndex = null
        bandRect = null
        lassoPoints.clear()
        if (mode == PointerMode.PINCH) {
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
                    sel.item.paint(r)
                }
            }

            // Live in-progress stroke / shape preview, clipped to its page.
            liveStroke?.let { stroke -> paintClippedToPage(r, strokePageIndex) { stroke.paint(r) } }
            pendingShape?.let { shape -> paintClippedToPage(r, shapePageIndex) { shape.paint(r) } }

            // Selection chrome.
            val accent = Pen(state.palette.accent, 1.3, cosmetic = true, dashed = true)
            when {
                mode == PointerMode.BAND -> bandRect?.let { r.strokeRect(it, accent) }
                mode == PointerMode.LASSO_DRAW && lassoPoints.size >= 2 ->
                    r.strokePolyline(lassoPoints, Pen(state.palette.accent, 1.3, cosmetic = true))
                lassoPolygon != null && selection.isNotEmpty() ->
                    r.strokePolygon(lassoPolygon!!.map { Pt(it.x + moveOffset.x, it.y + moveOffset.y) }, accent)
                selection.isNotEmpty() ->
                    selectionBoundsContent()?.translate(moveOffset.x, moveOffset.y)?.let { r.strokeRect(it, accent) }
            }

            // Resize handles for a single resizable selection.
            if (selection.size == 1 && selection[0].item is Resizable &&
                mode != PointerMode.BAND && mode != PointerMode.LASSO_DRAW
            ) {
                val sel = selection[0]
                val pr = state.pageRects.getOrNull(sel.pageIndex)
                if (pr != null) {
                    val side = HANDLE_HIT / state.zoom
                    for (h in ResizeMath.handles(sel.item, pr.topLeft)) {
                        val c = Pt(h.content.x + moveOffset.x, h.content.y + moveOffset.y)
                        r.fillRect(Rect(c.x - side / 2, c.y - side / 2, side, side), state.palette.accent)
                    }
                }
            }
        }

        // Eraser cursor (viewport space, after the transform is restored).
        eraserCursor?.let {
            val radius = eraserRadius() * state.zoom
            r.strokeEllipse(it, radius, radius, Pen(state.palette.textDim, 1.3, cosmetic = true))
        }
    }

    private inline fun paintClippedToPage(r: Renderer, pageIndex: Int?, crossinline paint: () -> Unit) {
        val pr = state.pageRects.getOrNull(pageIndex ?: -1) ?: return
        r.withSave {
            r.clipRect(pr)
            r.translate(pr.left, pr.top)
            paint()
        }
    }

    companion object {
        const val MIN_SAMPLE_DIST = 1.0
        const val MOVE_EPS = 0.01

        /** Padding (content px) added around erased items' bounds when repairing
         *  the cache, to cover stroke anti-aliasing at the dirty-rect edge. */
        const val REPAIR_PAD = 2.0
        const val HANDLE_HIT = 9.0
        const val SHAPE_MIN_DRAG = 3.0
        const val LONG_PRESS_MS = 450L
        const val LONG_PRESS_SLOP = 6.0

        // Inertial fling tuning (viewport px/s).
        const val VEL_SMOOTH = 0.4 // EMA weight on the previous velocity estimate
        const val FLING_FRICTION = 2.5 // higher = stops sooner; lower = floatier
        const val FLING_MIN_START = 120.0 // minimum flick velocity to start a glide
        const val FLING_MIN_STOP = 24.0 // velocity at which the glide ends

        // Elastic overscroll tuning (pull past the bottom end to add a page).
        const val OVERSCROLL_RESIST = 0.45 // fraction of past-end finger travel that becomes visible stretch
        const val OVERSCROLL_MAX = 240.0 // hard cap on the visible stretch (viewport px)
        const val OVERSCROLL_TRIGGER = 130.0 // stretch at which releasing appends a page
        const val OVERSCROLL_SPRING = 11.0 // spring-back rate toward rest (1/s; higher = snappier)
    }
}
