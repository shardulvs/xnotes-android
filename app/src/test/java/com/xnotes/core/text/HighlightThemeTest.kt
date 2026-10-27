package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightThemeTest {

    @Test
    fun neovimRootsTakeTheirSlotsColour() {
        val t = HighlightTheme.DARK
        assertEquals(t.colorFor("keyword"), t.colorFor("conditional"))
        assertEquals(t.colorFor("keyword"), t.colorFor("repeat"))
        assertEquals(t.colorFor("constant"), t.colorFor("boolean"))
        assertEquals(t.colorFor("number"), t.colorFor("float"))
        assertEquals(t.colorFor("function"), t.colorFor("method.call"))
    }

    @Test
    fun ourSlotsStillWinAndUnknownRootsStayPlain() {
        val t = HighlightTheme.LIGHT
        assertEquals(t.colorFor("keyword"), t.colorFor("keyword.conditional"))
        assertNull(t.colorFor("label"))
        assertNull(t.colorFor("none"))
    }
}
