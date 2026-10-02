package com.xnotes.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSnippetTest {

    private fun snippet(source: String, query: String, before: Int, after: Int): String {
        val text = SearchText.of(source)
        val m = text.find(SearchQuery(query))
        val s = SearchSnippet.of(SearchHit(0, SearchTarget.Pdf, text, m[0], m[1]), before, after)
        return s.text.substring(0, s.matchStart) + "[" + s.text.substring(s.matchStart, s.matchEnd) + "]" + s.text.substring(s.matchEnd)
    }

    @Test
    fun aShortTextIsShownWhole() {
        assertEquals("the [quick] fox", snippet("the quick fox", "quick", 28, 64))
    }

    @Test
    fun longContextIsCutAtSpaces() {
        val s = "alpha beta gamma delta needle epsilon zeta eta theta"
        assertEquals("…gamma delta [needle] epsilon zeta…", snippet(s, "needle", 14, 14))
    }

    @Test
    fun aWordTooLongToCutAtASpaceIsCutWhereItMust() {
        assertEquals("…cdefgh[x]ijklmn…", snippet("abcdefghxijklmnop", "x", 6, 6))
    }

    @Test
    fun theSnippetReadsLikeThePage() {
        assertEquals("[Café] crème", snippet("Café\ncrème", "cafe", 28, 64))
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        val s = snippet("ab😀cdxef😀gh", "x", 3, 3)
        assertEquals("…😀cd[x]ef😀…", s)
    }
}
