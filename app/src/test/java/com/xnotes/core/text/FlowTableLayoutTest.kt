package com.xnotes.core.text

import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table layout against the fake measurer: a char is 7.2 wide and a line 15.6
 * tall at 12pt; pages are margin-less and 200 wide; tables have no padding, so
 * each 2-column cell is exactly 100 wide.
 */
class FlowTableLayoutTest {

    private val layout = FlowLayout(FakeTextMeasurer())
    private val bare = TableStyle(paddingPt = 0.0, lineWidthPt = TableStyle.MIN_LINE_PT)
    private val lw = TableStyle.MIN_LINE_PT * TableStyle.PX_PER_PT

    private fun para(text: String) = Paragraph(if (text.isEmpty()) mutableListOf() else mutableListOf(Run(text)))

    /** "top", a table of [cells] (row-major, '\n' = more paragraphs in a cell), then [end]. */
    private fun flow(cols: Int, cells: List<String>, style: TableStyle = bare, end: String = "end"): TextFlow {
        val table = FlowTable(FlowTable.even(cols), List(cells.size / cols) { 0.0 }, style)
        return TextFlow().apply {
            margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
            paragraphs.add(para("top"))
            for (cell in cells) {
                cell.split('\n').forEachIndexed { i, text ->
                    paragraphs.add(para(text).apply { this.table = table; cellStart = i == 0 })
                }
            }
            paragraphs.add(para(end))
        }
    }

    private fun pages(n: Int, h: Double = 400.0) = List(n) { PageBox(200.0, h) }

    private fun lineOf(frame: FlowFrame, para: Int): PlacedLine =
        frame.lines.first { it.second.paraIndex == para }.second

    @Test
    fun cellsSitSideBySideInTheirColumns() {
        val frame = layout.layout(flow(2, listOf("a", "b", "c", "d")), pages(1), 150)
        val t = frame.pages[0].tables.single()
        assertEquals(listOf(0.0, 100.0, 200.0), t.colXs.toList())
        assertEquals(2, t.rows.size)
        assertEquals(15.6 + lw / 2, t.top, 1e-9)
        assertEquals(t.top, lineOf(frame, 2).top, 1e-9)
        assertEquals(100.0, lineOf(frame, 2).xs[0], 1e-9)
        assertEquals(t.top + 15.6, lineOf(frame, 3).top, 1e-9)
        assertEquals(t.bottom + lw / 2, lineOf(frame, 5).top, 1e-9)
    }

    @Test
    fun aRowTakesItsTallestCell() {
        val frame = layout.layout(flow(2, listOf("a\nmore\nlines", "b", "c", "d")), pages(1), 150)
        val t = frame.pages[0].tables.single()
        assertEquals(3 * 15.6, t.rows[0].bottom - t.rows[0].top, 1e-9)
        assertEquals(t.rows[0].bottom, lineOf(frame, 5).top, 1e-9)
    }

    @Test
    fun cellTextWrapsAtTheColumnWidth() {
        // 20 chars at 7.2 = 144 > 100: wraps after the space.
        val frame = layout.layout(flow(2, listOf("aaaaaaaaa bbbbbbbbbb", "b")), pages(1), 150)
        assertEquals(2, frame.lines.count { it.second.paraIndex == 1 })
    }

    @Test
    fun theHeaderRowIsBold() {
        val frame = layout.layout(flow(2, listOf("a", "b", "c", "d"), bare.copy(headerRow = true)), pages(1), 150)
        assertTrue(lineOf(frame, 1).segs.single().font.bold)
        assertFalse(lineOf(frame, 3).segs.single().font.bold)
    }

    @Test
    fun aRowThatDoesNotFitMovesWhole() {
        // 50 tall pages: "top" + two rows fit, the third row moves.
        val frame = layout.layout(flow(2, listOf("a", "b", "c", "d", "e", "f")), pages(2, 50.0), 150)
        assertEquals(listOf(0, 1), frame.pages[0].tables.single().rows.map { it.row })
        val second = frame.pages[1].tables.single()
        assertEquals(listOf(2), second.rows.map { it.row })
        assertEquals(0.0, second.top, 1e-9)
        assertEquals(0.0, lineOf(frame, 5).top, 1e-9)
    }

    @Test
    fun aTableOverflowsOntoPagesOfTheAddedSize() {
        val f = flow(2, List(12) { "x" })
        assertTrue(layout.layout(f, pages(1, 50.0), 150).extraPagesNeeded > 1)
        assertEquals(1, layout.layout(f, pages(1, 50.0), 150, addedPage = PageBox(200.0, 400.0)).extraPagesNeeded)
    }

    @Test
    fun aRowTallerThanAPageSplitsByLines() {
        val frame = layout.layout(flow(1, listOf("1\n2\n3\n4\n5")), pages(3, 50.0), 150)
        assertTrue(frame.pages[0].tables.isEmpty())
        assertEquals(3, frame.pages[1].lines.size)
        assertEquals(50.0, frame.pages[1].tables.single().bottom, 1e-9)
        val rest = frame.pages[2]
        assertEquals(listOf(0), rest.tables.single().rows.map { it.row })
        assertEquals(31.2, rest.tables.single().bottom, 1e-9)
        assertEquals(3, rest.lines.size)
    }

    @Test
    fun tapsLandInTheCellUnderThem() {
        val frame = layout.layout(flow(2, listOf("a", "b", "c", "d")), pages(1), 150)
        val t = frame.pages[0].tables.single()
        val hitB = frame.hitTest(0, Pt(150.0, t.top + 5.0)) as FlowHit.Caret
        assertEquals(2, hitB.pos.para)
        val hitC = frame.hitTest(0, Pt(3.0, t.bottom - 5.0)) as FlowHit.Caret
        assertEquals(FlowPos(3, 0), hitC.pos)
    }

    @Test
    fun aLongPressHoldsTextOnlyOnGlyphsAwayFromTheRules() {
        val frame = layout.layout(flow(2, listOf("abcdef", "b", "c", "d")), pages(1), 150)
        val t = frame.pages[0].tables.single()
        val mid = t.rows[0].top + 7.8
        // On "abcdef" (0..43.2), clear of the rules.
        assertEquals(true, frame.tablePressAt(0, Pt(20.0, mid), 3.0)!!.second)
        // Right of the text in the same cell: empty space holds the table.
        assertEquals(false, frame.tablePressAt(0, Pt(70.0, mid), 3.0)!!.second)
        // On the column rule, and just outside the table's top edge.
        assertEquals(false, frame.tablePressAt(0, Pt(101.0, mid), 3.0)!!.second)
        assertEquals(false, frame.tablePressAt(0, Pt(20.0, t.top - 2.0), 3.0)!!.second)
        // Clear of the table: no hold at all.
        assertEquals(null, frame.tablePressAt(0, Pt(20.0, 5.0), 3.0))
    }

    @Test
    fun verticalMovesWalkCellsThenLeaveTheTable() {
        val frame = layout.layout(flow(2, listOf("a", "b", "c", "d"), end = "0123456789012345678901234"), pages(1), 150)
        assertEquals(3, frame.moveVertical(FlowPos(1, 1), 1)!!.para)
        assertEquals(5, frame.moveVertical(FlowPos(3, 0), 1)!!.para)
        assertEquals(1, frame.moveVertical(FlowPos(0, 1), 1)!!.para)
        // From x = 158.4 below the table: the bottom cell of the right column.
        assertEquals(4, frame.moveVertical(FlowPos(5, 22), -1)!!.para)
    }

    @Test
    fun aCellSelectionHighlightsWholeCells() {
        val frame = layout.layout(flow(2, listOf("a", "b", "c", "d")), pages(1), 150)
        val rects = frame.selectionRects(FlowRange(FlowPos(2, 0), FlowPos(3, 1))).map { it.second }
        val t = frame.pages[0].tables.single()
        assertEquals(2, rects.size)
        for ((i, rect) in rects.withIndex()) {
            assertEquals(0.0, rect.left, 1e-9)
            assertEquals(200.0, rect.w, 1e-9)
            assertEquals(t.rows[i].top, rect.top, 1e-9)
            assertEquals(15.6, rect.h, 1e-9)
        }
    }

    @Test
    fun theWandWidensTheLongTextColumn() {
        val long = List(12) { "wordsword" }.joinToString(" ")
        val f = flow(2, listOf("Name", long, "Ada", long))
        val table = f.paragraphs[1].table!!
        val evenHeight = layout.layout(f, pages(1), 150).pages[0].tables.single().let { it.bottom - it.top }
        table.widths = layout.fitColumns(f, table, 200.0)
        assertEquals(1.0, table.widths.sum(), 1e-9)
        assertTrue(table.widths[1] > 0.7)
        // The name column still fits its longest word ("Name" = 4 x 7.2).
        assertTrue(table.widths[0] * 200.0 >= 28.8 - 1e-9)
        val fitted = layout.layout(f, pages(1), 150).pages[0].tables.single().let { it.bottom - it.top }
        assertTrue(fitted < evenHeight)
    }

    @Test
    fun theWandKeepsNaturalProportionsWhenEverythingFits() {
        val f = flow(2, listOf("abcd", "abcdefghijkl"))
        val widths = layout.fitColumns(f, f.paragraphs[1].table!!, 200.0)
        assertEquals(0.25, widths[0], 1e-9)
    }

    @Test
    fun aNarrowedTableKeepsItsColumnSharesOfTheSmallerWidth() {
        val f = flow(2, listOf("a", "b", "c", "d"))
        f.paragraphs[1].table!!.width = 0.5
        val frame = layout.layout(f, pages(1), 150)
        val t = frame.pages[0].tables.single()
        assertEquals(listOf(0.0, 50.0, 100.0), t.colXs.toList())
        assertEquals(50.0, lineOf(frame, 2).xs[0], 1e-9)
    }

    @Test
    fun aNarrowedTableWrapsItsCellsSooner() {
        val text = "aaaa bbbb cccc dddd"
        val full = layout.layout(flow(2, listOf(text, "b")), pages(1), 150)
        val narrow = flow(2, listOf(text, "b")).also { it.paragraphs[1].table!!.width = 0.5 }
        val t = layout.layout(narrow, pages(1), 150).pages[0].tables.single()
        assertTrue(t.bottom - t.top > full.pages[0].tables.single().let { it.bottom - it.top })
    }

    @Test
    fun theRightEdgeTravelsWithTheFinger() {
        // A half-width table in a 600 wide text column, so 300 wide, dragged 60 left.
        assertEquals(240.0 / 600.0, FlowTable.widthAfterDrag(0.5, 300.0, -60.0), 1e-9)
        assertEquals(360.0 / 600.0, FlowTable.widthAfterDrag(0.5, 300.0, 60.0), 1e-9)
    }

    @Test
    fun everyStepOfADragTakesTheSameWidthOffTheEdge() {
        // The travel is cumulative from the grab, so the width it divides by has to be too:
        // reading the shrinking table instead made each step overshoot the one before.
        for (n in 1..5) {
            val w = FlowTable.widthAfterDrag(0.5, 300.0, -30.0 * n) * 600.0
            assertEquals(300.0 - 30.0 * n, w, 1e-9)
        }
    }

    @Test
    fun aDragNeverLeavesTheSupportedWidths() {
        assertEquals(FlowTable.MIN_WIDTH, FlowTable.widthAfterDrag(0.5, 300.0, -1000.0), 1e-9)
        assertEquals(FlowTable.FULL_WIDTH, FlowTable.widthAfterDrag(0.5, 300.0, 1000.0), 1e-9)
        assertEquals(0.5, FlowTable.widthAfterDrag(0.5, 0.0, -60.0), 1e-9)
    }

    @Test
    fun normalizeClampsAnOutOfRangeTableWidth() {
        val f = flow(2, listOf("a", "b"))
        f.paragraphs[1].table!!.width = 0.0
        assertTrue(normalizeTables(f))
        assertEquals(FlowTable.MIN_WIDTH, f.paragraphs[1].table!!.width, 1e-9)
    }

    @Test
    fun paintsFillsAndRulesBeforeText() {
        val style = bare.copy(headerRow = true, banded = true)
        val frame = layout.layout(flow(2, listOf("a", "b", "c", "d", "e", "f"), style), pages(1), 150)
        val r = FakeRenderer()
        FlowPainter.paintPage(r, frame, 0, Rect(0.0, 0.0, 200.0, 400.0))
        val firstText = r.ops.indexOfFirst { it.startsWith("drawTextRun") }
        // Header + one band, then 4 horizontal rules and 3 verticals of 3 row segments each.
        assertEquals(2 + 4 + 9, r.ops.subList(0, firstText).count { it == "fillRect" })
    }
}
