package com.xnotes.platform

import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer

/** Measures text with the same layout pipeline the renderer draws with. */
class AndroidTextMeasurer : TextMeasurer {

    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect {
        val probe = text.ifEmpty { " " }
        val paint = AndroidText.textPaint(font)
        val layout = AndroidText.layout(probe, wrapWidth.toInt(), paint)
        // Width is fixed to the wrap width; height follows the text.
        return Rect(0.0, 0.0, wrapWidth, layout.height.toDouble())
    }

    override fun lineHeight(font: FontSpec): Double = AndroidText.lineHeight(font)
}
