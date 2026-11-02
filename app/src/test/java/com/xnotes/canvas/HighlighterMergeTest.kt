package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.ceil

/**
 * Issue #386: two highlighter passes over the same spot each multiply the paper, so the crossing
 * read darker than either pass. The flat-overlaps option answers that by merging a page's
 * highlighter ink into one layer, which the frame then composites once at the ink's alpha. These
 * tests pin the merge itself: one layer for both strokes, over their union, reused while they are
 * unchanged and rebaked in place when one of them moves.
 */
class HighlighterMergeTest {

    private val factory = FakeSurfaceFactory()
    private val page = Page(595.0, 842.0)
    private val st = CanvasState(Document(mutableListOf(page)), factory, Palette.DEFAULT).apply {
        viewportW = 1000
        viewportH = 1400
        relayout()
        establishInitialView()
    }

    private fun highlighter(y: Double) = Stroke(
        tool = Tool.HIGHLIGHTER,
        config = ToolConfig(rgba = Rgba(255, 235, 59)),
    ).apply {
        addSample(Sample(100.0, y, 0.6, 0.0))
        addSample(Sample(300.0, y, 0.6, 5.0))
        finished = true
    }

    @Test
    fun twoPassesShareOneLayerOverTheirUnion() {
        val a = highlighter(100.0)
        val b = highlighter(108.0) // crosses a
        val entry = st.mergedHighlightersFor(page, listOf(a, b), 0.35, BlendMode.MULTIPLY)!!
        val cover = st.highlighterCacheFor(a, page).cover.union(st.highlighterCacheFor(b, page).cover)
        assertEquals(cover, entry.cover)
        assertEquals(ceil(cover.w * entry.res).toInt(), entry.surface.width)
        assertEquals(2, entry.parts.size)
    }

    @Test
    fun anUnchangedPageRebakesNothingAndAllocatesNothing() {
        val a = highlighter(100.0)
        val b = highlighter(108.0)
        val first = st.mergedHighlightersFor(page, listOf(a, b), 0.35, BlendMode.MULTIPLY)!!
        val fills = (first.surface as FakeRasterSurface).fills
        val created = factory.created.size
        val again = st.mergedHighlightersFor(page, listOf(a, b), 0.35, BlendMode.MULTIPLY)!!
        assertSame(first, again)
        assertEquals(fills, (again.surface as FakeRasterSurface).fills)
        assertEquals(created, factory.created.size)
    }

    @Test
    fun editingAStrokeRebakesTheSameLayer() {
        val a = highlighter(100.0)
        val b = highlighter(108.0)
        val entry = st.mergedHighlightersFor(page, listOf(a, b), 0.35, BlendMode.MULTIPLY)!!
        val fills = (entry.surface as FakeRasterSurface).fills
        a.invalidate() // any edit drops the ribbon, so the per-stroke bitmap is rebuilt
        st.highlighterCacheFor(a, page)
        val again = st.mergedHighlightersFor(page, listOf(a, b), 0.35, BlendMode.MULTIPLY)!!
        assertSame(entry, again)
        assertEquals(fills + 1, (entry.surface as FakeRasterSurface).fills)
    }
}
