package com.xnotes.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
    // A bar changing between row and column smoothly resizes its container.
    // Tool icons remain upright: no rotating or fading the entire toolbar.
    val animatedWidth by animateDpAsState(placement.width.dp,
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "liveBarWidth-${bar.id}")
    val animatedHeight by animateDpAsState(placement.height.dp,
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "liveBarHeight-${bar.id}")
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
            ) { content() }
        }
    }
}
