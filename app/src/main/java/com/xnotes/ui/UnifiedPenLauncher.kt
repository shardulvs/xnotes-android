package com.xnotes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.xnotes.R
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarToolGroup
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

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
                .penFanGestures(
                    direction = direction,
                    items = group.items,
                    buttonSize = metrics.button,
                    screenOrigin = { launcherOnScreen },
                    centersOnScreen = penCentersOnScreen,
                    hoveredIndex = { hoveredIndex },
                    onTap = { tap() },
                    onOpen = { fanOpen = true; detailsOpen = false; hoveredIndex = -1 },
                    onHover = { hoveredIndex = it },
                    onChoose = { choose(it) },
                    onDismiss = { fanOpen = false; hoveredIndex = -1 },
                    onHaptic = { haptics.performHapticFeedback(it) },
                ),
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
