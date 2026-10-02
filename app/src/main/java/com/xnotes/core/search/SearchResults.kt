package com.xnotes.core.search

import com.xnotes.core.model.TextItem

/** What a match lies in. */
sealed interface SearchTarget {
    /** The PDF page its note page shows. */
    object Pdf : SearchTarget

    /** Paragraph [para] of the typed flow. */
    data class Flow(val para: Int) : SearchTarget

    /** A text box. */
    data class Box(val item: TextItem) : SearchTarget
}

/** One match: chars [start, end) of [text], which lies in [target] on note page [page]. */
class SearchHit(val page: Int, val target: SearchTarget, val text: SearchText, val start: Int, val end: Int) {
    /** The first source character matched: a PDF character's index, or a char index of the typed text. */
    val sourceStart: Int get() = text.sourceStart(start)

    /** Just past the last source character matched. */
    val sourceEnd: Int get() = text.sourceEnd(end)
}

/**
 * The matches found so far, in page order; on a page the PDF's come first, then the flow's, then
 * the text boxes'. Each update is a new object, and the hits it shares with the one before are the
 * same objects, so a hit can be followed by identity while a scan goes on.
 */
class SearchResults internal constructor(
    val query: SearchQuery,
    private val byPage: Array<List<SearchHit>>,
    /** Whether all of a page's matches are in: its PDF text was read, or it has none. */
    private val complete: BooleanArray,
    /** PDF pages read so far, of [toRead]. */
    val read: Int,
    val toRead: Int,
    /** The scan ended: everything is in, or [capped]. */
    val done: Boolean,
    /** The scan stopped at the most matches it keeps; pages it did not reach may hold more. */
    val capped: Boolean,
) {
    private val starts = IntArray(byPage.size + 1).also { s ->
        for (p in byPage.indices) s[p + 1] = s[p] + byPage[p].size
    }

    val count: Int get() = starts[byPage.size]

    /** The note pages that have matches, in order. */
    val pagesWithHits: List<Int> by lazy { byPage.indices.filter { byPage[it].isNotEmpty() } }

    fun hitsOn(page: Int): List<SearchHit> = byPage.getOrNull(page).orEmpty()

    /** Hit [index] in page order. */
    fun hit(index: Int): SearchHit {
        var lo = 0
        var hi = byPage.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= index) lo = mid else hi = mid - 1
        }
        return byPage[lo][index - starts[lo]]
    }

    /** [hit]'s index in page order, or -1 when these results do not hold it. */
    fun indexOf(hit: SearchHit): Int {
        val list = byPage.getOrNull(hit.page) ?: return -1
        val k = list.indexOfFirst { it === hit }
        return if (k < 0) -1 else starts[hit.page] + k
    }

    /**
     * The index of the first hit on [page] or after it, going round to the first page; null while
     * a page it would pass is still being read, -1 when there is none.
     */
    fun firstFrom(page: Int): Int? {
        val n = byPage.size
        for (step in 0 until n) {
            val p = (page.coerceIn(0, maxOf(n - 1, 0)) + step) % n
            if (!complete[p] && !done) return null
            if (byPage[p].isNotEmpty()) return starts[p]
        }
        return -1
    }
}
