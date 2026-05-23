package com.xnotes.presentation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xnotes.canvas.CanvasState
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.ImageCodec
import com.xnotes.platform.PresentationServer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the presentation run state (spec 12 §2): on/off, mode, quality, frame
 * rate. Emits frames on change, throttled to the max FPS — never as constant
 * video when idle. The main thread only snapshots a cheap frame plan (cache refs +
 * a copy of the live stroke); the blit, JPEG encode and network push all run on a
 * background thread, so presenting never competes with on-screen drawing. Only one
 * frame is in flight at a time — bursts coalesce to the latest, with a guaranteed
 * trailing frame — and nothing is rendered while no viewer is connected.
 */
class PresentationController(
    state: CanvasState,
    private val imageCodec: ImageCodec,
    liveStroke: () -> Pair<Int, Stroke>?,
    private val onStateChanged: () -> Unit,
) {
    private val server = PresentationServer()
    private val frameSource = PresentationFrameSource(state, liveStroke)
    private val handler = Handler(Looper.getMainLooper())
    private val encoder = Executors.newSingleThreadExecutor()
    private val state = state

    var mode: String = "page"
        private set
    var quality: String = "medium"
        private set
    var maxFps: Int = 30
        private set
    var port: Int = 8000
        private set
    var lan: Boolean = false
        private set
    val running: Boolean get() = server.isRunning
    val clientCount: Int get() = server.clientCount

    private var lastFrameAt = 0L
    private var scheduled = false
    private val frameInFlight = AtomicBoolean(false)
    @Volatile private var framePending = false

    init {
        server.onClientCountChanged = { handler.post { onStateChanged(); notifyChanged() } }
        server.statusJson = { statusJson() }
        server.onQualityRequest = { q -> handler.post { setQuality(q) } }
    }

    /** Start the server; returns an error message on failure, or null on success. */
    fun start(port: Int, lan: Boolean, mode: String, quality: String, maxFps: Int): String? {
        this.port = port
        this.lan = lan
        this.mode = mode
        this.quality = quality
        this.maxFps = maxFps
        val result = server.start(port, lan)
        return if (result.isSuccess) {
            onStateChanged()
            notifyChanged()
            null
        } else {
            "Could not start on port $port (${result.exceptionOrNull()?.message ?: "in use"})."
        }
    }

    fun stop() {
        server.stop()
        onStateChanged()
    }

    fun setMode(mode: String) {
        this.mode = mode
        if (running) notifyChanged()
        onStateChanged()
    }

    /** Change stream quality ("low" | "medium" | "high"); takes effect on the next frame. */
    fun setQuality(quality: String) {
        if (quality == this.quality) return
        this.quality = quality
        if (running) notifyChanged()
        onStateChanged()
    }

    /** Request a (throttled) frame; rides the canvas's repaint cadence. */
    fun notifyChanged() {
        if (!running) return
        val interval = (1000L / maxFps).coerceAtLeast(16L)
        val elapsed = SystemClock.uptimeMillis() - lastFrameAt
        if (elapsed >= interval) {
            produceFrame()
        } else if (!scheduled) {
            scheduled = true
            handler.postDelayed({ scheduled = false; produceFrame() }, interval - elapsed)
        }
    }

    private fun produceFrame() {
        if (!running) return
        if (server.clientCount == 0) return // no viewer connected: skip rendering entirely
        if (!frameInFlight.compareAndSet(false, true)) {
            framePending = true // a frame is still in flight; emit the latest once it finishes
            return
        }
        framePending = false
        lastFrameAt = SystemClock.uptimeMillis()
        // Cheap main-thread phase: snapshot what the frame needs (cache refs + a copy
        // of the live stroke). The expensive blit + JPEG encode run off-thread so they
        // never compete with on-screen drawing.
        val plan = try {
            if (mode == "follow") frameSource.planFollow(longEdge()) else frameSource.planPage(longEdge())
        } catch (_: Exception) {
            null
        }
        if (plan == null) {
            frameInFlight.set(false)
            return
        }
        val q = jpegQuality()
        encoder.execute {
            try {
                val surface = frameSource.render(plan)
                try {
                    server.pushFrame(imageCodec.encodeJpeg(surface, q))
                } finally {
                    surface.recycle()
                }
            } catch (_: Exception) {
                // drop this frame
            } finally {
                frameInFlight.set(false)
                if (framePending) handler.post { if (running) produceFrame() }
            }
        }
    }

    fun url(): String = "http://${host()}:$port/"

    private fun host(): String {
        if (!lan) return "localhost"
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "0.0.0.0"
    }

    private fun statusJson(): String {
        val title = state.document.title.replace("\"", "\\\"")
        return """{"presenting":$running,"mode":"$mode","page":${state.currentPageIndex()},""" +
            """"page_count":${state.document.pages.size},"title":"$title"}"""
    }

    private fun longEdge(): Int = when (quality) {
        "low" -> 960
        "high" -> 1920
        else -> 1440
    }

    private fun jpegQuality(): Double = when (quality) {
        "low" -> 0.7
        "high" -> 0.85
        else -> 0.8
    }
}
