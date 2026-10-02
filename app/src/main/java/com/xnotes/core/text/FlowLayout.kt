package com.xnotes.core.text

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.MathBox
import com.xnotes.core.pal.MathTypesetter
import com.xnotes.core.pal.TextMeasurer

/**
 * The concrete font a run resolves to: style overrides over the flow defaults.
 * Code is mono, and so is maths: a formula sets in its own faces anyway, so this
 * only shows when the LaTeX behind it does, which is exactly when it should not
 * look like the prose around it.
 */
fun resolveFont(flow: TextFlow, para: Paragraph, style: CharStyle): FontSpec = FontSpec(
    pointSize = style.sizePt ?: flow.defaultSizePt,
    face = if (para.codeLang != null || style.code || style.math) flow.monoFace else style.face ?: flow.defaultFace,
    bold = style.bold,
    italic = style.italic,
)

/**
 * One wrapped line of a paragraph: characters [startChar, endChar) of its plain
 * text. Trailing spaces hang (they are inside the span but excluded from
 * [width]); [spaceCount] is the stretchable interior spaces justify distributes
 * into; [hardBroken] lines (mid-word) never justify.
 */
class BrokenLine(
    val startChar: Int,
    val endChar: Int,
    val width: Double,
    val ascent: Double,
    val descent: Double,
    val spaceCount: Int,
    val hardBroken: Boolean,
) {
    val height: Double get() = ascent + descent
}

/**
 * Lays the flow out: greedy word wrap per paragraph (cached by paragraph
 * revision and width), then pagination into per-page content rects. Everything
 * here is pure and deterministic against the [TextMeasurer].
 */
class FlowLayout(private val measurer: TextMeasurer, private val math: MathTypesetter? = null) {

    /**
     * Derived syntax-highlight colours for a code paragraph (start-sorted char
     * spans), or null for none. Installed by the host; read at placement time so
     * highlight results recolour segments on the next republish, never persisting.
     */
    var codeSpans: (Paragraph) -> List<CodeSpan>? = { null }

    /** The active code theme's block background, read at layout time (null = neutral grey). */
    var codeBackground: () -> Rgba? = { null }

    /** Theme-derived colour for text when the flow sets no explicit default, read at layout time. */
    var autoColor: () -> Rgba? = { null }

    /** The theme accent, which tints a table's header and bands unless the table picks a colour. */
    var accentColor: () -> Rgba? = { null }

    /** The grey a table's rules take unless the table picks a colour (it depends on the paper). */
    var ruleColor: () -> Rgba? = { null }

    /**
     * Char offset of the math run showing its source because the caret is inside
     * it, or -1. Installed by the host and read at shaping time, so an equation
     * turns back into text to be edited and back into a formula on the way out.
     */
    var revealedMath: (Paragraph) -> Int = { -1 }

    // --- per-paragraph measurement (cached) ---

    /** Advances and per-run fonts/metrics for one paragraph at given flow defaults. */
    private class ParaShape(
        val text: String,
        val adv: DoubleArray,
        val runEnds: IntArray,
        val runMetrics: Array<LineMetrics>,
        val runFonts: Array<FontSpec>,
        val emptyFont: FontSpec,
        val emptyMetrics: LineMetrics,
        /**
         * True for characters that *continue* a drawn formula. A line may still
         * start at one, since the box is a word like any other; it may never
         * break inside it, which is what these mark.
         */
        val atom: BooleanArray,
        /** Per run: how a math run is being shown, or null when it is not one. */
        val mathShow: Array<MathShow?>,
    )

    private class ShapeEntry(
        val rev: Int,
        val defaultsKey: Long,
        val bold: Boolean,
        val reveal: Int,
        val shape: ParaShape,
    )
    private class BreakEntry(
        val rev: Int,
        val defaultsKey: Long,
        val bold: Boolean,
        val reveal: Int,
        val width: Double,
        val lines: List<BrokenLine>,
    )

    private val shapes = HashMap<Paragraph, ShapeEntry>()
    private val breaks = HashMap<Paragraph, BreakEntry>()

    private fun defaultsKey(flow: TextFlow): Long =
        (flow.defaultFace.id.hashCode().toLong() shl 32) xor
            (flow.monoFace.id.hashCode().toLong() shl 16) xor
            flow.defaultSizePt.toRawBits()

    /**
     * The font a math run would take if it were prose: the same size, in the face
     * the text around it is set in. A formula resolves to mono so its source
     * reads as source, which is no use for deciding the line it has to sit on.
     */
    private fun proseFont(flow: TextFlow, para: Paragraph, style: CharStyle, bold: Boolean): FontSpec =
        resolveFont(flow, para, (if (bold) style.copy(bold = true) else style).copy(math = false, code = false))

    /** [bold] forces bold on every run (a table's header row). */
    private fun shapeOf(flow: TextFlow, para: Paragraph, bold: Boolean = false): ParaShape {
        val key = defaultsKey(flow)
        val reveal = if (para.runs.any { it.style.math }) revealedMath(para) else -1
        shapes[para]?.let {
            if (it.rev == para.rev && it.defaultsKey == key && it.bold == bold && it.reveal == reveal) {
                return it.shape
            }
        }
        val text = para.plainText()
        val adv = DoubleArray(text.length)
        val atom = BooleanArray(text.length)
        val mathShow = arrayOfNulls<MathShow>(para.runs.size)
        val runEnds = IntArray(para.runs.size)
        val runMetrics = arrayOfNulls<LineMetrics>(para.runs.size)
        val runFonts = arrayOfNulls<FontSpec>(para.runs.size)
        var offset = 0
        for ((i, run) in para.runs.withIndex()) {
            val font = resolveFont(flow, para, if (bold) run.style.copy(bold = true) else run.style)
            // A formula is one box on the line: its whole advance rides on the first
            // character so the caret and the glyphs agree, and the rest measure zero.
            // Shown as source (revealed, or refused) it is its own text again.
            var box: MathBox? = null
            if (run.style.math) {
                val ts = math
                mathShow[i] = when {
                    offset == reveal -> MathShow.SOURCE
                    ts == null || !ts.ready() || run.text.isEmpty() -> MathShow.SOURCE
                    else -> {
                        box = ts.measure(run.text, font.pointSize, run.style.mathDisplay)
                        if (box == null) MathShow.ERROR else MathShow.FORMULA
                    }
                }
            }
            if (box != null) {
                adv[offset] = box.width
                for (k in offset + 1 until offset + run.text.length) atom[k] = true
                // A formula asks for at least the room the same text would: most of
                // them are shorter than a line of prose, and a line holding nothing
                // else would then sit higher than the lines around it, dropping to
                // the normal baseline the moment a single character joined it.
                // Measured against the face the prose uses, not the mono one the
                // run resolves to, since that is the baseline it has to share.
                val asText = measurer.metrics(proseFont(flow, para, run.style, bold))
                runMetrics[i] = LineMetrics(
                    maxOf(box.ascent, asText.ascent),
                    maxOf(box.descent, asText.descent),
                )
            } else {
                measurer.advances(run.text, font).copyInto(adv, offset)
                runMetrics[i] = measurer.metrics(font)
            }
            offset += run.text.length
            runEnds[i] = offset
            runFonts[i] = font
        }
        // An empty heading still stands as tall as the text it is waiting for, so the
        // caret previews at the right height and the line does not jump on the first key.
        val emptyStyle = if (para.headingLevel > 0) {
            Paragraph.headingStyle(para.headingLevel, flow.defaultSizePt)
        } else {
            CharStyle.DEFAULT
        }
        val emptyFont = resolveFont(flow, para, if (bold) emptyStyle.copy(bold = true) else emptyStyle)
        val shape = ParaShape(
            text, adv, runEnds,
            @Suppress("UNCHECKED_CAST") (runMetrics as Array<LineMetrics>),
            @Suppress("UNCHECKED_CAST") (runFonts as Array<FontSpec>),
            emptyFont,
            measurer.metrics(emptyFont),
            atom,
            mathShow,
        )
        shapes[para] = ShapeEntry(para.rev, key, bold, reveal, shape)
        return shape
    }


    // --- line breaking ---

    /**
     * Greedy word wrap of [para] at [availWidth]: break opportunities sit after
     * space runs; a word wider than the line hard-breaks per character (always
     * placing at least one char so layout progresses). [fromChar] re-breaks a
     * paragraph remainder when it continues onto a page of different width.
     */
    fun breakLines(
        flow: TextFlow,
        para: Paragraph,
        availWidth: Double,
        fromChar: Int = 0,
        bold: Boolean = false,
    ): List<BrokenLine> {
        val key = defaultsKey(flow)
        val reveal = if (para.runs.any { it.style.math }) revealedMath(para) else -1
        if (fromChar == 0) {
            breaks[para]?.let {
                if (it.rev == para.rev && it.defaultsKey == key && it.bold == bold &&
                    it.reveal == reveal && it.width == availWidth
                ) {
                    return it.lines
                }
            }
        }
        val shape = shapeOf(flow, para, bold)
        val lines = breakShape(shape, availWidth.coerceAtLeast(MIN_LINE_WIDTH), fromChar)
        if (fromChar == 0) breaks[para] = BreakEntry(para.rev, key, bold, reveal, availWidth, lines)
        return lines
    }

    private fun breakShape(shape: ParaShape, availWidth: Double, fromChar: Int): List<BrokenLine> {
        val text = shape.text
        val len = text.length
        val start0 = fromChar.coerceIn(0, len)
        if (start0 >= len) {
            return listOf(lineOf(shape, start0, len, 0.0, 0))
        }
        val out = mutableListOf<BrokenLine>()
        var i = start0
        while (i < len) {
            var w = 0.0
            var wAtLastContent = 0.0
            var spacesBeforeLastContent = 0
            var spacesSeen = 0
            var lastBreak = -1
            var lastBreakWidth = 0.0
            var lastBreakSpaces = 0
            var j = i
            var emitted = false
            while (j < len) {
                val c = text[j]
                val a = shape.adv[j]
                // A space inside a drawn formula is LaTeX, not a gap in the prose, so
                // it neither hangs nor offers anywhere to break: the box goes whole.
                if (c == ' ' && !shape.atom[j]) {
                    // Spaces hang: they widen the running total but never overflow a line.
                    spacesSeen++
                    w += a
                    j++
                    continue
                }
                // A non-space after spaces marks a break opportunity at its start.
                if (j > i && text[j - 1] == ' ' && !shape.atom[j]) {
                    lastBreak = j
                    lastBreakWidth = wAtLastContent
                    lastBreakSpaces = spacesBeforeLastContent
                }
                if (w + a > availWidth && j > i && !shape.atom[j]) {
                    if (lastBreak > i) {
                        out.add(lineOf(shape, i, lastBreak, lastBreakWidth, lastBreakSpaces))
                        i = lastBreak
                    } else {
                        out.add(lineOf(shape, i, j, w, spacesSeen, hardBroken = true))
                        i = j
                    }
                    emitted = true
                    break
                }
                w += a
                wAtLastContent = w
                spacesBeforeLastContent = spacesSeen
                j++
            }
            if (!emitted) {
                out.add(lineOf(shape, i, len, wAtLastContent, spacesBeforeLastContent))
                i = len
            }
        }
        return out
    }

    private fun lineOf(
        shape: ParaShape,
        start: Int,
        end: Int,
        width: Double,
        spaceCount: Int,
        hardBroken: Boolean = false,
    ): BrokenLine {
        var ascent = 0.0
        var descent = 0.0
        if (start >= end) {
            ascent = shape.emptyMetrics.ascent
            descent = shape.emptyMetrics.descent
        } else {
            var runStart = 0
            for (r in shape.runEnds.indices) {
                val runEnd = shape.runEnds[r]
                if (runEnd > start && runStart < end) {
                    val m = shape.runMetrics[r]
                    if (m.ascent > ascent) ascent = m.ascent
                    if (m.descent > descent) descent = m.descent
                }
                runStart = runEnd
            }
            if (ascent == 0.0 && descent == 0.0) {
                ascent = shape.emptyMetrics.ascent
                descent = shape.emptyMetrics.descent
            }
        }
        return BrokenLine(start, end, width, ascent, descent, spaceCount, hardBroken)
    }

    // --- the magic wand ---

    /**
     * Column fractions for [table] at [tableWidth] content px that make it as short
     * as possible, which evens out the cells across each row: a column of long text
     * widens, a column of short headings narrows. See the grid overload.
     */
    fun fitColumns(flow: TextFlow, table: FlowTable, tableWidth: Double): List<Double> {
        val block = CellIndex(flow.paragraphs).blockOf(table) ?: return table.widths
        return fitColumns(flow, block.grid(flow.paragraphs), table.style, tableWidth)
    }

    /**
     * Greedy fit over a grid of cell paragraphs: every column starts at its widest
     * word; each step gives the column whose extra width removes the most table
     * height per px (trying doubling increments, since a line only drops at a
     * threshold); width no column can turn into height is shared by how much
     * each could still unwrap. Fits that need no wrapping keep the natural widths'
     * proportions.
     */
    fun fitColumns(flow: TextFlow, grid: List<List<List<Paragraph>>>, style: TableStyle, tableWidth: Double): List<Double> {
        val rows = grid.size
        val cols = grid.maxOfOrNull { it.size } ?: 0
        if (rows == 0 || cols == 0) return emptyList()
        val pad = style.paddingPt * TableStyle.PX_PER_PT
        val shapes = List(rows) { r ->
            List(cols) { c -> grid[r].getOrNull(c).orEmpty().map { shapeOf(flow, it, style.headerRow && r == 0) } }
        }
        val floor = MIN_LINE_WIDTH + 2 * pad
        val minW = DoubleArray(cols) { c ->
            maxOf(floor, shapes.maxOf { row -> row[c].maxOfOrNull { longestWord(it) } ?: 0.0 } + 2 * pad)
        }
        val maxW = DoubleArray(cols) { c ->
            maxOf(minW[c], shapes.maxOf { row -> row[c].maxOfOrNull { it.adv.sum() } ?: 0.0 } + 2 * pad)
        }
        if (maxW.sum() <= tableWidth) return FlowTable.normalized(maxW.map { it / maxW.sum() })
        if (minW.sum() >= tableWidth) return FlowTable.normalized(minW.map { it / minW.sum() })

        val cache = HashMap<Pair<Int, Double>, DoubleArray>()
        fun heights(c: Int, w: Double): DoubleArray = cache.getOrPut(c to w) {
            val inner = (w - 2 * pad).coerceAtLeast(MIN_LINE_WIDTH)
            DoubleArray(rows) { r -> shapes[r][c].sumOf { sh -> breakShape(sh, inner, 0).sumOf { it.height } } }
        }
        val w = minW.copyOf()
        fun tableHeight(): Double = (0 until rows).sumOf { r -> (0 until cols).maxOf { c -> heights(c, w[c])[r] } }
        var left = tableWidth - w.sum()
        val step = maxOf(1.0, left / FIT_STEPS)
        var h = tableHeight()
        while (left >= step) {
            var bestC = -1
            var bestD = 0.0
            var bestRatio = 0.0
            var bestH = h
            for (c in 0 until cols) {
                var d = step
                while (d <= left + EPS) {
                    val old = w[c]
                    w[c] = old + d
                    val nh = tableHeight()
                    w[c] = old
                    val ratio = (h - nh) / d
                    if (ratio > bestRatio + 1e-12) {
                        bestRatio = ratio
                        bestC = c
                        bestD = d
                        bestH = nh
                    }
                    d *= 2
                }
            }
            if (bestC < 0) break
            w[bestC] += bestD
            left -= bestD
            h = bestH
        }
        if (left > 0.0) {
            val want = DoubleArray(cols) { (maxW[it] - w[it]).coerceAtLeast(0.0) }
            val sumWant = want.sum()
            val sumW = w.sum()
            for (c in 0 until cols) w[c] += left * if (sumWant > 0.0) want[c] / sumWant else w[c] / sumW
        }
        val sum = w.sum()
        return FlowTable.normalized(w.map { it / sum })
    }

    /** The widest run of non-space characters in [shape]. */
    private fun longestWord(shape: ParaShape): Double {
        var best = 0.0
        var cur = 0.0
        for (i in shape.text.indices) {
            if (shape.text[i] == ' ') {
                cur = 0.0
            } else {
                cur += shape.adv[i]
                if (cur > best) best = cur
            }
        }
        return best
    }

    /** Drop cached measurements for paragraphs no longer in [flow] (call after big splices). */
    fun pruneCaches(flow: TextFlow) {
        val live = HashSet<Paragraph>(flow.paragraphs)
        shapes.keys.retainAll(live)
        breaks.keys.retainAll(live)
    }

    // --- pagination ---

    /** The empty-line slot height at the flow's default font (empty-line fill math). */
    fun defaultSlotHeight(flow: TextFlow): Double =
        measurer.metrics(FontSpec(flow.defaultSizePt, flow.defaultFace)).height

    /**
     * Lay the whole flow onto [pageBoxes]: break each paragraph at its page's
     * content width, fill pages top to bottom, re-break a paragraph remainder
     * when it continues onto a page of different width, and count how many
     * virtual pages the tail overflowed past the real ones.
     */
    fun layout(flow: TextFlow, pageBoxes: List<PageBox>, dpi: Int): FlowFrame {
        val defColor = flow.defaultColor ?: autoColor() ?: TextFlow.DEFAULT_COLOR
        val cells = CellIndex(flow.paragraphs)
        if (pageBoxes.isEmpty()) return FlowFrame(emptyList(), flow.rev, 0, defColor, codeBackground(), cells)
        pruneCaches(flow)
        val contentRects = pageBoxes.map { contentRectOf(it, flow.margins, dpi) }
        val pageLines = List(pageBoxes.size) { mutableListOf<PlacedLine>() }
        val pageTables = List(pageBoxes.size) { mutableListOf<TableFrag>() }
        var page = 0
        var y = contentRects[0].top
        var ordinal = 0
        var skipTo = 0
        for ((paraIndex, para) in flow.paragraphs.withIndex()) {
            if (paraIndex < skipTo) continue
            val block = cells.blockAt(paraIndex)
            if (block != null) {
                val cur = Cursor(page, y)
                layoutTable(flow, block, contentRects, cur, pageLines, pageTables)
                page = cur.page
                y = cur.y
                ordinal = 0
                skipTo = block.last + 1
                continue
            }
            ordinal = if (para.list == ListKind.ORDERED) ordinal + 1 else 0
            val indentPx = para.indent * INDENT_STEP_PX +
                if (para.list != ListKind.NONE) MARKER_GUTTER_PX else 0.0
            var rect = contentRects.getOrElse(page) { contentRects.last() }
            var avail = (rect.w - indentPx).coerceAtLeast(MIN_LINE_WIDTH)
            var lines = breakLines(flow, para, avail)
            var li = 0
            var firstLine = true
            while (li < lines.size) {
                val bl = lines[li]
                if (y + bl.height > rect.bottom + EPS && y > rect.top + EPS) {
                    page++
                    rect = contentRects.getOrElse(page) { contentRects.last() }
                    y = rect.top
                    val widthNow = (rect.w - indentPx).coerceAtLeast(MIN_LINE_WIDTH)
                    if (widthNow != avail) {
                        avail = widthNow
                        lines = breakLines(flow, para, avail, fromChar = bl.startChar)
                        li = 0
                        continue
                    }
                }
                val placed = placeLine(
                    flow, para, paraIndex, shapeOf(flow, para), bl, rect, indentPx, avail, y,
                    lastLineOfPara = li == lines.lastIndex,
                    firstLineOfPara = firstLine && bl.startChar == 0,
                    ordinal = ordinal,
                )
                pageLines.getOrNull(page)?.add(placed)
                y += bl.height
                firstLine = false
                li++
            }
        }
        val extra = (page - (pageBoxes.size - 1)).coerceAtLeast(0)
        val pages = pageBoxes.indices.map { PageFlow(contentRects[it], pageLines[it], pageTables[it]) }
        return FlowFrame(pages, flow.rev, extra, defColor, codeBackground(), cells)
    }

    private class Cursor(var page: Int, var y: Double)

    /** One wrapped line of a cell: [bl] of paragraph [para]. */
    private class CellLine(val para: Int, val bl: BrokenLine, val firstOfPara: Boolean, val lastOfPara: Boolean)

    /**
     * Lay [block] out as a grid from the cursor: rows stack down the page and move
     * whole to the next page when they don't fit; only a row taller than a whole
     * page splits, each cell continuing line by line. The table takes its own
     * fraction of each page's content width from the left edge, and the columns
     * their fractions of that.
     */
    private fun layoutTable(
        flow: TextFlow,
        block: TableBlock,
        contentRects: List<Rect>,
        cur: Cursor,
        pageLines: List<MutableList<PlacedLine>>,
        pageTables: List<MutableList<TableFrag>>,
    ) {
        val table = block.table
        val style = table.style
        val pad = style.paddingPt * TableStyle.PX_PER_PT
        val lineW = style.lineWidthPt * TableStyle.PX_PER_PT
        val cols = block.cols
        val widths = FlowTable.normalized(table.widths).takeIf { it.size == cols } ?: FlowTable.even(cols)
        val frac = FlowTable.clampWidth(table.width)
        val tint = style.tint ?: accentColor() ?: DEFAULT_TINT
        fun rectAt(p: Int) = contentRects.getOrElse(p) { contentRects.last() }
        var rect = rectAt(cur.page)
        var colXs = colEdges(rect, widths, frac)
        val rowsHere = mutableListOf<RowFrag>()

        fun flush() {
            if (rowsHere.isEmpty()) return
            pageTables.getOrNull(cur.page)?.add(
                TableFrag(
                    table, block.ordinal, colXs, rowsHere.toList(),
                    lineColor = style.lineColor ?: ruleColor() ?: DEFAULT_RULE,
                    lineWidth = lineW,
                    borders = style.borders,
                    headerFill = if (style.headerRow) tint.withAlpha(HEADER_ALPHA) else null,
                    bandFill = if (style.banded) tint.withAlpha(BAND_ALPHA) else null,
                ),
            )
            rowsHere.clear()
        }

        fun newPage() {
            flush()
            cur.page++
            rect = rectAt(cur.page)
            cur.y = rect.top
            colXs = colEdges(rect, widths, frac)
        }

        fun innerW(c: Int) = (colXs[c + 1] - colXs[c] - 2 * pad).coerceAtLeast(MIN_LINE_WIDTH)

        fun cellLines(row: Int, col: Int, bold: Boolean, fromPara: Int, fromChar: Int): List<CellLine> {
            if (!block.hasCell(row, col)) return emptyList()
            val out = mutableListOf<CellLine>()
            for (p in maxOf(fromPara, block.cellFirstPara(row, col))..block.cellLastPara(row, col)) {
                val para = flow.paragraphs[p]
                val from = if (p == fromPara) fromChar else 0
                val broken = breakLines(flow, para, innerW(col), from, bold)
                for ((k, bl) in broken.withIndex()) {
                    out.add(CellLine(p, bl, k == 0 && bl.startChar == 0, k == broken.lastIndex))
                }
            }
            return out
        }

        fun place(line: CellLine, col: Int, y: Double, bold: Boolean) {
            val para = flow.paragraphs[line.para]
            val box = Rect(colXs[col] + pad, y, innerW(col), line.bl.height)
            pageLines.getOrNull(cur.page)?.add(
                placeLine(
                    flow, para, line.para, shapeOf(flow, para, bold), line.bl, box, 0.0, box.w, y,
                    lastLineOfPara = line.lastOfPara, firstLineOfPara = line.firstOfPara, ordinal = 0,
                ),
            )
        }

        // Half the rule above and below keeps the border off the neighbouring lines.
        cur.y += lineW / 2.0
        for (row in 0 until block.rows) {
            val bold = style.headerRow && row == 0
            val minH = table.minHeightPt(row) * TableStyle.PX_PER_PT
            fun contents() = List(cols) { c -> cellLines(row, c, bold, -1, 0) }
            fun heightOf(content: List<List<CellLine>>) =
                maxOf(minH, content.maxOf { cell -> cell.sumOf { it.bl.height } } + 2 * pad)
            var content = contents()
            var rowH = heightOf(content)
            if (cur.y + rowH > rect.bottom + EPS && cur.y > rect.top + lineW + EPS) {
                val oldW = rect.w
                newPage()
                if (rect.w != oldW) {
                    content = contents()
                    rowH = heightOf(content)
                }
            }
            if (cur.y + rowH <= rect.bottom + EPS) {
                for (c in 0 until cols) {
                    var y = cur.y + pad
                    for (line in content[c]) {
                        place(line, c, y, bold)
                        y += line.bl.height
                    }
                }
                rowsHere.add(RowFrag(row, cur.y, cur.y + rowH))
                cur.y += rowH
                continue
            }
            // Taller than a whole page: split it, every cell continuing where it stopped.
            val next = IntArray(cols)
            while (true) {
                var used = 0.0
                for (c in 0 until cols) {
                    var y = cur.y + pad
                    val lines = content[c]
                    while (next[c] < lines.size) {
                        val h = lines[next[c]].bl.height
                        if (y + h > rect.bottom - pad + EPS && y > cur.y + pad + EPS) break
                        place(lines[next[c]], c, y, bold)
                        y += h
                        next[c]++
                    }
                    used = maxOf(used, y - cur.y + pad)
                }
                val done = (0 until cols).all { next[it] >= content[it].size }
                if (done) {
                    val bottom = cur.y + maxOf(used, minOf(minH, rect.bottom - cur.y))
                    rowsHere.add(RowFrag(row, cur.y, bottom))
                    cur.y = bottom
                    break
                }
                rowsHere.add(RowFrag(row, cur.y, rect.bottom))
                val oldW = rect.w
                newPage()
                if (rect.w != oldW) {
                    content = List(cols) { c ->
                        val rest = content[c].getOrNull(next[c])
                        if (rest == null) emptyList() else cellLines(row, c, bold, rest.para, rest.bl.startChar)
                    }
                    next.fill(0)
                }
            }
        }
        flush()
        cur.y += lineW / 2.0
    }

    private fun colEdges(rect: Rect, widths: List<Double>, frac: Double): DoubleArray {
        val tableW = rect.w * frac
        val xs = DoubleArray(widths.size + 1)
        xs[0] = rect.left
        var acc = 0.0
        for (i in widths.indices) {
            acc += widths[i]
            xs[i + 1] = rect.left + tableW * acc
        }
        xs[widths.size] = rect.left + tableW
        return xs
    }

    private fun placeLine(
        flow: TextFlow,
        para: Paragraph,
        paraIndex: Int,
        shape: ParaShape,
        bl: BrokenLine,
        contentRect: Rect,
        indentPx: Double,
        avail: Double,
        y: Double,
        lastLineOfPara: Boolean,
        firstLineOfPara: Boolean,
        ordinal: Int,
    ): PlacedLine {
        val n = bl.endChar - bl.startChar
        // A display equation with nothing beside it sits centred, which is where
        // one belongs. It is centred here rather than by turning the paragraph's
        // alignment on, because that would outlive the equation and leave the
        // user turning it off again. An alignment they chose still wins.
        val centreDisplay = para.align == ParaAlign.LEFT && loneDisplayMath(para, shape, bl)
        val justify = para.align == ParaAlign.JUSTIFY &&
            !lastLineOfPara && !bl.hardBroken && bl.spaceCount > 0
        val extraPerSpace = if (justify) ((avail - bl.width) / bl.spaceCount).coerceAtLeast(0.0) else 0.0
        val alignOffset = when {
            centreDisplay || para.align == ParaAlign.CENTER -> ((avail - bl.width) / 2.0).coerceAtLeast(0.0)
            para.align == ParaAlign.RIGHT -> (avail - bl.width).coerceAtLeast(0.0)
            else -> 0.0
        }
        val left = contentRect.left + indentPx + alignOffset
        val xs = DoubleArray(n + 1)
        xs[0] = left
        var stretched = 0
        for (k in 0 until n) {
            val ci = bl.startChar + k
            var a = shape.adv[ci]
            if (extraPerSpace > 0.0 && shape.text[ci] == ' ' && stretched < bl.spaceCount) {
                a += extraPerSpace
                stretched++
            }
            xs[k + 1] = xs[k] + a
        }
        val segs = mutableListOf<Seg>()
        val decos = mutableListOf<Deco>()
        val mathStarts = mutableListOf<Int>()
        val spans = if (para.codeLang != null) codeSpans(para) else null
        var runStart = 0
        for (r in para.runs.indices) {
            val runEnd = shape.runEnds[r]
            val from = maxOf(bl.startChar, runStart)
            val to = minOf(bl.endChar, runEnd)
            if (to > from) {
                val font = shape.runFonts[r]
                val style = para.runs[r].style
                if (style.underline || style.strike || style.highlight != null || style.code) {
                    decos.add(Deco(xs[from - bl.startChar], xs[to - bl.startChar], font, style))
                }
                // A formula is one segment whatever is inside it: splitting at its
                // spaces would hand the painter pieces of LaTeX to set separately.
                val show = shape.mathShow[r]
                if (show == MathShow.FORMULA) {
                    segs.add(Seg(shape.text.substring(from, to), xs[from - bl.startChar], font, style, math = true, start = from))
                    mathStarts += from
                    runStart = runEnd
                    continue
                }
                // Showing its source instead: chip it so it cannot read as prose.
                if (show != null) {
                    decos.add(Deco(xs[from - bl.startChar], xs[to - bl.startChar], font, style, show))
                }
                // Words draw one run-fragment at a time; spaces are gaps the xs already carry.
                var s = from
                while (s < to) {
                    if (shape.text[s] == ' ') {
                        s++
                        continue
                    }
                    var e = s
                    while (e < to && shape.text[e] != ' ') e++
                    if (spans == null) {
                        segs.add(Seg(shape.text.substring(s, e), xs[s - bl.startChar], font, style, start = s))
                    } else {
                        emitHighlighted(shape, spans, s, e, xs, bl.startChar, font, style, segs)
                    }
                    s = e
                }
            }
            runStart = runEnd
        }
        val marker = if (firstLineOfPara && para.list != ListKind.NONE) {
            val gutterLeft = contentRect.left + para.indent * INDENT_STEP_PX
            val gutter = Rect(gutterLeft, y, MARKER_GUTTER_PX, bl.height)
            val font = FontSpec(flow.defaultSizePt, flow.defaultFace)
            if (para.list == ListKind.ORDERED) {
                val label = "$ordinal."
                val labelW = measurer.advances(label, font).sum()
                Marker(para.list, para.checked, ordinal, gutter, label, gutter.right - MARKER_GAP - labelW, font)
            } else {
                Marker(para.list, para.checked, ordinal, gutter, font = font)
            }
        } else {
            null
        }
        return PlacedLine(
            paraIndex, bl.startChar, bl.endChar,
            top = y, baseline = y + bl.ascent, bottom = y + bl.height,
            xs = xs, segs = segs, decos = decos, marker = marker,
            codeLine = para.codeLang != null,
            codeLeft = contentRect.left + indentPx,
            codeRight = contentRect.right,
            mathStarts = mathStarts.toIntArray(),
            font = lineFont(shape, bl.startChar),
        )
    }

    /** The font of the run holding character [at], or the empty paragraph's when there is none. */
    private fun lineFont(shape: ParaShape, at: Int): FontSpec {
        if (shape.text.isEmpty()) return shape.emptyFont
        for (r in shape.runEnds.indices) if (at < shape.runEnds[r]) return shape.runFonts[r]
        return shape.runFonts.lastOrNull() ?: shape.emptyFont
    }

    /**
     * True when this line holds a drawn display equation and nothing else. Such a
     * line centres itself: a formula set on its own line is display maths, and
     * that is how display maths is laid out.
     */
    private fun loneDisplayMath(para: Paragraph, shape: ParaShape, bl: BrokenLine): Boolean {
        var found = false
        var at = 0
        for ((i, run) in para.runs.withIndex()) {
            val end = at + run.text.length
            if (end > bl.startChar && at < bl.endChar) {
                if (run.style.mathDisplay && shape.mathShow[i] == MathShow.FORMULA) {
                    found = true
                } else if (shape.text.substring(maxOf(at, bl.startChar), minOf(end, bl.endChar)).isNotBlank()) {
                    return false
                }
            }
            at = end
        }
        return found
    }

    /** Emit one word's segments, split at highlight-span boundaries with their colours. */
    private fun emitHighlighted(
        shape: ParaShape,
        spans: List<CodeSpan>,
        from: Int,
        to: Int,
        xs: DoubleArray,
        blStart: Int,
        font: FontSpec,
        style: CharStyle,
        segs: MutableList<Seg>,
    ) {
        var i = from
        while (i < to) {
            val covering = spans.firstOrNull { it.start <= i && i < it.end }
            val boundary = if (covering != null) {
                minOf(to, covering.end)
            } else {
                minOf(to, spans.firstOrNull { it.start > i }?.start ?: to)
            }
            val st = if (covering != null) style.copy(color = covering.color) else style
            segs.add(Seg(shape.text.substring(i, boundary), xs[i - blStart], font, st, start = i))
            i = boundary
        }
    }

    private fun contentRectOf(box: PageBox, m: FlowMargins, dpi: Int): Rect {
        val l = PageSize.mmToPx(m.leftMm, dpi)
        val t = PageSize.mmToPx(m.topMm, dpi)
        val r = PageSize.mmToPx(m.rightMm, dpi)
        val b = PageSize.mmToPx(m.bottomMm, dpi)
        var left = l
        var w = box.width - l - r
        if (w < MIN_LINE_WIDTH) {
            w = minOf(MIN_LINE_WIDTH, box.width)
            left = ((box.width - w) / 2.0).coerceAtLeast(0.0)
        }
        var top = t
        var h = box.height - t - b
        if (h < MIN_CONTENT_HEIGHT) {
            h = minOf(MIN_CONTENT_HEIGHT, box.height)
            top = ((box.height - h) / 2.0).coerceAtLeast(0.0)
        }
        return Rect(left, top, w, h)
    }

    companion object {
        /** Indent step per level, content px at 150 dpi (~8 mm). */
        const val INDENT_STEP_PX = 48.0

        /** Gutter reserved left of list paragraphs for bullet/number/checkbox markers. */
        const val MARKER_GUTTER_PX = 40.0

        /** Gap between a marker's right edge and the paragraph text. */
        const val MARKER_GAP = 10.0

        /** Layout never wraps narrower than this, however extreme the margins. */
        const val MIN_LINE_WIDTH = 20.0

        /** A content rect is never shorter than this, however extreme the margins. */
        const val MIN_CONTENT_HEIGHT = 16.0

        const val HEADER_ALPHA = 64
        const val BAND_ALPHA = 26

        /** Tint when neither the table nor the theme names one. */
        val DEFAULT_TINT = Rgba(90, 140, 255, 255)

        /** Rule colour when neither the table nor the host names one. */
        val DEFAULT_RULE = Rgba(128, 128, 128, 255)

        private const val EPS = 0.01

        /** The wand hands out the spare width in steps of about this fraction of it. */
        private const val FIT_STEPS = 200.0
    }
}
