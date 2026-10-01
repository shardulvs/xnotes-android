package com.xnotes.canvas

import com.xnotes.core.geometry.Pt

/**
 * Pairs taps on a selected spline into double taps: two on the same control point remove it, and two
 * on the same empty spot add a point there. Taps on different curves never pair. Shared by the paged
 * and infinite canvases, which pass in uptime millis.
 */
class SplineTapTracker {
    private var curve: Any? = null
    private var pointIndex = -1
    private var emptyAt: Pt? = null
    private var lastUpMs = 0L

    /** Whether a press that travelled under the tap slop also lifted quickly enough to be a tap, not a small drag. */
    fun isTap(downMs: Long, upMs: Long): Boolean = upMs - downMs <= InteractionController.TAP_MAX_MS

    /** A tap on control point [index] of [curve]; true when it completes a double tap. */
    fun tapPoint(curve: Any, index: Int, downMs: Long, upMs: Long): Boolean {
        val double = curve === this.curve && index == pointIndex && pairs(downMs)
        if (double) reset() else arm(curve, index, null, upMs)
        return double
    }

    /** A tap at [at] inside [curve] but off its points; true when it completes a double tap within [tolerance]. */
    fun tapEmpty(curve: Any, at: Pt, tolerance: Double, downMs: Long, upMs: Long): Boolean {
        val first = emptyAt
        val double = curve === this.curve && first != null && first.distanceTo(at) <= tolerance && pairs(downMs)
        if (double) reset() else arm(curve, -1, at, upMs)
        return double
    }

    fun reset() {
        curve = null
        pointIndex = -1
        emptyAt = null
    }

    private fun pairs(downMs: Long) = downMs - lastUpMs <= InteractionController.DOUBLE_TAP_MS

    private fun arm(curve: Any, index: Int, at: Pt?, upMs: Long) {
        this.curve = curve
        pointIndex = index
        emptyAt = at
        lastUpMs = upMs
    }
}
