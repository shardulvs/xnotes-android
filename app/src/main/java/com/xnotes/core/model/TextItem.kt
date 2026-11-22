package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.BoxTextHost
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.pdf.FlowLinks
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.FlowPainter
import com.xnotes.core.text.PageBox
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TextFlow
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** A text box's own frame: what is painted behind and around its text. All off by default. */
data class BoxStyle(
    val fill: Rgba? = null,
    val border: Rgba? = null,
    val borderWidth: Double = 0.0,
    val corner: Double = 0.0,
) {
    /** Whether the frame draws an outline of its own. */
    val outlined: Boolean get() = border != null && borderWidth > 0.0

    /** Whether there is anything to see of the frame at all. */
    val visible: Boolean get() = fill != null || outlined

    companion object {
        val NONE = BoxStyle()
        const val MAX_BORDER = 12.0
        const val MAX_CORNER = 48.0
    }
}

/** What a new text box starts as: the Text box tool's defaults. A null [color] follows the theme. */
data class BoxDefaults(
    val face: FontFace = FontFace.SANS,
    val sizePt: Double = TextFlow.DEFAULT_SIZE_PT,
    val color: Rgba? = null,
    val box: BoxStyle = BoxStyle.NONE,
)

/** What a text box last set: its lines, the size they take, and a copy of the text no edit can reach. */
class BoxContent(
    val frame: FlowFrame,
    val w: Double,
    val h: Double,
    val frozen: TextFlow,
    val plain: String,
)

/**
 * A box of rich text placed on the page: a small [flow] of its own, set by the same engine as the
 * note's flowing text. [pos] is the top-left of the text and [width] what it wraps at; the box
 * stands [PAD_X] and [PAD_Y] out from that, so a file written before boxes had an inset still puts
 * every word where it was. With [autoWidth] nothing wraps and the box is as wide as its text.
 *
 * The flow is only edited on the main thread, and [reflow] must follow every edit: it publishes
 * [content], which is all that painting, bounds and saving read, on any thread.
 */
class TextItem(
    var pos: Pt,
    width: Double,
    autoWidth: Boolean,
    val flow: TextFlow,
    private val measurer: TextMeasurer,
) : CanvasItem, Resizable {

    /** A box of plain [text] in one style, as files before rich text held and tests build. */
    constructor(
        pos: Pt,
        width: Double = DEFAULT_WIDTH,
        text: String = "",
        rgba: Rgba? = DEFAULT_COLOR,
        pointSize: Double = DEFAULT_POINT_SIZE,
        face: FontFace = DEFAULT_FACE,
        measurer: TextMeasurer,
        autoWidth: Boolean = false,
    ) : this(pos, width, autoWidth, plainFlow(text, rgba, pointSize, face), measurer)

    override val kind = KIND
    override val mirrorable = false
    override val resizable = true
    override var locked = false
    override var group: Int? = null
    override var sync: SyncMark? = null

    private val host = measurer as? BoxTextHost
    private val layout = FlowLayout(measurer, host?.math).also { l -> l.autoColor = { host?.autoColor() } }

    /** The formula showing its source because the caret is in it, as the layout asks while the box is typed in. */
    var revealedMath: (Paragraph) -> Int
        get() = layout.revealedMath
        set(value) {
            layout.revealedMath = value
        }

    private var wrapWidth = width
    private var hugs = autoWidth

    /** The wrap width of the text; ignored while [autoWidth] is on. */
    var width: Double
        get() = wrapWidth
        set(value) = wrap(value, hugs)

    /** Whether the box is as wide as its text, with nothing wrapped. */
    var autoWidth: Boolean
        get() = hugs
        set(value) = wrap(wrapWidth, value)

    var box: BoxStyle = BoxStyle.NONE

    @Volatile
    var content: BoxContent = typeset()
        private set

    /** The base text colour, or null to follow the host's theme. */
    var color: Rgba?
        get() = flow.defaultColor
        set(value) {
            flow.defaultColor = value
            reflow()
        }

    /** The base colour as drawn: [color], or what following the theme resolves to. */
    var rgba: Rgba
        get() = content.frame.defaultColor
        set(value) {
            color = value
        }

    var pointSize: Double
        get() = flow.defaultSizePt
        set(value) {
            flow.defaultSizePt = value
            reflow()
        }

    var face: FontFace
        get() = flow.defaultFace
        set(value) {
            flow.defaultFace = value
            reflow()
        }

    /** The words alone. Setting them drops every style but the box's base one. */
    var text: String
        get() = content.plain
        set(value) {
            flow.paragraphs.clear()
            flow.paragraphs.addAll(plainParagraphs(value))
            reflow()
        }

    val font get() = FontSpec(pointSize, face)

    /** Lay the flow out afresh and publish it. Call after anything that changes the text or its styles. */
    fun reflow() {
        content = typeset()
    }

    /**
     * [reflow] after an edit of the text: a box hugging its text grows away from the edge its
     * first line is aligned to, so a centred label stays centred where it was put.
     */
    fun retype() {
        val before = content.w
        reflow()
        if (!hugs) return
        val grown = content.w - before
        when (flow.paragraphs.firstOrNull()?.align) {
            ParaAlign.CENTER -> pos = Pt(pos.x - grown / 2.0, pos.y)
            ParaAlign.RIGHT -> pos = Pt(pos.x - grown, pos.y)
            else -> Unit
        }
    }

    /** Set the wrap width and whether the box hugs its text, in one layout. */
    fun wrap(width: Double, autoWidth: Boolean) {
        if (width == wrapWidth && autoWidth == hugs) return
        wrapWidth = width
        hugs = autoWidth
        reflow()
    }

    private fun typeset(): BoxContent {
        if (flow.paragraphs.isEmpty()) flow.paragraphs.add(Paragraph())
        val w = if (hugs) {
            maxOf(ceil(layout.naturalWidth(flow)) + HUG_SLACK, MIN_TEXT_WIDTH)
        } else {
            wrapWidth.coerceAtLeast(MIN_TEXT_WIDTH)
        }
        val frame = layout.layout(flow, listOf(PageBox(w, TALL)), LAYOUT_DPI)
        val h = frame.lastLineEnd()?.second ?: layout.defaultSlotHeight(flow)
        return BoxContent(frame, w, h, flow.deepCopy(), flow.plainText())
    }

    /** Where the text itself sits, inside the inset. */
    fun textRect(): Rect = content.let { Rect(pos.x, pos.y, it.w, it.h) }

    override fun bounds(): Rect = content.let { Rect(pos.x - PAD_X, pos.y - PAD_Y, it.w + 2 * PAD_X, it.h + 2 * PAD_Y) }

    override fun paintBounds(): Rect = if (box.outlined) bounds().outset(box.borderWidth / 2.0) else bounds()

    override fun paint(r: Renderer) {
        val c = content
        paintFrame(r)
        r.save()
        r.translate(pos.x, pos.y)
        FlowPainter.paintPage(r, c.frame, 0, Rect(0.0, 0.0, c.w, c.h), marks = false)
        r.restore()
        // A backend writing real text also gets the box's links, each a piece per line it runs over.
        if (!r.writesText) return
        val links = FlowLinks.find(c.frozen)
        if (links.isEmpty()) return
        val order = links.values.flatten()
        for (placed in FlowLinks.place(c.frozen, c.frame, links)) {
            r.linkArea(order.indexOfFirst { it === placed.link }, placed.rect.translate(pos.x, pos.y), placed.uri)
        }
    }

    private fun paintFrame(r: Renderer) {
        val s = box
        if (!s.visible) return
        val b = bounds()
        val corner = s.corner.coerceIn(0.0, minOf(b.w, b.h) / 2.0)
        if (corner <= 0.0) {
            s.fill?.let { r.fillRect(b, it) }
            if (s.outlined) r.strokeRect(b, Pen(s.border!!, s.borderWidth, cosmetic = false))
            return
        }
        val ring = roundedRect(b, corner)
        s.fill?.let { r.fillPolygon(ring, it) }
        if (s.outlined) r.strokePolygon(ring, Pen(s.border!!, s.borderWidth, cosmetic = false))
    }

    override fun translate(dx: Double, dy: Double) {
        pos = Pt(pos.x + dx, pos.y + dy)
    }

    override fun contains(p: Pt): Boolean = bounds().contains(p)

    override fun centroid(): Pt = bounds().center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        bounds().distanceTo(Pt(cx, cy)) <= radius

    override fun geometry(): GeoHandle = TextHandle(pos, content.w, autoWidth)

    override fun setGeometry(handle: GeoHandle) {
        if (handle !is TextHandle) return
        pos = handle.pos
        wrap(if (handle.autoWidth) wrapWidth else handle.width, handle.autoWidth)
    }

    override fun snapshotGeometry(): GeometrySnapshot =
        TextSnapshot(pos, width, autoWidth, flow.defaultSizePt, flow.paragraphs.flatMap { p -> p.runs.map { it.style.sizePt } })

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is TextSnapshot) return
        pos = snap.pos
        flow.defaultSizePt = snap.baseSize
        val runs = flow.paragraphs.flatMap { p -> p.runs.map { p to it } }
        if (runs.size == snap.runSizes.size) {
            for ((i, pr) in runs.withIndex()) {
                val (para, run) = pr
                if (run.style.sizePt != snap.runSizes[i]) {
                    run.style = run.style.copy(sizePt = snap.runSizes[i])
                    para.touch()
                }
            }
        }
        wrapWidth = snap.width
        hugs = snap.autoWidth
        reflow()
    }

    /**
     * Text never rotates. A uniform (corner) scale grows every text size with the box; a scale
     * along x alone sets the wrap width, which ends the box hugging its text. Height always
     * follows the text, so a scale along y alone only moves the box. The inset does not scale.
     */
    override fun applyTransform(t: Affine) {
        val b = bounds()
        val corner = t.apply(Pt(b.left, b.top))
        pos = Pt(corner.x + PAD_X, corner.y + PAD_Y)
        if (t.isUniformScale) {
            if (t.scaleX == 1.0) return
            scaleText(t.scaleX)
            if (!hugs) wrapWidth = (b.w * t.scaleX - 2 * PAD_X).coerceAtLeast(MIN_TEXT_WIDTH)
            reflow()
        } else if (t.scaleX != 1.0) {
            wrap((b.w * t.scaleX - 2 * PAD_X).coerceAtLeast(MIN_TEXT_WIDTH), false)
        }
    }

    /** Multiply every text size in the box by [s]; the caller sets the text afresh. */
    private fun scaleText(s: Double) {
        flow.defaultSizePt *= s
        for (para in flow.paragraphs) {
            var any = false
            for (run in para.runs) {
                val size = run.style.sizePt ?: continue
                run.style = run.style.copy(sizePt = size * s)
                any = true
            }
            if (any) para.touch()
        }
    }

    /** An independent copy: its own flow, the same place, look and frame. */
    fun copy(): TextItem = TextItem(pos, width, autoWidth, flow.deepCopy(), measurer).also { it.box = box }

    companion object {
        const val KIND = "text"
        const val DEFAULT_WIDTH = 300.0
        const val DEFAULT_POINT_SIZE = 13.0
        val DEFAULT_FACE = FontFace.MONO
        val DEFAULT_COLOR = Rgba(236, 236, 236, 255)

        /** The inset between the box's edge and its text: about 2 mm of paper, a little less above and below. */
        const val PAD_X = 12.0
        const val PAD_Y = 8.0

        /** The narrowest text a box holds, which is also what an empty one takes. */
        const val MIN_TEXT_WIDTH = FlowLayout.MIN_LINE_WIDTH

        /** Room past the measured text of a hugging box, so rounding never wraps its last word. */
        private const val HUG_SLACK = 1.0

        /** A page no box runs off the end of. */
        private const val TALL = 1.0e7

        /** Margins are zero, so the dpi they would be read at does not matter. */
        private const val LAYOUT_DPI = 150

        private const val CORNER_STEPS = 6

        private fun plainParagraphs(text: String): List<Paragraph> =
            text.split('\n').map { line -> if (line.isEmpty()) Paragraph() else Paragraph(mutableListOf(Run(line))) }

        private fun plainFlow(text: String, rgba: Rgba?, pointSize: Double, face: FontFace): TextFlow = boxFlow(rgba, pointSize, face).also {
            it.paragraphs.addAll(plainParagraphs(text))
        }

        /** An empty flow set up for a box: no margins, the box's base style. */
        fun boxFlow(rgba: Rgba?, pointSize: Double, face: FontFace): TextFlow = TextFlow().also {
            it.margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
            it.defaultColor = rgba
            it.defaultSizePt = pointSize
            it.defaultFace = face
        }

        /** [b] with its corners rounded to [radius], as a closed ring. */
        internal fun roundedRect(b: Rect, radius: Double): List<Pt> {
            val out = ArrayList<Pt>(4 * (CORNER_STEPS + 1))
            fun arc(cx: Double, cy: Double, from: Double) {
                for (k in 0..CORNER_STEPS) {
                    val a = from + (PI / 2.0) * k / CORNER_STEPS
                    out += Pt(cx + radius * cos(a), cy + radius * sin(a))
                }
            }
            arc(b.right - radius, b.top + radius, -PI / 2.0)
            arc(b.right - radius, b.bottom - radius, 0.0)
            arc(b.left + radius, b.bottom - radius, PI / 2.0)
            arc(b.left + radius, b.top + radius, PI)
            return out
        }
    }
}

/** A snapshot of a text box's restylable properties (colour, size, face) for undo. */
data class TextStyle(val rgba: Rgba, val pointSize: Double, val face: FontFace) {
    fun applyTo(t: TextItem) {
        // A box following the theme stays following it unless the colour really changed.
        if (t.rgba != rgba) t.rgba = rgba
        if (t.pointSize != pointSize) t.pointSize = pointSize
        if (t.face != face) t.face = face
    }

    companion object {
        fun of(t: TextItem) = TextStyle(t.rgba, t.pointSize, t.face)
    }
}

/** Snapshot of a text box's transformable geometry: place, wrap width, and every text size a scale changes. */
private class TextSnapshot(
    val pos: Pt,
    val width: Double,
    val autoWidth: Boolean,
    val baseSize: Double,
    val runSizes: List<Double?>,
) : GeometrySnapshot
