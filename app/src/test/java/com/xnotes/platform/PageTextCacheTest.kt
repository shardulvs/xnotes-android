package com.xnotes.platform

import com.xnotes.core.pdf.PageText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PageTextCacheTest {

    private val reads: MutableList<Int> = Collections.synchronizedList(ArrayList())
    private var gate: CountDownLatch? = null
    private var unreadable = emptySet<Int>()

    private val cache = PageTextCache { index ->
        reads += index
        gate?.await(5, TimeUnit.SECONDS)
        if (index in unreadable) null else PageText(IntArray(1) { index }, FloatArray(4), ByteArray(1), null)
    }

    @After
    fun close() = cache.close()

    private fun eventually(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "timed out" }
            Thread.sleep(2)
        }
    }

    @Test
    fun requestReadsOnceThenServesTheCopyHeld() {
        val got = CountDownLatch(1)
        var text: PageText? = null
        cache.request(3) { text = it; got.countDown() }
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertEquals(3, text!!.codepoint(0))
        var again: PageText? = null
        cache.request(3) { again = it }
        assertSame(text, again)
        assertEquals(listOf(3), reads.toList())
    }

    @Test
    fun keepsOnlyTheLastPagesUsed() {
        for (i in 0 until PageTextCache.CAPACITY) cache.get(i)
        cache.get(0)
        cache.get(PageTextCache.CAPACITY)
        assertNotNull(cache.peek(0))
        assertNull(cache.peek(1))
        assertEquals(PageTextCache.CAPACITY, (0..PageTextCache.CAPACITY).count { cache.peek(it) != null })
    }

    @Test
    fun prefetchSkipsPagesAskedForInBetween() {
        val open = CountDownLatch(1)
        gate = open
        cache.prefetch(0)
        eventually { reads.isNotEmpty() }
        cache.prefetch(1)
        cache.prefetch(2)
        cache.prefetch(3)
        open.countDown()
        eventually { cache.peek(3) != null }
        assertEquals(listOf(0, 3), reads.toList())
    }

    @Test
    fun prefetchOfAPageHeldReadsNothing() {
        cache.get(5)
        cache.prefetch(5)
        Thread.sleep(50)
        assertEquals(listOf(5), reads.toList())
    }

    @Test
    fun aPageThatCannotBeReadIsNotKept() {
        unreadable = setOf(4)
        val got = CountDownLatch(1)
        var text: PageText? = PageText(IntArray(0), FloatArray(0), ByteArray(0), null)
        cache.request(4) { text = it; got.countDown() }
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertNull(text)
        assertNull(cache.get(4))
        assertEquals(listOf(4, 4), reads.toList())
    }

    @Test
    fun nothingIsReadOnceClosed() {
        cache.close()
        cache.prefetch(1)
        var called = false
        cache.request(2) { called = true }
        assertNull(cache.get(3))
        Thread.sleep(50)
        assertTrue(reads.isEmpty())
        assertTrue(!called)
    }
}
