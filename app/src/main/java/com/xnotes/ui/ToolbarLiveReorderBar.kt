package com.xnotes.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockPlacement
import com.xnotes.core.tools.ToolbarDropPosition
import com.xnotes.core.tools.ToolbarWorkspace

/** Mirrors the preview's optimistic gesture/reorder transaction on a real toolbar.
 * The source remains under the pointer, while peers animate to their proposed slots.
 * Do not derive state from bar list indices: bar identity survives every reorder. */
@Composable
internal fun ToolbarLiveReorderBar(
    bar: ToolbarBar,
    placement: ToolbarDockPlacement,
    x: Float,
    y: Float,
    anchor: ToolbarAnchor,
    alignment: ToolbarDockAlignment,
    paneBounds: Rect,
    workspace: ToolbarWorkspace,
    peers: List<String>,
    reorder: ToolbarWorkspaceReorderController,
    onMoveBar: ((String, ToolbarAnchor, ToolbarDockAlignment) -> Unit)?,
    onReorderBar: ((String, String) -> Unit)?,
    projectedSlot: (ToolbarDropPosition) -> ToolbarDropSlot?,
    content: @Composable () -> Unit,
) {
    val isSource = reorder.sourceId == bar.id || reorder.lastReleasedId == bar.id
    val animatedX by animateDpAsState(x.dp,
        animationSpec = if (isSource) snap() else spring(), label = "liveBarX-${bar.id}")
    val animatedY by animateDpAsState(y.dp,
        animationSpec = if (isSource) snap() else spring(), label = "liveBarY-${bar.id}")
    // Capture the target dimensions on release, not after the move callback.
    // Translation and resize now start together and finish in the same 220 ms.
    // Icons stay upright; no scaling/rotation or transparency of their contents.
    var settlingSize by remember(bar.id) { mutableStateOf<Pair<Dp, Dp>?>(null) }
    var settlingPlacement by remember(bar.id) { mutableStateOf<ToolbarDropPosition?>(null) }
    var morph by remember(bar.id) { mutableStateOf<ToolbarContainerMorph?>(null) }
    var morphProgress by remember(bar.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(anchor, alignment, settlingPlacement) {
        val target = settlingPlacement
        if (target != null && anchor == target.anchor && alignment == target.alignment) {
            settlingSize = null
            settlingPlacement = null
            morph = null
        }
    }
    val sizeSpec = tween<Dp>(220, easing = FastOutSlowInEasing)
    val animatedWidth by animateDpAsState(settlingSize?.first ?: placement.width.dp,
        animationSpec = sizeSpec, label = "liveBarWidth-${bar.id}")
    val animatedHeight by animateDpAsState(settlingSize?.second ?: placement.height.dp,
        animationSpec = sizeSpec, label = "liveBarHeight-${bar.id}")
    val currentCommit by rememberUpdatedState(onReorderBar)
    val density = LocalDensity.current
    Box(
        Modifier.offset(x = if (isSource) x.dp else animatedX,
                        y = if (isSource) y.dp else animatedY)
            .width(animatedWidth).height(animatedHeight)
            .testTag("live-toolbar-slot-${bar.id}")
            .zIndex(if (reorder.sourceId == bar.id) 2f else if (reorder.targetId == bar.id) 1f else 0f)
            .onGloballyPositioned { coordinates ->
                reorder.updateBounds(bar.id, coordinates.boundsInRoot())
            },
        contentAlignment = placement.edge.toolbarContentAlignment(),
    ) {
        if (onMoveBar == null) {
            content()
        } else {
            ToolbarDragSurface(
                id = bar.id,
                anchor = anchor,
                movable = bar.geometry.movable,
                paneBounds = paneBounds,
                currentAlignment = alignment,
                onMoveBar = { id, target, targetAlignment ->
                    reorder.markAnchorMoveReleased(id)
                    onMoveBar(id, target, targetAlignment)
                },
                onDragStart = if (onReorderBar == null) null else { _ ->
                    reorder.start(bar.id, peers,
                        placement.edge == com.xnotes.core.tools.ToolbarDockEdge.LEFT ||
                            placement.edge == com.xnotes.core.tools.ToolbarDockEdge.RIGHT,
                        workspace, anchor, alignment)
                },
                onDragPosition = if (onReorderBar == null) null else { finger ->
                    reorder.hover(bar.id, finger)
                },
                onDrop = if (onReorderBar == null) null else { finger ->
                    reorder.drop(bar.id, finger) { source, target ->
                        currentCommit?.invoke(source, target)
                    }
                },
                onDragCancel = if (onReorderBar == null) null else ({
                    reorder.cancel(bar.id)
                }),
                snapTargetInRoot = { target ->
                    projectedSlot(target)?.topLeftInRoot(paneBounds, density)
                },
                onPlacementPreview = { target ->
                    reorder.previewPlacement(bar.id, target)
                },
                onSettleTarget = { target ->
                    // Recovery watchdog covers a source removed by relayout before
                    // the 220-ms settle coroutine can publish its commit.
                    if (target != null) reorder.markAnchorMoveReleased(bar.id)
                    settlingPlacement = target
                    settlingSize = target?.let { projectedSlot(it) }?.let {
                        it.width.dp to it.height.dp
                    }
                    val startVertical = placement.edge == com.xnotes.core.tools.ToolbarDockEdge.LEFT ||
                        placement.edge == com.xnotes.core.tools.ToolbarDockEdge.RIGHT
                    val endVertical = target?.anchor?.let {
                        it == ToolbarAnchor.CENTER_LEFT || it == ToolbarAnchor.CENTER_RIGHT
                    }
                    morph = if (endVertical != null && endVertical != startVertical)
                        ToolbarContainerMorph(startVertical, endVertical, 0f) else null
                    morphProgress = 0f
                },
                onSettleProgress = { progress -> morphProgress = progress },
            ) {
                CompositionLocalProvider(LocalToolbarContainerMorph provides
                    morph?.copy(fraction = morphProgress)) { content() }
            }
        }
    }
}
