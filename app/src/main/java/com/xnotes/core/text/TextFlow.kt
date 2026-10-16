package com.xnotes.core.text

import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import kotlin.math.roundToInt

/** Paragraph alignment. [id] is the stable serialized token. */
enum class ParaAlign(val id: String) {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right"),
    JUSTIFY("justify");

    companion object {
        fun fromId(id: String?): ParaAlign = entries.firstOrNull { it.id == id } ?: LEFT
    }
}

/** What kind of list item a paragraph is, if any. [id] is the stable serialized token. */
enum class ListKind(val id: String) {
    NONE("none"),
    BULLET("bullet"),
    ORDERED("ordered"),
    CHECK("check");

    companion object {
        fun fromId(id: String?): ListKind = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * Immutable character style for a run. Nullable fields inherit the flow defaults
 * ([TextFlow.defaultColor]/[TextFlow.defaultFace]), and a null size the paragraph's
 * ([Paragraph.textSizePt]); [code] renders in [TextFlow.monoFace] with a subtle
 * background. Equality drives run merging, so keep this a value type.
 */
data class CharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val color: Rgba? = null,
    val highlight: Rgba? = null,
    val sizePt: Double? = null,
    val face: FontFace? = null,
    /** Non-null makes the run a tappable hyperlink (rendered link-coloured + underlined). */
    val link: String? = null,
    /** Marks a run whose text is LaTeX, drawn as the formula it sets. */
    val math: Boolean = false,
    /** A [math] run set as display rather than inline: bigger operators, its own line. */
    val mathDisplay: Boolean = false,
) {
    companion object {
        val DEFAULT = CharStyle()
    }
}

/** A maximal stretch of same-styled text inside a paragraph. Mutable, identity-compared. */
class Run(var text: String, var style: CharStyle = CharStyle.DEFAULT) {
    fun deepCopy(): Run = Run(text, style)
}

/**
 * One paragraph of the flow (one line of a code block). Mutable and compared by
 * identity, like [com.xnotes.core.model.CanvasItem]s, so undo commands can hold
 * stable references. All mutation goes through FlowEditor, which bumps [rev].
 */
class Paragraph(
    val runs: MutableList<Run> = mutableListOf(),
    var align: ParaAlign = ParaAlign.LEFT,
    var indent: Int = 0,
    var list: ListKind = ListKind.NONE,
    var checked: Boolean = false,
    /** Non-null marks a code line; "" means plain/unhighlighted code. */
    var codeLang: String? = null,
    /** Non-null makes this paragraph cell text of that table (see [FlowTable]). */
    var table: FlowTable? = null,
    /** True on the first paragraph of each table cell. */
    var cellStart: Boolean = false,
    /** 1..[MAX_HEADING] marks a heading, whose text takes its size from the level; 0 is body text. */
    var headingLevel: Int = 0,
) {
    /** Bumped on any content or style mutation; layout caches key on it. */
    var rev: Int = 0
        internal set

    internal fun touch() {
        rev++
    }

    val length: Int get() = runs.sumOf { it.text.length }

    fun plainText(): String = runs.joinToString("") { it.text }

    /** The size text with none of its own sets at here: the flow's base, scaled for a heading. */
    fun textSizePt(flow: TextFlow): Double =
        if (headingLevel > 0) headingSizePt(headingLevel, flow.defaultSizePt) else flow.defaultSizePt

    /** True when every paragraph-level property is at its default (runs not considered). */
    fun isDefaultStyle(): Boolean =
        align == ParaAlign.LEFT && indent == 0 && list == ListKind.NONE && !checked && codeLang == null &&
            table == null && headingLevel == 0

    /** A copy sharing [table]; [TextFlow.deepCopy] remaps it onto copied tables. */
    fun deepCopy(): Paragraph =
        Paragraph(runs.mapTo(mutableListOf()) { it.deepCopy() }, align, indent, list, checked, codeLang, table, cellStart, headingLevel)
            .also { it.rev = rev }

    companion object {
        const val MAX_INDENT = 6
        const val MAX_HEADING = 6

        // A geometric run at about 1.125 a step, so every level is visibly its own and
        // h1 lands on twice the body size. The tail has to stay above 1.0: a heading
        // that measures the same as body text is only bold, which is not a level.
        private val HEADING_SCALE = doubleArrayOf(2.0, 1.75, 1.55, 1.4, 1.25, 1.1)

        /** A heading's own text: bold, its size left to the level so it follows the base. */
        val HEADING_STYLE = CharStyle(bold = true)

        /** The size heading [level] sets at over [baseSizePt]. */
        fun headingSizePt(level: Int, baseSizePt: Double): Double =
            baseSizePt * HEADING_SCALE[(level - 1).coerceIn(HEADING_SCALE.indices)]
    }
}

/** Flow page margins in millimetres (converted to content px at the document dpi). */
data class FlowMargins(
    val leftMm: Double = DEFAULT_MM,
    val topMm: Double = DEFAULT_MM,
    val rightMm: Double = DEFAULT_MM,
    val bottomMm: Double = DEFAULT_MM,
) {
    companion object {
        const val DEFAULT_MM = 20.0
        const val MIN_MM = 0.0
        const val MAX_MM = 80.0
    }
}

/** A caret position: paragraph index + character offset within that paragraph. */
data class FlowPos(val para: Int, val offset: Int) : Comparable<FlowPos> {
    override fun compareTo(other: FlowPos): Int =
        if (para != other.para) para.compareTo(other.para) else offset.compareTo(other.offset)

    companion object {
        val START = FlowPos(0, 0)
    }
}

/** A (possibly collapsed, possibly reversed) span of the flow between two positions. */
data class FlowRange(val start: FlowPos, val end: FlowPos) {
    val collapsed: Boolean get() = start == end

    /** The same range with [start] <= [end] (selection drags can be backwards). */
    fun normalized(): FlowRange = if (start <= end) this else FlowRange(end, start)

    companion object {
        fun caret(pos: FlowPos): FlowRange = FlowRange(pos, pos)
    }
}

/**
 * The document-wide flowing rich text: an ordered list of paragraphs that lays
 * out across all pages inside [margins] (like an .odt body), plus the default
 * text style paragraphs inherit. Owned by [com.xnotes.core.model.Document]; one
 * per document, empty until the user types.
 */
class TextFlow {
    val paragraphs: MutableList<Paragraph> = mutableListOf()
    var margins: FlowMargins = FlowMargins()
    var defaultFace: FontFace = FontFace.SANS
    var defaultSizePt: Double = DEFAULT_SIZE_PT

    /** Explicit base text colour, or null to follow the host theme (auto). */
    var defaultColor: Rgba? = null

    /** The face code paragraphs and inline code render in. */
    var monoFace: FontFace = FontFace.MONO

    /** Bumped when the paragraph list itself changes (insert/remove/reorder). */
    var rev: Int = 0
        internal set

    internal fun touch() {
        rev++
    }

    /** True when there is nothing worth persisting (no text, no styled remnants). */
    val isEmpty: Boolean
        get() = paragraphs.isEmpty() ||
            (paragraphs.size == 1 && paragraphs[0].length == 0 && paragraphs[0].isDefaultStyle())

    /** The whole flow as plain text, paragraphs joined by '\n' (the IME mirror shape). */
    fun plainText(): String = paragraphs.joinToString("\n") { it.plainText() }

    /** Character offset of [pos] into [plainText] (newline separators count one each). */
    fun globalOffset(pos: FlowPos): Int {
        var total = 0
        for (i in 0 until pos.para) total += paragraphs[i].length + 1
        return total + pos.offset
    }

    /** Inverse of [globalOffset]; clamps past-the-end offsets to the flow end. */
    fun posAtGlobal(offset: Int): FlowPos {
        if (paragraphs.isEmpty()) return FlowPos.START
        var remaining = offset.coerceAtLeast(0)
        for (i in paragraphs.indices) {
            val len = paragraphs[i].length
            if (remaining <= len) return FlowPos(i, remaining)
            remaining -= len + 1
        }
        return FlowPos(paragraphs.size - 1, paragraphs.last().length)
    }

    /** Force re-shaping of every paragraph (font resolution changed under us). */
    fun reshapeAll() {
        paragraphs.forEach { it.touch() }
        touch()
    }

    /** The position after the last character of the flow. */
    fun endPos(): FlowPos =
        if (paragraphs.isEmpty()) FlowPos.START else FlowPos(paragraphs.size - 1, paragraphs.last().length)

    fun deepCopy(): TextFlow {
        val copy = TextFlow()
        val tables = java.util.IdentityHashMap<FlowTable, FlowTable>()
        paragraphs.mapTo(copy.paragraphs) { p ->
            p.deepCopy().also { c -> c.table = p.table?.let { t -> tables.getOrPut(t) { t.copy() } } }
        }
        copy.margins = margins
        copy.defaultFace = defaultFace
        copy.defaultSizePt = defaultSizePt
        copy.defaultColor = defaultColor
        copy.monoFace = monoFace
        copy.rev = rev
        return copy
    }

    companion object {
        const val DEFAULT_SIZE_PT = 12.0

        /** Matches TextItem's historical default so text is visible on the default paper. */
        val DEFAULT_COLOR = Rgba(236, 236, 236, 255)
    }
}

/**
 * The flow's document-level defaults as one value: what the text tool's config
 * popup edits, what "Default for new notes" saves, and what new notes are
 * stamped with. The factory size depends on the screen ([sizeForScreen]).
 */
data class FlowDefaults(
    val face: FontFace = FontFace.SANS,
    val monoFace: FontFace = FontFace.MONO,
    val sizePt: Double = TextFlow.DEFAULT_SIZE_PT,
    val color: Rgba? = null,
    val margins: FlowMargins = FlowMargins(),
) {
    /** All the file format's built-ins, which a note need not record (not a device's factory). */
    val isEmpty: Boolean get() = this == FlowDefaults()

    fun applyTo(flow: TextFlow) {
        flow.defaultFace = face
        flow.monoFace = monoFace
        flow.defaultSizePt = sizePt
        flow.defaultColor = color
        flow.margins = margins
    }

    companion object {
        fun of(flow: TextFlow): FlowDefaults =
            FlowDefaults(flow.defaultFace, flow.monoFace, flow.defaultSizePt, flow.defaultColor, flow.margins)

        /**
         * The factory text size for a screen whose shorter side is [shortSideDp]. A
         * narrower screen shows the fitted page smaller, so the size grows in inverse
         * proportion to look the same on every screen, in whole points held to 6..36.
         */
        fun sizeForScreen(shortSideDp: Double): Double {
            if (!(shortSideDp > 0.0)) return TextFlow.DEFAULT_SIZE_PT
            val pt = TABLET_PT * TABLET_DP / shortSideDp
            return pt.roundToInt().toDouble().coerceIn(MIN_SCREEN_PT, MAX_SCREEN_PT)
        }

        // Chosen by eye on a Galaxy Tab S9 FE.
        private const val TABLET_DP = 823.0
        private const val TABLET_PT = 12.0
        private const val MIN_SCREEN_PT = 6.0
        private const val MAX_SCREEN_PT = 36.0
    }
}
