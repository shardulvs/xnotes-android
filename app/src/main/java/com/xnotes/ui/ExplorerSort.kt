package com.xnotes.ui

/**
 * Orders explorer entries for the grid: folders first (rendered as chips), then files. Folders are
 * ascending by creation time (the most recently created folder sits last); files are descending (the
 * most recently created note sits first). [createdOf] supplies the app-tracked creation time (see
 * [com.xnotes.platform.CreationTimeStore]) — last-modified then name break ties, so a freshly granted
 * folder (every item stamped at the same discovery instant) still lists most-recently-edited first.
 *
 * Pure (no Android), so it's unit-tested directly on [BrowseEntry] lists.
 */
internal fun explorerComparator(createdOf: (BrowseEntry) -> Long): Comparator<BrowseEntry> {
    val folderOrder = compareBy<BrowseEntry>({ createdOf(it) }, { it.modified }, { it.name.lowercase() })
    val fileOrder = compareByDescending<BrowseEntry> { createdOf(it) }
        .thenByDescending { it.modified }
        .thenBy { it.name.lowercase() }
    return Comparator { a, b ->
        when {
            a.isDir && !b.isDir -> -1
            !a.isDir && b.isDir -> 1
            a.isDir -> folderOrder.compare(a, b)
            else -> fileOrder.compare(a, b)
        }
    }
}
