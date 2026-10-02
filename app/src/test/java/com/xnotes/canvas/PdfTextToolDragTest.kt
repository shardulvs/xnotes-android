package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.pdf.FakePageText
import com.xnotes.core.pdf.PageText
import com.xnotes.core.pdf.TextPos
import com.xnotes.core.pdf.TextSelection
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text markup tool's drag: from where it presses, a character at a time. */
class PdfTextToolDragTest {

    /** h0 e1 l2 l3 o4 _5 w6 o7 r8 l9 d10 in 6 pt cells from x = 10 (the first l at 22-28), 12 pt tall from y = 20. */
    private val text = FakePageText().line("hello world", 10f, 20f).build()

    private val pending = ArrayList<() -> Unit>()
    private var held: PageText? = text

    private val source = object : PdfTextSource {
        override fun peek(page: Int) = if (page == 0) held else null
        override fun prefetch(page: Int) {}
        override fun request(page: Int, onReady: (PageText?) -> Unit) {
            pending += { onReady(if (page == 0) text.also { held = it } else null) }
        }
    }

    // At 72 dpi a page-space px is a point.
    private val state = CanvasState(Document(mutableListOf(Page(300.0, 200.0, pdfPage = 0)), dpi = 72), FakeSurfaceFactory(), Palette.DEFAULT).apply {
        viewportW = 800
        viewportH = 1000
        relayout()
    }

    private val ctrl = PdfTextController(state, source, onViewChanged = {}, requestRender = {})
    private val marked = ArrayList<TextSelection>()
    private var settled = 0

    init {
        ctrl.onMarked = { marked += it }
        ctrl.onSettled = { settled++ }
    }

    /** The viewport point over page point ([x], [y]). */
    private fun at(x: Double, y: Double): Pt = state.contentToViewport(state.fromPageSpace(0, Pt(x, y)))

    @Test
    fun aMarkingDragHandsOverTheCharactersItCrossed() {
        ctrl.beginToolDrag(0, Pt(23.0, 26.0), mark = true) // short of the first l's middle: before it
        assertTrue(ctrl.marking)
        ctrl.dragTo(at(36.0, 26.0)) // short of the o's middle
        assertEquals(TextSelection(TextPos(0, 2), TextPos(0, 4)), ctrl.selection)
        ctrl.release()
        assertEquals(listOf(TextSelection(TextPos(0, 2), TextPos(0, 4))), marked)
        assertEquals(0, settled)
        assertTrue(ctrl.marking) // drawn as the mark until the marks are made
        ctrl.clear()
        assertFalse(ctrl.marking)
    }

    @Test
    fun aBackwardDragMarksTheSameWay() {
        ctrl.beginToolDrag(0, Pt(36.0, 26.0), mark = true)
        ctrl.dragTo(at(23.0, 26.0))
        ctrl.release()
        assertEquals(listOf(TextSelection(TextPos(0, 2), TextPos(0, 4))), marked)
    }

    @Test
    fun aTapMarksNothing() {
        ctrl.beginToolDrag(0, Pt(23.0, 26.0), mark = true)
        ctrl.release()
        assertTrue(marked.isEmpty())
        assertNull(ctrl.selection)
        assertFalse(ctrl.marking)
    }

    @Test
    fun aPressAwayFromTheTextSelectsNothing() {
        ctrl.beginToolDrag(0, Pt(150.0, 150.0), mark = true)
        ctrl.dragTo(at(40.0, 26.0))
        ctrl.release()
        assertTrue(marked.isEmpty())
        assertNull(ctrl.selection)
    }

    @Test
    fun selectModeKeepsTheSelectionAndShowsItsMenu() {
        ctrl.beginToolDrag(0, Pt(11.0, 26.0), mark = false)
        ctrl.dragTo(at(75.0, 26.0))
        ctrl.release()
        assertEquals(TextSelection(TextPos(0, 0), TextPos(0, 11)), ctrl.selection)
        assertEquals(1, settled)
        assertTrue(marked.isEmpty())
    }

    @Test
    fun textStillBeingReadStartsTheDragWhereThePointerIsNow() {
        held = null
        ctrl.beginToolDrag(0, Pt(11.0, 26.0), mark = true)
        ctrl.dragTo(at(40.0, 26.0))
        assertNull(ctrl.selection)
        pending.forEach { it() }
        assertEquals(TextSelection(TextPos(0, 0), TextPos(0, 5)), ctrl.selection)
        ctrl.release()
        assertEquals(1, marked.size)
    }

    @Test
    fun aSecondPointerDropsAMarkButKeepsASelection() {
        ctrl.beginToolDrag(0, Pt(11.0, 26.0), mark = true)
        ctrl.dragTo(at(40.0, 26.0))
        ctrl.interrupt()
        assertNull(ctrl.selection)
        assertTrue(marked.isEmpty())
        ctrl.beginToolDrag(0, Pt(11.0, 26.0), mark = false)
        ctrl.dragTo(at(40.0, 26.0))
        ctrl.interrupt()
        assertEquals(TextSelection(TextPos(0, 0), TextPos(0, 5)), ctrl.selection)
    }
}
