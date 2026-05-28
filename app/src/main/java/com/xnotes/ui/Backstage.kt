package com.xnotes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

/** An entry copied/cut in the explorer; [parentDocId] is the folder it came from (for moves). */
private data class ClipItem(val uri: String, val name: String, val parentDocId: String, val isCut: Boolean)

/** The next free "untitled_N" stem (no extension) for a fresh note in [entries]. */
private fun nextUntitled(entries: List<BrowseEntry>?): String {
    val taken = entries.orEmpty().filter { !it.isDir }.map { it.name.lowercase() }.toSet()
    var n = 1
    while ("untitled_$n.xnote" in taken) n++
    return "untitled_$n"
}

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
    // Two panes need real estate; below this width we stack into a single scrollable
    // menu that pushes Home / Preferences as full-screen sub-pages (phones in portrait).
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FullscreenDialogWindow()
        if (compact) {
            BackstageCompact(
                editor, view, onSelectView, onNewBlank, onOpenSystem, onSave, onSaveAs, onShare,
                onImportPdf, onExportPdf, onOpenRecent, onOpenFile, onPickRoot, onDismiss,
            )
        } else {
            BackstageWide(
                editor, view, onSelectView, onNewBlank, onOpenSystem, onSave, onSaveAs, onShare,
                onImportPdf, onExportPdf, onOpenRecent, onOpenFile, onPickRoot, onDismiss,
            )
        }
    }
}

/** Material's compact-width breakpoint: at or above this we keep the rail + pane. */
private const val COMPACT_WIDTH_DP = 600

/**
 * Stretch the backstage's own dialog window edge-to-edge — into the display cutout and
 * behind the (hidden) system bars — so it fills the screen like the editor instead of
 * leaving the camera-cutout band the default dialog window reserves at the top.
 */
@Composable
private fun FullscreenDialogWindow() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

// --- wide layout (tablets / landscape): rail on the left, content on the right ---

@Composable
private fun BackstageWide(
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
    Row(
        Modifier.fillMaxSize().background(palette.menuBg.toComposeColor()).imePadding(),
    ) {
        // Left command rail (scrolls when the screen is too short for every command).
        Column(
            Modifier.width(264.dp).fillMaxHeight().background(palette.panel.toComposeColor())
                .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
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

// --- compact layout (phones / portrait): a stacked menu, with sub-pages ---

/** Which screen the compact backstage is on: the menu, or a pushed sub-page. */
private enum class CompactPage { MENU, HOME, PREFERENCES }

@Composable
private fun BackstageCompact(
    editor: Editor,
    initialView: BackstageView,
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
    var page by remember {
        mutableStateOf(if (initialView == BackstageView.PREFERENCES) CompactPage.PREFERENCES else CompactPage.MENU)
    }
    var createMode by remember { mutableStateOf(CreateMode.NONE) }

    Column(Modifier.fillMaxSize().background(palette.menuBg.toComposeColor()).imePadding()) {
        // Top bar: close (X) at the menu, a back arrow inside a sub-page.
        Row(
            Modifier.fillMaxWidth().background(palette.panel.toComposeColor()).padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page == CompactPage.MENU) {
                IconButton(onClick = onDismiss) {
                    Icon(XnotesIcons.close, "Close", tint = palette.text.toComposeColor(), modifier = Modifier.size(22.dp))
                }
                Text("xnotes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 2.dp))
            } else {
                IconButton(onClick = { createMode = CreateMode.NONE; page = CompactPage.MENU }) {
                    Icon(XnotesIcons.prev, "Back", tint = palette.text.toComposeColor(), modifier = Modifier.size(22.dp))
                }
                Text(
                    if (page == CompactPage.HOME) "Home" else "Preferences",
                    color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                CompactPage.MENU -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                ) {
                    MenuRow(XnotesIcons.home, "Home", chevron = true) {
                        createMode = CreateMode.NONE; onSelectView(BackstageView.RECENT); page = CompactPage.HOME
                    }
                    MenuRow(XnotesIcons.plus, "New") {
                        if (editor.browseRoot != null) {
                            onSelectView(BackstageView.RECENT); createMode = CreateMode.FILE; page = CompactPage.HOME
                        } else onNewBlank()
                    }
                    MenuRow(XnotesIcons.folder, "Open…") { onOpenSystem() }
                    RailDivider()
                    MenuRow(XnotesIcons.save, "Save") { onSave() }
                    MenuRow(XnotesIcons.copy, "Save As…") { onSaveAs() }
                    MenuRow(XnotesIcons.share, "Share…") { onShare() }
                    RailDivider()
                    MenuRow(XnotesIcons.importDoc, "Import PDF…") { onImportPdf() }
                    MenuRow(XnotesIcons.exportDoc, "Export to PDF…") { onExportPdf() }
                    RailDivider()
                    MenuRow(XnotesIcons.sliders, "Preferences", chevron = true) {
                        onSelectView(BackstageView.PREFERENCES); page = CompactPage.PREFERENCES
                    }
                }
                CompactPage.HOME -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    HomePane(editor, onOpenRecent, onOpenFile, onPickRoot, createMode, { createMode = it }, onDismiss)
                }
                CompactPage.PREFERENCES -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    PreferencesPane(editor)
                }
            }
        }
    }
}

/** A full-width menu entry in the compact backstage; [chevron] marks a sub-page. */
@Composable
private fun MenuRow(icon: ImageVector, label: String, chevron: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = palette.accent.toComposeColor(), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = palette.text.toComposeColor(), fontSize = 16.sp)
        if (chevron) {
            Spacer(Modifier.weight(1f))
            Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
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
        if (recents.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent notes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editor.clearRecentFiles() }) {
                    Icon(XnotesIcons.trash, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear", color = palette.textDim.toComposeColor())
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                recents.forEach { uri -> RecentStripCard(editor, uri) { onOpenRecent(uri) } }
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = palette.border.toComposeColor())
            Spacer(Modifier.height(18.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ExplorerSection(editor, onOpenFile, onPickRoot, createMode, onCreateMode, onDismiss)
        }
    }
}

/** Loads (off-thread, cached) a recent note's thumbnail + details. */
@Composable
private fun recentInfoState(editor: Editor, uri: String, widthPx: Int) =
    produceState(editor.cachedRecentInfo(uri), uri, editor.contentVersion) {
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

@OptIn(ExperimentalFoundationApi::class)
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
    var fieldValue by remember(root) { mutableStateOf(TextFieldValue("")) }
    var fieldError by remember(root) { mutableStateOf<String?>(null) }
    var renaming by remember(root) { mutableStateOf<String?>(null) }
    var renameText by remember(root) { mutableStateOf("") }
    var selected by remember(root) { mutableStateOf<BrowseEntry?>(null) }
    var clipboard by remember(root) { mutableStateOf<ClipItem?>(null) }
    var pendingDelete by remember(root) { mutableStateOf<BrowseEntry?>(null) }
    var opError by remember(root) { mutableStateOf<String?>(null) }
    var menuOpen by remember(root) { mutableStateOf(false) }
    val rootName by produceState(editor.cachedRootName(root), root) { value = withContext(Dispatchers.IO) { editor.browseRootName(root) } }
    val fieldFocus = remember { FocusRequester() }
    val fieldBring = remember { BringIntoViewRequester() }
    LaunchedEffect(createMode) {
        if (createMode != CreateMode.NONE) {
            fieldValue = if (createMode == CreateMode.FILE) {
                val n = nextUntitled(editor.cachedChildren(root, currentDocId))
                TextFieldValue(n, selection = TextRange(0, n.length)) // select the whole word so typing replaces it
            } else {
                TextFieldValue("")
            }
            fieldError = null
            runCatching { fieldFocus.requestFocus() }
            runCatching { fieldBring.bringIntoView() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Path (breadcrumb, with "/" separators) + context actions, all on one line.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    XnotesIcons.home, "Root",
                    tint = (if (stack.isEmpty()) palette.accent else palette.textDim).toComposeColor(),
                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable { stack.clear(); selected = null; opError = null },
                )
                Spacer(Modifier.width(6.dp))
                Crumb("${rootName ?: "Folder"}/", current = stack.isEmpty()) { stack.clear(); selected = null; opError = null }
                stack.forEachIndexed { i, (_, name) ->
                    Crumb("$name/", current = i == stack.lastIndex) {
                        while (stack.size > i + 1) stack.removeAt(stack.lastIndex)
                        selected = null; opError = null
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            val sel = selected
            if (sel != null) {
                IconAction(XnotesIcons.edit, "Rename") {
                    renaming = sel.documentUri
                    renameText = if (sel.isDir) sel.name else sel.name.removeSuffix(".xnote").removeSuffix(".XNOTE")
                    selected = null
                }
                IconAction(XnotesIcons.copy, "Copy") { clipboard = ClipItem(sel.documentUri, sel.name, currentDocId, false); selected = null }
                IconAction(XnotesIcons.cut, "Cut") { clipboard = ClipItem(sel.documentUri, sel.name, currentDocId, true); selected = null }
                IconAction(XnotesIcons.trash, "Delete") { pendingDelete = sel }
                IconAction(XnotesIcons.close, "Deselect") { selected = null; clipboard = null }
            } else {
                IconAction(XnotesIcons.plus, "New note") { onCreateMode(CreateMode.FILE) }
                IconAction(XnotesIcons.newFolder, "New folder") { onCreateMode(CreateMode.FOLDER) }
                clipboard?.let { clip ->
                    IconAction(XnotesIcons.paste, "Paste") {
                        opError = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                if (clip.isCut) editor.moveDocumentInto(root, clip.uri, clip.parentDocId, currentDocId)
                                else editor.copyDocumentInto(root, clip.uri, currentDocId)
                            }
                            if (ok) { clipboard = null; refreshKey++ } else opError = "Couldn’t paste here."
                        }
                    }
                    IconAction(XnotesIcons.close, "Clear clipboard") { clipboard = null }
                }
                Box {
                    IconAction(XnotesIcons.more, "More") { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Change folder") }, onClick = { menuOpen = false; onPickRoot() })
                        DropdownMenuItem(text = { Text("Forget folder") }, onClick = { menuOpen = false; editor.clearBrowseRoot() })
                    }
                }
            }
        }
        opError?.let { Text(it, color = Color(0xFFE5534B), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
        Spacer(Modifier.height(10.dp))
        // Inline create (file or folder).
        if (createMode != CreateMode.NONE) {
            val isFolder = createMode == CreateMode.FOLDER
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).bringIntoViewRequester(fieldBring), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it; fieldError = null },
                    singleLine = true,
                    placeholder = if (isFolder) ({ Text("Folder name") }) else null,
                    modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                )
                IconButton(onClick = {
                    val n = fieldValue.text.trim()
                    if (isFolder) {
                        if (n.isEmpty()) fieldError = "Enter a folder name."
                        else scope.launch {
                            val ok = withContext(Dispatchers.IO) { editor.createFolder(root, currentDocId, n) }
                            if (ok) { onCreateMode(CreateMode.NONE); refreshKey++ } else fieldError = "Couldn’t create that folder."
                        }
                    } else scope.launch {
                        // Just create the note in the explorer — it opens only when the user taps it.
                        val uri = withContext(Dispatchers.IO) { editor.createBlankNoteFile(root, currentDocId, n) }
                        if (uri != null) { onCreateMode(CreateMode.NONE); refreshKey++ } else fieldError = "Couldn’t create the note."
                    }
                }) { Icon(XnotesIcons.check, "Create", tint = palette.accent.toComposeColor(), modifier = Modifier.size(22.dp)) }
                IconButton(onClick = { onCreateMode(CreateMode.NONE) }) {
                    Icon(XnotesIcons.close, "Cancel", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
                }
            }
            fieldError?.let { Text(it, color = Color(0xFFE5534B), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
        }
        // Listing.
        val entries by produceState(editor.cachedChildren(root, currentDocId), root, currentDocId, refreshKey) {
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
                            selected = selected?.documentUri == entry.documentUri,
                            dimmed = clipboard?.let { it.isCut && it.uri == entry.documentUri } == true,
                            isRenaming = renaming == entry.documentUri,
                            renameText = renameText,
                            onRenameTextChange = { renameText = it },
                            onClick = {
                                selected = null; opError = null
                                if (entry.isDir) stack.add(editor.browseDocId(entry.documentUri) to entry.name)
                                else onOpenFile(entry.documentUri)
                            },
                            onLongClick = { renaming = null; opError = null; selected = entry },
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

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete?") },
            text = { Text("Delete “${entryLabel(target)}”? This can’t be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = target.documentUri
                    pendingDelete = null; selected = null; opError = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { editor.deleteDocument(uri) }
                        if (ok) refreshKey++ else opError = "Couldn’t delete that."
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            containerColor = palette.menuBg.toComposeColor(),
        )
    }
}

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        text,
        color = (if (current) palette.text else palette.textDim).toComposeColor(),
        fontSize = 14.sp,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 2.dp),
    )
}

@Composable
private fun IconAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, desc, tint = palette.accent.toComposeColor(), modifier = Modifier.size(20.dp))
    }
}

private fun entryLabel(entry: BrowseEntry): String =
    if (entry.isDir) entry.name else entry.name.removeSuffix(".xnote").removeSuffix(".XNOTE")

private fun entryDetails(ctx: android.content.Context, entry: BrowseEntry): String {
    if (entry.isDir) return ""
    return listOfNotNull(
        if (entry.modified > 0)
            android.text.format.DateUtils.getRelativeTimeSpanString(entry.modified, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS).toString()
        else null,
        if (entry.size > 0) android.text.format.Formatter.formatShortFileSize(ctx, entry.size) else null,
    ).joinToString("  ·  ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: BrowseEntry,
    selected: Boolean,
    dimmed: Boolean,
    isRenaming: Boolean,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onConfirmRename: () -> Unit,
    onCancelRename: () -> Unit,
) {
    val palette = LocalPalette.current
    val ctx = LocalContext.current
    val icon = if (entry.isDir) XnotesIcons.folder else XnotesIcons.file
    val tint = (if (entry.isDir) palette.accent else palette.textDim).toComposeColor()
    if (isRenaming) {
        val fr = remember { FocusRequester() }
        val bring = remember { BringIntoViewRequester() }
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; runCatching { bring.bringIntoView() } }
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(bring).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            OutlinedTextField(renameText, onRenameTextChange, singleLine = true, modifier = Modifier.weight(1f).focusRequester(fr))
            IconButton(onClick = onConfirmRename) { Icon(XnotesIcons.check, "Save name", tint = palette.accent.toComposeColor(), modifier = Modifier.size(22.dp)) }
            IconButton(onClick = onCancelRename) { Icon(XnotesIcons.close, "Cancel", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp)) }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .then(if (selected) Modifier.background(palette.accentAlpha(38).toComposeColor()) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .alpha(if (dimmed) 0.4f else 1f)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                entryLabel(entry),
                color = palette.text.toComposeColor(),
                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val details = entryDetails(ctx, entry)
            if (details.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(details, color = palette.textDim.toComposeColor(), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

// --- shared bits ---

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
