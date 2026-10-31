package com.xnotes.platform

import android.graphics.Path
import android.os.Trace
import android.text.StaticLayout
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pal.TextOutlineLine
import com.xnotes.core.util.WeightedLruCache
import com.xnotes.core.shapes.FreePath
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.vector.VectorContour

/** Measures text with the same layout pipeline the renderer draws with. */
class AndroidTextMeasurer : TextMeasurer {
    private data class LayoutKey(val text: String, val font: FontSpec, val width: Int, val flags: TextFlags)
    private data class LineKey(val text: String, val start: Int, val end: Int, val font: FontSpec)
    private var lastLayout: Pair<LayoutKey, StaticLayout>? = null
    private val lines = WeightedLruCache<LineKey, List<VectorContour>>(200_000) { key, value ->
        key.text.length + value.sumOf { 1 + it.segments.size }
    }

    @Synchronized
    private fun layout(text: String, font: FontSpec, width: Double, flags: TextFlags): StaticLayout {
        val key = LayoutKey(text.ifEmpty { " " }, font, width.toInt().coerceAtLeast(1), flags)
        lastLayout?.let { if (it.first == key) return it.second }
        Trace.beginSection("Xnotes.text.layout")
        try {
            return AndroidText.layout(key.text, key.width, AndroidText.textPaint(font), flags).also {
                lastLayout = key to it
            }
        } finally {
            Trace.endSection()
        }
    }

    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect {
        val layout = layout(text, font, wrapWidth, flags)
        // Width is fixed to the wrap width; height follows the text.
        return Rect(0.0, 0.0, wrapWidth, layout.height.toDouble())
    }

    override fun lineHeight(font: FontSpec): Double = AndroidText.lineHeight(font)

    override fun metrics(font: FontSpec): LineMetrics {
        val fm = AndroidText.textPaint(font).fontMetrics
        // Paint ascent is negative (above baseline); expose both as positive distances.
        return LineMetrics(ascent = -fm.ascent.toDouble(), descent = fm.descent.toDouble())
    }

    override fun outline(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): List<VectorContour> =
        outlineLines(text, font, wrapWidth, flags).flatMap { line ->
            FreePath.map(line.contours) { Pt(it.x + line.origin.x, it.y + line.origin.y) }
        }

    override fun outlineLines(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): List<TextOutlineLine> {
        if (text.isBlank()) return emptyList()
        val layout = layout(text, font, wrapWidth, flags)
        val paint = layout.paint
        val out = ArrayList<TextOutlineLine>(layout.lineCount)
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            var end = layout.getLineEnd(line)
            while (end > start && text[end - 1].isWhitespace()) end--
            if (end <= start) continue
            val key = LineKey(text, start, end, font)
            val contours = lines.getOrPut(key) {
                Trace.beginSection("Xnotes.text.contours")
                try {
                    val path = Path()
                    // Keep the complete line shaping context, including ligatures and combining marks.
                    paint.getTextPath(key.text, key.start, key.end, 0f, 0f, path)
                    GlyphPaths.contours(path, paint.textSize.toDouble())
                } finally {
                    Trace.endSection()
                }
            }
            out += TextOutlineLine(contours, Pt(layout.getLineLeft(line).toDouble(), layout.getLineBaseline(line).toDouble()))
        }
        return out
    }

    override fun advances(text: String, font: FontSpec): DoubleArray {
        if (text.isEmpty()) return DoubleArray(0)
        val widths = FloatArray(text.length)
        AndroidText.textPaint(font).getTextWidths(text, widths)
        return DoubleArray(text.length) { widths[it].toDouble() }
    }
}
