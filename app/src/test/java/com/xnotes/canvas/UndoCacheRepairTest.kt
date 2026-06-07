package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the undo/redo flicker fix: applying an undo/redo must repair the page caches **in place**
 * (keeping the surfaces) rather than dropping them for an async rebuild, which blanked every visible
 * page to bare paper for a frame. The old undo path called [CanvasState.invalidateAllCaches], which
 * drops both layers to 0; [CanvasState.repairAllInkInPlace] must leave them standing. Asserted at the
 * cache-map level via [CanvasState.cacheSnapshot] — mirrors [SelectionCacheRepairTest].
 */
class UndoCacheRepairTest {

    private fun dot(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    private fun state(background: Boolean = false): CanvasState {
        val page = Page(200.0, 200.0, mutableListOf(dot(20.0, 20.0), dot(120.0, 120.0)))
        val doc = Document(mutableListOf(page))
        return CanvasState(doc, FakeSurfaceFactory(), Palette.forAppearance("dark", Rgba(0, 230, 118))).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
            if (background) paintPageBackground = { _, _, _, _ -> } // a non-null painter (stands in for a PDF)
        }
    }

    @Test fun undoRepairsInkCacheInPlace() {
        val st = state()
        val page = st.document.pages[0]
        st.cacheFor(page) // warm the ink cache, as a draw frame would
        assertEquals(1, st.cacheSnapshot().inkPages)

        // Mimic an undo that removed the last-added stroke, then run the undo repair path.
        page.items.removeAt(page.items.size - 1)
        st.repairAllInkInPlace()

        assertEquals(
            "undo must repair the ink cache in place, not drop it (the blank-frame flicker)",
            1, st.cacheSnapshot().inkPages,
        )
    }

    @Test fun undoLeavesBackgroundCacheIntact() {
        val st = state(background = true)
        val page = st.document.pages[0]
        st.cacheFor(page)
        st.backgroundFor(page)
        assertEquals(1, st.cacheSnapshot().inkPages)
        assertEquals(1, st.cacheSnapshot().bgPages)

        page.items.removeAt(page.items.size - 1)
        st.repairAllInkInPlace()

        // The expensive PDF/template background must survive undo — invalidateAllCaches() used to
        // flush it, which is what flickered the PDF layer on every undo/redo.
        assertEquals("undo must not flush the background/PDF cache", 1, st.cacheSnapshot().bgPages)
        assertEquals(1, st.cacheSnapshot().inkPages)
    }
}
