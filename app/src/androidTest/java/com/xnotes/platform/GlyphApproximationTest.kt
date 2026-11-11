package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.test.InstrumentationTestCase
import android.util.Log
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.vector.VectorContour

/** Tests the experimental Path.approximate converter against the production PathMeasure. */
class GlyphApproximationTest : InstrumentationTestCase() {

    private fun rebuild(contours: List<VectorContour>): Path = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        for (c in contours) {
            moveTo(c.start.x.toFloat(), c.start.y.toFloat())
            for (s in c.segments) lineTo(s.end.x.toFloat(), s.end.y.toFloat())
            close()
        }
    }

    private fun rasterDifference(original: Path, other: Path): Double {
        fun pixels(p: Path): IntArray {
            val bitmap = Bitmap.createBitmap(1024, 256, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(-1)
            Canvas(bitmap).drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x1000000 })
            val pixels = IntArray(1024 * 256)
            bitmap.getPixels(pixels, 0, 1024, 0, 0, 1024, 256)
            bitmap.recycle()
            return pixels
        }
        val a = pixels(original)
        val b = pixels(other)
        var delta = 0L
        var coverage = 0L
        for (i in a.indices) {
            val aGray = a[i] and 255
            delta += kotlin.math.abs(aGray - (b[i] and 255))
            coverage += 255 - aGray
        }
        return delta.toDouble() / coverage.coerceAtLeast(1L)
    }

    fun testClosedContoursAndGlyphHoles() {
        val synthetic = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(20f, 20f, 210f, 210f, Path.Direction.CW)
            addCircle(100f, 105f, 36f, Path.Direction.CCW)
            addOval(240f, 35f, 370f, 160f, Path.Direction.CW)
            addRect(400f, 40f, 460f, 100f, Path.Direction.CW)
        }
        val cases = ArrayList<Pair<Path, Double>>()
        cases.add(synthetic to 36.0)
        for ((text, spec) in listOf(
            "O0B8aegQp@&" to FontSpec(18.0, FontFace.MONO),
            "office fi ffi café àç 123" to FontSpec(17.0, FontFace.SERIF),
            "AV Waxy ΩЖ 0123" to FontSpec(15.0, FontFace.SANS),
            "O0B8aegQp@" to FontSpec(23.0, FontFace.SANS, bold = true, italic = true),
        )) {
            val paint = AndroidText.textPaint(spec)
            val path = Path()
            paint.getTextPath(text, 0, text.length, 20f, 140f, path)
            path.fillType = Path.FillType.EVEN_ODD
            cases.add(path to paint.textSize.toDouble())
        }
        for ((index, pair) in cases.withIndex()) {
            val (path, size) = pair
            val old = GlyphPaths.contours(path, size)
            for (errorEm in listOf(1.0 / 2000, 1.0 / 500, 1.0 / 250)) {
                val approx = GlyphPaths.approximateContours(path, size, errorEm)
                val oldDiff = rasterDifference(path, rebuild(old))
                val newDiff = rasterDifference(path, rebuild(approx))
                Log.i("PR428_APPROX", "case=" + index + " tolerance=" + errorEm +
                    " contours=" + old.size + "/" + approx.size +
                    " vertices=" + old.sumOf { it.segments.size } + "/" + approx.sumOf { it.segments.size } +
                    " rasterError=" + oldDiff + "/" + newDiff)
                assertEquals("Contour count: case=" + index + " tol=" + errorEm, old.size, approx.size)
                assertTrue("Excess raster difference: case=" + index + " tol=" + errorEm,
                    newDiff < maxOf(0.025, oldDiff * 1.5))
            }
        }
    }

    fun testTouchingClosedContoursStaySeparate() {
        val path = Path().apply {
            moveTo(20f, 20f); lineTo(80f, 20f); lineTo(80f, 80f); close()
            moveTo(20f, 20f); lineTo(120f, 20f); lineTo(120f, 120f); close()
        }
        val legacy = GlyphPaths.contours(path, 36.0)
        val new = GlyphPaths.approximateContours(path, 36.0, 1.0 / 500)
        Log.i("PR428_APPROX", "touchingContours legacy=" + legacy.size + " new=" + new.size)
        assertEquals("Two contours may share an exact vertex", legacy.size, new.size)
    }

}
