package com.xnotes.core.search

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Page
import com.xnotes.core.model.TextItem
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.PageBox
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TextFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class TypedTextsTest {

    private val measurer = FakeTextMeasurer()

    /** Pages one 12 pt line tall (15.6 px) and ten chars wide (7.2 px each), so every line is a page. */
    private fun flowOf(vararg texts: String): TextFlow = TextFlow().apply {
        margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
        texts.forEach { paragraphs += Paragraph(mutableListOf(Run(it))) }
    }

    private fun pages(n: Int): List<Page> = List(n) { Page(72.0, 20.0) }

    @Test
    fun flowParagraphsComeFirstAndKnowTheirPages() {
        val flow = flowOf("alpha", "", "aaaa bbbb cccc")
        val pages = pages(4)
        val box = TextItem(Pt(0.0, 0.0), width = 50.0, text = "in a box", measurer = measurer)
        pages[3].items += box
        val frame = FlowLayout(measurer).layout(flow, pages.map { PageBox(it.width, it.height) }, 150)
        val typed = TypedTexts.of(pages, flow, frame)
        assertEquals(listOf(SearchTarget.Flow(0), SearchTarget.Flow(2), SearchTarget.Box(box)), typed.map { it.target })
        assertEquals(0, typed[0].pageOf(0))
        assertEquals(2, typed[1].pageOf(0))
        assertEquals(3, typed[1].pageOf(10))
        assertEquals(3, typed[2].pageOf(5))
    }

    @Test
    fun withoutALayoutOnlyTheBoxesAreSearched() {
        val pages = pages(1)
        pages[0].items += TextItem(Pt(0.0, 0.0), text = "box", measurer = measurer)
        pages[0].items += TextItem(Pt(0.0, 0.0), text = "  ", measurer = measurer)
        val typed = TypedTexts.of(pages, flowOf("flow"), null)
        assertEquals(listOf("box"), typed.map { it.source })
    }
}
