package com.xnotes.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import kotlinx.coroutines.flow.first

/** Floating bars auto-pack along screen edges and overflow to free lanes, never covering each other. */
@Composable
fun BoxScope.WorkspaceFloatingBars(
    workspace: ToolbarWorkspace,
    onMoveBar: (id: String, anchor: ToolbarAnchor, alignment: ToolbarDockAlignment) -> Unit,
    primaryContent: @Composable () -> Unit,
    content: @Composable (ToolbarBar) -> Unit,
    colorCount: Int = 5,
    onReorderBar: ((String, String) -> Unit)? = null,
) {
    val reorder = remember { ToolbarWorkspaceReorderController() }
    ToolbarReorderRecoveryEffect(reorder)
    LaunchedEffect(workspace) { reorder.acknowledge(workspace) }
    var paneBounds by remember { mutableStateOf(Rect.Zero) }
    val baseLook = LocalToolbarLook.current
    val showPrimary = workspace.hasMultiDock(baseLook) ||
        workspace.usesSharedFloatingPrimary(baseLook)
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(10f).onGloballyPositioned { paneBounds = it.boundsInRoot() }) {
        val basePlan = remember(workspace, baseLook, maxWidth, maxHeight, colorCount) {
            toolbarFloatingPlan(workspace, baseLook, maxWidth, maxHeight, colorCount)
        }
        val reordered = reorder.displayedWorkspace(workspace)
        val hoveredId = reorder.sourceId
        val hoveredTarget = reorder.hoverPlacement
        val previewMove = if (hoveredId != null && hoveredTarget != null &&
            reorder.targetId == null) toolbarProjectedMove(workspace, baseLook, hoveredId,
                hoveredTarget) else null
        val proposedWorkspace = previewMove?.first ?: reordered
        val proposedLook = previewMove?.second ?: baseLook
        val proposedPlan = if (proposedWorkspace == workspace && proposedLook == baseLook) basePlan
            else remember(proposedWorkspace, proposedLook, maxWidth, maxHeight, colorCount) {
                toolbarFloatingPlan(proposedWorkspace, proposedLook, maxWidth, maxHeight, colorCount)
            }
        val peers = remember(workspace, basePlan, onReorderBar != null) {
            if (onReorderBar == null) emptyMap() else workspace.bars.associate { bar ->
                bar.id to toolbarReorderPeersInPlan(workspace, bar.id, basePlan, floating = true)
            }
        }
        if (hoveredId != null) {
            val slot = when {
                reorder.targetId != null ->
                    toolbarReorderDockSlot(workspace, baseLook, hoveredId, proposedPlan)
                hoveredTarget != null ->
                    toolbarFloatingDropSlot(workspace, baseLook, hoveredId, hoveredTarget,
                        maxWidth, maxHeight, colorCount)
                else -> null
            }
            slot?.let { ToolbarDropIndicator(it, hoveredId) }
        }
        for ((index, bar) in workspace.bars.withIndex()) {
            val placement = (if (reorder.sourceId == bar.id) basePlan else proposedPlan)
                .placement(bar.id) ?: continue
            // Legacy floating primary is already rendered by ToolbarAround.
            if (index == 0 && !bar.geometry.floating && !showPrimary) continue
            key(bar.id) {
                val look = ToolbarLook(
                    position = placement.edge.toolbarPosition(),
                    size = toolbarBarSize(bar, index, baseLook),
                    floating = true,
                )
                ToolbarLiveReorderBar(
                    bar = bar, placement = placement,
                    x = placement.x, y = placement.y,
                    anchor = if (index == 0 && !bar.geometry.floating && baseLook.floating)
                        when (baseLook.position) {
                            ToolbarPosition.TOP -> ToolbarAnchor.TOP_CENTER
                            ToolbarPosition.BOTTOM -> ToolbarAnchor.BOTTOM_CENTER
                            ToolbarPosition.LEFT -> ToolbarAnchor.CENTER_LEFT
                            ToolbarPosition.RIGHT -> ToolbarAnchor.CENTER_RIGHT
                        } else bar.geometry.anchor,
                    alignment = toolbarFloatingAlignment(bar),
                    paneBounds = paneBounds,
                    workspace = workspace,
                    peers = peers[bar.id].orEmpty(),
                    reorder = reorder,
                    onMoveBar = onMoveBar,
                    onReorderBar = onReorderBar,
                    projectedSlot = { target ->
                        toolbarFloatingDropSlot(workspace, baseLook, bar.id, target,
                            maxWidth, maxHeight, colorCount)
                    },
                ) {
                    CompositionLocalProvider(LocalToolbarLook provides look) {
                        if (index == 0) primaryContent() else content(bar)
                    }
                }
            }
        }
    }
}

internal fun ToolbarAnchor.alignment(): Alignment = when (this) {
    ToolbarAnchor.TOP_LEFT -> AbsoluteAlignment.TopLeft
    ToolbarAnchor.TOP_CENTER -> Alignment.TopCenter
    ToolbarAnchor.TOP_RIGHT -> AbsoluteAlignment.TopRight
    ToolbarAnchor.CENTER_LEFT -> AbsoluteAlignment.CenterLeft
    ToolbarAnchor.CENTER -> Alignment.Center
    ToolbarAnchor.CENTER_RIGHT -> AbsoluteAlignment.CenterRight
    ToolbarAnchor.BOTTOM_LEFT -> AbsoluteAlignment.BottomLeft
    ToolbarAnchor.BOTTOM_CENTER -> Alignment.BottomCenter
    ToolbarAnchor.BOTTOM_RIGHT -> AbsoluteAlignment.BottomRight
}

internal fun ToolbarAnchor.toolbarPosition(): ToolbarPosition = when (this) {
    ToolbarAnchor.CENTER_LEFT -> ToolbarPosition.LEFT
    ToolbarAnchor.CENTER_RIGHT -> ToolbarPosition.RIGHT
    ToolbarAnchor.BOTTOM_LEFT, ToolbarAnchor.BOTTOM_CENTER, ToolbarAnchor.BOTTOM_RIGHT -> ToolbarPosition.BOTTOM
    else -> ToolbarPosition.TOP
}
