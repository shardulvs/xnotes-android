package com.xnotes.ui

import com.xnotes.settings.ExplorerSortKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the explorer grid order: folders first, then the chosen field/direction within each group. */
class ExplorerSortTest {

    private fun entry(name: String, isDir: Boolean, created: Long = 0, modified: Long = 0, size: Long = 0) =
        BrowseEntry(name = name, documentUri = "uri:$name", isDir = isDir, size = size, modified = modified, created = created)

    private fun order(key: ExplorerSortKey, descending: Boolean, vararg entries: BrowseEntry) =
        entries.toList().sortedWith(explorerComparator(key, descending) { it.created }).map { it.name }

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
    }

    @Test
    fun namesCompareDigitRunsByValue() {
        assertEquals(-1, compareNames("page 9", "page 10"))
        assertEquals(-1, compareNames("Week 2", "week 10"))
        assertEquals(0, compareNames("Week 2", "week 2"))
        assertEquals(-1, compareNames("note", "note 2"))
    }

    @Test
    fun digitRunsNeitherOverflowNorLosePadding() {
        // Longer than any int or long, which is why the runs are compared as text.
        assertEquals(1, compareNames("n 100000000000000000000", "n 99999999999999999999"))
        assertEquals(-1, compareNames("page 9", "page 09"))
        assertEquals(-1, compareNames("page 09", "page 10"))
    }
}
