package com.xnotes.core.pdf

/** A boundary between characters: before character [index] of the PDF text on note page [page]. */
data class TextPos(val page: Int, val index: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos): Int =
        if (page != other.page) page.compareTo(other.page) else index.compareTo(other.index)
}

/** A run of characters along one line: its box in points as displayed and the [PageText.quarter] it reads in. */
data class TextQuad(val left: Float, val top: Float, val right: Float, val bottom: Float, val quarter: Int)

/** A caret across a line, from the side the glyphs' tops face to the other, in points as displayed. */
data class TextCaret(val topX: Float, val topY: Float, val bottomX: Float, val bottomY: Float)

/**
 * The characters from [start] up to [end] in note page order, then character order, so it may
 * cross pages. Note pages are told apart by index, so two showing the same PDF page select apart.
 */
data class TextSelection(val start: TextPos, val end: TextPos) {
    init {
        require(start <= end) { "selection ends before it starts" }
    }

    val isEmpty: Boolean get() = start == end

    /** Note page [page]'s share of the selection as character indices, empty when it has none. */
    fun rangeOn(page: Int, text: PageText): IntRange {
        if (page < start.page || page > end.page) return IntRange.EMPTY
        val from = if (page == start.page) start.index.coerceIn(0, text.length) else 0
        val to = if (page == end.page) end.index.coerceIn(0, text.length) else text.length
        return from until to
    }

    /**
     * The selected text as copied: each page's [PageText.text], pages joined with LF. [textOf] gives
     * a note page's text, null for none.
     */
    fun text(textOf: (Int) -> PageText?): String = (start.page..end.page).mapNotNull { page ->
        val text = textOf(page) ?: return@mapNotNull null
        val range = rangeOn(page, text)
        if (range.isEmpty()) null else text.text(range.first, range.last + 1)
    }.joinToString("\n")

    /** The boxes of the selection on note page [page], one per line run. */
    fun quads(page: Int, text: PageText): List<TextQuad> =
        rangeOn(page, text).let { TextQuads.of(text, it.first, it.last + 1) }

    /** Where the selection starts on its first page: the leading edge of its first glyph; null when it has none there. */
    fun startCaret(text: PageText): TextCaret? = rangeOn(start.page, text).let { TextQuads.caret(text, it.first, it.last + 1, false) }

    /** Where the selection ends on its last page: the trailing edge of its last glyph; null when it has none there. */
    fun endCaret(text: PageText): TextCaret? = rangeOn(end.page, text).let { TextQuads.caret(text, it.first, it.last + 1, true) }

    companion object {
        /** The selection between two boundaries given in either order. */
        fun between(a: TextPos, b: TextPos): TextSelection = if (a <= b) TextSelection(a, b) else TextSelection(b, a)
    }
}

/** The geometry of a span of a page's characters. */
object TextQuads {

    /**
     * Boxes covering characters [from, to) of [text], one per run along a line. A run ends at a line
     * break or line-end hyphen, at a character off its line, and at a gap wider than the line is
     * tall, where PDFium joined two columns. Characters PDFium inferred add no box of their own but
     * sit inside the run around them, so spaces between words are covered.
     */
    fun of(text: PageText, from: Int, to: Int): List<TextQuad> {
        val out = ArrayList<TextQuad>()
        var run: Run? = null
        for (i in from until to) {
            if (text.isLineBreak(i)) {
                run?.let { out += it.quad() }
                run = null
                continue
            }
            if (!text.hasBox(i)) continue
            val current = run
            if (current != null && current.takes(text, i)) {
                current.add(text, i)
            } else {
                current?.let { out += it.quad() }
                run = Run(text, i)
            }
            if (text.isHyphen(i)) {
                run?.let { out += it.quad() }
                run = null
            }
        }
        run?.let { out += it.quad() }
        return out
    }

    /**
     * The caret at the leading edge of the first glyph in [from, to), or with [atEnd] at the trailing
     * edge of the last; null when the span has no glyph.
     */
    fun caret(text: PageText, from: Int, to: Int, atEnd: Boolean): TextCaret? {
        val range = if (atEnd) (to - 1 downTo from) else (from until to)
        val i = range.firstOrNull { text.hasBox(it) } ?: return null
        val l = text.left(i)
        val t = text.top(i)
        val r = text.right(i)
        val b = text.bottom(i)
        return when (text.quarter(i)) {
            1 -> if (atEnd) TextCaret(r, b, l, b) else TextCaret(r, t, l, t)
            2 -> if (atEnd) TextCaret(l, b, l, t) else TextCaret(r, b, r, t)
            3 -> if (atEnd) TextCaret(l, t, r, t) else TextCaret(l, b, r, b)
            else -> if (atEnd) TextCaret(r, t, r, b) else TextCaret(l, t, l, b)
        }
    }

    private class Run(text: PageText, i: Int) {
        var l = text.left(i)
        var t = text.top(i)
        var r = text.right(i)
        var b = text.bottom(i)
        val quarter = text.quarter(i)
        var glyphs = 1

        /** Whether the line runs down or up the page; upright CJK set in columns shows from its second glyph. */
        var vertical = quarter % 2 == 1

        fun takes(text: PageText, i: Int): Boolean {
            if (text.quarter(i) != quarter) return false
            val cl = text.left(i)
            val ct = text.top(i)
            val cr = text.right(i)
            val cb = text.bottom(i)
            val acrossRows = overlap(ct, cb, t, b) >= 0.5f * minOf(cb - ct, b - t)
            val acrossColumns = overlap(cl, cr, l, r) >= 0.5f * minOf(cr - cl, r - l)
            if (glyphs == 1 && quarter % 2 == 0 && !acrossRows && acrossColumns) vertical = true
            return if (vertical) {
                acrossColumns && maxOf(ct - b, t - cb, 0f) <= maxOf(cr - cl, r - l)
            } else {
                acrossRows && maxOf(cl - r, l - cr, 0f) <= maxOf(cb - ct, b - t)
            }
        }

        fun add(text: PageText, i: Int) {
            l = minOf(l, text.left(i))
            t = minOf(t, text.top(i))
            r = maxOf(r, text.right(i))
            b = maxOf(b, text.bottom(i))
            glyphs++
        }

        fun quad() = TextQuad(l, t, r, b, quarter)

        private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = minOf(a1, b1) - maxOf(a0, b0)
    }
}
