package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.ui.icons.XnotesIcons

/**
 * Pure presentation of the context/bar selector and bar-actions menus. The
 * caller retains the editor transaction, placement checks and persistence, so
 * opening a popup never changes a workspace or the current drag state.
 */
@Composable
internal fun ToolbarWorkspaceSelector(
    canvasTab: Boolean,
    workspace: ToolbarWorkspace,
    selectedBarId: String,
    onContextSelected: (Boolean) -> Unit,
    onBarSelected: (String) -> Unit,
    onResetSelectedBar: () -> Unit,
    onOpenLibrary: () -> Unit,
    onCreate: () -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onCopy: () -> Unit,
) {
    val strings = LocalContext.current
    var barMenuExpanded by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    val selectedBar = workspace.bars.firstOrNull { it.id == selectedBarId } ?: workspace.bars.first()
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolbarEditorChip("xnote", selected = !canvasTab) { onContextSelected(false) }
        ToolbarEditorChip("xcanvas", selected = canvasTab) { onContextSelected(true) }
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { barMenuExpanded = true }, modifier = Modifier.widthIn(max = 120.dp)) {
                Text("${selectedBar.name} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = barMenuExpanded,
                onDismissRequest = { barMenuExpanded = false }) {
                workspace.bars.forEachIndexed { index, bar ->
                    DropdownMenuItem(
                        text = { Text("${index + 1}. ${bar.name}") },
                        onClick = {
                            onBarSelected(bar.id)
                            barMenuExpanded = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.reset)) }, onClick = {
                    barMenuExpanded = false
                    onResetSelectedBar()
                })
            }
        }
        TextButton(onClick = onOpenLibrary) {
            Text(strings.getString(R.string.toolbar_config_workspaces) + " ▾", maxLines = 1)
        }
        Box {
            IconButton(onClick = { actionsExpanded = true }) {
                Icon(XnotesIcons.more, strings.getString(R.string.toolbar_config_create_duplicate_or_copy_a_bar))
            }
            DropdownMenu(expanded = actionsExpanded,
                onDismissRequest = { actionsExpanded = false }) {
                Column(Modifier.widthIn(min = 290.dp).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { actionsExpanded = false; onCreate() }) {
                        Text(stringResource(R.string.add) + " " + stringResource(R.string.pref_toolbar))
                    }
                    TextButton(onClick = { actionsExpanded = false; onDuplicate() }) {
                        Text(stringResource(R.string.duplicate))
                    }
                    if (workspace.bars.size > 1 && selectedBar.id != workspace.bars.first().id) {
                        TextButton(onClick = { actionsExpanded = false; onRemove() }) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
                TextButton(onClick = { actionsExpanded = false; onCopy() }) {
                    Text(strings.getString(R.string.toolbar_config_copy_to,
                        if (canvasTab) "XNotes" else "XCanvas"))
                }
            }
        }
    }
}
