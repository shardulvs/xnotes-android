package com.xnotes.core.fill

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A window of content space cut into square cells, [scale] of them to a content px: the grid a
 * dropped colour's area is found on. Cell (i, j) covers [i, i + 1) x [j, j + 1) in grid units.
 */
class FillGrid(val window: Rect, val scale: Double) {
    val width: Int = max(1, ceil(window.w * scale).toInt())
    val height: Int = max(1, ceil(window.h * scale).toInt())

    fun gx(x: Double): Double = (x - window.left) * scale
    fun gy(y: Double): Double = (y - window.top) * scale

    fun toContent(gx: Double, gy: Double): Pt = Pt(window.left + gx / scale, window.top + gy / scale)

    companion object {
        /** [window] at [scale] cells per content px, or coarser where that would pass [budget] cells. */
        fun fitting(window: Rect, scale: Double, budget: Long): FillGrid {
            val area = max(window.w, 1e-9) * max(window.h, 1e-9)
            return FillGrid(window, min(scale, sqrt(budget / area)))
        }
    }
}
