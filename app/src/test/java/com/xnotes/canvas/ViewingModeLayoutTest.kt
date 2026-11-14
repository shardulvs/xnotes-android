package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Double/Cover row layouts: pairing, rects, navigation and the visible range. */
class ViewingModeLayoutTest {

    private fun state(pages: Int, mode: ViewingMode): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewingMode = mode
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    @Test fun singleKeepsTheClassicColumn() {
        val st = state(3, ViewingMode.SINGLE)
        val h = st.document.pages[0].height
        for (i in 0..2) {
            assertEquals(CanvasState.MARGIN + i * (h + st.pageGap), st.pageRects[i].top, 1e-6)
        }
        assertEquals(st.document.pages[0].width + 2 * st.sideMargin, st.contentW, 1e-6)
    }

    @Test fun doublePairsPagesSideBySide() {
        val st = state(4, ViewingMode.DOUBLE)
        assertEquals(listOf(0..1, 2..3), st.rowRanges())
        assertEquals(st.pageRects[0].top, st.pageRects[1].top, 1e-6)
        assertEquals(st.pageRects[0].right + st.pageGap, st.pageRects[1].left, 1e-6)
        assertTrue(st.pageRects[2].top > st.pageRects[0].bottom)
        val w = st.document.pages[0].width
        assertEquals(2 * w + st.pageGap + 2 * st.sideMargin, st.contentW, 1e-6)
    }

    @Test fun doubleWithOddCountLeavesLastPageAlone() {
        val st = state(5, ViewingMode.DOUBLE)
        assertEquals(listOf(0..1, 2..3, 4..4), st.rowRanges())
        // The lone last page is centred against the spread width.
        val spread = 2 * st.document.pages[0].width + st.pageGap
        val expectedLeft = st.sideMargin + (spread - st.document.pages[4].width) / 2.0
        assertEquals(expectedLeft, st.pageRects[4].left, 1e-6)
    }

    @Test fun coverLaysTheFirstPageAlone() {
        val st = state(5, ViewingMode.COVER)
        assertEquals(listOf(0..0, 1..2, 3..4), st.rowRanges())
        assertEquals(st.pageRects[1].top, st.pageRects[2].top, 1e-6)
        assertTrue(st.pageRects[1].top > st.pageRects[0].bottom)
    }

    @Test fun rowOfMatchesRowRanges() {
        for (mode in ViewingMode.entries) {
            val st = state(7, mode)
            for (row in st.rowRanges()) for (i in row) assertEquals(row, st.rowOf(i))
        }
    }

    @Test fun pageNavStepsByRow() {
        val st = state(6, ViewingMode.DOUBLE)
        assertEquals(2, st.nextPageIndex(0))
        assertEquals(2, st.nextPageIndex(1))
        assertEquals(0, st.prevPageIndex(2))
        assertEquals(0, st.prevPageIndex(3))

        val cov = state(6, ViewingMode.COVER)
        assertEquals(1, cov.nextPageIndex(0))
        assertEquals(3, cov.nextPageIndex(1))
        assertEquals(3, cov.nextPageIndex(2))
        assertEquals(1, cov.prevPageIndex(3))
        assertEquals(0, cov.prevPageIndex(1))
    }

    @Test fun goToPageCentersTheWholeRow() {
        val st = state(4, ViewingMode.DOUBLE)
        st.zoom = 1.0
        st.goToPage(2)
        val rowCenter = (st.pageRects[2].left + st.pageRects[3].right) / 2.0
        assertEquals((rowCenter - st.viewportW / 2.0).coerceIn(0.0, st.maxScrollX()), st.scrollX, 1e-6)
        val expectedY = st.pageRects[2].top - CanvasState.TOP_GAP
        assertEquals(expectedY.coerceIn(0.0, st.maxScrollY()), st.scrollY, 1e-6)
    }

    @Test fun visiblePageRangeSpansTheVisibleRows() {
        val st = state(6, ViewingMode.DOUBLE)
        st.zoom = st.fitWidthZoom()
        st.scrollX = 0.0
        st.scrollY = 0.0
        val vis = st.visiblePageRange()!!
        assertEquals(0, vis.first)
        assertTrue(vis.last >= 1) // both pages of the top spread are in the visible band
    }

    @Test fun documentEndVisibleUsesTheLastRowBottom() {
        val st = state(4, ViewingMode.DOUBLE)
        st.zoom = 1.0
        st.scrollY = st.maxScrollY()
        assertTrue(st.isDocumentEndVisible())
        st.scrollY = 0.0
        st.zoom = 1.0
        assertEquals(false, st.isDocumentEndVisible())
    }

    @Test fun verticalFreeMovementAllowsAllEdgesAndCornersToReachViewportCenter() {
        val st = state(2, ViewingMode.SINGLE)
        st.freeMovement = true
        st.insetLeft = 60.0
        st.insetTop = 100.0
        st.fitWidth()
        val first = st.pageRects.first()
        val last = st.pageRects.last()

        st.scrollBy(-1e6, -1e6)
        assertEquals(st.clearCenter().x, st.contentToViewport(com.xnotes.core.geometry.Pt(first.left, first.top)).x, 1e-6)
        assertEquals(st.clearCenter().y, st.contentToViewport(com.xnotes.core.geometry.Pt(first.left, first.top)).y, 1e-6)

        st.scrollBy(1e6, 1e6)
        assertEquals(st.clearCenter().x, st.contentToViewport(com.xnotes.core.geometry.Pt(last.right, last.bottom)).x, 1e-6)
        assertEquals(st.clearCenter().y, st.contentToViewport(com.xnotes.core.geometry.Pt(last.right, last.bottom)).y, 1e-6)
    }
}
