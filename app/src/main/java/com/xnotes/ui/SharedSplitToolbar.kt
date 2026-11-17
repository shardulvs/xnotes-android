package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.tools.*
import com.xnotes.settings.ToolbarLook
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Only the host draws the bar. Each surface reserves the part that actually overlaps it. */
internal class SharedToolbarOcclusion {
    var bar by mutableStateOf(Rect.Zero)

    fun insets(pane: Rect, look: ToolbarLook): ToolbarPaneInsets =
        if (!look.floating) ToolbarPaneInsets() else sharedToolbarInsets(
            ToolbarPaneBounds(bar.left.toDouble(), bar.top.toDouble(), bar.right.toDouble(), bar.bottom.toDouble()),
            ToolbarPaneBounds(pane.left.toDouble(), pane.top.toDouble(), pane.right.toDouble(), pane.bottom.toDouble()),
            look.position.id,
        )
}

/** Preserve the chosen interface's order, but never send a page command to a hidden note. */
@Composable
internal fun UnavailableSharedToolbarItem(item: ToolbarItem) {
    val description = stringResource(R.string.split_tool_unavailable, stringResource(item.labelRes))
    Box(Modifier.clearAndSetSemantics { contentDescription = description; disabled() }) {
        ToolbarIcon(toolbarItemIcon(item), description, enabled = false) {}
    }
}

/** A single document switcher above the common tools, with local close actions. */
@Composable
internal fun SplitWorkspaceHeader(
    app: Editor,
    second: Editor,
    mixed: Boolean,
    onClose: (Editor) -> Unit,
) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(palette.panel.toComposeColor()),
        verticalAlignment = Alignment.CenterVertically) {
        for (pane in listOf(app, second)) {
            val active = app.active === pane
            Row(Modifier.weight(1f).heightIn(min = 48.dp)
                .background((if (active) palette.selectionBackground else palette.panel).toComposeColor())
                .clickable { app.focusPane(pane.pane) }.padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val title = if (pane.canvasOpen) pane.infinite.title else pane.title
                Text(title.ifEmpty { stringResource(R.string.untitled) }, Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = (if (active) palette.selectionForeground else palette.textDim).toComposeColor(),
                    style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { onClose(pane) }) {
                    Icon(XnotesIcons.close, stringResource(R.string.split_close_document, title),
                        tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                }
            }
        }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(XnotesIcons.sliders, stringResource(R.string.split_settings),
                    tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val preferences = remember(app.prefsVersion) { app.preferences }
                Column(Modifier.width(300.dp).padding(12.dp)) {
                    Text(stringResource(R.string.split_settings), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    SplitToolbarSettings(preferences, mixed) { app.applyHomePreferences(it) }
                }
            }
        }
    }
}
