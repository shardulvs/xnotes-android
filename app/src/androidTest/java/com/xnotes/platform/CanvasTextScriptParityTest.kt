package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.test.InstrumentationTestCase
import android.util.Log
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextFlags

/** Check that GL glyph outlines follow the native text preview for real-world scripts. */
class CanvasTextScriptParityTest : InstrumentationTestCase() {
    private val measurer = AndroidTextMeasurer()

    private fun pixels(width: Int, height: Int, draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        draw(Canvas(bitmap))
        val result = IntArray(width * height)
        bitmap.getPixels(result, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return result
    }

    private fun difference(native: IntArray, outlined: IntArray): Double {
        var delta = 0L
        var ink = 0L
        for (i in native.indices) {
            val a = native[i] and 255
            delta += kotlin.math.abs(a - (outlined[i] and 255))
            ink += 255 - a
        }
        return delta.toDouble() / ink.coerceAtLeast(1)
    }

    fun testNativeLayoutVersusGlOutlines() {
        val font = FontSpec(23.0, FontFace.SANS)
        val paint = AndroidText.textPaint(font)
        val cases = linkedMapOf(
            "latin" to "Office ffl AV café ação",
            "combining" to "Cafe\u0301 a\u0303 e\u0327 a\u0301",
            "cjk" to "日本語 中文 문장 테스트",
            "arabic" to "مرحبا بكم في الاختبار",
            "hebrew" to "שלום עולם בדיקה",
            "devanagari" to "नमस्ते दुनिया परीक्षण",
            "emoji" to "Hello 👩🏽‍💻 😀 🇧🇷!",
        )
        for ((name, value) in cases) {
            val layout = AndroidText.layout(value, 950, paint, TextFlags())
            val native = pixels(1024, 280) { layout.draw(it) }
            fun fromOutlines(fast: Boolean): IntArray {
                val contours = if (fast) measurer.outlineFast(value, font, 950.0)
                    else measurer.outline(value, font, 950.0)
                val path = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    for (c in contours) {
                        moveTo(c.start.x.toFloat(), c.start.y.toFloat())
                        for (segment in c.segments) lineTo(segment.end.x.toFloat(), segment.end.y.toFloat())
                        close()
                    }
                }
                return pixels(1024, 280) {
                    it.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
                }
            }
            val original = difference(native, fromOutlines(false))
            val optimized = difference(native, fromOutlines(true))
            Log.i("PR428_SCRIPTS", "$name old_error=$original fast_error=$optimized")
            // The legacy getTextPath pipeline already differs from StaticLayout.
            // This guards against worsening that gap, not pixel-perfect script support.
            assertTrue("$name approximation must not worsen the existing raster gap",
                optimized <= original + 0.025)
        }
    }
}
