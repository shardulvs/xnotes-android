package com.xnotes.core.pdf

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.PageBox
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TextFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTest {

    /** The forest as nested values, e.g. "a(b c) d". */
    private fun <T> shape(nodes: List<OutlineNode<T>>): String = nodes.joinToString(" ") {
        if (it.kids.isEmpty()) "${it.value}" else "${it.value}(${shape(it.kids)})"
    }

    @Test
    fun headingsNestUnderTheClosestLowerLevelAbove() {
        val levels = listOf("a" to 1, "b" to 2, "c" to 3, "d" to 2, "e" to 1, "f" to 3, "g" to 2)
        assertEquals("a(b(c) d) e(f g)", shape(Outline.nest(levels) { it.second }.let { Outline.remap(it) { p -> p.first } }))
    }

    @Test
    fun aDocumentOpeningDeeperStartsAtTheTop() {
        assertEquals("x y(z)", shape(Outline.remap(Outline.nest(listOf("x" to 3, "y" to 1, "z" to 2)) { it.second }) { it.first }))
    }

    @Test
    fun aDroppedEntryLeavesItsChildrenInItsPlace() {
        val tree = listOf(
            OutlineNode("a", listOf(OutlineNode("b", listOf(OutlineNode("c"), OutlineNode("d"))), OutlineNode("e"))),
            OutlineNode("f"),
        )
        assertEquals("a(c d e) f", shape(Outline.remap(tree) { it.takeIf { v -> v != "b" } }))
        assertEquals("c d e f", shape(Outline.remap(tree) { it.takeIf { v -> v != "a" && v != "b" } }))
    }

    @Test
    fun aSourcePageMapsToWhereItFirstAppears() {
        assertEquals(mapOf(0 to 0, 2 to 1, 1 to 4), Outline.pageMap(listOf(0, 2, null, 2, 1)))
    }

    private fun frame(flow: TextFlow, pageHeight: Double = 400.0) =
        FlowLayout(FakeTextMeasurer()).layout(flow, listOf(PageBox(400.0, pageHeight), PageBox(400.0, pageHeight)), 150)

    @Test
    fun headingsCarryTheirPageTopAndLatex() {
        val flow = TextFlow().apply {
            margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
            paragraphs.add(Paragraph(mutableListOf(Run("Intro  text ")), headingLevel = 1))
            paragraphs.add(Paragraph(mutableListOf(Run("body"))))
            paragraphs.add(Paragraph(mutableListOf(Run("Energy "), Run("E=mc^2", CharStyle(math = true))), headingLevel = 2))
            paragraphs.add(Paragraph(mutableListOf(Run(" ")), headingLevel = 2))
        }
        val hs = FlowHeadings.find(flow, frame(flow))
        assertEquals(listOf("Intro text", "Energy \$E=mc^2\$"), hs.map { it.title })
        assertEquals(listOf(1, 2), hs.map { it.level })
        assertEquals(0.0, hs[0].top, 1e-9)
        assertTrue(hs[1].top > hs[0].top)
    }

    @Test
    fun aHeadingOnTheNextPageSaysSo() {
        val flow = TextFlow().apply {
            margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
            // Six 15.6 px lines fill a 100 px page, so the ninth paragraph lands on the second.
            repeat(8) { paragraphs.add(Paragraph(mutableListOf(Run("line $it")))) }
            paragraphs.add(Paragraph(mutableListOf(Run("Later")), headingLevel = 3))
        }
        val h = FlowHeadings.find(flow, frame(flow, pageHeight = 100.0)).single()
        assertEquals(1, h.page)
        assertEquals(8, h.para)
    }

    @Test
    fun aHeadingInsideATableIsNoBookmark() {
        val table = FlowTable(FlowTable.even(1))
        val flow = TextFlow().apply {
            margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
            paragraphs.add(Paragraph(mutableListOf(Run("cell")), table = table, cellStart = true, headingLevel = 1))
            paragraphs.add(Paragraph(mutableListOf(Run("Real")), headingLevel = 1))
        }
        assertEquals(listOf("Real"), FlowHeadings.find(flow, frame(flow)).map { it.title })
    }
}
