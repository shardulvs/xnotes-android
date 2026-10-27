package com.xnotes.canvas

import android.view.MotionEvent
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A touch Android takes back, a resting palm say, leaves the view as it found it. */
class TouchTakenBackTest {

    private fun state(): CanvasState =
        CanvasState(Document.blank(3), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    @Test
    fun aCancelledLiftCountsFromAndroid13() {
        assertTrue(InteractionController.isCancelledLift(MotionEvent.FLAG_CANCELED, 33))
        assertFalse(InteractionController.isCancelledLift(0, 33))
        assertFalse(InteractionController.isCancelledLift(MotionEvent.FLAG_CANCELED, 32))
    }

    @Test
    fun restoringPutsBackAPinchsZoomScrollAndFit() {
        val st = state()
        st.zoom = st.fitWidthZoom()
        st.fitWidthActive = true
        st.scrollY = 300.0
        st.clampScroll()
        val before = st.snapshotView()

        st.zoom = st.snapZoomToFit(st.fitWidthZoom() * 1.6)
        st.scrollX = 120.0
        st.scrollY = 900.0
        assertFalse(st.fitWidthActive)
        st.restoreView(before)

        assertEquals(before.zoom, st.zoom, 1e-9)
        assertEquals(before.scrollX, st.scrollX, 1e-9)
        assertEquals(300.0, st.scrollY, 1e-9)
        assertTrue(st.fitWidthActive)
    }
}
