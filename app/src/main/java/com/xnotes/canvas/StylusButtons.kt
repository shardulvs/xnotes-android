package com.xnotes.canvas

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Whether a stylus side button is currently down.
 *
 * Pens do not agree on how to report this, and getting it right took several releases of
 * device-specific work, so both canvases latch it through this rather than each working it out.
 * Three delivery routes are covered:
 *
 *  - the touch stream's `buttonState`, which most pens use;
 *  - the hovering generic-motion stream, for pens that report the button only while hovering and
 *    never in a touch event;
 *  - a `KeyEvent`, which Bluetooth and USI pens use, including one vendor code with no standard
 *    mapping ([InteractionController.VENDOR_HELD_BUTTON_KEYCODE]).
 *
 * The last two are latched apart and either one holds the button: a pen that sends the key can
 * hover with an empty `buttonState` while it is down (the OnePlus Pad Go 2 Stylo does), and that
 * must not let go of the key.
 *
 * A fourth quirk is handled higher up and needs nothing here: some Samsung builds tag a
 * button-held stroke with proprietary action codes instead of DOWN/MOVE/UP, and `MainActivity`
 * rewrites those before dispatch, so any view in the tree gets a normal touch stream.
 *
 * The button masks and the vendor keycode are read from [InteractionController] rather than copied,
 * so the two canvases cannot drift apart on which buttons count.
 */
class StylusButtonLatch {

    private var motionHeld = false

    /** True while a side button is held, from whichever stream reported it. */
    val held: Boolean
        get() = keyHeld || motionHeld

    /**
     * Latch the button off the generic-motion (hover) stream. Returns true when a stylus event
     * leaves the button up, so a caller can end a hovering gesture.
     */
    fun onGenericMotion(e: MotionEvent): Boolean =
        e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && onStylusMotion(e.buttonState)

    internal fun onStylusMotion(buttonState: Int): Boolean {
        motionHeld = (buttonState and InteractionController.STYLUS_BUTTON_MASK) != 0
        return !held
    }

    /**
     * Latch the button off a key event. Returns true when [keyCode] was a stylus side button, so
     * the host consumes it instead of letting it fall through as a normal key.
     */
    fun onKey(keyCode: Int, down: Boolean): Boolean {
        if (!isStylusButtonKey(keyCode)) return false
        keyHeld = down
        return true
    }

    /** Forget what the hover stream said; a held key belongs to the pen and its key-up clears it. */
    fun reset() {
        motionHeld = false
    }

    /** Whether [e] is a stylus contact with the side button down, on either route. */
    fun heldFor(e: MotionEvent): Boolean =
        e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && heldWith(e.buttonState)

    internal fun heldWith(buttonState: Int): Boolean =
        (buttonState and InteractionController.STYLUS_BUTTON_MASK) != 0 || held

    companion object {
        /** One pen, so one key state for every latch: its key-up can reach another pane than its key-down. */
        private var keyHeld = false

        /** The same pen's button clicks, kept once for the same reason; see [ButtonClick]. */
        val clicks = ButtonClick { ViewConfiguration.getLongPressTimeout().toLong() }

        /** Let go of a held key whose key-up may never arrive, as when the window loses focus. */
        fun releaseKey() {
            keyHeld = false
            clicks.cancel()
        }

        fun isStylusButtonKey(keyCode: Int): Boolean =
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
                keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
                keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY ||
                keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL ||
                keyCode == InteractionController.VENDOR_HELD_BUTTON_KEYCODE
    }
}

/**
 * Tells a click of a side button from a hold, for a button whose hardware reports both when it
 * goes down and when it comes up (the OnePlus Pad Go 2 Stylo's F21 does).
 *
 * Such a button can be held and clicked, so the user gets both: held while the pen touches the
 * screen, it is the side button's hold tool through [StylusButtonLatch]; clicked, it runs the
 * "Stylus side button (tap)" action. A pen whose button reports only clicks (Honor, Lenovo,
 * Redmi) or only a hold keeps the one it has, and another pen with both joins by adding its key
 * to the editor's click list.
 *
 * A click is let go within the long-press timeout with the pen tip off the screen throughout, so
 * the hold never acted on it. A hold that works without touching (hovering) waits out that window
 * through [mayStillClick], so a click never erases under a hovering pen.
 */
class ButtonClick(private val timeoutMs: () -> Long) {

    private var pressedAt = NONE
    private var touched = false
    private var touching = false

    /** The button went down at [at] (uptime ms). */
    fun onPress(at: Long) {
        pressedAt = at
        touched = touching
    }

    /** The button came up at [at]; true when the press was a click. */
    fun onRelease(at: Long): Boolean {
        val click = mayStillClick(at)
        pressedAt = NONE
        return click
    }

    /** Drop the press in progress, so its release cannot click. */
    fun cancel() {
        pressedAt = NONE
    }

    /** Whether the press in progress may still turn out a click at [at], so a hold must not act on it yet. */
    fun mayStillClick(at: Long): Boolean = pressedAt != NONE && !touched && at - pressedAt < timeoutMs()

    /** Follow the pen tip on and off the screen, from every touch the window gets. */
    fun onTouch(e: MotionEvent) = onTouch(e.actionMasked, e.getToolType(e.actionIndex))

    internal fun onTouch(action: Int, toolType: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                touching = true
                touched = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (toolType == MotionEvent.TOOL_TYPE_STYLUS) touching = false
            MotionEvent.ACTION_CANCEL -> touching = false
        }
    }

    private companion object {
        const val NONE = -1L
    }
}
