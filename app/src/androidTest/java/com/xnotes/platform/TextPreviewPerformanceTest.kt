package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Trace
import android.test.InstrumentationTestCase
import android.util.Log
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.shapes.ShapeKind
import kotlin.math.ceil
import kotlin.math.sqrt

/** Device-side benchmark, synthetic text only: never reads or changes a user's notes. */
@Suppress("DEPRECATION")
class TextPreviewPerformanceTest : InstrumentationTestCase() {
    fun testPolygonRecognitionAndBoundedLiveTextPreview() {
        val text = ("Estudar exige atenção, disciplina e leitura crítica. " +
            "Um texto importado precisa reorganizar as linhas sem deformar as letras. ")
            .repeat(24).take(1266)
        val font = FontSpec(13.0, FontFace.MONO)
        val style = TextStyle(Rgba(10, 10, 10), 13.0, FontFace.MONO)
        for (kind in listOf(ShapeKind.RECTANGLE, ShapeKind.POLYGON)) {
            val host = ShapeItem(kind, Pt(0.0, 0.0), Pt(420.0, 300.0),
                Rgba(0, 0, 0, 0), 0.0)
            host.label = ShapeLabel(text, style, AndroidTextMeasurer())
            assertTrue("Invisible $kind text must use the optimized quad path", host.isTextOnlyBox())
        }
        val widths = (0 until 48).map { if (it < 24) 390 - it * 4 else 298 + (it - 24) * 4 }
        val elapsedMs = ArrayList<Double>()
        val layoutMs = ArrayList<Double>()
        val bitmapMs = ArrayList<Double>()
        var lastLineEnds: IntArray? = null
        var bitmapCount = 0
        var bitmapBytes = 0L
        // Warm up Android's font system before collecting timings.
        AndroidText.drawLayout(text, 390, font, style.rgba.toArgb(), ShapeLabel.FLAGS)
        for (width in widths) {
            Trace.beginSection("Xnotes.preview.benchmark")
            val began = System.nanoTime()
            try {
                val startedLayout = System.nanoTime()
                val layout = AndroidText.drawLayout(
                    text, width, font, style.rgba.toArgb(), ShapeLabel.FLAGS)
                layoutMs += (System.nanoTime() - startedLayout) / 1e6
                val lineEnds = IntArray(layout.lineCount) { layout.getLineEnd(it) }
                if (lastLineEnds == null || !lastLineEnds!!.contentEquals(lineEnds)) {
                    val startedBitmap = System.nanoTime()
                    val w = width.toDouble()
                    val h = layout.height.toDouble().coerceAtLeast(1.0)
                    val scale = minOf(1.0, 1024.0 / w, 1024.0 / h,
                        sqrt(350_000.0 / (w * h)))
                    val bitmap = Bitmap.createBitmap(
                        ceil(w * scale).toInt().coerceAtLeast(1),
                        ceil(h * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(bitmap).apply {
                            scale(scale.toFloat(), scale.toFloat())
                            layout.draw(this)
                        }
                        bitmapBytes += bitmap.allocationByteCount
                        bitmapCount++
                    } finally { bitmap.recycle() }
                    bitmapMs += (System.nanoTime() - startedBitmap) / 1e6
                    lastLineEnds = lineEnds
                }
                elapsedMs += (System.nanoTime() - began) / 1e6
            } finally { Trace.endSection() }
        }
        fun p95(values: List<Double>): Double {
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * 0.95).toInt()]
        }
        fun median(values: List<Double>): Double = values.sorted()[values.size / 2]
        Log.i("XnotesPreviewBench", "chars=${text.length} shapes=rectangle,polygon " +
            "samples=${widths.size} redraws=$bitmapCount approxBitmapMiB=%.2f ".format(
                java.util.Locale.US, bitmapBytes / 1048576.0) +
            "totalMedian=%.2fms totalP95=%.2fms layoutP95=%.2fms rasterP95=%.2fms".format(
                java.util.Locale.US, median(elapsedMs), p95(elapsedMs),
                p95(layoutMs), p95(bitmapMs)))
        assertEquals(1266, text.length)
        assertTrue(bitmapCount in 1..widths.size)
        assertTrue("Cache should avoid at least one bitmap upload", bitmapCount < widths.size)
    }

    /** Reads only an existing private test copy; never logs its text or writes the real note. */
    fun testNativePreviewOnPrivateDeviceFixtureIfPresent() {
        val fixture = java.io.File(instrumentation.targetContext.cacheDir, "reflow-fixture.xcanvas")
        if (!fixture.isFile) {
            Log.i("XnotesPreviewFixture", "No fixture present; benchmark skipped")
            return
        }
        val codec = com.xnotes.format.CanvasCodec(AndroidImageCodec(), AndroidTextMeasurer())
        val document = fixture.inputStream().use { codec.read(it) }
        val shape = document.items.filterIsInstance<ShapeItem>().firstOrNull { it.label != null }
            ?: error("Fixture has no editable shape label")
        assertTrue("Private fixture must enter the native quad path", shape.isTextOnlyBox())
        val label = requireNotNull(shape.label)
        val sourceWidth = shape.box.w
        val widths = (0 until 24).map { (sourceWidth * (0.45 + it * 0.04))
            .coerceAtLeast(label.lineHeight * ShapeLabel.MIN_WIDTH_LINES) }
        val totals = ArrayList<Double>()
        val layouts = ArrayList<Double>()
        val rasters = ArrayList<Double>()
        var lastEnds: IntArray? = null
        var bitmapCount = 0
        var bitmapBytes = 0L
        AndroidText.drawLayout(label.text, sourceWidth.toInt().coerceAtLeast(1),
            label.font, label.style.rgba.toArgb(), ShapeLabel.FLAGS)
        for (w in widths) {
            val begin = System.nanoTime()
            val layoutBegin = System.nanoTime()
            val layout = AndroidText.drawLayout(label.text, w.toInt().coerceAtLeast(1),
                label.font, label.style.rgba.toArgb(), ShapeLabel.FLAGS)
            layouts += (System.nanoTime() - layoutBegin) / 1e6
            val ends = IntArray(layout.lineCount) { layout.getLineEnd(it) }
            if (lastEnds == null || !lastEnds!!.contentEquals(ends)) {
                val rasterBegin = System.nanoTime()
                val h = layout.height.toDouble().coerceAtLeast(1.0)
                val scale = minOf(1.0, 1024.0 / w, 1024.0 / h,
                    sqrt(350_000.0 / (w * h)))
                val bitmap = Bitmap.createBitmap(
                    ceil(w * scale).toInt().coerceAtLeast(1),
                    ceil(h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).apply {
                        scale(scale.toFloat(), scale.toFloat())
                        layout.draw(this)
                    }
                    bitmapBytes += bitmap.allocationByteCount
                    bitmapCount++
                } finally { bitmap.recycle() }
                rasters += (System.nanoTime() - rasterBegin) / 1e6
                lastEnds = ends
            }
            totals += (System.nanoTime() - begin) / 1e6
        }
        fun median(values: List<Double>) = values.sorted()[values.size / 2]
        fun p95(values: List<Double>): Double {
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * 0.95).toInt()]
        }
        Log.i("XnotesPreviewFixture", "chars=${label.text.length} kind=${shape.shape} " +
            "samples=${widths.size} redraws=$bitmapCount approxBitmapMiB=%.2f ".format(
                java.util.Locale.US, bitmapBytes / 1048576.0) +
            "totalMedian=%.2fms totalP95=%.2fms layoutP95=%.2fms rasterP95=%.2fms".format(
                java.util.Locale.US, median(totals), p95(totals), p95(layouts), p95(rasters)))
        assertTrue(bitmapCount in 1..widths.size)
    }

    /** Benchmark the actual canonical-wrap raster path on an existing private fixture copy. */
    fun testCanonicalPreviewOnPrivateDeviceFixtureIfPresent() {
        val fixture = java.io.File(instrumentation.targetContext.cacheDir, "reflow-fixture.xcanvas")
        if (!fixture.isFile) return
        val codec = com.xnotes.format.CanvasCodec(AndroidImageCodec(), AndroidTextMeasurer())
        val doc = fixture.inputStream().use { codec.read(it) }
        val shape = doc.items.filterIsInstance<ShapeItem>().firstOrNull { it.isTextOnlyBox() }
            ?: error("Fixture has no editable text")
        val label = requireNotNull(shape.label)
        val widths = (0 until 24).map { (shape.box.w * (0.45 + it * 0.04))
            .coerceAtLeast(label.lineHeight * ShapeLabel.MIN_WIDTH_LINES) }
        val glyphScale = label.style.pointSize / ShapeLabel.NORMALIZED_POINT_SIZE
        val totals = ArrayList<Double>()
        var lastEnds: IntArray? = null
        var redraws = 0
        var bytes = 0L
        for (width in widths) {
            val start = System.nanoTime()
            val layout = AndroidText.drawLayout(label.text, label.normalizedWidth(width),
                label.normalizedFont, label.style.rgba.toArgb(), ShapeLabel.FLAGS)
            val ends = IntArray(layout.lineCount) { layout.getLineEnd(it) }
            if (lastEnds == null || !lastEnds!!.contentEquals(ends)) {
                val h = (layout.height * glyphScale).coerceAtLeast(1.0)
                val scale = minOf(1.0, 1024.0 / width, 1024.0 / h,
                    sqrt(350_000.0 / (width * h)))
                val bitmap = Bitmap.createBitmap(ceil(width * scale).toInt().coerceAtLeast(1),
                    ceil(h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.scale((scale * glyphScale).toFloat(),
                        (scale * glyphScale).toFloat())
                    layout.draw(canvas)
                    redraws++
                    bytes += bitmap.allocationByteCount
                } finally { bitmap.recycle() }
                lastEnds = ends
            }
            totals += (System.nanoTime() - start) / 1e6
        }
        val sorted = totals.sorted()
        val p95 = sorted[((sorted.size - 1) * 0.95).toInt()]
        Log.i("XnotesCanonicalPreview", "chars=${label.text.length} kind=${shape.shape} " +
            "samples=${widths.size} redraws=$redraws bitmapMiB=%.2f ".format(
                java.util.Locale.US, bytes / 1048576.0) +
            "median=%.2fms p95=%.2fms".format(java.util.Locale.US,
                sorted[sorted.size / 2], p95))
        val narrowWidth = minOf(shape.box.w, label.lineHeight * 5.0)
        val narrowHeight = label.normalizedHeight(narrowWidth)
        val previewScale = minOf(1.0, 1024.0 / narrowWidth, 1024.0 / narrowHeight,
            sqrt(350_000.0 / (narrowWidth * narrowHeight)))
        val finalScale = minOf(1.0, 2048.0 / narrowWidth, 2048.0 / narrowHeight,
            sqrt(2_000_000.0 / (narrowWidth * narrowHeight)))
        Log.i("XnotesNarrowText", "chars=${label.text.length} width=$narrowWidth " +
            "height=$narrowHeight previewScale=$previewScale finalScale=$finalScale")
        assertTrue(redraws in 1..widths.size)
    }

    /** Detect font-metric rewrap during uniform scaling, without exposing fixture text. */
    fun testDiagonalScaleKeepsLineBreaksOnDeviceFixtureIfPresent() {
        val fixture = java.io.File(instrumentation.targetContext.cacheDir, "reflow-fixture.xcanvas")
        if (!fixture.isFile) return
        val codec = com.xnotes.format.CanvasCodec(AndroidImageCodec(), AndroidTextMeasurer())
        val doc = fixture.inputStream().use { codec.read(it) }
        val shape = doc.items.filterIsInstance<ShapeItem>().firstOrNull { it.isTextOnlyBox() }
            ?: error("Fixture has no text-only box")
        val label = requireNotNull(shape.label)
        val base = AndroidText.drawLayout(label.text, shape.box.w.toInt().coerceAtLeast(1),
            label.font, label.style.rgba.toArgb(), ShapeLabel.FLAGS)
        val ends = IntArray(base.lineCount) { base.getLineEnd(it) }
        val scales = listOf(0.65, 0.75, 0.9, 1.0, 1.1, 1.5, 2.0)
            .filter { label.style.pointSize * it >= 8.0 }
        val canonicalBase = AndroidText.drawLayout(label.text,
            label.normalizedWidth(shape.box.w), label.normalizedFont,
            label.style.rgba.toArgb(), ShapeLabel.FLAGS)
        val canonicalEnds = IntArray(canonicalBase.lineCount) { canonicalBase.getLineEnd(it) }
        val canonicalChanges = ArrayList<Double>()
        val changed = ArrayList<Double>()
        for (s in scales) {
            val scaledFont = FontSpec(label.style.pointSize * s, label.style.face)
            val scaledLabel = label.withStyle(label.style.copy(pointSize = label.style.pointSize * s))
            val canonical = AndroidText.drawLayout(label.text,
                scaledLabel.normalizedWidth(shape.box.w * s), scaledLabel.normalizedFont,
                label.style.rgba.toArgb(), ShapeLabel.FLAGS)
            if (!canonicalEnds.contentEquals(IntArray(canonical.lineCount) { canonical.getLineEnd(it) }))
                canonicalChanges += s
            val scaled = AndroidText.drawLayout(label.text,
                (shape.box.w * s).toInt().coerceAtLeast(1), scaledFont,
                label.style.rgba.toArgb(), ShapeLabel.FLAGS)
            val next = IntArray(scaled.lineCount) { scaled.getLineEnd(it) }
            if (!ends.contentEquals(next)) changed += s
        }
        Log.i("XnotesDiagonalWrap", "chars=${label.text.length} scaleSamples=${scales.size} " +
            "legacyChangedAt=$changed canonicalChangedAt=$canonicalChanges " +
            "baselineLines=${ends.size}")
        assertTrue("Canonical corner scaling must retain line breaks: $canonicalChanges",
            canonicalChanges.isEmpty())
    }
}
