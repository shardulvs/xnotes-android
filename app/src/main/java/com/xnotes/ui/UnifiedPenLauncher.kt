package com.xnotes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
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
 * Samsung Notes-inspired settings panel. Long-press shows a compact circular fan; drag and
 * lift chooses the hovered pen. Lifting without a choice ALWAYS dismisses the fan.
 */
@Composable
internal fun UnifiedPenLauncher(
    host: ToolPopupHost,
    armedTool: Tool,
    ink: Color,
    onChooseTool: (Tool) -> Unit,
    onSelectColor: (Int) -> Unit,
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
                PenSilhouette(lastItem, ink, Modifier.width(metrics.icon * .72f).height(metrics.icon))
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
                    ink = ink,
                    progress = fanProgress.value,
                    onItemPosition = { index, center -> penCentersOnScreen[index] = center },
                )
            }
        }
        if (detailsOpen) {
            UnifiedPenDetails(
                host = host,
                items = group.items,
                current = lastItem,
                ink = ink,
                onChoose = { choose(it) },
                onSelectColor = onSelectColor,
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
    ink: Color,
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
                PenSilhouette(item, ink, Modifier.width(18.dp).height(27.dp))
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

/** A stable, vector-based nib shared by the compact fan, detailed panel, and editor preview. */
@Composable
internal fun PenSilhouette(item: ToolbarItem, ink: Color, modifier: Modifier = Modifier) {
    // One consistent outlined icon family: dark/light adaptive strokes and a small live ink
    // accent, without the photorealistic shaded bodies that clash with the rest of XNotes.
    val foreground = LocalPalette.current.text.toComposeColor()
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val line = w * .075f
        val fine = w * .052f
        val outline = androidx.compose.ui.graphics.drawscope.Stroke(
            width = line,
            cap = StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        fun at(x: Float, y: Float) = Offset(w * x, h * y)
        fun nib(path: Path) = drawPath(path, foreground, style = outline)
        fun cap(left: Float = .24f, right: Float = .76f) {
            drawRoundRect(
                foreground,
                topLeft = at(left, .77f),
                size = Size(w * (right - left), h * .17f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .055f),
                style = outline,
            )
            drawLine(foreground, at(left, .86f), at(right, .86f), fine, cap = StrokeCap.Round)
        }

        when (item) {
            ToolbarItem.PEN, ToolbarItem.DASHED -> {
                nib(Path().apply {
                    moveTo(w * .30f, h * .76f)
                    lineTo(w * .22f, h * .36f)
                    quadraticBezierTo(w * .40f, h * .23f, w * .44f, h * .07f)
                    quadraticBezierTo(w * .50f, h * .01f, w * .56f, h * .07f)
                    quadraticBezierTo(w * .60f, h * .23f, w * .78f, h * .36f)
                    lineTo(w * .70f, h * .76f)
                })
                if (item == ToolbarItem.DASHED) {
                    for (y in listOf(.24f, .34f, .44f)) {
                        drawCircle(foreground, w * .035f, at(.50f, y))
                    }
                } else {
                    drawLine(foreground, at(.50f, .10f), at(.50f, .34f), fine, cap = StrokeCap.Round)
                    drawCircle(ink, w * .060f, at(.50f, .39f))
                }
                cap()
            }
            ToolbarItem.CALLIGRAPHY -> {
                nib(Path().apply {
                    moveTo(w * .31f, h * .76f)
                    lineTo(w * .27f, h * .29f)
                    lineTo(w * .61f, h * .08f)
                    lineTo(w * .75f, h * .18f)
                    lineTo(w * .70f, h * .76f)
                })
                drawLine(foreground, at(.37f, .33f), at(.58f, .17f), fine, cap = StrokeCap.Round)
                drawLine(ink, at(.42f, .42f), at(.61f, .35f), line, cap = StrokeCap.Round)
                cap()
            }
            ToolbarItem.SPEED -> {
                nib(Path().apply {
                    moveTo(w * .30f, h * .77f)
                    lineTo(w * .30f, h * .31f)
                    lineTo(w * .50f, h * .07f)
                    lineTo(w * .70f, h * .31f)
                    lineTo(w * .70f, h * .77f)
                })
                drawPath(Path().apply {
                    moveTo(w * .44f, h * .14f)
                    lineTo(w * .50f, h * .07f)
                    lineTo(w * .56f, h * .14f)
                }, foreground, style = outline)
                drawLine(foreground, at(.50f, .32f), at(.50f, .70f), fine)
                cap(.25f, .75f)
            }
            ToolbarItem.TAPER -> {
                nib(Path().apply {
                    moveTo(w * .33f, h * .76f)
                    lineTo(w * .34f, h * .34f)
                    quadraticBezierTo(w * .35f, h * .17f, w * .50f, h * .05f)
                    quadraticBezierTo(w * .65f, h * .17f, w * .66f, h * .34f)
                    lineTo(w * .67f, h * .76f)
                })
                drawLine(foreground, at(.33f, .35f), at(.67f, .35f), fine, cap = StrokeCap.Round)
                drawLine(ink, at(.50f, .11f), at(.50f, .31f), fine, cap = StrokeCap.Round)
                cap(.27f, .73f)
            }
            ToolbarItem.HIGHLIGHTER -> {
                nib(Path().apply {
                    moveTo(w * .23f, h * .77f)
                    lineTo(w * .23f, h * .37f)
                    lineTo(w * .58f, h * .11f)
                    lineTo(w * .78f, h * .20f)
                    lineTo(w * .77f, h * .77f)
                })
                drawLine(foreground, at(.23f, .37f), at(.77f, .37f), fine, cap = StrokeCap.Round)
                drawLine(ink, at(.28f, .57f), at(.72f, .57f), line * 1.5f, cap = StrokeCap.Round)
                cap(.19f, .81f)
            }
            else -> {
                drawLine(foreground, at(.50f, .12f), at(.50f, .85f), line, cap = StrokeCap.Round)
                drawCircle(ink, w * .065f, at(.50f, .42f))
            }
        }
    }
}

@Composable
internal fun UnifiedPenPreview(ink: Color, onSelect: () -> Unit) {
    val metrics = LocalBar.current
    Box(
        Modifier.size(metrics.button)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        PenSilhouette(ToolbarItem.PEN, ink, Modifier.width(metrics.icon * .72f).height(metrics.icon))
    }
}
