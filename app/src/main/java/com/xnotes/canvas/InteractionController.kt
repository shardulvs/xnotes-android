package com.xnotes.canvas

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults

/** The pointer state machine modes (spec 06 §1). */
enum class PointerMode { IDLE, DRAW, ERASE, BAND, LASSO_DRAW, SHAPE, MOVE, RESIZE, PAN, PINCH }

/**
 * Drives editing from pointer input (spec 06). This commit wires drawing
 * (DRAW), single-finger panning with the pan tool, and two-finger pan/zoom
 * (PINCH); later commits add erase, selection, shapes and text.
 *
 * Single pointer routes to the armed tool's gesture; a second finger switches to
 * pinch (aborting an in-progress finger stroke). A stylus stroke ignores finger
 * pointers (palm rejection).
 */
class InteractionController(
    private val state: CanvasState,
    val history: History,
    private val requestRender: () -> Unit,
    private val onContentChanged: () -> Unit = {},
    private val onViewChanged: () -> Unit = {},
) {
    val document: Document get() = state.document

    var tool: Tool = Tool.DEFAULT
        private set
    var inkColor: Rgba = InkPalette.DEFAULT

    private val toolConfigs: MutableMap<Tool, ToolConfig> =
        Tool.entries.associateWith { ToolDefaults.configFor(it) }.toMutableMap()

    private var mode = PointerMode.IDLE

    // --- DRAW gesture state ---
    private var liveStroke: Stroke? = null
    private var strokePageIndex: Int? = null
    private var drawingPointerId = -1
    private var drawingIsStylus = false

    // --- PAN gesture state ---
    private var lastPan = Pt.ZERO

    // --- PINCH gesture state ---
    private var pinchInitDist = 1.0
    private var pinchInitZoom = 1.0
    private var pinchAnchorContent = Pt.ZERO

    fun configFor(t: Tool): ToolConfig = toolConfigs[t] ?: ToolDefaults.configFor(t)

    fun setTool(t: Tool) {
        if (t == tool) return
        abortGesture()
        tool = t
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
            MotionEvent.ACTION_CANCEL -> abortGesture().also { requestRender() }
        }
        return true
    }

    private fun handleDown(e: MotionEvent) {
        val toolType = e.getToolType(0)
        val vx = e.getX(0).toDouble()
        val vy = e.getY(0).toDouble()
        drawingPointerId = e.getPointerId(0)
        drawingIsStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS

        when {
            tool == Tool.PAN -> beginPan(vx, vy)
            tool.isStroke -> beginDraw(vx, vy, resolvePressure(e, 0, toolType))
            // erase / select / lasso / shape / text arrive in the next commit
            else -> Unit
        }
    }

    private fun handlePointerDown(e: MotionEvent) {
        // A stylus stroke ignores additional (finger) pointers.
        if (mode == PointerMode.DRAW && drawingIsStylus) return
        if (e.pointerCount >= 2) beginPinch(e)
    }

    private fun handleMove(e: MotionEvent) {
        when (mode) {
            PointerMode.DRAW -> extendDraw(e)
            PointerMode.PAN -> {
                val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
                extendPan(e.getX(idx).toDouble(), e.getY(idx).toDouble())
            }
            PointerMode.PINCH -> updatePinch(e)
            else -> Unit
        }
    }

    private fun handlePointerUp(e: MotionEvent) {
        if (mode == PointerMode.PINCH && e.pointerCount <= 2) endPinch()
    }

    private fun handleUp(e: MotionEvent) {
        when (mode) {
            PointerMode.DRAW -> endDraw(e)
            PointerMode.PAN -> mode = PointerMode.IDLE
            PointerMode.PINCH -> endPinch()
            else -> Unit
        }
    }

    // --- DRAW ---

    private fun beginDraw(vx: Double, vy: Double, pressure: Double) {
        val content = state.viewportToContent(Pt(vx, vy))
        val pageIndex = state.pageIndexAtContent(content) ?: return
        val pr = state.pageRects[pageIndex]
        val config = configFor(tool).copy(rgba = inkColor)
        val stroke = Stroke(tool, config)
        stroke.addSample(Sample(content.x - pr.left, content.y - pr.top, pressure))
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
                force = false,
            )
        }
        addStrokePoint(
            e.getX(idx).toDouble(),
            e.getY(idx).toDouble(),
            if (drawingIsStylus) e.getPressure(idx).toDouble() else 1.0,
            force = false,
        )
        requestRender()
    }

    private fun addStrokePoint(vx: Double, vy: Double, pressure: Double, force: Boolean) {
        val stroke = liveStroke ?: return
        val pi = strokePageIndex ?: return
        val pr = state.pageRects.getOrNull(pi) ?: return
        val content = state.viewportToContent(Pt(vx, vy))
        val local = Pt(content.x - pr.left, content.y - pr.top)
        val last = stroke.samples.lastOrNull()
        if (force || last == null || Pt(last.x, last.y).manhattanTo(local) >= MIN_SAMPLE_DIST) {
            stroke.addSample(Sample(local.x, local.y, pressure.coerceIn(0.0, 1.0)))
        }
    }

    private fun endDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        addStrokePoint(
            e.getX(idx).toDouble(),
            e.getY(idx).toDouble(),
            if (drawingIsStylus) e.getPressure(idx).toDouble() else 1.0,
            force = true,
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

    // --- PAN ---

    private fun beginPan(vx: Double, vy: Double) {
        mode = PointerMode.PAN
        lastPan = Pt(vx, vy)
    }

    private fun extendPan(vx: Double, vy: Double) {
        state.scrollBy(-(vx - lastPan.x), -(vy - lastPan.y))
        lastPan = Pt(vx, vy)
        onViewChanged()
        requestRender()
    }

    // --- PINCH (two-finger pan + zoom) ---

    private fun beginPinch(e: MotionEvent) {
        liveStroke = null
        strokePageIndex = null
        mode = PointerMode.PINCH
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val mid = (a + b) * 0.5
        pinchInitDist = a.distanceTo(b).coerceAtLeast(1.0)
        pinchInitZoom = state.zoom
        pinchAnchorContent = state.viewportToContent(mid)
        state.zoomingInProgress = true
    }

    private fun updatePinch(e: MotionEvent) {
        if (e.pointerCount < 2) return
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val dist = a.distanceTo(b)
        if (dist < 1e-3) return
        val mid = (a + b) * 0.5
        val z = (pinchInitZoom * (dist / pinchInitDist)).coerceIn(CanvasState.MIN_ZOOM, CanvasState.MAX_ZOOM)
        state.zoom = z
        state.scrollX = pinchAnchorContent.x * z - mid.x
        state.scrollY = pinchAnchorContent.y * z - mid.y
        state.clampScroll()
        requestRender()
    }

    private fun endPinch() {
        mode = PointerMode.IDLE
        state.zoomingInProgress = false
        state.invalidateAllCaches()
        onViewChanged()
        requestRender()
    }

    private fun abortGesture() {
        liveStroke = null
        strokePageIndex = null
        if (mode == PointerMode.PINCH) {
            state.zoomingInProgress = false
            state.invalidateAllCaches()
        }
        mode = PointerMode.IDLE
    }

    private fun resolvePressure(e: MotionEvent, pointerIndex: Int, toolType: Int): Double =
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) e.getPressure(pointerIndex).toDouble().coerceIn(0.0, 1.0) else 1.0

    // --- overlay (live stroke; selection/eraser added later) ---

    fun drawOverlay(r: Renderer) {
        val stroke = liveStroke ?: return
        val pi = strokePageIndex ?: return
        val pr: Rect = state.pageRects.getOrNull(pi) ?: return
        val origin = state.origin()
        r.withSave {
            r.translate(origin.x, origin.y)
            r.scale(state.zoom, state.zoom)
            r.withSave {
                r.clipRect(pr)
                r.translate(pr.left, pr.top)
                stroke.paint(r)
            }
        }
    }

    companion object {
        /** Minimum page-local Manhattan distance between recorded samples (spec 03 §5). */
        const val MIN_SAMPLE_DIST = 1.0
    }
}
