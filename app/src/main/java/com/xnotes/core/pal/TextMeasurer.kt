package com.xnotes.core.pal

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.VectorContour

/** Reusable line geometry at the origin, placed by the platform layout. */
class TextOutlineLine(val contours: List<VectorContour>, val origin: Pt)

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
    /** Lines in local coordinates, with stable contour identities when the host caches geometry.
     * The default preserves compatibility with hosts that only provide a complete outline. */
    fun outlineLines(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags = TextFlags()): List<TextOutlineLine> =
        listOf(TextOutlineLine(outline(text, font, wrapWidth, flags), Pt.ZERO))

    fun outline(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags = TextFlags()): List<VectorContour> = emptyList()
}
