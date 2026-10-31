package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.shapes.FreePath
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.vector.VectorContour
import kotlin.math.roundToInt

/**
 * A shape's typed label: plain text in a text box's [style], wrapped to the kind's text rect and
 * centred on it, turning with the shape. Immutable, so an edit swaps in a new one and undo can hold
 * both. The [measurer] lays it out exactly as the screen and the PDF set it.
 */
class ShapeLabel(val text: String, val style: TextStyle, val measurer: TextMeasurer) {

    val font: FontSpec get() = FontSpec(style.pointSize, style.face)

    /** One line's height, which also bounds how narrow the label may wrap. */
    val lineHeight: Double by lazy { measurer.lineHeight(font) }

    /** How wide the text sets unwrapped: its longest line. */
    val naturalWidth: Double by lazy { text.split('\n').maxOf { measurer.advances(it, font).sum() } }

    @Volatile
    private var measured: Measured? = null

    private class Measured(val width: Double, val height: Double)

    /** How tall the text sets wrapped at [width]; the last answer is kept, since every repaint asks. */
    fun height(width: Double): Double {
        measured?.let { if (it.width == width) return it.height }
        val h = measurer.measure(text.ifEmpty { " " }, font, width, FLAGS).h
        measured = Measured(width, h)
        return h
    }

    /** Editable text boxes use the same line-breaking coordinate system at every font size.
     *  Android's hinting/line breaker otherwise shifts word boundaries during uniform scaling.
     *  Normalizing layout width by font size keeps the logical wrap invariant. */
    fun normalizedWidth(width: Double): Int =
        if (style.pointSize <= 0.0) width.toInt().coerceAtLeast(1)
        else (width * NORMALIZED_POINT_SIZE / style.pointSize)
            .coerceIn(1.0, 32768.0).roundToInt()

    val normalizedFont: FontSpec get() = FontSpec(NORMALIZED_POINT_SIZE, style.face)

    @Volatile private var measuredNormalized: Measured? = null

    fun normalizedHeight(width: Double): Double {
        measuredNormalized?.let { if (it.width == width) return it.height }
        val refWidth = normalizedWidth(width).toDouble()
        val height = measurer.measure(text.ifEmpty { " " }, normalizedFont, refWidth, FLAGS).h *
            style.pointSize / NORMALIZED_POINT_SIZE
        measuredNormalized = Measured(width, height)
        return height
    }

    @Volatile
    private var outlined: Outlined? = null

    private class Outlined(val width: Double, val contours: List<VectorContour>)

    /** The text as glyph outlines wrapped at [width], from its block's top-left; the last answer is kept. */
    fun outline(width: Double): List<VectorContour> {
        outlined?.let { if (it.width == width) return it.contours }
        val c = measurer.outline(text, font, width, FLAGS)
        outlined = Outlined(width, c)
        return c
    }

    /**
     * What a note keeps of the label when an eraser cuts what carries it: a text box centred where
     * the words were ([frame] in the label's own frame, [toPage] placing it), upright since boxes
     * never turn. Null for blank words.
     */
    fun asTextBox(frame: Rect, toPage: (Pt) -> Pt): TextItem? {
        if (text.isBlank()) return null
        val c = toPage(frame.center)
        return TextItem(Pt(c.x - frame.w / 2.0, c.y - frame.h / 2.0), frame.w, 0.0, text, style.rgba, style.pointSize, style.face, measurer)
    }

    /**
     * What a canvas keeps of it: the letters as a filled [ShapeKind.GLYPHS] shape exactly where they
     * were, turned by [turn]. Null when the host cannot outline text.
     */
    fun asGlyphs(frame: Rect, toPage: (Pt) -> Pt, turn: Double): ShapeItem? {
        val glyphs = outline(frame.w)
        if (glyphs.isEmpty()) return null
        val local = FreePath.map(glyphs) { Pt(frame.left + it.x, frame.top + it.y) }
        val lb = FreePath.bounds(local) ?: return null
        if (lb.w <= 0.0 || lb.h <= 0.0) return null
        val c = toPage(lb.center)
        return ShapeItem(
            ShapeKind.GLYPHS,
            Pt(c.x - lb.w / 2.0, c.y - lb.h / 2.0),
            Pt(c.x + lb.w / 2.0, c.y + lb.h / 2.0),
            strokeRgba = style.rgba,
            strokeWidth = GLYPH_PEN,
            fillRgba = style.rgba,
            path = FreePath.normalize(local, lb),
            rotation = turn,
        )
    }

    fun withText(text: String) = ShapeLabel(text, style, measurer)

    fun withStyle(style: TextStyle) = ShapeLabel(text, style, measurer)

    /** Same words in the same style: whether an edit changed anything. */
    fun sameAs(other: ShapeLabel?): Boolean = other != null && other.text == text && other.style == style

    companion object {
        /** Shared reference size for stable line breaking in uniformly scaled editable boxes. */
        const val NORMALIZED_POINT_SIZE = 48.0

        /**
         * A new label's style for [item]: the given type, in its line's colour, unless a solid fill
         * of that colour would swallow it, when black or white reads better on the fill.
         */
        fun styleFor(item: Labelled, pointSize: Double, face: FontFace): TextStyle {
            val outline = item.labelInk.withAlpha(255)
            val fill = item.labelBackdrop
            val swallowed = fill != null && fill.a >= SOLID_FILL_ALPHA && fill.r == outline.r && fill.g == outline.g && fill.b == outline.b
            val color = if (!swallowed) outline else if (outline.luminance() > 0.55) Rgba(0, 0, 0) else Rgba(255, 255, 255)
            return TextStyle(color, pointSize, face)
        }

        /** Drawn text is never stroked; its width only pads its bounds. */
        private const val GLYPH_PEN = 0.5

        /** A fill at least this opaque hides text in its own colour. */
        private const val SOLID_FILL_ALPHA = 160

        /** Wrapped, every line centred. */
        val FLAGS = TextFlags(wordWrap = true, alignLeft = false, alignTop = true)

        /** The narrowest a label wraps, in lines of its own height, however thin its shape. */
        const val MIN_WIDTH_LINES = 2.0
    }
}
