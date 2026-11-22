package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A page layer is cached as bands, not one bitmap: a long page is never a single huge surface, an
 * edit touches only the bands it lands on, and a frame asks only for the bands it shows.
 */
class BandedLayerTest {

    private val band = CanvasState.INK_BAND_PX

    private fun ink(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(x, y, 1.0), Sample(x + 10, y + 4, 1.0)))

    /** One page 300 wide and [height] tall, shown one page px to a pixel. */
    private fun state(height: Double, vararg items: Stroke): CanvasState =
        CanvasState(Document(mutableListOf(Page(300.0, height, items.toMutableList()))), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }

    private fun Band.ribbons() = (surface as FakeRasterSurface).painter.ribbonRuns.size

    /** Where rasters were drawn, as left, top, width, height (a footprint's left edge is minus zero). */
    private fun FakeRenderer.drawn() = rasterDests.map { listOf(it.left + 0.0, it.top + 0.0, it.w, it.h) }

    @Test fun aTallPageIsCutIntoBandsOfBoundedSize() {
        val st = state(3000.0)
        val layer = st.cacheFor(st.document.pages[0])
        assertEquals(6, layer.bands.size)
        for (b in layer.bands) {
            val s = b!!.surface
            assertEquals(300, s.width)
            assertTrue("a band and its gutters, never the page", s.height <= band + 2 * CanvasState.INK_GUTTER_PX)
        }
    }

    @Test fun aBandIsRasterizedFromTheInkItHoldsAlone() {
        val st = state(3000.0, ink(20.0, 100.0), ink(20.0, 1300.0))
        val layer = st.cacheFor(st.document.pages[0])
        assertEquals(listOf(1, 0, 1, 0, 0, 0), layer.bands.map { it!!.ribbons() })
    }

    @Test fun aNewStrokeIsPaintedOnlyOntoTheBandsThatHoldIt() {
        val st = state(3000.0)
        val page = st.document.pages[0]
        val layer = st.cacheFor(page)
        val stroke = ink(20.0, 700.0).also { page.items.add(it) }
        st.appendToCache(page, stroke)
        assertEquals(listOf(0, 1, 0, 0, 0, 0), layer.bands.map { it!!.ribbons() })
    }

    @Test fun inkOnASeamGoesOntoBothBandsSoTheirGuttersAgree() {
        val st = state(3000.0)
        val page = st.document.pages[0]
        val layer = st.cacheFor(page)
        val stroke = ink(20.0, band - 2.0).also { page.items.add(it) }
        st.appendToCache(page, stroke)
        assertEquals(listOf(1, 1, 0, 0, 0, 0), layer.bands.map { it!!.ribbons() })
    }

    @Test fun aRepairRepaintsOnlyTheBandsItMeets() {
        val st = state(3000.0, ink(20.0, 100.0), ink(20.0, 1300.0))
        val page = st.document.pages[0]
        val layer = st.cacheFor(page)
        val before = layer.bands.map { it!!.ribbons() }
        assertTrue(st.repairRegion(page, Rect(0.0, 1250.0, 300.0, 100.0)))
        val after = layer.bands.map { it!!.ribbons() }
        assertEquals("the band holding that ink painted it once more", before[2] + 1, after[2])
        assertEquals("and no other band was touched", before.filterIndexed { k, _ -> k != 2 }, after.filterIndexed { k, _ -> k != 2 })
    }

    @Test fun aFrameAsksOnlyForTheBandsItShows() {
        val st = state(3000.0)
        val page = st.document.pages[0]
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        val r = FakeRenderer()

        st.drawInk(r, page, Rect(0.0, 0.0, 300.0, 400.0))
        assertEquals("the top of the page is one band", 1, jobs.size)
        assertTrue("nothing to draw until it lands", r.rasterDests.isEmpty())

        st.drawInk(r, page, Rect(0.0, 400.0, 300.0, 400.0))
        assertEquals("scrolled across a seam: the next band, and the first is not asked for twice", 2, jobs.size)

        while (jobs.isNotEmpty()) jobs.removeFirst()()
        st.drawInk(r, page, Rect(0.0, 400.0, 300.0, 400.0))
        assertEquals(listOf(listOf(0.0, 0.0, 300.0, 512.0), listOf(0.0, 512.0, 300.0, 512.0)), r.drawn())
        assertEquals("the four bands off screen are not built by drawing", 2, st.cacheForOrSchedule(page)!!.built())
    }

    @Test fun aBandNotBuiltAtTheNewZoomShowsTheLayerBeforeIt() {
        val st = state(3000.0, ink(20.0, 100.0))
        val page = st.document.pages[0]
        st.cacheFor(page)
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }

        st.setZoomAnchored(Pt(0.0, 0.0), 0.5)
        val seen = Rect(0.0, 0.0, 300.0, 400.0)
        val stale = FakeRenderer()
        st.drawInk(stale, page, seen)
        assertEquals("the band of the new layer is asked for", 1, jobs.size)
        assertEquals("and the old layer's band shows there meanwhile, in its own place", listOf(listOf(0.0, 0.0, 300.0, 512.0)), stale.drawn())
        assertNotNull("clipped to the band it stands in for", stale.clips.singleOrNull())

        jobs.removeFirst()()
        val fresh = FakeRenderer()
        st.drawInk(fresh, page, seen)
        assertEquals("then the new band, twice the page to a pixel", listOf(listOf(0.0, 0.0, 300.0, 1024.0)), fresh.drawn())
        assertTrue(fresh.clips.isEmpty())
    }

    @Test fun aBuildThrownAwayGivesItsPixelsBack() {
        val factory = FakeSurfaceFactory()
        val st = CanvasState(Document(mutableListOf(Page(300.0, 3000.0))), factory, Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }
        val page = st.document.pages[0]
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        st.drawInk(FakeRenderer(), page, Rect(0.0, 0.0, 300.0, 400.0))
        st.invalidatePage(page) // an edit the build in flight knows nothing of
        jobs.removeFirst()()
        assertTrue("the surface it painted is never shown", factory.created.single().recycled)
        assertEquals(0, st.inkLayer(page)?.built() ?: 0)
    }

    @Test fun aPageInvalidatedGivesItsBandsBack() {
        val factory = FakeSurfaceFactory()
        val st = CanvasState(Document(mutableListOf(Page(300.0, 3000.0))), factory, Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }
        val page = st.document.pages[0]
        st.cacheFor(page)
        assertTrue(factory.created.none { it.recycled })
        st.invalidatePage(page)
        assertTrue(factory.created.all { it.recycled })
    }

    @Test fun aStrokeDrawnWhileItsBandIsBeingBuiltIsOnTheBandThatLands() {
        val st = state(3000.0)
        val page = st.document.pages[0]
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        st.drawInk(FakeRenderer(), page, Rect(0.0, 0.0, 300.0, 400.0)) // the build reads an empty page

        val stroke = ink(20.0, 100.0).also { page.items.add(it) }
        st.appendToCache(page, stroke)
        jobs.removeFirst()()

        val r = FakeRenderer()
        st.drawInk(r, page, Rect(0.0, 0.0, 300.0, 400.0))
        assertEquals(1, r.rasterDests.size)
        assertNull("no second build is needed for it", jobs.firstOrNull())
        assertEquals(1, st.cacheFor(page).bands[0]!!.ribbons())
    }
}
