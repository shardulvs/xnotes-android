package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band of pages cached around the viewport used to be sized by the visible page count, which flips as
 * page edges cross the screen: every flip evicted the pages just prefetched, so each PDF page rendered about
 * three times on a plain scroll, and a pinch (any two-finger pan) evicted every neighbour outright.
 */
class CacheBandTest {

    private class Harness(pageCount: Int, vw: Int, vh: Int, mode: ViewingMode = ViewingMode.SINGLE, vertical: Boolean = true) {
        val pdfRenders = HashMap<Page, Int>()
        private val cacheThread = ArrayDeque<() -> Unit>()
        val st = CanvasState(
            Document((0 until pageCount).mapTo(mutableListOf()) { Page(595.0, 842.0, pdfPage = it) }),
            FakeSurfaceFactory(),
            Palette.DEFAULT,
        ).apply {
            viewingMode = mode
            verticalScroll = vertical
            viewportW = vw
            viewportH = vh
            paintPageBackground = { page, _, _, _ -> pdfRenders.merge(page, 1, Int::plus) }
            runAsync = { cacheThread.addLast(it) }
            relayout()
            establishInitialView()
        }

        /** One onDraw's worth of cache traffic, then the cache thread catches up. Returns the drawn pages that had no raster yet. */
        fun frame(): List<Page> {
            val visible = st.visibleContentRect()
            val drawable = st.drawablePageRange()
            val drawn = ArrayList<Page>()
            val cold = ArrayList<Page>()
            for (i in st.document.pages.indices) {
                if (i !in drawable || !st.pageRects[i].intersects(visible)) continue
                val page = st.document.pages[i]
                drawn += page
                val bg = st.backgroundForOrSchedule(page)
                val ink = st.cacheForOrSchedule(page)
                if (bg == null || ink == null) cold += page
            }
            assertTrue("every drawn page is inside the band", st.cacheBand().keep.containsAll(drawn))
            st.prefetchAndPrune()
            while (cacheThread.isNotEmpty()) cacheThread.removeFirst()()
            return cold
        }

        fun renderedOnce(): Boolean = pdfRenders.values.all { it == 1 }
    }

    private val screens = listOf(
        "tablet portrait" to (1440 to 2204),
        "tablet landscape" to (2304 to 1340),
        "phone portrait" to (1080 to 2300),
    )

    @Test fun aSteadyScrollRendersEachPageOnceAndLandsOnReadyPages() {
        for (mode in ViewingMode.values()) {
            for ((name, size) in screens) {
                val h = Harness(60, size.first, size.second, mode)
                h.st.goToPage(6)
                h.frame()
                val target = h.st.pageRects[40].top * h.st.zoom
                var cold = 0
                while (h.st.scrollY < target) {
                    h.st.scrollBy(0.0, 12.0)
                    cold += h.frame().size
                }
                assertEquals("$mode $name: pages scrolled onto without a raster", 0, cold)
                assertTrue("$mode $name: a PDF page rendered twice ${h.pdfRenders.values}", h.renderedOnce())
            }
        }
    }

    @Test fun scrollingBackAndForthRendersNothingAgain() {
        for ((name, size) in screens) {
            val h = Harness(40, size.first, size.second)
            h.st.goToPage(10)
            h.frame()
            repeat(5) {
                repeat(60) { h.st.scrollBy(0.0, 15.0); h.frame() }
                repeat(60) { h.st.scrollBy(0.0, -15.0); h.frame() }
            }
            assertTrue("$name: a PDF page rendered twice ${h.pdfRenders.values}", h.renderedOnce())
        }
    }

    @Test fun aPinchKeepsTheCachedNeighbours() {
        val h = Harness(40, 1440, 2204)
        h.st.goToPage(10)
        h.frame()
        val cached = h.st.cacheSnapshot()
        assertTrue(cached.inkPages > cached.visiblePages)

        h.st.zoomingInProgress = true
        h.frame()
        assertEquals("ink evicted by a pinch", cached.inkPages, h.st.cacheSnapshot().inkPages)
        assertEquals("PDF evicted by a pinch", cached.bgPages, h.st.cacheSnapshot().bgPages)
    }

    @Test fun flippingPaginatedPagesBackAndForthRendersNothingAgain() {
        val h = Harness(40, 1440, 2204, vertical = false)
        h.st.goToPage(10)
        h.frame()
        var cold = 0
        repeat(3) {
            h.st.goToPage(11)
            cold += h.frame().size
            h.st.goToPage(10)
            cold += h.frame().size
        }
        assertEquals("flipped onto a page without a raster", 0, cold)
        assertTrue("a PDF page rendered twice ${h.pdfRenders.values}", h.renderedOnce())
    }

    @Test fun aViewInsideTheGapStillPrefetchesBothNeighbours() {
        val h = Harness(10, 400, 300)
        val st = h.st
        st.zoom = CanvasState.MAX_ZOOM
        val gapMid = (st.pageRects[4].bottom + st.pageRects[5].top) / 2.0
        st.scrollX = st.pageRects[4].centerX * st.zoom - st.viewportW / 2.0
        st.scrollY = gapMid * st.zoom - st.viewportH / 2.0
        assertEquals(null, st.visiblePageRange())
        val pages = st.document.pages
        assertEquals(listOf(pages[5], pages[4]), st.cacheBand().prefetch)
    }
}
