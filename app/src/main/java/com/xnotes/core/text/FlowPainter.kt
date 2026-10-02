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
     */
    fun paintPage(r: Renderer, frame: FlowFrame, pageIndex: Int, region: Rect, breaks: Map<Int, IntArray> = emptyMap()) {
        val page = frame.pages.getOrNull(pageIndex) ?: return
        if (r.writesText) r.beginMark(Mark.Decoration)
        for (table in page.tables) {
            if (table.bottom < region.top || table.top > region.bottom) continue
            paintTableFills(r, table)
            paintTableRules(r, table)
        }
        paintCodeChips(r, frame, page.lines, region)
        if (r.writesText) r.endMark()
        for (line in page.lines) {
            if (line.bottom < region.top || line.top > region.bottom) continue
            paintLine(r, frame, line, breaks[line.paraIndex])
        }
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

    private fun paintLine(r: Renderer, frame: FlowFrame, line: PlacedLine, breaks: IntArray?) {
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
            val color = inkColor(seg.style, frame)
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
                r.drawMath(seg.text, seg.x, line.baseline, seg.font.pointSize, color, seg.style.mathDisplay)
            } else {
                r.drawTextRun(seg.text, seg.x, line.baseline, seg.font, color)
            }
            if (tag) r.endMark()
        }
        if (tag) {
            // A line wrapped at a space keeps it, so the words either side of the break stay two words.
            val last = line.segs.lastOrNull()
            val color = last?.style?.color ?: frame.defaultColor
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
        val ascent = line.baseline - line.top
        val descent = line.bottom - line.baseline
        val thickness = (line.height / 14.0).coerceAtLeast(1.0)
        for (deco in line.decos) {
            val color = inkColor(deco.style, frame)
            // A hyperlink is underlined even without the explicit underline style.
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

    /** A run's ink colour: its own colour, else the link colour for a hyperlink, else the flow default. */
    private fun inkColor(style: CharStyle, frame: FlowFrame): Rgba =
        style.color ?: if (style.link != null) LINK_COLOR else frame.defaultColor

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

    /** Code line/chip backgrounds: neutral translucent grey, legible on light and dark paper. */
    val CODE_BG = Rgba(128, 128, 128, 42)
    val CHIP_BG = Rgba(128, 128, 128, 56)

    /** Hyperlink ink: an azure that reads on both light and dark paper. */
    val LINK_COLOR = Rgba(90, 156, 255, 255)

    /**
     * Chips behind a formula showing its source. Both are translucent tints so
     * they sit on light and dark paper alike, and the cool one is deliberately
     * not the code grey: opening an equation is a different thing from a code
     * span, and the warm one says the LaTeX is broken rather than merely open.
     */
    val MATH_BG = Rgba(88, 140, 216, 44)
    val MATH_ERROR_BG = Rgba(214, 74, 68, 52)
    val MATH_ERROR_RULE = Rgba(214, 74, 68, 190)

    const val CODE_PAD = 6.0
    const val CHIP_PAD = 3.0
}
