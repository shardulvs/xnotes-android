package com.xnotes.ui

import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarWorkspaces
import com.xnotes.settings.ToolbarWorkspacePreset

/** Resolve inherited preset placement before importing into a different global appearance. */
internal fun applyToolbarPresetToContext(
    current: ToolbarWorkspaces,
    preset: ToolbarWorkspacePreset,
    target: ToolbarContext,
): ToolbarWorkspaces {
    val source = preset.workspaces.forContext(target)
    val primary = source.bars.first()
    val edge = toolbarDockEdge(primary, 0, preset.look)
    val geometry = if (edge == null) toolbarFloatingGeometry(primary, 0, preset.look)
        else primary.geometry.copy(floating = false, dockEdge = edge)
    val explicit = source.replaceBar(primary.copy(geometry = geometry))
    return current.updateWorkspace(target) { explicit }.copy(shared = false)
}
