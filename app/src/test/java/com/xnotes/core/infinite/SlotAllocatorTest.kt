package com.xnotes.core.infinite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotAllocatorTest {

    @Test fun allocationsAreHandedOutInOrder() {
        val a = SlotAllocator(100)
        assertEquals(0, a.allocate(10))
        assertEquals(10, a.allocate(5))
        assertEquals(15, a.allocate(1))
        assertEquals(16, a.used)
    }

    @Test fun anAllocationPastCapacityFails() {
        val a = SlotAllocator(10)
        assertEquals(0, a.allocate(10))
        assertNull(a.allocate(1))
    }

    @Test fun growingLetsTheNextAllocationThrough() {
        val a = SlotAllocator(10)
        a.allocate(10)
        assertNull(a.allocate(4))
        a.grow(20)
        assertEquals(10, a.allocate(4))
        assertEquals(20, a.capacity)
    }

    @Test fun growingNeverShrinks() {
        val a = SlotAllocator(50)
        a.grow(10)
        assertEquals(50, a.capacity)
    }

    @Test fun growthCountsFromTheEndRatherThanFromWhatIsLive() {
        val a = SlotAllocator(100)
        val slots = List(10) { a.allocate(10)!! }
        // Half of it free, but as five separate holes of ten.
        for (i in 0 until 10 step 2) a.free(slots[i], 10)
        assertEquals(50, a.used)
        assertEquals(100, a.end)
        assertNull(a.allocate(20))
        val cap = a.grownCapacity(20)!!
        assertEquals(200, cap)
        a.grow(cap)
        assertEquals(100, a.allocate(20))
    }

    @Test fun growthPastAnIntIsRefused() {
        val a = SlotAllocator(1 shl 30)
        a.allocate(1 shl 30)
        assertNull(a.grownCapacity(1))
    }

    @Test fun aReservationFitsTheLoadWithAnEighthToSpare() {
        val a = SlotAllocator(100)
        val cap = a.reservedCapacity(1000)!!
        assertEquals(1125, cap)
        a.grow(cap)
        repeat(11) { assertNotNull(a.allocate(100)) }
        assertNull(a.allocate(100))
    }

    @Test fun aReservationCountsFromTheEnd() {
        val a = SlotAllocator(100)
        a.allocate(80)
        assertEquals(112, a.reservedCapacity(20))
    }

    @Test fun aReservationNeverShrinks() {
        val a = SlotAllocator(5000)
        assertEquals(5000, a.reservedCapacity(1000))
    }

    @Test fun aReservationPastAnIntIsRefused() {
        assertNull(SlotAllocator(100).reservedCapacity(Int.MAX_VALUE.toLong()))
    }

    @Test fun aZeroOrNegativeRequestIsRefused() {
        val a = SlotAllocator(10)
        assertNull(a.allocate(0))
        assertNull(a.allocate(-3))
        assertEquals(0, a.used)
    }

    @Test fun freeingTheLastAllocationRewindsTheBump() {
        val a = SlotAllocator(100)
        a.allocate(10)
        val second = a.allocate(20)!!
        a.free(second, 20)
        assertEquals(10, a.used)
        // The rewind means the next allocation reuses exactly the space just given back.
        assertEquals(10, a.allocate(20))
    }

    @Test fun aFreedHoleIsReused() {
        val a = SlotAllocator(100)
        a.allocate(10)
        val hole = a.allocate(20)!!
        a.allocate(5)
        a.free(hole, 20)
        assertEquals(hole, a.allocate(20))
        assertEquals(35, a.used)
    }

    @Test fun aHoleIsSplitWhenTheRequestIsSmaller() {
        val a = SlotAllocator(100)
        a.allocate(10)
        val hole = a.allocate(20)!!
        a.allocate(5)
        a.free(hole, 20)
        assertEquals(hole, a.allocate(8))
        assertEquals(hole + 8, a.allocate(12))
        // The hole is used up, so the next one comes off the end.
        assertEquals(35, a.allocate(1))
    }

    @Test fun adjacentHolesCoalesceIntoOne() {
        val a = SlotAllocator(100)
        val first = a.allocate(10)!!
        val second = a.allocate(10)!!
        a.allocate(5) // keeps the two holes away from the bump pointer
        a.free(first, 10)
        a.free(second, 10)
        // Only a coalesced 20-slot hole can serve this.
        assertEquals(first, a.allocate(20))
    }

    @Test fun holesCoalesceWhateverOrderTheyAreFreedIn() {
        val a = SlotAllocator(100)
        val one = a.allocate(8)!!
        val two = a.allocate(8)!!
        val three = a.allocate(8)!!
        a.allocate(4)
        a.free(three, 8)
        a.free(one, 8)
        a.free(two, 8)
        assertEquals(one, a.allocate(24))
    }

    @Test fun aHoleTouchingTheBumpPointerIsReclaimed() {
        val a = SlotAllocator(100)
        a.allocate(10)
        val tail = a.allocate(20)!!
        val last = a.allocate(5)!!
        a.free(tail, 20)     // a hole, not at the end
        a.free(last, 5)      // now the hole reaches the bump pointer
        assertEquals(10, a.used)
        // Everything past 10 is one contiguous free run again, so a large request fits.
        assertEquals(10, a.allocate(90))
    }

    @Test fun freeingSomethingOfZeroSizeChangesNothing() {
        val a = SlotAllocator(100)
        a.allocate(10)
        a.free(5, 0)
        assertEquals(10, a.used)
    }

    @Test fun resetGivesBackEverything() {
        val a = SlotAllocator(100)
        a.allocate(10)
        val hole = a.allocate(20)!!
        a.allocate(5)
        a.free(hole, 20)
        a.reset()
        assertEquals(0, a.used)
        assertEquals(0, a.allocate(100))
    }

    @Test fun aLongEditSessionDoesNotLeakCapacity() {
        // Alternating allocate and free, the pattern a canvas drawn on for hours produces.
        val a = SlotAllocator(1000)
        val live = ArrayList<Pair<Int, Int>>()
        var seed = 12345
        fun next(bound: Int): Int {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            return seed % bound
        }
        repeat(4000) {
            if (live.size > 8 && next(2) == 0) {
                val i = next(live.size)
                val (off, count) = live.removeAt(i)
                a.free(off, count)
            } else {
                val count = 1 + next(20)
                val off = a.allocate(count)
                if (off != null) live.add(off to count)
            }
        }
        val liveTotal = live.sumOf { it.second }
        assertEquals("used must equal what is still held", liveTotal, a.used)
        for ((off, count) in live) {
            assertTrue(off >= 0)
            assertTrue(off + count <= a.capacity)
        }
        for ((off, count) in live) a.free(off, count)
        assertEquals(0, a.used)
        assertNotNull("everything returned, so the whole capacity is free again", a.allocate(1000))
    }

    @Test fun liveRangesNeverOverlap() {
        val a = SlotAllocator(500)
        val live = ArrayList<Pair<Int, Int>>()
        var seed = 777
        fun next(bound: Int): Int {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            return seed % bound
        }
        repeat(2000) {
            if (live.size > 4 && next(3) == 0) {
                val (off, count) = live.removeAt(next(live.size))
                a.free(off, count)
            } else {
                val count = 1 + next(10)
                a.allocate(count)?.let { live.add(it to count) }
            }
            val sorted = live.sortedBy { it.first }
            for (i in 1 until sorted.size) {
                assertTrue(
                    "ranges ${sorted[i - 1]} and ${sorted[i]} overlap",
                    sorted[i - 1].first + sorted[i - 1].second <= sorted[i].first,
                )
            }
        }
    }
}
