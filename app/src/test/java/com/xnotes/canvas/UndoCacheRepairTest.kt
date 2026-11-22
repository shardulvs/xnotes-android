package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two things an undo/redo must not do to the page caches.
 *
 * It must not **drop** them: the old path called [CanvasState.invalidateAllCaches], which blanked
 * every visible page to bare paper for a frame (the undo/redo flicker). Asserted at the cache-map
 * level via [CanvasState.inkLayer] — mirrors [SelectionCacheRepairTest].
 *
 * And it must not **rasterize them on the calling thread**: a full-page repaint per cached page is
 * what timed out input on a dense note. [CanvasState.repairInkRegions] avoids it by repainting only
 * the regions a command named; [CanvasState.refreshAllInk], the fallback for a command that can't
 * name any, avoids it by handing the pages to the cache thread and keeping the pre-edit surfaces on
 * screen until the rebuilds land.
 */
class UndoCacheRepairTest {

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
            if (background) paintPageBackground = { _, _, _, _ -> } // a non-null painter (stands in for a PDF)
        }
    }

    @Test fun undoFallbackRebuildsOffThreadAndBlitsThePreEditSurfaceMeanwhile() {
        val st = state()
        val page = st.document.pages[0]
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) } // hold the build, as a real cache thread would for a frame or two

        val before = st.cacheFor(page).bands[0]!!.surface // warm the ink cache, as a draw frame would
        val painter = (before as FakeRasterSurface).painter
        painter.ribbonRuns.clear()

        // Mimic an undo that removed the last-added stroke, then run the whole-page fallback.
        page.items.removeAt(page.items.size - 1)
        st.refreshAllInk()

        assertEquals("the fallback must not rasterize on the thread the tap arrived on", 0, painter.ribbonRuns.size)
        assertEquals("undo must not drop the ink cache (the blank-frame flicker)", 1, st.inkLayer(st.document.pages[0])!!.built())
        assertSame(
            "the pre-edit surface keeps being blitted until the rebuild lands",
            before,
            st.cacheForOrSchedule(page)?.bands?.get(0)?.surface,
        )
        assertEquals("and asking again while it is in flight must not queue it twice", 1, jobs.size)

        jobs.removeFirst().invoke()

        val after = st.cacheForOrSchedule(page)?.bands?.get(0)?.surface
        assertNotSame("the rebuilt surface replaces it once ready", before, after)
        assertTrue("and the one it replaced gives its pixels back", (before as FakeRasterSurface).recycled)
        assertEquals(
            "and it holds the post-undo page: one stroke left, one ribbon painted",
            1,
            (after as FakeRasterSurface).painter.ribbonRuns.size,
        )
    }

    @Test fun undoLeavesBackgroundCacheIntact() {
        val st = state(background = true)
        val page = st.document.pages[0]
        st.cacheFor(page)
        st.backgroundFor(page)
        assertEquals(1, st.inkLayer(st.document.pages[0])!!.built())
        assertEquals(1, st.backgroundLayer(st.document.pages[0])!!.built())

        page.items.removeAt(page.items.size - 1)
        st.refreshAllInk()

        // The expensive PDF/template background must survive undo — invalidateAllCaches() used to
        // flush it, which is what flickered the PDF layer on every undo/redo.
        assertEquals("undo must not flush the background/PDF cache", 1, st.backgroundLayer(st.document.pages[0])!!.built())
        assertEquals(1, st.inkLayer(st.document.pages[0])!!.built())
    }

    @Test fun regionRepairRepaintsOnlyWhatTheRegionCovers() {
        val st = state()
        val page = st.document.pages[0]
        val painter = (st.cacheFor(page).bands[0]!!.surface as FakeRasterSurface).painter

        // A command that named its region (an undone stroke at 20,20) must not cost the whole page:
        // the dot at 120,120 is nowhere near it and stays as it was rasterized.
        painter.ribbonRuns.clear()
        st.repairInkRegions(listOf(page to Rect(10.0, 10.0, 20.0, 20.0)))
        assertEquals("only the strokes overlapping the repaired region repaint", 1, painter.ribbonRuns.size)
    }

    @Test fun regionRepairKeepsTheCacheInPlace() {
        val st = state()
        val page = st.document.pages[0]
        st.cacheFor(page)

        page.items.removeAt(page.items.size - 1)
        st.repairInkRegions(listOf(page to Rect(100.0, 100.0, 40.0, 40.0)))

        assertEquals(
            "a region repair keeps the surface too, or undo flickers again",
            1, st.inkLayer(st.document.pages[0])!!.built(),
        )
    }
}
