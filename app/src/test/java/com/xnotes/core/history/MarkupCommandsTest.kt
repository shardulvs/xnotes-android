package com.xnotes.core.history

import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pdf.TextQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupCommandsTest {

    private fun markup(text: String, type: MarkupType = MarkupType.HIGHLIGHT) = TextMarkup(
        TextMarkup.newId(), type, Rgba(0, 230, 118), 0.5,
        listOf(TextQuad(10f, 20f, 60f, 32f, 0)), text, null, 1L, 1L,
    )

    @Test
    fun addPutsMarkupsOnTopAndUndoTakesThemOff() {
        val page = Page(100.0, 100.0)
        val a = markup("a")
        page.markups = listOf(a)
        val b = markup("b")
        val c = markup("c")
        val cmd = AddMarkups(page, listOf(b, c))
        cmd.redo()
        assertEquals(listOf(a, b, c), page.markups)
        cmd.redo()
        assertEquals(3, page.markups.size)
        cmd.undo()
        assertEquals(listOf(a), page.markups)
        assertTrue(cmd.touched { null }!!.isEmpty())
        assertEquals(listOf(page to b, page to c), cmd.touchedMarkups())
    }

    @Test
    fun removeAndUndoKeepTheStackOrder() {
        val page = Page(100.0, 100.0)
        val (a, b, c) = listOf(markup("a"), markup("b"), markup("c"))
        page.markups = listOf(a, b, c)
        val cmd = RemoveMarkup(page, b)
        cmd.redo()
        assertEquals(listOf(a, c), page.markups)
        cmd.undo()
        assertEquals(listOf(a, b, c), page.markups)
        cmd.undo()
        assertEquals(3, page.markups.size)
    }

    @Test
    fun markupsErasedOneByOneComeBackInPlace() {
        val page = Page(100.0, 100.0)
        val (a, b, c, d) = listOf(markup("a"), markup("b"), markup("c"), markup("d"))
        page.markups = listOf(a, b, c, d)
        // As the eraser builds them: each made and applied before the next.
        val erased = listOf(c, a, d).map { RemoveMarkup(page, it).also { cmd -> cmd.redo() } }
        assertEquals(listOf(b), page.markups)
        val step = CompositeCommand(erased)
        step.undo()
        assertEquals(listOf(a, b, c, d), page.markups)
        step.redo()
        assertEquals(listOf(b), page.markups)
        assertEquals(listOf(page to c, page to a, page to d), step.touchedMarkups())
    }

    @Test
    fun replaceSwapsInPlace() {
        val page = Page(100.0, 100.0)
        val (a, b) = listOf(markup("a"), markup("b"))
        page.markups = listOf(a, b)
        val noted = a.copy(type = MarkupType.SQUIGGLY, note = "see eq. 4", modified = 2L)
        val cmd = ReplaceMarkup(page, a, noted)
        cmd.redo()
        assertSame(noted, page.markups[0])
        assertSame(b, page.markups[1])
        assertEquals(a.id, noted.id)
        assertEquals(a.created, noted.created)
        cmd.undo()
        assertSame(a, page.markups[0])
        assertNull(page.markups[0].note)
        assertEquals(listOf(page to a, page to noted), cmd.touchedMarkups())
    }

    @Test
    fun aMarkupAcrossPagesIsOneStep() {
        val p0 = Page(100.0, 100.0)
        val p1 = Page(100.0, 100.0)
        val (a, b) = listOf(markup("end of one"), markup("start of the next"))
        val history = History()
        val step = CompositeCommand(listOf(AddMarkups(p0, listOf(a)), AddMarkups(p1, listOf(b))))
        step.redo()
        history.push(step)
        assertEquals(listOf(p0 to a, p1 to b), step.touchedMarkups())
        assertTrue(step.touched { null }!!.isEmpty())
        history.undo()
        assertTrue(p0.markups.isEmpty() && p1.markups.isEmpty())
        history.redo()
        assertEquals(listOf(a), p0.markups)
        assertEquals(listOf(b), p1.markups)
    }

    @Test
    fun everyMarkupGetsItsOwnId() {
        assertNotEquals(markup("a").id, markup("a").id)
    }
}
