package com.xnotes.canvas

import android.os.Handler
import android.os.Looper
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Renderer

/**
 * The selection handles text selections share (flow text, PDF text): Android's teardrops hung below
 * each end's caret, the hit test that picks one up, and the autoscroll while a drag holds near the
 * top or bottom edge. Points are in content space unless named viewport.
 */
class TextHandles(private val state: CanvasState) {
    enum class Handle { START, END }

    /** Made on the first autoscroll, so a drag that never nears an edge touches no looper. */
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var velocity = 0.0
    private var dragAt: Pt? = null
    private var onStep: (Pt) -> Unit = {}
    private val step = object : Runnable {
        override fun run() {
            val at = dragAt ?: return
            if (velocity == 0.0) return
            state.scrollBy(0.0, velocity)
            onStep(at)
            handler.postDelayed(this, AUTOSCROLL_TICK_MS)
        }
    }

    private val radius: Double get() = RADIUS_DP * state.devicePxPerDp / state.zoom

    /**
     * The centre of the handle whose tip touches [tip], a caret's bottom: hung screen-down, away
     * from the selection (down-left for the start handle, down-right for the end handle).
     */
    fun center(tip: Pt, isStart: Boolean): Pt = Pt(if (isStart) tip.x - radius else tip.x + radius, tip.y + radius)

    /** The classic Android teardrop: a circle whose squared-off quadrant puts a sharp tip at the caret's bottom. */
    fun draw(r: Renderer, center: Pt, isStart: Boolean, color: Rgba) {
        val radius = radius
        val tipX = if (isStart) center.x + radius else center.x - radius
        r.fillCircle(center, radius, color)
        r.fillRect(Rect(minOf(tipX, center.x), center.y - radius, radius, radius), color)
    }

    /** The handle a press at [viewport] grabs, preferring the nearer of the two; a null centre is a handle not shown. */
    fun grab(viewport: Pt, start: Pt?, end: Pt?): Handle? {
        val hitRadius = HIT_DP * state.devicePxPerDp
        val dStart = start?.let { viewport.distanceTo(state.contentToViewport(it)) } ?: Double.MAX_VALUE
        val dEnd = end?.let { viewport.distanceTo(state.contentToViewport(it)) } ?: Double.MAX_VALUE
        return when {
            dStart <= dEnd && dStart <= hitRadius -> Handle.START
            dEnd <= hitRadius -> Handle.END
            else -> null
        }
    }

    /**
     * Scrolls while a drag at [viewport] sits in the top or bottom edge zone, speed scaling with
     * depth, calling [onStep] with the drag's latest point after each step.
     */
    fun autoscroll(viewport: Pt, onStep: (Pt) -> Unit) {
        dragAt = viewport
        this.onStep = onStep
        val zone = AUTOSCROLL_ZONE_DP * state.devicePxPerDp
        val maxV = AUTOSCROLL_MAX_DP * state.devicePxPerDp
        val top = state.insetTop
        val bottom = state.viewportH - state.insetBottom
        val vel = when {
            viewport.y < top + zone -> -maxV * ((top + zone - viewport.y) / zone).coerceAtMost(1.0)
            viewport.y > bottom - zone -> maxV * ((viewport.y - (bottom - zone)) / zone).coerceAtMost(1.0)
            else -> 0.0
        }
        val wasStill = velocity == 0.0
        velocity = vel
        if (vel == 0.0) {
            if (!wasStill) handler.removeCallbacks(step)
        } else if (wasStill) {
            handler.removeCallbacks(step)
            handler.post(step)
        }
    }

    fun stopAutoscroll() {
        val moving = velocity != 0.0
        velocity = 0.0
        dragAt = null
        if (moving) handler.removeCallbacks(step)
    }

    companion object {
        const val RADIUS_DP = 8.0
        const val HIT_DP = 26.0
        const val AUTOSCROLL_ZONE_DP = 56.0
        const val AUTOSCROLL_MAX_DP = 14.0
        const val AUTOSCROLL_TICK_MS = 16L
    }
}
