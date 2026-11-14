package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarDockPlan
import com.xnotes.core.tools.ToolbarWorkspace
import kotlin.math.abs

/** Reorder only among bars occupying the same rendered dock/floating lane and cluster.
 * Using actual placements (instead of requested lanes) handles automatic lane fallback. */
internal fun toolbarPreviewReorderPeers(
    workspace: ToolbarWorkspace,
    sourceId: String,
    docked: ToolbarDockPlan,
    floating: ToolbarDockPlan,
): List<String> {
    val dockedSource = docked.placement(sourceId) != null
    return toolbarReorderPeersInPlan(workspace, sourceId,
        if (dockedSource) docked else floating, floating = !dockedSource)
}

/** Reuse the exact preview peer rules in the live XNote/XCanvas renderer. */
internal fun toolbarReorderPeersInPlan(
    workspace: ToolbarWorkspace,
    sourceId: String,
    plan: ToolbarDockPlan,
    floating: Boolean,
): List<String> {
    val sourceBar = workspace.bars.firstOrNull { it.id == sourceId } ?: return emptyList()
    val source = plan.placement(sourceId) ?: return emptyList()
    val alignment = if (floating) toolbarFloatingAlignment(sourceBar) else sourceBar.geometry.dockAlignment
    return workspace.bars.filter { bar ->
        val placement = plan.placement(bar.id)
        val barAlignment = if (floating) toolbarFloatingAlignment(bar) else bar.geometry.dockAlignment
        placement != null && placement.edge == source.edge && placement.lane == source.lane &&
            barAlignment == alignment
    }.map { it.id }
}

/** Frozen bar rectangles keep hit testing stable while the preview animates to a new order. */
internal fun toolbarPreviewReorderHit(
    sourceId: String,
    candidates: List<String>,
    pointer: Offset,
    frozenBounds: Map<String, Rect>,
    vertical: Boolean,
    tolerancePx: Float = 24f,
): String? {
    return candidates.asSequence().filter { it != sourceId }.mapNotNull { id ->
        val rect = frozenBounds[id] ?: return@mapNotNull null
        if (pointer.x < rect.left - tolerancePx || pointer.x > rect.right + tolerancePx ||
            pointer.y < rect.top - tolerancePx || pointer.y > rect.bottom + tolerancePx) return@mapNotNull null
        val distance = if (vertical) abs(pointer.y - rect.center.y) else abs(pointer.x - rect.center.x)
        id to distance
    }.minByOrNull { it.second }?.first
}
