package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun parse(text: String) = MarkdownParser.parse(text)

    @Test
    fun headingsTakeTheirLevelAndBold() {
        val p = parse("## Section")[0]
        assertEquals("Section", p.plainText())
        assertEquals(2, p.headingLevel)
        assertTrue(p.runs[0].style.bold)
        // The level sizes it (see TextFlowTest), so the run carries no size of its own.
        assertEquals(null, p.runs[0].style.sizePt)
    }

    @Test
    fun fencedCodeBecomesCodeParagraphsWithLanguage() {
        val paras = parse("before\n```kotlin\nval x = 1\n\n  indented\n```\nafter")
        assertEquals(5, paras.size)
        assertEquals(null, paras[0].codeLang)
        assertEquals("kotlin", paras[1].codeLang)
        assertEquals("val x = 1", paras[1].plainText())
        assertEquals("kotlin", paras[2].codeLang)
        assertEquals(0, paras[2].length)
        assertEquals("  indented", paras[3].plainText())
        assertEquals(null, paras[4].codeLang)
    }

    @Test
    fun listsTasksAndQuotesMapToParagraphProperties() {
        val paras = parse("- a\n  - nested\n1. one\n- [x] done\n- [ ] todo\n> quoted")
        assertEquals(ListKind.BULLET, paras[0].list)
        assertEquals(0, paras[0].indent)
        assertEquals(1, paras[1].indent)
        assertEquals(ListKind.ORDERED, paras[2].list)
        assertEquals(ListKind.CHECK, paras[3].list)
        assertTrue(paras[3].checked)
        assertFalse(paras[4].checked)
        assertEquals("quoted", paras[5].plainText())
        assertEquals(1, paras[5].indent)
    }

    @Test
    fun inlineEmphasisNestsAndMerges() {
        val runs = parse("a **bold _both_** `code` ~~gone~~ plain")[0].runs
        assertEquals("a bold both code gone plain", runs.joinToString("") { it.text })
        val bold = runs.first { it.text == "bold " }
        assertTrue(bold.style.bold)
        assertFalse(bold.style.italic)
        val both = runs.first { it.text == "both" }
        assertTrue(both.style.bold)
        assertTrue(both.style.italic)
        assertTrue(runs.first { it.text == "code" }.style.code)
        assertTrue(runs.first { it.text == "gone" }.style.strike)
    }

    @Test
    fun unmatchedMarkersStayLiteral() {
        val p = parse("2 * 3 = 6 and a_var stays")[0]
        assertEquals("2 * 3 = 6 and a_var stays", p.plainText())
        assertEquals(1, p.runs.size)
        assertEquals(CharStyle.DEFAULT, p.runs[0].style)
    }

    @Test
    fun linksKeepStyledTextAndDropTheUrl() {
        val runs = parse("see [the docs](https://x.y) now")[0].runs
        assertEquals("see the docs now", runs.joinToString("") { it.text })
        val link = runs.first { it.text == "the docs" }
        assertTrue(link.style.underline)
        assertEquals(MarkdownParser.LINK_COLOR, link.style.color)
        val img = parse("![alt text](pic.png)")[0]
        assertEquals("alt text", img.plainText())
    }

    @Test
    fun pipeTablesBecomeTablesWithHeaderAndAlignment() {
        val paras = parse("intro\n| Name | Score |\n|:-----|------:|\n| **Ada** | 10 |\n| Bob \\| Co |\n\nafter")
        val cells = CellIndex(paras)
        val b = cells.tables.single()
        assertEquals(2, b.cols)
        assertEquals(3, b.rows)
        assertTrue(b.table.style.headerRow)
        assertEquals("intro", paras[0].plainText())
        assertEquals("Score", paras[b.cellFirstPara(0, 1)].plainText())
        assertEquals(ParaAlign.RIGHT, paras[b.cellFirstPara(1, 1)].align)
        assertTrue(paras[b.cellFirstPara(1, 0)].runs.single().style.bold)
        assertEquals("Bob | Co", paras[b.cellFirstPara(2, 0)].plainText())
        assertEquals("", paras[b.cellFirstPara(2, 1)].plainText())
        assertEquals("after", paras.last().plainText())
    }

    @Test
    fun aLoneDashLineIsNotATable() {
        val paras = parse("a | b\n---\nc")
        assertTrue(CellIndex(paras).isEmpty)
    }
    // --- maths ---

    @Test
    fun dollarsPasteAsAFormula() {
        val p = parse("mass is \$E=mc^2\$ roughly")[0]
        assertEquals("mass is E=mc^2 roughly", p.plainText())
        val f = p.runs.first { it.style.math }
        assertEquals("E=mc^2", f.text)
        assertFalse(f.style.mathDisplay)
    }

    @Test
    fun aFormulasLatexIsTakenLiterally() {
        // Stars and underscores inside maths are LaTeX, not emphasis.
        val p = parse("\$a_1 * b^{2} \\times c_2\$")[0]
        val f = p.runs.single()
        assertEquals("a_1 * b^{2} \\times c_2", f.text)
        assertTrue(f.style.math)
        assertFalse(f.style.italic)
    }

    @Test
    fun doubledDollarsPasteAsADisplayFormula() {
        val p = parse("\$\$\\sum_{i=1}^n i\$\$")[0]
        val f = p.runs.single()
        assertEquals("\\sum_{i=1}^n i", f.text)
        assertTrue(f.style.mathDisplay)
        assertEquals(ParaAlign.LEFT, p.align)
    }

    @Test
    fun aDisplayFormulaMidSentenceIsNotCentred() {
        val p = parse("see \$\$x^2\$\$ there")[0]
        assertEquals("see x^2 there", p.plainText())
        assertTrue(p.runs.first { it.style.math }.style.mathDisplay)
        assertEquals(ParaAlign.LEFT, p.align)
    }

    @Test
    fun aFencedDollarBlockIsOneFormulaNotAParagraphPerLine() {
        val paras = parse("before\n\$\$\n\\frac{a}{b}\n= c\n\$\$\nafter")
        assertEquals(3, paras.size)
        assertEquals("before", paras[0].plainText())
        assertEquals("\\frac{a}{b} = c", paras[1].runs.single().text)
        assertTrue(paras[1].runs.single().style.mathDisplay)
        assertEquals(ParaAlign.LEFT, paras[1].align)
        assertEquals("after", paras[2].plainText())
    }

    @Test
    fun moneyPastesAsMoney() {
        val p = parse("it cost \$5 and \$10")[0]
        assertEquals("it cost \$5 and \$10", p.plainText())
        assertTrue(p.runs.none { it.style.math })
    }

    @Test
    fun anUnclosedDollarIsJustADollar() {
        assertEquals("half \$x of it", parse("half \$x of it")[0].plainText())
        assertTrue(parse("half \$x of it")[0].runs.none { it.style.math })
    }

    @Test
    fun pastedAndTypedMathsAgree() {
        // The parser and the input rules are meant to land the same paragraph.
        val pasted = parse("\$x^2\$")[0]
        val flow = TextFlow()
        var pos = FlowPos.START
        for (ch in "\$x^2\$") {
            val (_, caret) = FlowEditor(flow).replaceRange(FlowRange.caret(pos), ch.toString())
            pos = caret
            InputRules.forTyped(flow, pos, ch.toString())?.let { pos = InputRules.apply(flow, pos, it).caret }
        }
        val typed = flow.paragraphs[0]
        assertEquals(typed.plainText(), pasted.plainText())
        assertEquals(typed.runs.single().style.math, pasted.runs.single().style.math)
    }

    @Test
    fun paddedDollarsPasteAsAFormulaToo() {
        val p = parse("see \$ x^2 \$ here")[0]
        assertEquals("see x^2 here", p.plainText())
        assertEquals("x^2", p.runs.first { it.style.math }.text)
    }

    @Test
    fun pastedPaddingHasToMatchOnBothSidesAsWell() {
        assertTrue(parse("\$x^2 \$")[0].runs.none { it.style.math })
        assertTrue(parse("\$ x^2\$")[0].runs.none { it.style.math })
        assertTrue(parse("\$   \$")[0].runs.none { it.style.math })
    }

    @Test
    fun inlineHtmlStylesLikeMarkdownAndDropsOtherTags() {
        val runs = parse("<b>bold</b> <em>it</em> <span class=\"x\">plain</span> <code>a<b</code> &amp; &#169;")[0].runs
        assertEquals("bold it plain a<b & \u00A9", runs.joinToString("") { it.text })
        assertTrue(runs.first { it.text == "bold" }.style.bold)
        assertTrue(runs.first { it.text == "it" }.style.italic)
        assertFalse(runs.first { it.text.contains("plain") }.style.bold)
        assertTrue(runs.first { it.text == "a<b" }.style.code)
        assertEquals("a < b", parse("a < b")[0].plainText())
    }

    @Test
    fun blockHtmlBecomesLinesHeadingsAndItems() {
        val paras = parse("<div>\n<h2>Title</h2>\none<br>two\n<li>item</li>\n</div>")
        assertEquals(listOf("Title", "one", "two", "item"), paras.map { it.plainText() })
        assertEquals(2, paras[0].headingLevel)
        assertEquals(ListKind.BULLET, paras[3].list)
    }
}
