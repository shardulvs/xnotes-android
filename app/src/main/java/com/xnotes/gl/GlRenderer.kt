package com.xnotes.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.xnotes.core.infinite.BackgroundPattern
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.model.Rgba
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Everything the GL thread needs for one frame, snapshotted on the main thread. The model and the
 * viewport belong to the main thread; copying the handful of numbers a frame reads is cheaper than
 * locking, and it guarantees a frame never sees half of a gesture's update.
 */
data class FrameState(
    val zoom: Double,
    val scrollX: Double,
    val scrollY: Double,
    val widthPx: Int,
    val heightPx: Int,
    val background: CanvasBackground,
    val paper: Rgba,
    /** Eraser cursor centre in device pixels, or null when the eraser is not down. */
    val cursorX: Double = 0.0,
    val cursorY: Double = 0.0,
    val cursorRadius: Double = 0.0,
    val cursorVisible: Boolean = false,
    /** Whether to draw the minimap, and what it should map. */
    val minimapVisible: Boolean = false,
    val contentBounds: com.xnotes.core.geometry.Rect? = null,
    /** Accent for the minimap's markers, from the app palette. */
    val accent: Rgba = Rgba(0, 230, 118, 255),
    /** What a floating toolbar covers of the right and bottom edges, which the minimap keeps clear of. */
    val insetRight: Double = 0.0,
    val insetBottom: Double = 0.0,
) {
    companion object {
        val EMPTY = FrameState(1.0, 0.0, 0.0, 0, 0, CanvasBackground(), Rgba(255, 255, 255, 255))
    }
}

/**
 * Content drawn over the background. Implemented by the geometry store from stage 4 on; kept
 * separate so the renderer owns nothing but the context lifecycle and the background.
 */
interface GlScene {
    /**
     * A fresh EGL context exists and every previous GL object is gone. Rebuild all GPU state from
     * the model. Runs on the GL thread.
     */
    fun onContextCreated(contextGen: Int)

    /** Draw the scene for [frame]. Runs on the GL thread with the framebuffer already cleared. */
    fun drawContent(frame: FrameState)

    /** Fill in what the scene just drew, for the debug HUD. Runs on the GL thread. */
    fun describe(into: GlStats): GlStats = into
}

/**
 * Owns the EGL context's lifetime and draws one frame: paper, ruling, then the scene.
 *
 * Backgrounding the app or destroying the surface kills every buffer, texture and program handle
 * at once, a failure class the paged canvas simply does not have. So nothing here is built lazily
 * and cached across a context: [onSurfaceCreated] bumps [contextGen], throws away every handle it
 * was holding, and rebuilds. Anything GPU-resident records the generation it was born under, and
 * a stale generation means rebuild rather than bind a dangling name.
 */
class GlRenderer : GLSurfaceView.Renderer {

    /** Snapshot the next frame will draw; replaced wholesale by the main thread. */
    @Volatile
    var frame: FrameState = FrameState.EMPTY

    @Volatile
    var scene: GlScene? = null

    /**
     * Run once at the end of the next frame, on the GL thread.
     *
     * This is how the front buffer hands a finished stroke back without a blink: the pad keeps its
     * pixels until the canvas has drawn the committed item, and only then wipes them.
     *
     * A queue rather than a slot, because more than one thing can be waiting on the same frame and
     * a slot silently drops all but the last: a stroke waiting to know it is on the glass and the
     * pad waiting to come down are two of them, and losing either is the blink itself.
     */
    private val afterFrame = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    fun runAfterFrame(action: () -> Unit) {
        afterFrame.add(action)
    }

    /** Bumped on every new EGL context. Everything GPU-resident is stamped with it. */
    @Volatile
    var contextGen: Int = 0
        private set

    /** Set when a shader would not build, so the host can show a message rather than a black view. */
    @Volatile
    var failure: String? = null
        private set

    /** What the self check made of this context, for the debug readout. */
    @Volatile
    var selfCheck: String = ""
        private set

    private var background: BackgroundShader? = null
    private var cursor: CursorShader? = null

    /** What the last frame did, swapped wholesale so the main thread never sees it half written. */
    @Volatile
    var stats: GlStats = GlStats()
        private set

    /** Reads the multisample count the config chooser obtained, which it only knows once a
     *  surface exists, so it is asked for rather than handed over at construction. */
    var msaaSamples: () -> Int = { 0 }

    /** Publish requests seen so far, written by the view and differenced per frame. */
    @Volatile
    var publishRequests: Int = 0

    /** The panel's refresh rate, so the HUD can say whether a frame count is at the cap. */
    @Volatile
    var displayHz: Double = 60.0

    // Timestamps of recently drawn frames, so the rate is a count over a window rather than a
    // smoothed interval. A smoothed interval reads at the refresh rate whenever anything is
    // drawing at all, which says nothing about whether frames were dropped.
    private val frameStamps = LongArray(WINDOW_FRAMES)

    /** Device pixels the content moved between each frame and the one before it. */
    private val frameSteps = DoubleArray(WINDOW_FRAMES)
    private var lastScrollX = 0.0
    private var lastScrollY = 0.0
    private var lastZoom = 0.0
    private var frameHead = 0
    private var frameCount = 0
    private var frameMs = 0.0
    private var lastRequests = 0
    private var requestsPerFrame = 0.0
    private var rendererName = ""
    private var glVersionName = ""

    /** Invoked on the GL thread right after a context is (re)built, for host-side bookkeeping. */
    var onContextReady: ((Int) -> Unit)? = null

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // The old context is already gone: its objects cannot and must not be deleted, only dropped.
        background = null
        contextGen++
        failure = null
        FrontBufferProbe.log()
        rendererName = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
        glVersionName = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
        frameCount = 0
        frameHead = 0
        Log.i(TAG, "context $contextGen on $rendererName, $glVersionName, ${msaaSamples()}x MSAA")
        cursor = null
        try {
            background = BackgroundShader(contextGen)
            cursor = CursorShader(contextGen)
        } catch (e: GlShaderException) {
            failure = e.message
            Log.e(TAG, "background shader unavailable", e)
        }
        GLES30.glDisable(GLES30.GL_DITHER)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        // Before the scene rebuilds anything, so a driver that gets the arithmetic or the blending
        // wrong is reported as that rather than as a canvas that looks a bit off.
        val checked = GlSelfCheck().run(contextGen)
        selfCheck = checked.summary
        if (!checked.ok) failure = listOfNotNull(failure, checked.summary).joinToString("; ")
        scene?.onContextCreated(contextGen)
        onContextReady?.invoke(contextGen)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        val f = frame
        if (f.widthPx <= 0 || f.heightPx <= 0) return
        // Taken before the frame, not after: something added while this one draws is waiting on
        // the next frame, not on this one.
        val after = ArrayList<() -> Unit>(afterFrame.size)
        while (true) after.add(afterFrame.poll() ?: break)
        val started = System.nanoTime()
        val paper = f.paper
        // Effects and offscreen passes change GL viewport/scissor state. On a reused canvas
        // surface (e.g. switching from a paged note), reset it before clearing the whole frame.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, f.widthPx, f.heightPx)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(paper.r / 255f, paper.g / 255f, paper.b / 255f, 1f)
        GLES30.glClearStencil(0)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)

        background?.let { shader ->
            val resolved = BackgroundPattern.resolve(f.background, f.zoom, f.scrollX, f.scrollY)
            shader.draw(f.background, resolved, paper, f.widthPx, f.heightPx)
        }

        scene?.drawContent(f)
        if (f.cursorVisible) {
            cursor?.draw(f.cursorX, f.cursorY, f.cursorRadius, cursorColor(f.paper), f.widthPx, f.heightPx)
        }
        sampleFrame(started, f)
        // After the draw and before the swap, which is the earliest point at which this frame's
        // pixels are certain to be the next thing the compositor latches for this layer.
        for (action in after) action()
    }

    /**
     * Record one drawn frame and republish the stats.
     *
     * The rate is a count of frames over the last second, not a smoothed interval, because a
     * smoothed interval is bounded by the display and so reads at the refresh rate the moment
     * anything is drawing at all. What actually matters is whether frames were missed, so the
     * longest gap and the count of late frames are reported alongside it, and the work inside the
     * frame is timed separately from the wait for the display.
     */
    private fun sampleFrame(startedNs: Long, f: FrameState) {
        val now = System.nanoTime()
        frameMs = (now - startedNs) / 1_000_000.0
        // How far the content actually moved on screen since the last frame. Even steps are what
        // reads as smooth; uneven ones read as stutter however many frames were drawn.
        val moved = if (lastZoom == f.zoom) {
            kotlin.math.hypot(f.scrollX - lastScrollX, f.scrollY - lastScrollY) * f.zoom
        } else {
            -1.0 // a zoom change is not a translation, so it is not part of the pan measurement
        }
        lastScrollX = f.scrollX
        lastScrollY = f.scrollY
        lastZoom = f.zoom
        frameSteps[frameHead] = moved
        frameStamps[frameHead] = now
        frameHead = (frameHead + 1) % WINDOW_FRAMES
        if (frameCount < WINDOW_FRAMES) frameCount++

        val requests = publishRequests
        requestsPerFrame = (requests - lastRequests).coerceAtLeast(0).toDouble()
        lastRequests = requests

        // Walk the window newest first, stopping at the second boundary.
        var frames = 0
        var worstNs = 0L
        var jank = 0
        val refreshNs = if (displayHz > 1.0) (1_000_000_000.0 / displayHz) else 16_666_667.0
        val lateNs = (refreshNs * 1.5).toLong()
        var previous = now
        var stepSum = 0.0
        var stepSquares = 0.0
        var steps = 0
        for (i in 1 until frameCount) {
            val slot = (frameHead - 1 - i + WINDOW_FRAMES * 2) % WINDOW_FRAMES
            val stamp = frameStamps[slot]
            if (now - stamp > WINDOW_NS) break
            val gap = previous - stamp
            if (gap > worstNs) worstNs = gap
            if (gap > lateNs) jank++
            previous = stamp
            frames++
            // Still frames and zoom changes say nothing about how evenly a pan moved.
            val step = frameSteps[slot]
            if (step > 0.0) {
                stepSum += step
                stepSquares += step * step
                steps++
            }
        }
        var stepMean = 0.0
        var jitter = 0.0
        if (steps >= 4) {
            stepMean = stepSum / steps
            val variance = (stepSquares / steps) - stepMean * stepMean
            if (stepMean > 0.0 && variance > 0.0) jitter = kotlin.math.sqrt(variance) / stepMean
        }

        val base = GlStats(
            fps = frames.toDouble(),
            frameMs = frameMs,
            worstFrameMs = worstNs / 1_000_000.0,
            jankFrames = jank,
            displayHz = displayHz,
            requestsPerFrame = requestsPerFrame,
            stepJitter = jitter,
            stepPx = stepMean,
            contextGen = contextGen,
            msaaSamples = msaaSamples(),
            renderer = rendererName,
            glVersion = glVersionName,
            selfCheck = selfCheck,
        )
        stats = scene?.describe(base) ?: base
    }

    /** A ring that reads against the paper it sits on, dark on light and light on dark. */
    private fun cursorColor(paper: Rgba): Rgba {
        val luminance = (paper.r * 0.299 + paper.g * 0.587 + paper.b * 0.114) / 255.0
        return if (luminance > 0.5) Rgba(0, 0, 0, 150) else Rgba(255, 255, 255, 170)
    }

    companion object {
        private const val TAG = "xnotes.gl"

        /** The window the rate is counted over. */
        private const val WINDOW_NS = 1_000_000_000L

        /** Frame timestamps kept, comfortably more than a second at any refresh rate. */
        private const val WINDOW_FRAMES = 256
    }
}
