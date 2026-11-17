package com.xnotes.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarItem
import kotlinx.coroutines.withTimeoutOrNull

/** Pointer recognition is independent of the launcher rendering and popup lifecycle. */
internal fun Modifier.penFanGestures(
    direction: PenFanDirection,
    items: List<ToolbarItem>,
    buttonSize: Dp,
    screenOrigin: () -> Offset,
    centersOnScreen: Map<Int, Offset>,
    hoveredIndex: () -> Int,
    onTap: () -> Unit,
    onOpen: () -> Unit,
    onHover: (Int) -> Unit,
    onChoose: (ToolbarItem) -> Unit,
    onDismiss: () -> Unit,
    onHaptic: (HapticFeedbackType) -> Unit,
): Modifier = pointerInput(direction, items) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume() // The pen owns its hold; the whole toolbar must not move.
        val quickRelease = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result: Boolean? = null
            while (result == null) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                result = when {
                    change == null -> false
                    !change.pressed -> true
                    change.isConsumed -> false
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                    else -> null
                }
            }
            result
        }
        when (quickRelease) {
            true -> onTap()
            false -> Unit
            null -> {
                onOpen()
                onHaptic(HapticFeedbackType.LongPress)
                val minDistance = 32.dp.toPx()
                // Compute from the palette's actual center, not the pressed
                // point. A press near the launcher edge otherwise skews the
                // narrow first/last sectors (especially on side rails).
                val fanOrigin = penFanGestureOrigin(
                    buttonSize.toPx(), direction, 35.dp.toPx(),
                )
                var tracking = true
                while (tracking) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        tracking = false
                    } else {
                        // Hit-test the *drawn* circles first. A side-rail popup
                        // can be shifted by the window edge, so its visual position
                        // is more reliable than an angle from the button.
                        val target = penFanVisibleTarget(
                            pointerOnScreen = screenOrigin() + change.position,
                            centersOnScreen = centersOnScreen,
                            count = items.size,
                            maxDistance = 44.dp.toPx(),
                        ) ?: penFanTarget(
                            displacement = change.position - fanOrigin,
                            direction = direction,
                            count = items.size,
                            minDistance = minDistance,
                        )
                        val next = target ?: -1
                        if (next != hoveredIndex() && next >= 0) {
                            onHaptic(HapticFeedbackType.TextHandleMove)
                        }
                        onHover(next)
                        if (!change.pressed) {
                            target?.let { onChoose(items[it]) }
                            tracking = false
                        } else {
                            change.consume()
                        }
                    }
                }
                // No sticky fan: a release at the origin also closes it.
                onDismiss()
            }
        }
    }
}
