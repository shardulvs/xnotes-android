package com.xnotes.canvas

import android.os.Debug
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec
import com.xnotes.platform.AndroidRenderer
import com.xnotes.platform.AndroidText
import com.xnotes.platform.PdfiumDocument

/**
 * A translucent, non-interactive debug HUD pinned to the top-right of the canvas,
 * toggled by a four-finger tap (detected in [CanvasView]). It reports the live frame
 * rate, page-cache occupancy and Java heap use.
 *
 * Frame timing is sampled in [CanvasView.onDraw]; while the HUD is up a low-rate idle ticker keeps
 * repainting it (independent of interaction), so the rate falls to 0 when idle instead of freezing and
 * the memory/autosave readouts stay live. The HUD is drawn as plain pixels on top of the frame and
 * never reads input, so it cannot interfere with stylus/finger drawing underneath it.
 */
class DebugOverlay {
    var enabled = false
        private set

    /** What the front buffer is doing, when there is one; installed by the host. */
    var frontHud: () -> String? = { null }

    fun toggle() {
        enabled = !enabled
    }

    // Frame timing, sampled once per painted frame (smoothed; see [sampleFrame]).
    private var lastFrameNs = 0L
    private var fps = 0.0
    private var frameMs = 0.0

    // Process memory in MB (total PSS / native heap / graphics), re-sampled at most once a second in
    // [draw]: Debug.getMemoryInfo walks /proc/self/smaps and is far too costly to read every frame.
    private val memInfo = Debug.MemoryInfo()
    private var lastMemSampleNs = 0L
    private var pssMb = 0.0
    private var nativeMb = 0.0
    private var gfxMb = 0.0

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

    /**
     * Re-read total PSS, native-heap and graphics memory from the OS, throttled to [MEM_SAMPLE_NS]
     * (~1s). These come from [Debug.getMemoryInfo], which walks /proc/self/smaps and is too heavy to
     * call per frame; the idle ticker keeps [draw] running so the values still refresh while idle.
     */
    private fun sampleMemory(nowNs: Long) {
        if (lastMemSampleNs != 0L && nowNs - lastMemSampleNs < MEM_SAMPLE_NS) return
        lastMemSampleNs = nowNs
        Debug.getMemoryInfo(memInfo)
        pssMb = memStat("summary.total-pss") / 1024.0 // getMemoryStat reports KB
        nativeMb = memStat("summary.native-heap") / 1024.0
        gfxMb = memStat("summary.graphics") / 1024.0
    }

    private fun memStat(key: String): Long = memInfo.getMemoryStat(key)?.toLongOrNull() ?: 0L

    private fun fmtBytes(bytes: Long): String =
        if (bytes < 1024 * 1024) "%.0f KB".format(bytes / 1024.0) else "%.2f MB".format(bytes / MB)

    fun draw(r: AndroidRenderer, state: CanvasState) {
        if (!enabled) return
        sampleMemory(System.nanoTime())
        val snap = state.cacheSnapshot()
        val rt = Runtime.getRuntime()
        val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val heapMaxMb = rt.maxMemory() / MB

        val (rw, rh) = state.targetRasterSize()
        val res = if (rw > 0) "$rw x $rh" else "-"
        val lines = buildList {
            add("%.0f fps   %.1f ms".format(fps, frameMs))
            add("cache res  $res")
            add("visible    ${snap.visiblePages} pg")
            add("ink cache  ${snap.inkPages} pg")
            add("bg  cache  ${snap.bgPages} pg")
            add("cache mem  %.1f MB".format(snap.bytes / MB))
            add("heap  %.0f / %.0f MB".format(heapUsedMb, heapMaxMb))
            add("pss    %.0f MB".format(pssMb))
            add("native %.0f MB".format(nativeMb))
            add("gfx    %.0f MB".format(gfxMb))
            add("pdfium     ${PdfiumDocument.hud}")
            frontHud()?.let { add("front     $it") }
            add("autosave   ${state.autosaveStatus}")
            if (state.lastOpenTotalMs >= 0) {
                add("open       ${state.lastOpenTotalMs} ms")
                add(" read      ${state.lastOpenReadMs} ms")
                add("  inflate  ${state.lastOpenInflateMs} ms")
                add("  parse    ${state.lastOpenParseMs} ms")
                add("  assets   ${state.lastOpenAssetsMs} ms")
                add("  compact  ${state.lastOpenCompactMs} ms")
            }
            if (state.openFileBytes >= 0) {
                add("compact    ${if (state.lastOpenCompacted) "yes" else "no"}")
                add("file orig  ${fmtBytes(state.openFileBytes)}")
            }
            if (state.lastSaveBytes >= 0) add("file live  ${fmtBytes(state.lastSaveBytes)}")
            if (state.lastSaveTotalMs >= 0) {
                add("save       ${state.lastSaveTotalMs} ms")
                add(" snapshot  ${state.lastSaveSnapshotMs} ms")
                add(" encode    ${state.lastSaveEncodeMs} ms")
                add("  json     ${state.lastSaveManifestMs} ms")
                add("   deflate ${state.lastSaveDeflateMs} ms")
                add("   raw     ${fmtBytes(state.lastSaveManifestBytes)}")
                add("  assets   ${state.lastSaveAssetsMs} ms")
                add(" saf copy  ${state.lastSaveCopyMs} ms")
            }
        }

        // Measure each row as it will actually be laid out, not as one line: [Renderer.drawText]
        // wraps to the rect's width, so a row too long for the panel used to be drawn over the one
        // below it. Advancing by the wrapped height pushes the rest down instead.
        val textW = PANEL_W - PAD_X * 2
        val rowH = lines.map { AndroidText.blockHeight(it, textW.toInt(), FONT) }
        val hintH = AndroidText.lineHeight(HINT_FONT)
        val panelH = PAD_Y * 2 + rowH.sum() + HINT_GAP + hintH
        val x = state.viewportW - PANEL_W - MARGIN
        val y = MARGIN

        r.fillRect(Rect(x, y, PANEL_W, panelH), PANEL_BG)

        var ty = y + PAD_Y
        for (i in lines.indices) {
            r.drawText(lines[i], Rect(x + PAD_X, ty, textW, rowH[i]), FONT, TEXT)
            ty += rowH[i]
        }
        r.drawText(HINT, Rect(x + PAD_X, ty + HINT_GAP, textW, hintH), HINT_FONT, TEXT_DIM)
    }

    companion object {
        private const val MB = 1024.0 * 1024.0
        private const val IDLE_GAP_NS = 200_000_000L // deltas longer than 200ms count as idle
        private const val MEM_SAMPLE_NS = 1_000_000_000L // re-read PSS from smaps at most once a second
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
