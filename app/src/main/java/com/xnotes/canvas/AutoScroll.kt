package com.xnotes.canvas

import android.view.Choreographer
import kotlin.math.floor

/**
 * The View menu's autoscroll: drifts a vertical-scroll note down at [mmPerSec] paper millimetres a
 * second, so zoom never changes the reading pace. It holds still while [held], and moves in whole
 * pixels with the remainder carried, so a slow crawl repaints only when the page actually moves.
 */
class AutoScroll(
    private val state: CanvasState,
    private val held: () -> Boolean,
    private val onMoved: () -> Unit,
    private val onEnded: () -> Unit,
) {
    private val choreographer by lazy { Choreographer.getInstance() }
    private val frame = Choreographer.FrameCallback { step(it) }
    private var lastNanos = 0L
    private var carry = 0.0

    var mmPerSec = 2.0
    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        lastNanos = 0L
        carry = 0.0
        choreographer.postFrameCallback(frame)
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(frame)
    }

    private fun step(nanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 0.0 else ((nanos - lastNanos) / 1e9).coerceIn(0.0, MAX_STEP_S)
        lastNanos = nanos
        if (held()) {
            carry = 0.0
        } else {
            carry += pxPerSec() * dt
            val px = floor(carry)
            if (px >= 1.0) {
                carry -= px
                val before = state.scrollY
                state.scrollBy(0.0, px)
                if (state.scrollY <= before) {
                    running = false
                    onEnded()
                    return
                }
                onMoved()
            }
        }
        choreographer.postFrameCallback(frame)
    }

    /** Seconds until the view reaches the end of the note at the current speed and zoom. */
    fun secondsToEnd(): Double = (state.maxScrollY() - state.scrollY).coerceAtLeast(0.0) / pxPerSec()

    private fun pxPerSec(): Double = mmPerSec * state.document.dpi / MM_PER_INCH * state.zoom

    private companion object {
        const val MM_PER_INCH = 25.4
        const val MAX_STEP_S = 0.05
    }
}
