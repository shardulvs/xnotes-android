package com.xnotes.ui

import android.content.Context
import com.xnotes.R
import com.xnotes.settings.ToolbarWorkspacePreset
import com.xnotes.settings.ToolbarWorkspaceTemplates

/** Localize built-in defaults before preview/application; never translate user-saved names. */
internal fun localizedToolbarTemplates(context: Context): List<ToolbarWorkspacePreset> {
    fun name(value: String): String = when (value) {
        "Distraction-free writing" -> context.getString(R.string.toolbar_template_name_0)
        "Writing" -> context.getString(R.string.toolbar_template_name_1)
        "Study notebook" -> context.getString(R.string.toolbar_template_name_2)
        "Document" -> context.getString(R.string.toolbar_template_name_3)
        "Annotation" -> context.getString(R.string.toolbar_template_name_4)
        "Document review" -> context.getString(R.string.toolbar_template_name_5)
        "Navigation" -> context.getString(R.string.toolbar_template_name_6)
        "Free drawing" -> context.getString(R.string.toolbar_template_name_7)
        "Drawing tools" -> context.getString(R.string.toolbar_template_name_8)
        "Colors" -> context.getString(R.string.toolbar_template_name_9)
        "Actions" -> context.getString(R.string.toolbar_template_name_10)
        "Quick markup" -> context.getString(R.string.toolbar_template_name_11)
        "Markup" -> context.getString(R.string.toolbar_template_name_12)
        "Complete toolbar" -> context.getString(R.string.toolbar_template_name_13)
        "Organization" -> context.getString(R.string.toolbar_template_name_14)
        "Two rows" -> context.getString(R.string.toolbar_template_name_15)
        "Tools" -> context.getString(R.string.toolbar_template_name_16)
        "Original" -> context.getString(R.string.toolbar_template_name_17)
        "Main toolbar" -> context.getString(R.string.toolbar_template_name_18)
        else -> value
    }
    return ToolbarWorkspaceTemplates.presets.map { preset ->
        preset.copy(name = name(preset.name), workspaces = preset.workspaces.copy(
            note = preset.workspaces.note.copy(bars = preset.workspaces.note.bars.map { it.copy(name = name(it.name)) }),
            canvas = preset.workspaces.canvas.copy(bars = preset.workspaces.canvas.bars.map { it.copy(name = name(it.name)) }),
        ))
    }
}
