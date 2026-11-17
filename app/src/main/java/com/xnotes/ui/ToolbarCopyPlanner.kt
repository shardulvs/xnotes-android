package com.xnotes.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarTransferDraft
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.core.tools.prepareToolbarTransfer
import com.xnotes.settings.ToolbarLook

/**
 * Docks reserve space before floating bars are checked against the remaining stage.
 * The editor may project tools for docks while retaining the saved floating layout.
 */
internal fun toolbarPlacementFits(
    workspace: ToolbarWorkspace,
    look: ToolbarLook,
    width: Dp,
    height: Dp,
    colorCount: Int,
    dockWorkspace: ToolbarWorkspace = workspace,
): Boolean {
    val docked = toolbarDockPlan(dockWorkspace, look, width, height)
    val floating = toolbarFloatingPlan(workspace, look,
        width - docked.leftInset.dp - docked.rightInset.dp,
        height - docked.topInset.dp - docked.bottomInset.dp, colorCount)
    return docked.rejected.isEmpty() && floating.rejected.isEmpty()
}

/** Returns a previewable copy, or null when no compatible tools or placement remain. */
internal fun planToolbarCopy(
    source: ToolbarBar,
    sourceIsPrimary: Boolean,
    destination: ToolbarWorkspace,
    target: ToolbarContext,
    look: ToolbarLook,
    width: Dp,
    height: Dp,
    colorCount: Int,
): ToolbarTransferDraft? {
    val initial = prepareToolbarTransfer(source, destination, target)
    if (initial.retainedCount == 0) return null
    // A copied primary becomes secondary and can no longer inherit the global look.
    val geometry = if (sourceIsPrimary) {
        if (toolbarDockEdge(source, 0, look) == null)
            source.geometry.copy(floating = true, dockEdge = null)
        else source.geometry.copy(
            dockEdge = source.geometry.dockEdge ?: look.position.dockEdge(), floating = false)
    } else source.geometry
    val candidates = listOf(geometry) + ToolbarAnchor.entries.filter { it != ToolbarAnchor.CENTER }
        .map { geometry.copy(floating = true, dockEdge = null, anchor = it, dockLane = 0) }
    val placed = candidates.firstOrNull { position ->
        toolbarPlacementFits(destination.addBar(initial.bar.copy(geometry = position)),
            look, width, height, colorCount)
    } ?: return null
    return initial.copy(bar = initial.bar.copy(geometry = placed))
}
