package com.xnotes.core.pdf

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.PageBox
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TextFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowLinksTest {

    private fun place(vararg paras: Paragraph, width: Double = 400.0): List<PlacedLink> {
        val flow = TextFlow().apply {
            margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
            paragraphs.addAll(paras)
        }
        val frame = FlowLayout(FakeTextMeasurer()).layout(flow, listOf(PageBox(width, 400.0)), 150)
        return FlowLinks.place(flow, frame)
    }

    @Test
    fun aLinkSitsOverItsCharacters() {
        val links = place(Paragraph(mutableListOf(Run("go www.a.io now"))))
        assertEquals(1, links.size)
        val l = links[0]
        assertEquals("https://www.a.io", l.uri)
        assertEquals(3, l.start)
        assertEquals(11, l.end)
        assertEquals(3 * 7.2, l.rect.left, 1e-9) // FakeTextMeasurer: 0.6 em per char at 12 pt
        assertEquals(8 * 7.2, l.rect.w, 1e-9)
    }

    @Test
    fun codeAndMathNeverLink() {
        val links = place(
            Paragraph(mutableListOf(Run("https://in.code/x")), codeLang = "kotlin"),
            Paragraph(mutableListOf(Run("text "), Run("https://inline.code", CharStyle(code = true)))),
            Paragraph(mutableListOf(Run("https://in.math", CharStyle(math = true)))),
        )
        assertTrue(links.isEmpty())
    }

    @Test
    fun aWrappedLinkIsOnePieceALine() {
        // 16 chars fit a line; the address hard-breaks across two.
        val links = place(Paragraph(mutableListOf(Run("https://abcdefghij.io/abc"))), width = 16 * 7.2)
        assertEquals(2, links.size)
        assertTrue(links.all { it.uri == "https://abcdefghij.io/abc" })
        assertEquals(links[0].end, links[1].start)
    }
}
