package com.xnotes.core.search

import com.xnotes.core.pdf.PageText

/** What to look for, as typed, and the two toggles. */
data class SearchQuery(val text: String, val matchCase: Boolean = false, val wholeWords: Boolean = false) {
    /** The query as [SearchText.find] compares it: built like a text, then folded. */
    internal val pattern: String = SearchText.pattern(text, matchCase)

    val isEmpty: Boolean get() = pattern.isEmpty()
}

/**
 * A text made searchable. [text] is the source as one line, the way a reader takes it in: line
 * breaks and whitespace runs are one space, a line-end hyphen joins its word, invisible characters
 * and characters without text are gone, and compatibility forms that stand for several letters
 * (ligatures, ellipses, fractions) are spelt out. The rest stays as written, so a snippet reads like
 * the page; [find] folds case, accents and the like as it compares. Each char traces back to the
 * source character it came from through [sourceIndex].
 */
class SearchText private constructor(
    val text: String,
    /** Where the run of source indexes restarts, as pairs: text index, then its source index. */
    private val restarts: IntArray,
    /** Chars standing for a whole surrogate pair of a string (a spelt-out 𝑥), in order. */
    private val wide: IntArray,
) {
    val length: Int get() = text.length

    /** The source index of char [i]: a PDF character's index, or a string's char index. */
    fun sourceIndex(i: Int): Int {
        var lo = 0
        var hi = restarts.size / 2 - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (restarts[2 * mid] <= i) lo = mid else hi = mid - 1
        }
        return restarts[2 * lo + 1] + (i - restarts[2 * lo])
    }

    /** Where in the source a match of chars [from, to) starts. */
    fun sourceStart(from: Int): Int = sourceIndex(from)

    /** Where in the source a match of chars [from, to) ends, exclusive. */
    fun sourceEnd(to: Int): Int = sourceIndex(to - 1) + if (wide.isNotEmpty() && wide.binarySearch(to - 1) >= 0) 2 else 1

    /**
     * Every match of [query] in order, none overlapping, as pairs of char indexes: start, then end
     * (exclusive). A match takes the lone accents that follow it.
     */
    fun find(query: SearchQuery): IntArray {
        val p = query.pattern
        if (p.isEmpty()) return EMPTY
        val matchCase = query.matchCase
        var out = IntArray(8)
        var count = 0
        val n = text.length
        var i = 0
        while (i < n) {
            if (SearchFold.fold(text[i], matchCase) == p[0]) {
                val end = matchAt(i, p, matchCase)
                if (end > 0 && (!query.wholeWords || !touchesWord(i, end))) {
                    if (count + 2 > out.size) out = out.copyOf(2 * out.size)
                    out[count++] = i
                    out[count++] = end
                    i = end
                    continue
                }
            }
            i++
        }
        return out.copyOf(count)
    }

    /** The end of a match of [p] whose first char is at [start], or -1. */
    private fun matchAt(start: Int, p: String, matchCase: Boolean): Int {
        val n = text.length
        var j = start + 1
        var k = 1
        while (k < p.length) {
            if (j >= n) return -1
            val f = SearchFold.fold(text[j], matchCase)
            j++
            if (f == SearchFold.IGNORE) continue
            if (f != p[k]) return -1
            k++
        }
        while (j < n && SearchFold.fold(text[j], matchCase) == SearchFold.IGNORE) j++
        return j
    }

    private fun touchesWord(from: Int, to: Int): Boolean =
        (from > 0 && SearchFold.isWordChar(text.codePointBefore(from))) ||
            (to < text.length && SearchFold.isWordChar(text.codePointAt(to)))

    private class Builder(capacity: Int) {
        private val sb = StringBuilder(capacity)
        private var restarts = IntArray(8)
        private var count = 0
        private var last = Int.MIN_VALUE
        private var wide = IntArray(0)

        /** Adds codepoint [cp] of the source character at [src]; a pair's low half traces to [lowSrc]. */
        fun add(cp: Int, src: Int, lowSrc: Int) {
            when (SearchFold.kind(cp)) {
                SearchFold.SPACE -> space(src)
                SearchFold.DROP -> Unit
                SearchFold.EXPAND -> {
                    val before = sb.length
                    for (c in SearchFold.expansion(cp)) if (c == ' ') space(src) else put(c, src)
                    if (lowSrc != src && sb.length > before) wide += sb.length - 1
                }
                else -> if (Character.isBmpCodePoint(cp)) {
                    put(cp.toChar(), src)
                } else {
                    put(Character.highSurrogate(cp), src)
                    put(Character.lowSurrogate(cp), lowSrc)
                }
            }
        }

        private fun space(src: Int) {
            if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') put(' ', src)
        }

        private fun put(c: Char, src: Int) {
            if (src != last + 1) {
                if (count + 2 > restarts.size) restarts = restarts.copyOf(2 * restarts.size)
                restarts[count++] = sb.length
                restarts[count++] = src
            }
            sb.append(c)
            last = src
        }

        fun build(): SearchText {
            if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
                sb.setLength(sb.length - 1)
                if (count > 0 && restarts[count - 2] == sb.length) count -= 2
                if (wide.isNotEmpty() && wide.last() == sb.length) wide = wide.copyOf(wide.size - 1)
            }
            return SearchText(sb.toString(), restarts.copyOf(count), wide)
        }
    }

    companion object {
        private val EMPTY = IntArray(0)

        /** A PDF page's text: characters without text and line-end hyphens are left out. */
        fun of(page: PageText): SearchText = ofPdf(page.length, page::codepoint, page::isHyphen)

        /** A PDF page's text from its [codepoints] and [PageText] flag bits alone. */
        fun ofPdf(codepoints: IntArray, flags: ByteArray): SearchText =
            ofPdf(codepoints.size, { codepoints[it] }, { flags[it].toInt() and PageText.HYPHEN != 0 })

        private inline fun ofPdf(length: Int, codepoint: (Int) -> Int, isHyphen: (Int) -> Boolean): SearchText {
            val b = Builder(length)
            for (i in 0 until length) {
                val cp = codepoint(i)
                if (cp == 0 || isHyphen(i)) continue
                b.add(cp, i, i)
            }
            return b.build()
        }

        /** Typed text, traced back by char index. */
        fun of(source: String): SearchText {
            val b = Builder(source.length)
            var i = 0
            while (i < source.length) {
                val cp = source.codePointAt(i)
                val units = Character.charCount(cp)
                b.add(cp, i, i + units - 1)
                i += units
            }
            return b.build()
        }

        internal fun pattern(query: String, matchCase: Boolean): String {
            val built = of(query).text
            val sb = StringBuilder(built.length)
            for (c in built) {
                val f = SearchFold.fold(c, matchCase)
                if (f != SearchFold.IGNORE) sb.append(f)
            }
            return sb.toString().trim(' ')
        }
    }
}
