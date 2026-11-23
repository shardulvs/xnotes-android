package com.xnotes.ui

import com.xnotes.settings.ExplorerSortKey

/**
 * Orders explorer entries: folders first when [foldersFirst] (rendered on their own), then files,
 * with the chosen [key]/[descending] sort applied within each group. [createdOf] supplies the
 * creation time (see [com.xnotes.platform.CreationTimeStore]); it breaks ties on the modified/size
 * keys, and name is the final ascending tiebreaker so the order is always stable.
 *
 * Names compare in natural order ([compareNames]), so a numbered run reads as counting rather than
 * as text.
 *
 * Pure (no Android), so it's unit-tested directly on [BrowseEntry] lists.
 */
internal fun explorerComparator(
    key: ExplorerSortKey,
    descending: Boolean,
    foldersFirst: Boolean = true,
    createdOf: (BrowseEntry) -> Long,
): Comparator<BrowseEntry> {
    val byName = Comparator<BrowseEntry> { a, b -> compareNames(a.name, b.name) }
    val base: Comparator<BrowseEntry> = when (key) {
        ExplorerSortKey.NAME -> byName
        ExplorerSortKey.MODIFIED -> compareBy({ it.modified }, { createdOf(it) })
        ExplorerSortKey.CREATED -> compareBy({ createdOf(it) }, { it.modified })
        ExplorerSortKey.SIZE -> compareBy({ it.size }, { createdOf(it) })
    }
    val within = (if (descending) base.reversed() else base).then(byName)
    if (!foldersFirst) return within
    return Comparator { a, b ->
        when {
            a.isDir && !b.isDir -> -1
            !a.isDir && b.isDir -> 1
            else -> within.compare(a, b)
        }
    }
}

/**
 * Natural order for names: a run of digits compares by value, so "week 2" comes before "week 10"
 * and "page 09" sits next to "page 9". Everything else compares as text, ignoring case.
 *
 * Both names are walked one character, or one digit run, at a time instead of being turned into
 * sort keys: that keeps the answer a real total order, which a comparator has to be for a sort to
 * stay consistent. How a run is padded is settled last, so "page 09 a" comes before "page 9 z" and
 * "page 9" before "page 09".
 */
internal fun compareNames(a: String, b: String): Int {
    var i = 0
    var j = 0
    // Runs holding the same value can still be padded differently; that only breaks the tie once
    // every other character of the names has matched.
    var padded = 0
    while (i < a.length && j < b.length) {
        if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
            val endA = digitRunEnd(a, i)
            val endB = digitRunEnd(b, j)
            val byValue = compareDigitRuns(a, i, endA, b, j, endB)
            if (byValue != 0) return byValue
            if (padded == 0) padded = (endA - i).compareTo(endB - j)
            i = endA
            j = endB
            continue
        }
        val ca = a[i].lowercaseChar()
        val cb = b[j].lowercaseChar()
        if (ca != cb) return ca.compareTo(cb)
        i++
        j++
    }
    // One name is a prefix of the other, or they are equal; the shorter ends the walk.
    return when {
        i < a.length -> 1
        j < b.length -> -1
        else -> padded
    }
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** Index just past the digit run that starts at [start]. */
private fun digitRunEnd(s: String, start: Int): Int {
    var i = start
    while (i < s.length && s[i].isAsciiDigit()) i++
    return i
}

/**
 * Compares two digit runs by value without building a number: a run long enough overflows every
 * built-in type, so the significant digits are compared as text, longest run first. Runs holding
 * the same value compare equal; their padding is left to [compareNames].
 */
private fun compareDigitRuns(a: String, startA: Int, endA: Int, b: String, startB: Int, endB: Int): Int {
    var ia = startA
    var ib = startB
    while (ia < endA && a[ia] == '0') ia++
    while (ib < endB && b[ib] == '0') ib++
    val lengthA = endA - ia
    val lengthB = endB - ib
    if (lengthA != lengthB) return if (lengthA < lengthB) -1 else 1
    while (ia < endA) {
        if (a[ia] != b[ib]) return if (a[ia] < b[ib]) -1 else 1
        ia++
        ib++
    }
    return 0
}
