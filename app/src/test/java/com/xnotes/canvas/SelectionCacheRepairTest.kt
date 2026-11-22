package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the select-tool flicker fix: a selection edit must repair the page's ink cache
 * **in place** (the eraser's path) rather than dropping it for an async rebuild, and a
 * delete must never flush the (PDF/template) background cache. Both used to blank the whole
 * layer for a frame. We assert it at the cache-map level through [CanvasState.inkLayer]:
 * dropping a cache makes its page count fall to 0; an in-place repair leaves it standing.
 */
class SelectionCacheRepairTest {

    private fun dot(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    private fun state(background: Boolean = false): CanvasState {
        val page = Page(200.0, 200.0, mutableListOf(dot(20.0, 20.0), dot(120.0, 120.0)))
        if (background) page.pdfPage = 0 // a real PDF-backed page, so a background cache is built and kept
        val doc = Document(mutableListOf(page))
        return CanvasState(doc, FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
            if (background) paintPageBackground = { _, _, _, _ -> } // a non-null background painter (stands in for a PDF)
        }
    }

    private fun controller(st: CanvasState) =
        InteractionController(st, History(), FakeTextMeasurer(), requestRender = {})

    @Test fun selectingThenDismissingKeepsInkCacheLive() {
        val st = state()
        val ctrl = controller(st)
        st.cacheFor(st.document.pages[0]) // warm the ink cache, as a draw frame would
        assertEquals(1, st.inkLayer(st.document.pages[0])!!.built())

        ctrl.selectAll() // lifts every item out of the cache
        assertEquals(
            "selecting must repair in place, not drop the page's ink cache",
            1, st.inkLayer(st.document.pages[0])!!.built(),
        )

        ctrl.escape() // dismiss the selection (clearSelection)
        assertEquals(
            "dismissing must repair in place, not drop the page's ink cache",
            1, st.inkLayer(st.document.pages[0])!!.built(),
        )
    }

    @Test fun deletingSelectionLeavesBackgroundCacheIntact() {
        val st = state(background = true)
        val ctrl = controller(st)
        val page = st.document.pages[0]
        st.cacheFor(page)
        st.backgroundFor(page)
        assertEquals(1, st.inkLayer(st.document.pages[0])!!.built())
        assertEquals(1, st.backgroundLayer(st.document.pages[0])!!.built())

        ctrl.selectAll()
        ctrl.deleteSelection()

        // The expensive PDF/template background must survive a delete — it used to be flushed by
        // invalidateAllCaches(), which is precisely what made the PDF layer flicker on delete/cut.
        assertEquals("delete must not flush the background/PDF cache", 1, st.backgroundLayer(st.document.pages[0])!!.built())
        // The ink layer stays cached too (repaired in place), never dropped to bare paper.
        assertEquals(1, st.inkLayer(st.document.pages[0])!!.built())
    }
}
