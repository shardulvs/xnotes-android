package com.xnotes.canvas

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
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
 * Locks the sharp-viewport edit replay: a stroke committed (or erased) while the
 * sharp render is still in flight is replayed onto the freshly published frame,
 * so it doesn't vanish (or reappear) once the sharp layer settles.
 */
class SharpViewportEditReplayTest {

    private class RecordingSurface(override val width: Int, override val height: Int) : RasterSurface {
        override val devicePixelRatio = 1.0
        val recorder = FakeRenderer()
        override fun fill(color: Rgba) {}
        override fun renderer(): Renderer = recorder
    }

    private class RecordingFactory : SurfaceFactory {
        val surfaces = mutableListOf<RecordingSurface>()
        override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface =
            RecordingSurface(widthPx, heightPx).also { surfaces.add(it) }
    }

    private fun dot(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    private val factory = RecordingFactory()
    private val queued = mutableListOf<() -> Unit>()

    private fun state(page: Page): CanvasState {
        val doc = Document(mutableListOf(page))
        return CanvasState(doc, factory, Palette.DEFAULT).apply {
            viewportW = 100 // a 200px page is past the cap already at zoom 1
            viewportH = 1000
            relayout()
            runAsync = { queued.add(it) }
            cacheFor(document.pages[0]) // soft cache in place, like the live draw loop keeps it
        }
    }

    private fun runPending() {
        val work = queued.toList()
        queued.clear()
        work.forEach { it() }
    }

    /** Indices of ink-primitive ops (a dot paints circles/polygons) in a recorded op list. */
    private fun List<String>.inkOps(): List<Int> =
        withIndex().filter { it.value == "fillCircle" || it.value == "fillPolygon" }.map { it.index }

    private fun List<String>.lastClear(): Int = lastIndexOf("clear")

    /** The sharp ink layer is the last surface the async build created. */
    private fun sharpInkOps(): List<String> = factory.surfaces.last().recorder.ops

    @Test
    fun strokeCommittedDuringSharpBuildIsReplayedOntoTheFreshFrame() {
        val page = Page(200.0, 200.0, mutableListOf(dot(50.0, 50.0)))
        val st = state(page)
        st.requestSharpViewport() // snapshots the single existing stroke
        val late = dot(150.0, 150.0)
        page.items.add(late)
        st.appendToCache(page, late) // commits while the build is still in flight
        runPending() // build + publish + replay
        val ops = sharpInkOps()
        val clear = ops.lastClear()
        assertTrue(clear >= 0) // the replay repaired the late stroke's region
        val before = ops.inkOps().filter { it < clear }
        val after = ops.inkOps().filter { it > clear }
        assertEquals(before.size, after.size) // one dot from the snapshot, one replayed
        assertTrue(after.isNotEmpty())
        assertNotNull(st.sharpViewportBlit())
    }

    @Test
    fun eraseDuringSharpBuildIsReplayedOntoTheFreshFrame() {
        val victim = dot(50.0, 50.0)
        val page = Page(200.0, 200.0, mutableListOf(victim))
        val st = state(page)
        st.requestSharpViewport() // snapshot still contains the victim
        page.items.remove(victim)
        assertTrue(st.repairRegion(page, victim.paintBounds().outset(2.0)))
        runPending()
        val ops = sharpInkOps()
        val clear = ops.lastClear()
        assertTrue(clear >= 0)
        assertTrue(ops.inkOps().all { it < clear }) // nothing repainted after the replay clear
    }

    @Test
    fun editLogDoesNotLeakIntoTheNextPublish() {
        val page = Page(200.0, 200.0, mutableListOf(dot(50.0, 50.0)))
        val st = state(page)
        st.requestSharpViewport()
        val late = dot(150.0, 150.0)
        page.items.add(late)
        st.appendToCache(page, late)
        st.setZoomAnchored(Pt(0.0, 0.0), 2.0) // view moved: the in-flight frame is discarded
        runPending()
        assertNull(st.sharpViewportBlit())
        st.requestSharpViewport() // fresh snapshot already contains both strokes
        runPending()
        assertEquals(-1, sharpInkOps().lastClear()) // no stale replay over the fresh render
        assertNotNull(st.sharpViewportBlit())
    }
}
