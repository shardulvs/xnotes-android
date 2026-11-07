package com.xnotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class WorkersTest {
    @Test
    fun mapKeepsOrderOnAnyThreadCount() {
        for (threads in 1..4) Workers(threads).use { w ->
            assertEquals((1..50).map { it * 2 }, w.map((1..50).toList()) { Thread.sleep((50 - it) % 3L); it * 2 })
        }
    }

    @Test
    fun mapThrowsTheFailureInsteadOfAPartialResult() {
        for (threads in 1..4) Workers(threads).use { w ->
            val e = runCatching { w.map((1..20).toList()) { if (it == 7) throw IOException("listing failed") else it } }.exceptionOrNull()
            assertTrue(e is IOException)
        }
    }

    @Test
    fun awaitAllWaitsForEveryTaskAndNeverRunsMoreThanAllowed() {
        for (threads in 1..4) Workers(threads).use { w ->
            val running = AtomicInteger()
            val most = AtomicInteger()
            val done = AtomicInteger()
            repeat(40) {
                w.submit {
                    most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    Thread.sleep(2)
                    running.decrementAndGet()
                    done.incrementAndGet()
                }
            }
            w.awaitAll()
            assertEquals(40, done.get())
            assertTrue(most.get() <= threads)
        }
    }
}
