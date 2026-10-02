package com.xnotes.core.search

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Typed text to search: [source] in [target], whose char i lies on note page [pageOf] (i). */
class TypedText(val target: SearchTarget, val source: String, val pageOf: (Int) -> Int)

/** What a search reads. [pdfPages] and [typedTexts] are called on the main thread, [pdfText] on the session's. */
interface SearchCorpus {
    /** For each note page, the page of its PDF it shows, or -1. */
    fun pdfPages(): IntArray

    /** The flow's paragraphs, then the text boxes, as they are now. */
    fun typedTexts(): List<TypedText>

    /** PDF page [index]'s text, read now; null when it has none. Blocks. */
    fun pdfText(index: Int): SearchText?
}

/** The main thread, as a session posts to it. */
interface UiThread {
    fun post(r: Runnable)

    fun postDelayed(r: Runnable, delayMs: Long)

    fun cancel(r: Runnable)
}

/**
 * One search of a note, from the Search tab opening until it closes. A query waits for typing to
 * pause, then a scan on [worker] finds the typed text's matches at once and reads the PDF a page at
 * a time, from the page in view to the end and round to it again, publishing results as they grow.
 * A newer query or [close] ends a scan. The PDF pages' texts stay for later queries until [close]:
 * they are what the session holds in RAM.
 */
class SearchSession(
    private val corpus: SearchCorpus,
    private val ui: UiThread,
    private val worker: Executor,
    private val maxHits: Int = MAX_HITS,
) {
    /** The latest results, on the main thread; null once the query is empty. */
    var onResults: (SearchResults?) -> Unit = {}

    private val pdfTexts = ConcurrentHashMap<Int, SearchText>()

    /** Bumped by every scan and by [close]; a scan whose number moved on stops. */
    @Volatile private var generation = 0
    private var closed = false
    private var pending: SearchQuery? = null
    private var pendingFrom = 0

    private val start = Runnable {
        val q = pending ?: return@Runnable
        pending = null
        scan(q, pendingFrom)
    }

    /** Looks for [query] from note page [fromPage] once typing pauses, or [now]. */
    fun search(query: SearchQuery, fromPage: Int, now: Boolean = false) {
        if (closed) return
        ui.cancel(start)
        if (query.isEmpty) {
            pending = null
            generation++
            onResults(null)
            return
        }
        pending = query
        pendingFrom = fromPage
        if (now) start.run() else ui.postDelayed(start, DEBOUNCE_MS)
    }

    /** Ends the search and lets go of the texts read. */
    fun close() {
        closed = true
        generation++
        pending = null
        ui.cancel(start)
        pdfTexts.clear()
    }

    private fun scan(query: SearchQuery, fromPage: Int) {
        val gen = ++generation
        val pdfPages = corpus.pdfPages()
        val typed = corpus.typedTexts()
        worker.execute { Scan(gen, query, fromPage, pdfPages, typed).run() }
    }

    private inner class Scan(
        private val gen: Int,
        private val query: SearchQuery,
        private val fromPage: Int,
        private val pdfPages: IntArray,
        private val typed: List<TypedText>,
    ) {
        private val n = pdfPages.size
        private val byPage = Array<List<SearchHit>>(n) { emptyList() }
        private val complete = BooleanArray(n) { pdfPages[it] < 0 }
        private val toRead = pdfPages.count { it >= 0 }
        private var read = 0
        private var count = 0
        private var capped = false
        private var lastPublish = 0L
        private var pdfShown = false

        private val live: Boolean get() = generation == gen

        fun run() {
            val typedByPage = Array(n) { ArrayList<SearchHit>() }
            for (t in typed) {
                if (!live) return
                val text = SearchText.of(t.source)
                val m = text.find(query)
                for (k in m.indices step 2) {
                    val page = t.pageOf(text.sourceStart(m[k]))
                    if (page !in 0 until n) continue
                    if (count == maxHits) {
                        capped = true
                        break
                    }
                    typedByPage[page] += SearchHit(page, t.target, text, m[k], m[k + 1])
                    count++
                }
            }
            for (p in 0 until n) byPage[p] = typedByPage[p]
            publish(force = true)
            for (step in 0 until n) {
                if (capped) break
                val p = (fromPage.coerceIn(0, maxOf(n - 1, 0)) + step) % n
                val pdf = pdfPages[p]
                if (pdf < 0) continue
                if (!live) return
                val text = pdfTexts[pdf] ?: corpus.pdfText(pdf)?.also { pdfTexts[pdf] = it }
                if (!live) return
                val found = text?.find(query) ?: IntArray(0)
                val hits = ArrayList<SearchHit>(found.size / 2 + byPage[p].size)
                for (k in found.indices step 2) {
                    if (count == maxHits) {
                        capped = true
                        break
                    }
                    hits += SearchHit(p, SearchTarget.Pdf, text!!, found[k], found[k + 1])
                    count++
                }
                hits += byPage[p]
                byPage[p] = hits
                complete[p] = true
                read++
                publish(force = found.isNotEmpty() && !pdfShown)
                if (found.isNotEmpty()) pdfShown = true
            }
            publish(force = true, done = true)
        }

        private fun publish(force: Boolean, done: Boolean = false) {
            val now = System.nanoTime()
            if (!force && now - lastPublish < PUBLISH_NS) return
            lastPublish = now
            val results = SearchResults(query, byPage.copyOf(), complete.copyOf(), read, toRead, done, capped)
            ui.post { if (live && !closed) onResults(results) }
        }
    }

    companion object {
        /** How long typing must pause before a query is looked for. */
        const val DEBOUNCE_MS = 250L

        /** The most matches a search keeps. */
        const val MAX_HITS = 10_000

        /** How often a scan shows its progress. */
        private const val PUBLISH_NS = 100_000_000L
    }
}
