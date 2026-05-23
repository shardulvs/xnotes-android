package com.xnotes.core.pal

import com.xnotes.core.geometry.Rect

/**
 * Lays out text identically whether measuring (for bounds / hit-testing) or
 * drawing (spec 01 §12). Text-box bounds, painting and the editor overlay MUST
 * use the same font and wrapping so measured bounds match drawn glyphs.
 */
interface TextMeasurer {
    /**
     * The wrapped bounding box of [text] at [wrapWidth]. The width is fixed to
     * [wrapWidth]; the height follows the text. An empty string measures as one
     * line tall (the spec's one-space probe).
     */
    fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags = TextFlags()): Rect

    fun lineHeight(font: FontSpec): Double
}
