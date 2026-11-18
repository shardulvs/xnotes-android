package com.xnotes.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** Shared stylus events for regressions; gesture timestamps remain caller-controlled. */
internal object CanvasTextTestInput {
    fun pointer(editor: InfiniteEditor, action: Int, x: Float, y: Float, down: Long, at: Long) {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            down, at, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
        )
        try { editor.interaction.onTouch(event) } finally { event.recycle() }
    }

    fun tap(
        editor: InfiniteEditor, x: Float, y: Float,
        down: Long = SystemClock.uptimeMillis(), duration: Long = 30L,
    ) {
        pointer(editor, MotionEvent.ACTION_DOWN, x, y, down, down)
        pointer(editor, MotionEvent.ACTION_UP, x, y, down, down + duration)
    }
}
