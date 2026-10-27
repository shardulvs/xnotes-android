package com.xnotes.core.audio

import com.xnotes.core.model.CanvasItem

/**
 * Recording [rid]'s items in the order they were added, each with where it sits ([P]: its page on a
 * note), so a moving playhead finds what it passed by a search rather than by walking the whole
 * document ten times a second.
 */
class SyncIndex<P>(val rid: Int, items: List<Pair<P, CanvasItem>>) {

    class Entry<P>(val ms: Int, val place: P, val item: CanvasItem)

    private val entries: List<Entry<P>> = items
        .mapNotNull { (place, item) -> item.sync?.takeIf { it.rid == rid }?.let { Entry(it.ms, place, item) } }
        .sortedBy { it.ms }

    val size: Int get() = entries.size

    /** The items added after [lo] and up to [hi] ms, oldest first. */
    fun between(lo: Int, hi: Int): List<Entry<P>> {
        if (hi <= lo || entries.isEmpty()) return emptyList()
        val from = firstAfter(lo)
        val to = firstAfter(hi)
        return if (from >= to) emptyList() else entries.subList(from, to)
    }

    /** The first entry later than [ms]. */
    private fun firstAfter(ms: Int): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].ms <= ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Seeking from ink starts this far before it was written, to hear the sentence that prompted it. */
        const val LEAD_IN_MS = 3_000
    }
}
