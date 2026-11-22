package com.xnotes.canvas

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ribbon geometry is the second biggest thing a dense note holds (~30 bytes per sample, tens of MB
 * across a long document) and nothing used to drop it: a page painted once kept its ribbons for the
 * session however far it scrolled away. It is now bound to the page-cache band.
 */
class GeometryEvictionTest {

    private fun ink(x: Double, y: Double): Stroke =
        Stroke(
            Tool.PEN,
            ToolDefaults.configFor(Tool.PEN),
            listOf(Sample(x, y, 1.0), Sample(x + 10, y + 10, 1.0), Sample(x + 20, y, 1.0)),
        )

    private fun state(pageCount: Int = 4): CanvasState {
        val pages = (0 until pageCount).mapTo(mutableListOf()) {
            Page(200.0, 200.0, mutableListOf(ink(20.0, 20.0), ink(120.0, 120.0)))
        }
        return CanvasState(Document(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }
    }

    private fun Page.anyGeometry() = items.filterIsInstance<Stroke>().any { it.hasGeometry }

    @Test fun paintingAPageBuildsGeometryAndLeavingTheBandDropsIt() {
        val st = state()
        val kept = st.document.pages[0]
        val gone = st.document.pages[1]
        st.cacheFor(kept)
        st.cacheFor(gone)
        assertTrue(kept.anyGeometry())
        assertTrue(gone.anyGeometry())

        st.releaseGeometryExcept(setOf(kept))
        assertTrue("a page still in the band keeps its ribbons", kept.anyGeometry())
        assertFalse("a page that left the band must give them back", gone.anyGeometry())
    }

    @Test fun releasingGeometryKeepsBoundsWithoutRebuilding() {
        val st = state()
        val page = st.document.pages[0]
        st.cacheFor(page)
        val stroke = page.items[0] as Stroke
        val before = stroke.bounds()

        st.releaseGeometryExcept(emptySet())
        assertFalse(stroke.hasGeometry)

        // The bounds must still answer, and answering must not silently rebuild the ribbon we just
        // freed: that is what band selection reads across pages it never painted.
        assertEquals(before, stroke.bounds())
        assertFalse("bounds() must not resurrect the geometry", stroke.hasGeometry)
    }

    @Test fun geometryIsRebuiltOnDemandAfterEviction() {
        val st = state()
        val page = st.document.pages[0]
        st.cacheFor(page)
        val stroke = page.items[0] as Stroke
        val before = stroke.geometry().centerline.toList()

        st.releaseGeometryExcept(emptySet())
        assertFalse(stroke.hasGeometry)

        assertEquals("a rebuilt ribbon must be the one we threw away", before, stroke.geometry().centerline.toList())
        assertTrue(stroke.hasGeometry)
    }

    @Test fun bandSelectSkipsPagesTheBandCannotReach() {
        val st = state()
        val pages = st.document.pages
        val far = pages.last()
        for (p in pages) st.cacheFor(p)
        st.releaseGeometryExcept(setOf(pages[0]))
        assertFalse(far.anyGeometry())

        // A band drawn over the first page only. Without the page guard this walked every page and
        // called bounds() on every item, rebuilding exactly what the eviction just reclaimed.
        val band = st.pageRects[0]
        val hits = SelectionMath.bandMembers(pages, st.pageRects, band)
        assertTrue(hits.isNotEmpty())
        assertTrue("the band must only select from the page it covers", hits.all { it.pageIndex == 0 })
        assertFalse("an unreachable page must not be touched at all", far.anyGeometry())
    }

    @Test fun bandSelectStillCatchesInkPaintedPastThePageEdge() {
        val st = state()
        val pages = st.document.pages
        // Ink drawn over the margin, sitting just outside its page rect.
        val overflow = ink(-30.0, 20.0)
        pages[1].items.add(overflow)

        val pr = st.pageRects[1]
        val band = Rect(pr.left - 40.0, pr.top, 30.0, 60.0) // misses the page rect, hits the ink
        val hits = SelectionMath.bandMembers(pages, st.pageRects, band)
        assertTrue("slack must keep off-page ink selectable", hits.any { it.item === overflow })
    }
}
