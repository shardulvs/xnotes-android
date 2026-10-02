package com.xnotes.core.search

/** A match in a line of its context for the results list: [text], with the match at [matchStart, matchEnd). */
class SearchSnippet(val text: String, val matchStart: Int, val matchEnd: Int) {
    companion object {
        private const val ELLIPSIS = "…"

        /** About [before] chars ahead of [hit] and [after] past it, cut at spaces where it can be. */
        fun of(hit: SearchHit, before: Int = 28, after: Int = 64): SearchSnippet {
            val t = hit.text.text
            var from = maxOf(0, hit.start - before)
            if (from > 0) {
                val space = t.indexOf(' ', from)
                if (space in from until hit.start) from = space + 1 else if (Character.isLowSurrogate(t[from])) from--
            }
            var to = minOf(t.length, hit.end + after)
            if (to < t.length) {
                val space = t.lastIndexOf(' ', to)
                if (space >= hit.end) to = space else if (Character.isLowSurrogate(t[to])) to++
            }
            val lead = if (from > 0) ELLIPSIS else ""
            val tail = if (to < t.length) ELLIPSIS else ""
            return SearchSnippet(lead + t.substring(from, to) + tail, lead.length + hit.start - from, lead.length + hit.end - from)
        }
    }
}
