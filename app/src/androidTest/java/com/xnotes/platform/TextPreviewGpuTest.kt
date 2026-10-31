package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.GLES30
import android.test.InstrumentationTestCase
import android.util.Log
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.gl.CanvasScene
import com.xnotes.gl.FrameState
import kotlin.math.ceil
import kotlin.math.sqrt

/** Measures native text quad uploads plus GL drawing offscreen; never opens a user's note. */
@Suppress("DEPRECATION")
class TextPreviewGpuTest : InstrumentationTestCase() {
    fun testRepeatedPreviewUpdatesAndRigidMovementOnDeviceGpu() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_STENCIL_SIZE, 8, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0))
        assertTrue(count[0] > 0)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1200, EGL14.EGL_HEIGHT, 800, EGL14.EGL_NONE), 0)
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            GLES30.glViewport(0, 0, 1200, 800)
            val maxTexture = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            Log.i("XnotesGPUCapability", "maxTextureSide=${maxTexture[0]}")
            val scene = CanvasScene()
            scene.onContextCreated(1)
            val text = ("Texto longo para testar a GPU e os envios de textura. ")
                .repeat(30).take(1266)
            val font = FontSpec(13.0, FontFace.MONO)
            val item = ShapeItem(ShapeKind.POLYGON, Pt.ZERO, Pt(410.0, 500.0),
                Rgba(0, 0, 0, 0), 0.0).also {
                it.label = ShapeLabel(text, TextStyle(Rgba(0, 0, 0),
                    13.0, FontFace.MONO), AndroidTextMeasurer())
            }
            scene.setOrder(listOf(item))
            val frame = FrameState.EMPTY.copy(zoom = 1.0, scrollX = 0.0,
                scrollY = 0.0, widthPx = 1200, heightPx = 800)
            val times = ArrayList<Double>()
            var uploads = 0
            repeat(48) { index ->
                if (index % 6 == 0) {
                    val w = 320.0 + index * 2
                    val layout = AndroidText.drawLayout(text, w.toInt(), font,
                        android.graphics.Color.BLACK, ShapeLabel.FLAGS)
                    val h = layout.height.toDouble()
                    val scale = minOf(1.0, 1024.0 / w, 1024.0 / h,
                        sqrt(350_000.0 / (w * h)))
                    val bitmap = Bitmap.createBitmap(
                        ceil(w * scale).toInt().coerceAtLeast(1),
                        ceil(h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply {
                        scale(scale.toFloat(), scale.toFloat())
                        layout.draw(this)
                    }
                    val corners = doubleArrayOf(50.0, 40.0, 50.0 + w, 40.0,
                        50.0, 40.0 + h, 50.0 + w, 40.0 + h)
                    scene.upsertText(item, bitmap, corners, Rect(50.0, 40.0, w, h))
                    uploads++
                }
                scene.setLift(listOf(item), LiftTransform.shift(index * 2.0, 0.0))
                GLES30.glClearColor(1f, 1f, 1f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
                val began = System.nanoTime()
                scene.drawContent(frame)
                GLES30.glFinish()
                times += (System.nanoTime() - began) / 1e6
                assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            }
            val sorted = times.sorted()
            Log.i("XnotesPreviewGPU", "frames=48 uploads=$uploads " +
                "median=%.2fms p95=%.2fms max=%.2fms".format(
                    java.util.Locale.US, sorted[24], sorted[44], sorted.last()))
            assertEquals(8, uploads)

            // Reproduce the very tall, narrow text texture seen when both sides approach.
            val narrowWidth = 159.0
            val label = requireNotNull(item.label)
            val narrowLayout = AndroidText.drawLayout(text, label.normalizedWidth(narrowWidth),
                label.normalizedFont, android.graphics.Color.BLACK, ShapeLabel.FLAGS)
            val glyphScale = label.style.pointSize / ShapeLabel.NORMALIZED_POINT_SIZE
            val narrowHeight = narrowLayout.height * glyphScale
            val narrowBitmap = Bitmap.createBitmap(narrowWidth.toInt(),
                ceil(narrowHeight).toInt(), Bitmap.Config.ARGB_8888)
            Canvas(narrowBitmap).apply {
                scale(glyphScale.toFloat(), glyphScale.toFloat())
                narrowLayout.draw(this)
            }
            scene.upsertText(item, narrowBitmap,
                doubleArrayOf(50.0, 40.0, 50.0 + narrowWidth, 40.0,
                    50.0, 40.0 + narrowHeight, 50.0 + narrowWidth, 40.0 + narrowHeight),
                Rect(50.0, 40.0, narrowWidth, narrowHeight))
            val narrowTimes = ArrayList<Double>()
            repeat(24) { index ->
                scene.setLift(listOf(item), LiftTransform.shift(index * 4.0, 0.0))
                val began = System.nanoTime()
                scene.drawContent(frame)
                GLES30.glFinish()
                narrowTimes += (System.nanoTime() - began) / 1e6
                assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            }
            val narrowSorted = narrowTimes.sorted()
            Log.i("XnotesNarrowGPU", "size=${narrowBitmap.width}x${narrowBitmap.height} " +
                "bytes=${narrowBitmap.allocationByteCount} " +
                "median=%.2fms p95=%.2fms uploadFrame=%.2fms".format(
                    java.util.Locale.US, narrowSorted[12], narrowSorted[21], narrowTimes[0]))

            // The same canonical line layout, progressively rasterized at 1×, 2×, and 4×.
            // Only texture pixels change; no text or document geometry is modified.
            val zoomText = text.take(140)
            val zoomWidth = 420.0
            val zoomLayout = AndroidText.drawLayout(zoomText,
                label.normalizedWidth(zoomWidth), label.normalizedFont,
                android.graphics.Color.BLACK, ShapeLabel.FLAGS)
            val zoomHeight = zoomLayout.height * glyphScale
            val produced = ArrayList<String>()
            for (target in listOf(1.0, 2.0, 4.0)) {
                val lod = com.xnotes.core.infinite.textBitmapScale(zoomWidth, zoomHeight,
                    scene.maxTextTextureSide, preview = false, targetScale = target)
                val bitmap = Bitmap.createBitmap(
                    ceil(zoomWidth * lod).toInt(), ceil(zoomHeight * lod).toInt(),
                    Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    scale((lod * glyphScale).toFloat(), (lod * glyphScale).toFloat())
                    zoomLayout.draw(this)
                }
                produced += "$target:${bitmap.width}x${bitmap.height}"
                val quad = doubleArrayOf(10.0, 20.0, 10.0 + zoomWidth, 20.0,
                    10.0, 20.0 + zoomHeight, 10.0 + zoomWidth, 20.0 + zoomHeight)
                scene.upsertText(item, bitmap, quad, Rect(10.0, 20.0, zoomWidth, zoomHeight))
                scene.setLift(emptyList(), LiftTransform.NONE)
                scene.drawContent(frame.copy(zoom = target))
                GLES30.glFinish()
                assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
                assertTrue(bitmap.allocationByteCount <= 8_400_000)
            }
            Log.i("XnotesZoomGPU", "tiers=${produced.joinToString()} lines=${zoomLayout.lineCount}")
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    /** Measure chrome tessellation, the CPU work repeated on every pointer move. */
    fun testTallSelectionChromeOnlyProcessesOnscreenDashes() {
        val box = com.xnotes.core.geometry.Obb(Pt(100.0, 4000.0), 159.0, 4000.0, 0.0)
        val visible = Rect(-500.0, 0.0, 2000.0, 1400.0)
        val accent = Rgba(40, 140, 220)
        fun sample(clipped: Boolean): Pair<List<Double>, Int> {
            val timings = ArrayList<Double>()
            var vertices = 0
            repeat(50) {
                val started = System.nanoTime()
                val parts = com.xnotes.core.infinite.OverlayTessellator.selection(
                    box, 0.5, accent, 0.01,
                    visibleRect = if (clipped) visible else null)
                timings += (System.nanoTime() - started) / 1e6
                vertices = parts.sumOf { it.mesh.vertexCount }
            }
            return timings.sorted() to vertices
        }
        sample(clipped = true)
        val (oldTimes, oldVertices) = sample(clipped = false)
        val (newTimes, newVertices) = sample(clipped = true)
        Log.i("XnotesChromeCPU", "unclippedVertices=$oldVertices " +
            "clippedVertices=$newVertices " +
            "unclippedMedian=%.2fms unclippedP95=%.2fms ".format(
                java.util.Locale.US, oldTimes[25], oldTimes[46]) +
            "clippedMedian=%.2fms clippedP95=%.2fms".format(
                java.util.Locale.US, newTimes[25], newTimes[46]))
        assertTrue(newVertices < oldVertices / 2)
    }
}
