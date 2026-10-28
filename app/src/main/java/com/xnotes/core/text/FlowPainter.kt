package com.xnotes.core.text

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Mark
import com.xnotes.core.pal.Renderer

/**
 * Paints one page of a [FlowFrame] in page-local content space (origin at the
 * page's top-left), modeled on `paintPagePattern`: pure, region-aware, safe to
 * call from any thread against the immutable frame. Draw order per line: code
 * chip, highlight/inline-code backgrounds, list marker, text runs, then
 * underline/strike so decorations sit over their glyphs. Translucent fills use
 * plain colour alpha, which the PDF backend carries as real transparency, so no
 * layer tricks are needed.
 */
object FlowPainter {

    /**
     * [breaks] holds, per paragraph, offsets where a backend writing text needs a run to end and
     * the next begin, so each piece can be tagged on its own (a link inside a word, for one).
     * Without [math] the formulas are left out, for a caller that draws them with [paintMath].
     * [chrome] adds what only the screen shows, such as where page breaks sit.
     */
    fun paintPage(
        r: Renderer,
        frame: FlowFrame,
        pageIndex: Int,
        region: Rect,
        breaks: Map<Int, IntArray> = emptyMap(),
        math: Boolean = true,
        chrome: Boolean = false,
    ) {
        val page = frame.pages.getOrNull(pageIndex) ?: return
        if (chrome) {
            for (b in page.breaks) if (b.y >= region.top - b.font.pointSize && b.y <= region.bottom + b.font.pointSize) paintBreak(r, frame, b)
        }
        if (r.writesText) r.beginMark(Mark.Decoration)
        for (table in page.tables) {
            if (table.bottom < region.top || table.top > region.bottom) continue
            paintTableFills(r, table)
            paintTableRules(r, table)
        }
        paintCodeChips(r, frame, page.lines, region)
        paintQuoteBars(r, frame, page)
        if (r.writesText) r.endMark()
        for (t in page.callouts) {
            if (t.bottom < region.top || t.top > region.bottom) continue
            paintCalloutTitle(r, frame, t)
        }
        for (line in page.lines) {
            if (line.bottom < region.top || line.top > region.bottom) continue
            paintLine(r, frame, line, breaks[line.paraIndex], math)
        }
    }

    /** Only one page's formulas, for the screen, which paints them over the page every frame. */
    fun paintMath(r: Renderer, frame: FlowFrame, pageIndex: Int, region: Rect) {
        val page = frame.pages.getOrNull(pageIndex) ?: return
        for (line in page.lines) {
            if (line.mathStarts.isEmpty() || line.bottom < region.top || line.top > region.bottom) continue
            for (seg in line.segs) {
                if (seg.math) drawFormula(r, line, seg, inkOf(seg.style, frame))
            }
        }
    }

    /** One bar down each run of lines a quote or callout holds on this page, its title row included. */
    private fun paintQuoteBars(r: Renderer, frame: FlowFrame, page: PageFlow) {
        val lines = page.lines
        var i = 0
        while (i < lines.size) {
            val id = lines[i].quoteId
            if (id < 0) {
                i++
                continue
            }
            var j = i
            while (j + 1 < lines.size && lines[j + 1].quoteId == id) j++
            val title = page.callouts.firstOrNull { it.quoteId == id }
            val top = minOf(lines[i].top, title?.top ?: lines[i].top)
            val kind = lines[i].callout
            val color = if (kind == null) frame.defaultColor.withAlpha(QUOTE_ALPHA) else calloutColor(kind, frame)
            r.fillRect(Rect(lines[i].quoteBar, top, QUOTE_BAR, lines[j].bottom - top), color)
            i = j + 1
        }
    }

    /** A callout's icon and title, in its colour. */
    private fun paintCalloutTitle(r: Renderer, frame: FlowFrame, t: CalloutTitle) {
        val color = calloutColor(t.kind, frame)
        if (r.writesText) r.beginMark(Mark.Decoration)
        val s = t.iconSize
        CalloutIcons.paint(r, t.kind, t.x, t.baseline - s * ICON_RISE, s, color)
        if (r.writesText) {
            r.endMark()
            r.beginMark(Mark.FlowTitle(t.para))
        }
        r.drawTextRun(t.label, t.labelX, t.baseline, t.font, color)
        if (r.writesText) r.endMark()
    }

    /**
     * GitHub's alert colours, the darker set on light paper and the lighter on dark, told
     * apart by the text colour the frame resolved: light text means dark paper.
     */
    fun calloutColor(kind: CalloutKind, frame: FlowFrame): Rgba {
        val c = frame.defaultColor
        val darkPaper = 0.299 * c.r + 0.587 * c.g + 0.114 * c.b > 128
        return when (kind) {
            CalloutKind.NOTE -> if (darkPaper) Rgba(68, 147, 248, 255) else Rgba(9, 105, 218, 255)
            CalloutKind.TIP -> if (darkPaper) Rgba(63, 185, 80, 255) else Rgba(26, 127, 55, 255)
            CalloutKind.IMPORTANT -> if (darkPaper) Rgba(171, 125, 248, 255) else Rgba(130, 80, 223, 255)
            CalloutKind.WARNING -> if (darkPaper) Rgba(210, 153, 34, 255) else Rgba(154, 103, 0, 255)
            CalloutKind.CAUTION -> if (darkPaper) Rgba(248, 81, 73, 255) else Rgba(209, 36, 47, 255)
        }
    }

    /** A page break's dashed line, broken around its label. */
    private fun paintBreak(r: Renderer, frame: FlowFrame, b: BreakMark) {
        val color = frame.defaultColor.withAlpha(BREAK_ALPHA)
        fun dashes(from: Double, to: Double) {
            var x = from
            while (x < to) {
                r.fillRect(Rect(x, b.y - BREAK_LINE / 2.0, minOf(BREAK_DASH, to - x), BREAK_LINE), color)
                x += BREAK_DASH + BREAK_GAP
            }
        }
        dashes(b.left, b.labelX - BREAK_LABEL_PAD)
        dashes(b.labelX + b.labelW + BREAK_LABEL_PAD, b.right)
        r.drawTextRun(b.label, b.labelX, b.baseline, b.font, color)
    }

    /** [seg]'s formula, or a stand-in while it is pending or the backend is still setting it. */
    private fun drawFormula(r: Renderer, line: PlacedLine, seg: Seg, color: Rgba) {
        val size = seg.font.pointSize
        val display = seg.style.mathDisplay
        if (!seg.pending && r.drawMath(seg.text, seg.x, line.baseline, size, color, display)) return
        val box = seg.box ?: return
        r.drawMathPending(seg.text, size, display, Rect(seg.x, line.baseline - box.ascent, box.width, box.height), color)
    }

    /**
     * One background rect per contiguous code block, not per line: abutting
     * per-line rects leave antialiased seam hairlines at their shared fractional
     * edges (two half-covered translucent fills never sum back to a solid one).
     */
    private fun paintCodeChips(r: Renderer, frame: FlowFrame, lines: List<PlacedLine>, region: Rect) {
        var i = 0
        while (i < lines.size) {
            if (!lines[i].codeLine) {
                i++
                continue
            }
            val top = lines[i].top
            var left = lines[i].codeLeft
            var right = lines[i].codeRight
            while (i + 1 < lines.size && lines[i + 1].codeLine) {
                i++
                left = minOf(left, lines[i].codeLeft)
                right = maxOf(right, lines[i].codeRight)
            }
            val bottom = lines[i].bottom
            if (bottom >= region.top && top <= region.bottom) {
                r.fillRect(
                    Rect(left - CODE_PAD, top, right - left + 2 * CODE_PAD, bottom - top),
                    frame.codeBg ?: CODE_BG,
                )
            }
            i++
        }
    }

    /** Header row and banded rows (bands count from the first body row). */
    private fun paintTableFills(r: Renderer, t: TableFrag) {
        val header = t.headerFill != null
        for (row in t.rows) {
            val body = row.row - if (header) 1 else 0
            val fill = when {
                header && row.row == 0 -> t.headerFill
                body >= 0 && body % 2 == 1 -> t.bandFill
                else -> null
            } ?: continue
            r.fillRect(Rect(t.left, row.top, t.right - t.left, row.bottom - row.top), fill)
        }
    }

    /**
     * Rules as filled rects centred on the grid edges. Horizontals run the full
     * width; verticals stop short of them, so a translucent rule never doubles up
     * at a crossing.
     */
    private fun paintTableRules(r: Renderer, t: TableFrag) {
        if (t.borders == TableBorders.NONE) return
        val w = t.lineWidth
        val half = w / 2.0
        fun h(y: Double) = r.fillRect(Rect(t.left - half, y - half, t.right - t.left + w, w), t.lineColor)
        fun v(x: Double) {
            var y = t.top + half
            for (row in t.rows) {
                val end = row.bottom - half
                if (end > y) r.fillRect(Rect(x - half, y, w, end - y), t.lineColor)
                y = row.bottom + half
            }
        }
        h(t.top)
        when (t.borders) {
            TableBorders.OUTER -> {
                h(t.bottom)
                v(t.left)
                v(t.right)
            }
            TableBorders.HORIZONTAL -> for (row in t.rows) h(row.bottom)
            else -> {
                for (row in t.rows) h(row.bottom)
                for (x in t.colXs) v(x)
            }
        }
    }

    private fun paintLine(r: Renderer, frame: FlowFrame, line: PlacedLine, breaks: IntArray?, math: Boolean) {
        // Marks are only for a backend recording structure; the screen never allocates one.
        val tag = r.writesText
        val para = line.paraIndex
        if (tag) r.beginMark(Mark.Decoration)
        for (deco in line.decos) {
            deco.style.highlight?.let {
                r.fillRect(Rect(deco.x0, line.top, deco.x1 - deco.x0, line.height), it)
            }
            if (deco.style.code && !line.codeLine) {
                r.fillRect(
                    Rect(deco.x0 - CHIP_PAD, line.top, deco.x1 - deco.x0 + 2 * CHIP_PAD, line.height),
                    frame.codeBg ?: CHIP_BG,
                )
            }
            // A formula showing its LaTeX: the chip is the only thing telling the
            // user this is an equation opened up rather than text that lost it.
            deco.math?.let {
                r.fillRect(
                    Rect(deco.x0 - CHIP_PAD, line.top, deco.x1 - deco.x0 + 2 * CHIP_PAD, line.height),
                    if (it == MathShow.ERROR) MATH_ERROR_BG else MATH_BG,
                )
            }
        }
        if (tag) r.endMark()
        line.marker?.let {
            if (tag) r.beginMark(Mark.FlowMarker(para))
            paintMarker(r, frame, line, it)
            if (tag) r.endMark()
        }
        // A backend writing real text gets the spaces too, each in reading order before the word
        // after it: a reader copies in content order, so trailing them all behind would scramble it.
        var next = line.startChar
        for (seg in line.segs) {
            val color = inkOf(seg.style, frame)
            if (tag) {
                for (k in next until seg.start) {
                    r.beginMark(Mark.FlowText(para, k, k + 1))
                    r.drawTextRun(" ", line.caretX(k), line.baseline, seg.font, color)
                    r.endMark()
                }
                next = seg.end
                if (!seg.math && breaks != null && breaks.any { it > seg.start && it < seg.end }) {
                    paintPieces(r, line, seg, color, breaks)
                    continue
                }
                r.beginMark(Mark.FlowText(para, seg.start, seg.end))
            }
            if (seg.math) {
                if (math) drawFormula(r, line, seg, color)
            } else {
                r.drawTextRun(seg.text, seg.x, line.baseline, seg.font, color)
            }
            if (tag) r.endMark()
        }
        if (tag) {
            // A line wrapped at a space keeps it, so the words either side of the break stay two words.
            val last = line.segs.lastOrNull()
            val color = last?.style?.let { inkOf(it, frame) } ?: frame.defaultColor
            for (k in next until line.endChar) {
                r.beginMark(Mark.FlowText(para, k, k + 1))
                r.drawTextRun(" ", line.caretX(k), line.baseline, last?.font ?: line.font, color)
                r.endMark()
            }
        }
        // A blank line inside a code block has to exist in the copy, or the code pastes back squashed.
        if (tag && line.codeLine && line.endChar == line.startChar) {
            r.beginMark(Mark.FlowText(para, line.startChar, line.startChar))
            r.drawTextRun(" ", line.caretX(line.startChar), line.baseline, line.font, frame.defaultColor)
            r.endMark()
        }
        if (tag) r.beginMark(Mark.Decoration)
        if (line.rule) {
            val t = (line.height / RULE_THICKNESS_PER_LINE).coerceAtLeast(1.0)
            val mid = (line.top + line.bottom) / 2.0
            r.fillRect(Rect(line.codeLeft, mid - t / 2.0, line.codeRight - line.codeLeft, t), frame.defaultColor.withAlpha(RULE_ALPHA))
        }
        val ascent = line.baseline - line.top
        val descent = line.bottom - line.baseline
        val thickness = (line.height / 14.0).coerceAtLeast(1.0)
        for (deco in line.decos) {
            val color = inkOf(deco.style, frame)
            if (deco.style.underline || deco.style.link != null) {
                r.fillRect(Rect(deco.x0, line.baseline + descent * 0.35, deco.x1 - deco.x0, thickness), color)
            }
            if (deco.style.strike) {
                r.fillRect(Rect(deco.x0, line.baseline - ascent * 0.30, deco.x1 - deco.x0, thickness), color)
            }
            // LaTeX that would not set is ruled underneath, so a broken formula
            // reads as broken from across the page and not only by its tint.
            if (deco.math == MathShow.ERROR) {
                r.fillRect(
                    Rect(deco.x0 - CHIP_PAD, line.bottom - thickness, deco.x1 - deco.x0 + 2 * CHIP_PAD, thickness),
                    MATH_ERROR_RULE,
                )
            }
        }
        if (tag) r.endMark()
    }

    /** [seg] cut at [breaks], each piece marked on its own and set where the line places it. */
    private fun paintPieces(r: Renderer, line: PlacedLine, seg: Seg, color: Rgba, breaks: IntArray) {
        var a = seg.start
        for (b in breaks.sorted() + seg.end) {
            if (b <= a || b > seg.end) continue
            r.beginMark(Mark.FlowText(line.paraIndex, a, b))
            val x = seg.x + line.caretX(a) - line.caretX(seg.start)
            r.drawTextRun(seg.text.substring(a - seg.start, b - seg.start), x, line.baseline, seg.font, color)
            r.endMark()
            a = b
        }
    }

    private fun paintMarker(r: Renderer, frame: FlowFrame, line: PlacedLine, m: Marker) {
        val color = frame.defaultColor
        val ascent = line.baseline - line.top
        when (m.kind) {
            ListKind.BULLET -> {
                val radius = (ascent * 0.16).coerceIn(2.0, 6.0)
                val cx = m.rect.right - FlowLayout.MARKER_GAP - radius
                val cy = line.baseline - ascent * 0.32
                r.drawBullet(Pt(cx, cy), radius, line.baseline, m.font, color)
            }
            ListKind.ORDERED -> {
                m.text?.let { r.drawTextRun(it, m.textX, line.baseline, m.font, color) }
            }
            ListKind.CHECK -> {
                val side = (ascent * 0.85).coerceIn(8.0, 26.0)
                val box = Rect(m.rect.right - FlowLayout.MARKER_GAP - side, line.baseline - side, side, side)
                r.drawCheckbox(box, (side / 9.0).coerceAtLeast(1.2), m.checked, line.baseline, m.font, color)
            }
            ListKind.NONE -> return
        }
        // Copied, a marker is followed by a real space, so "• item" does not read "•item".
        if (r.writesText) r.drawTextRun(" ", m.rect.right - FlowLayout.MARKER_GAP, line.baseline, m.font, color)
    }

    /** What [style]'s text draws in: its own colour, else a link's, else the flow's. */
    private fun inkOf(style: CharStyle, frame: FlowFrame): Rgba =
        style.color ?: if (style.link != null) LINK_COLOR else frame.defaultColor

    /** A link's look unless its text picks a colour: this blue, underlined. */
    val LINK_COLOR = Rgba(100, 160, 255, 255)

    /** Code line/chip backgrounds: neutral translucent grey, legible on light and dark paper. */
    val CODE_BG = Rgba(128, 128, 128, 42)
    val CHIP_BG = Rgba(128, 128, 128, 56)

    /**
     * Chips behind a formula showing its source. Both are translucent tints so
     * they sit on light and dark paper alike, and the cool one is deliberately
     * not the code grey: opening an equation is a different thing from a code
     * span, and the warm one says the LaTeX is broken rather than merely open.
     */
    val MATH_BG = Rgba(88, 140, 216, 44)
    val MATH_ERROR_BG = Rgba(214, 74, 68, 52)
    val MATH_ERROR_RULE = Rgba(214, 74, 68, 190)

    /** A quote's bar: this wide, and for a plain quote the text colour toned down this far. */
    private const val QUOTE_BAR = 4.0
    private const val QUOTE_ALPHA = 90

    /** How much of a callout icon stands above its title's baseline. */
    private const val ICON_RISE = 0.9

    private const val BREAK_ALPHA = 110
    private const val BREAK_LINE = 1.5
    private const val BREAK_DASH = 8.0
    private const val BREAK_GAP = 6.0
    private const val BREAK_LABEL_PAD = 10.0

    /** A horizontal rule: a fraction of a line thick, in the text colour toned down. */
    private const val RULE_THICKNESS_PER_LINE = 10.0
    private const val RULE_ALPHA = 90

    const val CODE_PAD = 6.0
    const val CHIP_PAD = 3.0
}
