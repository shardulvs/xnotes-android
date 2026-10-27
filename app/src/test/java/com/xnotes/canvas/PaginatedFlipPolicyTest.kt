package com.xnotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatedFlipPolicyTest {
    @Test fun narrowScreenRequiresMinimumPhysicalDrag() {
        assertEquals(150.0, PaginatedFlipPolicy.threshold(900, 2.0), 1e-9)
        assertFalse(PaginatedFlipPolicy.shouldFlip(149.0, 900, 2.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(150.0, 900, 2.0))
    }

    @Test fun tabletThresholdScalesWithViewport() {
        assertEquals(230.4, PaginatedFlipPolicy.threshold(2560, 2.0), 1e-9)
        assertFalse(PaginatedFlipPolicy.shouldFlip(-229.0, 2560, 2.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(-231.0, 2560, 2.0))
    }

    @Test fun highZoomSlightlyRaisesFlipThreshold() {
        val base = PaginatedFlipPolicy.threshold(1600, 2.0, 1.0)
        assertEquals(150.0, base, 1e-9)
        assertEquals(base, PaginatedFlipPolicy.threshold(1600, 2.0, 1.25), 1e-9)
        assertEquals(base * 1.10, PaginatedFlipPolicy.threshold(1600, 2.0, 1.875), 1e-9)
        assertEquals(base * 1.20, PaginatedFlipPolicy.threshold(1600, 2.0, 2.5), 1e-9)
        assertEquals(base * 1.20, PaginatedFlipPolicy.threshold(1600, 2.0, 4.0), 1e-9)
    }

    @Test fun zoomAwareReleaseUsesSameThreshold() {
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(160.0, 1600, 2.0, -80.0, 0.0, 1.0))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(160.0, 1600, 2.0, -80.0, 0.0, 2.5))
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(185.0, 1600, 2.0, -80.0, 0.0, 2.5))
    }

    @Test fun addPageRequiresMoreTravelThanOrdinaryNavigation() {
        assertEquals(220.0, PaginatedFlipPolicy.addPageThreshold(1000, 2.0), 1e-9)
        assertEquals(563.2, PaginatedFlipPolicy.addPageThreshold(2560, 2.0), 1e-9)
        assertTrue(PaginatedFlipPolicy.addPageThreshold(2560, 2.0) >
            PaginatedFlipPolicy.threshold(2560, 2.0))
    }

    @Test fun fastOutwardFlickRequiresMeaningfulEdgeTravel() {
        val normal = PaginatedFlipPolicy.threshold(1600, 2.0) // 150 px
        assertFalse(PaginatedFlipPolicy.shouldFlip(0.0, 1600, 2.0, -4000.0))
        assertFalse(PaginatedFlipPolicy.shouldFlip(normal * 0.5, 1600, 2.0, -4000.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(normal * 0.56, 1600, 2.0, -1800.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(-normal * 0.56, 1600, 2.0, 1800.0))
    }

    @Test fun slowWrongWayAndDiagonalFlicksDoNotFlip() {
        val pull = 105.0
        assertFalse(PaginatedFlipPolicy.shouldFlip(pull, 1600, 2.0, -1000.0))
        assertFalse(PaginatedFlipPolicy.shouldFlip(pull, 1600, 2.0, 1800.0))
        assertFalse(PaginatedFlipPolicy.shouldFlip(pull, 1600, 2.0, -2000.0, 1700.0))
        assertFalse(PaginatedFlipPolicy.shouldFlip(0.0, 1600, 2.0, -4000.0))
    }

    @Test fun aHeldPullPastTheThresholdStillTurnsThePage() {
        // A stopped finger reaches the policy with zero velocity, however long it was held.
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(850.0, 1600, 2.0, 0.0, 0.0))
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(-150.0, 1600, 2.0, 0.0, 0.0))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(149.0, 1600, 2.0, 0.0, 0.0))
    }

    @Test fun continuousOutwardReleaseCommitsAfterDeliberatePull() {
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(260.0, 1600, 2.0, -500.0, 30.0))
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(-260.0, 1600, 2.0, 500.0, 30.0))
    }

    @Test fun naturalReleaseWorksAfterDeceleration() {
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(260.0, 1600, 2.0, -45.0, 12.0))
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(-260.0, 1600, 2.0, 0.0, 0.0))
    }

    @Test fun onlyAClearFlickBackCancelsAPull() {
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(260.0, 1600, 2.0, 120.0, 0.0)) // a lift-off twitch
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(260.0, 1600, 2.0, 650.0, 0.0))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(35.0, 1600, 2.0, 0.0, 0.0))
    }

    @Test fun reverseOrMostlyVerticalReleaseDoesNotCommit() {
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(600.0, 1600, 2.0, 400.0, 0.0))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(600.0, 1600, 2.0, -400.0, 500.0))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(35.0, 1600, 2.0, -4000.0, 0.0))
    }

    @Test fun retractingUndoesAStretchAtTheRateItWasBuilt() {
        val gain = 0.48
        val pull = -200.0 * gain // dragged 200 px past the first page's empty side
        val (back, rest) = PaginatedFlipPolicy.retract(pull, 200.0, gain)
        assertEquals(0.0, back, 1e-9)
        assertEquals(0.0, rest, 1e-9) // nothing left to scroll the page or pull the next one in
        val (half, none) = PaginatedFlipPolicy.retract(pull, 100.0, gain)
        assertEquals(pull / 2, half, 1e-9)
        assertEquals(0.0, none, 1e-9)
        val (zero, beyond) = PaginatedFlipPolicy.retract(pull, 300.0, gain)
        assertEquals(0.0, zero, 1e-9)
        assertEquals(100.0, beyond, 1e-9) // only travel past the start carries on
    }

    @Test fun fullDistanceStillWorksWithoutFlick() {
        assertTrue(PaginatedFlipPolicy.shouldFlip(225.0, 1600, 2.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(-225.0, 1600, 2.0))
    }
}
