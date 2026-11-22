package com.xnotes.core.pal

import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.VectorContour

/** Font vertical metrics in content px; both values are positive distances from the baseline. */
data class LineMetrics(val ascent: Double, val descent: Double) {
    val height: Double get() = ascent + descent
}

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

    /** Baseline metrics for [font]; ascent + descent equals [lineHeight] by construction. */
    fun metrics(font: FontSpec): LineMetrics

    /**
     * Per-character advance widths of [text] in content px, using the same paint
     * [Renderer.drawTextRun] draws with, so prefix sums match drawn glyph positions.
     */
    fun advances(text: String, font: FontSpec): DoubleArray

    /**
     * [text] laid out as [measure] lays it out, as closed glyph outlines from the block's top-left:
     * how the infinite canvas, which draws only triangles, sets a shape's label. Empty when the
     * host cannot outline text.
     */
    fun outline(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags = TextFlags()): List<VectorContour> = emptyList()
}

/**
 * What a text box needs of its host beyond measuring. A measurer that also implements this hands
 * the boxes it builds their maths and the colour text following the theme takes.
 */
interface BoxTextHost {
    val math: MathTypesetter? get() = null

    /** The colour of text with none of its own, or null for the format's built-in. */
    fun autoColor(): com.xnotes.core.model.Rgba? = null
}
