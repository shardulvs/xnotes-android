package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichPasteTest {

    private fun html(s: String) = RichPaste.fromHtml(s)
    private fun paras(s: String) = html(s).flatMap { if (it.isBlank()) listOf(Paragraph()) else MarkdownParser.parse(it) }

    @Test fun headingsAndEmphasis() {
        assertEquals(
            listOf("## Title", "Some **bold** and *italic* text"),
            html("<meta charset='utf-8'><h2>Title</h2><p>Some <b>bold </b>and <em>italic</em> text</p>"),
        )
        val p = paras("<h1>Big</h1><p>x</p>")
        assertEquals(1, p[0].headingLevel)
        assertEquals("x", p[1].plainText())
    }

    @Test fun googleDocsWrapperIsNotBoldButSpanStylesAre() {
        val docs = "<b style=\"font-weight:normal;\" id=\"docs-internal-guid-1\"><p dir=\"ltr\">" +
            "<span style=\"font-weight:700;\">Strong</span><span style=\"font-weight:400;\"> plain</span></p>" +
            "<br><p dir=\"ltr\"><span style=\"font-style:italic;\">slanted</span></p></b>"
        assertEquals(listOf("**Strong** plain", "", "*slanted*"), html(docs))
    }

    @Test fun markdownCharactersInTextStayLiteral() {
        val p = paras("<p>2 * 3 = 6, a_b, [x], # no heading, costs \$5 and \$6</p><p>- not a list</p>")
        assertEquals("2 * 3 = 6, a_b, [x], # no heading, costs \$5 and \$6", p[0].plainText())
        assertEquals("- not a list", p[1].plainText())
        assertEquals(0, p[1].indent)
    }

    @Test fun linksListsQuotesAndCode() {
        assertEquals(
            listOf("[site](https://example.com)", "- a\n- b\n    1. c", "> q1\n>\n> q2", "```\nval x = 1\n  y\n```", "use `a*b`"),
            html(
                "<a href=\"https://example.com\">site</a>" +
                    "<ul><li>a</li><li>b<ol><li>c</li></ol></li></ul>" +
                    "<blockquote><p>q1</p><p>q2</p></blockquote>" +
                    "<pre><code>val x = 1\n  y</code></pre><p>use <code>a*b</code></p>",
            ),
        )
    }

    @Test fun tables() {
        assertEquals(
            listOf("| A | B |\n| --- | --- |\n| 1 | x\\|y |"),
            html("<table><tr><th>A</th><th>B</th></tr><tr><td><p>1</p></td><td>x|y</td></tr></table>"),
        )
    }

    @Test fun katexKeepsItsTexAndDropsTheDrawnCopy() {
        val katex = "<p>Area <span class=\"katex\"><span class=\"katex-mathml\"><math><semantics><mi>x</mi>" +
            "<annotation encoding=\"application/x-tex\">\\pi r^2</annotation></semantics></math></span>" +
            "<span class=\"katex-html\" aria-hidden=\"true\"><span>πr2</span></span></span> here</p>" +
            "<math display=\"block\"><annotation encoding=\"application/x-tex\">a_1 &lt; b</annotation></math>"
        assertEquals(listOf("Area \$\\pi r^2\$ here", "\$\$a_1 < b\$\$"), html(katex))
        val p = paras(katex)
        assertTrue(p[0].runs.any { it.style.math && it.text == "\\pi r^2" })
        assertTrue(p[1].runs.single().style.mathDisplay)
    }

    @Test fun markSpanningLinesClosesAndReopens() {
        assertEquals(listOf("**a**", "**b**"), html("<b>a<br>b</b>"))
        assertEquals(listOf("x"), html("<p><i></i>x<b> </b></p><script>bad()</script><style>p{}</style>"))
    }

    @Test fun plainBulletLinesBecomeLists() {
        assertEquals(
            listOf("Shopping:", "- milk\n- eggs\n    1. brown\n    2. white\n- \\*bread\\*", "done"),
            RichPaste.fromPlain("Shopping:\n• milk\r\n- eggs\n  1. brown\n  2) white\n* *bread*\ndone"),
        )
        val p = RichPaste.fromPlain("- a\n\t- b")!!.flatMap { MarkdownParser.parse(it) }
        assertEquals(listOf("a", "b"), p.map { it.plainText() })
        assertEquals(ListKind.BULLET, p[1].list)
        assertTrue(p[1].indent > p[0].indent)
        assertNull(RichPaste.fromPlain("-not a bullet\n2020 was a year"))
    }

    @Test fun characterBulletsInHtmlBecomeListItems() {
        assertEquals(listOf("- one", "- two"), html("<p>•&nbsp;one</p><p class=MsoListParagraph>· two</p>"))
    }

    @Test fun plainTextOnlyWhenItHoldsMaths() {
        assertNull(RichPaste.fromPlain("just *text*, \$5 and \$6"))
        assertEquals(listOf("Euler: \$e^{i\\pi}+1=0\$ \\*", "\$\$a_1\$\$"), RichPaste.fromPlain("Euler: \$e^{i\\pi}+1=0\$ *\n\$\$a_1\$\$"))
        assertEquals(listOf("\\[x^2\\]"), RichPaste.fromPlain("\\[x^2\\]"))
    }
}
