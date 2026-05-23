package com.xnotes.platform

import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import com.xnotes.core.pal.FontSpec

/**
 * Shared text layout so measuring and drawing produce identical results
 * (spec 01 §12). A "point size" is converted to page-space pixels at the
 * document authoring DPI (150), so 13pt renders as true 13pt on a 150-DPI page.
 */
object AndroidText {
    /** points -> page pixels at 150 DPI (1pt = 1/72 inch). */
    const val POINTS_TO_PX = 150f / 72f

    private val mono: Typeface = Typeface.MONOSPACE
    private val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun textPaint(font: FontSpec, argb: Int = 0xFF000000.toInt()): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = if (font.bold) monoBold else mono
            textSize = (font.pointSize * POINTS_TO_PX).toFloat()
            color = argb
        }

    fun layout(text: CharSequence, widthPx: Int, paint: TextPaint): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    fun lineHeight(font: FontSpec): Double {
        val fm = textPaint(font).fontMetrics
        return (fm.descent - fm.ascent).toDouble()
    }
}
