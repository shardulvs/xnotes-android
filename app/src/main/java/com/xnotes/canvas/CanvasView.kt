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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.platform.AndroidRenderer

/**
 * The on-screen canvas. Draws the document in immediate mode each frame
 * (spec 05 §6): window background, then visible pages (paper + hairline border +
 * cached background layer + cached ink + page label). Selection overlay, the live
 * stroke and the eraser cursor are layered on top by later interaction code.
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

    /** Invoked after the viewport is (re)laid out and the initial fit applied. */
    var afterLayout: (() -> Unit)? = null

    /** Hover handler (stylus/mouse hover) for the eraser cursor. */
    var hover: ((MotionEvent) -> Boolean)? = null

    /** Transparent debug HUD (frame rate / cache / heap), toggled by a four-finger tap. */
    val debugOverlay = DebugOverlay()

    // Reused paints for the elastic "pull to add page" badge, so onDraw allocates nothing.
    private val overscrollStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val overscrollFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val overscrollText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val overscrollArc = RectF()

    /**
     * Low-rate repaint while the HUD is visible. The canvas only repaints on interaction,
     * so without this the frame-rate line would freeze at its last value when idle instead
     * of falling to 0. Runs only while [DebugOverlay.enabled]; costs nothing otherwise.
     */
    private val debugTick = object : Runnable {
        override fun run() {
            if (!debugOverlay.enabled) return
            invalidate()
            mainHandler.postDelayed(this, DEBUG_TICK_MS)
        }
    }

    // --- four-finger-tap recognition (toggles the debug HUD) ---
    private var gestureDownMs = 0L
    private var gestureMaxPointers = 0
    private var fourFingerActive = false
    private var fourCx = 0f
    private var fourCy = 0f
    private var fourMoved = false

    init {
        isFocusableInTouchMode = true
        setWillNotDraw(false)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (trackFourFingerTap(event)) return true
        return input?.invoke(event) ?: super.onTouchEvent(event)
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
                        mainHandler.removeCallbacks(debugTick)
                        if (debugOverlay.enabled) mainHandler.postDelayed(debugTick, DEBUG_TICK_MS)
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

    override fun onHoverEvent(event: MotionEvent): Boolean =
        hover?.invoke(event) ?: super.onHoverEvent(event)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (cacheExecutor?.isShutdown != false) {
            cacheExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "xnotes-cache").apply { isDaemon = true }
            }
        }
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
        val visiblePages = HashSet<Page>()

        for (i in st.document.pages.indices) {
            val pr = st.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = st.document.pages[i]
            visiblePages.add(page)

            r.fillRect(pr, st.paperColor(page))
            r.strokeRect(pr, border)
            st.backgroundForOrSchedule(page)?.let { r.drawRaster(it.surface, pr) }
            st.cacheForOrSchedule(page)?.let { r.drawRaster(it.surface, pr) }
            drawPageLabel(r, st, i, pr)
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
                val pr = st.pageRects.getOrNull(i) ?: continue
                if (!pr.intersects(visible)) continue
                val page = st.document.pages[i]
                r.withSave {
                    r.clipRect(pr)
                    r.translate(pr.left, pr.top)
                    for (item in page.items) {
                        if (item.isHighlighterInk() && !st.isLiftedItem(item)) item.paint(r)
                    }
                }
            }
        }

        drawOverlay?.invoke(r, canvas)

        // Elastic "pull past the end to add a page" affordance, on top of everything (viewport space).
        if (st.overscrollY > 1.0) drawOverscrollIndicator(canvas, st)

        st.dropCachesExcept(visiblePages)

        // Debug HUD on top, reading the just-pruned cache state (viewport space).
        debugOverlay.sampleFrame(System.nanoTime())
        debugOverlay.draw(r, st)
    }

    /** Fires once the view has been still for [SHARP_SETTLE_MS], rendering the sharp viewport. */
    private val sharpDebounce = Runnable { state?.requestSharpViewport() }

    /**
     * Draw the elastic add-page badge in the gap the pull opens below the last page: an accent
     * progress ring with a "+" that fills solid (with a white "+") once the pull is far enough to
     * release. Everything fades in with the stretch so a small accidental tug shows almost nothing.
     */
    private fun drawOverscrollIndicator(canvas: Canvas, st: CanvasState) {
        val over = st.overscrollY
        if (st.pageRects.isEmpty()) return
        val d = resources.displayMetrics.density
        val progress = (over / InteractionController.OVERSCROLL_TRIGGER).coerceIn(0.0, 1.0).toFloat()
        val alpha = (over / (InteractionController.OVERSCROLL_TRIGGER * 0.4)).coerceIn(0.0, 1.0).toFloat()
        val ready = progress >= 1f

        val last = st.pageRects.last()
        val anchor = st.contentToViewport(Pt(last.centerX, last.bottom))
        val cx = anchor.x.toFloat().coerceIn(0f, st.viewportW.toFloat())
        val cy = ((anchor.y + st.viewportH) / 2.0).toFloat().coerceIn(0f, st.viewportH - 30f * d)
        val radius = (16f * d) * (0.8f + 0.2f * progress)
        val accent = st.palette.accent.toArgb()
        val dim = st.palette.textDim.toArgb()

        // Faint full track behind the progress.
        overscrollStroke.color = dim
        overscrollStroke.alpha = (45 * alpha).toInt()
        overscrollStroke.strokeWidth = 2.5f * d
        canvas.drawCircle(cx, cy, radius, overscrollStroke)

        if (ready) {
            overscrollFill.color = accent
            overscrollFill.alpha = (255 * alpha).toInt()
            canvas.drawCircle(cx, cy, radius, overscrollFill)
            drawPlus(canvas, cx, cy, radius * 0.46f, 0xFFFFFFFF.toInt(), (255 * alpha).toInt(), 2.6f * d)
        } else {
            overscrollStroke.color = accent
            overscrollStroke.alpha = (255 * alpha).toInt()
            overscrollStroke.strokeWidth = 2.8f * d
            overscrollArc.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(overscrollArc, -90f, 360f * progress, false, overscrollStroke)
            drawPlus(canvas, cx, cy, radius * 0.46f, accent, (255 * alpha).toInt(), 2.4f * d)
        }

        overscrollText.color = if (ready) accent else dim
        overscrollText.alpha = (235 * alpha).toInt()
        overscrollText.textSize = 11f * d
        canvas.drawText(if (ready) "Release to add page" else "Pull to add page", cx, cy + radius + 16f * d, overscrollText)
    }

    /** A centred "+" glyph (two rounded strokes) for the overscroll badge. */
    private fun drawPlus(canvas: Canvas, cx: Float, cy: Float, half: Float, color: Int, a: Int, w: Float) {
        overscrollStroke.color = color
        overscrollStroke.alpha = a
        overscrollStroke.strokeWidth = w
        canvas.drawLine(cx - half, cy, cx + half, cy, overscrollStroke)
        canvas.drawLine(cx, cy - half, cx, cy + half, overscrollStroke)
    }

    private fun drawPageLabel(r: AndroidRenderer, st: CanvasState, index: Int, pr: Rect) {
        val label = "%02d".format(index + 1)
        r.drawText(label, Rect(pr.left, pr.top - 26.0, 140.0, 24.0), FontSpec(9.0), st.palette.textDim)
    }

    /** Request a vsync-aligned repaint (rides the display refresh while drawing). */
    fun requestRender() = postInvalidateOnAnimation()

    companion object {
        /** Max gesture duration (ms) still counted as a tap. */
        private const val TAP_TIMEOUT_MS = 500L

        /** Max centroid drift (viewport px) the four fingers may wander and still tap. */
        private const val TAP_SLOP = 40.0

        /** How long the view must be still before the sharp viewport is rendered (ms). */
        private const val SHARP_SETTLE_MS = 90L

        /** Idle repaint interval (ms) while the debug HUD is visible, so its FPS falls to 0. */
        private const val DEBUG_TICK_MS = 250L
    }
}
