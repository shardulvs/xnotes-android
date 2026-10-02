package com.xnotes.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToUnicodeTest {

    private fun mappings(cmap: String): List<String> =
        Regex("beginbfchar\n(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(cmap)
            .flatMap { Regex("<([0-9A-F]{2})> <([0-9A-F]*)>").findAll(it.groupValues[1]) }
            .map { "${it.groupValues[1]}=${it.groupValues[2]}" }
            .toList()

    @Test
    fun mapsCodesToUtf16BigEndian() {
        val cmap = ToUnicode.cmap(listOf(1 to "A", 2 to "é", 3 to " "))
        assertEquals(listOf("01=0041", "02=00E9", "03=0020"), mappings(cmap))
        assertTrue(cmap.contains("1 begincodespacerange\n<00> <FF>\nendcodespacerange"))
    }

    @Test
    fun oneCodeCanReadAsSeveralCharacters() {
        val cmap = ToUnicode.cmap(listOf(7 to "ffi", 8 to "क्ष"))
        assertEquals(listOf("07=006600660069", "08=0915094D0937"), mappings(cmap))
    }

    @Test
    fun charactersBeyondTheBmpBecomeSurrogatePairs() {
        val cmap = ToUnicode.cmap(listOf(9 to "😀"))
        assertEquals(listOf("09=D83DDE00"), mappings(cmap))
    }

    @Test
    fun forbiddenCodePointsAreDropped() {
        val cmap = ToUnicode.cmap(listOf(1 to "a﻿b", 2 to "\u0000"))
        assertEquals(listOf("01=00610062", "02=200B"), mappings(cmap))
    }

    @Test
    fun splitsIntoBlocksOfAtMostAHundred() {
        val cmap = ToUnicode.cmap((1..250).map { it to "x" })
        assertTrue(cmap.contains("100 beginbfchar"))
        assertTrue(cmap.contains("50 beginbfchar"))
        assertEquals(3, Regex("beginbfchar").findAll(cmap).count())
        assertFalse(cmap.contains("<FA0>"))
    }
}
