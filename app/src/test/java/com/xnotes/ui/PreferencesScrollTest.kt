package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesScrollTest {

    @Test fun theSectionsPanelMarksTheLastSectionAtTheBottom() {
        val max = 3000
        val height = 800f
        val top = 24f
        assertEquals(top, sectionLine(0, max, height, top), 0f)
        assertEquals(1000f + top, sectionLine(1000, max, height, top), 0f)
        assertEquals("at the end the line is the bottom of the page", (max + height), sectionLine(max, max, height, top), 0f)
        for (y in listOf(500, 2900, 3300, 3700)) {
            val s = scrollForSection(y, max, height, top)
            assertTrue(s in 0..max)
            assertTrue("a tap on a section at $y marks it", sectionLine(s, max, height, top) >= y)
        }
    }
}
