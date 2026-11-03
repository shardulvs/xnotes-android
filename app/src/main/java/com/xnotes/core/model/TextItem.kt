package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.HAlign
import com.xnotes.core.pal.MathBox
import com.xnotes.core.pal.MathTypesetter
import com.xnotes.core.pal.Pen
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
    override val mirrorable = false
    override val resizable = true
    override var locked = false
    override var link: String? = null
    override var group: Int? = null
    override var sync: SyncMark? = null

    val font get() = FontSpec(pointSize, face, bold, italic)

    /** Layout flags carrying this box's decorations and alignment. */
    private fun flags(): TextFlags =
        TextFlags(wordWrap = true, alignLeft = true, alignTop = true, underline = underline, strike = strike, hAlign = align)

    /** A link to a stored document draws a paperclip before its text, this wide. */
    private val inset: Double get() = if (link?.startsWith(DOCUMENT_LINK) == true) docLinkInset(pointSize) else 0.0

    /** Height the current text needs at the current width (empty ⇒ one line). */
    fun contentHeight(): Double = measurer.measure(text.ifEmpty { " " }, font, width - inset, flags()).h

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
        val box = bounds()
        val inset = inset
        if (inset > 0.0) paintPaperclip(r)
        r.drawText(text, Rect(box.x + inset, box.y, box.w - inset, box.h), font, rgba, flags())
    }

    private fun paintPaperclip(r: Renderer) {
        val k = pointSize / 24.0
        val top = pos.y + pointSize * 0.15
        r.strokePolyline(List(PAPERCLIP.size / 2) { Pt(pos.x + PAPERCLIP[2 * it] * k, top + PAPERCLIP[2 * it + 1] * k) }, Pen(rgba, 2.0 * k, cosmetic = false))
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

        /** The [link] prefix of a link to a stored document. */
        const val DOCUMENT_LINK = "xnotes-doc:"

        fun docLinkInset(pointSize: Double) = pointSize * 1.3

        /** The toolbar's Insert paperclip (Feather), flattened on its 24-unit grid. */
        private val PAPERCLIP = doubleArrayOf(
            21.44, 11.05, 12.25, 20.24, 11.79, 20.65, 11.31, 21.01, 10.26, 21.56, 9.15, 21.89, 8.01, 22.00, 6.86, 21.89,
            5.75, 21.56, 4.70, 21.01, 4.22, 20.65, 3.76, 20.24, 3.35, 19.78, 2.99, 19.30, 2.44, 18.25, 2.11, 17.14,
            2.00, 16.00, 2.11, 14.85, 2.44, 13.74, 2.99, 12.69, 3.35, 12.21, 3.76, 11.75, 12.95, 2.56, 13.58, 2.05,
            14.27, 1.68, 15.02, 1.46, 15.78, 1.39, 16.54, 1.46, 17.29, 1.68, 17.98, 2.05, 18.61, 2.56, 19.12, 3.19,
            19.49, 3.88, 19.71, 4.63, 19.78, 5.39, 19.71, 6.15, 19.49, 6.90, 19.12, 7.59, 18.61, 8.22, 9.41, 17.41,
            9.10, 17.67, 8.75, 17.85, 8.38, 17.96, 7.99, 18.00, 7.61, 17.96, 7.24, 17.85, 6.89, 17.67, 6.58, 17.41,
            6.32, 17.10, 6.14, 16.75, 6.03, 16.38, 5.99, 16.00, 6.03, 15.61, 6.14, 15.24, 6.32, 14.89, 6.58, 14.58,
            15.07, 6.10,
        )
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
