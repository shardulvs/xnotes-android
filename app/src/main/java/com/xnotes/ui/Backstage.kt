package com.xnotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The full-screen "File" area (an office-suite-style backstage): a command rail on
 * the left (New/Open/Save/…/Preferences) and a gallery of recent notes — each a
 * live first-page thumbnail — on the right. Opening a recent note routes through
 * [onOpenRecent] so the activity can guard unsaved changes.
 */
@Composable
fun Backstage(
    editor: Editor,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onImportPdf: () -> Unit,
    onExportPdf: () -> Unit,
    onPreferences: () -> Unit,
    onOpenRecent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .fillMaxSize()
                .background(palette.menuBg.toComposeColor()),
        ) {
            // Left command rail.
            Column(
                Modifier
                    .width(264.dp)
                    .fillMaxHeight()
                    .background(palette.panel.toComposeColor())
                    .padding(vertical = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(XnotesIcons.close, "Close", tint = palette.text.toComposeColor(), modifier = Modifier.size(22.dp))
                    }
                    Text(
                        "xnotes",
                        color = palette.text.toComposeColor(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Command(XnotesIcons.plus, "New", onNew)
                Command(XnotesIcons.folder, "Open…", onOpen)
                RailDivider()
                Command(XnotesIcons.save, "Save", onSave)
                Command(XnotesIcons.copy, "Save As…", onSaveAs)
                RailDivider()
                Command(XnotesIcons.importDoc, "Import PDF…", onImportPdf)
                Command(XnotesIcons.exportDoc, "Export to PDF…", onExportPdf)
                RailDivider()
                Command(XnotesIcons.sliders, "Preferences…", onPreferences)
            }

            // Right recent-notes gallery.
            Column(Modifier.weight(1f).fillMaxHeight().padding(28.dp)) {
                Text("Recent notes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(18.dp))
                val recents = editor.recentFiles
                if (recents.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No recent notes yet — saved notes will appear here.",
                            color = palette.textDim.toComposeColor(),
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 168.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(recents) { uri ->
                            RecentCard(editor, uri) { onOpenRecent(uri) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Command(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = palette.accent.toComposeColor(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = palette.text.toComposeColor(), fontSize = 15.sp)
    }
}

@Composable
private fun RailDivider() {
    HorizontalDivider(
        color = LocalPalette.current.border.toComposeColor(),
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
private fun RecentCard(editor: Editor, uri: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val data by produceState<Pair<ImageBitmap?, String>?>(null, uri, editor.contentVersion) {
        value = withContext(Dispatchers.IO) {
            val bmp = editor.renderRecentThumbnail(uri, 360)?.asImageBitmap()
            bmp to editor.recentLabel(uri)
        }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(196.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.paper.toComposeColor())
                .border(1.dp, palette.paperBorder.toComposeColor(), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = data?.first
            if (bmp != null) {
                Image(
                    bmp,
                    contentDescription = data?.second,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(1.dp),
                )
            } else {
                Icon(XnotesIcons.file, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            data?.second ?: "…",
            color = palette.text.toComposeColor(),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}
