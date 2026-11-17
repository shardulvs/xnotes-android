package com.xnotes.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook

/** A completed immutable editor command. The caller is responsible for persisting it. */
internal data class ToolbarWorkspaceEdit(val workspace: ToolbarWorkspace, val selectedBarId: String)

/** User-generated IDs must be independent of display names and existing list order. */
internal fun ToolbarWorkspace.nextToolbarBarId(): String {
    val used = bars.mapTo(HashSet(bars.size)) { it.id }
    return generateSequence(1) { it + 1 }.map { "bar-$it" }.first { it !in used }
}

/**
 * Create a small colour palette, leaving other tools available but hidden.
 * Match the previous editor behavior: a new floating bar is rejected only if
 * the floating layout planner rejects that specific ID.
 */
internal fun planToolbarAdd(
    workspace: ToolbarWorkspace,
    context: ToolbarContext,
    namePrefix: String,
    look: ToolbarLook,
    width: Dp,
    height: Dp,
    colorCount: Int,
): ToolbarWorkspaceEdit? {
    val id = workspace.nextToolbarBarId()
    val defaults = if (context == ToolbarContext.CANVAS) ToolbarLayout.CANVAS_DEFAULT else ToolbarLayout.DEFAULT
    val compact = ToolbarLayout(defaults.sections.map { section ->
        section.copy(entries = section.entries.map { entry ->
            entry.copy(visible = entry.item == ToolbarItem.COLORS)
        })
    })
    val candidate = workspace.addBar(ToolbarBar(
        id = id,
        name = "$namePrefix ${workspace.bars.size + 1}",
        layout = compact,
        geometry = ToolbarGeometry(anchor = ToolbarAnchor.BOTTOM_RIGHT, floating = true),
    ))
    val dock = toolbarDockPlan(candidate, look, width, height)
    val floating = toolbarFloatingPlan(candidate, look,
        width - dock.leftInset.dp - dock.rightInset.dp,
        height - dock.topInset.dp - dock.bottomInset.dp, colorCount)
    return if (id in floating.rejected) null else ToolbarWorkspaceEdit(candidate, id)
}

/** Duplicate only if the resulting complete workspace can be displayed. */
internal fun planToolbarDuplicate(
    workspace: ToolbarWorkspace,
    sourceId: String,
    namePrefix: String,
    look: ToolbarLook,
    width: Dp,
    height: Dp,
    colorCount: Int,
): ToolbarWorkspaceEdit? {
    if (workspace.bars.none { it.id == sourceId }) return null
    val id = workspace.nextToolbarBarId()
    val candidate = workspace.duplicateBar(sourceId, id, "$namePrefix ${workspace.bars.size + 1}")
    if (!toolbarPlacementFits(candidate, look, width, height, colorCount)) return null
    return ToolbarWorkspaceEdit(candidate, id)
}
