package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the per-document initial view: opening a document never inherits the previous
 *  one's zoom/scroll, and a remembered view is reapplied even under a zoom lock. */
class InitialViewTest {

    private fun state(pages: Int): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.forAppearance(true, Rgba(0, 230, 118))).apply {
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    @Test fun restoreReappliesViewEvenWhenZoomLocked() {
        val st = state(pages = 3)
        st.zoomLocked = true // fitWidth would no-op here — the bug that leaked the last view
        st.pendingInitialView = InitialView.Restore(zoom = 2.5, scrollX = 0.0, scrollY = 4000.0)
        st.establishInitialView()
        assertEquals(2.5, st.zoom, 1e-9)
        assertEquals(4000.0.coerceIn(0.0, st.maxScrollY()), st.scrollY, 1e-6)
        assertTrue(st.didInitialFit)
    }

    @Test fun openingAnotherDocumentDoesNotInheritTheView() {
        val st = state(pages = 5)
        st.pendingInitialView = InitialView.Restore(zoom = 4.0, scrollX = 0.0, scrollY = 9000.0)
        st.establishInitialView()

        // Open a fresh document with no remembered view: must fit width at the top, not keep 4.0×.
        st.document = Document.blank(2)
        st.relayout()
        st.pendingInitialView = InitialView.FitWidth
        st.didInitialFit = false
        st.establishInitialView()

        val fitWidth = (st.viewportW / st.contentW).coerceIn(CanvasState.MIN_ZOOM, CanvasState.MAX_ZOOM)
        assertEquals(fitWidth, st.zoom, 1e-9)
        assertNotEquals(4.0, st.zoom, 1e-6)
    }

    @Test fun setViewIgnoresZoomLock() {
        val st = state(pages = 1)
        st.zoomLocked = true
        st.setView(1.7, 0.0, 50.0)
        assertEquals(1.7, st.zoom, 1e-9)
    }
}
