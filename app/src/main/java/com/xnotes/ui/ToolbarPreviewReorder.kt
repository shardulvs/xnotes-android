package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarDockPlan
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import kotlin.math.abs

/** Reorder only among bars occupying the same rendered dock/floating lane and cluster.
 * Using actual placements (instead of requested lanes) handles automatic lane fallback. */
internal fun toolbarPreviewReorderPeers(
    workspace: ToolbarWorkspace,
    look: ToolbarLook,
    sourceId: String,
    docked: ToolbarDockPlan,
    floating: ToolbarDockPlan,
): List<String> {
    val sourceBar = workspace.bars.firstOrNull { it.id == sourceId } ?: return emptyList()
    val isDocked = docked.placement(sourceId) != null
    val plan = if (isDocked) docked else floating
    val source = plan.placement(sourceId) ?: return emptyList()
    val alignment = if (isDocked) sourceBar.geometry.dockAlignment else toolbarFloatingAlignment(sourceBar)
    return workspace.bars.filter { bar ->
        val placement = plan.placement(bar.id)
        val barAlignment = if (isDocked) bar.geometry.dockAlignment else toolbarFloatingAlignment(bar)
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
