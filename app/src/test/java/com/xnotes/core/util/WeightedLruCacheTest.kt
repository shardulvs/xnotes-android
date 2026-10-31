package com.xnotes.core.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WeightedLruCacheTest {
    @Test fun retainsRecentlyUsedEntriesWithinBudget() {
        val cache = WeightedLruCache<String, String>(4) { _, value -> value.length }
        cache.getOrPut("a") { "aa" }
        cache.getOrPut("b") { "bb" }
        assertEquals("aa", cache.getOrPut("a") { error("cache miss") })
        cache.getOrPut("c") { "cc" }
        assertEquals("aa", cache.getOrPut("a") { error("recent entry evicted") })
        assertEquals("new", cache.getOrPut("b") { "new" })
    }

    @Test fun oversizedEntriesDoNotEvictUsefulEntries() {
        val cache = WeightedLruCache<String, String>(2) { _, value -> value.length }
        cache.getOrPut("a") { "aa" }
        assertEquals("large", cache.getOrPut("large") { "large" })
        assertEquals("aa", cache.getOrPut("a") { error("entry evicted") })
        assertEquals("fresh", cache.getOrPut("large") { "fresh" })
    }

    @Test fun aSlowMissDoesNotBlockAnUnrelatedCacheEntry() {
        val cache = WeightedLruCache<String, String>(10) { _, value -> value.length }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val slow = pool.submit<String> {
                cache.getOrPut("slow") {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    "slow"
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val fast = pool.submit<String> { cache.getOrPut("fast") { "fast" } }
            assertEquals("fast", fast.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("slow", slow.get(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
