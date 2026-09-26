package com.xnotes.core.infinite

/**
 * A first-fit allocator over a linear array of slots, with adjacent free ranges coalesced so a
 * document that is edited for hours does not fragment its buffers into uselessness.
 */
class SlotAllocator(initialCapacity: Int) {

    var capacity: Int = initialCapacity
        private set

    /** Slots handed out and not yet freed. */
    var used: Int = 0
        private set

    private var bump = 0

    /** One past the highest slot handed out; the holes below it are not room for a new run. */
    val end: Int get() = bump

    // Free ranges as (offset, count) pairs, kept sorted by offset so neighbours coalesce.
    private val freeOffsets = ArrayList<Int>()
    private val freeCounts = ArrayList<Int>()

    fun allocate(count: Int): Int? {
        if (count <= 0) return null
        for (i in freeOffsets.indices) {
            val have = freeCounts[i]
            if (have < count) continue
            val offset = freeOffsets[i]
            if (have == count) {
                freeOffsets.removeAt(i)
                freeCounts.removeAt(i)
            } else {
                freeOffsets[i] = offset + count
                freeCounts[i] = have - count
            }
            used += count
            return offset
        }
        if (bump + count > capacity) return null
        val offset = bump
        bump += count
        used += count
        return offset
    }

    fun free(offset: Int, count: Int) {
        if (count <= 0) return
        used -= count
        // A range freed at the very end just rewinds the bump pointer, which is the common case
        // while drawing: the newest stroke is the one most likely to be undone.
        if (offset + count == bump) {
            bump = offset
            reclaimTail()
            return
        }
        var i = 0
        while (i < freeOffsets.size && freeOffsets[i] < offset) i++
        freeOffsets.add(i, offset)
        freeCounts.add(i, count)
        coalesceAround(i)
    }

    fun reset() {
        bump = 0
        used = 0
        freeOffsets.clear()
        freeCounts.clear()
    }

    fun grow(newCapacity: Int) {
        if (newCapacity > capacity) capacity = newCapacity
    }

    /** Capacity that fits [count] more slots past [end], doubled from the current, or null past an Int. */
    fun grownCapacity(count: Int): Int? {
        val need = bump.toLong() + count
        var cap = maxOf(capacity, 1).toLong()
        while (cap < need) cap *= 2
        return if (cap > Int.MAX_VALUE) null else cap.toInt()
    }

    /** Capacity for a bulk load of [count] more slots past [end], an eighth over, or null past an Int. */
    fun reservedCapacity(count: Long): Int? {
        val need = bump + count
        val cap = need + need / 8
        return if (cap > Int.MAX_VALUE) null else maxOf(capacity.toLong(), cap).toInt()
    }

    /** Pull any free range that now touches the bump pointer back out of the free list. */
    private fun reclaimTail() {
        while (freeOffsets.isNotEmpty()) {
            val last = freeOffsets.size - 1
            if (freeOffsets[last] + freeCounts[last] != bump) return
            bump = freeOffsets[last]
            freeOffsets.removeAt(last)
            freeCounts.removeAt(last)
        }
    }

    private fun coalesceAround(i: Int) {
        var at = i
        if (at + 1 < freeOffsets.size && freeOffsets[at] + freeCounts[at] == freeOffsets[at + 1]) {
            freeCounts[at] = freeCounts[at] + freeCounts[at + 1]
            freeOffsets.removeAt(at + 1)
            freeCounts.removeAt(at + 1)
        }
        if (at > 0 && freeOffsets[at - 1] + freeCounts[at - 1] == freeOffsets[at]) {
            freeCounts[at - 1] = freeCounts[at - 1] + freeCounts[at]
            freeOffsets.removeAt(at)
            freeCounts.removeAt(at)
            at--
        }
        if (at >= 0 && at < freeOffsets.size && freeOffsets[at] + freeCounts[at] == bump) {
            bump = freeOffsets[at]
            freeOffsets.removeAt(at)
            freeCounts.removeAt(at)
            reclaimTail()
        }
    }
}
