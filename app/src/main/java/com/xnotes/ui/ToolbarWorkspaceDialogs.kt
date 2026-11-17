package com.xnotes.ui

import com.xnotes.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarTransferDraft
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarWorkspacePreset
import com.xnotes.ui.icons.XnotesIcons

/** Review the destination as it will look, including existing bars and omitted tools. */
@Composable
internal fun ToolbarCopyDialog(
    targetLabel: String,
    candidate: ToolbarWorkspace,
    draft: ToolbarTransferDraft,
    look: ToolbarLook,
    colors: List<Rgba>,
    activeColorIndex: Int,
    sharedCanvasWillDisable: Boolean,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(toolbarStrings.getString(R.string.toolbar_config_copy_bar_to, targetLabel)) },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(toolbarStrings.getString(R.string.toolbar_config_compatible_tools, draft.retainedCount))
                ToolbarWorkspacePreview(
                    workspace = candidate,
                    look = look,
                    selectedId = draft.bar.id,
                    selectedEntry = null,
                    colors = colors,
                    activeColorIndex = activeColorIndex,
                    pageNumber = 1,
                    pageCount = 1,
                    zoomLabel = "100%",
                    titleLabel = targetLabel,
                    onSelectBar = {},
                    onSelectEntry = { _, _, _ -> },
                    onMoveBar = { _, _, _ -> },
                )
                if (draft.omitted.isNotEmpty()) {
                    Text(toolbarStrings.getString(R.string.toolbar_config_omitted_tools, draft.omitted.size) +
                        draft.omitted.joinToString { toolbarStrings.getString(it.labelRes) })
                } else Text(toolbarStrings.getString(R.string.toolbar_config_all_tools_are_compatible))
                if (sharedCanvasWillDisable) {
                    Text(toolbarStrings.getString(R.string.toolbar_config_xcanvas_uses_the_shared_profile_confirming_will_enable_its_individual_profi))
                }
                Text(toolbarStrings.getString(R.string.toolbar_config_bar_dimensions_sections_spacing_and_alignment_will_be_preserved))
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy, enabled = draft.retainedCount > 0) { Text(toolbarStrings.getString(R.string.toolbar_config_copy_bar)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(toolbarStrings.getString(R.string.toolbar_config_cancel)) } },
    )
}

/** A compact library with saved profiles and built-in layouts in separate sections. */
@Composable
internal fun ToolbarPresetsDialog(
    presets: List<ToolbarWorkspacePreset>,
    templates: List<ToolbarWorkspacePreset>,
    statusMessage: String?,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onUseTemplate: (ToolbarWorkspacePreset) -> Unit,
    onSave: (String) -> Boolean,
    onRestore: (ToolbarWorkspacePreset) -> Unit,
    onDelete: (String) -> Unit,
    onExportOne: (ToolbarWorkspacePreset) -> Unit,
    onExportCurrent: () -> Unit,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var libraryMenuOpen by remember { mutableStateOf(false) }
    var presetMenuOpen by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(toolbarStrings.getString(R.string.toolbar_config_workspaces)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedTab == 0, onClick = { onSelectTab(0) }, label = { Text(toolbarStrings.getString(R.string.toolbar_config_my_saved_workspaces)) })
                    FilterChip(selected = selectedTab == 1, onClick = { onSelectTab(1) }, label = { Text(toolbarStrings.getString(R.string.toolbar_config_templates)) })
                }
                statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (selectedTab == 0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { saving = !saving; error = null }) { Text(toolbarStrings.getString(R.string.toolbar_config_save_current)) }
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { libraryMenuOpen = true }) {
                                Icon(XnotesIcons.more, contentDescription = toolbarStrings.getString(R.string.toolbar_config_import_and_export_workspaces))
                            }
                            DropdownMenu(
                                expanded = libraryMenuOpen,
                                onDismissRequest = { libraryMenuOpen = false },
                            ) {
                                DropdownMenuItem(text = { Text(toolbarStrings.getString(R.string.toolbar_config_import)) }, onClick = {
                                    libraryMenuOpen = false
                                    onImport()
                                })
                                DropdownMenuItem(text = { Text(toolbarStrings.getString(R.string.toolbar_config_export_current_workspace)) }, onClick = {
                                    libraryMenuOpen = false
                                    onExportCurrent()
                                })
                                if (presets.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text(toolbarStrings.getString(R.string.toolbar_config_export_all_saved_workspaces)) }, onClick = {
                                        libraryMenuOpen = false
                                        onExportAll()
                                    })
                                }
                            }
                        }
                    }
                    if (saving) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(48); error = null },
                            label = { Text(toolbarStrings.getString(R.string.toolbar_config_workspace_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                if (onSave(name)) {
                                    saving = false
                                    name = ""
                                    error = null
                                } else error = toolbarStrings.getString(R.string.toolbar_config_use_a_different_name_maximum_20)
                            }) { Text(toolbarStrings.getString(R.string.toolbar_config_save)) }
                            TextButton(onClick = { saving = false }) { Text(toolbarStrings.getString(R.string.toolbar_config_cancel)) }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (presets.isEmpty()) Text(toolbarStrings.getString(R.string.toolbar_config_no_saved_workspaces_yet))
                    presets.forEach { preset ->
                        Column(
                            Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .padding(4.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f)
                                        .clickable { onRestore(preset) }
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                    Text(toolbarStrings.getString(R.string.toolbar_config_profile_bar_counts, preset.workspaces.note.bars.size, preset.workspaces.canvas.bars.size),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Box {
                                    IconButton(onClick = { presetMenuOpen = preset.id }) {
                                        Icon(XnotesIcons.more, contentDescription = toolbarStrings.getString(R.string.toolbar_config_workspace_options, preset.name))
                                    }
                                    DropdownMenu(
                                        expanded = presetMenuOpen == preset.id,
                                        onDismissRequest = { presetMenuOpen = null },
                                    ) {
                                        DropdownMenuItem(text = { Text(toolbarStrings.getString(R.string.toolbar_config_export)) }, onClick = {
                                            presetMenuOpen = null
                                            onExportOne(preset)
                                        })
                                        DropdownMenuItem(text = { Text(toolbarStrings.getString(R.string.toolbar_config_delete)) }, onClick = {
                                            presetMenuOpen = null
                                            pendingDelete = preset.id
                                        })
                                    }
                                }
                            }
                            if (pendingDelete == preset.id) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { onDelete(preset.id); pendingDelete = null }) { Text(toolbarStrings.getString(R.string.toolbar_config_confirm_deletion)) }
                                    TextButton(onClick = { pendingDelete = null }) { Text(toolbarStrings.getString(R.string.toolbar_config_cancel)) }
                                }
                            }
                        }
                    }
                } else {
                    templates.forEach { template ->
                        Column(
                            Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .clickable { onUseTemplate(template) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(template.name, style = MaterialTheme.typography.titleSmall)
                            Text(workspaceTemplateDescription(toolbarStrings, template.id),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(toolbarStrings.getString(R.string.toolbar_config_close)) } },
    )
}

private fun workspaceTemplateDescription(toolbarStrings: android.content.Context, id: String): String = when (id) {
    "builtin-original" -> toolbarStrings.getString(R.string.toolbar_config_the_default_xnotes_toolbar_for_a_fresh_start)
    "builtin-focus" -> toolbarStrings.getString(R.string.toolbar_config_one_compact_bottom_bar_for_distraction_free_writing)
    "builtin-study" -> toolbarStrings.getString(R.string.toolbar_config_navigation_at_the_top_and_a_separate_writing_bar_at_the_bottom)
    "builtin-pdf" -> toolbarStrings.getString(R.string.toolbar_config_document_navigation_above_and_annotation_tools_on_the_right)
    "builtin-drawing" -> toolbarStrings.getString(R.string.toolbar_config_drawing_tools_in_two_rows_a_bottom_palette_and_actions_at_the_top)
    "builtin-markup" -> toolbarStrings.getString(R.string.toolbar_config_a_bottom_markup_bar_with_separate_document_controls)
    "builtin-complete" -> toolbarStrings.getString(R.string.toolbar_config_all_main_actions_at_the_top_aligned_at_the_start_center_and_end)
    "builtin-two-rows" -> toolbarStrings.getString(R.string.toolbar_config_tools_in_two_rows_at_the_bottom_and_navigation_at_the_top)
    else -> toolbarStrings.getString(R.string.toolbar_config_another_tool_arrangement)
}

/** Preview both profiles before replacing the live workspace with a saved or built-in layout. */
@Composable
internal fun ToolbarPresetPreviewDialog(
    preset: ToolbarWorkspacePreset,
    canvasInitially: Boolean,
    onApply: (ToolbarContext?) -> Unit,
    onDismiss: () -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    var canvasPreview by remember(preset.id) { mutableStateOf(canvasInitially) }
    var applyBoth by remember(preset.id) { mutableStateOf(false) }
    val context = if (canvasPreview) ToolbarContext.CANVAS else ToolbarContext.NOTE
    val workspace = preset.workspaces.forContext(context)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preset.name) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !canvasPreview, onClick = { canvasPreview = false },
                        label = { Text("XNotes") })
                    FilterChip(selected = canvasPreview, onClick = { canvasPreview = true },
                        label = { Text("XCanvas") })
                }
                ToolbarWorkspacePreview(
                    workspace = workspace,
                    look = preset.look,
                    selectedId = workspace.bars.first().id,
                    selectedEntry = null,
                    colors = preset.colors.take(preset.colorCount),
                    activeColorIndex = 0,
                    pageNumber = 1,
                    pageCount = 1,
                    zoomLabel = "100%",
                    titleLabel = if (canvasPreview) "Canvas" else "Note",
                    onSelectBar = {},
                    onSelectEntry = { _, _, _ -> },
                    onMoveBar = { _, _, _ -> },
                )
                FilterChip(
                    selected = applyBoth,
                    onClick = { applyBoth = !applyBoth },
                    label = { Text(toolbarStrings.getString(R.string.toolbar_config_also_apply_to_the_other_editor)) },
                )
                Text(if (applyBoth)
                    toolbarStrings.getString(R.string.toolbar_config_replaces_xnotes_and_xcanvas_bars_along_with_the_global_appearance_and_palet)
                    else toolbarStrings.getString(R.string.toolbar_config_apply_one_description, if (canvasPreview) "XCanvas" else "XNotes"))
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(if (applyBoth) null else context) }) {
                Text(if (applyBoth) toolbarStrings.getString(R.string.toolbar_config_apply_to_both) else toolbarStrings.getString(R.string.toolbar_config_apply_to, if (canvasPreview) "XCanvas" else "XNotes"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(toolbarStrings.getString(R.string.toolbar_config_back_to_list)) } },
    )
}
