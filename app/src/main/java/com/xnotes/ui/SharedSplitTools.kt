package com.xnotes.ui

import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout

/**
 * Carry the common toolbox to the next surface before its first DOWN. Only tool state is copied:
 * never call pickColor (which recolours selected content), or copy a document, selection or history.
 */
internal fun Editor.transferSplitToolsTo(target: Editor) {
    val source: ToolPopupHost = if (canvasOpen) infinite else this
    val sourceTool = if (canvasOpen) infinite.tool else tool
    val color = if (canvasOpen) infinite.inkColor else controller.inkColor
    val nextTool = if (!target.canvasOpen || ToolbarItem.fromId(sourceTool.id) in ToolbarLayout.CANVAS_ITEMS)
        sourceTool else Tool.PAN
    // A tap on a local selection/text action also activates its pane. Do not clear that session
    // by replacing its tool before the very action the user touched can run.
    val keepSession = if (target.canvasOpen) target.infinite.hasSelection || target.infinite.editingField != null
        else target.hasSelection || target.editingField != null || target.flowEditingActive
    if (target.canvasOpen) {
        val canvas = target.infinite
        for (tool in ToolDefaults.persistedTools) canvas.setToolConfig(tool, source.toolConfig(tool))
        canvas.armShapeConfig(source.hostShapeConfig)
        canvas.adoptSharedInk(source, color)
        if (!keepSession && canvas.tool != nextTool) canvas.armTool(nextTool)
    } else {
        for (tool in ToolDefaults.persistedTools) target.controller.setToolConfig(tool, source.toolConfig(tool))
        target.updateShapeConfig(source.hostShapeConfig)
        target.adoptSharedInk(source, color)
        if (!keepSession && target.tool != nextTool) target.selectTool(nextTool)
    }
}
