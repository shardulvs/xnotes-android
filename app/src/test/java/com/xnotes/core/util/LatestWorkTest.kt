package com.xnotes.core.util

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class LatestWorkTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun aBurstComputesOnlyTheNewestRequestForEachItem() {
        val worker = Queue()
        val main = Queue()
        val work = LatestWork<String>(worker) { main.execute(it) }
        val computed = ArrayList<Int>()
        val shown = ArrayList<Int>()
        for (i in 1..100) work.submit("text", { computed.add(i); i }, shown::add, { throw it })
        work.submit("other", { 200 }, shown::add, { throw it })
        assertEquals(1, worker.tasks.size)
        worker.drain()
        main.drain()
        assertEquals(listOf(100), computed)
        assertEquals(listOf(100, 200), shown)
    }

    @Test fun resultsQueuedBeforeANewRequestOrRemovalCannotPublish() {
        val worker = Queue()
        val main = Queue()
        val work = LatestWork<String>(worker) { main.execute(it) }
        val shown = ArrayList<Int>()
        work.submit("text", { 1 }, shown::add, { throw it })
        worker.drain()
        work.submit("text", { 2 }, shown::add, { throw it })
        main.drain()
        assertTrue(shown.isEmpty())
        worker.drain()
        work.cancel("text")
        main.drain()
        assertTrue(shown.isEmpty())
    }

    @Test fun aRequestArrivingDuringComputationReplacesThePendingWork() {
        val worker = Queue()
        val main = Queue()
        val work = LatestWork<String>(worker) { main.execute(it) }
        val shown = ArrayList<Int>()
        work.submit("text", {
            work.submit("text", { 2 }, shown::add, { throw it })
            work.submit("text", { 3 }, shown::add, { throw it })
            1
        }, shown::add, { throw it })
        worker.drain()
        main.drain()
        assertEquals(listOf(3), shown)
    }

    @Test fun resetInvalidatesResultsAndFailuresDoNotBlockLaterWork() {
        val worker = Queue()
        val main = Queue()
        val work = LatestWork<String>(worker) { main.execute(it) }
        val shown = ArrayList<Int>()
        val errors = ArrayList<Exception>()
        work.submit<Int>("bad", { error("failed") }, shown::add, errors::add)
        worker.drain()
        work.clear()
        main.drain()
        assertTrue(errors.isEmpty())
        work.submit<Int>("bad", { error("failed") }, shown::add, errors::add)
        work.submit("good", { 5 }, shown::add, errors::add)
        worker.drain()
        main.drain()
        assertEquals(1, errors.size)
        assertEquals(listOf(5), shown)
    }
    @Test fun previewsUpdateDuringContinuousInputButCannotResurrectACanceledItem() {
        val worker = Queue()
        val main = Queue()
        val work = LatestWork<String>(worker) { main.execute(it) }
        val group = Any()
        val shown = ArrayList<Int>()
        work.submit("text", { 1 }, shown::add, { throw it }, group)
        worker.drain()
        work.submit("text", { 2 }, shown::add, { throw it }, group)
        main.drain()
        assertEquals(listOf(1), shown)
        worker.drain()
        work.cancel("text")
        work.submit("text", { 3 }, shown::add, { throw it }, group)
        main.drain()
        assertEquals(listOf(1), shown)
        worker.drain()
        main.drain()
        assertEquals(listOf(1, 3), shown)
    }

    @Test fun previewsWithDifferentContentOrStyleCannotReplaceTheCurrentRequest() {
        val worker = Queue()
        val main = Queue()
        val work = LatestWork<String>(worker) { main.execute(it) }
        val shown = ArrayList<Int>()
        work.submit("text", { 1 }, shown::add, { throw it }, Any())
        worker.drain()
        work.submit("text", { 2 }, shown::add, { throw it }, Any())
        main.drain()
        assertTrue(shown.isEmpty())
        worker.drain()
        main.drain()
        assertEquals(listOf(2), shown)
    }

    @Test fun canceledRasterResultIsDiscardedInsteadOfPublished() {
        val worker = Queue()
        val main = Queue()
        val work = LatestWork<String>(worker) { main.execute(it) }
        val shown = ArrayList<Int>()
        val discarded = ArrayList<Int>()
        work.submit("text", { 42 }, shown::add, { throw it }, discard = discarded::add)
        worker.drain()
        work.cancel("text")
        main.drain()
        assertTrue(shown.isEmpty())
        assertEquals(listOf(42), discarded)
    }

    @Test fun interruptedDelayDoesNotAbandonPendingWork() {
        val worker = Queue()
        val main = Queue()
        val work = LatestWork<String>(worker, minimumStartIntervalMs = 100L) { main.execute(it) }
        val shown = ArrayList<Int>()
        work.submit("first", { Thread.currentThread().interrupt(); 1 }, shown::add, { throw it })
        work.submit("pending", { 2 }, shown::add, { throw it })
        try {
            worker.drain()
        } finally {
            // The deterministic queue executes on the test thread, unlike the app's worker.
            Thread.interrupted()
        }
        main.drain()
        assertEquals(listOf(1, 2), shown)
        work.submit("later", { 3 }, shown::add, { throw it })
        worker.drain()
        main.drain()
        assertEquals(listOf(1, 2, 3), shown)
    }

}
