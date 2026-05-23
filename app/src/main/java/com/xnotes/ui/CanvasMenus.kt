package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Floating action bar shown above a settled selection (spec-adjacent): delete,
 * cut, copy, bring-to-front, duplicate. Hidden while moving/resizing.
 */
@Composable
fun SelectionMenu(editor: Editor) {
    val rect = editor.selectionMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { 248.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + gap
    }
    val xDp = with(density) { xPx.toDp() }
    val yDp = with(density) { yPx.toDp() }

    Row(
        modifier = Modifier
            .offset(xDp, yDp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(10.dp)),
    ) {
        ActionIcon(XnotesIcons.trash, "Delete") { editor.deleteSelection() }
        ActionIcon(XnotesIcons.cut, "Cut") { editor.cutSelection() }
        ActionIcon(XnotesIcons.copy, "Copy") { editor.copySelection(); editor.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.front, "Bring to front") { editor.bringToFront(); editor.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.duplicate, "Duplicate") { editor.duplicateSelection() }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    IconButton(onClick = onClick, modifier = Modifier.size(46.dp)) {
        Icon(icon, contentDescription = desc, tint = palette.text.toComposeColor(), modifier = Modifier.size(22.dp))
    }
}

/**
 * Long-press paste menu on empty space: paste copied items or an image from the
 * system clipboard at the press point, or insert an image there.
 */
@Composable
fun LongPressMenu(editor: Editor, onInsertImageAt: (com.xnotes.core.geometry.Pt) -> Unit) {
    val target = editor.contextMenu ?: return
    val density = LocalDensity.current
    val xDp = with(density) { target.viewportX.toFloat().toDp() }
    val yDp = with(density) { target.viewportY.toFloat().toDp() }

    Box(modifier = Modifier.offset(xDp, yDp).size(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = { editor.dismissContextMenu() }) {
            if (editor.hasClipboardItems) {
                DropdownMenuItem(text = { Text("Paste here") }, onClick = {
                    editor.pasteItemsAt(target.content); editor.dismissContextMenu()
                })
            }
            if (editor.clipboardHasImage) {
                DropdownMenuItem(text = { Text("Paste image") }, onClick = {
                    editor.pasteClipboardImageAt(target.content); editor.dismissContextMenu()
                })
            }
            DropdownMenuItem(text = { Text("Insert image…") }, onClick = {
                onInsertImageAt(target.content); editor.dismissContextMenu()
            })
        }
    }
}
