package com.xnotes.core.util

/** Small synchronized cache bounded by retained geometry, rather than only entry count. */
internal class WeightedLruCache<K, V>(private val limit: Int, private val weigh: (K, V) -> Int) {
    private val entries = LinkedHashMap<K, Pair<V, Int>>(16, 0.75f, true)
    private var weight = 0

    fun getOrPut(key: K, create: () -> V): V {
        synchronized(this) { entries[key]?.let { return it.first } }
        // Triangulating a cache miss can be expensive. Never hold the LRU lock while
        // computing it: another thread may still serve a cache hit in the meantime.
        val value = create()
        val size = weigh(key, value).coerceAtLeast(1)
        if (size > limit) return value
        synchronized(this) {
            // Two callers can miss concurrently; keep the first completed entry.
            entries[key]?.let { return it.first }
            val iterator = entries.entries.iterator()
            while (weight > limit - size && iterator.hasNext()) {
                weight -= iterator.next().value.second
                iterator.remove()
            }
            entries[key] = value to size
            weight += size
        }
        return value
    }
}
