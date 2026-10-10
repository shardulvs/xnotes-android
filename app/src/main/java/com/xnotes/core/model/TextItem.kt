package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.HAlign
import com.xnotes.core.pal.MathBox
import com.xnotes.core.pal.MathTypesetter
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer

/**
 * A wrapped plain-text box (spec 02 §5.3). [width] is the wrap width; the box
 * grows to fit its text, so [height] is a *reserved minimum* — the rendered box
 * is `max(height, content height)` tall and never clips. A box created by a tap
 * has `height == 0` (pure auto-height, as before); one dragged out reserves the
 * dragged height. The [measurer] lays out text identically for bounds and paint.
 */
class TextItem(
    var pos: Pt,
    var width: Double = DEFAULT_WIDTH,
    var height: Double = 0.0,
    var text: String = "",
    var rgba: Rgba = DEFAULT_COLOR,
    var pointSize: Double = DEFAULT_POINT_SIZE,
    var face: FontFace = DEFAULT_FACE,
    private val measurer: TextMeasurer,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var strike: Boolean = false,
    var align: HAlign = HAlign.LEFT,
    /** [text] is LaTeX, drawn as a formula (the handwritten-maths add-on makes these). */
    var math: Boolean = false,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true
    override var locked = false
    override var link: String? = null

    val font get() = FontSpec(pointSize, face, bold, italic)

    /** Layout flags carrying this box's decorations and alignment. */
    private fun flags(): TextFlags =
        TextFlags(wordWrap = true, alignLeft = true, alignTop = true, underline = underline, strike = strike, hAlign = align)

    /** Height the current text needs at the current width (empty ⇒ one line). */
    fun contentHeight(): Double = measurer.measure(text.ifEmpty { " " }, font, width, flags()).h

    /** The formula's size, or null when this is not maths or the LaTeX cannot be set (then it shows as text). */
    fun mathBox(): MathBox? = if (math && text.isNotEmpty()) mathTypesetter?.measure(text, pointSize, display = true) else null

    override fun bounds(): Rect {
        mathBox()?.let { return Rect(pos.x, pos.y, it.width, it.height) }
        return Rect(pos.x, pos.y, width, maxOf(height, contentHeight()))
    }

    override fun paint(r: Renderer) {
        if (text.isEmpty()) return
        mathBox()?.let {
            r.drawMath(text, pos.x, pos.y + it.ascent, pointSize, rgba, display = true)
            return
        }
        r.drawText(text, bounds(), font, rgba, flags())
    }

    override fun translate(dx: Double, dy: Double) {
        pos = Pt(pos.x + dx, pos.y + dy)
    }

    override fun contains(p: Pt): Boolean = bounds().contains(p)

    override fun centroid(): Pt = bounds().center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        bounds().distanceTo(Pt(cx, cy)) <= radius

    override fun geometry(): GeoHandle = TextHandle(pos, width, height)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is TextHandle) {
            pos = handle.pos
            width = handle.width
            height = handle.height
        }
    }

    override fun snapshotGeometry(): GeometrySnapshot = TextSnapshot(pos, width, height, pointSize)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is TextSnapshot) return
        pos = snap.pos
        width = snap.width
        height = snap.height
        pointSize = snap.pointSize
    }

    /** Text never rotates. A uniform (corner) scale grows the wrap width, reserved height, and font
     *  size together; a single-axis (edge) scale changes only the wrap width or the reserved height
     *  and leaves the font size alone. The vertical scale reserves at least the current content
     *  height first, so dragging the bottom edge of an auto-height box actually grows it. */
    override fun applyTransform(t: Affine) {
        pos = t.apply(pos)
        width *= t.scaleX
        if (t.scaleY != 1.0) height = maxOf(height, contentHeight()) * t.scaleY
        if (t.isUniformScale) pointSize *= t.scaleX
    }

    companion object {
        const val KIND = "text"

        /** Sets formula boxes; installed by the app once the LaTeX renderer exists. */
        var mathTypesetter: MathTypesetter? = null
        const val DEFAULT_WIDTH = 300.0
        const val DEFAULT_POINT_SIZE = 13.0
        val DEFAULT_FACE = FontFace.MONO
        val DEFAULT_COLOR = Rgba(236, 236, 236, 255)
    }
}

/** A snapshot of a text box's restylable properties (colour, size, face, decorations) for undo. */
data class TextStyle(
    val rgba: Rgba,
    val pointSize: Double,
    val face: FontFace,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val align: HAlign = HAlign.LEFT,
) {
    fun applyTo(t: TextItem) {
        t.rgba = rgba
        t.pointSize = pointSize
        t.face = face
        t.bold = bold
        t.italic = italic
        t.underline = underline
        t.strike = strike
        t.align = align
    }

    companion object {
        fun of(t: TextItem) = TextStyle(t.rgba, t.pointSize, t.face, t.bold, t.italic, t.underline, t.strike, t.align)
    }
}

/** Snapshot of a text box's transformable geometry (position, wrap width, reserved height, size). */
private data class TextSnapshot(
    val pos: Pt,
    val width: Double,
    val height: Double,
    val pointSize: Double,
) : GeometrySnapshot
