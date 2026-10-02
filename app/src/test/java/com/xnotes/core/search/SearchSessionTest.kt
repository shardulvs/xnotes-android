package com.xnotes.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class SearchSessionTest {

    /** The main thread, run by hand: [advance] moves the clock and runs what is due. */
    private class FakeUi : UiThread {
        private var now = 0L
        private val queue = ArrayDeque<Runnable>()
        private val delayed = ArrayList<Pair<Long, Runnable>>()

        override fun post(r: Runnable) {
            queue += r
        }

        override fun postDelayed(r: Runnable, delayMs: Long) {
            delayed += (now + delayMs) to r
        }

        override fun cancel(r: Runnable) {
            delayed.removeAll { it.second === r }
            queue.remove(r)
        }

        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = delayed.filter { it.first <= now }
                if (due.isEmpty()) break
                delayed.removeAll(due)
                due.forEach { it.second.run() }
            }
            drain()
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private class FakeCorpus(
        private val pages: IntArray,
        private val pdf: Map<Int, String> = emptyMap(),
        private val typed: List<TypedText> = emptyList(),
    ) : SearchCorpus {
        val reads = ArrayList<Int>()
        var onRead: (Int) -> Unit = {}

        override fun pdfPages(): IntArray = pages

        override fun typedTexts(): List<TypedText> = typed

        override fun pdfText(index: Int): SearchText? {
            reads += index
            onRead(index)
            return pdf[index]?.let { SearchText.of(it) }
        }
    }

    private val ui = FakeUi()
    private val inline = Executor { it.run() }

    private fun session(corpus: SearchCorpus, maxHits: Int = SearchSession.MAX_HITS): Pair<SearchSession, MutableList<SearchResults?>> {
        val got = mutableListOf<SearchResults?>()
        val s = SearchSession(corpus, ui, inline, maxHits)
        s.onResults = { got += it }
        return s to got
    }

    private fun SearchResults.texts(): List<String> = (0 until count).map { hit(it) }.map { it.text.text.substring(it.start, it.end) }

    private fun SearchResults.pages(): List<Int> = (0 until count).map { hit(it).page }

    @Test
    fun waitsForTypingToPause() {
        val corpus = FakeCorpus(intArrayOf(0), mapOf(0 to "alpha beta"))
        val (s, got) = session(corpus)
        s.search(SearchQuery("a"), 0)
        ui.advance(100)
        s.search(SearchQuery("al"), 0)
        ui.advance(200)
        assertTrue(corpus.reads.isEmpty())
        ui.advance(SearchSession.DEBOUNCE_MS)
        assertEquals(listOf(0), corpus.reads)
        assertEquals(listOf("al"), got.last()!!.texts())
        assertTrue(got.last()!!.done)
    }

    @Test
    fun readsThePdfFromThePageInViewRoundToItAgain() {
        val corpus = FakeCorpus(intArrayOf(0, 1, 2, 3, 4), (0..4).associateWith { "x$it" })
        val (s, got) = session(corpus)
        s.search(SearchQuery("x"), 2, now = true)
        ui.drain()
        assertEquals(listOf(2, 3, 4, 0, 1), corpus.reads)
        assertEquals(listOf(0, 1, 2, 3, 4), got.last()!!.pages())
    }

    @Test
    fun aPageShowsItsPdfMatchesBeforeItsTypedOnes() {
        val typed = listOf(
            TypedText(SearchTarget.Flow(0), "cat in the flow") { 1 },
            TypedText(SearchTarget.Flow(1), "no match") { 0 },
        )
        val corpus = FakeCorpus(intArrayOf(0, 1), mapOf(0 to "cat", 1 to "a cat and a cat"), typed)
        val (s, got) = session(corpus)
        s.search(SearchQuery("cat"), 0, now = true)
        ui.drain()
        val r = got.last()!!
        assertEquals(listOf(0, 1, 1, 1), r.pages())
        assertEquals(listOf(SearchTarget.Pdf, SearchTarget.Pdf, SearchTarget.Pdf, SearchTarget.Flow(0)), (0 until 4).map { r.hit(it).target })
    }

    @Test
    fun typedMatchesLandOnThePageTheirTextIsOn() {
        val typed = listOf(TypedText(SearchTarget.Flow(0), "alpha beta alpha") { if (it < 6) 0 else 2 })
        val (s, got) = session(FakeCorpus(intArrayOf(-1, -1, -1), typed = typed))
        s.search(SearchQuery("alpha"), 0, now = true)
        ui.drain()
        val r = got.last()!!
        assertEquals(listOf(0, 2), r.pages())
        assertEquals(11, r.hit(1).sourceStart)
        assertEquals(16, r.hit(1).sourceEnd)
    }

    @Test
    fun theTypedMatchesShowBeforeAnyPdfPageIsRead() {
        val typed = listOf(TypedText(SearchTarget.Flow(0), "dog") { 0 })
        val corpus = FakeCorpus(intArrayOf(0), mapOf(0 to "dog"), typed)
        val (s, got) = session(corpus)
        s.search(SearchQuery("dog"), 0, now = true)
        ui.drain()
        val first = got.first()!!
        assertEquals(1, first.count)
        assertEquals(0, first.read)
        assertEquals(1, first.toRead)
        assertNull(first.firstFrom(0))
        assertEquals(2, got.last()!!.count)
        assertEquals(0, got.last()!!.firstFrom(0))
    }

    @Test
    fun aNewQueryEndsTheScanItOvertakes() {
        val corpus = FakeCorpus(intArrayOf(0, 1, 2, 3), (0..3).associateWith { "old new" })
        val (s, got) = session(corpus)
        corpus.onRead = { if (it == 1 && corpus.reads.size == 2) s.search(SearchQuery("new"), 0, now = true) }
        s.search(SearchQuery("old"), 0, now = true)
        ui.drain()
        assertEquals(listOf(0, 1, 2, 3), corpus.reads.distinct())
        assertEquals(4, corpus.reads.size - 1)
        val last = got.last()!!
        assertEquals("new", last.query.text)
        assertEquals(4, last.count)
        assertTrue(got.none { it!!.query.text == "old" && it.done })
    }

    @Test
    fun pdfTextsAreReadOncePerSession() {
        val corpus = FakeCorpus(intArrayOf(0, 1), mapOf(0 to "one", 1 to "two"))
        val (s, got) = session(corpus)
        s.search(SearchQuery("one"), 0, now = true)
        s.search(SearchQuery("two"), 0, now = true)
        ui.drain()
        assertEquals(listOf(0, 1), corpus.reads)
        assertEquals(listOf("two"), got.last()!!.texts())
    }

    @Test
    fun aDuplicatedPageIsReadOnceAndMatchedOnEach() {
        val corpus = FakeCorpus(intArrayOf(0, 0, 1), mapOf(0 to "same", 1 to "other"))
        val (s, got) = session(corpus)
        s.search(SearchQuery("same"), 0, now = true)
        ui.drain()
        assertEquals(listOf(0, 1), corpus.reads)
        assertEquals(listOf(0, 1), got.last()!!.pages())
    }

    @Test
    fun aScanStopsAtTheMostMatchesItKeeps() {
        val corpus = FakeCorpus(intArrayOf(0, 1, 2), mapOf(0 to "a a", 1 to "a a", 2 to "a a"))
        val (s, got) = session(corpus, maxHits = 3)
        s.search(SearchQuery("a"), 0, now = true)
        ui.drain()
        val r = got.last()!!
        assertEquals(3, r.count)
        assertTrue(r.capped)
        assertTrue(r.done)
        assertEquals(listOf(0, 1), corpus.reads)
    }

    @Test
    fun anEmptyQueryClearsAtOnce() {
        val corpus = FakeCorpus(intArrayOf(0), mapOf(0 to "abc"))
        val (s, got) = session(corpus)
        s.search(SearchQuery("abc"), 0)
        s.search(SearchQuery("  "), 0)
        assertEquals(listOf<SearchResults?>(null), got)
        ui.advance(SearchSession.DEBOUNCE_MS)
        assertTrue(corpus.reads.isEmpty())
    }

    @Test
    fun closingStopsEverything() {
        val corpus = FakeCorpus(intArrayOf(0, 1), mapOf(0 to "abc", 1 to "abc"))
        val (s, got) = session(corpus)
        s.search(SearchQuery("abc"), 0)
        s.close()
        ui.advance(SearchSession.DEBOUNCE_MS)
        s.search(SearchQuery("abc"), 0, now = true)
        ui.drain()
        assertTrue(corpus.reads.isEmpty())
        assertTrue(got.isEmpty())
    }

    @Test
    fun resultsFindTheFirstMatchFromAPageRoundToTheStart() {
        val corpus = FakeCorpus(intArrayOf(0, 1, 2, 3, 4), mapOf(1 to "hit", 3 to "hit hit"))
        val (s, got) = session(corpus)
        s.search(SearchQuery("hit"), 0, now = true)
        ui.drain()
        val r = got.last()!!
        assertEquals(1, r.firstFrom(2))
        assertEquals(1, r.firstFrom(3))
        assertEquals(0, r.firstFrom(4))
        assertEquals(listOf(1, 3), r.pagesWithHits)
        assertEquals(2, r.hitsOn(3).size)
        assertSame(r.hit(2), r.hitsOn(3)[1])
        assertEquals(2, r.indexOf(r.hit(2)))
    }

    @Test
    fun aHitKeepsItsIdentityAcrossUpdates() {
        val typed = listOf(TypedText(SearchTarget.Flow(0), "key") { 1 })
        val corpus = FakeCorpus(intArrayOf(0, 1), mapOf(0 to "key", 1 to "key"), typed)
        val (s, got) = session(corpus)
        s.search(SearchQuery("key"), 0, now = true)
        ui.drain()
        val flowHit = got.first()!!.hit(0)
        assertEquals(2, got.last()!!.indexOf(flowHit))
    }

    @Test
    fun aMissingPdfTextFindsNothingThere() {
        val (s, got) = session(FakeCorpus(intArrayOf(0, 1), mapOf(1 to "word")))
        s.search(SearchQuery("word"), 0, now = true)
        ui.drain()
        assertEquals(listOf(1), got.last()!!.pages())
        assertEquals(-1, SearchResults(SearchQuery("x"), arrayOf(emptyList()), booleanArrayOf(true), 1, 1, done = true, capped = false).firstFrom(0))
    }
}
