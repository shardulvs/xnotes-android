package com.xnotes.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkFinderTest {

    private fun links(text: String): List<Pair<String, String>> =
        LinkFinder.find(text).map { text.substring(it.start, it.end) to it.uri }

    @Test
    fun findsSchemesHostsAndEmails() {
        assertEquals(
            listOf(
                "https://example.com/path?q=1" to "https://example.com/path?q=1",
                "www.xnotes.app" to "https://www.xnotes.app",
                "me@example.org" to "mailto:me@example.org",
            ),
            links("see https://example.com/path?q=1 plus www.xnotes.app and mail me@example.org today"),
        )
    }

    @Test
    fun sentencePunctuationStaysOutside() {
        assertEquals(listOf("https://a.io/x" to "https://a.io/x"), links("Go to https://a.io/x."))
        assertEquals(listOf("www.a.org" to "https://www.a.org"), links("(see www.a.org)."))
        assertEquals(listOf("http://b.co/?a=1" to "http://b.co/?a=1"), links("\"http://b.co/?a=1\","))
    }

    @Test
    fun bracketsTheLinkOpenedStayInside() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Set_(mathematics)" to "https://en.wikipedia.org/wiki/Set_(mathematics)"),
            links("https://en.wikipedia.org/wiki/Set_(mathematics)"),
        )
    }

    @Test
    fun bareDomainsAndSchemesAloneAreNotLinks() {
        assertTrue(links("example.com is not a link, nor is https:// alone").isEmpty())
    }

    @Test
    fun aMatchInsideAWordIsNotALink() {
        assertTrue(links("foo.www.bar.com").isEmpty())
    }
}
