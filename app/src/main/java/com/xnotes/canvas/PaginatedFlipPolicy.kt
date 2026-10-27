package com.xnotes.canvas

import kotlin.math.abs
import kotlin.math.max

/**
 * Rules for intentional paginated navigation. Physical swipes must travel a meaningful
 * distance beyond the current page edge. A fast, directionally consistent flick can
 * shorten that distance, but speed alone never turns a page.
 *
 * This policy is used only in paginated mode, not continuous vertical scrolling.
 */
internal object PaginatedFlipPolicy {
    const val MIN_PULL_DP = 75.0
    const val VIEWPORT_FRACTION = 0.09
    private const val FAST_PULL_FRACTION = 0.55
    private const val FAST_FLICK_DP_PER_SECOND = 850.0
    private const val REVERSE_VETO_DP_PER_SECOND = 170.0
    /** A flick counts only if the finger was still moving this recently when it lifted. */
    const val RELEASE_MOTION_MAX_AGE_MS = 180L
    /** Creating a new page requires a longer pull than navigating to an existing one. */
    const val ADD_PAGE_VIEWPORT_FRACTION = 0.22

    fun addPageThreshold(viewportWidthPx: Int, density: Double): Double =
        max(threshold(viewportWidthPx, density), viewportWidthPx * ADD_PAGE_VIEWPORT_FRACTION)


    /**
     * Zoomed-in pages need a slightly more deliberate edge gesture because users pan
     * horizontally more often. Keep the familiar light feel up to 125%, then increase
     * the distance smoothly by at most 20% at 250% zoom.
     */
    fun threshold(viewportWidthPx: Int, density: Double, zoom: Double = 1.0): Double {
        val zoomFactor = 1.0 + ((zoom - 1.25) / 1.25).coerceIn(0.0, 1.0) * 0.20
        return max(MIN_PULL_DP * density.coerceAtLeast(1.0), viewportWidthPx * VIEWPORT_FRACTION) * zoomFactor
    }

    /** A pull past the threshold turns the page unless the finger is flicking back or moving mostly vertically as it lifts. */
    fun shouldCommitOnRelease(
        pullPx: Double,
        viewportWidthPx: Int,
        density: Double,
        fingerVelocityX: Double,
        fingerVelocityY: Double,
        zoom: Double = 1.0,
    ): Boolean {
        val scale = density.coerceAtLeast(1.0)
        val clearlyReversing = fingerVelocityX * pullPx > 0.0 &&
            abs(fingerVelocityX) >= REVERSE_VETO_DP_PER_SECOND * scale
        val predominantlyVertical = abs(fingerVelocityY) >= 170.0 * scale &&
            abs(fingerVelocityY) > abs(fingerVelocityX) * 1.2
        if (clearlyReversing || predominantlyVertical) return false
        return shouldFlip(pullPx, viewportWidthPx, density, fingerVelocityX, fingerVelocityY, zoom)
    }

    fun shouldFlip(
        pullPx: Double,
        viewportWidthPx: Int,
        density: Double,
        fingerVelocityX: Double = 0.0,
        fingerVelocityY: Double = 0.0,
        zoom: Double = 1.0,
    ): Boolean {
        val normal = threshold(viewportWidthPx, density, zoom)
        if (abs(pullPx) >= normal) return true
        // Pull direction is the opposite of the finger velocity. Require horizontal
        // dominance and enough actual edge travel so a fast pan never flips unexpectedly.
        val outward = pullPx * fingerVelocityX < 0.0
        val horizontalFlick = abs(fingerVelocityX) >= FAST_FLICK_DP_PER_SECOND * density.coerceAtLeast(1.0) &&
            abs(fingerVelocityX) > abs(fingerVelocityY) * 1.5
        return outward && horizontalFlick && abs(pullPx) >= normal * FAST_PULL_FRACTION
    }

    /** Unwinds [pull] by the scroll [dx] at the [gain] it was built with; returns the new pull and the scroll left over. */
    fun retract(pull: Double, dx: Double, gain: Double): Pair<Double, Double> {
        val relaxed = pull + dx * gain
        return if (relaxed * pull <= 0.0) 0.0 to dx + pull / gain else relaxed to 0.0
    }
}
