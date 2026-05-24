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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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

/** Which pane the backstage shows on the right. */
enum class BackstageView { RECENT, EXPLORER, PREFERENCES }

/**
 * The full-screen "File" area (an office-suite-style backstage): a command rail on
 * the left and a switchable pane on the right — recent notes (grid/list), a custom
 * file explorer rooted at a folder the user granted, or preferences.
 */
@Composable
fun Backstage(
    editor: Editor,
    view: BackstageView,
    onSelectView: (BackstageView) -> Unit,
    onNew: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
    onImportPdf: () -> Unit,
    onExportPdf: () -> Unit,
    onOpenRecent: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
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
                Command(XnotesIcons.home, "Home", selected = view == BackstageView.RECENT) { onSelectView(BackstageView.RECENT) }
                Command(XnotesIcons.plus, "New") { onNew() }
                Command(XnotesIcons.folder, "Open…", selected = view == BackstageView.EXPLORER) { onSelectView(BackstageView.EXPLORER) }
                RailDivider()
                Command(XnotesIcons.save, "Save") { onSave() }
                Command(XnotesIcons.copy, "Save As…") { onSaveAs() }
                Command(XnotesIcons.share, "Share…") { onShare() }
                RailDivider()
                Command(XnotesIcons.importDoc, "Import PDF…") { onImportPdf() }
                Command(XnotesIcons.exportDoc, "Export to PDF…") { onExportPdf() }
                RailDivider()
                Command(XnotesIcons.sliders, "Preferences", selected = view == BackstageView.PREFERENCES) { onSelectView(BackstageView.PREFERENCES) }
            }

            // Right pane.
            Box(Modifier.weight(1f).fillMaxHeight().padding(28.dp)) {
                when (view) {
                    BackstageView.RECENT -> RecentPane(editor, onOpenRecent)
                    BackstageView.EXPLORER -> ExplorerPane(editor, onOpenFile, onPickRoot)
                    BackstageView.PREFERENCES -> PreferencesPane(editor)
                }
            }
        }
    }
}

// --- left rail ---

@Composable
private fun Command(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(if (selected) Modifier.background(palette.accentAlpha(38).toComposeColor()) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = palette.accent.toComposeColor(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun RailDivider() {
    HorizontalDivider(
        color = LocalPalette.current.border.toComposeColor(),
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

// --- recent pane ---

@Composable
private fun RecentPane(editor: Editor, onOpenRecent: (String) -> Unit) {
    val palette = LocalPalette.current
    val recents = editor.recentFiles
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent notes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            if (recents.isNotEmpty()) {
                ViewToggle(XnotesIcons.thumbnails, "Grid view", editor.recentGrid) { editor.updateRecentGrid(true) }
                ViewToggle(XnotesIcons.contents, "List view", !editor.recentGrid) { editor.updateRecentGrid(false) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { editor.clearRecentFiles() }) {
                    Icon(XnotesIcons.trash, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear", color = palette.textDim.toComposeColor())
                }
            }
        }
        if (recents.isEmpty()) {
            EmptyPane("No recent notes yet — saved notes will appear here.")
        } else if (editor.recentGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                gridItems(recents) { uri -> RecentCard(editor, uri) { onOpenRecent(uri) } }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(recents) { uri -> RecentRow(editor, uri) { onOpenRecent(uri) } }
            }
        }
    }
}

@Composable
private fun ViewToggle(icon: ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (active) palette.accent.toComposeColor() else palette.textDim.toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Loads (off-thread, cached) a recent note's first-page thumbnail and label. */
@Composable
private fun recentData(editor: Editor, uri: String, widthPx: Int) =
    produceState<Pair<ImageBitmap?, String>?>(null, uri, editor.contentVersion) {
        value = withContext(Dispatchers.IO) {
            editor.renderRecentThumbnail(uri, widthPx)?.asImageBitmap() to editor.recentLabel(uri)
        }
    }

@Composable
private fun RecentCard(editor: Editor, uri: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val data by recentData(editor, uri, 360)
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
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
                Image(bmp, data?.second, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(1.dp))
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

@Composable
private fun RecentRow(editor: Editor, uri: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val data by recentData(editor, uri, 160)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(72.dp, 54.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(palette.paper.toComposeColor())
                .border(1.dp, palette.paperBorder.toComposeColor(), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = data?.first
            if (bmp != null) {
                Image(bmp, data?.second, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(1.dp))
            } else {
                Icon(XnotesIcons.file, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            data?.second ?: "…",
            color = palette.text.toComposeColor(),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- explorer pane ---

@Composable
private fun ExplorerPane(editor: Editor, onOpenFile: (String) -> Unit, onPickRoot: () -> Unit) {
    val palette = LocalPalette.current
    val root = editor.browseRoot
    if (root == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Open", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
            Icon(XnotesIcons.folder, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(14.dp))
            Text("Choose a folder to browse your notes in.", color = palette.textDim.toComposeColor(), fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Choose folder", onPickRoot)
            Spacer(Modifier.weight(1f))
        }
        return
    }

    val rootDocId = remember(root) { editor.browseRootDocId(root) }
    // Folders descended into below the root, each (documentId, name). Empty = at root.
    val stack = remember(root) { mutableStateListOf<Pair<String, String>>() }
    val currentDocId = if (stack.isEmpty()) rootDocId else stack.last().first

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onPickRoot) {
                Icon(XnotesIcons.folder, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Change folder", color = palette.accent.toComposeColor())
            }
        }
        // Breadcrumb: home (root) › folder › folder …
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { stack.clear() }, modifier = Modifier.size(28.dp)) {
                Icon(XnotesIcons.home, "Root", tint = (if (stack.isEmpty()) palette.accent else palette.textDim).toComposeColor(), modifier = Modifier.size(18.dp))
            }
            stack.forEachIndexed { i, (_, name) ->
                Text(" ›", color = palette.textDim.toComposeColor(), fontSize = 14.sp)
                Text(
                    " $name",
                    color = (if (i == stack.lastIndex) palette.text else palette.textDim).toComposeColor(),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { while (stack.size > i + 1) stack.removeAt(stack.lastIndex) },
                )
            }
        }
        val entries by produceState<List<BrowseEntry>?>(null, root, currentDocId) {
            value = withContext(Dispatchers.IO) { editor.browseChildren(root, currentDocId) }
        }
        when {
            entries == null -> EmptyPane("Loading…")
            entries!!.isEmpty() -> EmptyPane("This folder has no notes.")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries!!) { entry ->
                    EntryRow(entry) {
                        if (entry.isDir) {
                            stack.add(editor.browseDocId(entry.documentUri) to entry.name)
                        } else {
                            onOpenFile(entry.documentUri)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: BrowseEntry, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDir) XnotesIcons.folder else XnotesIcons.file,
            contentDescription = null,
            tint = (if (entry.isDir) palette.accent else palette.textDim).toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            if (entry.isDir) entry.name else entry.name.removeSuffix(".xnote").removeSuffix(".XNOTE"),
            color = palette.text.toComposeColor(),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- shared bits ---

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.accent.toComposeColor())
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(label, color = palette.bg.toComposeColor(), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyPane(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 14.sp)
    }
}
