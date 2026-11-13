package com.xnotes.core.text

import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowMergeTest {

    private fun flowOf(vararg texts: String) = TextFlow().apply {
        texts.forEach { paragraphs.add(Paragraph(mutableListOf(Run(it)))) }
    }

    @Test fun theTextAfterStartsOnItsNotesFirstPage() {
        val dst = flowOf("a")
        val src = flowOf("b", "c")
        FlowMerge.append(dst, src, page = 4)
        assertEquals(listOf("a", "b", "c"), dst.paragraphs.map { it.plainText() })
        assertTrue(dst.paragraphs[1].breakBefore)
        assertEquals(4, dst.paragraphs[1].breakToPage)
        assertFalse(dst.paragraphs[2].breakBefore)
    }

    @Test fun nothingComesFromAnEmptyNoteAndAnEmptyOneTakesTheTextWhole() {
        val dst = flowOf("a")
        FlowMerge.append(dst, TextFlow(), page = 2)
        assertEquals(1, dst.paragraphs.size)
        val empty = TextFlow().apply { paragraphs.add(Paragraph()) }
        FlowMerge.append(empty, flowOf("b"), page = 3)
        assertEquals(listOf("b"), empty.paragraphs.map { it.plainText() })
        assertEquals(3, empty.paragraphs[0].breakToPage)
    }

    @Test fun textKeepsItsOwnBaseWhereTheNotesDiffer() {
        val dst = flowOf("a").apply { defaultSizePt = 12.0 }
        val src = flowOf("b").apply {
            defaultSizePt = 16.0
            defaultFace = FontFace("serif")
            defaultColor = Rgba(10, 20, 30, 255)
            paragraphs.add(Paragraph(mutableListOf(Run("h", CharStyle(sizePt = 30.0))), headingLevel = 1))
        }
        FlowMerge.append(dst, src, page = 1)
        val body = dst.paragraphs[1].runs.single().style
        assertEquals(16.0, body.sizePt)
        assertEquals(FontFace("serif"), body.face)
        assertEquals(Rgba(10, 20, 30, 255), body.color)
        // A heading's size comes from its level, and a size it set itself stays.
        assertEquals(30.0, dst.paragraphs[2].runs.single().style.sizePt)
    }

    @Test fun matchingBasesWriteNothing() {
        val dst = flowOf("a")
        FlowMerge.append(dst, flowOf("b"), page = 1)
        assertNull(dst.paragraphs[1].runs.single().style.sizePt)
        assertNull(dst.paragraphs[1].runs.single().style.face)
    }

    @Test fun textOpeningWithATableGetsItsBreakOnALineOfItsOwn() {
        val table = FlowTable(listOf(1.0))
        val cell = Paragraph(mutableListOf(Run("x")), table = table, cellStart = true)
        val src = TextFlow().apply { paragraphs.add(cell) }
        val dst = flowOf("a")
        FlowMerge.append(dst, src, page = 2)
        assertEquals(3, dst.paragraphs.size)
        assertEquals(2, dst.paragraphs[1].breakToPage)
        assertSame(cell, dst.paragraphs[2])
        assertFalse(cell.breakBefore)
    }
}
