package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import com.xnotes.settings.ToolbarPosition
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Semicircular quick-select layout, opening away from the toolbar's docked edge. */
internal enum class PenFanDirection { DOWN, UP, RIGHT, LEFT }

internal fun ToolbarPosition.penFanDirection(): PenFanDirection = when (this) {
    ToolbarPosition.TOP -> PenFanDirection.DOWN
    ToolbarPosition.BOTTOM -> PenFanDirection.UP
    ToolbarPosition.LEFT -> PenFanDirection.RIGHT
    ToolbarPosition.RIGHT -> PenFanDirection.LEFT
}

/** Palette center in the launcher's local coordinates. Popup alignment puts its center 35 dp
 * from the launching edge, which differs from the launcher's center by ~14 dp. */
internal fun penFanGestureOrigin(
    buttonSize: Float,
    direction: PenFanDirection,
    inset: Float,
): Offset = when (direction) {
    PenFanDirection.DOWN -> Offset(buttonSize / 2f, inset)
    PenFanDirection.UP -> Offset(buttonSize / 2f, buttonSize - inset)
    PenFanDirection.RIGHT -> Offset(inset, buttonSize / 2f)
    PenFanDirection.LEFT -> Offset(buttonSize - inset, buttonSize / 2f)
}

/** Position of an item relative to the launcher, in the same units as [radius]. */
internal fun penFanOffset(index: Int, count: Int, direction: PenFanDirection, radius: Float): Offset {
    if (count <= 0) return Offset.Zero
    val angle = (-75.0 + 150.0 * index.coerceIn(0, count - 1) / (count - 1).coerceAtLeast(1)) * PI / 180.0
    val sideways = (sin(angle) * radius).toFloat()
    val forward = (cos(angle) * radius).toFloat()
    return when (direction) {
        PenFanDirection.DOWN -> Offset(sideways, forward)
        PenFanDirection.UP -> Offset(sideways, -forward)
        PenFanDirection.RIGHT -> Offset(forward, sideways)
        PenFanDirection.LEFT -> Offset(-forward, sideways)
    }
}

/** Actual on-screen hit testing for popup icons, including popups shifted at screen edges.
 * The angle-based selector remains a fallback before the popup has laid out. */
internal fun penFanVisibleTarget(
    pointerOnScreen: Offset,
    centersOnScreen: Map<Int, Offset>,
    count: Int,
    maxDistance: Float,
): Int? {
    if (!pointerOnScreen.x.isFinite() || !pointerOnScreen.y.isFinite() ||
        !maxDistance.isFinite() || maxDistance <= 0f
    ) return null
    return centersOnScreen.entries
        .asSequence()
        .filter { (index, center) -> index in 0 until count && center.x.isFinite() && center.y.isFinite() }
        .map { (index, center) -> index to (pointerOnScreen - center).getDistance() }
        .filter { (_, distance) -> distance <= maxDistance }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

/**
 * Press, drag in the direction of a pen, release to activate it. The minimum distance
 * is radial, not forward-only: the first and last pens sit near 75 degrees, where a
 * forward-only threshold made them almost impossible to select on side rails.
 */
internal fun penFanTarget(
    displacement: Offset,
    direction: PenFanDirection,
    count: Int,
    minDistance: Float,
): Int? {
    if (count <= 0) return null
    val (forward, sideways) = when (direction) {
        PenFanDirection.DOWN -> displacement.y to displacement.x
        PenFanDirection.UP -> -displacement.y to displacement.x
        PenFanDirection.RIGHT -> displacement.x to displacement.y
        PenFanDirection.LEFT -> -displacement.x to displacement.y
    }
    if (!forward.isFinite() || !sideways.isFinite()) return null
    val distance = kotlin.math.hypot(forward.toDouble(), sideways.toDouble())
    if (forward <= 0f || distance < minDistance) return null
    val angle = atan2(sideways.toDouble(), forward.toDouble()) * 180.0 / PI
    if (angle < -90.0 || angle > 90.0) return null
    return (((angle + 75.0) / 150.0) * (count - 1))
        .roundToInt().coerceIn(0, count - 1)
}
