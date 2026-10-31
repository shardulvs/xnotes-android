package com.xnotes.platform

import android.opengl.EGL14
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.xnotes.core.vector.VectorSeg
import android.test.InstrumentationTestCase
import android.util.Log
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.ShapeTessellator
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.PathFlattener

/** Runs on Android: the JVM fake cannot validate native layout or glyph paths. */
@Suppress("DEPRECATION")
class TextReflowTest : InstrumentationTestCase() {
    private fun reference(text: String, font: FontSpec, width: Double, flags: TextFlags): List<VectorContour> {
        val paint = AndroidText.textPaint(font)
        val layout = AndroidText.layout(text, width.toInt(), paint, flags)
        return buildList {
            for (line in 0 until layout.lineCount) {
                val start = layout.getLineStart(line)
                var end = layout.getLineEnd(line)
                while (end > start && text[end - 1].isWhitespace()) end--
                if (end <= start) continue
                val path = Path()
                paint.getTextPath(text, start, end, layout.getLineLeft(line), layout.getLineBaseline(line).toFloat(), path)
                addAll(sampledContours(path, paint.textSize.toDouble()))
            }
        }
    }

    /** Historical PathMeasure baseline for compatibility assertions; never shipped in main code. */
    private fun sampledContours(source: Path, size: Double): List<VectorContour> {
        val step = size / 64.0
        val tolerance = size / 2000.0
        val measure = PathMeasure(source, false)
        val out = ArrayList<VectorContour>()
        val at = FloatArray(2)
        var guard = 4096
        do {
            val length = measure.length
            if (length > 1e-4) {
                val count = kotlin.math.ceil(length / step).toInt().coerceIn(2, 4096)
                val points = ArrayList<Pt>(count + 1)
                for (i in 0..count) {
                    val distance = length * i / count
                    if (measure.getPosTan(distance, at, null)) {
                        points += Pt(at[0].toDouble(), at[1].toDouble())
                    }
                }
                val thinned = PathFlattener.decimate(points, tolerance)
                if (thinned.size >= 3) {
                    val last = thinned.last()
                    val first = thinned.first()
                    val near = kotlin.math.abs(first.x - last.x) < 1e-4 &&
                        kotlin.math.abs(first.y - last.y) < 1e-4
                    val ring = if (near) thinned.dropLast(1) else thinned
                    out += VectorContour(ring[0],
                        ring.drop(1).map { VectorSeg.Line(it) }, closed = true)
                }
            }
        } while (measure.nextContour() && guard-- > 0)
        return out
    }

    private fun nativePath(text: String, font: FontSpec, width: Double, flags: TextFlags): Path {
        val paint = AndroidText.textPaint(font)
        val layout = AndroidText.layout(text, width.toInt(), paint, flags)
        val result = Path()
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            var end = layout.getLineEnd(line)
            while (end > start && text[end - 1].isWhitespace()) end--
            if (end <= start) continue
            val path = Path()
            paint.getTextPath(text, start, end, layout.getLineLeft(line), layout.getLineBaseline(line).toFloat(), path)
            result.addPath(path)
        }
        return result
    }

    fun testReflowMatchesNativeOutlinesAndReusesLines() {
        val measurer = AndroidTextMeasurer()
        val font = FontSpec(13.0, FontFace.SANS)
        val flags = ShapeLabel.FLAGS
        for (text in listOf("Ação café office fi ffi\nsegunda linha", "مرحبا بالعالم نص عربي", "abc אבג 123", "a\u0301 e\u0302 ç\n\n fim")) {
            for (width in listOf(150.0, 231.0, 400.0, 150.0)) {
                val actual = measurer.outline(text, font, width, flags)


                // Translation changes PathMeasure's float rounding and which collinear points
                // decimation keeps. Compare filled coverage instead of corresponding samples.
                fun raster(contours: List<VectorContour>?): IntArray {
                    val h = measurer.measure(text, font, width, flags).h.toInt() + 50
                    val bitmap = Bitmap.createBitmap(1000, h * 2, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.scale(2f, 2f)
                    canvas.translate(20f, 20f)
                    val path = Path()
                    for (c in contours.orEmpty()) {
                        path.moveTo(c.start.x.toFloat(), c.start.y.toFloat())
                        for (seg in c.segments) when (seg) {
                            is VectorSeg.Line -> path.lineTo(seg.end.x.toFloat(), seg.end.y.toFloat())
                            is VectorSeg.Cubic -> path.cubicTo(seg.c1.x.toFloat(), seg.c1.y.toFloat(),
                                seg.c2.x.toFloat(), seg.c2.y.toFloat(), seg.end.x.toFloat(), seg.end.y.toFloat())
                        }
                        if (c.closed) path.close()
                    }
                    canvas.drawPath(if (contours == null) nativePath(text, font, width, flags) else path, Paint(Paint.ANTI_ALIAS_FLAG))
                    return IntArray(bitmap.width * bitmap.height).also {
                        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        bitmap.recycle()
                    }
                }
                val a = raster(actual)
                val e = raster(null)
                var error = 0L
                var coverage = 0L
                for (i in a.indices) {
                    error += kotlin.math.abs((a[i] ushr 24) - (e[i] ushr 24))
                    coverage += e[i] ushr 24
                }
                assertTrue("Coverage mismatch for '$text' at $width: $error / $coverage",
                    error.toDouble() / coverage.coerceAtLeast(1) < 0.02)
            }
        }
        val first = measurer.outlineLines("fixed line\nanother", font, 400.0, flags)
        val second = measurer.outlineLines("fixed line\nanother", font, 450.0, flags)
        assertSame(first[0].contours, second[0].contours)
        assertSame(first[1].contours, second[1].contours)
        assertTrue(first[0].origin != second[0].origin)
    }

    /** Optional private fixture, copied to the debug app cache by the developer. Never writes it. */
    fun testDeviceFixtureReflowIfPresent() {
        val fixture = java.io.File(instrumentation.targetContext.cacheDir, "reflow-fixture.xcanvas")
        if (!fixture.isFile) {
            Log.i("XnotesReflow", "No local fixture supplied; skipping device fixture measurement")
            return
        }
        val measurer = AndroidTextMeasurer()
        val codec = com.xnotes.format.CanvasCodec(AndroidImageCodec(), measurer)
        val doc = fixture.inputStream().use { codec.read(it) }
        val shape = doc.items.filterIsInstance<ShapeItem>().first { it.label != null }
        val original = shape.snapshotGeometry()
        val anchor = shape.start
        val times = ArrayList<Double>()
        for (i in 0..23) {
            shape.restoreGeometry(original)
            shape.applyTransform(com.xnotes.core.geometry.Affine.scaleAbout(anchor, 0.45 + i * .04, 1.0))
            var start = System.nanoTime()
            val frame = shape.labelRect()!!
            val layoutMs = (System.nanoTime() - start) / 1e6
            start = System.nanoTime()
            val lines = measurer.outlineLines(shape.label!!.text, shape.label!!.font, frame.w, ShapeLabel.FLAGS)
            val outlineMs = (System.nanoTime() - start) / 1e6
            start = System.nanoTime()
            val parts = ShapeTessellator.labelParts(shape)
            val labelMs = (System.nanoTime() - start) / 1e6
            start = System.nanoTime()
            val host = ShapeTessellator.tessellate(shape)
            val hostMs = (System.nanoTime() - start) / 1e6
            start = System.nanoTime()
            val bounds = shape.paintBounds()
            val boundsMs = (System.nanoTime() - start) / 1e6
            times += layoutMs + outlineMs + labelMs + hostMs + boundsMs
            Log.i("XnotesReflow", "stage $i: layout=$layoutMs outline=$outlineMs label=$labelMs host=$hostMs bounds=$boundsMs " +
                "contours=${lines.sumOf { it.contours.size }} segments=${lines.sumOf { it.contours.sumOf { c -> c.segments.size } }} " +
                "vertices=${parts.sumOf { it.mesh.vertexCount }} hostParts=${host.size} boundsW=${bounds.w}")
        }
        val sorted = times.sorted()
        Log.i("XnotesReflow", "device fixture chars=${shape.label!!.text.length} font=${shape.label!!.font.face.id}; " +
            "median=%.2fms p95=%.2fms max=%.2fms".format(java.util.Locale.US,
                sorted[sorted.size / 2], sorted[(sorted.size * .95).toInt()], sorted.last()))
    }

    fun testWindingMeshMatchesNativeTextCoverage() {
        val text = "Ação, café, office ffi. abc אבג 123. Saúde e educação. ".repeat(4)
        for (face in listOf(FontFace.MONO, FontFace.SANS)) {
            val font = FontSpec(13.0, face)
            val shape = ShapeItem(ShapeKind.RECTANGLE, Pt.ZERO, Pt(600.0, 200.0), Rgba(0, 0, 0, 0), 0.0)
            shape.label = ShapeLabel(text, TextStyle(Rgba(0, 0, 0), 13.0, face), AndroidTextMeasurer())
            val frame = shape.labelRect()!!
            fun raster(actual: Boolean): IntArray {
                val bitmap = Bitmap.createBitmap(1300, 1000, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(2f, 2f)
                canvas.translate(20f, 150f)
                val paint = Paint()
                if (actual) {
                    bitmap.recycle()
                    return gpuRaster(shape)
                } else {
                    canvas.translate(frame.left.toFloat(), frame.top.toFloat())
                    canvas.drawPath(nativePath(text, font, frame.w, ShapeLabel.FLAGS), paint)
                }
                return IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    java.io.File(instrumentation.targetContext.cacheDir, "reflow-${face.id}-$actual.png").outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bitmap.recycle()
                }
            }
            val actual = raster(true)
            val expected = raster(false)
            // CPU and GPU use different edge antialiasing. Compare stable interior pixels and
            // total area, allowing a one-device-pixel boundary band at this 2x test resolution.
            var mismatched = 0
            var interior = 0
            for (y in 1 until 999) for (x in 1 until 1299) {
                val i = y * 1300 + x
                val ink = (expected[i] ushr 24) >= 128
                var stable = true
                for (dy in -1..1) for (dx in -1..1) {
                    if (((expected[i + dy * 1300 + dx] ushr 24) >= 128) != ink) stable = false
                }
                if (stable) {
                    if (ink) interior++
                    if (((actual[i] ushr 24) >= 128) != ink) mismatched++
                }
            }
            val area = expected.count { (it ushr 24) >= 128 }
            val actualArea = actual.count { (it ushr 24) >= 128 }
            assertTrue("Mesh interior mismatch for $face: $mismatched / $interior", mismatched.toDouble() / interior.coerceAtLeast(1) < .005)
            assertTrue("Mesh area mismatch for $face: $actualArea / $area", kotlin.math.abs(actualArea - area).toDouble() / area.coerceAtLeast(1) < .03)
        }
    }

    private fun gpuRaster(shape: ShapeItem): IntArray {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_STENCIL_SIZE, 8, EGL14.EGL_NONE
        ), 0, configs, 0, 1, count, 0))
        assertTrue(count[0] > 0)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1300, EGL14.EGL_HEIGHT, 1000, EGL14.EGL_NONE), 0)
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            android.opengl.GLES30.glViewport(0, 0, 1300, 1000)
            android.opengl.GLES30.glEnable(android.opengl.GLES30.GL_BLEND)
            android.opengl.GLES30.glBlendFunc(android.opengl.GLES30.GL_SRC_ALPHA, android.opengl.GLES30.GL_ONE_MINUS_SRC_ALPHA)
            android.opengl.GLES30.glClearColor(0f, 0f, 0f, 0f)
            android.opengl.GLES30.glClearStencil(0)
            android.opengl.GLES30.glClear(android.opengl.GLES30.GL_COLOR_BUFFER_BIT or android.opengl.GLES30.GL_STENCIL_BUFFER_BIT)
            val scene = com.xnotes.gl.CanvasScene()
            scene.onContextCreated(1)
            scene.upsert(shape, ShapeTessellator.labelParts(shape), shape.paintBounds())
            scene.setOrder(listOf(shape))
            scene.drawContent(com.xnotes.gl.FrameState.EMPTY.copy(zoom = 2.0, scrollX = -20.0,
                scrollY = -150.0, widthPx = 1300, heightPx = 1000))
            val pixels = java.nio.ByteBuffer.allocateDirect(1300 * 1000 * 4)
            android.opengl.GLES30.glReadPixels(0, 0, 1300, 1000, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_UNSIGNED_BYTE, pixels)
            assertEquals(android.opengl.GLES30.GL_NO_ERROR, android.opengl.GLES30.glGetError())
            val result = IntArray(1300 * 1000) { index ->
                val x = index % 1300
                val y = index / 1300
                (pixels.get(((999 - y) * 1300 + x) * 4 + 3).toInt() and 255) shl 24
            }
            Bitmap.createBitmap(result, 1300, 1000, Bitmap.Config.ARGB_8888).also { bitmap ->
                java.io.File(instrumentation.targetContext.cacheDir, "reflow-gpu.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            return result
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun legacyMesh(glyphs: List<VectorContour>): com.xnotes.core.vector.Triangulator.Mesh? {
        val rings = glyphs.map { com.xnotes.core.vector.PathFlattener.flatten(it, .05) }.filter { it.size >= 3 }
        return com.xnotes.core.vector.Triangulator.triangulate(rings, com.xnotes.core.pal.FillRule.NONZERO)
    }

    fun testBenchmarkReflow() {
        val text = "Texto longo com acentuação, palavras e várias linhas para redimensionar. ".repeat(12)
        val font = FontSpec(13.0, FontFace.SANS)
        val measurer = AndroidTextMeasurer()
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt.ZERO, Pt(500.0, 600.0), Rgba(0, 0, 0), 1.0)
        shape.label = ShapeLabel(text, TextStyle(Rgba(0, 0, 0), 13.0, FontFace.SANS), measurer)
        val before = ArrayList<Double>()
        val after = ArrayList<Double>()
        // Both directions exercise new line content and cache reuse across widths.
        val widths = (0..15).map { 500.0 - it * 9 } + (0..15).map { 365.0 + it * 9 }
        for (width in widths) {
            shape.end = Pt(width, 600.0)
            val effectiveWidth = maxOf(shape.localGeometry().textRect.w,
                measurer.lineHeight(font) * ShapeLabel.MIN_WIDTH_LINES)
            var start = System.nanoTime()
            val layout = AndroidText.layout(text, effectiveWidth.toInt(), AndroidText.textPaint(font), ShapeLabel.FLAGS)
            val glyphs = reference(text, font, effectiveWidth, ShapeLabel.FLAGS)
            assertNotNull(legacyMesh(glyphs))
            assertTrue(layout.height > 0)
            before += (System.nanoTime() - start) / 1e6
            start = System.nanoTime()
            assertTrue(ShapeTessellator.labelParts(shape).isNotEmpty())
            after += (System.nanoTime() - start) / 1e6
        }
        fun stats(values: List<Double>): String {
            val sorted = values.sorted()
            return "median=%.2fms p95=%.2fms".format(java.util.Locale.US, sorted[sorted.size / 2], sorted[(sorted.size * .95).toInt()])
        }
        Log.i("XnotesReflow", "reference ${stats(before)}; cached ${stats(after)}; samples=${widths.size}; debug build")
    }
}
