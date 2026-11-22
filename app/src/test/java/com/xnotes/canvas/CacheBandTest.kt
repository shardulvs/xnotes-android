package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is cached round the viewport used to be sized by the visible page count, which flips as page
 * edges cross the screen: every flip evicted the pages just prefetched, so each PDF page rendered
 * about three times on a plain scroll, and a pinch (any two-finger pan) evicted every neighbour
 * outright. It is sized in bytes now, band by band, nearest the screen first.
 */
class CacheBandTest {

    private class Harness(
        pageCount: Int,
        vw: Int,
        vh: Int,
        mode: ViewingMode = ViewingMode.SINGLE,
        vertical: Boolean = true,
        pageH: Double = 842.0,
    ) {
        /** How often each region of each page's background was rendered. */
        val pdfRenders = HashMap<Pair<Page, Rect>, Int>()
        private val cacheThread = ArrayDeque<() -> Unit>()
        val factory = FakeSurfaceFactory()
        val st = CanvasState(
            Document((0 until pageCount).mapTo(mutableListOf()) { Page(595.0, pageH, pdfPage = it) }),
            factory,
            Palette.DEFAULT,
        ).apply {
            viewingMode = mode
            verticalScroll = vertical
            viewportW = vw
            viewportH = vh
            paintPageBackground = { page, _, _, region -> pdfRenders.merge(page to region, 1, Int::plus) }
            runAsync = { cacheThread.addLast(it) }
            relayout()
            establishInitialView()
        }

        private fun missing(layer: PageLayer?, seen: Rect): Int =
            layer?.let { l -> l.grid.meeting(seen).count { l.bands[it] == null } } ?: 1

        /** One onDraw's worth of cache traffic, then the cache thread catches up. Returns how many bands on screen had no raster yet. */
        fun frame(): Int {
            val visible = st.visibleContentRect()
            val drawable = st.drawablePageRange()
            val r = FakeRenderer()
            var cold = 0
            for (i in st.document.pages.indices) {
                val pr = st.pageRects[i]
                if (i !in drawable || !pr.intersects(visible)) continue
                val page = st.document.pages[i]
                val seen = st.displayRectToPage(page, visible.translate(-pr.left, -pr.top))
                st.drawBackground(r, page, seen)
                st.drawInk(r, page, seen)
                cold += missing(st.backgroundLayer(page), seen) + missing(st.inkLayer(page), seen)
            }
            st.prefetchAndPrune()
            while (cacheThread.isNotEmpty()) cacheThread.removeFirst()()
            return cold
        }

        fun renderedOnce(): Boolean = pdfRenders.values.all { it == 1 }

        fun built(page: Page): Int = (st.inkLayer(page)?.built() ?: 0) + (st.backgroundLayer(page)?.built() ?: 0)

        /** Pages with a band of the layer [of] gives built. */
        fun held(of: (Page) -> PageLayer?): Int = st.document.pages.count { (of(it)?.built() ?: 0) > 0 }

        /** Surfaces made and not given back, against the bands the caches hold. */
        fun unreturned(): Int = factory.created.count { !it.recycled }
        fun bands(): Int = st.document.pages.sumOf { built(it) }
        fun aBuiltBandWasGivenBack(): Boolean = st.document.pages.any { page ->
            listOfNotNull(st.inkLayer(page), st.backgroundLayer(page)).any { l -> l.bands.any { (it?.surface as? FakeRasterSurface)?.recycled == true } }
        }
    }

    private val screens = listOf(
        "tablet portrait" to (1440 to 2204),
        "tablet landscape" to (2304 to 1340),
        "phone portrait" to (1080 to 2300),
    )

    @Test fun aSteadyScrollRendersEachBandOnceAndLandsOnReadyBands() {
        for (mode in ViewingMode.values()) {
            for ((name, size) in screens) {
                val h = Harness(60, size.first, size.second, mode)
                h.st.goToPage(6)
                h.frame()
                val target = h.st.pageRects[40].top * h.st.zoom
                var cold = 0
                while (h.st.scrollY < target) {
                    h.st.scrollBy(0.0, 12.0)
                    cold += h.frame()
                }
                assertEquals("$mode $name: bands scrolled onto without a raster", 0, cold)
                assertTrue("$mode $name: a PDF band rendered twice ${h.pdfRenders.values}", h.renderedOnce())
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
            assertTrue("$name: a PDF band rendered twice ${h.pdfRenders.values}", h.renderedOnce())
        }
    }

    @Test fun aPinchKeepsTheCachedNeighbours() {
        val h = Harness(40, 1440, 2204)
        h.st.goToPage(10)
        h.frame()
        val ink = h.held { h.st.inkLayer(it) }
        val pdf = h.held { h.st.backgroundLayer(it) }
        assertTrue(ink > h.st.visiblePageRange()!!.let { it.last - it.first + 1 })

        h.st.zoomingInProgress = true
        h.frame()
        assertEquals("ink evicted by a pinch", ink, h.held { h.st.inkLayer(it) })
        assertEquals("PDF evicted by a pinch", pdf, h.held { h.st.backgroundLayer(it) })
    }

    @Test fun flippingPaginatedPagesBackAndForthRendersNothingAgain() {
        val h = Harness(40, 1440, 2204, vertical = false)
        h.st.goToPage(10)
        h.frame()
        var cold = 0
        repeat(3) {
            h.st.goToPage(11)
            cold += h.frame()
            h.st.goToPage(10)
            cold += h.frame()
        }
        assertEquals("flipped onto a page without a raster", 0, cold)
        assertTrue("a PDF band rendered twice ${h.pdfRenders.values}", h.renderedOnce())
    }

    @Test fun aViewInsideTheGapStillWantsBothNeighboursFirst() {
        val h = Harness(10, 400, 300)
        val st = h.st
        st.zoom = CanvasState.MAX_ZOOM
        val gapMid = (st.pageRects[4].bottom + st.pageRects[5].top) / 2.0
        st.scrollX = st.pageRects[4].centerX * st.zoom - st.viewportW / 2.0
        st.scrollY = gapMid * st.zoom - st.viewportH / 2.0
        assertEquals(null, st.visiblePageRange())
        st.prefetchAndPrune()
        val pages = st.document.pages
        assertEquals(listOf(pages[5], pages[4]), st.wantedPages().take(2))
    }

    @Test fun theCachesStayInsideTheBudgetHoweverFarTheScrollGoes() {
        for ((name, size) in screens) {
            val h = Harness(60, size.first, size.second)
            h.st.goToPage(3)
            h.frame()
            val target = h.st.pageRects[50].top * h.st.zoom
            var most = 0L
            while (h.st.scrollY < target) {
                h.st.scrollBy(0.0, 400.0)
                h.frame()
                most = maxOf(most, h.st.cacheSnapshot().bytes)
            }
            assertTrue("$name: $most bytes cached over a budget of ${h.st.cacheBudget()}", most <= h.st.cacheBudget())
            assertTrue("$name: and most of it is put to use", most >= h.st.cacheBudget() / 2)
        }
    }

    @Test fun whatIsFurthestFromTheScreenGoesFirst() {
        val h = Harness(60, 1440, 2204)
        h.st.goToPage(3)
        h.frame()
        val target = h.st.pageRects[50].top * h.st.zoom
        while (h.st.scrollY < target) {
            h.st.scrollBy(0.0, 400.0)
            h.frame()
        }
        val pages = h.st.document.pages
        val held = pages.indices.filter { h.built(pages[it]) > 0 }
        assertEquals("what is held is one run of pages round the screen", held.first()..held.last(), held.first()..held.last())
        assertEquals(held.size, held.last() - held.first() + 1)
        assertTrue("the page on screen is in it", h.st.currentPageIndex() in held)
        assertTrue("with pages kept behind it for a scroll back", held.first() < h.st.currentPageIndex())
        assertEquals("and nothing of where the scroll began", 0, h.built(pages[3]))
    }

    @Test fun aShortNoteNeverLetsGoOfAnything() {
        val h = Harness(3, 1440, 2204)
        h.frame()
        repeat(3) {
            while (h.st.scrollY < h.st.maxScrollY()) { h.st.scrollBy(0.0, 200.0); h.frame() }
            while (h.st.scrollY > h.st.minScrollY()) { h.st.scrollBy(0.0, -200.0); h.frame() }
        }
        assertTrue("a PDF band rendered twice ${h.pdfRenders.values}", h.renderedOnce())
        for (page in h.st.document.pages) {
            assertEquals(h.st.inkLayer(page)!!.bands.size, h.st.inkLayer(page)!!.built())
            assertEquals(h.st.backgroundLayer(page)!!.bands.size, h.st.backgroundLayer(page)!!.built())
        }
    }

    @Test fun aVeryLongPageIsBuiltRoundTheScreenAndNoFurther() {
        // One page seventy screens long, cached at the size it shows at.
        val h = Harness(1, 1440, 2204, pageH = 842.0 * 80)
        h.frame()
        val page = h.st.document.pages[0]
        val ink = h.st.inkLayer(page)!!
        assertTrue("the whole page would be far over the budget", ink.bands.indices.sumOf { ink.grid.bytes(it) } > 4 * h.st.cacheBudget())
        var cold = 0
        repeat(400) {
            h.st.scrollBy(0.0, 40.0)
            cold += h.frame()
            assertTrue(h.st.cacheSnapshot().bytes <= h.st.cacheBudget())
        }
        assertEquals("the page stays ready under a steady scroll", 0, cold)
        assertTrue("a PDF band rendered twice ${h.pdfRenders.values}", h.renderedOnce())
        assertTrue("and its far end is never built", ink.bands.last() == null)
    }

    @Test fun whatLeavesTheCachesGivesItsPixelsBackAtOnce() {
        val h = Harness(60, 1440, 2204)
        h.st.goToPage(3)
        h.frame()
        val target = h.st.pageRects[50].top * h.st.zoom
        while (h.st.scrollY < target) {
            h.st.scrollBy(0.0, 400.0)
            h.frame()
            assertEquals("every surface not in the caches has been given back", h.bands(), h.unreturned())
            assertTrue("and none still in them has", !h.aBuiltBandWasGivenBack())
        }
        assertTrue("with plenty evicted on the way", h.factory.created.size > 3 * h.bands())
    }

    @Test fun theLayersOfAnOldZoomAreGivenBackOnceTheNewOnesAreBuilt() {
        val h = Harness(60, 1440, 2204)
        h.st.goToPage(10)
        h.frame()
        h.st.setZoomAnchored(h.st.clearCenter(), h.st.zoom * 0.8)
        repeat(3) { h.frame() }
        assertEquals(h.bands(), h.unreturned())
        assertTrue(!h.aBuiltBandWasGivenBack())
    }

    @Test fun theReadoutSaysHowMuchOfEachLayerIsReadyEitherSideOfTheView() {
        val h = Harness(60, 1440, 2204)
        h.st.goToPage(10)
        h.frame()
        h.frame()
        val snap = h.st.cacheSnapshot()
        assertTrue("ink is ready more than a screen below: ${snap.ink.below}", snap.ink.below > 1.0)
        assertTrue("and above: ${snap.ink.above}", snap.ink.above > 1.0)
        assertTrue("the PDF layer likewise: ${snap.background.below}", snap.background.below > 1.0)
        assertTrue(snap.ink.bytes > 0 && snap.background.bytes > 0)
        assertEquals("the two layers are all that is held here", snap.bytes, snap.ink.bytes + snap.background.bytes)
        assertTrue(snap.bytes <= snap.budget)

        // Jumped to where nothing is built yet: only the bare gap to the next page is ready past the view.
        h.st.goToPage(40)
        val cold = h.st.cacheSnapshot()
        assertTrue("${cold.ink.below}", cold.ink.below < 0.1)
        assertTrue("${cold.background.above}", cold.background.above < 0.1)
    }

    @Test fun aShortNoteReadsAsReadyToBothItsEnds() {
        val h = Harness(3, 1440, 2204)
        h.frame()
        h.frame()
        val st = h.st
        val view = st.visibleContentRect()
        val snap = st.cacheSnapshot()
        val toEnd = (st.pageRects.last().bottom - view.bottom) * st.zoom / st.viewportH
        assertEquals(toEnd, snap.ink.below, 1e-6)
        assertEquals(toEnd, snap.background.below, 1e-6)
        assertEquals("the view starts above the first page", 0.0, snap.ink.above, 1e-6)
    }

    @Test fun anAutomaticBudgetIsTwelveScreensBetweenAFloorAndAShareOfMemory() {
        val mb = 1L shl 20
        assertEquals(12L * 4 * 1440 * 2304, CacheBudget.automatic(1440, 2304))
        assertEquals("a small screen still gets the floor", 64 * mb, CacheBudget.automatic(800, 1280))
        assertEquals("a large one stops at the device's share", 128 * mb, CacheBudget.automatic(2560, 1600, ceiling = 128 * mb))
        assertEquals("which never undercuts the floor", 64 * mb, CacheBudget.automatic(2560, 1600, ceiling = 32 * mb))
        assertEquals("a sixteenth of the device's memory", 256 * mb, CacheBudget.ceiling(4096 * mb))
    }

    @Test fun aUserMaySetUpToAQuarterOfTheDevicesMemory() {
        val mb = 1L shl 20
        assertEquals(1024, CacheBudget.maxMb(8192 * mb))
        assertEquals(1024, CacheBudget.maxMb(4096 * mb))
        assertEquals(512, CacheBudget.maxMb(2048 * mb))
        assertEquals("a whole step under a device that reports a little short of 3 GB", 704, CacheBudget.maxMb(2850 * mb))
        assertEquals(1024, CacheBudget.maxMb(0))
    }

    @Test fun aLimitTheUserSetReplacesTheAutomaticOne() {
        val h = Harness(60, 1440, 2204)
        h.st.cacheLimit = { 80L shl 20 }
        assertEquals(80L shl 20, h.st.cacheBudget())
        h.st.goToPage(3)
        h.frame()
        val target = h.st.pageRects[30].top * h.st.zoom
        while (h.st.scrollY < target) {
            h.st.scrollBy(0.0, 400.0)
            h.frame()
            assertTrue(h.st.cacheSnapshot().bytes <= 80L shl 20)
        }
    }
}
