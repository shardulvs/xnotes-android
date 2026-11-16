package com.xnotes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarToolGroup
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pinterest-style radial shortcut: a tap arms the last pen, a second tap opens the full
 * N-Notes tool settings panel. Long-press shows a compact circular fan; drag and
 * lift chooses the hovered pen. Lifting without a choice ALWAYS dismisses the fan.
 */
@Composable
internal fun UnifiedPenLauncher(
    host: ToolPopupHost,
    armedTool: Tool,
    onChooseTool: (Tool) -> Unit,
) {
    val group = remember { ToolbarToolGroup.defaultWritingGroup() }
    val direction = LocalToolbarLook.current.position.penFanDirection()
    val palette = LocalPalette.current
    val metrics = LocalBar.current
    val haptics = LocalHapticFeedback.current
    var lastId by rememberSaveable { mutableStateOf(ToolbarItem.PEN.id) }
    val lastItem = group.items.firstOrNull { it.id == lastId } ?: ToolbarItem.PEN
    val glide = LocalToolGlide.current
    val glideKey = Tool.fromId(lastItem.id)
    val selection = palette.selectionBackground.toComposeColor()
    val activeFill by animateFloatAsState(
        if (armedTool.id == lastItem.id && glide == null) 1f else 0f,
        tween(durationMillis = 150),
        label = "unifiedPenSelection",
    )
    DisposableEffect(glide, glideKey) {
        onDispose { if (glideKey != null) glide?.centers?.remove(glideKey) }
    }
    var fanOpen by remember { mutableStateOf(false) }
    var fanMounted by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var hoveredIndex by remember { mutableIntStateOf(-1) }
    var launcherOnScreen by remember { mutableStateOf(Offset.Zero) }
    val penCentersOnScreen = remember { mutableStateMapOf<Int, Offset>() }

    val fanProgress = remember { Animatable(0f) }
    LaunchedEffect(fanOpen) {
        if (fanOpen) {
            penCentersOnScreen.clear()
            fanMounted = true
            fanProgress.snapTo(0f)
            fanProgress.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            )
        } else if (fanMounted) {
            fanProgress.animateTo(0f, tween(durationMillis = 125))
            fanMounted = false
        }
    }

    val choose by rememberUpdatedState<(ToolbarItem) -> Unit> { item ->
        val tool = Tool.fromId(item.id)
        if (tool != null && item in group.items) {
            lastId = item.id
            fanOpen = false
            hoveredIndex = -1
            onChooseTool(tool)
        }
    }
    // A separate pen button updates the shortcut too, but not the second-tap state.
    LaunchedEffect(armedTool) {
        val activeItem = group.items.firstOrNull { it.id == armedTool.id }
        if (activeItem != null && activeItem.id != lastId) {
            lastId = activeItem.id
        }
    }
    // If this pen is already armed, one tap opens its details—even immediately after closing
    // the panel. Otherwise the first tap arms it and the next tap opens the panel.
    val tap by rememberUpdatedState {
        if (armedTool.id == lastItem.id) detailsOpen = true else choose(lastItem)
    }

    val label = stringResource(R.string.toolbar_unified_pen)
    Box(Modifier.size(metrics.button), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(metrics.button)
                .onGloballyPositioned { launcherOnScreen = it.positionOnScreen() }
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick { tap(); true }
                }
                .pointerInput(direction, group.items) {
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
                            true -> tap()
                            false -> Unit
                            null -> {
                                fanOpen = true
                                detailsOpen = false
                                hoveredIndex = -1
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val minDistance = 32.dp.toPx()
                                // Compute from the palette's actual center, not the pressed
                                // point. A press near the launcher edge otherwise skews the
                                // narrow first/last sectors (especially on side rails).
                                val fanOrigin = penFanGestureOrigin(
                                    metrics.button.toPx(), direction, 35.dp.toPx(),
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
                                            pointerOnScreen = launcherOnScreen + change.position,
                                            centersOnScreen = penCentersOnScreen,
                                            count = group.items.size,
                                            maxDistance = 44.dp.toPx(),
                                        ) ?: penFanTarget(
                                            displacement = change.position - fanOrigin,
                                            direction = direction,
                                            count = group.items.size,
                                            minDistance = minDistance,
                                        )
                                        val next = target ?: -1
                                        if (next != hoveredIndex && next >= 0) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        hoveredIndex = next
                                        if (!change.pressed) {
                                            target?.let { choose(group.items[it]) }
                                            tracking = false
                                        } else {
                                            change.consume()
                                        }
                                    }
                                }
                                // No sticky fan: a release at the origin also closes it.
                                fanOpen = false
                                hoveredIndex = -1
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Match the native toolbar icon scale; the ink is shown in the settings panel,
            // not by an extra dot that gets cropped by this button's rounded container.
            Box(
                Modifier
                    .size(metrics.circle)
                    .onGloballyPositioned { coordinates ->
                        if (glideKey != null) {
                            glide?.row?.let { row ->
                                glide.centers[glideKey] = row.localPositionOf(
                                    coordinates, Offset(coordinates.size.width / 2f, coordinates.size.height / 2f),
                                )
                            }
                        }
                    }
                    .drawBehind {
                        if (activeFill > 0f) {
                            drawCircle(selection.copy(alpha = selection.alpha * activeFill))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PenSilhouette(lastItem, Modifier.width(metrics.icon * .72f).height(metrics.icon))
            }
        }
        if (fanMounted) {
            Popup(
                alignment = when (direction) {
                    PenFanDirection.DOWN -> Alignment.TopCenter
                    PenFanDirection.UP -> Alignment.BottomCenter
                    PenFanDirection.RIGHT -> Alignment.CenterStart
                    PenFanDirection.LEFT -> Alignment.CenterEnd
                },
                onDismissRequest = { fanOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                PenFanPalette(
                    items = group.items,
                    direction = direction,
                    selected = hoveredIndex,
                    active = lastItem,
                    progress = fanProgress.value,
                    onItemPosition = { index, center -> penCentersOnScreen[index] = center },
                )
            }
        }
        if (detailsOpen) {
            ToolConfigPopup(
                editor = host,
                tool = Tool.fromId(lastItem.id) ?: Tool.PEN,
                onPick = { pen ->
                    group.items.firstOrNull { it.id == pen.id }?.let(choose)
                    detailsOpen = true
                },
                onDismiss = { detailsOpen = false },
            )
        }
    }
}

@Composable
private fun PenFanPalette(
    items: List<ToolbarItem>,
    direction: PenFanDirection,
    selected: Int,
    active: ToolbarItem,
    progress: Float,
    onItemPosition: (Int, Offset) -> Unit,
) {
    val horizontal = direction == PenFanDirection.DOWN || direction == PenFanDirection.UP
    val width = if (horizontal) 260.dp else 185.dp
    val height = if (horizontal) 185.dp else 260.dp
    val originX = if (horizontal) width / 2 else if (direction == PenFanDirection.RIGHT) 35.dp else width - 35.dp
    val originY = if (!horizontal) height / 2 else if (direction == PenFanDirection.DOWN) 35.dp else height - 35.dp
    val palette = LocalPalette.current
    val surface = palette.panel.toComposeColor()
    val stroke = palette.border.toComposeColor()

    Box(Modifier.width(width).height(height)) {
        items.forEachIndexed { index, item ->
            // 110 dp radius provides clear spacing, even when the hovered circle grows.
            val displacement = penFanOffset(index, items.size, direction, 110f)
            val reveal = progress.coerceIn(0f, 1f)
            val x = originX + (displacement.x * reveal).dp - 23.dp
            val y = originY + (displacement.y * reveal).dp - 23.dp
            val hovered = index == selected
            val scale by animateFloatAsState(
                targetValue = if (hovered) 1.17f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "pen-fan-hover",
            )
            Box(
                Modifier
                    .offset(x = x, y = y)
                    .size(46.dp)
                    .onGloballyPositioned { coords ->
                        val center = Offset(coords.size.width / 2f, coords.size.height / 2f)
                        onItemPosition(index, coords.localToScreen(center))
                    }
                    .graphicsLayer {
                        alpha = reveal
                        scaleX = (0.65f + 0.35f * reveal) * scale
                        scaleY = (0.65f + 0.35f * reveal) * scale
                    }
                    .clip(CircleShape)
                    .background(if (hovered) palette.selectionBackground.toComposeColor() else surface)
                    .border(
                        if (hovered) 2.5.dp else 1.2.dp,
                        if (hovered || item == active) palette.accent.toComposeColor() else stroke,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                PenSilhouette(item, Modifier.width(18.dp).height(27.dp))
            }
        }
        if (selected in items.indices) {
            val name = stringResource(items[selected].labelRes)
            Box(Modifier.align(Alignment.Center).padding(4.dp), contentAlignment = Alignment.Center) {
                Text(name, color = palette.text.toComposeColor(), fontSize = 12.sp)
            }
        }
    }
}

/** N-Notes' stroke icon assets, shared by the launcher, gesture fan and toolbar preview. */
@Composable
internal fun PenSilhouette(item: ToolbarItem, modifier: Modifier = Modifier) {
    val icon = when (item) {
        ToolbarItem.PEN -> R.drawable.ic_stroke_regular
        ToolbarItem.DASHED -> R.drawable.ic_stroke_dashed
        ToolbarItem.CALLIGRAPHY -> R.drawable.ic_stroke_calligraphy
        ToolbarItem.SPEED -> R.drawable.ic_stroke_speed
        ToolbarItem.TAPER -> R.drawable.ic_stroke_taper
        ToolbarItem.HIGHLIGHTER -> R.drawable.ic_stroke_highlighter
        else -> R.drawable.ic_stroke_regular
    }
    androidx.compose.material3.Icon(
        imageVector = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(icon),
        contentDescription = null,
        modifier = modifier,
        tint = LocalPalette.current.text.toComposeColor(),
    )
}
