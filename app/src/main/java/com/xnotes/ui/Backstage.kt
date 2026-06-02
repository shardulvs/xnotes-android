package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which pane the backstage shows on the right. */
enum class BackstageView { HOME, PREFERENCES, ABOUT }

/** Whether the Home explorer is awaiting a new file/folder name. */
private enum class CreateMode { NONE, FILE, FOLDER }

/** Entries copied/cut in the explorer; [sourceParentDocId] is the folder they came from (for moves). */
private data class ClipItem(val entries: List<BrowseEntry>, val sourceParentDocId: String, val isCut: Boolean)

/** The next free "untitled_N" stem (no extension) for a fresh note in [entries]. */
private fun nextUntitled(entries: List<BrowseEntry>?): String {
    val taken = entries.orEmpty().filter { !it.isDir }.map { it.name.lowercase() }.toSet()
    var n = 1
    while ("untitled_$n.xnote" in taken) n++
    return "untitled_$n"
}

/**
 * The full-screen "File" area (the home screen). Shows recent notes + an in-app file
 * explorer rooted at a folder the user granted, with a command sidebar that is a
 * collapsible left pane on wide screens and a slide-over drawer on phones. "Open…" uses
 * the system picker; "New note" / "Import PDF" land a file in the current folder.
 */
@Composable
fun Backstage(
    editor: Editor,
    view: BackstageView,
    onSelectView: (BackstageView) -> Unit,
    onOpenSystem: () -> Unit,
    onImportPdf: () -> Unit,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
    onShareFile: (String) -> Unit,
    onSaveCopyFile: (String) -> Unit,
    onExportFilePdf: (String) -> Unit,
    /** Home is the app's root: back from here leaves the app rather than dropping into the editor. */
    onExitApp: () -> Unit,
) {
    // Below this width the sidebar becomes a slide-over drawer instead of a persistent pane.
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP
    // The backstage is the root of the stack — ordinary base content, not a dialog. The activity
    // window already runs edge-to-edge with the system bars hidden (MainActivity.applyFullscreen).
    BackstageContent(
        editor, compact, view, onSelectView, onOpenSystem, onImportPdf,
        onOpenFile, onPickRoot, onShareFile, onSaveCopyFile, onExportFilePdf, onExitApp,
    )
}

/** Width at or above which the sidebar is a persistent pane rather than a drawer. */
private const val COMPACT_WIDTH_DP = 600

/**
 * The home-first layout: the recents + explorer (or Preferences) fill the screen, with a
 * command sidebar that's a collapsible left pane on wide screens and a slide-over drawer on
 * phones. A `<` collapses it; a hamburger (hidden while open) brings it back.
 */
@Composable
private fun BackstageContent(
    editor: Editor,
    compact: Boolean,
    view: BackstageView,
    onSelectView: (BackstageView) -> Unit,
    onOpenSystem: () -> Unit,
    onImportPdf: () -> Unit,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
    onShareFile: (String) -> Unit,
    onSaveCopyFile: (String) -> Unit,
    onExportFilePdf: (String) -> Unit,
    onExitApp: () -> Unit,
) {
    val palette = LocalPalette.current
    var createMode by remember { mutableStateOf(CreateMode.NONE) }
    var sidebarOpen by remember { mutableStateOf(!compact) }

    // A folder is required for these actions; without one, send the user to pick a folder first.
    val selectView: (BackstageView) -> Unit = { v ->
        if (v == BackstageView.HOME) createMode = CreateMode.NONE
        onSelectView(v)
        if (compact) sidebarOpen = false
    }
    val newNote: () -> Unit = {
        if (editor.browseRoot != null) { onSelectView(BackstageView.HOME); createMode = CreateMode.FILE } else onPickRoot()
        if (compact) sidebarOpen = false
    }
    val importPdf: () -> Unit = {
        if (editor.browseRoot != null) onImportPdf() else onPickRoot()
        if (compact) sidebarOpen = false
    }
    val openSystem: () -> Unit = {
        if (editor.browseRoot != null) onOpenSystem() else onPickRoot()
        if (compact) sidebarOpen = false
    }

    // Home is the app's root, so it owns every back press while it's up (the editor sits
    // underneath in the same activity — letting the dialog dismiss would just bounce back to
    // it, and the editor's own handler would re-open Home: an endless loop). Back peels off
    // one layer at a time — drawer, Preferences, an in-progress create — and once at the bare
    // Home screen it leaves the app instead. A deeper explorer folder is popped first by the
    // explorer's own (more-nested) handler before this one ever sees the press.
    BackHandler {
        when {
            compact && sidebarOpen -> sidebarOpen = false
            // Preferences and About are sub-pages of Home: back lands on Home rather than leaving the app.
            view == BackstageView.PREFERENCES || view == BackstageView.ABOUT -> selectView(BackstageView.HOME)
            createMode != CreateMode.NONE -> createMode = CreateMode.NONE
            else -> onExitApp()
        }
    }

    if (compact) {
        Box(Modifier.fillMaxSize().background(palette.menuBg.toComposeColor()).imePadding()) {
            BackstageMain(
                Modifier.fillMaxSize(), editor, view, sidebarOpen, { sidebarOpen = true },
                onOpenFile, onPickRoot, importPdf, onShareFile, onSaveCopyFile, onExportFilePdf, createMode, { createMode = it },
            )
            AnimatedVisibility(visible = sidebarOpen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { sidebarOpen = false })
            }
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = slideInHorizontally(initialOffsetX = { -it }),
                exit = slideOutHorizontally(targetOffsetX = { -it }),
            ) {
                BackstageSidebar(Modifier.width(296.dp), view, { sidebarOpen = false }, selectView, newNote, importPdf, openSystem)
            }
        }
    } else {
        Row(Modifier.fillMaxSize().background(palette.menuBg.toComposeColor()).imePadding()) {
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            ) {
                BackstageSidebar(Modifier.width(264.dp), view, { sidebarOpen = false }, selectView, newNote, importPdf, openSystem)
            }
            BackstageMain(
                Modifier.weight(1f).fillMaxHeight(), editor, view, sidebarOpen, { sidebarOpen = true },
                onOpenFile, onPickRoot, importPdf, onShareFile, onSaveCopyFile, onExportFilePdf, createMode, { createMode = it },
            )
        }
    }
}

/** The command sidebar (collapsible pane on wide screens, slide-over drawer on phones). */
@Composable
private fun BackstageSidebar(
    modifier: Modifier,
    view: BackstageView,
    onCollapse: () -> Unit,
    onSelectView: (BackstageView) -> Unit,
    onNewNote: () -> Unit,
    onImportPdf: () -> Unit,
    onOpenSystem: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        modifier.fillMaxHeight().background(palette.panel.toComposeColor())
            .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("xnotes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCollapse) {
                Icon(XnotesIcons.prev, "Collapse sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Command(XnotesIcons.home, "Home", selected = view == BackstageView.HOME) { onSelectView(BackstageView.HOME) }
        Command(XnotesIcons.plus, "New note") { onNewNote() }
        Command(XnotesIcons.importDoc, "Import PDF…") { onImportPdf() }
        Command(XnotesIcons.folder, "Open…") { onOpenSystem() }
        RailDivider()
        Command(XnotesIcons.sliders, "Preferences", selected = view == BackstageView.PREFERENCES) { onSelectView(BackstageView.PREFERENCES) }
        Command(XnotesIcons.info, "About", selected = view == BackstageView.ABOUT) { onSelectView(BackstageView.ABOUT) }
    }
}

/** The main pane (the explorer, or Preferences); shows a hamburger when the sidebar is hidden. */
@Composable
private fun BackstageMain(
    modifier: Modifier,
    editor: Editor,
    view: BackstageView,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
    onImportPdf: () -> Unit,
    onShareFile: (String) -> Unit,
    onSaveCopyFile: (String) -> Unit,
    onExportFilePdf: (String) -> Unit,
    createMode: CreateMode,
    onCreateMode: (CreateMode) -> Unit,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        // About keeps a slim top bar for the hamburger, reserved at a constant height so toggling
        // the sidebar never shifts it. Home and Preferences host the hamburger inline with their headers.
        if (view == BackstageView.ABOUT) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 6.dp, end = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (!sidebarOpen) {
                    IconButton(onClick = onShowSidebar) {
                        Icon(XnotesIcons.menu, "Show sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            when (view) {
                BackstageView.HOME -> HomePane(
                    editor, onOpenFile, onPickRoot, onImportPdf,
                    onShareFile, onSaveCopyFile, onExportFilePdf, createMode, onCreateMode, sidebarOpen, onShowSidebar,
                )
                BackstageView.PREFERENCES -> PreferencesPane(editor, sidebarOpen, onShowSidebar)
                BackstageView.ABOUT -> AboutPane()
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

// --- home pane: the folder explorer ---

@Composable
private fun HomePane(
    editor: Editor,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
    onImportPdf: () -> Unit,
    onShareFile: (String) -> Unit,
    onSaveCopyFile: (String) -> Unit,
    onExportFilePdf: (String) -> Unit,
    createMode: CreateMode,
    onCreateMode: (CreateMode) -> Unit,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        // Constant-height header so toggling the sidebar never shifts the explorer below it. The
        // hamburger shows only when the sidebar is hidden; the wordmark then titles the row so the
        // menu button isn't stranded alone (the persistent sidebar already brands wide layouts).
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!sidebarOpen) {
                IconButton(onClick = onShowSidebar) {
                    Icon(XnotesIcons.menu, "Show sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text("xnotes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ExplorerSection(
                editor, onOpenFile, onPickRoot, onImportPdf,
                onShareFile, onSaveCopyFile, onExportFilePdf, createMode, onCreateMode,
            )
            // A round quick-create button for a new note in the current folder. Only when a folder is
            // granted — otherwise the explorer shows the folder-picker prompt and there's nowhere to create.
            if (editor.browseRoot != null) {
                FloatingActionButton(
                    onClick = { onCreateMode(CreateMode.FILE) },
                    shape = CircleShape,
                    containerColor = palette.accent.toComposeColor(),
                    contentColor = palette.bg.toComposeColor(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                ) {
                    Icon(XnotesIcons.edit, "New note", modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

// --- explorer section ---

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ExplorerSection(
    editor: Editor,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
    onImportPdf: () -> Unit,
    onShareFile: (String) -> Unit,
    onSaveCopyFile: (String) -> Unit,
    onExportFilePdf: (String) -> Unit,
    createMode: CreateMode,
    onCreateMode: (CreateMode) -> Unit,
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
    var fieldError by remember(root) { mutableStateOf<String?>(null) }
    var renaming by remember(root) { mutableStateOf<BrowseEntry?>(null) }
    val selection = remember(root) { mutableStateListOf<BrowseEntry>() }
    var clipboard by remember(root) { mutableStateOf<ClipItem?>(null) }
    var pendingDelete by remember(root) { mutableStateOf<List<BrowseEntry>?>(null) }
    var opError by remember(root) { mutableStateOf<String?>(null) }
    // Inside a subfolder, back climbs one level out (this sits below the Backstage's root
    // handler, so it's consulted first and only fires while there's a folder to leave).
    BackHandler(enabled = stack.isNotEmpty()) {
        stack.removeAt(stack.lastIndex)
        selection.clear()
        opError = null
    }
    fun toggleSelect(e: BrowseEntry) {
        val i = selection.indexOfFirst { it.documentUri == e.documentUri }
        if (i >= 0) selection.removeAt(i) else selection.add(e)
    }
    var menuOpen by remember(root) { mutableStateOf(false) }
    var newMenuOpen by remember(root) { mutableStateOf(false) }
    val rootName by produceState(editor.cachedRootName(root), root) { value = withContext(Dispatchers.IO) { editor.browseRootName(root) } }
    val dismissInteraction = remember { MutableInteractionSource() }
    val pendingImport = editor.pendingImport
    // Clear any stale error when a fresh name dialog opens for a new operation.
    LaunchedEffect(createMode, pendingImport) { fieldError = null }

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
                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable { stack.clear(); selection.clear(); opError = null },
                )
                Spacer(Modifier.width(6.dp))
                Crumb("${rootName ?: "Folder"}/", current = stack.isEmpty()) { stack.clear(); selection.clear(); opError = null }
                stack.forEachIndexed { i, (_, name) ->
                    Crumb("$name/", current = i == stack.lastIndex) {
                        while (stack.size > i + 1) stack.removeAt(stack.lastIndex)
                        selection.clear(); opError = null
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (selection.isEmpty()) {
                Box {
                    IconAction(XnotesIcons.plus, "New") { newMenuOpen = true }
                    DropdownMenu(expanded = newMenuOpen, onDismissRequest = { newMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("New Note") }, onClick = { newMenuOpen = false; onCreateMode(CreateMode.FILE) })
                        DropdownMenuItem(text = { Text("Import PDF") }, onClick = { newMenuOpen = false; onImportPdf() })
                    }
                }
                IconAction(XnotesIcons.newFolder, "New folder") { onCreateMode(CreateMode.FOLDER) }
                clipboard?.let { clip ->
                    IconAction(XnotesIcons.paste, "Paste") {
                        opError = null
                        scope.launch {
                            val allOk = withContext(Dispatchers.IO) {
                                var ok = true
                                clip.entries.forEach { e ->
                                    val one = if (clip.isCut) editor.moveDocumentInto(root, e.documentUri, clip.sourceParentDocId, currentDocId)
                                    else editor.copyDocumentInto(root, e.documentUri, currentDocId)
                                    if (!one) ok = false
                                }
                                ok
                            }
                            refreshKey++
                            if (allOk) clipboard = null else opError = "Couldn’t paste some items here."
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
            } else {
                // Rename only makes sense for a single item.
                if (selection.size == 1) {
                    val sel = selection.first()
                    IconAction(XnotesIcons.edit, "Rename") {
                        renaming = sel
                        selection.clear()
                    }
                }
                IconAction(XnotesIcons.copy, "Copy") { clipboard = ClipItem(selection.toList(), currentDocId, false); selection.clear() }
                IconAction(XnotesIcons.cut, "Cut") { clipboard = ClipItem(selection.toList(), currentDocId, true); selection.clear() }
                IconAction(XnotesIcons.trash, "Delete") { pendingDelete = selection.toList() }
                IconAction(XnotesIcons.close, "Deselect") { selection.clear() }
            }
        }
        opError?.let { Text(it, color = Color(0xFFE5534B), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
        Spacer(Modifier.height(10.dp))
        // Listing. Re-keyed on noteOpen so returning from the editor re-queries the folder, picking up
        // the just-closed note's new mtime (its tile refreshes) and any newly created/discovered items.
        val entries by produceState(editor.cachedChildren(root, currentDocId), root, currentDocId, refreshKey, editor.noteOpen) {
            value = withContext(Dispatchers.IO) { editor.browseChildren(root, currentDocId) }
        }
        // browseChildren returns grid order: folders (ascending by creation), then files (descending).
        val folders = entries?.filter { it.isDir }.orEmpty()
        val files = entries?.filterNot { it.isDir }.orEmpty()
        // A fixed column count per orientation, derived from the full screen width (not the pane), so
        // toggling the sidebar never changes how many tiles are in a row — closing it just widens the
        // pane and enlarges the tiles.
        val gridColumns = (LocalConfiguration.current.screenWidthDp / 190).coerceIn(2, 8)
        Box(
            Modifier.weight(1f).fillMaxWidth().then(
                // In select mode, tapping empty space (not a tile) clears the selection.
                if (selection.isNotEmpty()) Modifier.clickable(interactionSource = dismissInteraction, indication = null) { selection.clear() } else Modifier,
            ),
        ) {
            when {
                entries == null -> EmptyPane("Loading…")
                entries!!.isEmpty() -> EmptyPane("This folder has no notes.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 88.dp), // clear the quick-create FAB
                ) {
                    // Folders: a full-width wrapping row of compact chips above the file tiles.
                    if (folders.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            FlowRow(
                                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                folders.forEach { entry ->
                                    FolderChip(
                                        entry = entry,
                                        selected = selection.any { it.documentUri == entry.documentUri },
                                        dimmed = clipboard?.let { c -> c.isCut && c.entries.any { it.documentUri == entry.documentUri } } == true,
                                        inSelectMode = selection.isNotEmpty(),
                                        onRename = if (selection.isEmpty()) ({ renaming = entry }) else null,
                                        onCopy = if (selection.isEmpty()) ({ clipboard = ClipItem(listOf(entry), currentDocId, false) }) else null,
                                        onCut = if (selection.isEmpty()) ({ clipboard = ClipItem(listOf(entry), currentDocId, true) }) else null,
                                        onDelete = if (selection.isEmpty()) ({ pendingDelete = listOf(entry) }) else null,
                                        onDismissSelection = { selection.clear() },
                                        onClick = {
                                            opError = null
                                            if (selection.isNotEmpty()) toggleSelect(entry)
                                            else stack.add(editor.browseDocId(entry.documentUri) to entry.name)
                                        },
                                        onLongClick = {
                                            renaming = null; opError = null
                                            if (selection.none { it.documentUri == entry.documentUri }) selection.add(entry)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // Files: big square thumbnail tiles, captioned with the name and date.
                    items(files, key = { it.documentUri }) { entry ->
                        val fileActions = selection.isEmpty()
                        FileTile(
                            editor = editor,
                            entry = entry,
                            selected = selection.any { it.documentUri == entry.documentUri },
                            dimmed = clipboard?.let { c -> c.isCut && c.entries.any { it.documentUri == entry.documentUri } } == true,
                            inSelectMode = selection.isNotEmpty(),
                            onShare = if (fileActions) ({ onShareFile(entry.documentUri) }) else null,
                            onSaveCopy = if (fileActions) ({ onSaveCopyFile(entry.documentUri) }) else null,
                            onExportPdf = if (fileActions) ({ onExportFilePdf(entry.documentUri) }) else null,
                            onRename = if (fileActions) ({ renaming = entry }) else null,
                            onCopy = if (fileActions) ({ clipboard = ClipItem(listOf(entry), currentDocId, false) }) else null,
                            onCut = if (fileActions) ({ clipboard = ClipItem(listOf(entry), currentDocId, true) }) else null,
                            onDelete = if (fileActions) ({ pendingDelete = listOf(entry) }) else null,
                            onClick = {
                                opError = null
                                if (selection.isNotEmpty()) toggleSelect(entry) else onOpenFile(entry.documentUri)
                            },
                            onLongClick = {
                                renaming = null; opError = null
                                if (selection.none { it.documentUri == entry.documentUri }) selection.add(entry)
                            },
                        )
                    }
                }
            }
        }
    }

    // Name entry for a new note, new folder, or a pending PDF/Open import.
    if (createMode != CreateMode.NONE || pendingImport != null) {
        val isFolder = pendingImport == null && createMode == CreateMode.FOLDER
        val default = when {
            pendingImport != null -> pendingImport.defaultName // import names default to the source file
            createMode == CreateMode.FILE -> nextUntitled(editor.cachedChildren(root, currentDocId))
            else -> "" // new folder
        }
        NameDialog(
            title = when {
                pendingImport != null -> "Import"
                isFolder -> "New folder"
                else -> "New note"
            },
            initial = default,
            confirmLabel = if (pendingImport != null) "Save" else "Create",
            placeholder = if (isFolder) "Folder name" else null,
            allowEmpty = !isFolder, // a folder needs a name; a blank note name becomes "untitled_N"
            error = fieldError,
            onConfirm = { n ->
                when {
                    pendingImport != null -> scope.launch {
                        // Land the import in the current folder; it opens only when the user taps it.
                        val uri = withContext(Dispatchers.IO) { editor.commitImport(root, currentDocId, n) }
                        if (uri != null) refreshKey++ else fieldError = "Couldn’t save that note."
                    }
                    isFolder -> scope.launch {
                        val ok = withContext(Dispatchers.IO) { editor.createFolder(root, currentDocId, n) }
                        if (ok) { onCreateMode(CreateMode.NONE); refreshKey++ } else fieldError = "Couldn’t create that folder."
                    }
                    else -> scope.launch {
                        // Just create the note in the explorer — it opens only when the user taps it.
                        val uri = withContext(Dispatchers.IO) { editor.createBlankNoteFile(root, currentDocId, n) }
                        if (uri != null) { onCreateMode(CreateMode.NONE); refreshKey++ } else fieldError = "Couldn’t create the note."
                    }
                }
            },
            onDismiss = { fieldError = null; if (pendingImport != null) editor.cancelImport() else onCreateMode(CreateMode.NONE) },
        )
    }

    renaming?.let { entry ->
        NameDialog(
            title = if (entry.isDir) "Rename folder" else "Rename note",
            initial = entryLabel(entry),
            confirmLabel = "Rename",
            allowEmpty = false,
            onConfirm = { raw ->
                val newName = if (entry.isDir || raw.endsWith(".xnote", ignoreCase = true)) raw else "$raw.xnote"
                // Renames touch the open-note binding (Compose state) so run on the main thread.
                val ok = editor.renameDocument(entry.documentUri, newName)
                renaming = null
                if (ok) refreshKey++
            },
            onDismiss = { renaming = null },
        )
    }

    pendingDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete?") },
            text = {
                Text(
                    if (targets.size == 1) "Delete “${entryLabel(targets.first())}”? This can’t be undone."
                    else "Delete ${targets.size} items? This can’t be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val uris = targets.map { it.documentUri }
                    pendingDelete = null; selection.clear(); opError = null
                    scope.launch {
                        val allOk = withContext(Dispatchers.IO) {
                            var ok = true
                            uris.forEach { if (!editor.deleteDocument(it)) ok = false }
                            ok
                        }
                        refreshKey++
                        if (!allOk) opError = "Couldn’t delete some items."
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

/** A file's last-edited date for the line beneath its tile (relative, e.g. "2 days ago"). */
private fun entryDate(entry: BrowseEntry): String =
    if (entry.modified > 0)
        android.text.format.DateUtils.getRelativeTimeSpanString(
            entry.modified, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS,
        ).toString()
    else ""

/** The per-entry overflow menu. Files get the extra Share/Save-a-copy/Export block (pass [onShare]); folders don't. */
@Composable
private fun EntryMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onCut: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onShare: (() -> Unit)? = null,
    onSaveCopy: (() -> Unit)? = null,
    onExportPdf: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Rename") }, onClick = { onDismiss(); onRename?.invoke() })
        DropdownMenuItem(text = { Text("Copy") }, onClick = { onDismiss(); onCopy?.invoke() })
        DropdownMenuItem(text = { Text("Cut") }, onClick = { onDismiss(); onCut?.invoke() })
        DropdownMenuItem(text = { Text("Delete") }, onClick = { onDismiss(); onDelete?.invoke() })
        if (onShare != null) {
            HorizontalDivider(color = palette.border.toComposeColor())
            DropdownMenuItem(text = { Text("Share") }, onClick = { onDismiss(); onShare() })
            DropdownMenuItem(text = { Text("Save a copy…") }, onClick = { onDismiss(); onSaveCopy?.invoke() })
            DropdownMenuItem(text = { Text("Export to PDF") }, onClick = { onDismiss(); onExportPdf?.invoke() })
        }
    }
}

/** A compact folder chip (small icon + name) for the wrapping row above the file tiles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderChip(
    entry: BrowseEntry,
    selected: Boolean,
    dimmed: Boolean,
    inSelectMode: Boolean,
    onRename: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onCut: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismissSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(8.dp))
            // Selected: accent border AND accent transparent fill (matching the file tiles).
            .background((if (selected) palette.accentAlpha(38) else palette.panel).toComposeColor())
            .then(if (selected) Modifier.border(1.5.dp, palette.accent.toComposeColor(), RoundedCornerShape(8.dp)) else Modifier)
            // No tap ripple — the accent border + fill is the only selection cue.
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .alpha(if (dimmed) 0.4f else 1f)
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(XnotesIcons.folder, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            entryLabel(entry), color = palette.text.toComposeColor(), fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        // The overflow button is kept in select mode too, so the chip width (and the row layout) never
        // shifts when selection starts; there a tap dismisses the selection instead of opening the menu.
        Box {
            IconButton(
                onClick = { if (inSelectMode) onDismissSelection() else menuOpen = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(XnotesIcons.more, "More", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            }
            if (!inSelectMode) EntryMenu(menuOpen, { menuOpen = false }, onRename, onCopy, onCut, onDelete)
        }
    }
}

/** A big square note tile: first-page thumbnail (cropped to the page top) + name + date. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(
    editor: Editor,
    entry: BrowseEntry,
    selected: Boolean,
    dimmed: Boolean,
    inSelectMode: Boolean,
    onShare: (() -> Unit)?,
    onSaveCopy: (() -> Unit)?,
    onExportPdf: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onCut: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    // Seed from the in-memory cache for an instant paint, then load/render off-thread; re-keying on
    // the file's mtime re-renders the tile after the note is edited.
    val thumb by produceState<ImageBitmap?>(editor.cachedNoteTile(entry.documentUri), entry.documentUri, entry.modified) {
        value = editor.noteTileThumbnail(entry.documentUri, entry.modified)
    }
    Column(
        Modifier
            .alpha(if (dimmed) 0.4f else 1f)
            .clip(RoundedCornerShape(10.dp))
            // No tap ripple — the accent border + fill is the only selection cue.
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.paper.toComposeColor())
                .then(
                    if (selected) Modifier.border(2.dp, palette.accent.toComposeColor(), RoundedCornerShape(10.dp))
                    else Modifier.border(1.dp, palette.paperBorder.toComposeColor(), RoundedCornerShape(10.dp)),
                ),
        ) {
            val img = thumb
            if (img != null) {
                Image(img, entryLabel(entry), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize())
            } else {
                Icon(XnotesIcons.file, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(32.dp).align(Alignment.Center))
            }
            // Selected: accent transparent fill over the thumbnail, on top of the accent border below.
            if (selected) Box(Modifier.matchParentSize().background(palette.accentAlpha(38).toComposeColor()))
            if (!inSelectMode && onRename != null) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(XnotesIcons.more, "More", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                    }
                    EntryMenu(menuOpen, { menuOpen = false }, onRename, onCopy, onCut, onDelete, onShare, onSaveCopy, onExportPdf)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entryLabel(entry), color = palette.text.toComposeColor(), fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp),
        )
        val date = entryDate(entry)
        if (date.isNotEmpty()) {
            Text(
                date, color = palette.textDim.toComposeColor(), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

// --- shared bits ---

/**
 * A small modal that asks for a single name, used for new notes, new folders, renames, and
 * naming a pending import. Pre-fills [initial] (fully selected so typing replaces it), confirms
 * on the keyboard's Done action or hardware Enter, and dismisses on Cancel, the scrim, or Esc.
 * When [allowEmpty] is false the confirm button stays disabled until something is typed; a
 * non-null [error] shows under the field and keeps the dialog open after a failed operation.
 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    placeholder: String? = null,
    allowEmpty: Boolean,
    error: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val confirm = {
        val n = text.text.trim()
        if (allowEmpty || n.isNotEmpty()) onConfirm(n)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = error != null,
                placeholder = placeholder?.let { { Text(it) } },
                supportingText = error?.let { { Text(it, color = Color(0xFFE5534B)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier
                    .focusRequester(focus)
                    .onPreviewKeyEvent { ev ->
                        when {
                            ev.type != KeyEventType.KeyDown -> false
                            ev.key == Key.Enter || ev.key == Key.NumPadEnter -> { confirm(); true }
                            ev.key == Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = allowEmpty || text.text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = palette.menuBg.toComposeColor(),
    )
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
