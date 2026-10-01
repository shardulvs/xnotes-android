package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplineTapTrackerTest {

    private val a = Any()
    private val b = Any()

    @Test fun twoQuickTapsOnOneSpotPair() {
        val t = SplineTapTracker()
        assertFalse(t.tapEmpty(a, Pt(10.0, 10.0), 5.0, 0, 80))
        assertTrue(t.tapEmpty(a, Pt(12.0, 11.0), 5.0, 200, 280))
    }

    @Test fun aPairedTapStartsOver() {
        val t = SplineTapTracker()
        t.tapEmpty(a, Pt.ZERO, 5.0, 0, 80)
        assertTrue(t.tapEmpty(a, Pt.ZERO, 5.0, 200, 280))
        assertFalse(t.tapEmpty(a, Pt.ZERO, 5.0, 400, 480))
    }

    @Test fun tapsFarApartOrSlowDoNotPair() {
        val t = SplineTapTracker()
        t.tapEmpty(a, Pt.ZERO, 5.0, 0, 80)
        assertFalse(t.tapEmpty(a, Pt(20.0, 0.0), 5.0, 200, 280))
        assertFalse(t.tapEmpty(a, Pt(20.0, 0.0), 5.0, 280 + InteractionController.DOUBLE_TAP_MS + 1, 1000))
    }

    @Test fun tapsOnDifferentCurvesDoNotPair() {
        val t = SplineTapTracker()
        t.tapEmpty(a, Pt.ZERO, 5.0, 0, 80)
        assertFalse(t.tapEmpty(b, Pt.ZERO, 5.0, 200, 280))
        t.tapPoint(a, 1, 400, 480)
        assertFalse(t.tapPoint(b, 1, 600, 680))
    }

    @Test fun pointAndEmptyTapsDoNotPairWithEachOther() {
        val t = SplineTapTracker()
        t.tapPoint(a, 1, 0, 80)
        assertFalse(t.tapEmpty(a, Pt.ZERO, 5.0, 200, 280))
        assertFalse(t.tapPoint(a, 1, 400, 480))
        assertTrue(t.tapPoint(a, 1, 600, 680))
        t.tapPoint(a, 1, 800, 880)
        assertFalse(t.tapPoint(a, 2, 1000, 1080))
    }

    @Test fun resetForgetsTheFirstTap() {
        val t = SplineTapTracker()
        t.tapEmpty(a, Pt.ZERO, 5.0, 0, 80)
        t.reset()
        assertFalse(t.tapEmpty(a, Pt.ZERO, 5.0, 200, 280))
    }

    @Test fun aLongPressIsNotATap() {
        val t = SplineTapTracker()
        assertTrue(t.isTap(0, InteractionController.TAP_MAX_MS))
        assertFalse(t.isTap(0, InteractionController.TAP_MAX_MS + 1))
    }
}
