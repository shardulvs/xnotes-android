package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeHorizontalScrollTest {

    private fun state(): CanvasState =
        CanvasState(Document.blank(1), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000
            viewportH = 1400
            relayout()
            zoom = 0.5
            freeHorizontalScroll = true
        }

    private fun pageRightOnScreen(st: CanvasState) =
        st.contentToViewport(Pt(st.contentW - st.sideMargin, 0.0)).x

    private fun pageLeftOnScreen(st: CanvasState) =
        st.contentToViewport(Pt(st.sideMargin, 0.0)).x

    @Test fun pageStopsWithItsRightEdgeStillOnScreen() {
        val st = state()
        st.scrollBy(1e6, 0.0)
        assertEquals(CanvasState.FREE_SCROLL_EDGE_DP, pageRightOnScreen(st), 1e-6)
    }

    @Test fun pageStopsWithItsLeftEdgeStillOnScreen() {
        val st = state()
        st.scrollBy(-1e6, 0.0)
        assertEquals(1000 - CanvasState.FREE_SCROLL_EDGE_DP, pageLeftOnScreen(st), 1e-6)
    }
}
