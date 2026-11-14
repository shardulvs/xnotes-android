package com.xnotes.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarDockPlacement
import com.xnotes.core.tools.ToolbarDockPlan
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook

/** Read-only placement calculations for the preview (no gesture or Compose state).
 * The renderer and the drop controller share one consistent screen-space plan. */
internal data class ToolbarPreviewLayoutPlan(
    val docked: ToolbarDockPlan,
    val floating: ToolbarDockPlan,
) {
    fun position(id: String): ToolbarPreviewPosition? {
        val dockedPlacement = docked.placement(id)
        val floatingPlacement = if (dockedPlacement == null) floating.placement(id) else null
        val placement = dockedPlacement ?: floatingPlacement ?: return null
        return ToolbarPreviewPosition(
            placement = placement,
            floating = dockedPlacement == null,
            x = placement.x + if (dockedPlacement == null) docked.leftInset else 0f,
            y = placement.y + if (dockedPlacement == null) docked.topInset else 0f,
        )
    }
}

internal data class ToolbarPreviewPosition(
    val placement: ToolbarDockPlacement,
    val floating: Boolean,
    val x: Float,
    val y: Float,
)

internal fun toolbarPreviewLayoutPlan(
    workspace: ToolbarWorkspace,
    look: ToolbarLook,
    screenWidth: Dp,
    screenHeight: Dp,
    colorsCount: Int,
): ToolbarPreviewLayoutPlan {
    val docked = toolbarDockPlan(workspace, look, screenWidth, screenHeight)
    val floating = toolbarFloatingPlan(
        workspace, look,
        (screenWidth.value - docked.leftInset - docked.rightInset).coerceAtLeast(0f).dp,
        (screenHeight.value - docked.topInset - docked.bottomInset).coerceAtLeast(0f).dp,
        colorsCount,
    )
    return ToolbarPreviewLayoutPlan(docked, floating)
}

/** Candidate selection is resolved against the *base* geometry, never animated peers. */
internal fun toolbarPreviewPeers(
    workspace: ToolbarWorkspace,
    plan: ToolbarPreviewLayoutPlan,
): Map<String, List<String>> = workspace.bars.associate { bar ->
    bar.id to toolbarPreviewReorderPeers(workspace, bar.id, plan.docked, plan.floating)
}
