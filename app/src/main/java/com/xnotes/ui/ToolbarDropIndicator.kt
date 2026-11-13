package com.xnotes.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarDockPlacement
import com.xnotes.core.tools.ToolbarDropPosition
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Physical footprint of the toolbar *itself*, not the maximum depth reserved by a lane. */
internal data class ToolbarDropSlot(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun topLeftInRoot(pane: Rect, density: Density): Offset = with(density) {
        pane.topLeft + Offset(x.dp.toPx(), y.dp.toPx())
    }
}

/** Project a gesture using the same geometry transaction as Editor.moveToolbarBar(). */
internal fun toolbarProjectedMove(
    workspace: ToolbarWorkspace,
    look: ToolbarLook,
    id: String,
    target: ToolbarDropPosition,
): Pair<ToolbarWorkspace, ToolbarLook>? {
    val index = workspace.bars.indexOfFirst { it.id == id && it.visible && it.geometry.movable }
    if (index < 0) return null
    val bar = workspace.bars[index]
    val priorEdge = toolbarDockEdge(bar, index, look)
    val targetEdge = target.anchor.floatingEdge()
    val docked = priorEdge != null
    val geometry = bar.geometry.movedTo(target.anchor, floating = !docked,
        fromEdge = priorEdge ?: bar.geometry.anchor.floatingEdge()).copy(
            dockEdge = if (docked) targetEdge else null,
            dockAlignment = target.alignment,
        )
    val projected = workspace.replaceBar(bar.copy(geometry = geometry))
    val projectedLook = if (docked && index == 0)
        look.copy(floating = false, position = targetEdge.toolbarPosition()) else look
    return projected to projectedLook
}

/** The rendered toolbar hugs the physical edge even when a larger peer owns the lane depth. */
internal fun toolbarDropSlot(
    placement: ToolbarDockPlacement,
    bar: ToolbarBar,
    index: Int,
    look: ToolbarLook,
): ToolbarDropSlot {
    val metrics = resolvedBarMetrics(toolbarBarSize(bar, index, look), bar.geometry)
    val vertical = placement.edge == ToolbarDockEdge.LEFT || placement.edge == ToolbarDockEdge.RIGHT
    val naturalDepth = if (vertical) toolbarWidth(metrics, bar.layout, bar.geometry).value
        else toolbarHeight(metrics, bar.layout, bar.geometry).value
    val width = if (vertical) minOf(placement.width, naturalDepth) else placement.width
    val height = if (vertical) placement.height else minOf(placement.height, naturalDepth)
    val x = placement.x + if (placement.edge == ToolbarDockEdge.RIGHT) placement.width - width else 0f
    val y = placement.y + if (placement.edge == ToolbarDockEdge.BOTTOM) placement.height - height else 0f
    return ToolbarDropSlot(x, y, width, height)
}

internal fun toolbarDockDropSlot(
    workspace: ToolbarWorkspace, look: ToolbarLook, id: String, target: ToolbarDropPosition,
    screenWidth: Dp, screenHeight: Dp,
): ToolbarDropSlot? {
    val (projected, nextLook) = toolbarProjectedMove(workspace, look, id, target) ?: return null
    val placement = toolbarDockPlan(projected, nextLook, screenWidth, screenHeight).placement(id)
        ?: return null
    val index = projected.bars.indexOfFirst { it.id == id }
    return toolbarDropSlot(placement, projected.bars[index], index, nextLook)
}

internal fun toolbarFloatingDropSlot(
    workspace: ToolbarWorkspace, look: ToolbarLook, id: String, target: ToolbarDropPosition,
    stageWidth: Dp, stageHeight: Dp, colorCount: Int,
): ToolbarDropSlot? {
    val (projected, nextLook) = toolbarProjectedMove(workspace, look, id, target) ?: return null
    val placement = toolbarFloatingPlan(projected, nextLook, stageWidth, stageHeight, colorCount)
        .placement(id) ?: return null
    val index = projected.bars.indexOfFirst { it.id == id }
    return toolbarDropSlot(placement, projected.bars[index], index, nextLook)
}

internal fun toolbarPreviewDropSlot(
    workspace: ToolbarWorkspace, look: ToolbarLook, id: String, target: ToolbarDropPosition,
    stageWidth: Dp, stageHeight: Dp, colorCount: Int,
): ToolbarDropSlot? {
    val (projected, nextLook) = toolbarProjectedMove(workspace, look, id, target) ?: return null
    val preview = toolbarPreviewLayoutPlan(projected, nextLook, stageWidth, stageHeight, colorCount)
        .position(id) ?: return null
    val index = projected.bars.indexOfFirst { it.id == id }
    val slot = toolbarDropSlot(preview.placement, projected.bars[index], index, nextLook)
    return slot.copy(x = slot.x + preview.x - preview.placement.x,
        y = slot.y + preview.y - preview.placement.y)
}

/** Non-interactive target overlay. The bar remains responsive to pen/finger events above it. */
@Composable
internal fun ToolbarDropIndicator(slot: ToolbarDropSlot, id: String) {
    val accent = LocalPalette.current.accent.toComposeColor()
    val x = animateDpAsState(slot.x.dp, spring(), label = "dropIndicatorX-$id")
    val y = animateDpAsState(slot.y.dp, spring(), label = "dropIndicatorY-$id")
    Box(Modifier.offset(x = x.value, y = y.value)
        .width(slot.width.dp).height(slot.height.dp)
        .background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
        .border(2.dp, accent.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
        .testTag("toolbar-drop-indicator-$id"))
}
