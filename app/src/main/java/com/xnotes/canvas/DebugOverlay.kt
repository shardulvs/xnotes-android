package com.xnotes.canvas

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec
import com.xnotes.platform.AndroidRenderer
import com.xnotes.platform.AndroidText

/**
 * A translucent, non-interactive debug HUD pinned to the top-right of the canvas,
 * toggled by a four-finger tap (detected in [CanvasView]). It reports the live frame
 * rate, page-cache occupancy and Java heap use.
 *
 * Frame timing is sampled in [CanvasView.onDraw], so the rate reflects real repaints
 * and reads stale while the canvas sits idle (the canvas only repaints on interaction).
 * The HUD is drawn as plain pixels on top of the frame and never reads input, so it
 * cannot interfere with stylus/finger drawing underneath it.
 */
class DebugOverlay {
    var enabled = false
        private set

    fun toggle() {
        enabled = !enabled
    }

    // Frame timing, sampled once per painted frame (smoothed; see [sampleFrame]).
    private var lastFrameNs = 0L
    private var fps = 0.0
    private var frameMs = 0.0

    /**
     * Record one painted frame. A gap longer than [IDLE_GAP_NS] means the canvas stopped
     * repainting (the HUD's idle ticker produced this frame), so the rate reads 0 — the EMA
     * then re-seeds cleanly from the next active frame.
     */
    fun sampleFrame(nowNs: Long) {
        val last = lastFrameNs
        lastFrameNs = nowNs
        if (last == 0L) return
        val dtNs = nowNs - last
        if (dtNs <= 0L) return
        if (dtNs > IDLE_GAP_NS) { // idle: no real repaints since the last frame
            fps = 0.0
            frameMs = 0.0
            return
        }
        val instFps = 1_000_000_000.0 / dtNs
        val instMs = dtNs / 1_000_000.0
        fps = if (fps == 0.0) instFps else fps * (1 - SMOOTH) + instFps * SMOOTH
        frameMs = if (frameMs == 0.0) instMs else frameMs * (1 - SMOOTH) + instMs * SMOOTH
    }

    fun draw(r: AndroidRenderer, state: CanvasState) {
        if (!enabled) return
        val snap = state.cacheSnapshot()
        val rt = Runtime.getRuntime()
        val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val heapMaxMb = rt.maxMemory() / MB

        val (rw, rh) = state.targetRasterSize()
        val res = if (rw > 0) "$rw x $rh" else "-"
        val lines = listOf(
            "%.0f fps   %.1f ms".format(fps, frameMs),
            "cache res  $res",
            "ink cache  ${snap.inkPages} pg",
            "bg  cache  ${snap.bgPages} pg",
            "cache mem  %.1f MB".format(snap.bytes / MB),
            "heap  %.0f / %.0f MB".format(heapUsedMb, heapMaxMb),
        )

        val lineH = AndroidText.lineHeight(FONT)
        val hintH = AndroidText.lineHeight(HINT_FONT)
        val panelH = PAD_Y * 2 + lines.size * lineH + HINT_GAP + hintH
        val x = state.viewportW - PANEL_W - MARGIN
        val y = MARGIN

        r.fillRect(Rect(x, y, PANEL_W, panelH), PANEL_BG)

        val textW = PANEL_W - PAD_X * 2
        var ty = y + PAD_Y
        for (line in lines) {
            r.drawText(line, Rect(x + PAD_X, ty, textW, lineH), FONT, TEXT)
            ty += lineH
        }
        r.drawText(HINT, Rect(x + PAD_X, ty + HINT_GAP, textW, hintH), HINT_FONT, TEXT_DIM)
    }

    companion object {
        private const val MB = 1024.0 * 1024.0
        private const val IDLE_GAP_NS = 200_000_000L // deltas longer than 200ms count as idle
        private const val SMOOTH = 0.15 // EMA weight on the newest frame

        private const val PANEL_W = 460.0
        private const val MARGIN = 16.0
        private const val PAD_X = 20.0
        private const val PAD_Y = 18.0
        private const val HINT_GAP = 10.0

        private const val HINT = "four finger tap to dismiss"
        private val FONT = FontSpec(15.0, bold = true)
        private val HINT_FONT = FontSpec(11.0)
        private val PANEL_BG = Rgba(0, 0, 0, 170)
        private val TEXT = Rgba(238, 238, 238)
        private val TEXT_DIM = Rgba(175, 175, 175)
    }
}
