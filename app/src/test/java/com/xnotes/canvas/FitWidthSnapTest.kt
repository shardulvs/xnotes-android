package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards directional fit snapping and resize re-fit without trapping zoom-in gestures. */
class FitWidthSnapTest {

    private fun state(pages: Int = 1, viewportW: Int = 1000): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            this.viewportW = viewportW
            viewportH = 1400
            relayout()
        }

    @Test fun zoomWithinBandSticksToFitWidth() {
        val st = state()
        val fit = st.fitWidthZoom()
        assertFalse(st.fitWidthActive)

        val z = st.snapZoomToFit(fit * 1.03) // inside the ±5% band
        assertEquals(fit, z, 1e-9) // stuck exactly to fit-width
        assertTrue(st.fitWidthActive)
    }

    @Test fun zoomOutsideBandPassesThroughAndClearsFit() {
        val st = state()
        val fit = st.fitWidthZoom()
        st.fitWidthActive = true // pretend we were stuck; leaving the band must release

        val z = st.snapZoomToFit(fit * 1.3)
        assertEquals(fit * 1.3, z, 1e-9) // raw zoom passes through untouched
        assertFalse(st.fitWidthActive)
    }

    @Test fun zoomingInTowardFitWidthDoesNotSnapOrClampToViewport() {
        val st = state()
        st.freeMovement = true
        st.verticalScroll = false
        st.relayout()
        val fit = st.fitWidthZoom()
        st.zoom = fit * 0.8
        val raw = fit * 0.99

        val z = st.snapZoomToFit(raw)

        assertEquals(raw, z, 1e-9)
        assertFalse(st.fitWidthActive)
    }

    @Test fun freeMovementCanBeTurnedOnWithoutAffectingVerticalMode() {
        val st = state()
        st.verticalScroll = false
        st.relayout()
        st.freeMovement = true
        val fit = st.fitHeightZoom()
        st.zoom = fit * 1.3
        val raw = fit * 1.02
        assertEquals(raw, st.snapZoomToFit(raw), 1e-9)
        assertFalse(st.fitHeightActive)
        assertFalse(st.fitWidthActive)

        st.verticalScroll = true
        st.relayout()
        val verticalFit = st.fitWidthZoom()
        assertEquals(verticalFit, st.snapZoomToFit(verticalFit * 1.01), 1e-9)
        assertTrue(st.fitWidthActive)
    }

    @Test fun freeMovementCanBeTurnedOffAgain() {
        val st = state()
        st.verticalScroll = false
        st.relayout()
        st.freeMovement = true
        st.freeMovement = false
        val fit = st.fitHeightZoom()
        st.zoom = fit * 1.3
        assertEquals(fit, st.snapZoomToFit(fit * 1.02), 1e-9)
    }

    @Test fun classicModeRestoresOriginalZoomInMagnetAndFreeModeDoesNot() {
        val st = state()
        st.freeMovement = true
        st.verticalScroll = false
        st.relayout()
        val fit = st.fitHeightZoom()
        st.zoom = fit * 0.8
        val raw = fit * 0.99
        assertEquals(raw, st.snapZoomToFit(raw), 1e-9)
        st.freeMovement = false
        val snapped = st.snapZoomToFit(raw)
        assertTrue(st.fitHeightActive || st.fitWidthActive)
        assertTrue(snapped == st.fitHeightZoom() || snapped == st.fitWidthZoom())
    }

    @Test fun verticalModeRetainsOriginalFitMagnetWhenZoomingIn() {
        val st = state()
        assertTrue(st.verticalScroll)
        val fit = st.fitWidthZoom()
        st.zoom = fit * 0.8
        val z = st.snapZoomToFit(fit * 0.99)
        assertEquals(fit, z, 1e-9)
        assertTrue(st.fitWidthActive)
    }

    @Test fun zoomingInTowardFitHeightDoesNotSnapOrClampToViewport() {
        val st = state()
        st.freeMovement = true
        st.verticalScroll = false
        st.relayout()
        val fit = st.fitHeightZoom()
        st.zoom = fit * 0.8
        val raw = fit * 0.99

        val z = st.snapZoomToFit(raw)

        assertEquals(raw, z, 1e-9)
        assertFalse(st.fitHeightActive)
    }

    @Test fun zoomingOutTowardFitHeightStillSnaps() {
        val st = state()
        st.freeMovement = false
        st.verticalScroll = false
        st.relayout()
        val fit = st.fitHeightZoom()
        st.zoom = fit * 1.2

        val z = st.snapZoomToFit(fit * 1.03)

        assertEquals(fit, z, 1e-9)
        assertTrue(st.fitHeightActive)
    }

    @Test fun noHeightMagnetInVerticalMode() {
        val st = state()
        assertEquals(0.0, st.fitHeightZoom(), 1e-9)
        val fitW = st.fitWidthZoom()
        st.snapZoomToFit(fitW)
        assertTrue(st.fitWidthActive)
        assertFalse(st.fitHeightActive)
    }

    @Test fun paginatedPinchSticksToFitHeight() {
        val st = state()
        st.freeMovement = false
        st.verticalScroll = false
        st.relayout()
        val fit = st.fitHeightZoom()
        assertTrue(fit > 0.0)

        val z = st.snapZoomToFit(fit * 1.03) // inside the same ±5% band as fit-width
        assertEquals(fit, z, 1e-9)
        assertTrue(st.fitHeightActive)
        assertFalse(st.fitWidthActive)

        // Pinching past the band releases the magnet like fit-width does.
        val out = st.snapZoomToFit(fit * 1.3)
        assertEquals(fit * 1.3, out, 1e-9)
        assertFalse(st.fitHeightActive)
    }

    @Test fun nearerMagnetWinsWhenBothGrab() {
        val st = state()
        st.freeMovement = false
        st.verticalScroll = false
        st.relayout()
        val fitW = st.fitWidthZoom()
        val fitH = st.fitHeightZoom()
        // A4 portrait in a 1000x1400 viewport: the two targets are distinct but close
        // (fitH sits ~1% above fitW), so approach each from its far side.
        assertTrue(fitW < fitH)
        st.snapZoomToFit(fitH * 1.01)
        assertTrue(st.fitHeightActive)
        st.snapZoomToFit(fitW * 0.99)
        assertTrue(st.fitWidthActive)
        assertFalse(st.fitHeightActive)
    }

    @Test fun resizeRefitsHeightWhileHeightFitActive() {
        val st = state()
        st.freeMovement = false
        st.verticalScroll = false
        st.relayout()
        st.snapZoomToFit(st.fitHeightZoom())
        assertTrue(st.fitHeightActive)

        st.viewportH = 1000
        st.relayout()
        st.reflowFitWidthForResize()
        assertEquals(st.fitHeightZoom(), st.zoom, 1e-9)
    }

    @Test fun resizeRefitsWidthWhileFitActive() {
        val st = state(viewportW = 1000)
        st.fitWidth()
        val wide = st.zoom
        assertTrue(st.fitWidthActive)

        // Sidebar opens: narrower viewport, same content width.
        st.viewportW = 776
        st.relayout()
        st.reflowFitWidthForResize()

        assertEquals(st.fitWidthZoom(), st.zoom, 1e-9)
        assertTrue(st.zoom < wide)
    }

    @Test fun resizeRefitsEvenWhenZoomLocked() {
        val st = state(viewportW = 1000)
        st.fitWidth()
        st.zoomLocked = true // lock freezes user gestures, not the resize re-fit

        st.viewportW = 776
        st.relayout()
        st.reflowFitWidthForResize()

        assertEquals(st.fitWidthZoom(), st.zoom, 1e-9)
    }

    @Test fun heightMagnetFollowsTheMarginPreference() {
        val st = state()
        st.verticalScroll = false
        st.sideMargin = 0.0
        st.relayout()
        val h = st.document.pages[0].height
        assertEquals(st.viewportH / h, st.fitHeightZoom(), 1e-9) // no margin: the page fills the height

        st.sideMargin = 20.0
        st.relayout()
        assertEquals(st.viewportH / (h + 40.0), st.fitHeightZoom(), 1e-9)
    }

    @Test fun zeroMarginFitHeightLeavesNoVerticalGap() {
        val st = state()
        st.verticalScroll = false
        st.sideMargin = 0.0
        st.relayout()
        st.zoom = st.fitHeightZoom()
        st.clampScroll()

        val page = st.pageRects[0]
        assertEquals(0.0, st.contentToViewport(Pt(0.0, page.top)).y, 1e-6)
        assertEquals(st.viewportH.toDouble(), st.contentToViewport(Pt(0.0, page.bottom)).y, 1e-6)
    }

    @Test fun fitPageFollowsTheMarginPreference() {
        val st = state()
        st.verticalScroll = false
        val page = st.document.pages[0]

        st.sideMargin = 0.0
        st.relayout()
        st.fitPage()
        assertEquals(min(st.viewportW / page.width, st.viewportH / page.height), st.zoom, 1e-9)

        st.sideMargin = 20.0
        st.relayout()
        st.fitPage()
        assertEquals(min(st.viewportW / (page.width + 40.0), st.viewportH / (page.height + 40.0)), st.zoom, 1e-9)
    }

    @Test fun resizeIsNoOpWhenNotFitToWidth() {
        val st = state(viewportW = 1000)
        st.zoom = 2.5
        st.fitWidthActive = false

        st.viewportW = 776
        st.relayout()
        st.reflowFitWidthForResize()

        assertEquals(2.5, st.zoom, 1e-9) // arbitrary zoom preserved
    }
}
