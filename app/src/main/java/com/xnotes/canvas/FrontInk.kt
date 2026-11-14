package com.xnotes.canvas

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.infinite.PixelRect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.gl.GlWetPad
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Wet ink on the front buffer, for a paged note.
 *
 * The canvas here paints into the window with Skia, so the ink under the pen cannot be quicker than
 * the view tree's next frame. [GlWetPad] is a surface of its own, switched to `EGL_SINGLE_BUFFER`,
 * where a `glFlush` puts pixels on the glass without a queue or a refresh boundary in between. This
 * is the piece that decides what goes there, feeds it, and hands the stroke back at pen up.
 *
 * ### The view, as one scroll
 *
 * A page reaches the screen as `(p + pageRect.topLeft + insets) * zoom + origin`, which is a scroll
 * and a zoom and nothing else while the view is upright, so the pad needs no notion of pages at
 * all: the whole page transform bakes down into the scroll it is given at pen down. A *rotated*
 * view is the one case that does not fit, and it keeps the ordinary path.
 *
 * ### The handover
 *
 * At pen up the committed stroke is held out of the ink cache while the canvas as it stands is
 * captured and drawn *under* the ink already on the pad. The pad then holds an opaque copy of the
 * composite the screen was showing, the canvas underneath can take the stroke unseen, and taking
 * the pad down is a no-op on any refresh. Without it the two layers have to be raced, and one
 * refresh either way is a blink or a doubled antialiased edge.
 *
 * ### A hand that comes back down first
 *
 * The handover takes a capture and a canvas frame, which is tens of milliseconds, and handwriting
 * is quicker than that. A stroke that starts inside one *joins* the pad instead of taking it:
 * nothing is wiped, nothing is handed over, and the strokes accumulate until the hand pauses long
 * enough for one handover to cover all of them. Joining needs the new stroke to be painted through
 * exactly the view the pad was given, so a page crossing, a scroll or a zoom between the two ends
 * the run. There the pad is wiped as before, but only once the canvas has published what it was
 * showing; until then the new stroke rides the ordinary path, as it does at the start of any stroke.
 */
class FrontInk(
    private val state: CanvasState,
    private val view: CanvasView,
    val pad: GlWetPad,
) {

    private val handler = Handler(Looper.getMainLooper())

    /** The stroke whose runs are already on the pad, by identity. */
    private var owner: Stroke? = null

    /** Ribbon points of [owner] already meshed, and the arc those points spent. */
    private var meshed = 0
    private var arc = 0.0

    /** Points a run holds; a front-buffered stroke uses a much shorter one (see [decide]). */
    private var runPoints = WET_RUN_POINTS

    /** The tail as it was last meshed, which a stroke joining this one has to settle first. */
    private var tailPart: MeshPart? = null

    /** [tailPart] of the stroke just committed, waiting for a joiner or for the handover. */
    private var handoverTail: MeshPart? = null

    /** Whether this stroke's route has been chosen. */
    private var decided = false

    /** Whether the pad is painting the stroke under the pen. */
    var live = false
        private set

    /** An item whose pixels are still the pad's, and the page whose cache takes it, if any. */
    private class Held(val item: CanvasItem, val page: Page?)

    /**
     * The committed items the pad is still showing, kept out of the ink cache until it lets go.
     *
     * The document and the undo stack take each one immediately; only the pixels wait, so nothing
     * about the edit is delayed by the handover. More than one when strokes joined each other, and
     * replaced rather than mutated because the cache threads read it.
     */
    @Volatile
    private var holds: List<Held> = emptyList()

    /** Whether the pad, not the canvas, is showing [item], so the cache has to leave it out. */
    fun holding(item: CanvasItem): Boolean {
        val list = holds
        for (i in list.indices) if (list[i].item === item) return true
        return false
    }

    /** Bumped by anything that outdates a handover in flight, so a stale capture cannot land. */
    private var handoffGen = 0

    /**
     * Whether ink given back to the canvas is still only in its cache and not yet on the glass.
     *
     * Being held is not the same as being shown. A settled stroke is the canvas's, but the canvas
     * paints on its own frame, and until that frame is out the pad's pixels are still the only copy
     * of it anyone can see. A wipe inside that window is the blink this whole file exists to avoid.
     */
    private var awaitingPublish = false

    /** What the front buffer is doing, for the debug HUD, or null when there is no pad. */
    val hud: String?
        get() {
            if (!pad.ready) return null
            val waiting = holds.size
            return if (waiting == 0) pad.hud else "${pad.hud} h$waiting"
        }

    // --- the stroke under the pen ---

    /**
     * Publish the stroke under the pen, in two pieces where the pen allows it.
     *
     * The run that has stopped moving is uploaded once and never again; only the few points still
     * in play are rebuilt each present. The two overlap by a point so the quad bridging them
     * belongs to the later one and no gap can open on the join.
     */
    fun wet(stroke: Stroke?, pageIndex: Int?) {
        if (stroke == null || pageIndex == null) return abandon()
        val ribbon = stroke.wetRibbon
        // Ink whose runs cannot simply be laid over each other keeps the ordinary path, which
        // composites the whole stroke every frame and is what the wet cache is for.
        if (ribbon == null || !stroke.wetCacheable) return abandon()
        if (owner !== stroke) {
            abandon()
            owner = stroke
        }
        if (!decided) decide(stroke, pageIndex)
        if (!live) {
            view.setUnbufferedStylus(false)
            return
        }

        val settled = ribbon.settledCount
        if (settled - meshed >= runPoints) {
            val from = (meshed - 1).coerceAtLeast(0)
            ItemMesher.meshRun(stroke, ribbon, from, settled - from, arc)?.let { pad.appendRun(listOf(it)) }
            for (k in from + 1 until settled) {
                arc += hypot(ribbon.cx(k) - ribbon.cx(k - 1), ribbon.cy(k) - ribbon.cy(k - 1))
            }
            meshed = settled
        }
        val tailFrom = (meshed - 1).coerceAtLeast(0)
        val tail = ItemMesher.meshRun(stroke, ribbon, tailFrom, ribbon.pointCount - tailFrom, arc)
        tailPart = tail
        pad.setTail(if (tail == null) emptyList() else listOf(tail))
    }

    /**
     * The pad's pixels have gone, under the stroke being drawn or under ink it was still holding.
     *
     * There is nothing left to hand over and nothing left to wait for, so the stroke goes back on
     * the ordinary path and the canvas takes everything the pad was showing at once.
     */
    fun surfaceLost() {
        abandon()
        settle()
    }

    /** Give the pad back with nothing to hand over: a stroke abandoned, snapped to a shape, or gone. */
    fun abandon() {
        owner = null
        meshed = 0
        arc = 0.0
        decided = false
        runPoints = WET_RUN_POINTS
        tailPart = null
        view.setUnbufferedStylus(false)
        // Called on the way into every stroke as well, where the pad is not live and a tail may be
        // waiting for this one to join it. Only a stroke actually being dropped goes further.
        if (!live) return
        live = false
        // Strokes that joined this one are still the pad's, and the pad is about to be wiped.
        settle()
        pad.endStroke()
    }

    /**
     * Whether this stroke can go on the front buffer, decided from what it meshes to rather than
     * from the tool.
     *
     * The pad has no copy of what is under it, so anything that composites against the page cannot
     * live there. Plain triangles can, and that is what a pen and a pencil are.
     */
    private fun decide(stroke: Stroke, pageIndex: Int) {
        decided = true
        // Turned off for this device or non-direct physical display scanout, so there is no pad to consult.
        if (!pad.frontBuffering || !pad.isPhysicalScanoutDirect) return
        if (ItemMesher.passFor(stroke) != InkPass.OPAQUE) return
        // A rotated view is a rotation, and the ink shader has room for a scroll and a zoom.
        if (state.rotationDeg != 0) return
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        val rect = state.pageRects.getOrNull(pageIndex) ?: return
        val zoom = state.zoom
        if (zoom <= 0.0 || !zoom.isFinite()) return
        val origin = state.origin()
        val insets = state.insets(page)
        val scrollX = -(rect.left + insets.left + origin.x / zoom)
        val scrollY = -(rect.top + insets.top + origin.y / zoom)
        val clip = paperClip(rect)
        if (join(scrollX, scrollY, zoom, clip)) return
        // Nothing joined, so the pad has to be taken over, and anything it was showing has to be on
        // the canvas first. Settling is not that: the canvas has the ink, but the frame carrying it
        // may still be out, and a handover a moment ago is exactly when it is, since giving a long
        // stroke to the cache is tens of milliseconds. Only then may the pad be taken, and taking it
        // is itself a wait: see [takePad].
        val settled = settle()
        if (settled || awaitingPublish) {
            val gen = handoffGen
            return view.publishThen {
                if (gen == handoffGen) takePad(stroke, pageIndex, scrollX, scrollY, zoom, clip)
            }
        }
        takePad(stroke, pageIndex, scrollX, scrollY, zoom, clip)
    }

    /**
     * Lay this stroke over the one the pad is still holding, on the same view.
     *
     * The pad keeps every pixel it has; only the tail changes hands, and it has to be settled into
     * a run first because the joining stroke's own tail replaces it. Nothing is captured, nothing is
     * wiped, and one handover at the end of the run covers every stroke in it.
     */
    private fun join(scrollX: Double, scrollY: Double, zoom: Double, clip: PixelRect): Boolean {
        if (!pad.isPhysicalScanoutDirect) return false
        if (holds.isEmpty() || !roomToJoin()) return false
        if (!pad.extendStroke(scrollX, scrollY, zoom, clip)) return false
        // The capture the last stroke started is of a box this one is about to grow past.
        handoffGen++
        handoverTail?.let { pad.appendRun(listOf(it)) }
        handoverTail = null
        pad.setTail(emptyList())
        live = true
        runPoints = FRONT_RUN_POINTS
        view.setUnbufferedStylus(true)
        return true
    }

    /**
     * Whether the pad may grow any further.
     *
     * The handover ends with an opaque copy of the canvas under the whole run, and the canvas is
     * frozen behind it for a few refreshes. A sliver of it is invisible; most of the screen is not.
     */
    private fun roomToJoin(): Boolean {
        val box = pad.strokeBox() ?: return true
        val seen = view.width.toLong() * view.height
        return seen <= 0L || box.width().toLong() * box.height() * 2 <= seen
    }

    /**
     * Take the pad, once it is down.
     *
     * Never by clearing it where it stands. A wipe of a front buffer is on the glass at the next
     * scanout whatever the canvas has queued, and a canvas frame that has been *committed* is only
     * queued: it is latched a vsync later. So a wipe timed against a commit still drops whatever the
     * pad was showing for the refresh in between, which is the blink. [GlWetPad.standDown] hides the
     * layer instead, by a transaction, which is latched like a buffer and so lands in the same
     * composite as the canvas's frame. Only then are the pixels this stroke's to take.
     *
     * A pad that is already down hands the surface over on the spot, which is every stroke that
     * starts after any sort of pause. The rest draw through the canvas for a refresh or two first,
     * as every stroke does before it is decided.
     */
    private fun takePad(
        stroke: Stroke,
        pageIndex: Int,
        scrollX: Double,
        scrollY: Double,
        zoom: Double,
        clip: PixelRect,
    ) {
        // Already down, which is every stroke that starts after any sort of pause: take it here so
        // the caller's own present is the first one, rather than re-entering it.
        if (!pad.showing) {
            start(scrollX, scrollY, zoom, clip)
            return
        }
        val gen = handoffGen
        pad.standDown {
            // A moved generation means another stroke has taken the pad and is answerable for it.
            if (gen != handoffGen) return@standDown
            if (owner === stroke && start(scrollX, scrollY, zoom, clip)) {
                // Straight into a present, so the pad has the stroke before the canvas drops it.
                wet(stroke, pageIndex)
                view.requestRender()
            } else {
                // Nobody is taking it, so let it clear itself and let the compositor rest.
                pad.release()
            }
        }
    }

    private fun start(scrollX: Double, scrollY: Double, zoom: Double, clip: PixelRect): Boolean {
        live = pad.beginStroke(scrollX, scrollY, zoom, SAMPLES, clip)
        if (!live) return false
        // A shorter run on the front buffer, because there the tail is what a present has to clear
        // and rebuild, and its extent is the size of everything that present does.
        runPoints = FRONT_RUN_POINTS
        view.setUnbufferedStylus(true)
        return true
    }

    /** The paper's own pixels, so ink running off the page is cut at the edge as the canvas cuts it. */
    private fun paperClip(rect: Rect): PixelRect {
        val topLeft = state.contentToViewport(Pt(rect.left, rect.top))
        val bottomRight = state.contentToViewport(Pt(rect.right, rect.bottom))
        return PixelRect(
            floor(topLeft.x).toInt(), floor(topLeft.y).toInt(),
            ceil(bottomRight.x).toInt(), ceil(bottomRight.y).toInt(),
        )
    }

    // --- the handover ---

    /**
     * Take the just-committed [item] if the pad is showing it, so the caller leaves it out of the
     * ink cache. Returns false when the stroke was never on the front buffer and the caller should
     * file it as it always has. The pad keeps it until the handover, or until a stroke that joins
     * this one has been handed over with it.
     */
    fun hold(item: CanvasItem, page: Page): Boolean = take(Held(item, page))

    /** [hold] for disappearing ink, which the caller's overlay paints once the pad lets go. */
    fun holdFading(stroke: Stroke): Boolean = take(Held(stroke, null))

    private fun take(held: Held): Boolean {
        if (!live) return false
        abandonToHold()
        // A capture in flight was started for a run this stroke has since grown.
        handoffGen++
        holds = holds + held
        capture()
        return true
    }

    /** Stop drawing but keep the pixels: they are the only copy of the stroke until the canvas has it. */
    private fun abandonToHold() {
        owner = null
        meshed = 0
        arc = 0.0
        decided = false
        runPoints = WET_RUN_POINTS
        live = false
        // The tail was never settled into a run, so a stroke that joins this one has to do it.
        handoverTail = tailPart
        tailPart = null
        pad.freeze()
        view.setUnbufferedStylus(false)
    }

    /**
     * Take the canvas as it stands, put it behind the ink on the pad, and only then hand over.
     *
     * Every failure path ends the same way, because a handover that is merely timed is far better
     * than a stroke that never reaches the canvas.
     */
    private fun capture() {
        val box = pad.strokeBox() ?: return finish()
        val window = window() ?: return finish()
        val src = inWindow(window, box) ?: return finish()
        val shot = try {
            Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return finish()
        }
        val gen = handoffGen
        // However the capture goes, the stroke reaches the canvas: late is a blink, never is a lost
        // stroke. One Runnable, held, because a fresh method reference cannot be cancelled.
        val timeout = Runnable { if (gen == handoffGen) finish() }
        handler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        try {
            PixelCopy.request(window, src, shot, { result ->
                handler.removeCallbacks(timeout)
                if (gen != handoffGen) return@request
                if (result != PixelCopy.SUCCESS) return@request finish()
                pad.coverWith(shot, box) { if (gen == handoffGen) finish() }
            }, handler)
        } catch (e: IllegalArgumentException) {
            handler.removeCallbacks(timeout)
            finish()
        }
    }

    /** Let the held items reach the canvas, and take the pad down once that frame is out. */
    private fun finish() {
        if (!settle()) return
        // The wait is a frame long, which is long enough for a new stroke to have taken the pad.
        // Its ink would then be the only copy on screen, and this would wipe it.
        val gen = handoffGen
        view.publishThen { if (gen == handoffGen) pad.release() }
    }

    /**
     * Give the held items back to the canvas, leaving the pad alone, and outdate whatever capture
     * was still coming for them.
     */
    private fun settle(): Boolean {
        handoffGen++
        val items = holds
        if (items.isEmpty()) return false
        holds = emptyList()
        handoverTail = null
        // One erased while it was held (a scribble over ink still on the pad) must not come back.
        for (h in items) h.page?.let { page -> if (page.items.any { it === h.item }) state.appendToCache(page, h.item) }
        // Every settle tracks its own publication, whichever path settled: what the caller does
        // next is not what says when these pixels reached the glass.
        awaitingPublish = true
        view.publishThen { awaitingPublish = false }
        return true
    }

    /** The window the canvas draws into, which is what a capture of what is under the ink comes from. */
    private fun window(): Window? {
        var context: android.content.Context? = view.context
        while (context is android.content.ContextWrapper) {
            if (context is android.app.Activity) return context.window
            context = context.baseContext
        }
        return null
    }

    /**
     * [box], in the canvas's pixels, as window pixels inside the window's own bounds.
     *
     * The pad is a layer above the window, so a copy of the window is the canvas *without* the ink
     * that is on the pad, which is exactly what has to go behind it.
     */
    private fun inWindow(window: Window, box: AndroidRect): AndroidRect? {
        val decor = window.peekDecorView() ?: return null
        val at = IntArray(2)
        view.getLocationInWindow(at)
        val src = AndroidRect(box.left + at[0], box.top + at[1], box.right + at[0], box.bottom + at[1])
        // Whole or not at all: a capture cut down to the window would be stretched back over the
        // box it was taken from, which is worse than not covering at all.
        if (src.isEmpty || src.left < 0 || src.top < 0) return null
        if (src.right > decor.width || src.bottom > decor.height) return null
        return src
    }

    private companion object {
        /** Points a run holds on the ordinary path, matching the wet cache's own bake size. */
        const val WET_RUN_POINTS = 96

        const val FRONT_RUN_POINTS = 8

        /** Samples the pad antialiases live ink with; the canvas itself draws through Skia. */
        const val SAMPLES = 4

        /** How long the handover waits for the capture before giving the stroke back anyway (ms). */
        const val CAPTURE_TIMEOUT_MS = 120L
    }
}
