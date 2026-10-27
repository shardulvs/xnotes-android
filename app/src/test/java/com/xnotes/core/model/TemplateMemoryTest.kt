package com.xnotes.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMemoryTest {

    private val calligraphy = "0123456789abcdef"
    private val lines = TemplateSettings(patternColor = Rgba(0, 0, 255), spacing = 100.0)
    private val grid = TemplateSettings(spacing = 40.0, params = mapOf("gap" to 2.0))

    private fun Document.take(c: TemplateMemory.NoteChange) {
        style = c.style
        for ((p, s) in c.pages) p.style = s
    }

    private fun note(vararg pages: PageStyle): Document {
        val d = Document()
        d.style = PageStyle(template = "lines").withSettings(lines)
        for (s in pages) d.pages.add(Page(100.0, 100.0).also { it.style = s })
        return d
    }

    @Test
    fun eachTemplateGetsBackWhatItHad() {
        val d = note()
        val m = TemplateMemory()
        d.take(m.pickForNote(d, "grid"))
        assertEquals("grid", d.style.template)
        assertTrue(d.style.settings.isEmpty)
        d.style = d.style.withSettings(grid)
        d.take(m.pickForNote(d, calligraphy))
        assertTrue(d.style.settings.isEmpty)
        d.take(m.pickForNote(d, "lines"))
        assertEquals(lines, d.style.settings)
        d.take(m.pickForNote(d, "grid"))
        assertEquals(grid, d.style.settings)
    }

    @Test
    fun pickingTheShownTemplateChangesNothing() {
        val d = note(PageStyle(spacing = 30.0))
        val c = TemplateMemory().pickForNote(d, "lines")
        assertEquals(d.style, c.style)
        assertTrue(c.pages.isEmpty())
    }

    @Test
    fun pagesFollowingTheNoteSwapTheirOwnSettingsWithIt() {
        val d = note(PageStyle(spacing = 30.0), PageStyle(template = "dots", spacing = 10.0), PageStyle())
        val (follower, own, plain) = d.pages
        val m = TemplateMemory()
        val c = m.pickForNote(d, calligraphy)
        // Only the follower with settings of its own changes; the page with its own template is left alone.
        assertEquals(listOf(follower), c.pages.keys.toList())
        d.take(c)
        assertTrue(follower.style.settings.isEmpty)
        assertEquals(10.0, own.style.spacing!!, 0.0)
        d.take(m.pickForNote(d, "lines"))
        assertEquals(30.0, follower.style.spacing!!, 0.0)
        assertTrue(plain.style.settings.isEmpty)
    }

    @Test
    fun resetForgetsEverythingAndClearsTheFollowers() {
        val d = note(PageStyle(spacing = 30.0), PageStyle(template = "dots", spacing = 10.0))
        val m = TemplateMemory()
        d.take(m.pickForNote(d, "grid"))
        d.style = d.style.withSettings(grid)
        d.pages[0].style = PageStyle(spacing = 5.0)
        d.take(m.resetNote(d))
        assertEquals(PageStyle(), d.style)
        assertTrue(d.pages[0].style.settings.isEmpty)
        assertEquals(10.0, d.pages[1].style.spacing!!, 0.0)
        d.take(m.pickForNote(d, "lines"))
        assertTrue(d.style.settings.isEmpty)
        assertTrue(d.pages[0].style.settings.isEmpty)
    }

    @Test
    fun aPageKeepsItsSettingsPerTemplate() {
        val d = note(PageStyle(spacing = 30.0))
        val page = d.pages[0]
        val m = TemplateMemory()
        page.style = m.pickForPage(d, page, "dots")
        assertEquals("dots", page.style.template)
        assertTrue(page.style.settings.isEmpty)
        page.style = page.style.copy(spacing = 10.0)
        page.style = m.pickForPage(d, page, null)
        assertNull(page.style.template)
        assertEquals(30.0, page.style.spacing!!, 0.0)
        // Naming the note's own template keeps what the page has.
        page.style = m.pickForPage(d, page, "lines")
        assertEquals(30.0, page.style.spacing!!, 0.0)
        page.style = m.pickForPage(d, page, "dots")
        assertEquals(10.0, page.style.spacing!!, 0.0)
    }

    @Test
    fun aStyleSwapsItsSettingsThroughAMap() {
        val kept = HashMap<String, TemplateSettings>()
        var s = PageStyle(pageColor = Rgba(9, 9, 9), template = "lines").withSettings(lines)
        s = s.withTemplate("grid", PageTemplates.NONE, kept)
        assertTrue(s.settings.isEmpty)
        assertEquals(Rgba(9, 9, 9), s.pageColor)
        s = s.withTemplate("lines", PageTemplates.NONE, kept)
        assertEquals(lines, s.settings)
        // A template left with nothing set leaves nothing behind.
        assertEquals(setOf("lines"), kept.keys)
    }

    @Test
    fun recallShowsWhatATemplateHadWhileAnotherIsShown() {
        val d = note(PageStyle(template = "grid").withSettings(grid))
        val page = d.pages[0]
        val m = TemplateMemory()
        d.take(m.pickForNote(d, "dots"))
        page.style = m.pickForPage(d, page, "dots")
        assertEquals(lines, m.recall(null, "lines"))
        assertEquals(grid, m.recall(page, "grid"))
        assertNull(m.recall(page, "lines"))
    }
}
