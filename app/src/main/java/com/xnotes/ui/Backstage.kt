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
    onOpenRecent: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
    onShareFile: (String) -> Unit,
    onSaveCopyFile: (String) -> Unit,
    onExportFilePdf: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Home is the app's root: back from here leaves the app rather than dropping into the editor. */
    onExitApp: () -> Unit,
) {
    // Below this width the sidebar becomes a slide-over drawer instead of a persistent pane.
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FullscreenDialogWindow()
        BackstageContent(
            editor, compact, view, onSelectView, onOpenSystem, onImportPdf,
            onOpenRecent, onOpenFile, onPickRoot, onShareFile, onSaveCopyFile, onExportFilePdf, onExitApp,
        )
    }
}

/** Width at or above which the sidebar is a persistent pane rather than a drawer. */
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
    onOpenRecent: (String) -> Unit,
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
        if (v == BackstageView.RECENT) createMode = CreateMode.NONE
        onSelectView(v)
        if (compact) sidebarOpen = false
    }
    val newNote: () -> Unit = {
        if (editor.browseRoot != null) { onSelectView(BackstageView.RECENT); createMode = CreateMode.FILE } else onPickRoot()
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
            view == BackstageView.PREFERENCES -> selectView(BackstageView.RECENT)
            createMode != CreateMode.NONE -> createMode = CreateMode.NONE
            else -> onExitApp()
        }
    }

    if (compact) {
        Box(Modifier.fillMaxSize().background(palette.menuBg.toComposeColor()).imePadding()) {
            BackstageMain(
                Modifier.fillMaxSize(), editor, view, sidebarOpen, { sidebarOpen = true },
                onOpenRecent, onOpenFile, onPickRoot, importPdf, onShareFile, onSaveCopyFile, onExportFilePdf, createMode, { createMode = it },
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
                onOpenRecent, onOpenFile, onPickRoot, importPdf, onShareFile, onSaveCopyFile, onExportFilePdf, createMode, { createMode = it },
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
        Command(XnotesIcons.home, "Home", selected = view == BackstageView.RECENT) { onSelectView(BackstageView.RECENT) }
        Command(XnotesIcons.plus, "New note") { onNewNote() }
        Command(XnotesIcons.importDoc, "Import PDF…") { onImportPdf() }
        Command(XnotesIcons.folder, "Open…") { onOpenSystem() }
        RailDivider()
        Command(XnotesIcons.sliders, "Preferences", selected = view == BackstageView.PREFERENCES) { onSelectView(BackstageView.PREFERENCES) }
    }
}

/** The main pane (recents + explorer, or Preferences); shows a hamburger when the sidebar is hidden. */
@Composable
private fun BackstageMain(
    modifier: Modifier,
    editor: Editor,
    view: BackstageView,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
    onOpenRecent: (String) -> Unit,
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
        // Preferences gets its own hamburger bar; on Home the hamburger sits inline with "Recent notes".
        if (!sidebarOpen && view == BackstageView.PREFERENCES) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShowSidebar) {
                    Icon(XnotesIcons.menu, "Show sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            when (view) {
                BackstageView.RECENT -> HomePane(
                    editor, onOpenRecent, onOpenFile, onPickRoot, onImportPdf,
                    onShareFile, onSaveCopyFile, onExportFilePdf, createMode, onCreateMode, sidebarOpen, onShowSidebar,
                )
                BackstageView.PREFERENCES -> PreferencesPane(editor)
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
    val recents = editor.recentFiles
    Column(Modifier.fillMaxSize()) {
        // Header: hamburger (when the sidebar is hidden) on the same line as "Recent notes" + Clear.
        if (!sidebarOpen || recents.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!sidebarOpen) {
                    IconButton(onClick = onShowSidebar) {
                        Icon(XnotesIcons.menu, "Show sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (recents.isNotEmpty()) {
                    Text("Recent notes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editor.clearRecentFiles() }) {
                        Icon(XnotesIcons.trash, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear", color = palette.textDim.toComposeColor())
                    }
                }
            }
        }
        if (recents.isNotEmpty()) {
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
        } else if (!sidebarOpen) {
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ExplorerSection(
                editor, onOpenFile, onPickRoot, onImportPdf,
                onShareFile, onSaveCopyFile, onExportFilePdf, createMode, onCreateMode,
            )
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
                // Fit to width (page top showing); the card clips the overflowing height.
                Image(bmp, info?.label, contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
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
    var fieldValue by remember(root) { mutableStateOf(TextFieldValue("")) }
    var fieldError by remember(root) { mutableStateOf<String?>(null) }
    var renaming by remember(root) { mutableStateOf<String?>(null) }
    var renameText by remember(root) { mutableStateOf("") }
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
    val fieldFocus = remember { FocusRequester() }
    val fieldBring = remember { BringIntoViewRequester() }
    val dismissInteraction = remember { MutableInteractionSource() }
    val pendingImport = editor.pendingImport
    LaunchedEffect(createMode, pendingImport) {
        val active = createMode != CreateMode.NONE || pendingImport != null
        if (active) {
            val default = when {
                pendingImport != null -> pendingImport.defaultName // import names default to the source file
                createMode == CreateMode.FILE -> nextUntitled(editor.cachedChildren(root, currentDocId))
                else -> "" // new folder
            }
            fieldValue = TextFieldValue(default, selection = TextRange(0, default.length)) // select the whole word so typing replaces it
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
                        renaming = sel.documentUri
                        renameText = if (sel.isDir) sel.name else sel.name.removeSuffix(".xnote").removeSuffix(".XNOTE")
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
        // Inline name field: new note, new folder, or naming a pending PDF/Open import.
        if (createMode != CreateMode.NONE || pendingImport != null) {
            val isFolder = pendingImport == null && createMode == CreateMode.FOLDER
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
                    when {
                        pendingImport != null -> scope.launch {
                            // Land the import in the current folder; it opens only when the user taps it.
                            val uri = withContext(Dispatchers.IO) { editor.commitImport(root, currentDocId, n) }
                            if (uri != null) refreshKey++ else fieldError = "Couldn’t save that note."
                        }
                        isFolder -> {
                            if (n.isEmpty()) fieldError = "Enter a folder name."
                            else scope.launch {
                                val ok = withContext(Dispatchers.IO) { editor.createFolder(root, currentDocId, n) }
                                if (ok) { onCreateMode(CreateMode.NONE); refreshKey++ } else fieldError = "Couldn’t create that folder."
                            }
                        }
                        else -> scope.launch {
                            // Just create the note in the explorer — it opens only when the user taps it.
                            val uri = withContext(Dispatchers.IO) { editor.createBlankNoteFile(root, currentDocId, n) }
                            if (uri != null) { onCreateMode(CreateMode.NONE); refreshKey++ } else fieldError = "Couldn’t create the note."
                        }
                    }
                }) { Icon(XnotesIcons.check, "Create", tint = palette.accent.toComposeColor(), modifier = Modifier.size(22.dp)) }
                IconButton(onClick = { if (pendingImport != null) editor.cancelImport() else onCreateMode(CreateMode.NONE) }) {
                    Icon(XnotesIcons.close, "Cancel", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
                }
            }
            fieldError?.let { Text(it, color = Color(0xFFE5534B), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
        }
        // Listing.
        val entries by produceState(editor.cachedChildren(root, currentDocId), root, currentDocId, refreshKey) {
            value = withContext(Dispatchers.IO) { editor.browseChildren(root, currentDocId) }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().then(
                // In select mode, tapping empty space (not a row) clears the selection.
                if (selection.isNotEmpty()) Modifier.clickable(interactionSource = dismissInteraction, indication = null) { selection.clear() } else Modifier,
            ),
        ) {
            when {
                entries == null -> EmptyPane("Loading…")
                entries!!.isEmpty() -> EmptyPane("This folder has no notes.")
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(entries!!) { entry ->
                        // Per-file actions show only on file rows when not in select mode.
                        val fileActions = !entry.isDir && selection.isEmpty()
                        // Rename/copy/cut/delete apply to files and folders alike, outside select mode.
                        val manageActions = selection.isEmpty()
                        EntryRow(
                            entry = entry,
                            selected = selection.any { it.documentUri == entry.documentUri },
                            dimmed = clipboard?.let { c -> c.isCut && c.entries.any { it.documentUri == entry.documentUri } } == true,
                            isRenaming = renaming == entry.documentUri,
                            renameText = renameText,
                            onRenameTextChange = { renameText = it },
                            onShare = if (fileActions) ({ onShareFile(entry.documentUri) }) else null,
                            onSaveCopy = if (fileActions) ({ onSaveCopyFile(entry.documentUri) }) else null,
                            onExportPdf = if (fileActions) ({ onExportFilePdf(entry.documentUri) }) else null,
                            onRename = if (manageActions) ({
                                renaming = entry.documentUri
                                renameText = entryLabel(entry)
                            }) else null,
                            onCopy = if (manageActions) ({ clipboard = ClipItem(listOf(entry), currentDocId, false) }) else null,
                            onCut = if (manageActions) ({ clipboard = ClipItem(listOf(entry), currentDocId, true) }) else null,
                            onDelete = if (manageActions) ({ pendingDelete = listOf(entry) }) else null,
                            onClick = {
                                opError = null
                                when {
                                    selection.isNotEmpty() -> toggleSelect(entry) // select mode: tap toggles (files and folders)
                                    entry.isDir -> stack.add(editor.browseDocId(entry.documentUri) to entry.name)
                                    else -> onOpenFile(entry.documentUri)
                                }
                            },
                            onLongClick = {
                                renaming = null; opError = null
                                if (selection.none { it.documentUri == entry.documentUri }) selection.add(entry)
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
    onShare: (() -> Unit)? = null,
    onSaveCopy: (() -> Unit)? = null,
    onExportPdf: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onCut: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onConfirmRename: () -> Unit,
    onCancelRename: () -> Unit,
) {
    val palette = LocalPalette.current
    val ctx = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
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
            if (onShare != null || onRename != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(XnotesIcons.more, "More", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename?.invoke() })
                        DropdownMenuItem(text = { Text("Copy") }, onClick = { menuOpen = false; onCopy?.invoke() })
                        DropdownMenuItem(text = { Text("Cut") }, onClick = { menuOpen = false; onCut?.invoke() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete?.invoke() })
                        if (onShare != null) {
                            HorizontalDivider(color = palette.border.toComposeColor())
                            DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; onShare() })
                            DropdownMenuItem(text = { Text("Save a copy…") }, onClick = { menuOpen = false; onSaveCopy?.invoke() })
                            DropdownMenuItem(text = { Text("Export to PDF") }, onClick = { menuOpen = false; onExportPdf?.invoke() })
                        }
                    }
                }
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
