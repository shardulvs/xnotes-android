package com.xnotes.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.xnotes.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.Pen
import com.xnotes.platform.AndroidRenderer

/**
 * The on-screen canvas. Draws the document in immediate mode each frame
 * (spec 05 §6): window background, then visible pages (paper + hairline border +
 * cached background layer + cached ink). Selection overlay, the live stroke and
 * the eraser cursor are layered on top by later interaction code.
 */
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var state: CanvasState? = null
        set(value) {
            field = value
            value?.let { st ->
                st.devicePxPerDp = resources.displayMetrics.density.toDouble()
                // Rasterize newly visible pages off the UI thread so scrolling never
                // stalls while a page is built; publish the surface back on the main
                // thread and ask for a repaint.
                st.runAsync = { work ->
                    val ex = cacheExecutor
                    if (ex != null && !ex.isShutdown) ex.execute(work) else work()
                }
                st.postToMain = { work -> mainHandler.post(work) }
                st.onCacheReady = { requestRender() }
            }
            invalidate()
        }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Single background thread that builds page caches; lives while the view is attached. */
    private var cacheExecutor: ExecutorService? = null

    /** Hook for overlay drawing (selection/live-stroke/eraser), set by the interaction layer. */
    var drawOverlay: ((renderer: AndroidRenderer, canvas: Canvas) -> Unit)? = null

    /** Pointer handler installed by the interaction layer. */
    var input: ((MotionEvent) -> Boolean)? = null

    /** Clean two-finger tap (finger-only, brief, near-stationary), for the configurable gesture. */
    var onTwoFingerTap: (() -> Unit)? = null

    /** Clean three-finger tap (finger-only, brief, near-stationary), for the configurable gesture. */
    var onThreeFingerTap: (() -> Unit)? = null

    /** Invoked after the viewport is (re)laid out and the initial fit applied. */
    var afterLayout: (() -> Unit)? = null

    /** Hover handler (stylus/mouse hover) for the eraser cursor. */
    var hover: ((MotionEvent) -> Boolean)? = null

    /** Stylus side-button presses (generic-motion stream), for pens that don't put it in the touch buttonState. */
    var genericMotion: ((MotionEvent) -> Unit)? = null

    /** The inline flow text input surface (IME mirror), installed by the Editor. */
    var flowInput: FlowInput? = null

    /** Hardware keys arriving while this view holds focus (flow text sessions), fed to the Editor. */
    var onKey: ((android.view.KeyEvent) -> Boolean)? = null

    override fun onCheckIsTextEditor(): Boolean = flowInput?.sessionActive == true

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val input = flowInput
        if (input == null || !input.sessionActive) return null
        return input.createInputConnection(outAttrs)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        onKey?.invoke(event) == true || super.onKeyDown(keyCode, event)

    /** Transparent debug HUD (frame rate / cache / heap), toggled by a four-finger tap. */
    val debugOverlay = DebugOverlay()

    /** Whether the canvas scrollbar is shown (the View menu's per-note toggle). */
    var scrollbarEnabled = false

    /** Invoked after a scrollbar drag moved the view, so the host can refresh (page indicator). */
    var onScrollbarScrolled: (() -> Unit)? = null

    // Scrollbar drag state; the thumb is tracked as a fraction so fast drags don't drift.
    private var scrollbarDragging = false
    private var scrollbarFrac = 0.0
    private var scrollbarLastY = 0f
    private val scrollbarPaint = Paint()

    // Reused paints for the elastic "pull to add page" badge, so onDraw allocates nothing.
    private val overscrollStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val overscrollText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pullLabel = context.getString(R.string.pull_to_add_page)
    private val releaseLabel = context.getString(R.string.release_to_add_page)
    private val overscrollArc = RectF()

    /**
     * Low-rate repaint while the HUD is visible. The canvas only repaints on interaction,
     * so without this the frame-rate line would freeze at its last value when idle instead
     * of falling to 0. Runs only while [DebugOverlay.enabled]; costs nothing otherwise.
     */
    private val debugTick = object : Runnable {
        override fun run() {
            if (!debugOverlay.enabled) return
            requestRender() // vsync-aligned repaint, like scroll/cache-ready; a plain invalidate() does
                            // not reliably repaint this Compose-hosted view while idle, which froze the HUD
            mainHandler.postDelayed(this, DEBUG_TICK_MS)
        }
    }

    /** (Re)start the HUD's idle repaint loop when it's showing; safe to call repeatedly. */
    private fun startDebugTick() {
        mainHandler.removeCallbacks(debugTick)
        if (debugOverlay.enabled) mainHandler.postDelayed(debugTick, DEBUG_TICK_MS)
    }

    // --- four-finger-tap recognition (toggles the debug HUD) ---
    private var gestureDownMs = 0L
    private var gestureMaxPointers = 0
    private var fourFingerActive = false
    private var fourCx = 0f
    private var fourCy = 0f
    private var fourMoved = false

    // --- two/three-finger-tap recognition (configurable gesture actions) ---
    private val tapDownX = HashMap<Int, Float>()
    private val tapDownY = HashMap<Int, Float>()
    private var tapMoved = false
    private var tapAllFingers = true

    init {
        isFocusableInTouchMode = true
        setWillNotDraw(false)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (trackScrollbar(event)) return true
        if (trackFourFingerTap(event)) return true
        if (trackMultiFingerTap(event)) return true
        return input?.invoke(event) ?: super.onTouchEvent(event)
    }

    // --- canvas scrollbar (View menu toggle; drawn in [onDraw], dragged like the sidebar's) ---
    // Continuous mode: a vertical bar at the right edge mapping the scroll. Paginated mode: a
    // horizontal scrubber along the bottom whose thumb is the current row; dragging snaps per row.

    /** True when the scrollbar has anything to scroll for the current view. */
    private fun scrollbarActive(st: CanvasState): Boolean = scrollbarEnabled &&
        if (st.verticalScroll) st.maxScrollY() > st.minScrollY() else st.rowRanges().size > 1

    /** Thumb length (viewport px): the viewport's share of the content, floored for grabbability. */
    private fun scrollbarThumb(st: CanvasState): Float {
        val minThumb = SCROLLBAR_MIN_THUMB_DP * resources.displayMetrics.density
        if (!st.verticalScroll) {
            val track = st.clearW.toFloat()
            val rows = st.rowRanges().size.coerceAtLeast(1)
            return (track / rows).coerceAtLeast(minThumb).coerceAtMost(track)
        }
        val track = st.clearH.toFloat()
        val share = (st.clearH / (st.contentH * st.zoom)).toFloat().coerceAtMost(1f)
        return (track * share).coerceAtLeast(minThumb).coerceAtMost(track)
    }

    /** The thumb's 0..1 position for the current view. */
    private fun scrollbarFraction(st: CanvasState): Double =
        if (st.verticalScroll) {
            ((st.scrollY - st.minScrollY()) / (st.maxScrollY() - st.minScrollY())).coerceIn(0.0, 1.0)
        } else {
            val last = (st.rowRanges().size - 1).coerceAtLeast(1)
            st.currentRow.toDouble() / last
        }

    /**
     * Capture and drive a drag that starts on the scrollbar's band (right edge, or the bottom
     * edge in paginated mode). Deltas accumulate into a locally tracked fraction (seeded from
     * the current position) and map back onto the scroll — or snap per row in paginated mode —
     * exactly like the side panel's scrollbar; once a drag is live every event is consumed
     * until the finger lifts so no ink is drawn under the bar.
     */
    private fun trackScrollbar(e: MotionEvent): Boolean {
        val st = state ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scrollbarActive(st)) return false
                val band = SCROLLBAR_TOUCH_DP * resources.displayMetrics.density
                val onBand = if (st.verticalScroll) e.x >= st.viewportW - st.insetRight - band else e.y >= st.viewportH - st.insetBottom - band
                if (!onBand) return false
                scrollbarDragging = true
                scrollbarLastY = if (st.verticalScroll) e.y else e.x
                scrollbarFrac = scrollbarFraction(st)
                requestRender()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (scrollbarDragging) {
                val pos = if (st.verticalScroll) e.y else e.x
                val delta = pos - scrollbarLastY
                scrollbarLastY = pos
                val track = if (st.verticalScroll) st.clearH.toFloat() else st.clearW.toFloat()
                val travel = track - scrollbarThumb(st)
                if (delta != 0f && travel > 0f) {
                    scrollbarFrac = (scrollbarFrac + delta / travel).coerceIn(0.0, 1.0)
                    if (st.verticalScroll) {
                        st.scrollY = st.minScrollY() + scrollbarFrac * (st.maxScrollY() - st.minScrollY())
                        st.clampScroll()
                        onScrollbarScrolled?.invoke()
                    } else {
                        val rows = st.rowRanges()
                        val target = (scrollbarFrac * (rows.size - 1)).toInt().coerceIn(0, rows.lastIndex)
                        if (target != st.currentRow) {
                            st.goToPage(rows[target].first)
                            onScrollbarScrolled?.invoke()
                        }
                    }
                    requestRender()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                if (scrollbarDragging) return true // extra fingers never reach the canvas mid-drag
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrollbarDragging) {
                scrollbarDragging = false
                requestRender()
                return true
            }
        }
        return false
    }

    /** Draw the scrollbar thumb (square, dim; accent while dragged). */
    private fun drawScrollbar(canvas: Canvas, st: CanvasState) {
        if (!scrollbarActive(st)) return
        val d = resources.displayMetrics.density
        val barW = SCROLLBAR_WIDTH_DP * d
        val thumb = scrollbarThumb(st)
        val frac = scrollbarFraction(st).toFloat()
        scrollbarPaint.color = (if (scrollbarDragging) st.palette.accent else st.palette.textDim).toArgb()
        // Inside the clear area, so a floating toolbar never sits on it.
        val right = (st.viewportW - st.insetRight).toFloat()
        val bottom = (st.viewportH - st.insetBottom).toFloat()
        if (st.verticalScroll) {
            val top = st.insetTop.toFloat() + (st.clearH.toFloat() - thumb) * frac
            canvas.drawRect(right - barW, top, right, top + thumb, scrollbarPaint)
        } else {
            val left = st.insetLeft.toFloat() + (st.clearW.toFloat() - thumb) * frac
            canvas.drawRect(left, bottom - barW, left + thumb, bottom, scrollbarPaint)
        }
    }

    /**
     * Watch the touch stream for a clean four-finger tap. Once a 4th finger lands we
     * cancel whatever gesture the interaction layer began (e.g. a pinch) and swallow the
     * rest of the gesture; on a quick, near-stationary release with exactly four fingers
     * we toggle [debugOverlay]. Returns true when the event was consumed here (so the
     * caller must not forward it to the interaction layer).
     */
    private fun trackFourFingerTap(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownMs = e.eventTime
                gestureMaxPointers = 1
                fourFingerActive = false
                fourMoved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gestureMaxPointers = maxOf(gestureMaxPointers, e.pointerCount)
                if (!fourFingerActive && e.pointerCount >= 4) {
                    fourFingerActive = true
                    val (cx, cy) = centroid(e)
                    fourCx = cx; fourCy = cy
                    cancelInteraction(e) // abort the pinch the controller already started
                }
                if (fourFingerActive) return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (fourFingerActive) {
                    val (cx, cy) = centroid(e)
                    if (kotlin.math.hypot((cx - fourCx).toDouble(), (cy - fourCy).toDouble()) > TAP_SLOP) {
                        fourMoved = true
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (fourFingerActive) return true // wait for the last finger to lift
            }
            MotionEvent.ACTION_UP -> {
                if (fourFingerActive) {
                    fourFingerActive = false
                    val quick = e.eventTime - gestureDownMs <= TAP_TIMEOUT_MS
                    if (quick && !fourMoved && gestureMaxPointers == 4) {
                        debugOverlay.toggle()
                        startDebugTick()
                        requestRender()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (fourFingerActive) {
                    fourFingerActive = false
                    return true
                }
            }
        }
        return false
    }

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (i in 0 until e.pointerCount) { sx += e.getX(i); sy += e.getY(i) }
        return sx / e.pointerCount to sy / e.pointerCount
    }

    /** Forward a synthetic CANCEL so the interaction layer abandons its in-flight gesture. */
    private fun cancelInteraction(e: MotionEvent) {
        val cancel = MotionEvent.obtain(e)
        cancel.action = MotionEvent.ACTION_CANCEL
        input?.invoke(cancel)
        cancel.recycle()
    }

    /**
     * Recognize a clean two- or three-finger tap (finger-only, brief, near-stationary) and fire the
     * matching callback. Unlike the four-finger tap this never swallows the gesture mid-flight, so
     * pinch-zoom keeps working; only on a recognized tap do we cancel the (already-ended) pinch and
     * consume the terminal UP. A moving or slow gesture falls through untouched. Reuses [gestureDownMs]
     * and [gestureMaxPointers], which [trackFourFingerTap] (run first) keeps current.
     */
    private fun trackMultiFingerTap(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDownX.clear(); tapDownY.clear()
                tapMoved = false
                tapAllFingers = e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                recordTapDown(e, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.getToolType(e.actionIndex) != MotionEvent.TOOL_TYPE_FINGER) tapAllFingers = false
                recordTapDown(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> if (!tapMoved) {
                for (i in 0 until e.pointerCount) {
                    val dx = e.getX(i) - (tapDownX[e.getPointerId(i)] ?: e.getX(i))
                    val dy = e.getY(i) - (tapDownY[e.getPointerId(i)] ?: e.getY(i))
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > TAP_SLOP) { tapMoved = true; break }
                }
            }
            MotionEvent.ACTION_UP -> {
                val cb = when (gestureMaxPointers) {
                    2 -> onTwoFingerTap
                    3 -> onThreeFingerTap
                    else -> null
                }
                val quick = e.eventTime - gestureDownMs <= TAP_TIMEOUT_MS
                if (cb != null && quick && !tapMoved && tapAllFingers) {
                    cancelInteraction(e)
                    cb()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> tapMoved = true
        }
        return false
    }

    private fun recordTapDown(e: MotionEvent, index: Int) {
        tapDownX[e.getPointerId(index)] = e.getX(index)
        tapDownY[e.getPointerId(index)] = e.getY(index)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean =
        hover?.invoke(event) ?: super.onHoverEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        genericMotion?.invoke(event)
        return super.onGenericMotionEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (cacheExecutor?.isShutdown != false) {
            cacheExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "xnotes-cache").apply { isDaemon = true }
            }
        }
        startDebugTick() // resume the HUD ticker if it was left enabled across a detach/reattach
    }

    override fun onDetachedFromWindow() {
        cacheExecutor?.shutdown()
        mainHandler.removeCallbacks(debugTick)
        mainHandler.removeCallbacks(sharpDebounce)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val st = state ?: return
        st.viewportW = w
        st.viewportH = h
        st.relayout()
        if (!st.didInitialFit && w > 0 && h > 0) {
            st.establishInitialView()
        } else if (w != oldw && w > 0) {
            st.reflowFitWidthForResize() // sidebar opened/closed: re-fit to the new width
        }
        st.clampScroll()
        afterLayout?.invoke()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val st = state ?: return
        canvas.drawColor(st.palette.bg.toArgb())

        val r = AndroidRenderer(canvas)
        val origin = st.origin()
        r.save()
        r.translate(origin.x, origin.y)
        r.scale(st.zoom, st.zoom)

        val visible = st.visibleContentRect()
        val border = Pen(st.palette.paperBorder, 1.0, cosmetic = true)
        // Paginated mode shows only the current row; neighbours never draw.
        val drawable = st.drawablePageRange()

        for (i in st.document.pages.indices) {
            if (i !in drawable) continue
            val pr = st.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = st.document.pages[i]

            r.fillRect(pr, st.paperColor(page))
            if (st.pageBorders) r.strokeRect(pr, border)
            st.backgroundForOrSchedule(page)?.let { blitPageSurface(r, st, page, pr, it.surface) }
            // A live caret session lifts the flow out of the ink cache; paint it
            // immediate-mode here (under the ink, over the background) so every
            // keystroke shows without waiting for a cache rebuild.
            if (st.flowLifted) {
                r.withSave {
                    r.clipRect(pr)
                    r.translate(pr.left, pr.top)
                    st.applyPageTransform(r, page)
                    st.paintFlow?.invoke(page, r, st.displayRectToPage(page, visible.translate(-pr.left, -pr.top)))
                }
            }
            st.cacheForOrSchedule(page)?.let { blitPageSurface(r, st, page, pr, it.surface) }
        }
        r.restore()

        // Past the resolution cap, cover the (soft, capped) page caches with a razor-sharp,
        // full-resolution render of just the viewport. While panning we slide the previous sharp
        // render with the content (so short pans stay sharp) and let the soft cache show only in
        // the strip panning into view; once the view settles we re-render the sharp viewport for
        // the new area. A zoom change drops back to the soft caches until the settle re-render.
        if (st.isPastResolutionCap()) {
            val blit = st.sharpViewportBlit()
            if (blit != null) {
                val dw = blit.base.width * blit.scale
                val dh = blit.base.height * blit.scale
                r.drawRaster(blit.base, Rect(blit.dx, blit.dy, dw, dh))
                // A lifted flow is absent from the sharp ink layer too: draw it live
                // between the sharp base and sharp ink so the stack order holds.
                // Clipped to the blit's own rect: outside it the page loop's live pass
                // already painted the flow, and a second (translucent) chip on top
                // reads as a lighter band while the slid sharp frame settles.
                if (st.flowLifted) {
                    r.withSave {
                        r.clipRect(Rect(blit.dx, blit.dy, dw, dh))
                        r.translate(origin.x, origin.y)
                        r.scale(st.zoom, st.zoom)
                        for (i in st.document.pages.indices) {
                            if (i !in drawable) continue
                            val pr = st.pageRects.getOrNull(i) ?: continue
                            if (!pr.intersects(visible)) continue
                            val page = st.document.pages[i]
                            r.withSave {
                                r.clipRect(pr)
                                r.translate(pr.left, pr.top)
                                st.applyPageTransform(r, page)
                                st.paintFlow?.invoke(page, r, st.displayRectToPage(page, visible.translate(-pr.left, -pr.top)))
                            }
                        }
                    }
                }
                r.drawRaster(blit.ink, Rect(blit.dx, blit.dy, dw, dh))
                mainHandler.removeCallbacks(sharpDebounce)
                // Off the exact rendered view (panned or zoomed): re-render for where we settle.
                val exact = blit.scale == 1.0 && blit.dx == 0.0 && blit.dy == 0.0
                if (!exact) mainHandler.postDelayed(sharpDebounce, SHARP_SETTLE_MS)
            } else {
                mainHandler.removeCallbacks(sharpDebounce)
                mainHandler.postDelayed(sharpDebounce, SHARP_SETTLE_MS)
            }
        } else {
            mainHandler.removeCallbacks(sharpDebounce)
            st.clearSharpViewport()
        }

        // Highlighters composite here, over the finished page (paper + background + ink), so
        // their MULTIPLY blend darkens against everything beneath instead of washing it out —
        // matching the live preview. They're few and drawn at screen resolution (so crisp at
        // any zoom); pen/calligraphy ink stays cached underneath.
        r.withSave {
            r.translate(origin.x, origin.y)
            r.scale(st.zoom, st.zoom)
            for (i in st.document.pages.indices) {
                if (i !in drawable) continue
                val pr = st.pageRects.getOrNull(i) ?: continue
                if (!pr.intersects(visible)) continue
                val page = st.document.pages[i]
                // Page-space visible rect, so off-band highlighters on a tall page skip the composite.
                val visLocal = st.displayRectToPage(page, visible.translate(-pr.left, -pr.top))
                r.withSave {
                    r.clipRect(pr)
                    r.translate(pr.left, pr.top)
                    st.applyPageTransform(r, page)
                    for (item in page.items) {
                        if (item is Stroke && item.isHighlighterInk() && !st.isLiftedItem(item) &&
                            item.bounds().intersects(visLocal)
                        ) {
                            // Blit the pre-rendered opaque ribbon at the ink's alpha and blend,
                            // instead of re-tessellating the ribbon every frame.
                            val hc = st.highlighterCacheFor(item, page)
                            r.drawRasterBlended(hc.surface, hc.cover, item.renderColor.a / 255.0, item.blendMode)
                        }
                    }
                }
            }
        }

        drawOverlay?.invoke(r, canvas)

        // Elastic "pull past the end to add a page" affordance, on top of everything (viewport space).
        if (st.overscrollY > 1.0) {
            drawOverscrollIndicator(canvas, st)
        } else if (!st.verticalScroll && st.flipOffsetX > 1.0 && st.currentRow >= st.rowRanges().size - 1) {
            drawFlipAddPageIndicator(canvas, st)
        }

        drawScrollbar(canvas, st)

        st.prefetchAndPrune()

        // Debug HUD on top, reading the just-pruned cache state (viewport space).
        debugOverlay.sampleFrame(System.nanoTime())
        debugOverlay.draw(r, st)
    }

    /** Fires once the view has been still for [SHARP_SETTLE_MS], rendering the sharp viewport. */
    private val sharpDebounce = Runnable { state?.requestSharpViewport() }

    /** Blit a page-space cache surface into the page's display rect, rotated per the view. The
     *  surface covers the page's whole footprint (margins included), so it blits at [CanvasState.footprint]. */
    private fun blitPageSurface(r: AndroidRenderer, st: CanvasState, page: Page, pr: Rect, surface: com.xnotes.core.pal.RasterSurface) {
        if (st.rotationDeg == 0) {
            r.drawRaster(surface, pr)
            return
        }
        r.withSave {
            r.translate(pr.left, pr.top)
            st.applyPageTransform(r, page)
            r.drawRaster(surface, st.footprint(page))
        }
    }

    /**
     * Draw the elastic add-page badge in the gap the pull opens below the last page: an accent
     * progress ring with a "+" that closes to a full circle once the pull is far enough to release.
     * Everything fades in with the stretch so a small accidental tug shows almost nothing.
     */
    private fun drawOverscrollIndicator(canvas: Canvas, st: CanvasState) {
        val over = st.overscrollY
        if (st.pageRects.isEmpty()) return
        val d = resources.displayMetrics.density
        val progress = (over / InteractionController.OVERSCROLL_TRIGGER).coerceIn(0.0, 1.0).toFloat()
        val radius = (16f * d) * (0.8f + 0.2f * progress)

        val last = st.pageRects.last()
        val anchor = st.contentToViewport(Pt(last.centerX, last.bottom))
        val cx = anchor.x.toFloat().coerceIn(0f, st.viewportW.toFloat())
        // Pin the badge just below the page's bottom edge so it rises into the gap, never over the page.
        val cy = anchor.y.toFloat() + 12f * d + radius
        drawAddPageBadge(canvas, st, cx, cy, radius, progress, over / (InteractionController.OVERSCROLL_TRIGGER * 0.4))
    }

    /**
     * The same badge for the paginated strip, in the gap the edge-pull opens past the last page.
     * Only the last row shows it — an earlier row's pull flips to the next page instead.
     */
    private fun drawFlipAddPageIndicator(canvas: Canvas, st: CanvasState) {
        val pull = st.flipOffsetX
        val rows = st.rowRanges()
        val lastPage = rows.lastOrNull()?.last ?: return
        val rect = st.pageRects.getOrNull(lastPage) ?: return
        val d = resources.displayMetrics.density
        val progress = (pull / InteractionController.FLIP_TRIGGER).coerceIn(0.0, 1.0).toFloat()
        val radius = (16f * d) * (0.8f + 0.2f * progress)

        val anchor = st.contentToViewport(Pt(rect.right, rect.centerY))
        val cx = anchor.x.toFloat() + 12f * d + radius
        val cy = anchor.y.toFloat().coerceIn(radius + 8f * d, st.viewportH.toFloat() - radius - 24f * d)
        drawAddPageBadge(canvas, st, cx, cy, radius, progress, pull / (InteractionController.FLIP_TRIGGER * 0.4))
    }

    /** The shared add-page badge: progress ring, "+", and the pull/release caption under it. */
    private fun drawAddPageBadge(
        canvas: Canvas,
        st: CanvasState,
        cx: Float,
        cy: Float,
        radius: Float,
        progress: Float,
        rawAlpha: Double,
    ) {
        val d = resources.displayMetrics.density
        val alpha = rawAlpha.coerceIn(0.0, 1.0).toFloat()
        val ready = progress >= 1f
        val accent = st.palette.accent.toArgb()
        val dim = st.palette.textDim.toArgb()

        // Faint full track behind the progress.
        overscrollStroke.color = dim
        overscrollStroke.alpha = (45 * alpha).toInt()
        overscrollStroke.strokeWidth = 2.5f * d
        canvas.drawCircle(cx, cy, radius, overscrollStroke)

        // Accent progress ring (closes to a full circle once armed) with a "+", never filled solid.
        overscrollStroke.color = accent
        overscrollStroke.alpha = (255 * alpha).toInt()
        overscrollStroke.strokeWidth = 2.8f * d
        overscrollArc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(overscrollArc, -90f, 360f * progress, false, overscrollStroke)
        drawPlus(canvas, cx, cy, radius * 0.46f, accent, (255 * alpha).toInt(), 2.4f * d)

        overscrollText.color = if (ready) accent else dim
        overscrollText.alpha = (235 * alpha).toInt()
        overscrollText.textSize = 11f * d
        canvas.drawText(if (ready) releaseLabel else pullLabel, cx, cy + radius + 16f * d, overscrollText)
    }

    /** A centred "+" glyph (two rounded strokes) for the overscroll badge. */
    private fun drawPlus(canvas: Canvas, cx: Float, cy: Float, half: Float, color: Int, a: Int, w: Float) {
        overscrollStroke.color = color
        overscrollStroke.alpha = a
        overscrollStroke.strokeWidth = w
        canvas.drawLine(cx - half, cy, cx + half, cy, overscrollStroke)
        canvas.drawLine(cx, cy - half, cx, cy + half, overscrollStroke)
    }

    /** Request a vsync-aligned repaint (rides the display refresh while drawing). */
    fun requestRender() = postInvalidateOnAnimation()

    /**
     * Repaint, and run [action] once that frame has been handed to the compositor.
     *
     * The front buffer's handover needs it: the pad may only come down after the canvas has
     * actually put the committed stroke on screen, and a plain [requestRender] says nothing about
     * when that happened. A timer backs the callback up rather than replacing it, because a
     * handover that never finishes leaves the pad holding the last stroke forever.
     */
    fun publishThen(action: () -> Unit) {
        var done = false
        val once = Runnable {
            if (done) return@Runnable
            done = true
            action()
        }
        // A detached view has no live observer to register with, and the timer covers it.
        if (android.os.Build.VERSION.SDK_INT >= 29 && isAttachedToWindow) {
            runCatching { viewTreeObserver.registerFrameCommitCallback(once) }
        }
        mainHandler.postDelayed(once, FRAME_COMMIT_TIMEOUT_MS)
        requestRender()
    }

    /**
     * Take the pen's samples as the driver produces them rather than batched to the frame the view
     * tree is about to draw.
     *
     * Only while the front buffer has the stroke. There the canvas is not drawing the ink at all,
     * so a batch is pure delay; on the ordinary path it is doing a job, since one frame's samples
     * collapse into one repaint and a stroke re-filled per sample gets slower as it grows.
     */
    fun setUnbufferedStylus(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        requestUnbufferedDispatch(
            if (on) android.view.InputDevice.SOURCE_STYLUS else android.view.InputDevice.SOURCE_CLASS_NONE,
        )
    }

    companion object {
        /** Max gesture duration (ms) still counted as a tap. */
        private const val TAP_TIMEOUT_MS = 500L

        /** Max centroid drift (viewport px) the four fingers may wander and still tap. */
        private const val TAP_SLOP = 40.0

        /** How long the view must be still before the sharp viewport is rendered (ms). */
        private const val SHARP_SETTLE_MS = 90L

        /** Idle repaint interval (ms) while the debug HUD is visible, so its FPS falls to 0. */
        private const val DEBUG_TICK_MS = 250L

        /** Drawn scrollbar thumb width (dp), matching the side panel's. */
        private const val SCROLLBAR_WIDTH_DP = 8f

        /** Touch band width (dp) that grabs the scrollbar — wider than the drawn bar for fingers. */
        private const val SCROLLBAR_TOUCH_DP = 16f

        /** Minimum thumb length (dp), matching the side panel's. */
        private const val SCROLLBAR_MIN_THUMB_DP = 28f

        /** How long [publishThen] waits for a frame before running the action anyway (ms). */
        private const val FRAME_COMMIT_TIMEOUT_MS = 120L
    }
}
