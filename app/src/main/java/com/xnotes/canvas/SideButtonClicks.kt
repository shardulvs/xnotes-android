package com.xnotes.canvas

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt

/**
 * The view half of the side-button click, shared by the paged and the infinite canvas views: it
 * reports the button's press and release off the hover stream to [ButtonClick], and holds back the
 * touch that closes the pen menu so it leaves no ink.
 *
 * The S Pen and most other pens send no key for the side button. A hovering event's `buttonState`
 * (and ACTION_BUTTON_PRESS/RELEASE on the generic-motion stream) is all there is, so a click shows
 * up as that state going down and back up with the tip off the screen. The press and release go to
 * the same [StylusButtonLatch.clicks] as a key's, which tells the click from a hold and makes a
 * hovering hold wait it out.
 */
class SideButtonClicks(private val clicks: ButtonClick = StylusButtonLatch.clicks) {
    /** A click with the pen over the page, at the viewport point where the button went down. */
    var onClick: ((Pt) -> Unit)? = null

    /** Whether a click runs anything; while not, presses are not reported, as on the key route. */
    var clicking: () -> Boolean = { false }

    /**
     * A touch is starting on the page. True when a menu that leaves the page live took it (the pen
     * menu, which it closes): the whole touch is then the menu's, so it draws nothing.
     */
    var touchClosesMenu: (() -> Boolean)? = null

    /** The button as this view's hover stream last reported it. */
    private var held = false

    /** Where the pen was when a reported press went down. */
    private var pressedAt: Pt? = null

    /** The touch under way went to a menu (see [touchClosesMenu]). */
    private var touchForMenu = false

    /** Every touch event, before the view handles it. True when the view should drop it. */
    fun onTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) touchForMenu = touchClosesMenu?.invoke() == true
        if (!touchForMenu) return false
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) touchForMenu = false
        return true
    }

    /** A hover or generic-motion event, which carries the button's state for pens without a key. */
    fun onMotion(e: MotionEvent) {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        // The pen leaving the glass says nothing of the button: it may be on its way down to write.
        if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) return
        val down = (e.buttonState and InteractionController.STYLUS_BUTTON_MASK) != 0
        button(down, e.eventTime, Pt(e.x.toDouble(), e.y.toDouble()))?.let { onClick?.invoke(it) }
    }

    /** The button is [down] or up at [time], with the pen at [pen]. Returns where it went down, if this lets go of a click. */
    internal fun button(down: Boolean, time: Long, pen: Pt): Pt? {
        if (down == held) return null
        held = down
        if (down) {
            if (clicking()) {
                clicks.onPress(time)
                pressedAt = pen
            }
            return null
        }
        val at = pressedAt ?: pen
        pressedAt = null
        return at.takeIf { clicks.onRelease(time) }
    }
}
