package com.xnotes.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageLinksTest {

    /** Cells 6 pt wide and 12 tall, so char k of a line at (0, y) spans x 6k to 6k + 6. */
    @Test
    fun anAddressOnALineIsALink() {
        val page = FakePageText().line("see https://a.io/x. now", 0f, 0f).build()
        val links = PageLinks.find(page)
        assertEquals(listOf(TextLink(4, 18, "https://a.io/x")), links)
        assertEquals("https://a.io/x", PageLinks.at(page, 4 * 6f + 1, 6f)?.uri)
        assertEquals("https://a.io/x", PageLinks.at(page, 17 * 6f + 5, 11f)?.uri)
        assertNull(PageLinks.at(page, 2 * 6f, 6f))
        assertNull(PageLinks.at(page, 19 * 6f, 6f))
    }

    @Test
    fun hostsAndEmailsLinkToo() {
        val page = FakePageText().line("visit␣www.b.org or mail me@c.net.", 0f, 0f).build()
        assertEquals(listOf("https://www.b.org", "mailto:me@c.net"), PageLinks.find(page).map { it.uri })
    }

    @Test
    fun aLineBreakEndsALink() {
        val page = FakePageText().line("go https://d.io/path", 0f, 0f).line("more text", 0f, 14f).build()
        val links = PageLinks.find(page)
        assertEquals(listOf("https://d.io/path"), links.map { it.uri })
        assertNull(PageLinks.at(page, 6f, 20f))
    }

    @Test
    fun aLineEndHyphenJoinsTheNextLineOn() {
        val page = FakePageText().line("at www.exam-", 0f, 0f, hyphen = true).line("ple.org now", 0f, 14f).build()
        val links = PageLinks.find(page)
        assertEquals(listOf("https://www.exam-ple.org"), links.map { it.uri })
        assertEquals(3, links[0].start)
        assertEquals(19, links[0].end)
        assertEquals("https://www.exam-ple.org", PageLinks.at(page, 2 * 6f, 20f)?.uri)
    }

    @Test
    fun aTapOffTheTextFindsNothing() {
        val page = FakePageText().line("https://f.io", 0f, 0f).line("https://g.io", 0f, 100f).build()
        assertNull(PageLinks.at(page, 30f, 50f, slop = 4f))
        assertEquals("https://g.io", PageLinks.at(page, 30f, 105f, slop = 4f)?.uri)
    }

    @Test
    fun aTapJustOffTheTextNeedsSlop() {
        val page = FakePageText().line("https://e.io", 0f, 0f).build()
        assertNull(PageLinks.at(page, 30f, 13f))
        assertEquals("https://e.io", PageLinks.at(page, 30f, 13f, slop = 2f)?.uri)
    }
}
