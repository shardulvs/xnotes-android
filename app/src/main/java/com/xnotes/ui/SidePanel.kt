package com.xnotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SidePanel(editor: Editor) {
    val palette = LocalPalette.current
    var tab by remember { mutableStateOf(0) }
    Column(
        Modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(palette.panel.toComposeColor()),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            SegIcon(XnotesIcons.thumbnails, "Pages", tab == 0) { tab = 0 }
            SegIcon(XnotesIcons.contents, "Contents", tab == 1) { tab = 1 }
            SegIcon(XnotesIcons.bookmark, "Bookmarks", tab == 2) { tab = 2 }
        }
        Box(Modifier.fillMaxWidth().width(224.dp)) {
            when (tab) {
                0 -> PagesTab(editor)
                1 -> EmptyHint("— no table of contents —")
                else -> BookmarksTab(editor)
            }
        }
    }
}

@Composable
private fun SegIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (selected) palette.accent.toComposeColor() else palette.textDim.toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PagesTab(editor: Editor) {
    val palette = LocalPalette.current
    LazyColumn(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(editor.pageCount) { index ->
            val current = index == editor.pageIndex
            val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, index, editor.contentVersion, editor.pageCount) {
                value = withContext(Dispatchers.Default) { editor.renderThumbnail(index, 300)?.asImageBitmap() }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(150.dp)
                        .border(if (current) 2.dp else 1.dp, (if (current) palette.accent else palette.border).toComposeColor())
                        .clickable { editor.goToPage(index) },
                ) {
                    bitmap?.let { Image(it, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxWidth()) }
                }
                Text(
                    "%02d".format(index + 1),
                    color = (if (current) palette.accent else palette.textDim).toComposeColor(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun BookmarksTab(editor: Editor) {
    val palette = LocalPalette.current
    var showAdd by remember { mutableStateOf(false) }
    // Read the version so the list recomposes on add/remove.
    val version = editor.bookmarkVersion
    val bookmarks = remember(version) { editor.bookmarks }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { showAdd = true }) {
                Icon(XnotesIcons.plus, "Add bookmark", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
            }
        }
        if (bookmarks.isEmpty()) {
            EmptyHint("— no bookmarks —")
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(bookmarks) { i, bm ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editor.goToPage(bm.page) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${bm.label} · p.${bm.page + 1}",
                            color = palette.text.toComposeColor(),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { editor.removeBookmark(i) }) {
                            Icon(XnotesIcons.trash, "Remove", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var label by remember { mutableStateOf("Page ${editor.pageIndex + 1}") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add bookmark") },
            text = { OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { editor.addBookmark(label.ifBlank { "Page ${editor.pageIndex + 1}" }); showAdd = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
            containerColor = palette.menuBg.toComposeColor(),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 12.sp)
    }
}
