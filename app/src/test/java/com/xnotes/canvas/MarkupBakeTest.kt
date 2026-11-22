package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pdf.TextQuad
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A markup edit paints its page's background afresh, and the overlay shows new marks until that lands. */
class MarkupBakeTest {

    private val factory = FakeSurfaceFactory()
    private val queued = ArrayList<() -> Unit>()

    /** How many times the background painter ran. */
    private var paints = 0

    private fun runPending() {
        while (queued.isNotEmpty()) {
            val work = queued.toList()
            queued.clear()
            work.forEach { it() }
        }
    }

    private fun state(page: Page, viewW: Int = 800) = CanvasState(Document(mutableListOf(page)), factory, Palette.DEFAULT).apply {
        viewportW = viewW
        viewportH = 1000
        relayout()
        runAsync = { queued += it }
        paintPageBackground = { _, _, _, _ -> paints++ }
    }

    private fun pdfPage() = Page(200.0, 200.0, pdfPage = 0)

    private fun markup() = TextMarkup(
        TextMarkup.newId(), MarkupType.HIGHLIGHT, Rgba(0, 230, 118), 0.5,
        listOf(TextQuad(10f, 20f, 40f, 26f, 0)), "x", null, 0L, 0L,
    )

    @Test
    fun theOldLayerShowsUntilTheNewOneIsPainted() {
        val page = pdfPage()
        val st = state(page)
        val old = st.backgroundFor(page)!!.bands[0]
        val m = markup()
        page.markups = listOf(m)
        st.rebakeBackground(page, listOf(m))
        assertSame(old, st.backgroundForOrSchedule(page)!!.bands[0])
        assertEquals(listOf(m), st.unbakedMarkups(page))
        runPending()
        val fresh = st.backgroundForOrSchedule(page)!!.bands[0]
        assertNotSame(old, fresh)
        assertTrue(st.unbakedMarkups(page).isEmpty())
    }

    @Test
    fun aWholeLayerBuildStartedBeforeTheEditIsThrownAway() {
        val page = pdfPage()
        val st = state(page)
        st.backgroundForOrSchedule(page) // queued, reading the page as it was
        val m = markup()
        page.markups = listOf(m)
        st.rebakeBackground(page, listOf(m))
        runPending()
        assertEquals(listOf(m), st.unbakedMarkups(page)) // nothing on screen holds it yet
        st.backgroundForOrSchedule(page) // the frame after: built anew, after the edit
        runPending()
        assertNotNull(st.backgroundForOrSchedule(page))
        assertTrue(st.unbakedMarkups(page).isEmpty())
    }

    @Test
    fun editsDuringAPaintMakeOneMoreAfterIt() {
        val page = pdfPage()
        val st = state(page)
        st.backgroundFor(page)
        val (a, b, c) = listOf(markup(), markup(), markup())
        page.markups = listOf(a)
        st.rebakeBackground(page, listOf(a))
        page.markups = listOf(a, b)
        st.rebakeBackground(page, listOf(b))
        page.markups = listOf(a, b, c)
        st.rebakeBackground(page, listOf(c))
        paints = 0
        runPending()
        assertEquals(2, paints)
        assertTrue(st.unbakedMarkups(page).isEmpty())
    }

    @Test
    fun anUndoneMarkupIsNoLongerShown() {
        val page = pdfPage()
        val st = state(page)
        st.backgroundFor(page)
        val m = markup()
        page.markups = listOf(m)
        st.rebakeBackground(page, listOf(m))
        page.markups = emptyList()
        assertTrue(st.unbakedMarkups(page).isEmpty())
    }

    @Test
    fun aPageGainingItsFirstMarkupGetsABackground() {
        val plain = Page(200.0, 200.0)
        val st = state(plain)
        assertFalse(st.hasPageBackground(plain))
        val m = markup()
        plain.markups = listOf(m)
        assertTrue(st.hasPageBackground(plain))
        st.rebakeBackground(plain, listOf(m))
        assertEquals(listOf(m), st.unbakedMarkups(plain))
        st.backgroundForOrSchedule(plain)
        runPending()
        assertTrue(st.unbakedMarkups(plain).isEmpty())
    }

    @Test
    fun theSharpViewportIsRenderedAgainWithoutLettingGoOfTheOldOne() {
        val page = pdfPage()
        val st = state(page, viewW = 100) // a 200px page on a 100px view: past the cap at zoom 1
        st.backgroundFor(page)
        st.requestSharpViewport()
        runPending()
        val before = st.sharpViewportBlit()!!
        val m = markup()
        page.markups = listOf(m)
        st.rebakeBackground(page, listOf(m))
        assertSame(before.base, st.sharpViewportBlit()?.base) // still shown while the new one renders
        assertEquals(listOf(m), st.unbakedMarkups(page))
        runPending()
        assertNotSame(before.base, st.sharpViewportBlit()?.base)
        assertTrue(st.unbakedMarkups(page).isEmpty())
    }

    @Test
    fun anEditDuringASharpRenderRendersAgainOnceItLands() {
        val page = pdfPage()
        val st = state(page, viewW = 100)
        st.backgroundFor(page)
        st.requestSharpViewport() // under way, reading the page as it was
        val m = markup()
        page.markups = listOf(m)
        st.rebakeBackground(page, listOf(m))
        queued.removeAt(0)() // the first render lands, holding nothing of the edit
        assertNotNull(st.sharpViewportBlit())
        assertEquals(listOf(m), st.unbakedMarkups(page))
        runPending()
        assertTrue(st.unbakedMarkups(page).isEmpty())
    }
}
