package com.xnotes.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarDockEdge

/** Keep the visible toolbar inside its window even when the pointer reaches an edge.
 * Hit testing still uses the raw pointer; only the rendered drag is constrained.
 * Otherwise a vertical bar grabbed near its centre projects above TOP, then jumps
 * back inside the window while morphing beside a toolbar already docked there. */
internal fun toolbarVisibleDrag(offset: Offset, slot: Rect, pane: Rect): Offset {
    if (slot == Rect.Zero || pane == Rect.Zero || pane.width <= 0f || pane.height <= 0f ||
        !offset.x.isFinite() || !offset.y.isFinite()) return offset
    fun fit(raw: Float, from: Float, to: Float, low: Float, high: Float): Float {
        val minimum = low - from
        val maximum = high - to
        // For an oversized bar, preserve the pointer motion while keeping the
        // viewport covered instead of jumping to the centre at gesture start.
        return if (minimum <= maximum) raw.coerceIn(minimum, maximum)
        else raw.coerceIn(maximum, minimum)
    }
    return Offset(
        fit(offset.x, slot.left, slot.right, pane.left, pane.right),
        fit(offset.y, slot.top, slot.bottom, pane.top, pane.bottom),
    )
}

/** End-of-morph translation uses the invariant outer slot, not the child
 * toolbar's initial inner bounds. Horizontal/vertical alignment changes that
 * inset during reflow; subtracting it from the target leaves a final-frame jump. */
internal fun toolbarSettleDestinationOffset(
    targetInRoot: Offset, parentSlotInRoot: Offset?, measuredContentSlot: Rect,
): Offset = targetInRoot - (parentSlotInRoot ?: measuredContentSlot.topLeft)

/** Root-independent parent/child alignment during a release transition.
 * The outer lane can grow between the projected compact landing width and its
 * eventual packed width. The smaller intrinsic child shifts RIGHT/BOTTOM inside
 * it. Return its CURRENT position so the pending drag can compensate each frame,
 * rather than preserving a static offset that briefly sends it offscreen. */
internal fun toolbarAlignedContentTopLeft(
    parentX: Float, parentY: Float,
    animatedWidth: Float, animatedHeight: Float,
    contentWidth: Float, contentHeight: Float,
    edge: ToolbarDockEdge,
): Offset = Offset(
    parentX + if (edge == ToolbarDockEdge.RIGHT)
        (animatedWidth - contentWidth).coerceAtLeast(0f) else 0f,
    parentY + if (edge == ToolbarDockEdge.BOTTOM)
        (animatedHeight - contentHeight).coerceAtLeast(0f) else 0f,
)
