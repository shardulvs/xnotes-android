package com.xnotes.core.text

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.LineMetrics

/** Page dimensions handed to layout (content px at the document dpi). */
data class PageBox(val width: Double, val height: Double)

/**
 * How a math run is being shown this pass: as the formula it sets, as the LaTeX
 * behind it because the caret is in there, or as LaTeX the typesetter refused.
 * The last two both draw as text, and only the chip behind them tells the user
 * whether they opened it or broke it.
 */
enum class MathShow { FORMULA, SOURCE, ERROR }

/**
 * A drawable fragment: [text] starts at page-local [x] on the line baseline, and
 * at paragraph-local character [start]. With [math] set it is LaTeX to be
 * typeset there rather than characters to draw, which is the same run read
 * either way depending on where the caret is.
 */
class Seg(
    val text: String,
    val x: Double,
    val font: FontSpec,
    val style: CharStyle,
    val math: Boolean = false,
    val start: Int = 0,
) {
    /** The paragraph-local character just past this fragment. */
    val end: Int get() = start + text.length
}

/**
 * A decorated span of one line (underline/strike/highlight/inline-code chip),
 * page-local x0..x1. [math] set means the span is a formula showing its source,
 * which gets a chip of its own so it never reads as ordinary prose.
 */
class Deco(
    val x0: Double,
    val x1: Double,
    val font: FontSpec,
    val style: CharStyle,
    val math: MathShow? = null,
)

/**
 * The bullet/number/checkbox marker of a list paragraph's first line; [rect] is
 * the gutter box. Ordered lists carry their pre-measured label ([text] drawn at
 * [textX] on the line baseline) so painting needs no measurer.
 */
class Marker(
    val kind: ListKind,
    val checked: Boolean,
    val ordinal: Int,
    val rect: Rect,
    val text: String? = null,
    val textX: Double = 0.0,
    val font: FontSpec = FontSpec(TextFlow.DEFAULT_SIZE_PT),
)

/**
 * One laid-out line: chars [startChar, endChar) of paragraph [paraIndex], placed
 * at page-local coordinates. [xs] holds every char boundary x (justify already
 * distributed), so caret mapping and hit-testing are exact lookups.
 */
class PlacedLine(
    val paraIndex: Int,
    val startChar: Int,
    val endChar: Int,
    val top: Double,
    val baseline: Double,
    val bottom: Double,
    val xs: DoubleArray,
    val segs: List<Seg>,
    val decos: List<Deco>,
    val marker: Marker?,
    val codeLine: Boolean,
    val codeLeft: Double,
    val codeRight: Double,
    /** Paragraph-local offsets where a drawn formula begins on this line. */
    val mathStarts: IntArray = IntArray(0),
    /** The font of the line's first run, or the empty paragraph's: what a space with no word beside it sets in. */
    val font: FontSpec = FontSpec(TextFlow.DEFAULT_SIZE_PT),
) {
    val height: Double get() = bottom - top

    /** The metrics holding this line open, or null when nothing is typed on it yet. */
    val held: LineMetrics? get() =
        if (startChar >= endChar) null else LineMetrics(baseline - top, bottom - baseline)

    /** Page-local x of the caret before character [offset] (paragraph-local), clamped. */
    fun caretX(offset: Int): Double = xs[(offset - startChar).coerceIn(0, xs.size - 1)]

    /** The paragraph-local offset whose boundary is nearest to page-local [x]. */
    fun offsetAt(x: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (k in xs.indices) {
            val d = kotlin.math.abs(x - xs[k])
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        val offset = startChar + best
        // A drawn formula carries its whole width on one character, so its interior
        // boundaries all sit at its right edge and a tap on its left half snaps to
        // the offset in front of it. That offset is outside the formula, which
        // would make tapping an equation fail to open it, so a tap that actually
        // landed within the box is nudged inside.
        if (x > xs[best] && offset in mathStarts) return offset + 1
        return offset
    }
}

/** One row of a table placed on a page; a row split across pages has a slice on each. */
class RowFrag(val row: Int, val top: Double, val bottom: Double)

/**
 * The part of a table laid onto one page: column edges, the row slices there,
 * and the look with its colours already resolved, so painting reads nothing
 * else. [table] identifies the table for the edit-mode chrome.
 */
class TableFrag(
    val table: FlowTable,
    val ordinal: Int,
    val colXs: DoubleArray,
    val rows: List<RowFrag>,
    val lineColor: Rgba,
    val lineWidth: Double,
    val borders: TableBorders,
    val headerFill: Rgba?,
    val bandFill: Rgba?,
) {
    val left: Double get() = colXs.first()
    val right: Double get() = colXs.last()
    val top: Double get() = rows.first().top
    val bottom: Double get() = rows.last().bottom
    val rect: Rect get() = Rect(left, top, right - left, bottom - top)

    /** The column under page-local [x], clamped to the table. */
    fun colAt(x: Double): Int {
        for (c in 0 until colXs.size - 1) if (x < colXs[c + 1]) return c
        return colXs.size - 2
    }
}

/** The flow laid onto one page: its content rect, the lines placed there, and its table slices. */
class PageFlow(val contentRect: Rect, val lines: List<PlacedLine>, val tables: List<TableFrag> = emptyList())

/** What a text-tool tap landed on. */
sealed class FlowHit {
    class Caret(val pos: FlowPos) : FlowHit()
    class Checkbox(val paraIndex: Int) : FlowHit()

    /** Below the end of the flow: the tap asks for empty-line fill. */
    object BeyondEnd : FlowHit()
}

/**
 * An immutable snapshot of the fully laid-out flow. Painters on any thread read
 * only this (never the live model); queries answer caret/hit geometry from it.
 * [extraPagesNeeded] is how many pages typing overflowed past the real ones.
 */
class FlowFrame(
    val pages: List<PageFlow>,
    val flowRev: Int,
    val extraPagesNeeded: Int,
    val defaultColor: Rgba,
    /** Code chip background from the active code theme; null = the painter's neutral grey. */
    val codeBg: Rgba? = null,
    /** Where each laid-out paragraph sits in tables, as of this layout. */
    val cells: CellIndex = CellIndex(emptyList()),
) {
    private val byPara = HashMap<Int, MutableList<Pair<Int, PlacedLine>>>()

    /** Every page slice of each table (by [TableBlock.ordinal]), page index attached. */
    val tableFrags: List<List<Pair<Int, TableFrag>>> = List(cells.tables.size) { t ->
        pages.withIndex().flatMap { (pi, page) -> page.tables.filter { it.ordinal == t }.map { pi to it } }
    }

    /** The page slices of [table], or empty when it is not laid out. */
    fun fragsOf(table: FlowTable): List<Pair<Int, TableFrag>> =
        cells.blockOf(table)?.let { tableFrags.getOrNull(it.ordinal) }.orEmpty()

    /** Every placed line in document order, page index attached. */
    val lines: List<Pair<Int, PlacedLine>>

    private val lineOrder = HashMap<PlacedLine, Int>()

    init {
        val all = mutableListOf<Pair<Int, PlacedLine>>()
        for ((pi, page) in pages.withIndex()) {
            for (line in page.lines) {
                byPara.getOrPut(line.paraIndex) { mutableListOf() }.add(pi to line)
                lineOrder[line] = all.size
                all.add(pi to line)
            }
        }
        lines = all
    }

    val isEmpty: Boolean get() = pages.all { it.lines.isEmpty() }

    fun pagesWithLines(): Set<Int> = pages.indices.filterTo(mutableSetOf()) { pages[it].lines.isNotEmpty() }

    /** The page-local extent of the flow on [pageIndex] (padded for chips/markers), or null. */
    fun pageFlowBounds(pageIndex: Int): Rect? {
        val page = pages.getOrNull(pageIndex) ?: return null
        if (page.lines.isEmpty()) return null
        var top = page.lines.minOf { it.top }
        var bottom = page.lines.maxOf { it.bottom }
        var pad = FlowPainter.CODE_PAD
        for (t in page.tables) {
            top = minOf(top, t.top - t.lineWidth)
            bottom = maxOf(bottom, t.bottom + t.lineWidth)
            pad = maxOf(pad, t.lineWidth)
        }
        var left = page.contentRect.left - pad
        var right = page.contentRect.right + pad
        // A formula wider than the column hangs out of it, and a crop taken at the
        // column would cut in the export what the screen draws whole.
        for (line in page.lines) {
            if (line.xs.isEmpty()) continue
            left = minOf(left, line.xs.first() - pad)
            right = maxOf(right, line.xs.last() + pad)
        }
        return Rect(left, top, right - left, bottom - top)
    }

    /** (page index, bottom y) of the last placed line, or null when nothing is placed. */
    fun lastLineEnd(): Pair<Int, Double>? {
        for (pi in pages.indices.reversed()) {
            pages[pi].lines.lastOrNull()?.let { return pi to it.bottom }
        }
        return null
    }

    /** The placed line a caret at [pos] sits on (wrap boundaries resolve downward). */
    fun placedLineFor(pos: FlowPos): Pair<Int, PlacedLine>? {
        val paraLines = byPara[pos.para] ?: return null
        for (entry in paraLines) {
            if (pos.offset >= entry.second.startChar && pos.offset < entry.second.endChar) return entry
        }
        return paraLines.last()
    }

    /** Page index + page-local caret rect for [pos], or null when it is not laid out. */
    fun caretRect(pos: FlowPos): Pair<Int, Rect>? {
        val (pg, line) = placedLineFor(pos) ?: return null
        val offset = pos.offset.coerceAtMost(line.endChar)
        return pg to Rect(line.caretX(offset), line.top, CARET_WIDTH, line.height)
    }

    /**
     * The position one visual line above/below [pos], keeping the caret x; null at
     * the edges. Inside a table it walks the cell's lines, then the cell above or
     * below, then out of the table; entering one lands in the column under the x.
     */
    fun moveVertical(pos: FlowPos, dir: Int): FlowPos? {
        val (_, cur) = placedLineFor(pos) ?: return null
        val idx = lineOrder[cur] ?: return null
        val x = cur.caretX(pos.offset)
        val next = lines.getOrNull(idx + dir)?.second
        val b = cells.blockAt(cur.paraIndex)
        if (b == null) {
            next ?: return null
            val nb = cells.blockAt(next.paraIndex) ?: return FlowPos(next.paraIndex, next.offsetAt(x))
            val frags = tableFrags.getOrNull(nb.ordinal).orEmpty()
            val frag = (if (dir > 0) frags.firstOrNull() else frags.lastOrNull())?.second
            val col = frag?.colAt(x) ?: 0
            return cellEdge(nb, if (dir > 0) 0 else nb.rows - 1, col, top = dir > 0, x)
        }
        if (next != null && cells.sameContainer(next.paraIndex, cur.paraIndex)) {
            return FlowPos(next.paraIndex, next.offsetAt(x))
        }
        val row = cells.rowOf(cur.paraIndex) + dir
        val col = cells.colOf(cur.paraIndex)
        if (b.hasCell(row, col)) return cellEdge(b, row, col, top = dir > 0, x)
        val out = if (dir > 0) b.last + 1 else b.first - 1
        val paraLines = byPara[out] ?: return null
        val line = if (dir > 0) paraLines.first().second else paraLines.last().second
        return FlowPos(out, line.offsetAt(x))
    }

    /** The caret on the first ([top]) or last line of cell ([row], [col]) nearest [x]. */
    private fun cellEdge(b: TableBlock, row: Int, col: Int, top: Boolean, x: Double): FlowPos? {
        if (!b.hasCell(row, col)) return null
        val para = if (top) b.cellFirstPara(row, col) else b.cellLastPara(row, col)
        val paraLines = byPara[para] ?: return FlowPos(para, 0)
        val line = if (top) paraLines.first().second else paraLines.last().second
        return FlowPos(para, line.offsetAt(x))
    }

    /** The start or end of [pos]'s visual line. */
    fun lineEdge(pos: FlowPos, start: Boolean): FlowPos? {
        val (_, line) = placedLineFor(pos) ?: return null
        return FlowPos(line.paraIndex, if (start) line.startChar else line.endChar)
    }

    /** Resolve a text-tool tap at page-local [local] on page [pageIndex]. */
    fun hitTest(pageIndex: Int, local: Pt): FlowHit {
        val end = lastLineEnd() ?: return FlowHit.BeyondEnd
        if (pageIndex > end.first || (pageIndex == end.first && local.y >= end.second)) {
            return FlowHit.BeyondEnd
        }
        val page = pages.getOrNull(pageIndex) ?: return FlowHit.BeyondEnd
        if (page.lines.isEmpty()) {
            // A degenerate mid-flow page with no lines: land at the next placed line.
            for (pi in (pageIndex + 1) until pages.size) {
                pages[pi].lines.firstOrNull()?.let {
                    return FlowHit.Caret(FlowPos(it.paraIndex, it.startChar))
                }
            }
            return FlowHit.BeyondEnd
        }
        for (line in page.lines) {
            val m = line.marker ?: continue
            if (m.kind == ListKind.CHECK && m.rect.outset(CHECKBOX_HIT_PAD).contains(local)) {
                return FlowHit.Checkbox(line.paraIndex)
            }
        }
        for (frag in page.tables) {
            if (local.y >= frag.top && local.y <= frag.bottom) return FlowHit.Caret(cellCaretAt(page, frag, local))
        }
        val body = if (page.tables.isEmpty()) page.lines else page.lines.filter { !cells.inTable(it.paraIndex) }
        val pool = body.ifEmpty { page.lines }
        val line = pool.firstOrNull { local.y < it.bottom } ?: pool.last()
        return FlowHit.Caret(FlowPos(line.paraIndex, line.offsetAt(local.x)))
    }

    /** The hyperlink under a finger tap at page-local [local], or null. Bounded to the line's
     *  actual text extent so a tap in the empty margin past a link never opens it. */
    fun linkAt(pageIndex: Int, local: Pt): String? {
        val page = pages.getOrNull(pageIndex) ?: return null
        val line = page.lines.firstOrNull { local.y >= it.top && local.y < it.bottom } ?: return null
        val leftX = line.xs.firstOrNull() ?: return null
        val rightX = line.xs.lastOrNull() ?: return null
        if (local.x < leftX || local.x > rightX) return null
        for ((i, seg) in line.segs.withIndex()) {
            val segRight = line.segs.getOrNull(i + 1)?.x ?: rightX
            if (local.x >= seg.x && local.x <= segRight) return seg.style.link
        }
        return null
    }

    /** The caret nearest [local] inside the cell of [frag] under it (x picks the column). */
    private fun cellCaretAt(page: PageFlow, frag: TableFrag, local: Pt): FlowPos {
        val rowFrag = frag.rows.firstOrNull { local.y < it.bottom } ?: frag.rows.last()
        val b = cells.tables[frag.ordinal]
        val col = frag.colAt(local.x)
        if (!b.hasCell(rowFrag.row, col)) return FlowPos(b.first, 0)
        val first = b.cellFirstPara(rowFrag.row, col)
        val last = b.cellLastPara(rowFrag.row, col)
        val inCell = page.lines.filter {
            it.paraIndex in first..last && it.top >= rowFrag.top - EDGE_EPS && it.bottom <= rowFrag.bottom + EDGE_EPS
        }
        // The cell's text is on another page (a split row): its start is the nearest caret.
        if (inCell.isEmpty()) return FlowPos(first, 0)
        val line = inCell.firstOrNull { local.y < it.bottom } ?: inCell.last()
        return FlowPos(line.paraIndex, line.offsetAt(local.x))
    }

    /**
     * What a long press at [local] on [pageIndex] holds in a table: the table, and
     * true when it is on cell text. Glyphs win unless the press is within
     * [ruleSlop] of a rule; anywhere else in the table (the rules, cell padding,
     * empty cell space, or just outside the edge) holds the table itself.
     */
    fun tablePressAt(pageIndex: Int, local: Pt, ruleSlop: Double): Pair<FlowTable, Boolean>? {
        val page = pages.getOrNull(pageIndex) ?: return null
        val frag = page.tables.firstOrNull {
            local.x >= it.left - ruleSlop && local.x <= it.right + ruleSlop &&
                local.y >= it.top - ruleSlop && local.y <= it.bottom + ruleSlop
        } ?: return null
        val nearRule = frag.colXs.any { kotlin.math.abs(local.x - it) <= ruleSlop } ||
            frag.rows.any { kotlin.math.abs(local.y - it.top) <= ruleSlop || kotlin.math.abs(local.y - it.bottom) <= ruleSlop }
        if (nearRule) return frag.table to false
        val b = cells.tables[frag.ordinal]
        val onText = page.lines.any {
            it.paraIndex in b.first..b.last && it.xs.size > 1 &&
                local.y >= it.top && local.y < it.bottom && local.x >= it.xs.first() && local.x <= it.xs.last()
        }
        return frag.table to onText
    }

    /** Where a press at [local] on [pageIndex] lands in a table: the table, row and column, or null. */
    fun tableCellAt(pageIndex: Int, local: Pt): Triple<FlowTable, Int, Int>? {
        val page = pages.getOrNull(pageIndex) ?: return null
        val frag = page.tables.firstOrNull { local.y >= it.top && local.y <= it.bottom && local.x >= it.left && local.x <= it.right }
            ?: return null
        val row = frag.rows.firstOrNull { local.y < it.bottom } ?: frag.rows.last()
        return Triple(frag.table, row.row, frag.colAt(local.x))
    }

    /** Page-local highlight rects (page index keyed) covering [range]. */
    fun selectionRects(range: FlowRange): List<Pair<Int, Rect>> {
        val r = range.normalized()
        if (r.collapsed) return emptyList()
        val out = mutableListOf<Pair<Int, Rect>>()
        val shape = cells.shapeOf(r)
        if (shape is SelShape.Cells) {
            for ((pi, frag) in tableFrags.getOrNull(shape.block.ordinal).orEmpty()) {
                val x0 = frag.colXs[shape.cols.first]
                val x1 = frag.colXs[(shape.cols.last + 1).coerceAtMost(frag.colXs.size - 1)]
                for (row in frag.rows) {
                    if (row.row in shape.rows) out.add(pi to Rect(x0, row.top, x1 - x0, row.bottom - row.top))
                }
            }
            return out
        }
        if (shape == SelShape.Mixed) {
            for (b in cells.tables) {
                if (b.last < r.start.para || b.first > r.end.para) continue
                val rows = cells.mixedRows(b, r)
                for ((pi, frag) in tableFrags.getOrNull(b.ordinal).orEmpty()) {
                    for (row in frag.rows) {
                        if (row.row in rows) out.add(pi to Rect(frag.left, row.top, frag.right - frag.left, row.bottom - row.top))
                    }
                }
            }
        }
        for ((pi, page) in pages.withIndex()) {
            for (line in page.lines) {
                if (line.paraIndex < r.start.para || line.paraIndex > r.end.para) continue
                if (shape == SelShape.Mixed && cells.inTable(line.paraIndex)) continue
                val from = if (line.paraIndex == r.start.para) maxOf(line.startChar, r.start.offset) else line.startChar
                val to = if (line.paraIndex == r.end.para) minOf(line.endChar, r.end.offset) else line.endChar
                if (to < from) continue
                val x0 = line.caretX(from)
                var w = line.caretX(to) - x0
                // Mark the paragraph break itself while the selection continues past it.
                if (to == line.endChar && line.paraIndex < r.end.para) w += NEWLINE_TAIL
                if (w > 0.0) out.add(pi to Rect(x0, line.top, w, line.height))
            }
        }
        return out
    }

    /**
     * How many empty lines of height [slotH] must be appended so the caret can
     * land on the tapped line at page-local [y] on [pageIndex] (walking through
     * the intermediate pages' content rects). Zero when the tap is not beyond
     * the flow end.
     */
    fun emptyLinesToReach(pageIndex: Int, y: Double, slotH: Double): Int {
        if (pages.isEmpty() || slotH <= 0.0 || pageIndex !in pages.indices) return 0
        val end = lastLineEnd()
        val endPage = end?.first ?: 0
        val endY = end?.second ?: pages[0].contentRect.top
        if (pageIndex < endPage || (pageIndex == endPage && y < endY)) return 0
        fun slotsIn(h: Double): Int = maxOf(1, kotlin.math.floor(h / slotH).toInt())
        if (pageIndex == endPage) {
            val want = kotlin.math.floor((y - endY) / slotH).toInt() + 1
            val cap = slotsIn(pages[endPage].contentRect.bottom - endY)
            return want.coerceAtMost(cap)
        }
        var count = kotlin.math.floor((pages[endPage].contentRect.bottom - endY) / slotH).toInt()
            .coerceAtLeast(0)
        for (p in (endPage + 1) until pageIndex) count += slotsIn(pages[p].contentRect.h)
        val rect = pages[pageIndex].contentRect
        val want = kotlin.math.floor((y - rect.top).coerceAtLeast(0.0) / slotH).toInt() + 1
        return count + want.coerceAtMost(slotsIn(rect.h))
    }

    companion object {
        val EMPTY = FlowFrame(emptyList(), -1, 0, TextFlow.DEFAULT_COLOR)

        const val CARET_WIDTH = 2.0
        const val NEWLINE_TAIL = 8.0
        const val CHECKBOX_HIT_PAD = 6.0
        private const val EDGE_EPS = 0.5
    }
}

/**
 * The caret's vertical span, as (top, height), while it previews [pending] on a line
 * at [lineTop] that [held] glyphs already hold open, null when there are none. It is
 * the line box that first character leaves behind, which is what [caretRect] hands
 * back once the style is spent, so the caret does not jump when it lands.
 */
fun caretPreviewSpan(lineTop: Double, held: LineMetrics?, pending: LineMetrics): Pair<Double, Double> =
    lineTop to (maxOf(held?.ascent ?: 0.0, pending.ascent) + maxOf(held?.descent ?: 0.0, pending.descent))

/** The double-tap word range around [pos]: same-class char run (word/space/other). */
fun wordRangeAt(flow: TextFlow, pos: FlowPos): FlowRange {
    val para = flow.paragraphs.getOrNull(pos.para) ?: return FlowRange.caret(pos)
    val text = para.plainText()
    if (text.isEmpty()) return FlowRange.caret(FlowPos(pos.para, 0))
    val at = pos.offset.coerceIn(0, text.length - 1)
    fun cls(c: Char): Int = when {
        c.isLetterOrDigit() || c == '_' -> 0
        c == ' ' || c == '\t' -> 1
        else -> 2
    }
    val k = cls(text[at])
    var s = at
    var e = at + 1
    while (s > 0 && cls(text[s - 1]) == k) s--
    while (e < text.length && cls(text[e]) == k) e++
    return FlowRange(FlowPos(pos.para, s), FlowPos(pos.para, e))
}

/**
 * The next word boundary from [pos] in the given direction, for Ctrl+arrow movement
 * and Ctrl+Backspace/Delete. Works in flat plainText space so it crosses paragraphs:
 * skip separators then a same-class content run (letters/digits/_ vs punctuation).
 */
fun wordBoundary(flow: TextFlow, pos: FlowPos, forward: Boolean): FlowPos {
    val text = flow.plainText()
    val n = text.length
    var j = flow.globalOffset(pos).coerceIn(0, n)
    fun content(c: Char): Int = if (c.isLetterOrDigit() || c == '_') 0 else 1
    if (forward) {
        while (j < n && text[j].isWhitespace()) j++
        if (j < n) {
            val k = content(text[j])
            while (j < n && !text[j].isWhitespace() && content(text[j]) == k) j++
        }
    } else {
        while (j > 0 && text[j - 1].isWhitespace()) j--
        if (j > 0) {
            val k = content(text[j - 1])
            while (j > 0 && !text[j - 1].isWhitespace() && content(text[j - 1]) == k) j--
        }
    }
    return flow.posAtGlobal(j)
}
