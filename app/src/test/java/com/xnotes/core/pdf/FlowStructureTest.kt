package com.xnotes.core.pdf

import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TableStyle
import com.xnotes.core.text.TextFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowStructureTest {

    private fun tree(vararg paras: Paragraph): FlowStructure.Tree =
        FlowStructure.build(TextFlow().apply { paragraphs.addAll(paras) })

    private fun para(text: String = "x", list: ListKind = ListKind.NONE, indent: Int = 0, heading: Int = 0, code: String? = null) =
        Paragraph(mutableListOf(Run(text)), list = list, indent = indent, codeLang = code, headingLevel = heading)

    /** The subtree as nested type names, e.g. "L(LI(Lbl LBody))". */
    private fun shape(n: StructNode): String =
        if (n.kids.isEmpty()) n.type else n.type + n.kids.joinToString(" ", "(", ")") { shape(it) }

    @Test
    fun headingsAndParagraphsAreBlocksOfTheirOwn() {
        val t = tree(para(heading = 1), para(), para(heading = 3), para(heading = 9))
        assertEquals(listOf("H1", "P", "H3", "H6"), t.blocks.map { it.type })
        assertSame(t.blocks[1], t.textOf(1))
        assertSame(t.blocks[1], t.labelOf(1))
    }

    @Test
    fun listsNestByIndentInsideTheItemAbove() {
        val t = tree(
            para(list = ListKind.BULLET),
            para(list = ListKind.ORDERED, indent = 1),
            para(list = ListKind.ORDERED, indent = 1),
            para(list = ListKind.BULLET),
        )
        assertEquals(1, t.blocks.size)
        val l = t.blocks[0]
        assertEquals("L(LI(Lbl LBody(L(LI(Lbl LBody) LI(Lbl LBody)))) LI(Lbl LBody))", shape(l))
        assertEquals("Disc", l.numbering)
        val inner = l.kids[0].kids[1].kids[0]
        assertEquals("Decimal", inner.numbering)
        assertSame(inner.kids[1].kids[1], t.textOf(2))
        assertSame(inner.kids[1].kids[0], t.labelOf(2))
    }

    @Test
    fun anotherKindAtTheSameIndentStartsAnotherList() {
        val t = tree(para(list = ListKind.BULLET), para(list = ListKind.CHECK), para(), para(list = ListKind.CHECK))
        assertEquals(listOf("L", "L", "P", "L"), t.blocks.map { it.type })
        assertEquals(listOf("Disc", "None", null, "None"), t.blocks.map { it.numbering })
    }

    @Test
    fun aListStartingIndentedStillOpensAtTheTop() {
        val t = tree(para(list = ListKind.BULLET, indent = 2), para(list = ListKind.BULLET))
        assertEquals(listOf("L", "L"), t.blocks.map { it.type })
    }

    @Test
    fun codeLinesShareOneCodeElement() {
        val t = tree(para(code = "kotlin"), para("", code = "kotlin"), para(code = ""), para())
        assertEquals(listOf("P", "P"), t.blocks.map { it.type })
        assertEquals("P(Code)", shape(t.blocks[0]))
        val code = t.blocks[0].kids[0]
        for (i in 0..2) assertSame(code, t.textOf(i))
    }

    @Test
    fun aTableIsAFullGridWithHeaderCellsScopedToColumns() {
        val table = FlowTable(FlowTable.even(2), style = TableStyle(headerRow = true))
        fun cell(text: String, start: Boolean = true) = Paragraph(mutableListOf(Run(text)), table = table, cellStart = start)
        // Three cells in two columns: the second row has one cell missing.
        val t = tree(cell("a"), cell("b"), cell("b2", start = false), cell("c"), para())
        val tab = t.blocks[0]
        assertEquals("Table(TR(TH(P) TH(P P)) TR(TD(P) TD))", shape(tab))
        assertEquals(listOf("Column", "Column"), tab.kids[0].kids.map { it.scope })
        assertNull(tab.kids[1].kids[0].scope)
        assertSame(tab.kids[0].kids[1].kids[1], t.textOf(2))
        assertEquals("P", t.blocks[1].type)
    }

    @Test
    fun pruningKeepsTheGridOfATableWithContent() {
        val table = FlowTable(FlowTable.even(2))
        fun cell(text: String) = Paragraph(mutableListOf(Run(text)), table = table, cellStart = true)
        val t = tree(cell("a"), cell(""), para(), para())
        val shown = setOf(t.textOf(0), t.textOf(3))
        val root = StructNode("Document").apply { kids.addAll(t.blocks) }
        assertTrue(root.prune { it in shown })
        assertEquals("Document(Table(TR(TD(P) TD)) P)", shape(root))
    }

    @Test
    fun pruningDropsATableWithNothingShown() {
        val table = FlowTable(FlowTable.even(2))
        fun cell(text: String) = Paragraph(mutableListOf(Run(text)), table = table, cellStart = true)
        val t = tree(cell("a"), cell("b"))
        val root = StructNode("Document").apply { kids.addAll(t.blocks) }
        assertFalse(root.prune { false })
        assertTrue(root.kids.isEmpty())
    }
}
