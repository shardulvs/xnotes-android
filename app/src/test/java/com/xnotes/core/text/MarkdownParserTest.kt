package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals(0, paras[5].indent)
        assertNotNull(paras[5].quote)
        assertTrue(paras.take(5).all { it.quote == null })
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
    fun linksKeepTheirAddress() {
        val runs = parse("see [the docs](https://x.y) now")[0].runs
        assertEquals("see the docs now", runs.joinToString("") { it.text })
        val link = runs.first { it.text == "the docs" }
        assertEquals("https://x.y", link.style.link)
        assertFalse(link.style.underline)
        assertEquals(null, link.style.color)
        val img = parse("![alt text](pic.png)")[0]
        assertEquals("alt text", img.plainText())
        assertTrue(img.runs.all { it.style.link == null })
    }

    @Test
    fun onlyAddressesThatOpenStayLinks() {
        assertEquals(null, parse("[up](#setup)")[0].runs.single().style.link)
        assertEquals(null, parse("[file](docs/a.md)")[0].runs.single().style.link)
        assertEquals("https://www.a.org", parse("[a](www.a.org)")[0].runs.single().style.link)
        assertEquals("mailto:x@y.z", parse("[mail](mailto:x@y.z)")[0].runs.single().style.link)
    }

    @Test
    fun writtenOutAddressesBecomeLinks() {
        val p = parse("go to <https://a.org> or www.b.org. Not `https://c.org`")[0]
        assertEquals("https://a.org", p.runs.first { it.text == "https://a.org" }.style.link)
        assertEquals("https://www.b.org", p.runs.first { it.text == "www.b.org" }.style.link)
        assertEquals(null, p.runs.first { it.text == "https://c.org" }.style.link)
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

    // --- CommonMark ---

    @Test
    fun wrappedLinesJoinAndBlankLinesLeaveOneGap() {
        val paras = parse("one\ntwo\n\n\n\nthree")
        assertEquals(listOf("one two", "", "three"), paras.map { it.plainText() })
    }

    @Test
    fun aHardBreakSplitsTheParagraph() {
        assertEquals(listOf("a", "b"), parse("a  \nb").map { it.plainText() })
        assertEquals(listOf("a", "b"), parse("a\\\nb").map { it.plainText() })
    }

    @Test
    fun escapesStayLiteral() {
        val p = parse("\\*not italic\\* and \\`tick\\`")[0]
        assertEquals("*not italic* and `tick`", p.plainText())
        assertTrue(p.runs.all { it.style == CharStyle.DEFAULT })
    }

    @Test
    fun tripleStarsAreBoldAndItalic() {
        val run = parse("***both***")[0].runs.single()
        assertEquals("both", run.text)
        assertTrue(run.style.bold)
        assertTrue(run.style.italic)
    }

    @Test
    fun doubleBackticksHoldABacktick() {
        val run = parse("`` a ` b ``")[0].runs.single()
        assertEquals("a ` b", run.text)
        assertTrue(run.style.code)
    }

    @Test
    fun aLoneTildeIsNotStrikethrough() {
        val p = parse("~5 min~ and ~~gone~~")[0]
        assertEquals("~5 min~ and ", p.runs[0].text)
        assertTrue(p.runs.last().style.strike)
    }

    @Test
    fun everyFenceKindIsCode() {
        val tilde = parse("~~~py\nx = 1\n~~~").single()
        assertEquals("python", tilde.codeLang)
        val nested = parse("````md\n```\n````").single()
        assertEquals("```", nested.plainText())
        assertEquals("md", nested.codeLang)
        assertEquals("python", parse("```python title=\"a.py\"\nx\n```").single().codeLang)
        val indented = parse("text\n\n    code here")
        assertEquals("", indented.last().codeLang)
        assertEquals("code here", indented.last().plainText())
    }

    @Test
    fun aFenceInsideAListItemIsCode() {
        val paras = parse("- item\n\n  ```kotlin\n  val x = 1\n  ```")
        assertEquals(ListKind.BULLET, paras[0].list)
        val code = paras.last()
        assertEquals("kotlin", code.codeLang)
        assertEquals("val x = 1", code.plainText())
        assertEquals(1, code.indent)
    }

    @Test
    fun underlinedHeadingsAreHeadings() {
        val paras = parse("Title\n=====\n\nSub\n---")
        assertEquals(1, paras[0].headingLevel)
        assertEquals("Sub", paras.last().plainText())
        assertEquals(2, paras.last().headingLevel)
    }

    @Test
    fun listItemsContinueAcrossLines() {
        val paras = parse("- first\n  more\n- second\nlazy")
        assertEquals(listOf("first more", "second lazy"), paras.map { it.plainText() })
        assertTrue(paras.all { it.list == ListKind.BULLET })
    }

    @Test
    fun aListInsideAQuoteKeepsItsMarker() {
        val p = parse("> - item").single()
        assertEquals(ListKind.BULLET, p.list)
        assertEquals(0, p.indent)
        assertNotNull(p.quote)
        val deep = parse("> a\n> > deep")
        assertTrue(deep[0].quote != null && deep[0].quote === deep.last().quote)
    }

    @Test
    fun frontMatterIsDropped() {
        assertEquals(listOf("body"), parse("---\ntitle: x\ntags: [a]\n---\nbody").map { it.plainText() })
    }

    @Test
    fun footnotesStayAsWritten() {
        val paras = parse("see[^1] there\n\n[^1]: the note")
        assertEquals("see[^1] there", paras[0].plainText())
        assertEquals("[^1]: the note", paras.last().plainText())
    }

    @Test
    fun htmlStaysLiteral() {
        assertEquals("a <b>c</b> d", parse("a <b>c</b> d")[0].plainText())
        assertEquals("<details>", parse("<details>\n<summary>x</summary>")[0].plainText())
    }

    @Test
    fun latexDelimitersAreMaths() {
        val p = parse("so \\(x^2\\) holds")[0]
        assertEquals("so x^2 holds", p.plainText())
        val inline = p.runs.first { it.style.math }
        assertFalse(inline.style.mathDisplay)
        val display = parse("\\[ \\int_0^1 x \\]").single().runs.single()
        assertEquals("\\int_0^1 x", display.text)
        assertTrue(display.style.mathDisplay)
        val block = parse("before\n\\[\n\\frac{a}{b}\n\\]\nafter")
        assertEquals(3, block.size)
        assertEquals("\\frac{a}{b}", block[1].runs.single().text)
        assertTrue(block[1].runs.single().style.mathDisplay)
    }

    @Test
    fun anUnclosedLatexParenIsJustAParen() {
        assertEquals("a (b", parse("a \\(b")[0].plainText())
    }

    @Test
    fun thematicBreaksAreRules() {
        val paras = parse("a\n\n---\n\n***\nb")
        assertEquals(2, paras.count { it.rule })
        assertTrue(paras.filter { it.rule }.all { it.length == 0 })
        assertEquals("b", paras.last().plainText())
    }

    @Test
    fun latexPageBreaksBreakThePage() {
        val paras = parse("a\n\n\\newpage\n\nb\n\n\\pagebreak")
        assertEquals(listOf("a", "b", ""), paras.map { it.plainText() })
        assertEquals(listOf(false, true, true), paras.map { it.breakBefore })
    }

    @Test
    fun anOrderedListKeepsItsStartNumber() {
        val paras = parse("3. a\n4. b\n\n1. fresh")
        assertEquals(3, paras[0].listStart)
        assertEquals(null, paras[1].listStart)
        assertEquals(null, paras.last().listStart)
    }

    @Test
    fun doubleEqualsHighlightsInTheGivenColour() {
        val red = com.xnotes.core.model.Rgba(255, 0, 0, 255)
        val p = MarkdownParser.parse("a ==marked **bold**== b and x == y", highlight = red)[0]
        assertEquals("a marked bold b and x == y", p.plainText())
        assertEquals(red, p.runs.first { it.text == "marked " }.style.highlight)
        val bold = p.runs.first { it.text == "bold" }
        assertTrue(bold.style.bold)
        assertEquals(red, bold.style.highlight)
        assertEquals(null, p.runs.last().style.highlight)
        assertEquals(CharStyle.DEFAULT_HIGHLIGHT, parse("==x==")[0].runs.single().style.highlight)
    }

    // --- quotes and callouts ---

    @Test
    fun aQuoteIsOneContainerGapsAndAll() {
        val paras = parse("> one\n>\n> two\n\nafter")
        val q = paras[0].quote
        assertNotNull(q)
        assertEquals(listOf("one", "", "two"), paras.take(3).map { it.plainText() })
        assertTrue(paras.take(3).all { it.quote === q })
        assertEquals(null, paras.last().quote)
    }

    @Test
    fun twoQuotesApartStayTwo() {
        val paras = parse("> a\n\n> b")
        assertTrue(paras.first().quote != null && paras.last().quote != null)
        assertTrue(paras.first().quote !== paras.last().quote)
    }

    @Test
    fun gitHubAlertsBecomeCallouts() {
        for (kind in CalloutKind.entries) {
            val paras = parse("> [!${kind.name}]\n> Body text\n> - item")
            assertEquals(listOf("Body text", "item"), paras.map { it.plainText() })
            assertTrue(paras.all { it.quote?.kind == kind })
            assertTrue(paras[0].quote === paras[1].quote)
        }
        assertEquals(null, parse("> [!BOGUS]\n> x").last().quote?.kind)
    }

    @Test
    fun codeCutsACalloutInTwo() {
        val paras = parse("> [!TIP]\n> a\n> ```\n> code\n> ```\n> b")
        val a = paras.first { it.plainText() == "a" }
        val code = paras.first { it.codeLang != null }
        val b = paras.first { it.plainText() == "b" }
        assertEquals(null, code.quote)
        assertEquals(CalloutKind.TIP, a.quote?.kind)
        assertEquals(CalloutKind.TIP, b.quote?.kind)
        assertTrue(a.quote !== b.quote)
    }

    @Test
    fun pastedShortcodesBecomeEmojiOutsideCode() {
        val p = parse("ship it :rocket: but not `:rocket:`")[0]
        assertEquals("ship it 🚀 but not :rocket:", p.plainText())
    }
}
