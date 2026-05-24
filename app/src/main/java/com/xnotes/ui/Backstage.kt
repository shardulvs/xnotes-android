package com.xnotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which pane the backstage shows on the right. */
enum class BackstageView { RECENT, PREFERENCES }

/** Whether the Home explorer is awaiting a new file/folder name. */
private enum class CreateMode { NONE, FILE, FOLDER }

/**
 * The full-screen "File" area. The rail picks between Home (recent notes + an
 * in-app file explorer rooted at a folder the user granted) and Preferences, and
 * carries the file commands. "Open…" uses the system picker; "New" makes a note
 * in the explorer's current folder (or a blank in-memory note when none is set).
 */
@Composable
fun Backstage(
    editor: Editor,
    view: BackstageView,
    onSelectView: (BackstageView) -> Unit,
    onNewBlank: () -> Unit,
    onOpenSystem: () -> Unit,
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
    var createMode by remember { mutableStateOf(CreateMode.NONE) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.fillMaxSize().background(palette.menuBg.toComposeColor()),
        ) {
            // Left command rail.
            Column(
                Modifier.width(264.dp).fillMaxHeight().background(palette.panel.toComposeColor()).padding(vertical = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(XnotesIcons.close, "Close", tint = palette.text.toComposeColor(), modifier = Modifier.size(22.dp))
                    }
                    Text("xnotes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 2.dp))
                }
                Spacer(Modifier.height(6.dp))
                Command(XnotesIcons.home, "Home", selected = view == BackstageView.RECENT) {
                    createMode = CreateMode.NONE; onSelectView(BackstageView.RECENT)
                }
                Command(XnotesIcons.plus, "New") {
                    if (editor.browseRoot != null) {
                        onSelectView(BackstageView.RECENT); createMode = CreateMode.FILE
                    } else onNewBlank()
                }
                Command(XnotesIcons.folder, "Open…") { onOpenSystem() }
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
                    BackstageView.RECENT -> HomePane(editor, onOpenRecent, onOpenFile, onPickRoot, createMode, { createMode = it }, onDismiss)
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
    HorizontalDivider(color = LocalPalette.current.border.toComposeColor(), modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
}

// --- home pane: recent notes (top strip) + folder explorer (below) ---

@Composable
private fun HomePane(
    editor: Editor,
    onOpenRecent: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
    createMode: CreateMode,
    onCreateMode: (CreateMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val recents = editor.recentFiles
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent notes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            if (recents.isNotEmpty()) {
                TextButton(onClick = { editor.clearRecentFiles() }) {
                    Icon(XnotesIcons.trash, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear", color = palette.textDim.toComposeColor())
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (recents.isEmpty()) {
            Text("No recent notes yet.", color = palette.textDim.toComposeColor(), fontSize = 13.sp)
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                recents.forEach { uri -> RecentStripCard(editor, uri) { onOpenRecent(uri) } }
            }
        }
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = palette.border.toComposeColor())
        Spacer(Modifier.height(18.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ExplorerSection(editor, onOpenFile, onPickRoot, createMode, onCreateMode, onDismiss)
        }
    }
}

/** Loads (off-thread, cached) a recent note's thumbnail + details. */
@Composable
private fun recentInfoState(editor: Editor, uri: String, widthPx: Int) =
    produceState<RecentInfo?>(null, uri, editor.contentVersion) {
        value = withContext(Dispatchers.IO) { editor.recentInfo(uri, widthPx) }
    }

/** "Folder · 3 pages · 2 days ago · 540 kB" — empty parts dropped. */
private fun recentDetails(ctx: android.content.Context, info: RecentInfo): String = listOfNotNull(
    if (info.pageCount > 0) "${info.pageCount} ${if (info.pageCount == 1) "page" else "pages"}" else null,
    if (info.modified > 0)
        android.text.format.DateUtils.getRelativeTimeSpanString(
            info.modified, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS,
        ).toString() else null,
    if (info.sizeBytes > 0) android.text.format.Formatter.formatShortFileSize(ctx, info.sizeBytes) else null,
).joinToString("  ·  ")

@Composable
private fun RecentStripCard(editor: Editor, uri: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val ctx = LocalContext.current
    val info by recentInfoState(editor, uri, 300)
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.paper.toComposeColor())
                .border(1.dp, palette.paperBorder.toComposeColor(), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = info?.thumbnail?.asImageBitmap()
            if (bmp != null) {
                Image(bmp, info?.label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(1.dp))
            } else {
                Icon(XnotesIcons.file, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(info?.label ?: "…", color = palette.text.toComposeColor(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        val details = info?.let { recentDetails(ctx, it) }.orEmpty()
        if (details.isNotEmpty()) {
            Text(details, color = palette.textDim.toComposeColor(), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

// --- explorer section ---

@Composable
private fun ExplorerSection(
    editor: Editor,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
    createMode: CreateMode,
    onCreateMode: (CreateMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val root = editor.browseRoot
    if (root == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Icon(XnotesIcons.folder, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(14.dp))
            Text("Choose a folder to keep and browse your notes in.", color = palette.textDim.toComposeColor(), fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Choose folder", onPickRoot)
            Spacer(Modifier.weight(1f))
        }
        return
    }

    val scope = rememberCoroutineScope()
    val rootDocId = remember(root) { editor.browseRootDocId(root) }
    val stack = remember(root) { mutableStateListOf<Pair<String, String>>() }
    val currentDocId = if (stack.isEmpty()) rootDocId else stack.last().first
    var refreshKey by remember(root) { mutableStateOf(0) }
    var fieldText by remember(root) { mutableStateOf("") }
    var fieldError by remember(root) { mutableStateOf<String?>(null) }
    var renaming by remember(root) { mutableStateOf<String?>(null) }
    var renameText by remember(root) { mutableStateOf("") }
    val rootName by produceState<String?>(null, root) { value = withContext(Dispatchers.IO) { editor.browseRootName(root) } }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(createMode) {
        if (createMode != CreateMode.NONE) { fieldText = ""; fieldError = null; runCatching { fieldFocus.requestFocus() } }
    }

    Column(Modifier.fillMaxSize()) {
        // Folder name + actions.
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(rootName ?: "Folder", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            HeaderButton(XnotesIcons.plus, "New note") { onCreateMode(CreateMode.FILE) }
            HeaderButton(XnotesIcons.newFolder, "New folder") { onCreateMode(CreateMode.FOLDER) }
            HeaderButton(XnotesIcons.folder, "Change folder", onPickRoot)
            HeaderButton(XnotesIcons.close, "Forget folder") { editor.clearBrowseRoot() }
        }
        // Breadcrumb: the granted folder's name (root) › folder › folder …
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { stack.clear() }.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(XnotesIcons.home, "Root", tint = (if (stack.isEmpty()) palette.accent else palette.textDim).toComposeColor(), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(rootName ?: "Folder", color = (if (stack.isEmpty()) palette.text else palette.textDim).toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            stack.forEachIndexed { i, (_, name) ->
                Text("  ›  ", color = palette.textDim.toComposeColor(), fontSize = 14.sp)
                Text(
                    name,
                    color = (if (i == stack.lastIndex) palette.text else palette.textDim).toComposeColor(),
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { while (stack.size > i + 1) stack.removeAt(stack.lastIndex) },
                )
            }
        }
        // Inline create (file or folder).
        if (createMode != CreateMode.NONE) {
            val isFolder = createMode == CreateMode.FOLDER
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = fieldText,
                    onValueChange = { fieldText = it; fieldError = null },
                    singleLine = true,
                    placeholder = { Text(if (isFolder) "Folder name" else "Note name (optional)") },
                    modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                )
                IconButton(onClick = {
                    val n = fieldText.trim()
                    if (isFolder) {
                        if (n.isEmpty()) fieldError = "Enter a folder name."
                        else scope.launch {
                            val ok = withContext(Dispatchers.IO) { editor.createFolder(root, currentDocId, n) }
                            if (ok) { onCreateMode(CreateMode.NONE); refreshKey++ } else fieldError = "Couldn’t create that folder."
                        }
                    } else scope.launch {
                        val uri = withContext(Dispatchers.IO) { editor.createBlankNoteFile(root, currentDocId, n) }
                        if (uri != null) { editor.adoptFolderNote(uri); onCreateMode(CreateMode.NONE); onDismiss() } else fieldError = "Couldn’t create the note."
                    }
                }) { Icon(XnotesIcons.check, "Create", tint = palette.accent.toComposeColor(), modifier = Modifier.size(22.dp)) }
                IconButton(onClick = { onCreateMode(CreateMode.NONE) }) {
                    Icon(XnotesIcons.close, "Cancel", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
                }
            }
            fieldError?.let { Text(it, color = Color(0xFFE5534B), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
        }
        // Listing.
        val entries by produceState<List<BrowseEntry>?>(null, root, currentDocId, refreshKey) {
            value = withContext(Dispatchers.IO) { editor.browseChildren(root, currentDocId) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                entries == null -> EmptyPane("Loading…")
                entries!!.isEmpty() -> EmptyPane("This folder has no notes.")
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(entries!!) { entry ->
                        EntryRow(
                            entry = entry,
                            isRenaming = renaming == entry.documentUri,
                            renameText = renameText,
                            onRenameTextChange = { renameText = it },
                            onClick = {
                                if (entry.isDir) stack.add(editor.browseDocId(entry.documentUri) to entry.name)
                                else onOpenFile(entry.documentUri)
                            },
                            onStartRename = {
                                renaming = entry.documentUri
                                renameText = if (entry.isDir) entry.name else entry.name.removeSuffix(".xnote").removeSuffix(".XNOTE")
                            },
                            onConfirmRename = {
                                val raw = renameText.trim()
                                if (raw.isNotEmpty()) {
                                    val newName = if (entry.isDir || raw.endsWith(".xnote", ignoreCase = true)) raw else "$raw.xnote"
                                    // Renames touch the open-note binding (Compose state) so run on the main thread.
                                    val ok = editor.renameDocument(entry.documentUri, newName)
                                    renaming = null
                                    if (ok) refreshKey++
                                } else renaming = null
                            },
                            onCancelRename = { renaming = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: BrowseEntry,
    isRenaming: Boolean,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onClick: () -> Unit,
    onStartRename: () -> Unit,
    onConfirmRename: () -> Unit,
    onCancelRename: () -> Unit,
) {
    val palette = LocalPalette.current
    val icon = if (entry.isDir) XnotesIcons.folder else XnotesIcons.file
    val tint = (if (entry.isDir) palette.accent else palette.textDim).toComposeColor()
    if (isRenaming) {
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            OutlinedTextField(renameText, onRenameTextChange, singleLine = true, modifier = Modifier.weight(1f).focusRequester(fr))
            IconButton(onClick = onConfirmRename) { Icon(XnotesIcons.check, "Save name", tint = palette.accent.toComposeColor(), modifier = Modifier.size(22.dp)) }
            IconButton(onClick = onCancelRename) { Icon(XnotesIcons.close, "Cancel", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp)) }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(start = 10.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                if (entry.isDir) entry.name else entry.name.removeSuffix(".xnote").removeSuffix(".XNOTE"),
                color = palette.text.toComposeColor(),
                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onStartRename, modifier = Modifier.size(36.dp)) {
                Icon(XnotesIcons.edit, "Rename", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            }
        }
    }
}

// --- shared bits ---

@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    TextButton(onClick = onClick) {
        Icon(icon, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = palette.accent.toComposeColor())
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(palette.accent.toComposeColor()).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
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
