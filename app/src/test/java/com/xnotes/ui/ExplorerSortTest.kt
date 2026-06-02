package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the explorer grid order: folders first (ascending by creation), files after (descending). */
class ExplorerSortTest {

    private fun entry(name: String, isDir: Boolean, created: Long, modified: Long = 0) =
        BrowseEntry(name = name, documentUri = "uri:$name", isDir = isDir, size = 0, modified = modified, created = created)

    private fun order(vararg entries: BrowseEntry) =
        entries.toList().sortedWith(explorerComparator { it.created }).map { it.name }

    @Test
    fun foldersComeBeforeFiles() {
        assertEquals(
            listOf("folder", "note"),
            order(
                entry("note", isDir = false, created = 100),
                entry("folder", isDir = true, created = 1),
            ),
        )
    }

    @Test
    fun foldersAscendingFilesDescendingByCreated() {
        assertEquals(
            // folders oldest -> newest (newest folder last); files newest -> oldest (newest note first)
            listOf("f-old", "f-mid", "f-new", "note-new", "note-mid", "note-old"),
            order(
                entry("f-old", isDir = true, created = 10),
                entry("f-new", isDir = true, created = 30),
                entry("f-mid", isDir = true, created = 20),
                entry("note-old", isDir = false, created = 10),
                entry("note-new", isDir = false, created = 30),
                entry("note-mid", isDir = false, created = 20),
            ),
        )
    }

    @Test
    fun filesTieOnCreatedFallBackToModifiedThenName() {
        // A freshly granted folder stamps every file at the same instant — most-recently-edited wins.
        assertEquals(
            listOf("a", "b", "c"),
            order(
                entry("b", isDir = false, created = 5, modified = 100),
                entry("a", isDir = false, created = 5, modified = 200),
                entry("c", isDir = false, created = 5, modified = 100),
            ),
        )
    }

    @Test
    fun foldersTieOnCreatedFallBackToModifiedThenName() {
        assertEquals(
            listOf("x", "z", "y"),
            order(
                entry("y", isDir = true, created = 5, modified = 200),
                entry("x", isDir = true, created = 5, modified = 100),
                entry("z", isDir = true, created = 5, modified = 100),
            ),
        )
    }
}
