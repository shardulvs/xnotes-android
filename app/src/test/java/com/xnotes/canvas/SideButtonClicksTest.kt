package com.xnotes.canvas

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A side button reported only on the hover stream (the S Pen), clicked or held. */
class SideButtonClicksTest {

    private val click = ButtonClick { 400L }
    private val side = SideButtonClicks(click).apply { clicking = { true } }
    private val pen = Pt(120.0, 300.0)

    @Test
    fun aQuickPressAndReleaseIsAClickWhereTheButtonWentDown() {
        assertNull(side.button(true, 1_000, pen))
        assertEquals(pen, side.button(false, 1_150, Pt(130.0, 310.0)))
    }

    @Test
    fun hoverEventsWhileHeldAreOnePress() {
        side.button(true, 1_000, pen)
        assertNull(side.button(true, 1_200, pen))
        assertNull(side.button(true, 1_300, pen))
        assertEquals(pen, side.button(false, 1_350, pen))
    }

    @Test
    fun aPressHeldThroughTheTimeoutIsAHold() {
        side.button(true, 1_000, pen)
        assertNull(side.button(false, 1_450, pen))
    }

    @Test
    fun writingWithTheButtonHeldIsAHold() {
        side.button(true, 1_000, pen)
        click.onTouch(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS)
        click.onTouch(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS)
        assertNull(side.button(false, 1_200, pen))
    }

    @Test
    fun withNoTapActionPressesAreNotReported() {
        side.clicking = { false }
        side.button(true, 1_000, pen)
        // A hovering hold (eraser, pan) need not wait for a click that cannot come.
        assertFalse(click.mayStillClick(1_100))
        assertNull(side.button(false, 1_100, pen))
    }

    @Test
    fun aReportedPressMakesAHoveringHoldWait() {
        side.button(true, 1_000, pen)
        assertTrue(click.mayStillClick(1_100))
        assertFalse(click.mayStillClick(1_400))
    }
}
