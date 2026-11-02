package com.xnotes.sync.merge

import org.junit.Assert.assertEquals
import org.junit.Test

class PageMergeTest {
    /** The merge as page names: local refs read from [local], remote ones from [remote]. */
    private fun merge(base: String, local: String, remote: String): Pair<String, List<Int>> {
        val l = local.map { it.toString() }
        val r = remote.map { it.toString() }
        val result = PageMerge.merge(base.map { it.toString() }, l, r)!!
        return result.pages.joinToString("") { if (it.local) l[it.index] else r[it.index] } to result.keptBoth
    }

    @Test fun changesOnDifferentPagesBothApply() = assertEquals("XbY" to emptyList<Int>(), merge("abc", "Xbc", "abY"))

    @Test fun insertsAtBothEndsAndInTheMiddle() = assertEquals("1ab2c3", merge("abc", "1ab2c", "abc3").first)

    @Test fun aDeleteOnOneSideApplies() = assertEquals("acZ", merge("abc", "ac", "abcZ").first)

    @Test fun theSameChangeOnBothSidesAppliesOnce() = assertEquals("aXc" to emptyList<Int>(), merge("abc", "aXc", "aXc"))

    @Test fun differentChangesToOnePageKeepBoth() = assertEquals("aXYc" to listOf(1, 2), merge("abc", "aXc", "aYc"))

    @Test fun anEditBeatsADelete() = assertEquals("aYc" to emptyList<Int>(), merge("abc", "ac", "aYc"))

    @Test fun identicalBlankPagesLineUp() = assertEquals("--X--Y", merge("----", "--X--", "----Y").first)

    @Test fun changesOnNeighbouringPagesBothApply() = assertEquals("XY" to emptyList<Int>(), merge("ab", "Xb", "aY"))

    @Test fun aDeleteNextToAnEditBothApply() = assertEquals("aZ" to emptyList<Int>(), merge("abc", "ac", "abZ"))

    @Test fun aMoveAgainstAnEditOfThatPageIsNotMerged() =
        org.junit.Assert.assertNull(PageMerge.merge(listOf("a", "b", "c"), listOf("c", "a", "b"), listOf("a", "b", "X")))

    @Test fun theSameMoveOnBothSidesStillMerges() = assertEquals("cab", merge("abc", "cab", "cab").first)

    @Test fun nothingChanged() = assertEquals("abc", merge("abc", "abc", "abc").first)
}
