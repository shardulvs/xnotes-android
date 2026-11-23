package com.xnotes.ui

import com.xnotes.settings.ExplorerSortKey
import kotlin.math.sign
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the explorer grid order: folders first, then the chosen field/direction within each group. */
class ExplorerSortTest {

    private fun entry(name: String, isDir: Boolean, created: Long = 0, modified: Long = 0, size: Long = 0) =
        BrowseEntry(name = name, documentUri = "uri:$name", isDir = isDir, size = size, modified = modified, created = created)

    private fun order(key: ExplorerSortKey, descending: Boolean, vararg entries: BrowseEntry) =
        entries.toList().sortedWith(explorerComparator(key, descending) { it.created }).map { it.name }

    /** Asserts [first] sorts first, and that swapping the arguments flips the answer. */
    private fun assertOrder(first: String, second: String) {
        assertTrue("\"$first\" should sort before \"$second\"", compareNames(first, second) < 0)
        assertTrue("\"$second\" should sort after \"$first\"", compareNames(second, first) > 0)
    }

    @Test
    fun foldersAlwaysComeBeforeFiles() {
        // Even the most recently edited folder sorts ahead of every file, whatever the key.
        assertEquals(
            listOf("folder", "note"),
            order(
                ExplorerSortKey.MODIFIED, descending = true,
                entry("note", isDir = false, modified = 100),
                entry("folder", isDir = true, modified = 1),
            ),
        )
    }

    @Test
    fun modifiedSortsBothDirections() {
        val items = arrayOf(
            entry("a", isDir = false, modified = 20),
            entry("b", isDir = false, modified = 30),
            entry("c", isDir = false, modified = 10),
        )
        assertEquals(listOf("b", "a", "c"), order(ExplorerSortKey.MODIFIED, descending = true, *items))
        assertEquals(listOf("c", "a", "b"), order(ExplorerSortKey.MODIFIED, descending = false, *items))
    }

    @Test
    fun modifiedKeepsFoldersAndFilesInSeparateGroups() {
        assertEquals(
            listOf("f-new", "f-old", "note-new", "note-old"),
            order(
                ExplorerSortKey.MODIFIED, descending = true,
                entry("f-old", isDir = true, modified = 10),
                entry("f-new", isDir = true, modified = 30),
                entry("note-old", isDir = false, modified = 20),
                entry("note-new", isDir = false, modified = 40),
            ),
        )
    }

    @Test
    fun nameSortsBothDirections() {
        val items = arrayOf(
            entry("banana", isDir = false),
            entry("Apple", isDir = false),
            entry("cherry", isDir = false),
        )
        assertEquals(listOf("Apple", "banana", "cherry"), order(ExplorerSortKey.NAME, descending = false, *items))
        assertEquals(listOf("cherry", "banana", "Apple"), order(ExplorerSortKey.NAME, descending = true, *items))
    }

    @Test
    fun sizeDescendingPutsLargestFirst() {
        assertEquals(
            listOf("big", "mid", "small"),
            order(
                ExplorerSortKey.SIZE, descending = true,
                entry("small", isDir = false, size = 100),
                entry("big", isDir = false, size = 300),
                entry("mid", isDir = false, size = 200),
            ),
        )
    }

    @Test
    fun tiesFallBackToNameAscending() {
        // Same modified and creation time: name breaks the tie, ascending, whatever the direction.
        assertEquals(
            listOf("a", "b", "c"),
            order(
                ExplorerSortKey.MODIFIED, descending = true,
                entry("b", isDir = false, modified = 5),
                entry("a", isDir = false, modified = 5),
                entry("c", isDir = false, modified = 5),
            ),
        )
    }

    @Test
    fun tiesFallBackToNameInCountingOrder() {
        // The tiebreaker reads numbers by value too, or week 2 and week 10 interleave on a date sort.
        assertEquals(
            listOf("week 2", "week 10"),
            order(
                ExplorerSortKey.MODIFIED, descending = true,
                entry("week 10", isDir = false, modified = 5),
                entry("week 2", isDir = false, modified = 5),
            ),
        )
    }

    @Test
    fun nameSortsNumberedRunsByValue() {
        // A numbered folder reads as counting order: week 2 belongs before week 10, not after it.
        assertEquals(
            listOf("week 1", "week 2", "week 10"),
            order(
                ExplorerSortKey.NAME, descending = false,
                entry("week 10", isDir = false),
                entry("week 1", isDir = false),
                entry("week 2", isDir = false),
            ),
        )
        assertEquals(
            listOf("week 10", "week 2", "week 1"),
            order(
                ExplorerSortKey.NAME, descending = true,
                entry("week 2", isDir = false),
                entry("week 1", isDir = false),
                entry("week 10", isDir = false),
            ),
        )
    }

    @Test
    fun namesCompareDigitRunsByValue() {
        assertOrder("page 9", "page 10")
        assertOrder("Week 2", "week 10")
        assertOrder("note", "note 2")
        assertEquals(0, compareNames("Week 2", "week 2"))
    }

    @Test
    fun namesWithoutDigitsKeepTheTextOrder() {
        assertOrder("", "a")
        assertOrder("apple", "banana")
        assertOrder("note 2", "note a")
        assertEquals(0, compareNames("", ""))
        assertEquals(0, compareNames("Apple", "apple"))
    }

    @Test
    fun digitRunsNeitherOverflowNorLosePadding() {
        // Longer than any int or long, which is why the runs are compared as text.
        assertOrder("n 99999999999999999999", "n 100000000000000000000")
        assertOrder("page 9", "page 09")
        assertOrder("page 09", "page 09a")
    }

    @Test
    fun paddingDecidesOnlyWhenNothingElseDiffers() {
        // The numbers are equal, so the rest of the name orders these two.
        assertOrder("page 09 a", "page 9 z")
        assertOrder("page 9", "page 09")
    }

    @Test
    fun nameOrderIsConsistentForEveryPairAndTriple() {
        // sortedWith throws when a comparator contradicts itself, so the walk has to be a real
        // total order, not just right on the pairs a test happens to name.
        val rnd = Random(7)
        val alphabet = "abAZ 019.-"
        val names = List(40) { String(CharArray(rnd.nextInt(1, 8)) { alphabet[rnd.nextInt(alphabet.length)] }) }
        for (a in names) {
            for (b in names) {
                val forward = compareNames(a, b).sign
                assertEquals("reverse of \"$a\" vs \"$b\"", -forward, compareNames(b, a).sign)
                if (forward > 0) continue
                for (c in names) {
                    if (compareNames(b, c) <= 0) {
                        assertTrue("transitivity broke on \"$a\", \"$b\", \"$c\"", compareNames(a, c) <= 0)
                    }
                }
            }
        }
        val sorted = names.sortedWith(Comparator { x, y -> compareNames(x, y) })
        for (k in 1 until sorted.size) assertTrue("sorted order broke", compareNames(sorted[k - 1], sorted[k]) <= 0)
    }
}
