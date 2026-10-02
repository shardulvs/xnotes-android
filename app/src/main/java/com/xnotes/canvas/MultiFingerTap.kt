package com.xnotes.canvas

import android.view.MotionEvent

/**
 * Recognizes a clean two- or three-finger tap (finger-only, brief, near-stationary) for the
 * configurable gesture actions. Both canvases' views feed it, so the gesture cannot drift apart.
 *
 * It never swallows a gesture mid-flight, so pinch-zoom keeps working: it only names the terminal
 * UP of a recognized tap, and the view then cancels the (already-ended) pinch and consumes that UP.
 */
class MultiFingerTap {
    private var downMs = 0L
    private var maxPointers = 0
    private val downX = HashMap<Int, Float>()
    private val downY = HashMap<Int, Float>()
    private var moved = false
    private var allFingers = true

    /** Feed every touch event; returns 2 or 3 when [e] ends a clean tap of that many fingers, else 0. */
    fun tapEndedBy(e: MotionEvent): Int {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downMs = e.eventTime
                maxPointers = 1
                downX.clear(); downY.clear()
                moved = false
                allFingers = e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                recordDown(e, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (e.getToolType(e.actionIndex) != MotionEvent.TOOL_TYPE_FINGER) allFingers = false
                recordDown(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> if (!moved) {
                for (i in 0 until e.pointerCount) {
                    val dx = e.getX(i) - (downX[e.getPointerId(i)] ?: e.getX(i))
                    val dy = e.getY(i) - (downY[e.getPointerId(i)] ?: e.getY(i))
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > TAP_SLOP) { moved = true; break }
                }
            }
            MotionEvent.ACTION_UP -> {
                val quick = e.eventTime - downMs <= TAP_TIMEOUT_MS
                if (maxPointers in 2..3 && quick && !moved && allFingers) return maxPointers
            }
            MotionEvent.ACTION_CANCEL -> moved = true
        }
        return 0
    }

    private fun recordDown(e: MotionEvent, index: Int) {
        downX[e.getPointerId(index)] = e.getX(index)
        downY[e.getPointerId(index)] = e.getY(index)
    }

    private companion object {
        /** Max gesture duration (ms) still counted as a tap. */
        const val TAP_TIMEOUT_MS = 500L

        /** Max distance (viewport px) any finger may wander and still tap. */
        const val TAP_SLOP = 40.0
    }
}
