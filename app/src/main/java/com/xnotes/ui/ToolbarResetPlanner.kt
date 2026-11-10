package com.xnotes.ui

import androidx.compose.ui.unit.Dp
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook

/** Validate the complete preview before resetting one bar; never hide another to make room. */
internal fun planToolbarReset(
    workspace: ToolbarWorkspace,
    barId: String,
    context: ToolbarContext,
    look: ToolbarLook,
    width: Dp,
    height: Dp,
    colorCount: Int,
): ToolbarWorkspace? {
    val bar = workspace.bars.firstOrNull { it.id == barId } ?: return null
    val layout = if (context == ToolbarContext.CANVAS) ToolbarLayout.CANVAS_DEFAULT else ToolbarLayout.DEFAULT
    val candidate = workspace.replaceBar(bar.copy(layout = layout))
    val visible = candidate.onlyItems(if (context == ToolbarContext.CANVAS)
        ToolbarLayout.CANVAS_ITEMS else ToolbarLayout.NOTE_ITEMS)
    return candidate.takeIf { toolbarPlacementFits(visible, look, width, height, colorCount) }
}
