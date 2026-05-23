package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.tools.Tool
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

private val toolIcons: List<Pair<Tool, ImageVector>> = listOf(
    Tool.PEN to XnotesIcons.pen,
    Tool.CALLIGRAPHY to XnotesIcons.calligraphy,
    Tool.HIGHLIGHTER to XnotesIcons.highlighter,
    Tool.ERASER to XnotesIcons.eraser,
    Tool.PAN to XnotesIcons.pan,
    Tool.SELECT to XnotesIcons.select,
    Tool.LASSO to XnotesIcons.lasso,
    Tool.SHAPE to XnotesIcons.shape,
    Tool.TEXT to XnotesIcons.text,
)

@Composable
fun Toolbar(
    editor: Editor,
    onToggleFullscreen: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onPreferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    var configForTool by remember { mutableStateOf<Tool?>(null) }
    var switcherIndex by remember { mutableStateOf<Int?>(null) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(palette.panel.toComposeColor())
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileMenu(editor, onOpen, onSave, onSaveAs)
        EditMenu(editor, onPreferences)
        Label(editor.title + if (editor.dirty) " *" else "")
        Separator()

        ToolbarIcon(XnotesIcons.sidebar, "Side panel", active = editor.sidebarVisible) { editor.toggleSidebar() }
        Separator()

        // Tools (P C H E | Pan V L | S T); re-clicking the armed stroke/shape tool opens its popup.
        toolIcons.forEachIndexed { i, (tool, icon) ->
            Box {
                ToolbarIcon(icon, tool.name, active = editor.tool == tool) {
                    if (editor.tool == tool && (tool.isStroke || tool == Tool.SHAPE)) {
                        configForTool = tool
                    } else {
                        editor.selectTool(tool)
                        configForTool = null
                    }
                }
                if (configForTool == tool) {
                    if (tool == Tool.SHAPE) {
                        ShapeConfigPopup(editor) { configForTool = null }
                    } else {
                        ToolConfigPopup(editor, tool) { configForTool = null }
                    }
                }
            }
            if (i == 3 || i == 6) Separator()
        }
        Separator()

        // Ink swatches; re-clicking the active swatch opens the colour switcher.
        editor.toolbarColors.forEachIndexed { i, color ->
            Box {
                Swatch(
                    color = color.toComposeColor(),
                    active = i == editor.activeColorIndex,
                    onClick = { if (i == editor.activeColorIndex) switcherIndex = i else editor.pickColor(i) },
                )
                if (switcherIndex == i) ColorSwitcherPopup(editor, i) { switcherIndex = null }
            }
        }
        Separator()

        ToolbarIcon(XnotesIcons.undo, "Undo", enabled = editor.canUndo) { editor.undo() }
        ToolbarIcon(XnotesIcons.redo, "Redo", enabled = editor.canRedo) { editor.redo() }
        Separator()

        // Page navigation
        ToolbarIcon(XnotesIcons.prev, "Previous page") { editor.prevPage() }
        Label("${editor.pageIndex + 1} / ${editor.pageCount}")
        ToolbarIcon(XnotesIcons.next, "Next page") { editor.nextPage() }
        PageMenu(editor)
        Separator()

        // Zoom
        ToolbarIcon(XnotesIcons.zoomOut, "Zoom out") { editor.zoomOut() }
        Label("${editor.zoomPercent}%")
        ToolbarIcon(XnotesIcons.zoomIn, "Zoom in") { editor.zoomIn() }
        FitMenu(editor)
        Separator()

        RenderMenu(editor)
        ToolbarIcon(XnotesIcons.fullscreen, "Full screen") { onToggleFullscreen() }
        ToolbarIcon(XnotesIcons.present, "Present") { editor.message = "Presentation arrives in a later build." }
    }
}

@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val tint = when {
        !enabled -> com.xnotes.ui.theme.Palette.DISABLED_ICON.toComposeColor()
        active -> palette.accent.toComposeColor()
        else -> palette.textDim.toComposeColor()
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(42.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Swatch(color: androidx.compose.ui.graphics.Color, active: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(28.dp)
            .then(if (active) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun Separator() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(26.dp)
            .background(LocalPalette.current.border.toComposeColor()),
    )
}

@Composable
private fun FileMenu(editor: Editor, onOpen: () -> Unit, onSave: () -> Unit, onSaveAs: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.file, "File") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("New note") }, onClick = { editor.newNote(); expanded = false })
            DropdownMenuItem(text = { Text("Open…") }, onClick = { onOpen(); expanded = false })
            DropdownMenuItem(text = { Text("Save") }, onClick = { onSave(); expanded = false })
            DropdownMenuItem(text = { Text("Save as…") }, onClick = { onSaveAs(); expanded = false })
        }
    }
}

@Composable
private fun EditMenu(editor: Editor, onPreferences: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.edit, "Edit") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Undo") }, enabled = editor.canUndo, onClick = { editor.undo(); expanded = false })
            DropdownMenuItem(text = { Text("Redo") }, enabled = editor.canRedo, onClick = { editor.redo(); expanded = false })
            DropdownMenuItem(text = { Text("Delete selection") }, enabled = editor.hasSelection, onClick = { editor.deleteSelection(); expanded = false })
            DropdownMenuItem(text = { Text("Bring to front") }, enabled = editor.hasSelection, onClick = { editor.bringToFront(); expanded = false })
            DropdownMenuItem(text = { Text("Select all") }, onClick = { editor.selectAll(); expanded = false })
            DropdownMenuItem(text = { Text("Preferences…") }, onClick = { onPreferences(); expanded = false })
        }
    }
}

@Composable
private fun PageMenu(editor: Editor) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.page, "Pages") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Add page") }, onClick = { editor.addPage(); expanded = false })
            DropdownMenuItem(text = { Text("Delete current page") }, onClick = { editor.deleteCurrentPage(); expanded = false })
        }
    }
}

@Composable
private fun FitMenu(editor: Editor) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.fit, "Fit") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Fit whole page") }, onClick = { editor.fitPage(); expanded = false })
            DropdownMenuItem(text = { Text("Fit page width") }, onClick = { editor.fitWidth(); expanded = false })
            DropdownMenuItem(text = { Text("Fit page height") }, onClick = { editor.fitHeight(); expanded = false })
        }
    }
}

@Composable
private fun RenderMenu(editor: Editor) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(54.dp)) {
            Text(
                "DPI ${formatScale(editor.renderScale)}",
                color = LocalPalette.current.textDim.toComposeColor(),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(1.0, 1.5, 2.0).forEach { scale ->
                DropdownMenuItem(
                    text = { Text("${formatScale(scale)} render") },
                    onClick = { editor.applyRenderScale(scale); expanded = false },
                )
            }
        }
    }
}

private fun formatScale(s: Double): String =
    if (s == s.toLong().toDouble()) "${s.toLong()}x" else "${s}x"
