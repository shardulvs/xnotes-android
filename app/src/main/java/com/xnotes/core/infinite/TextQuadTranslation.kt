package com.xnotes.core.infinite

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On a side-handle resize, move the cached text to the box's new anchor without scaling
 * the glyph bitmap. A worker can update line breaks without scaling the texture.
 */
internal fun fixedSizeTextLift(origin: Pt, boxTransform: Affine): LiftTransform {
    val next = boxTransform.apply(origin)
    return LiftTransform.shift(next.x - origin.x, next.y - origin.y)
}

/**
 * For centred text, changing width without changing any line break only translates the glyphs
 * relative to the box centre. Its existing texture is still valid.
 */
internal fun textWrapChanged(previousEnds: IntArray?, nextEnds: IntArray): Boolean =
    previousEnds == null || !previousEnds.contentEquals(nextEnds)

/** A rotation or translation changes only the placement of a text quad, never its glyph layout.
 *  Compare its two edge lengths and their mutual angle; scaling/shearing must rerasterize. */
internal fun isRigidTextQuadMotion(previous: DoubleArray, next: DoubleArray): Boolean {
    if (previous.size != 8 || next.size != 8 ||
        previous.any { !it.isFinite() } || next.any { !it.isFinite() }) return false
    fun near(a: Double, b: Double): Boolean =
        abs(a - b) <= 1e-6 * max(1.0, max(abs(a), abs(b)))
    fun frame(c: DoubleArray): DoubleArray {
        val ux = c[2] - c[0]
        val uy = c[3] - c[1]
        val vx = c[4] - c[0]
        val vy = c[5] - c[1]
        return doubleArrayOf(ux * ux + uy * uy, vx * vx + vy * vy,
            ux * vx + uy * vy, ux * vy - uy * vx)
    }
    fun isParallelogram(c: DoubleArray): Boolean =
        near(c[6], c[2] + c[4] - c[0]) && near(c[7], c[3] + c[5] - c[1])
    val before = frame(previous)
    val after = frame(next)
    return isParallelogram(previous) && isParallelogram(next) &&
        before.indices.all { near(before[it], after[it]) }
}

/** Text detail is view state, not font-size or line-break state. Bucket levels so a pinch
 * does not start a new raster for every fractional zoom increment. */
internal fun textRasterZoomTier(zoom: Double): Double = when {
    !zoom.isFinite() || zoom <= 1.25 -> 1.0
    zoom <= 2.5 -> 2.0
    else -> 4.0
}

/** A little hysteresis avoids replacing an expensive GL bitmap for marginal detail gains. */
internal fun shouldRefineTextBitmap(currentScale: Double, wantedScale: Double): Boolean =
    currentScale.isFinite() && currentScale > 0.0 && wantedScale.isFinite() &&
        wantedScale > currentScale * 1.25

/** Keep narrow or tall text legible, bounded by a pixel budget and the actual GPU limit.
 * High zoom may request more pixels than the document unit size, without changing layout. */
internal fun textBitmapScale(
    width: Double,
    height: Double,
    glMaxSide: Double,
    preview: Boolean,
    targetScale: Double = 1.0,
): Double {
    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return 1.0
    val longSide = max(width, height)
    val shortSide = min(width, height)
    val extremeAspect = longSide > 2048.0 && longSide > shortSide * 8.0
    val budgetPixels = if (!preview) 2_000_000.0
        else if (extremeAspect) 1_000_000.0 else 350_000.0
    val edge = min(8192.0, glMaxSide.coerceAtLeast(2048.0))
    val desired = if (targetScale.isFinite() && targetScale > 0.0) targetScale.coerceAtMost(4.0) else 1.0
    return minOf(desired, edge / width, edge / height,
        sqrt(budgetPixels / (width * height)))
}
