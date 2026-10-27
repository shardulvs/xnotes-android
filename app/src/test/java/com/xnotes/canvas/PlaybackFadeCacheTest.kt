package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.audio.InkFade
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page caches while a recording's ink fades. The fade coming or going (the player opening or
 * closing, or its pill deleted) must not blank the pages, the flash an invalidation made; and ink
 * the playhead crosses while a page rebuilds must not come back faint when the rebuild lands.
 */
class PlaybackFadeCacheTest {

    private fun ink(x: Double, y: Double, ms: Int): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0))).apply { sync = SyncMark(1, ms) }

    private val factory = FakeSurfaceFactory()

    private fun state(vararg items: CanvasItem, maxCache: Double = 2048.0): CanvasState {
        val doc = Document(mutableListOf(Page(200.0, 200.0, items.toMutableList())))
        return CanvasState(doc, factory, Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            maxCachePx = maxCache
            relayout()
        }
    }

    private fun FakeRasterSurface.faintRuns() = painter.ops.count { it == "saveLayerAlpha" }

    @Test fun closingThePlayerRebuildsBehindThePageInsteadOfBlankingIt() {
        val late = ink(120.0, 120.0, 9_000)
        val st = state(ink(20.0, 20.0, 1_000), late)
        val page = st.document.pages[0]
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        st.fade = InkFade(1, 5_000) // paused halfway: the later dot is faint
        val faded = st.cacheFor(page).surface as FakeRasterSurface
        assertEquals(1, faded.faintRuns())
        val painted = faded.painter.ribbonRuns.size

        st.fade = null
        st.refreshInkRegions(mapOf(page to late.paintBounds()))

        assertEquals("nothing is rasterized on the main thread", painted, faded.painter.ribbonRuns.size)
        assertEquals("the page keeps its cache, or it blinks to bare paper", 1, st.cacheSnapshot().inkPages)
        assertSame("the faded surface shows until the rebuild lands", faded, st.cacheForOrSchedule(page)?.surface)
        assertEquals(1, jobs.size)
        jobs.removeFirst().invoke()
        val after = st.cacheForOrSchedule(page)?.surface as FakeRasterSurface
        assertNotSame(faded, after)
        assertEquals("the rebuilt surface draws the ink solid", 0, after.faintRuns())
    }

    @Test fun inkThePlayheadCrossesWhileItsPageRebuildsStaysCrossed() {
        val first = ink(20.0, 20.0, 1_000)
        val st = state(first, ink(120.0, 120.0, 9_000))
        val page = st.document.pages[0]
        st.cacheFor(page)
        val jobs = ArrayDeque<() -> Unit>()
        val landings = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        st.postToMain = { landings.addLast(it) }

        st.fade = InkFade(1, 0) // the player opens at the start: all its ink faint
        st.refreshInkRegions(mapOf(page to Rect(0.0, 0.0, 200.0, 200.0)))
        jobs.removeFirst().invoke() // the cache thread paints both dots faint
        st.fade = InkFade(1, 1_500) // and before that lands, the playhead passes the first
        assertTrue(st.repairRegion(page, first.paintBounds()))
        landings.removeFirst().invoke()

        val painter = (st.cacheForOrSchedule(page)?.surface as FakeRasterSurface).painter
        assertEquals("the crossing is painted again on the surface that landed", first.paintBounds(), painter.clips.last())
        val repaint = painter.ops.subList(painter.ops.lastIndexOf("clear"), painter.ops.size)
        assertFalse("solid, as the playhead left it", repaint.contains("saveLayerAlpha"))
        assertEquals("both dots from the build, then the first once more", 3, painter.ribbonRuns.size)
    }

    @Test fun aDeepZoomStaysSharpAsTheFadeComesOrGoes() {
        val late = ink(120.0, 120.0, 9_000)
        val st = state(ink(20.0, 20.0, 1_000), late, maxCache = 100.0) // a 200px page is past the cap at zoom 1
        val page = st.document.pages[0]
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        st.cacheFor(page)
        st.requestSharpViewport()
        while (jobs.isNotEmpty()) jobs.removeFirst().invoke()
        assertNotNull(st.sharpViewportBlit())
        val sharpInk = factory.created.last()

        st.fade = InkFade(1, 5_000)
        st.refreshInkRegions(mapOf(page to late.paintBounds()))

        assertNotNull("the sharp layer is patched, not dropped to the soft cache under it", st.sharpViewportBlit())
        val ops = sharpInk.painter.ops
        assertTrue("and the patch draws the later dot faint", ops.subList(ops.lastIndexOf("clear"), ops.size).contains("saveLayerAlpha"))
    }
}
