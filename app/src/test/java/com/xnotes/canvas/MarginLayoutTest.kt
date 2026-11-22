package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.PageMargins
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page margin grows the paper without moving anything on it. */
class MarginLayoutTest {

    private fun state(rotation: Int = 0): CanvasState =
        CanvasState(Document.blank(2), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            rotationDeg = rotation
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    private fun assertPt(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }

    @Test fun theFootprintGrowsByTheMargins() {
        val st = state()
        val page = st.document.pages[0]
        val w = page.width
        val h = page.height
        page.margins = PageMargins(left = 0.25, bottom = 0.5)
        st.relayout()
        assertEquals(w * 1.25, st.displayW(page), 1e-9)
        assertEquals(h * 1.5, st.displayH(page), 1e-9)
        assertEquals(w * 1.25, st.pageRects[0].w, 1e-9)
        assertEquals(h * 1.5, st.pageRects[0].h, 1e-9)
        // Page space keeps its origin at the content box and runs negative into the margin.
        assertEquals(-w * 0.25, st.footprint(page).left, 1e-9)
        assertEquals(0.0, st.footprint(page).top, 1e-9)
    }

    @Test fun contentKeepsItsPlaceOnTheGrownPaper() {
        val st = state()
        val page = st.document.pages[0]
        val before = st.fromPageSpace(0, Pt(10.0, 10.0)) // content-space position of an inked point
        page.margins = PageMargins(left = 0.25, top = 0.25)
        st.relayout()
        val after = st.fromPageSpace(0, Pt(10.0, 10.0))
        // The paper grew leftward/upward, so the same page point sits further right/down in the
        // document — the ink moved with the paper it is written on.
        assertEquals(page.width * 0.25, after.x - before.x, 1e-9)
        assertEquals(page.height * 0.25, after.y - before.y, 1e-9)
    }

    @Test fun displayAndPageSpaceRoundTripUnderEveryRotation() {
        for (deg in listOf(0, 90, 180, 270)) {
            val st = state(deg)
            val page = st.document.pages[0]
            page.margins = PageMargins(left = 0.2, top = 0.1, right = 0.05, bottom = 0.3)
            st.relayout()
            for (p in listOf(Pt(0.0, 0.0), Pt(37.0, 91.0), Pt(-40.0, -20.0), Pt(page.width, page.height))) {
                assertPt(p, st.displayToPage(page, st.pageToDisplay(page, p)))
            }
        }
    }

    @Test fun aPageSpaceCornerLandsOnTheRightDisplayCorner() {
        val st = state()
        val page = st.document.pages[0]
        page.margins = PageMargins(left = 0.25, top = 0.5)
        st.relayout()
        // Page space (0,0) is inset from the display footprint's corner by the left/top margins.
        assertPt(Pt(page.width * 0.25, page.height * 0.5), st.pageToDisplay(page, Pt(0.0, 0.0)))
        assertPt(Pt(0.0, 0.0), st.displayToPage(page, Pt(page.width * 0.25, page.height * 0.5)))
    }

    @Test fun aTapInTheMarginHitsThePageAtNegativeCoordinates() {
        val st = state()
        val page = st.document.pages[0]
        page.margins = PageMargins(left = 0.25)
        st.relayout()
        val pr = st.pageRects[0]
        val inMargin = Pt(pr.left + 4.0, pr.top + 100.0)
        assertEquals(0, st.pageIndexAtContent(inMargin))
        val local = st.toPageSpace(0, inMargin)
        assertEquals(4.0 - page.width * 0.25, local.x, 1e-9)
        assertEquals(100.0, local.y, 1e-9)
    }

    @Test fun documentMarginsApplyToEveryPage() {
        val st = state()
        st.document.margins = PageMargins(right = 0.5)
        st.relayout()
        for (page in st.document.pages) assertEquals(page.width * 1.5, st.displayW(page), 1e-9)
    }

    @Test fun aMarginEditRebuildsTheCacheAtTheNewSize() {
        val st = state()
        val page = st.document.pages[0]
        val before = st.cacheFor(page)
        assertEquals(st.footprint(page), before.cover)

        page.margins = PageMargins(right = 0.5)
        st.relayout()
        st.invalidatePageGeometry()
        val after = st.cacheFor(page)
        assertEquals(st.footprint(page), after.cover)
        assertEquals(page.width * 1.5, after.cover.w, 1e-9)
    }

    @Test fun aResizedPageIsNotDrawnFromItsOldSurface() {
        val st = state()
        // The app builds caches off the UI thread; hold the build so the draw loop is asked for a
        // page whose rebuild is still in flight, which is every frame of a margin-slider drag.
        val pending = mutableListOf<() -> Unit>()
        st.runAsync = { pending += it }
        val page = st.document.pages[0]
        st.cacheFor(page) // rasterize at the current size
        page.margins = PageMargins(right = 0.5)
        st.relayout()
        st.invalidatePageGeometry()

        // Nothing to blit while the rebuild is in flight: bare paper beats a stretched page.
        assertNull(st.cacheForOrSchedule(page))
        assertEquals("one build a band", st.inkLayer(page)!!.bands.size, pending.size)

        pending.forEach { it() }
        val rebuilt = st.cacheForOrSchedule(page)
        assertNotNull(rebuilt)
        assertEquals(st.footprint(page), rebuilt!!.cover)
    }

    @Test fun aMarginDraggedBackToASizeAlreadyDrawnShowsAtOnce() {
        val st = state()
        val page = st.document.pages[0]
        val original = st.cacheFor(page)
        page.margins = PageMargins(right = 0.5)
        st.relayout()
        st.invalidatePageGeometry()
        page.margins = PageMargins()
        st.relayout()
        st.invalidatePageGeometry()
        assertSame(original, st.cacheForOrSchedule(page))
    }

    @Test fun aBuildDiscardedMidDragStillBringsTheDrawLoopBack() {
        val st = state()
        val pending = mutableListOf<() -> Unit>()
        var repaints = 0
        st.runAsync = { pending += it }
        st.onCacheReady = { repaints++ }
        val page = st.document.pages[0]

        // A frame schedules the page's ink, a build a band; the slider moves before they land.
        st.cacheForOrSchedule(page)
        val bands = pending.size
        assertTrue(bands > 0)
        page.margins = PageMargins(right = 0.5)
        st.relayout()
        st.invalidatePageGeometry()

        // The next frame is turned away: a build for each of those bands is already in flight.
        assertNull(st.cacheForOrSchedule(page))
        val wider = st.inkLayer(page)!!.bands.size // a wider page fills the view at a coarser resolution
        assertTrue(wider <= bands)
        assertEquals(bands, pending.size)

        // Those builds are now stale and are dropped — but each must still ask for a repaint, or
        // nothing would ever schedule the replacements and the page would stay blank until a scroll.
        val stale = pending.toList()
        pending.clear()
        stale.forEach { it() }
        assertEquals(bands, repaints)
        assertNull(st.cacheForOrSchedule(page)) // the repaint's frame reschedules
        assertEquals(wider, pending.size)

        pending.toList().forEach { it() }
        val rebuilt = st.cacheForOrSchedule(page)
        assertNotNull(rebuilt)
        assertEquals(st.footprint(page), rebuilt!!.cover)
    }
}
