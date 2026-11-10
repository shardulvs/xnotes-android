package com.xnotes.ui

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copiar barra para $targetLabel") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pré-visualização no destino · ${draft.retainedCount} elementos compatíveis")
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
                    onMoveBar = { _, _ -> },
                )
                if (draft.omitted.isNotEmpty()) {
                    Text("Não serão copiados (${draft.omitted.size}): " +
                        draft.omitted.joinToString { it.id.replace('_', ' ') })
                } else Text("Todos os elementos são compatíveis.")
                if (sharedCanvasWillDisable) {
                    Text("O XCanvas usa o perfil compartilhado. Ao confirmar, seu perfil individual será ativado.")
                }
                Text("Dimensões, seções, espaçamento e alinhamento da barra serão preservados.")
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy, enabled = draft.retainedCount > 0) { Text("Copiar barra") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
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
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var libraryMenuOpen by remember { mutableStateOf(false) }
    var presetMenuOpen by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workspaces") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedTab == 0, onClick = { onSelectTab(0) }, label = { Text("Meus salvos") })
                    FilterChip(selected = selectedTab == 1, onClick = { onSelectTab(1) }, label = { Text("Modelos") })
                }
                statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (selectedTab == 0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { saving = !saving; error = null }) { Text("Salvar atual") }
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { libraryMenuOpen = true }) {
                                Icon(XnotesIcons.more, contentDescription = "Importar e exportar workspaces")
                            }
                            DropdownMenu(
                                expanded = libraryMenuOpen,
                                onDismissRequest = { libraryMenuOpen = false },
                            ) {
                                DropdownMenuItem(text = { Text("Importar") }, onClick = {
                                    libraryMenuOpen = false
                                    onImport()
                                })
                                DropdownMenuItem(text = { Text("Exportar workspace atual") }, onClick = {
                                    libraryMenuOpen = false
                                    onExportCurrent()
                                })
                                if (presets.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text("Exportar todos os salvos") }, onClick = {
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
                            label = { Text("Nome do workspace") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                if (onSave(name)) {
                                    saving = false
                                    name = ""
                                    error = null
                                } else error = "Use um nome diferente (limite de 20)."
                            }) { Text("Salvar") }
                            TextButton(onClick = { saving = false }) { Text("Cancelar") }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (presets.isEmpty()) Text("Nenhum workspace salvo ainda.")
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
                                    Text("${preset.workspaces.note.bars.size} barras XNotes · ${preset.workspaces.canvas.bars.size} XCanvas",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Box {
                                    IconButton(onClick = { presetMenuOpen = preset.id }) {
                                        Icon(XnotesIcons.more, contentDescription = "Opções de ${preset.name}")
                                    }
                                    DropdownMenu(
                                        expanded = presetMenuOpen == preset.id,
                                        onDismissRequest = { presetMenuOpen = null },
                                    ) {
                                        DropdownMenuItem(text = { Text("Exportar") }, onClick = {
                                            presetMenuOpen = null
                                            onExportOne(preset)
                                        })
                                        DropdownMenuItem(text = { Text("Excluir") }, onClick = {
                                            presetMenuOpen = null
                                            pendingDelete = preset.id
                                        })
                                    }
                                }
                            }
                            if (pendingDelete == preset.id) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { onDelete(preset.id); pendingDelete = null }) { Text("Confirmar exclusão") }
                                    TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
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
                            Text(workspaceTemplateDescription(template.id),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

private fun workspaceTemplateDescription(id: String): String = when (id) {
    "builtin-original" -> "Barra padrão do XNotes, para recomeçar uma personalização."
    "builtin-focus" -> "Uma única barra inferior, compacta, para escrever sem distrações."
    "builtin-study" -> "Navegação superior e escrita em uma barra independente na parte inferior."
    "builtin-pdf" -> "Navegação de documentos acima e ferramentas de anotação à direita."
    "builtin-drawing" -> "Traços organizados em duas linhas, paleta inferior e ações no topo."
    "builtin-markup" -> "Estojo inferior de marcação com controles de documento separados."
    "builtin-complete" -> "Todas as ações principais no topo, alinhadas em início, centro e fim."
    "builtin-two-rows" -> "Ferramentas em duas fileiras na parte inferior e navegação no topo."
    else -> "Outra disposição de ferramentas."
}

/** Preview both profiles before replacing the live workspace with a saved or built-in layout. */
@Composable
internal fun ToolbarPresetPreviewDialog(
    preset: ToolbarWorkspacePreset,
    canvasInitially: Boolean,
    onApply: (ToolbarContext?) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    onMoveBar = { _, _ -> },
                )
                FilterChip(
                    selected = applyBoth,
                    onClick = { applyBoth = !applyBoth },
                    label = { Text("Aplicar também ao outro editor") },
                )
                Text(if (applyBoth)
                    "Substitui as barras de XNotes e XCanvas, além da aparência e paleta globais."
                    else "Substitui somente as barras de ${if (canvasPreview) "XCanvas" else "XNotes"}. O outro editor, as cores e a aparência globais serão preservados.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(if (applyBoth) null else context) }) {
                Text(if (applyBoth) "Aplicar nos dois" else "Aplicar em ${if (canvasPreview) "XCanvas" else "XNotes"}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Voltar à lista") } },
    )
}
