package com.xnotes.platform

import com.xnotes.core.pdf.PageText
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The text of the PDF pages last touched, read off the main thread by [read] (a PDF page index to
 * its text, null when it can't be read). [prefetch] starts on the page under a pointer as it goes
 * down, the latest page winning, so a long press finds the text waiting; [request] is the read a
 * gesture that needs the text now waits on. Only [CAPACITY] pages are kept, so memory stays
 * bounded however long the session.
 */
class PageTextCache(private val read: (Int) -> PageText?) {
    /** Least recently used first; guarded by itself. */
    private val pages = LinkedHashMap<Int, PageText>(CAPACITY + 1, 0.75f, true)

    /** The page [prefetch] last asked for, -1 once read. */
    private val wanted = AtomicInteger(-1)

    /** Whether a prefetch pass is queued or running, so a burst of [prefetch] calls makes one. */
    private val prefetching = AtomicBoolean(false)

    /** Guards [worker]'s lifecycle. */
    private val workerLock = Any()
    private var worker: ExecutorService? = null

    @Volatile private var closed = false

    /** Page [index]'s text when held; never waits. */
    fun peek(index: Int): PageText? = synchronized(pages) { pages[index] }

    /** Starts reading page [index]'s text in the background unless held, in place of any page asked for before not yet begun. */
    fun prefetch(index: Int) {
        if (closed || index < 0 || peek(index) != null) return
        wanted.set(index)
        if (prefetching.compareAndSet(false, true) && !submit(::prefetchPass)) prefetching.set(false)
    }

    /**
     * Reads page [index]'s text unless held, then hands it to [onReady], null when it can't be read:
     * at once when held, else on the worker thread. Never called after [close].
     */
    fun request(index: Int, onReady: (PageText?) -> Unit) {
        peek(index)?.let { return onReady(it) }
        submit { if (!closed) load(index).let { if (!closed) onReady(it) } }
    }

    /** Page [index]'s text, read now unless held; waits, so never on the main thread. */
    fun get(index: Int): PageText? = if (closed) null else load(index)

    fun close() {
        closed = true
        synchronized(workerLock) { runCatching { worker?.shutdownNow() } }
        synchronized(pages) { pages.clear() }
    }

    private fun prefetchPass() {
        try {
            while (!closed) {
                val index = wanted.get()
                if (index < 0) break
                load(index)
                wanted.compareAndSet(index, -1)
            }
        } finally {
            prefetching.set(false)
            // A prefetch that came as the pass was ending gets a pass of its own.
            wanted.get().let { if (!closed && it >= 0) prefetch(it) }
        }
    }

    private fun load(index: Int): PageText? {
        peek(index)?.let { return it }
        val text = read(index) ?: return null
        synchronized(pages) {
            pages[index] = text
            if (pages.size > CAPACITY) pages.remove(pages.keys.first())
        }
        return text
    }

    private fun submit(task: () -> Unit): Boolean = synchronized(workerLock) {
        if (closed) return false
        val w = worker ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "xnotes-pdf-text").apply { isDaemon = true }
        }.also { worker = it }
        runCatching { w.execute(task) }.isSuccess
    }

    companion object {
        /** Pages kept: the one being read, plus a few to come back to or select across. */
        const val CAPACITY = 4
    }
}
