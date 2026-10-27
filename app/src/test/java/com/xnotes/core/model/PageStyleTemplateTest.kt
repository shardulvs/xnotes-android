package com.xnotes.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageStyleTemplateTest {

    private fun doc(docStyle: PageStyle, pageStyle: PageStyle): Pair<Document, Page> {
        val d = Document()
        d.style = docStyle
        val p = Page(100.0, 100.0)
        p.style = pageStyle
        d.pages.add(p)
        return d to p
    }

    private val all = PageStyle(
        template = "lines",
        patternColor = Rgba(1, 2, 3),
        spacing = 80.0,
        accentColor = Rgba(4, 5, 6),
        params = mapOf("gap" to 3.0),
        colors = mapOf("rule" to Rgba(7, 8, 9)),
    )

    @Test
    fun theBuiltInRulingsKeepTheirOwnSpacing() {
        val (d, p) = doc(PageStyle(template = "grid", spacing = 80.0), PageStyle(template = "dots"))
        assertEquals("dots", p.resolvedTemplate(d))
        assertNull(p.resolvedSpacing(d))
    }

    @Test
    fun aPageShowingTheNotesTemplateTakesItsSettings() {
        val (d, p) = doc(all, PageStyle(template = "lines", params = mapOf("rows" to 2.0)))
        assertEquals(80.0, p.resolvedSpacing(d)!!, 0.0)
        assertEquals(Rgba(1, 2, 3), p.resolvedPatternColor(d))
        assertEquals(Rgba(4, 5, 6), p.resolvedAccentColor(d))
        assertEquals(mapOf("gap" to 3.0, "rows" to 2.0), p.resolvedParams(d))
        assertEquals(mapOf("rule" to Rgba(7, 8, 9)), p.resolvedColors(d))
    }

    @Test
    fun settingsDoNotLeakIntoADifferentTemplate() {
        val (d, p) = doc(all, PageStyle(template = "abcdef0123456789"))
        assertNull(p.resolvedSpacing(d))
        assertEquals(emptyMap<String, Double>(), p.resolvedParams(d))
        assertEquals(emptyMap<String, Rgba>(), p.resolvedColors(d))
        assertEquals(PageStyle.DEFAULT_PATTERN_COLOR, p.resolvedPatternColor(d))
        assertEquals(PageStyle.DEFAULT_ACCENT_COLOR, p.resolvedAccentColor(d))
    }

    @Test
    fun aPageWithoutItsOwnTemplateTakesTheDocumentsSettings() {
        val (d, p) = doc(
            PageStyle(template = "abcdef0123456789", patternColor = Rgba(1, 2, 3), params = mapOf("gap" to 3.0, "rows" to 5.0)),
            PageStyle(params = mapOf("gap" to 2.0)),
        )
        assertEquals(mapOf("gap" to 2.0, "rows" to 5.0), p.resolvedParams(d))
        assertEquals(Rgba(1, 2, 3), p.resolvedPatternColor(d))
    }

    @Test
    fun aNoteShowingNoTemplateLendsNothing() {
        val (d, p) = doc(PageStyle(spacing = 80.0, patternColor = Rgba(1, 2, 3)), PageStyle(template = "lines"))
        assertNull(p.resolvedSpacing(d))
        assertEquals(PageStyle.DEFAULT_PATTERN_COLOR, p.resolvedPatternColor(d))
    }

    @Test
    fun switchingTemplatesDropsAllTheirSettings() {
        val s = all.copy(pageColor = Rgba(9, 9, 9))
        val grid = s.withTemplate("grid", PageTemplates.NONE)
        assertEquals("grid", grid.template)
        assertTrue(grid.settings.isEmpty)
        // The paper belongs to the level, not to the template.
        assertEquals(Rgba(9, 9, 9), grid.pageColor)
        assertEquals(s, s.withTemplate("lines", PageTemplates.NONE))
        // Default on a page keeps them while it inherits the same template, and drops them otherwise.
        assertEquals(s.copy(template = null), s.withTemplate(null, "lines"))
        assertTrue(s.withTemplate(null, "dots").settings.isEmpty)
    }

    @Test
    fun olderNotesKeepWhatTheirPagesBorrowed() {
        val d = Document()
        d.style = all
        val grid = Page(100.0, 100.0).also { it.style = PageStyle(template = "grid", spacing = 20.0) }
        val other = Page(100.0, 100.0).also { it.style = PageStyle(template = "abcdef0123456789", accentColor = Rgba(0, 0, 0)) }
        val follower = Page(100.0, 100.0).also { it.style = PageStyle(spacing = 30.0) }
        d.pages.addAll(listOf(grid, other, follower))
        d.keepBorrowedTemplateSettings()
        // A built-in ruling borrowed everything from the note's built-in ruling, its own values winning.
        assertEquals(PageStyle(template = "grid", spacing = 20.0).withSettings(all.settings.copy(spacing = 20.0)), grid.style)
        // Any other template borrowed only the colours.
        assertEquals(PageStyle(template = "abcdef0123456789", patternColor = Rgba(1, 2, 3), accentColor = Rgba(0, 0, 0)), other.style)
        assertEquals(PageStyle(spacing = 30.0), follower.style)
    }

    @Test
    fun aNoteShowingNoTemplateLentEverything() {
        val d = Document()
        d.style = PageStyle(spacing = 80.0, params = mapOf("gap" to 3.0))
        val p = Page(100.0, 100.0).also { it.style = PageStyle(template = "abcdef0123456789", params = mapOf("rows" to 2.0)) }
        d.pages.add(p)
        d.keepBorrowedTemplateSettings()
        assertEquals(80.0, p.resolvedSpacing(d)!!, 0.0)
        assertEquals(mapOf("gap" to 3.0, "rows" to 2.0), p.resolvedParams(d))
    }

    @Test
    fun settingsComeOutAndGoBackWhole() {
        val t = all.settings
        assertEquals(TemplateSettings(Rgba(1, 2, 3), 80.0, Rgba(4, 5, 6), mapOf("gap" to 3.0), mapOf("rule" to Rgba(7, 8, 9))), t)
        assertEquals(all, PageStyle(template = "lines").withSettings(t))
        assertTrue(TemplateSettings().isEmpty)
    }
}
