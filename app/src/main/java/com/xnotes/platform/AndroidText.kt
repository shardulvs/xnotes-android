package com.xnotes.platform

import android.text.StaticLayout
import android.text.TextPaint
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextFlags

/**
 * Shared text layout so measuring and drawing produce identical results
 * (spec 01 §12). A "point size" is converted to page-space pixels at the
 * document authoring DPI (150), so 13pt renders as true 13pt on a 150-DPI page.
 */
object AndroidText {
    /** points -> page pixels at 150 DPI (1pt = 1/72 inch). */
    const val POINTS_TO_PX = 150f / 72f

    fun textPaint(font: FontSpec, argb: Int = 0xFF000000.toInt()): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            val r = FontCatalog.resolve(font.face, font.bold, font.italic)
            typeface = r.typeface
            if (r.fakeBold) isFakeBoldText = true
            if (r.fakeItalic) textSkewX = -0.25f
            textSize = (font.pointSize * POINTS_TO_PX).toFloat()
            color = argb
        }

    fun layout(text: CharSequence, widthPx: Int, paint: TextPaint, flags: TextFlags = TextFlags()): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(if (flags.alignLeft) android.text.Layout.Alignment.ALIGN_NORMAL else android.text.Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    /** Drawing selected text happens every frame, even without a text/layout change. Keep a small
     * per-thread cache: StaticLayout and its TextPaint are not shared across thumbnail/UI threads.
     * A width change produces a new layout; revisiting an integer width can reuse it. */
    private data class DrawKey(val text: String, val width: Int, val font: FontSpec, val color: Int, val flags: TextFlags)
    private class DrawCache {
        val layouts = LinkedHashMap<DrawKey, StaticLayout>(16, 0.75f, true)
        var chars = 0
        fun get(key: DrawKey): StaticLayout {
            layouts[key]?.let { return it }
            val result = layout(key.text, key.width, textPaint(key.font, key.color), key.flags)
            // Don't retain exceptionally large selections, and cap both entry count and text budget.
            if (key.text.length > 16_000) return result
            layouts[key] = result
            chars += key.text.length
            while (layouts.size > 12 || chars > 48_000) {
                val oldest = layouts.entries.iterator()
                if (!oldest.hasNext()) break
                val entry = oldest.next()
                chars -= entry.key.text.length
                oldest.remove()
            }
            return result
        }
    }
    private val drawCache = ThreadLocal.withInitial { DrawCache() }

    fun drawLayout(text: String, widthPx: Int, font: FontSpec, color: Int, flags: TextFlags): StaticLayout =
        drawCache.get()!!.get(DrawKey(text, widthPx.coerceAtLeast(1), font, color, flags))

    /** The height [text] actually occupies laid out at [widthPx]: one line height per wrapped line. */
    fun blockHeight(text: CharSequence, widthPx: Int, font: FontSpec): Double =
        layout(text, widthPx, textPaint(font)).height.toDouble()

    fun lineHeight(font: FontSpec): Double {
        val fm = textPaint(font).fontMetrics
        return (fm.descent - fm.ascent).toDouble()
    }
}
