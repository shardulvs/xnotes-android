package com.xnotes.core.model

import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class TableRangeTest {

    private fun table() = TableItem.create(Rect(0.0, 0.0, 300.0, 200.0), 3, 2, Rgba(0, 0, 0, 255))

    @Test fun wholeWidthResizeScalesEveryColumn() {
        val t = table()
        t.resizeColumnRange(0, 2, 600.0, atEnd = true)
        assertEquals(600.0, t.rect.w, 1e-9)
        assertEquals(0.0, t.rect.left, 1e-9)
        for (f in t.colFractions) assertEquals(1.0 / 3, f, 1e-9)
    }

    @Test fun rangeResizeKeepsOtherColumnsWidths() {
        val t = table()
        t.resizeColumnRange(1, 1, 250.0, atEnd = true)
        assertEquals(350.0, t.rect.w, 1e-9)
        assertEquals(100.0, t.colX(1), 1e-9)
        assertEquals(250.0, t.colX(2), 1e-9)
        assertEquals(350.0, t.colX(3), 1e-9)
    }

    @Test fun nearEdgeResizeMovesTheTableEdge() {
        val t = table()
        t.resizeColumnRange(0, 0, 50.0, atEnd = false)
        assertEquals(50.0, t.rect.left, 1e-9)
        assertEquals(250.0, t.rect.w, 1e-9)
        assertEquals(100.0, t.colX(1), 1e-9)
    }

    @Test fun rangeNeverShrinksBelowMinCell() {
        val t = table()
        t.resizeRowRange(0, 0, -500.0, atEnd = true)
        assertEquals(TableItem.MIN_CELL, t.rowY(1) - t.rowY(0), 1e-9)
    }

    @Test fun insertAndRemoveInTheMiddle() {
        val t = table()
        t.insertColumn(1)
        assertEquals(4, t.cols)
        assertEquals(300.0, t.rect.w, 1e-9)
        assertEquals(1.0, t.colFractions.sum(), 1e-9)
        t.removeColumns(1..2)
        assertEquals(2, t.cols)
        t.removeColumns(0..1)
        assertEquals(2, t.cols)
    }
}
