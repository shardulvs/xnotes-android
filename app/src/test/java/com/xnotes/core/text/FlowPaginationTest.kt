package com.xnotes.core.text

import com.xnotes.core.FakeMathTypesetter
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pagination against the fake measurer: at the 12pt default a char is 7.2 wide
 * and a line is 15.6 tall, so a margin-less 100x50 page holds 3 lines.
 */
class FlowPaginationTest {

    private val layout = FlowLayout(FakeTextMeasurer())

    private fun flowOf(vararg lines: String): TextFlow = TextFlow().apply {
        margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
        lines.forEach {
            paragraphs.add(Paragraph(if (it.isEmpty()) mutableListOf() else mutableListOf(Run(it))))
        }
    }

    private fun pages(n: Int) = List(n) { PageBox(100.0, 50.0) }

    @Test
    fun fillsPagesTopToBottom() {
        val flow = flowOf(*Array(8) { "" })
        val frame = layout.layout(flow, pages(3), 150)
        assertEquals(listOf(3, 3, 2), frame.pages.map { it.lines.size })
        assertEquals(0.0, frame.pages[0].lines[0].top, 1e-9)
        assertEquals(15.6, frame.pages[0].lines[1].top, 1e-9)
        assertEquals(0.0, frame.pages[1].lines[0].top, 1e-9)
        assertEquals(0, frame.extraPagesNeeded)
        assertEquals(setOf(0, 1, 2), frame.pagesWithLines())
    }

    @Test
    fun overflowPastTheLastPageIsCounted() {
        val flow = flowOf(*Array(8) { "" })
        val frame = layout.layout(flow, pages(1), 150)
        assertEquals(3, frame.pages[0].lines.size)
        assertEquals(2, frame.extraPagesNeeded)
        assertEquals(0 to 46.8, frame.lastLineEnd())
    }

    @Test
    fun overflowIsCountedInPagesOfTheAddedSize() {
        // Added pages half as tall hold one line each, not three.
        val flow = flowOf(*Array(8) { "" })
        val frame = layout.layout(flow, pages(1), 150, addedPage = PageBox(100.0, 25.0))
        assertEquals(3, frame.pages[0].lines.size)
        assertEquals(5, frame.extraPagesNeeded)
    }

    @Test
    fun continuationRebreaksAtTheNextPageWidth() {
        val flow = flowOf("aaaa bbbb cccc dddd eeee")
        val boxes = listOf(PageBox(100.0, 16.0), PageBox(60.0, 100.0))
        val frame = layout.layout(flow, boxes, 150)
        assertEquals(1, frame.pages[0].lines.size)
        assertEquals(10, frame.pages[0].lines[0].endChar)
        assertEquals(listOf(10, 15, 20), frame.pages[1].lines.map { it.startChar })
        assertEquals(listOf(15, 20, 24), frame.pages[1].lines.map { it.endChar })
    }

    @Test
    fun justifyStretchesInteriorSpacesToTheMargin() {
        val flow = flowOf("aa bb cc dd ee")
        flow.paragraphs[0].align = ParaAlign.JUSTIFY
        val frame = layout.layout(flow, pages(1), 150)
        val first = frame.pages[0].lines[0]
        assertEquals(12, first.endChar)
        assertEquals(100.0, first.xs[11], 1e-6)
        val last = frame.pages[0].lines[1]
        assertEquals(0.0, last.xs[0], 1e-9)
        assertEquals(14.4, last.xs[2] - last.xs[0], 1e-9)
    }

    @Test
    fun centerAndRightAlignOffsetTheLine() {
        val center = flowOf("aaaa").apply { paragraphs[0].align = ParaAlign.CENTER }
        assertEquals(35.6, layout.layout(center, pages(1), 150).pages[0].lines[0].xs[0], 1e-9)
        val right = flowOf("aaaa").apply { paragraphs[0].align = ParaAlign.RIGHT }
        assertEquals(71.2, layout.layout(right, pages(1), 150).pages[0].lines[0].xs[0], 1e-9)
    }

    @Test
    fun marginsInsetTheContentRect() {
        val flow = flowOf("x").apply { margins = FlowMargins(25.4, 25.4, 25.4, 25.4) }
        val frame = layout.layout(flow, listOf(PageBox(1240.0, 1754.0)), 150)
        val rect = frame.pages[0].contentRect
        assertEquals(150.0, rect.left, 1e-9)
        assertEquals(150.0, rect.top, 1e-9)
        assertEquals(1240.0 - 300.0, rect.w, 1e-9)
        assertEquals(150.0, frame.pages[0].lines[0].top, 1e-9)
    }

    @Test
    fun hitTestAndCaretRectAgree() {
        val flow = flowOf("hello world", "todo")
        flow.paragraphs[1].list = ListKind.CHECK
        val frame = layout.layout(flow, pages(2), 150)

        val hit = frame.hitTest(0, Pt(22.0, 5.0))
        assertTrue(hit is FlowHit.Caret)
        assertEquals(FlowPos(0, 3), (hit as FlowHit.Caret).pos)
        val (page, rect) = frame.caretRect(hit.pos)!!
        assertEquals(0, page)
        assertEquals(21.6, rect.left, 1e-9)
        assertEquals(0.0, rect.top, 1e-9)

        val checkboxLine = frame.pages[0].lines[1]
        val marker = checkboxLine.marker!!
        assertEquals(ListKind.CHECK, marker.kind)
        val boxHit = frame.hitTest(0, marker.rect.center)
        assertTrue(boxHit is FlowHit.Checkbox)
        assertEquals(1, (boxHit as FlowHit.Checkbox).paraIndex)

        assertTrue(frame.hitTest(1, Pt(50.0, 25.0)) is FlowHit.BeyondEnd)
        assertTrue(frame.hitTest(0, Pt(50.0, 49.0)) is FlowHit.BeyondEnd)
    }

    @Test
    fun emptyLineFillWalksTheIntermediatePages() {
        val flow = flowOf("x")
        val frame = layout.layout(flow, pages(3), 150)
        val slot = layout.defaultSlotHeight(flow)
        assertEquals(15.6, slot, 1e-9)

        val count = frame.emptyLinesToReach(2, 25.0, slot)
        assertEquals(7, count)

        FlowEditor(flow).appendEmptyLines(count)
        val after = layout.layout(flow, pages(3), 150)
        val (page, rect) = after.caretRect(FlowPos(7, 0))!!
        assertEquals(2, page)
        assertTrue(25.0 >= rect.top && 25.0 <= rect.bottom)
        assertEquals(0, after.extraPagesNeeded)

        assertEquals(0, frame.emptyLinesToReach(0, 5.0, slot))
    }

    @Test
    fun orderedListNumberingRunsAndResets() {
        val flow = flowOf("one", "two", "bullet", "restart")
        flow.paragraphs[0].list = ListKind.ORDERED
        flow.paragraphs[1].list = ListKind.ORDERED
        flow.paragraphs[2].list = ListKind.BULLET
        flow.paragraphs[3].list = ListKind.ORDERED
        val frame = layout.layout(flow, listOf(PageBox(400.0, 400.0)), 150)
        val markers = frame.pages[0].lines.map { it.marker!! }
        assertEquals(listOf(1, 2, 0, 1), markers.map { it.ordinal })
        assertEquals(FlowLayout.MARKER_GUTTER_PX, frame.pages[0].lines[0].xs[0], 1e-9)
    }

    @Test
    fun selectionRectsMarkTheParagraphBreak() {
        val flow = flowOf("ab", "cd")
        val frame = layout.layout(flow, pages(1), 150)
        val rects = frame.selectionRects(FlowRange(FlowPos(0, 1), FlowPos(1, 1)))
        assertEquals(2, rects.size)
        assertEquals(7.2 + FlowFrame.NEWLINE_TAIL, rects[0].second.w, 1e-9)
        assertEquals(7.2, rects[1].second.w, 1e-9)
    }
    // --- tall boxes: equations ---
    //
    // The fake typesetter sets a formula three times the text ascent, so at the
    // 12pt default an inline one is 39.6 tall and a display one 57.6, against a
    // 15.6 line and a 50-high page. That makes an inline formula taller than the
    // space left under one line of text, and a display formula taller than the
    // whole page, which are the two cases pagination has to survive.

    private val mathLayout = FlowLayout(FakeTextMeasurer(), FakeMathTypesetter())

    private fun mathFlow(vararg paras: Pair<String, CharStyle>): TextFlow = TextFlow().apply {
        margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
        paras.forEach { (text, style) -> paragraphs.add(Paragraph(mutableListOf(Run(text, style)))) }
    }

    private val plain = CharStyle.DEFAULT
    private val inlineMath = CharStyle(math = true)
    private val displayMath = CharStyle(math = true, mathDisplay = true)

    @Test
    fun aFormulaTallerThanTheSpaceLeftMovesToTheNextPage() {
        // 15.6 + 39.6 = 55.2, past the 50 the page has.
        val flow = mathFlow("text" to plain, "x^2" to inlineMath)
        val frame = mathLayout.layout(flow, pages(2), 150)
        assertEquals(1, frame.pages[0].lines.size)
        assertEquals(1, frame.pages[1].lines.size)
        assertEquals(1, frame.pages[1].lines[0].paraIndex)
        assertEquals(0.0, frame.pages[1].lines[0].top, 1e-9)
    }

    @Test(timeout = 5_000)
    fun aFormulaTallerThanThePageIsPlacedWhereItIsRatherThanBumpedOn() {
        // 57.6 against a 50-high page: no page can hold it, so bumping it to the
        // next one only leaves a blank page behind and loses it off the end. It
        // goes down where it stands and overflows instead. Deleting the
        // "y > rect.top" half of the page-break test is what this catches.
        val flow = mathFlow("x^2" to displayMath)
        val frame = mathLayout.layout(flow, pages(2), 150)
        assertEquals(1, frame.pages[0].lines.size)
        assertEquals(0.0, frame.pages[0].lines[0].top, 1e-9)
        assertTrue(frame.pages[0].lines[0].bottom > frame.pages[0].contentRect.bottom)
        assertTrue(frame.pages[1].lines.isEmpty())
        assertEquals(0, frame.extraPagesNeeded)
    }

    @Test(timeout = 5_000)
    fun anOversizedFormulaAfterTextTakesOneNewPageAndNoMore() {
        val flow = mathFlow("text" to plain, "x^2" to displayMath)
        val frame = mathLayout.layout(flow, pages(3), 150)
        assertEquals(setOf(0, 1), frame.pagesWithLines())
        assertEquals(1, frame.pages[1].lines.size)
        assertEquals(0, frame.extraPagesNeeded)
    }

    @Test(timeout = 5_000)
    fun textCarriesOnPastAnOverflowingFormula() {
        val flow = mathFlow("x^2" to displayMath, "after" to plain)
        val frame = mathLayout.layout(flow, pages(2), 150)
        assertEquals(listOf(0), frame.pages[0].lines.map { it.paraIndex })
        assertEquals(listOf(1), frame.pages[1].lines.map { it.paraIndex })
    }

    @Test(timeout = 5_000)
    fun aRunOfOversizedFormulasTakesOnePageEachAndTerminates() {
        val flow = mathFlow(*Array(4) { "x^2" to displayMath })
        val frame = mathLayout.layout(flow, pages(2), 150)
        assertEquals(1, frame.pages[0].lines.size)
        assertEquals(1, frame.pages[1].lines.size)
        // Two more had nowhere to go, and the count of them has to stay finite.
        assertEquals(2, frame.extraPagesNeeded)
    }

    @Test(timeout = 5_000)
    fun aFormulaWiderThanThePageOverflowsRatherThanDisappearing() {
        // Ten characters set 216 wide against a 100 page, and it cannot be split.
        val flow = mathFlow("abcdefghij" to inlineMath)
        val frame = mathLayout.layout(flow, pages(2), 150)
        val lines = frame.pages[0].lines
        assertEquals(1, lines.size)
        assertEquals(0, lines[0].startChar)
        assertEquals(10, lines[0].endChar)
        assertTrue(lines[0].xs.last() > frame.pages[0].contentRect.right)
    }

    @Test(timeout = 5_000)
    fun anOversizedFormulaAcrossNarrowingPagesStillTerminates() {
        // Pages of different widths send a paragraph back to be re-broken. A
        // formula cannot be re-broken, so this is where a rebreak loop would show.
        val flow = mathFlow("text" to plain, "abcdefghij" to inlineMath, "tail" to plain)
        val boxes = listOf(PageBox(100.0, 50.0), PageBox(60.0, 50.0), PageBox(100.0, 50.0))
        val frame = mathLayout.layout(flow, boxes, 150)
        assertEquals(setOf(0, 1, 2), frame.pagesWithLines())
        assertEquals(10, frame.pages[1].lines[0].endChar)
    }

    @Test
    fun paginationIsUntouchedWhenNothingIsAFormula() {
        // The same flow through the math-aware layout paginates exactly as before.
        val flow = flowOf(*Array(8) { "" })
        val frame = mathLayout.layout(flow, pages(3), 150)
        assertEquals(listOf(3, 3, 2), frame.pages.map { it.lines.size })
        assertEquals(0, frame.extraPagesNeeded)
    }
    @Test
    fun aFlowStartingPastTheCoverLeavesItEmpty() {
        val frame = layout.layout(flowOf(*Array(5) { "" }), pages(3), 150, firstPage = 1)
        assertEquals(listOf(0, 3, 2), frame.pages.map { it.lines.size })
        assertEquals(0, frame.extraPagesNeeded)
        assertEquals(1, frame.firstPage)
    }

    @Test
    fun anEmptyFlowPastTheCoverFillsFromItsFirstPage() {
        val frame = layout.layout(TextFlow().apply { margins = FlowMargins(0.0, 0.0, 0.0, 0.0) }, pages(3), 150, firstPage = 1)
        // A tap two lines down page 1 needs two lines before the caret's, none for the cover.
        assertEquals(3, frame.emptyLinesToReach(1, 2 * 15.6 + 1.0, 15.6))
        assertEquals(0, frame.emptyLinesToReach(0, 40.0, 15.6))
    }

    // --- page breaks ---

    @Test
    fun aPageBreakStartsTheNextPage() {
        val flow = flowOf("a", "b", "c")
        flow.paragraphs[1].breakBefore = true
        val frame = layout.layout(flow, pages(2), 150)
        assertEquals(listOf(1, 2), frame.pages.map { it.lines.size })
        assertEquals(1, frame.pages[1].lines[0].paraIndex)
        val mark = frame.pages[0].breaks.single()
        assertTrue(mark.y > frame.pages[0].lines[0].bottom)
        assertTrue(frame.pages[1].breaks.isEmpty())
    }

    @Test
    fun aBreakAlreadyAtThePageTopPushesNothingButStillShows() {
        val flow = flowOf("a", "b")
        flow.paragraphs[0].breakBefore = true
        val frame = layout.layout(flow, pages(2), 150)
        assertEquals(listOf(2, 0), frame.pages.map { it.lines.size })
        assertTrue(frame.pages[0].breaks.single().y < 0.0)
    }

    @Test
    fun aBreakOnTheLastPageAsksForAnotherPage() {
        val flow = flowOf("a", "b")
        flow.paragraphs[1].breakBefore = true
        assertEquals(1, layout.layout(flow, pages(1), 150).extraPagesNeeded)
    }

    @Test
    fun aBreakToAPageSkipsThePagesBetween() {
        val flow = flowOf("a", "b")
        flow.paragraphs[1].breakBefore = true
        flow.paragraphs[1].breakToPage = 3
        assertEquals(listOf(1, 0, 0, 1), layout.layout(flow, pages(4), 150).pages.map { it.lines.size })
    }

    @Test
    fun textRunningPastABreaksPageCarriesItOn() {
        val flow = flowOf("1", "2", "3", "4", "b")
        flow.paragraphs[4].breakBefore = true
        flow.paragraphs[4].breakToPage = 1
        assertEquals(listOf(3, 1, 1), layout.layout(flow, pages(3), 150).pages.map { it.lines.size })
    }

    @Test
    fun aBreakToAPageOpeningTheFlowStartsThere() {
        val flow = flowOf("b")
        flow.paragraphs[0].breakBefore = true
        flow.paragraphs[0].breakToPage = 2
        assertEquals(listOf(0, 0, 1), layout.layout(flow, pages(3), 150).pages.map { it.lines.size })
    }

    @Test
    fun aBreakToAPageNoLongerThereIsAPlainBreak() {
        val flow = flowOf("a", "b")
        flow.paragraphs[1].breakBefore = true
        flow.paragraphs[1].breakToPage = 9
        assertEquals(listOf(1, 1), layout.layout(flow, pages(2), 150).pages.map { it.lines.size })
    }

    // --- list numbering ---

    private fun item(text: String, indent: Int = 0, kind: ListKind = ListKind.ORDERED, start: Int? = null) =
        Paragraph(mutableListOf(Run(text)), indent = indent, list = kind, listStart = start)

    private fun ordinals(vararg paras: Paragraph): List<Int?> {
        val flow = TextFlow().apply {
            margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
            paragraphs.addAll(paras)
        }
        val frame = layout.layout(flow, listOf(PageBox(400.0, 400.0)), 150)
        return frame.pages[0].lines.map { it.marker?.ordinal }
    }

    @Test
    fun anOrderedListCountsFromItsStart() {
        assertEquals(listOf(3, 4, 5), ordinals(item("a", start = 3), item("b"), item("c")))
    }

    @Test
    fun aNestedListCountsApartFromTheOneAroundIt() {
        val got = ordinals(
            item("a"), item("x", 1), item("y", 1), item("b"),
            item("dot", 1, ListKind.BULLET), item("c"),
        )
        assertEquals(listOf(1, 1, 2, 2, 0, 3), got)
    }

    @Test
    fun aParagraphContinuingAnItemKeepsTheCountButBodyTextEndsIt() {
        val more = Paragraph(mutableListOf(Run("more")), indent = 1)
        val body = Paragraph(mutableListOf(Run("body")))
        assertEquals(listOf(1, null, 2, null, 1), ordinals(item("a"), more, item("b"), body, item("c")))
    }

    // --- quotes and callouts ---

    @Test
    fun aQuoteStandsInAndACalloutTitleSitsAboveItsFirstLine() {
        val note = FlowQuote(CalloutKind.NOTE)
        val flow = flowOf("a", "b", "c")
        flow.paragraphs[1].quote = note
        flow.paragraphs[2].quote = note
        val frame = layout.layout(flow, listOf(PageBox(200.0, 400.0)), 150)
        val lines = frame.pages[0].lines
        assertEquals(-1, lines[0].quoteId)
        assertEquals(0, lines[1].quoteId)
        assertEquals(0, lines[2].quoteId)
        assertEquals(FlowLayout.QUOTE_INSET_PX, lines[1].xs[0], 1e-9)
        val title = frame.pages[0].callouts.single()
        assertEquals(CalloutKind.NOTE, title.kind)
        assertEquals(1, title.para)
        assertEquals(lines[0].bottom, title.top, 1e-9)
        assertEquals(title.bottom, lines[1].top, 1e-9)
    }

    @Test
    fun aCalloutTitleKeepsWithItsFirstLine() {
        val flow = flowOf("a", "b", "c")
        flow.paragraphs[2].quote = FlowQuote(CalloutKind.TIP)
        val frame = layout.layout(flow, pages(2), 150)
        assertEquals(listOf(2, 1), frame.pages.map { it.lines.size })
        assertTrue(frame.pages[0].callouts.isEmpty())
        assertEquals(0.0, frame.pages[1].callouts.single().top, 1e-9)
    }

    @Test
    fun twoNeighbouringQuotesAreTwo() {
        val flow = flowOf("a", "b")
        flow.paragraphs[0].quote = FlowQuote()
        flow.paragraphs[1].quote = FlowQuote()
        val lines = layout.layout(flow, pages(1), 150).pages[0].lines
        assertEquals(listOf(0, 1), lines.map { it.quoteId })
    }
}
