package com.xnotes.canvas

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A click of a side button that can also be held, told from a hold (OnePlus Pad Go 2 Stylo). */
class ButtonClickTest {

    private val click = ButtonClick { 400L }

    private fun pen(action: Int) = click.onTouch(action, MotionEvent.TOOL_TYPE_STYLUS)

    @Test
    fun aQuickPressWithThePenOffTheScreenIsAClick() {
        click.onPress(1_000)
        assertTrue(click.onRelease(1_120))
    }

    @Test
    fun aPressHeldThroughTheTimeoutIsAHold() {
        click.onPress(1_000)
        assertFalse(click.onRelease(1_400))
    }

    @Test
    fun aTouchDuringThePressMakesItAHold() {
        click.onPress(1_000)
        pen(MotionEvent.ACTION_DOWN)
        pen(MotionEvent.ACTION_UP)
        assertFalse(click.onRelease(1_200))
    }

    @Test
    fun aPressWithThePenOnTheScreenIsAHold() {
        pen(MotionEvent.ACTION_DOWN)
        click.onPress(1_000)
        assertFalse(click.onRelease(1_100))
    }

    @Test
    fun aPressAfterThePenLiftsCanClick() {
        pen(MotionEvent.ACTION_DOWN)
        pen(MotionEvent.ACTION_UP)
        click.onPress(1_000)
        assertTrue(click.onRelease(1_100))
    }

    @Test
    fun aFingerDoesNotSpoilAClick() {
        click.onPress(1_000)
        click.onTouch(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER)
        assertTrue(click.onRelease(1_100))
    }

    @Test
    fun aHoveringHoldWaitsOnlyWhileThePressMayStillClick() {
        assertFalse(click.mayStillClick(1_000))
        click.onPress(1_000)
        assertTrue(click.mayStillClick(1_399))
        assertFalse(click.mayStillClick(1_400))
        pen(MotionEvent.ACTION_DOWN)
        assertFalse(click.mayStillClick(1_100))
    }

    @Test
    fun anUntrackedOrCancelledPressNeverClicks() {
        // With the tap action at None the editor never reports the press.
        assertFalse(click.onRelease(1_100))
        click.onPress(1_000)
        click.cancel()
        assertFalse(click.onRelease(1_100))
    }
}
