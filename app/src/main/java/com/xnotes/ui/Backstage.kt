package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.Rgba
import com.xnotes.settings.ExplorerSortKey
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

/** Which pane the backstage shows on the right. */
enum class BackstageView { HOME, PREFERENCES, ABOUT, RECYCLE_BIN }

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
 * The full-screen "File" area (the home screen). Shows an in-app file explorer
 * rooted at a folder the user granted, with a command sidebar that is a
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
    /** Preferences asked to import a Helix code theme. */
    onImportCodeTheme: () -> Unit = {},
    /** Preferences asked to import a font file. */
    onImportFont: () -> Unit = {},
) {
    // Below this width the sidebar becomes a slide-over drawer instead of a persistent pane.
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP
    // The backstage is the root of the stack — ordinary base content, not a dialog. The activity
    // window already runs edge-to-edge with the system bars hidden (MainActivity.applyFullscreen).
    BackstageContent(
        editor, compact, view, onSelectView, onOpenSystem, onImportPdf,
        onOpenFile, onPickRoot, onShareFile, onSaveCopyFile, onExportFilePdf, onExitApp, onImportCodeTheme, onImportFont,
    )
}

/** Width at or above which the sidebar is a persistent pane rather than a drawer. */
private const val COMPACT_WIDTH_DP = 600

/** Open/close animation duration for the sidebar drawer/pane and its scrim. */
private const val SIDEBAR_ANIM_MS = 150

/**
 * The home-first layout: the explorer (or Preferences) fills the screen, with a
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
    onImportCodeTheme: () -> Unit,
    onImportFont: () -> Unit,
) {
    val palette = LocalPalette.current
    var createMode by remember { mutableStateOf(CreateMode.NONE) }
    var sidebarOpen by remember { mutableStateOf(!compact) }
    // Close animates only on a true dismiss ("<", scrim, back); a command swaps the pane
    // already composed underneath, so it closes instantly.
    var animateClose by remember { mutableStateOf(true) }
    val dismissSidebar = { animateClose = true; sidebarOpen = false }

    // A folder is required for these actions; without one, send the user to pick a folder first.
    val selectView: (BackstageView) -> Unit = { v ->
        if (v == BackstageView.HOME) createMode = CreateMode.NONE
        onSelectView(v)
        if (compact) { animateClose = false; sidebarOpen = false }
    }
    val newNote: () -> Unit = {
        if (editor.browseRoot != null) { onSelectView(BackstageView.HOME); createMode = CreateMode.FILE } else onPickRoot()
        if (compact) { animateClose = false; sidebarOpen = false }
    }
    val importPdf: () -> Unit = {
        if (editor.browseRoot != null) onImportPdf() else onPickRoot()
        if (compact) { animateClose = false; sidebarOpen = false }
    }
    val openSystem: () -> Unit = {
        if (editor.browseRoot != null) onOpenSystem() else onPickRoot()
        if (compact) { animateClose = false; sidebarOpen = false }
    }

    // Home is the app's root, so it owns every back press while it's up (the editor sits
    // underneath in the same activity — letting the dialog dismiss would just bounce back to
    // it, and the editor's own handler would re-open Home: an endless loop). Back peels off
    // one layer at a time — drawer, Preferences, an in-progress create — and once at the bare
    // Home screen it leaves the app instead. A deeper explorer folder is popped first by the
    // explorer's own (more-nested) handler before this one ever sees the press.
    BackHandler {
        when {
            compact && sidebarOpen -> dismissSidebar()
            // Preferences, About, and Recycle Bin are sub-pages of Home: back lands on Home.
            view == BackstageView.PREFERENCES || view == BackstageView.ABOUT || view == BackstageView.RECYCLE_BIN -> selectView(BackstageView.HOME)
            createMode != CreateMode.NONE -> createMode = CreateMode.NONE
            else -> onExitApp()
        }
    }

    if (compact) {
        Box(Modifier.fillMaxSize().background(palette.menuBg.toComposeColor()).imePadding()) {
            BackstageMain(
                Modifier.fillMaxSize(), editor, view, compact, sidebarOpen, { animateClose = true; sidebarOpen = true }, { selectView(BackstageView.HOME) },
                onOpenFile, onPickRoot, importPdf, onShareFile, onSaveCopyFile, onExportFilePdf, createMode, { createMode = it }, onImportCodeTheme, onImportFont,
            )
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = fadeIn(animationSpec = tween(SIDEBAR_ANIM_MS)),
                exit = if (animateClose) fadeOut(animationSpec = tween(SIDEBAR_ANIM_MS)) else ExitTransition.None,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { dismissSidebar() })
            }
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = slideInHorizontally(animationSpec = tween(SIDEBAR_ANIM_MS), initialOffsetX = { -it }),
                exit = if (animateClose) slideOutHorizontally(animationSpec = tween(SIDEBAR_ANIM_MS), targetOffsetX = { -it }) else ExitTransition.None,
            ) {
                BackstageSidebar(Modifier.width(296.dp), view, dismissSidebar, selectView, newNote, importPdf, openSystem)
            }
        }
    } else {
        Row(Modifier.fillMaxSize().background(palette.menuBg.toComposeColor()).imePadding()) {
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = expandHorizontally(animationSpec = tween(SIDEBAR_ANIM_MS), expandFrom = Alignment.Start) + fadeIn(animationSpec = tween(SIDEBAR_ANIM_MS)),
                exit = shrinkHorizontally(animationSpec = tween(SIDEBAR_ANIM_MS), shrinkTowards = Alignment.Start) + fadeOut(animationSpec = tween(SIDEBAR_ANIM_MS)),
            ) {
                BackstageSidebar(Modifier.width(264.dp), view, { sidebarOpen = false }, selectView, newNote, importPdf, openSystem)
            }
            BackstageMain(
                Modifier.weight(1f).fillMaxHeight(), editor, view, compact, sidebarOpen, { sidebarOpen = true }, { selectView(BackstageView.HOME) },
                onOpenFile, onPickRoot, importPdf, onShareFile, onSaveCopyFile, onExportFilePdf, createMode, { createMode = it }, onImportCodeTheme, onImportFont,
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
        Command(XnotesIcons.trash, "Recycle Bin", selected = view == BackstageView.RECYCLE_BIN) { onSelectView(BackstageView.RECYCLE_BIN) }
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
    compact: Boolean,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
    onBackToHome: () -> Unit,
    onOpenFile: (String) -> Unit,
    onPickRoot: () -> Unit,
    onImportPdf: () -> Unit,
    onShareFile: (String) -> Unit,
    onSaveCopyFile: (String) -> Unit,
    onExportFilePdf: (String) -> Unit,
    createMode: CreateMode,
    onCreateMode: (CreateMode) -> Unit,
    onImportCodeTheme: () -> Unit,
    onImportFont: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        // About's slim top bar (constant height so toggling the sidebar never shifts it) holds the
        // same leading control as Home/Preferences: a Back arrow to Home on compact, else a hamburger.
        if (view == BackstageView.ABOUT) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 6.dp, end = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (compact) {
                    IconButton(onClick = onBackToHome) {
                        Icon(XnotesIcons.prev, "Back to home", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                } else if (!sidebarOpen) {
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
                BackstageView.RECYCLE_BIN -> RecycleBinPane(
                    editor = editor,
                    onShowSidebar = onShowSidebar,
                    onBackToHome = onBackToHome,
                    sidebarOpen = sidebarOpen,
                    compact = compact,
                )
                BackstageView.PREFERENCES -> PreferencesPane(editor, compact, sidebarOpen, onShowSidebar, onBackToHome, onImportCodeTheme, onImportFont)
                BackstageView.ABOUT -> AboutPane()
            }
        }
    }
}

// --- recycle bin pane ---

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun RecycleBinPane(
    editor: Editor,
    onShowSidebar: () -> Unit,
    onBackToHome: () -> Unit,
    sidebarOpen: Boolean,
    compact: Boolean,
) {
    val palette = LocalPalette.current
    val root = editor.browseRoot
    if (root == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text("No folder selected.", color = palette.textDim.toComposeColor(), fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
        }
        return
    }

    val scope = rememberCoroutineScope()
    var refreshKey by remember(root) { mutableStateOf(0) }
    val selection = remember(root) { mutableStateListOf<BrowseEntry>() }
    var pendingDelete by remember(root) { mutableStateOf<List<BrowseEntry>?>(null) }
    var opError by remember(root) { mutableStateOf<String?>(null) }
    val dismissInteraction = remember { MutableInteractionSource() }
    val gridState = rememberLazyGridState()
    val currentDocId by produceState<String?>(root) { value = withContext(Dispatchers.IO) { editor.recycleBinDocId(root) } }
    val recycleDocId = currentDocId

    fun toggleSelect(e: BrowseEntry) {
        val i = selection.indexOfFirst { it.documentUri == e.documentUri }
        if (i >= 0) selection.removeAt(i) else selection.add(e)
    }

    BackHandler { onBackToHome() }

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!sidebarOpen) {
                IconButton(onClick = onShowSidebar) {
                    Icon(XnotesIcons.menu, "Show sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                }
            }
            Text("Recycle Bin", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            if (selection.isEmpty()) {
                TextButton(onClick = {
                    recycleDocId?.let { docId ->
                        scope.launch {
                            val entries = withContext(Dispatchers.IO) { editor.browseChildren(root, docId) }
                            if (entries.isNotEmpty()) pendingDelete = entries
                        }
                    }
                }) {
                    Text("Empty Trash", color = palette.accent.toComposeColor())
                }
            }
        }
        // Multi-select toolbar
        if (selection.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconAction(XnotesIcons.trash, "Delete permanently") { pendingDelete = selection.toList() }
                IconAction(XnotesIcons.close, "Deselect") { selection.clear() }
            }
        }
        opError?.let { Text(it, color = Color(0xFFE5534B), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
        Spacer(Modifier.height(10.dp))

        // Listing
        val entries by produceState<List<BrowseEntry>?>(emptyList(), root, recycleDocId, refreshKey) {
            value = recycleDocId?.let { withContext(Dispatchers.IO) { editor.browseChildren(root, it) } }.orEmpty()
        }
        val folders = entries?.filter { it.isDir }.orEmpty()
        val files = entries?.filterNot { it.isDir }.orEmpty()
        val gridColumns = (LocalConfiguration.current.screenWidthDp / 240).coerceIn(2, 8)

        Box(Modifier.weight(1f).fillMaxWidth().then(
            if (selection.isNotEmpty()) Modifier.clickable(interactionSource = dismissInteraction, indication = null) { selection.clear() } else Modifier,
        )) {
            when {
                entries == null -> EmptyPane("Loading…")
                entries!!.isEmpty() -> EmptyPane("The recycle bin is empty.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(entries!!, key = { it.documentUri }) { entry ->
                        if (entry.isDir) {
                            var menuOpen by remember { mutableStateOf(false) }
                            val selected = selection.any { it.documentUri == entry.documentUri }
                            val accent = palette.accent.toComposeColor()
                            val onAccent = palette.bg.toComposeColor()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, if (selected) accent else palette.border.toComposeColor(), RectangleShape)
                                    .background(if (selected) accent else Color.Transparent)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { if (selection.isEmpty()) selection.add(entry) else toggleSelect(entry) },
                                    )
                                    .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    XnotesIcons.folder, null,
                                    tint = if (selected) onAccent else palette.textDim.toComposeColor(),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    entryLabel(entry),
                                    color = if (selected) onAccent else palette.text.toComposeColor(),
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selection.isEmpty()) {
                                    Box {
                                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(XnotesIcons.more, "More", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                                        }
                                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                            DropdownMenuItem(text = { Text("Restore") }, onClick = {
                                                menuOpen = false; opError = "Not implemented yet."
                                            })
                                            DropdownMenuItem(text = { Text("Delete permanently") }, onClick = {
                                                menuOpen = false; pendingDelete = listOf(entry)
                                            })
                                        }
                                    }
                                }
                            }
                        } else {
                            var menuOpen by remember { mutableStateOf(false) }
                            Box {
                                FileTile(
                                    editor = editor,
                                    entry = entry,
                                    selected = selection.any { it.documentUri == entry.documentUri },
                                    dimmed = false,
                                    inSelectMode = selection.isNotEmpty(),
                                    onShare = null,
                                    onSaveCopy = null,
                                    onExportPdf = null,
                                    onRename = null,
                                    onCopy = null,
                                    onCut = null,
                                    onDelete = null,
                                    onTrash = null,
                                    onColor = null,
                                    onClick = {
                                        if (selection.isEmpty()) selection.add(entry) else toggleSelect(entry)
                                    },
                                    onBounds = {},
                                )
                                if (selection.isEmpty()) {
                                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(XnotesIcons.more, "More", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                                        }
                                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                            DropdownMenuItem(text = { Text("Restore") }, onClick = {
                                                menuOpen = false; opError = "Not implemented yet."
                                            })
                                            DropdownMenuItem(text = { Text("Delete permanently") }, onClick = {
                                                menuOpen = false; pendingDelete = listOf(entry)
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete permanently?") },
            text = {
                Text(
                    if (targets.size == 1) "Permanently delete “${entryLabel(targets.first())}”? This can't be undone."
                    else "Permanently delete ${targets.size} items? This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val items = targets.toList()
                    pendingDelete = null; selection.clear(); opError = null
                    scope.launch {
                        val allOk = withContext(Dispatchers.IO) {
                            items.all { e -> editor.deleteDocument(e.documentUri) }
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

/** One row of the explorer's "Sort by" menu: a check and direction arrow mark the active field. */
@Composable
private fun SortOption(
    label: String,
    key: ExplorerSortKey,
    activeKey: ExplorerSortKey,
    descending: Boolean,
    onPick: (ExplorerSortKey, Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val active = key == activeKey
    val tint = (if (active) palette.accent else palette.text).toComposeColor()
    DropdownMenuItem(
        text = { Text(label, color = tint) },
        leadingIcon = {
            if (active) Icon(XnotesIcons.check, null, tint = tint, modifier = Modifier.size(18.dp))
            else Spacer(Modifier.size(18.dp))
        },
        trailingIcon = if (active) {
            {
                Icon(
                    if (descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp,
                    if (descending) "Descending" else "Ascending",
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else null,
        onClick = { if (active) onPick(key, !descending) else onPick(key, key != ExplorerSortKey.NAME) },
    )
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
    val focusManager = LocalFocusManager.current
    // Recursive name filter; lives here so the search pill can fill the constant-height header strip
    // that would otherwise sit empty on wide layouts (where the sidebar, not a hamburger, occupies
    // this row). Cleared when the user changes folders (see ExplorerSection).
    var query by remember { mutableStateOf("") }
    // A tap on empty space anywhere in the pane drops focus from the search field, dismissing it
    // (children like tiles and buttons consume their own taps, so this only fires "outside").
    Column(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
        // Constant-height header so toggling the sidebar never shifts the explorer below it. The
        // hamburger shows only when the sidebar is hidden. With a folder granted, a small centered
        // search pill anchors the row (it expands on focus); without one there's nothing to search,
        // so the wordmark titles it instead (and the menu button isn't stranded — the persistent
        // sidebar brands wide layouts).
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            val hasRoot = editor.browseRoot != null
            if (!sidebarOpen) {
                IconButton(onClick = onShowSidebar) {
                    Icon(XnotesIcons.menu, "Show sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(4.dp))
                if (!hasRoot) Text("xnotes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            if (hasRoot) {
                BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // Comfortable width, with margin left for the spring's overshoot not to clip.
                    val expandedWidth = (maxWidth - 40.dp).coerceIn(180.dp, 440.dp)
                    ExplorerSearchField(query, { query = it }, expandedWidth)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ExplorerSection(
                editor, onOpenFile, onPickRoot, onImportPdf,
                onShareFile, onSaveCopyFile, onExportFilePdf, createMode, onCreateMode,
                searchQuery = query, onSearchChange = { query = it },
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
    searchQuery: String = "",
    onSearchChange: (String) -> Unit = {},
) {
    val palette = LocalPalette.current
    val root = editor.browseRoot
    if (root == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text("Choose a folder to keep and browse your notes in.", color = palette.textDim.toComposeColor(), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(XnotesIcons.folder, "Choose folder", Modifier.fillMaxHeight(), onPickRoot)
                PrimaryButton(XnotesIcons.database, "Use App Storage", Modifier.fillMaxHeight()) { editor.useInternalStorage() }
            }
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
    // Drag-to-move state. While a selection is being dragged onto a folder, [dragPos] is the finger
    // position in window coords, [folderBounds] maps each visible folder to its window rect for
    // hit-testing, [dropTargetUri] is the folder under the finger, and [pulseUri] flashes the folder a
    // dropped move just landed in. [boxCoords] anchors the floating preview into the grid's own space.
    val folderBounds = remember(root) { mutableStateMapOf<String, Rect>() }
    val fileBounds = remember(root) { mutableStateMapOf<String, Rect>() }
    var dragItems by remember(root) { mutableStateOf<List<BrowseEntry>>(emptyList()) }
    var dragPos by remember(root) { mutableStateOf<Offset?>(null) }
    var dropTargetUri by remember(root) { mutableStateOf<String?>(null) }
    var pulseUri by remember(root) { mutableStateOf<String?>(null) }
    var boxCoords by remember(root) { mutableStateOf<LayoutCoordinates?>(null) }
    var dragCardSize by remember(root) { mutableStateOf<IntSize?>(null) }
    val gridState = rememberLazyGridState()
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
    // The folder under the finger, ignoring any folder that's itself part of the dragged selection.
    fun updateDropTarget(pos: Offset) {
        dropTargetUri = folderBounds.entries
            .firstOrNull { (uri, r) -> r.contains(pos) && dragItems.none { it.documentUri == uri } }?.key
    }
    // While dragging a selection near the top/bottom edge of the grid, keep it scrolling so a folder
    // that's currently off-screen can still be reached, the same way the toolbar drag does.
    val autoScrollBand = with(LocalDensity.current) { 72.dp.toPx() }
    val autoScrollMax = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(dragPos != null) {
        while (dragPos != null) {
            val pos = dragPos
            val bc = boxCoords
            if (pos != null && bc != null) {
                val b = bc.boundsInWindow()
                val delta = when {
                    pos.y < b.top + autoScrollBand ->
                        -((b.top + autoScrollBand - pos.y) / autoScrollBand).coerceIn(0f, 1f) * autoScrollMax
                    pos.y > b.bottom - autoScrollBand ->
                        ((pos.y - (b.bottom - autoScrollBand)) / autoScrollBand).coerceIn(0f, 1f) * autoScrollMax
                    else -> 0f
                }
                if (delta != 0f) {
                    gridState.scrollBy(delta)
                    updateDropTarget(pos)
                }
            }
            delay(16L)
        }
    }
    var menuOpen by remember(root) { mutableStateOf(false) }
    // The More menu drills into a "Sort by" sub-list; reset to the main list whenever it closes.
    var sortSubmenu by remember(root) { mutableStateOf(false) }
    LaunchedEffect(menuOpen) { if (!menuOpen) sortSubmenu = false }
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
                        if (sortSubmenu) {
                            val sortKey = editor.explorerSortKey
                            val sortDesc = editor.explorerSortDescending
                            // Header doubles as "back"; tapping a field flips its direction when already
                            // active, else switches to it (dates/size newest-or-largest first, name A→Z).
                            DropdownMenuItem(
                                text = { Text("Sort by", color = palette.textDim.toComposeColor()) },
                                leadingIcon = { Icon(XnotesIcons.prev, "Back", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) },
                                onClick = { sortSubmenu = false },
                            )
                            HorizontalDivider(color = palette.border.toComposeColor())
                            SortOption("Name", ExplorerSortKey.NAME, sortKey, sortDesc) { k, d -> editor.setExplorerSort(k, d); refreshKey++ }
                            SortOption("Date modified", ExplorerSortKey.MODIFIED, sortKey, sortDesc) { k, d -> editor.setExplorerSort(k, d); refreshKey++ }
                            SortOption("Size", ExplorerSortKey.SIZE, sortKey, sortDesc) { k, d -> editor.setExplorerSort(k, d); refreshKey++ }
                        } else {
                            DropdownMenuItem(
                                text = { Text("Sort by") },
                                trailingIcon = { Icon(XnotesIcons.next, null, tint = palette.text.toComposeColor(), modifier = Modifier.size(18.dp)) },
                                onClick = { sortSubmenu = true },
                            )
                            HorizontalDivider(color = palette.border.toComposeColor())
                            DropdownMenuItem(text = { Text("Change folder") }, onClick = { menuOpen = false; onPickRoot() })
                            DropdownMenuItem(text = { Text("Forget folder") }, onClick = { menuOpen = false; editor.clearBrowseRoot() })
                        }
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
                IconAction(XnotesIcons.trash, "Move to Trash") {
                    val items = selection.toList(); selection.clear()
                    scope.launch {
                        val allOk = withContext(Dispatchers.IO) {
                            items.all { e -> editor.recycleDocument(e.documentUri, root, e.parentDocId) }
                        }
                        refreshKey++; if (!allOk) opError = "Couldn’t trash some items."
                    }
                }
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
        // Changing folders drops a stale query so it can't carry into a folder the user just opened.
        LaunchedEffect(currentDocId) { onSearchChange("") }
        val query = searchQuery.trim()
        val searching = query.isNotEmpty()
        // When searching, recurse the whole subtree (debounced) and show only the matching notes — no
        // folder chips, since a deep hit doesn't belong to the folder the breadcrumb is sitting in.
        val results by produceState<List<BrowseEntry>?>(emptyList(), root, currentDocId, query, refreshKey, editor.noteOpen) {
            if (!searching) { value = emptyList(); return@produceState }
            value = null
            delay(250) // debounce keystrokes before walking the tree
            value = withContext(Dispatchers.IO) { editor.searchNotes(root, currentDocId, query) }
        }
        // browseChildren returns grid order: folders (ascending by creation), then files (descending).
        val folders = if (searching) emptyList() else entries?.filter { it.isDir }.orEmpty()
        val files = if (searching) results.orEmpty() else entries?.filterNot { it.isDir }.orEmpty()
        // A fixed column count per orientation, derived from the full screen width (not the pane), so
        // toggling the sidebar never changes how many tiles are in a row — closing it just widens the
        // pane and enlarges the tiles.
        val gridColumns = (LocalConfiguration.current.screenWidthDp / 240).coerceIn(2, 8)
        // Read inside the long-lived drag gesture below, which never restarts, so snapshot the values
        // that change as the user navigates (the folder it sources from, the current file list).
        val filesNow = rememberUpdatedState(files)
        val sourceDocId = rememberUpdatedState(currentDocId)
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { boxCoords = it }
                // Drag-to-move lives on the container (not the tiles) so the gesture keeps running while
                // the grid auto-scrolls and the picked-up tile scrolls out of view. We can't reuse the
                // tiles' clickable for the long-press because a child consuming it (consumeUntilUp) would
                // starve this ancestor, so this is a hand-rolled long-press that hit-tests the file tiles
                // and consumes in the Initial pass (ahead of the tiles) to claim the gesture cleanly.
                .pointerInput(root) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Long-press gate: only a held, near-stationary finger qualifies. A quick lift
                        // (tap) or an early move (scroll) returns from the timeout normally so the
                        // tile/grid keep those; holding past it throws, which is our long-press signal.
                        val longPress = try {
                            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id }
                                    if (c == null || !c.pressed || c.isConsumed) return@withTimeout false
                                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeout false
                                }
                                @Suppress("UNREACHABLE_CODE") false
                            }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            true
                        }
                        if (!longPress) return@awaitEachGesture
                        // Long-press fired. Find the file tile under the finger; ignore folders/empty.
                        val winDown = boxCoords?.localToWindow(down.position) ?: return@awaitEachGesture
                        val hitUri = fileBounds.entries.firstOrNull { it.value.contains(winDown) }?.key
                        if (hitUri == null) return@awaitEachGesture
                        if (selection.none { it.documentUri == hitUri }) {
                            // First long-press selects. Consume to the up (Initial pass, ahead of the
                            // tile) so its click can't toggle the selection straight back off.
                            filesNow.value.firstOrNull { it.documentUri == hitUri }?.let {
                                renaming = null; opError = null; selection.add(it)
                            }
                            do {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.id == down.id && it.pressed })
                            return@awaitEachGesture
                        }
                        // Second long-press on a selected tile: pick the whole selection up and drag it.
                        // Dragging moves files only; if any folder is selected, claim the gesture but don't drag.
                        if (selection.any { it.isDir }) {
                            do {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.id == down.id && it.pressed })
                            return@awaitEachGesture
                        }
                        val rect = fileBounds[hitUri]
                        dragItems = selection.toList()
                        dragCardSize = rect?.let { IntSize(it.width.roundToInt(), it.height.roundToInt()) }
                        var pos = winDown
                        dragPos = pos
                        updateDropTarget(pos)
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            // Read the delta before consuming: positionChange() reports zero once consumed.
                            val delta = c.positionChange()
                            c.consume()
                            if (!c.pressed) break
                            pos += delta
                            dragPos = pos
                            updateDropTarget(pos)
                        }
                        // Drop: move the carried notes into the highlighted folder, then pulse it.
                        val target = dropTargetUri
                        val items = dragItems
                        dragPos = null; dropTargetUri = null; dragItems = emptyList()
                        if (target != null) {
                            val targetDocId = editor.browseDocId(target)
                            pulseUri = target; opError = null
                            val srcParent = sourceDocId.value
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    var all = true
                                    items.forEach { e ->
                                        if (e.documentUri != target &&
                                            !editor.moveDocumentInto(root, e.documentUri, srcParent, targetDocId)) all = false
                                    }
                                    all
                                }
                                selection.clear(); refreshKey++
                                if (!ok) opError = "Couldn’t move some items."
                            }
                        }
                    }
                }
                .then(
                    // In select mode, tapping empty space (not a tile) clears the selection.
                    if (selection.isNotEmpty()) Modifier.clickable(interactionSource = dismissInteraction, indication = null) { selection.clear() } else Modifier,
                ),
        ) {
            when {
                searching && results == null -> EmptyPane("Searching…")
                searching && results!!.isEmpty() -> EmptyPane("No notes match “$query”.")
                !searching && entries == null -> EmptyPane("Loading…")
                !searching && entries!!.isEmpty() -> EmptyPane("This folder has no notes.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 88.dp), // clear the quick-create FAB
                ) {
                    // Folders: one chip per grid cell, so they line up at the same width as the
                    // file tiles below instead of as a separate wrapping row of compact chips.
                    items(folders, key = { it.documentUri }) { entry ->
                        FolderChip(
                            entry = entry,
                            selected = selection.any { it.documentUri == entry.documentUri },
                            dimmed = clipboard?.let { c -> c.isCut && c.entries.any { it.documentUri == entry.documentUri } } == true,
                            inSelectMode = selection.isNotEmpty(),
                            onRename = if (selection.isEmpty()) ({ renaming = entry }) else null,
                            onCopy = if (selection.isEmpty()) ({ clipboard = ClipItem(listOf(entry), currentDocId, false) }) else null,
                            onCut = if (selection.isEmpty()) ({ clipboard = ClipItem(listOf(entry), currentDocId, true) }) else null,
                            onDelete = if (selection.isEmpty()) ({ pendingDelete = listOf(entry) }) else null,
                            onTrash = if (selection.isEmpty()) ({
                                scope.launch {
                                    withContext(Dispatchers.IO) { editor.recycleDocument(entry.documentUri, root, entry.parentDocId) }
                                    refreshKey++
                                }
                            }) else null,
                            onColor = if (selection.isEmpty()) ({ c ->
                                scope.launch { withContext(Dispatchers.IO) { editor.setItemColor(root, entry.parentDocId, entry.name, c) }; refreshKey++ }
                            }) else null,
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
                            isDropTarget = dragPos != null && dropTargetUri == entry.documentUri,
                            pulsing = pulseUri == entry.documentUri,
                            onPulseDone = { if (pulseUri == entry.documentUri) pulseUri = null },
                            onBounds = { r -> if (r == null) folderBounds.remove(entry.documentUri) else folderBounds[entry.documentUri] = r },
                        )
                    }
                    // Break the row so a trailing folder never shares a line with a file tile.
                    if (folders.isNotEmpty() && files.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(0.dp)) }
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
                            onTrash = if (fileActions) ({
                                scope.launch {
                                    withContext(Dispatchers.IO) { editor.recycleDocument(entry.documentUri, root, entry.parentDocId) }
                                    refreshKey++
                                }
                            }) else null,
                            onColor = if (fileActions) ({ c ->
                                scope.launch { withContext(Dispatchers.IO) { editor.setItemColor(root, entry.parentDocId, entry.name, c) }; refreshKey++ }
                            }) else null,
                            onClick = {
                                opError = null
                                if (selection.isNotEmpty()) toggleSelect(entry) else onOpenFile(entry.documentUri)
                            },
                            onBounds = { r -> if (r == null) fileBounds.remove(entry.documentUri) else fileBounds[entry.documentUri] = r },
                        )
                    }
                }
            }
            // Floating stack of the dragged notes, the primary card centred on the finger and lifted.
            val pos = dragPos
            val bc = boxCoords
            val sz = dragCardSize
            if (pos != null && bc != null && sz != null && sz.width > 0) {
                val origin = bc.positionInWindow()
                val lift = with(LocalDensity.current) { 24.dp.toPx() }
                val dx = pos.x - origin.x - sz.width / 2f
                val dy = pos.y - origin.y - sz.height / 2f - lift
                DragPreview(editor, dragItems, sz, Modifier.offset { IntOffset(dx.roundToInt(), dy.roundToInt()) })
            }
        }
    }

    // Name entry for a new note, new folder, or a pending PDF/Open import. Hidden while an import is
    // actually being written, so only the "Importing…" dialog shows.
    if ((createMode != CreateMode.NONE || pendingImport != null) && !editor.importing) {
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
                        // commitImportAsync drives the "Importing…" dialog and runs the copy off-thread.
                        val uri = editor.commitImportAsync(root, currentDocId, n)
                        when {
                            uri != null -> refreshKey++
                            editor.pendingImport != null -> fieldError = "Couldn’t save that note." // genuine failure; keep the prompt
                            // else: cancelled — the prompt already dismissed (pendingImport cleared)
                        }
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
                if (ok) {
                    // Carry the colour code to the new name (sidecar is keyed by name), then re-list.
                    if (entry.color != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { editor.moveItemColor(root, entry.parentDocId, entry.name, newName) }
                            refreshKey++
                        }
                    } else {
                        refreshKey++
                    }
                }
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
                    val items = targets.toList()
                    pendingDelete = null; selection.clear(); opError = null
                    scope.launch {
                        val allOk = withContext(Dispatchers.IO) {
                            var ok = true
                            items.forEach { e ->
                                if (editor.deleteDocument(e.documentUri)) {
                                    // Drop its colour entry from the parent sidecar (a deleted folder's
                                    // own sidecar goes with it).
                                    if (e.color != null) editor.setItemColor(root, e.parentDocId, e.name, null)
                                } else {
                                    ok = false
                                }
                            }
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

/** Height of the search pill (also its rounded-end radius via CircleShape). */
private val SEARCH_HEIGHT = 40.dp

/**
 * The explorer's recursive name filter, shaped as a rounded pill with a left-aligned magnifier (no
 * placeholder text). Idle it's a shorter pill; tapped, it eases out to [expandedWidth] and stops
 * (no overshoot), revealing the field. Tapping outside drops focus, which clears the query and lets
 * it ease back. A trailing ✕ does the same by hand.
 */
@Composable
private fun ExplorerSearchField(query: String, onQueryChange: (String) -> Unit, expandedWidth: Dp) {
    val palette = LocalPalette.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // `active` bootstraps the field into composition on tap so its FocusRequester can grab focus;
    // `focused` is the real focus state. Either keeps the pill expanded.
    var active by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val expanded = active || focused
    // Idle pill is a little shorter than the active one (not a circle) so the grow stays subtle.
    val collapsedWidth = (expandedWidth * 0.7f).coerceAtLeast(150.dp)
    val width by animateDpAsState(
        if (expanded) expandedWidth else collapsedWidth,
        // Eased grow that decelerates into its target and holds — no spring overshoot or rebound.
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "searchWidth",
    )
    LaunchedEffect(active) { if (active) runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .width(width)
            .height(SEARCH_HEIGHT)
            .clip(CircleShape)
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { active = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(XnotesIcons.search, "Search notes", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
        if (expanded) {
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = palette.text.toComposeColor(), fontSize = 14.sp),
                cursorBrush = SolidColor(palette.accent.toComposeColor()),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focused = true
                            active = false // bootstrap done; `focused` holds it open now
                        } else {
                            if (focused) onQueryChange("") // a real blur (tap outside) ends the search
                            focused = false
                        }
                    },
            )
            if (query.isNotEmpty()) {
                Icon(
                    XnotesIcons.close, "Clear search",
                    tint = palette.textDim.toComposeColor(),
                    modifier = Modifier.size(16.dp).clip(CircleShape)
                        .clickable { onQueryChange(""); focusManager.clearFocus() },
                )
            }
        }
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

/** A colour-coded outline, deepened in the light theme like the accent (dark/oled keep it as stored). */
private fun codeOutline(c: Rgba, isDark: Boolean): Rgba = if (isDark) c else ColorMath.darkenForLight(c)

/** The per-entry overflow menu. Files get the extra Share/Save-a-copy/Export block (pass [onShare]); folders don't. */
@Composable
private fun EntryMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onCut: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onTrash: (() -> Unit)?,
    onShare: (() -> Unit)? = null,
    onSaveCopy: (() -> Unit)? = null,
    onExportPdf: (() -> Unit)? = null,
    onColor: ((Rgba?) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    // "Color code" swaps the menu's contents for the swatch picker until a colour (or None) is chosen;
    // closing the menu resets it so it always reopens on the main list.
    var showColors by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) showColors = false }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showColors) {
            ColorCodeMenuContent { c -> onDismiss(); onColor?.invoke(c) }
        } else {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { onDismiss(); onRename?.invoke() })
            DropdownMenuItem(text = { Text("Copy") }, onClick = { onDismiss(); onCopy?.invoke() })
            DropdownMenuItem(text = { Text("Cut") }, onClick = { onDismiss(); onCut?.invoke() })
            if (onColor != null) DropdownMenuItem(text = { Text("Color code") }, onClick = { showColors = true })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { onDismiss(); onDelete?.invoke() })
            DropdownMenuItem(text = { Text("Move to Trash") }, onClick = { onDismiss(); onTrash?.invoke() })
            if (onShare != null) {
                HorizontalDivider(color = palette.border.toComposeColor())
                DropdownMenuItem(text = { Text("Share") }, onClick = { onDismiss(); onShare() })
                DropdownMenuItem(text = { Text("Save a copy…") }, onClick = { onDismiss(); onSaveCopy?.invoke() })
                DropdownMenuItem(text = { Text("Export to PDF") }, onClick = { onDismiss(); onExportPdf?.invoke() })
            }
        }
    }
}

/** Step each deeper card down-and-right by this much so the stack reads as a tidy pile. */
private val DRAG_STACK_STEP = 8.dp

/**
 * A neat stack of the dragged notes drawn as full-size tile cards (same size as the grid tiles, taken
 * from the picked-up tile), trailing the finger while they're moved onto a folder.
 */
@Composable
private fun DragPreview(editor: Editor, items: List<BrowseEntry>, sizePx: IntSize, modifier: Modifier) {
    val density = LocalDensity.current
    val cardW = with(density) { sizePx.width.toDp() }
    val cardH = with(density) { sizePx.height.toDp() }
    // At most three cards; the primary note sits on top of the pile, the rest peek out behind it.
    val shown = items.take(3)
    val spread = DRAG_STACK_STEP * shown.lastIndex.coerceAtLeast(0)
    Box(modifier.size(cardW + spread, cardH + spread)) {
        for (i in shown.indices.reversed()) {
            val shift = DRAG_STACK_STEP * i
            StackedNoteCard(
                editor, shown[i],
                Modifier.offset(shift, shift).size(cardW, cardH).alpha(if (i == 0) 1f else 0.97f),
            )
        }
    }
}

/** One opaque card mirroring a file tile (thumbnail + name + date), for the dragged stack. */
@Composable
private fun StackedNoteCard(editor: Editor, entry: BrowseEntry, modifier: Modifier) {
    val palette = LocalPalette.current
    val thumb = editor.cachedNoteTile(entry.documentUri)
    Column(modifier.background(palette.bg.toComposeColor()).border(1.dp, palette.accent.toComposeColor(), RectangleShape)) {
        Box(Modifier.fillMaxWidth().weight(1f).background(palette.paper.toComposeColor())) {
            if (thumb != null) {
                Image(thumb, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize())
            } else {
                Icon(XnotesIcons.file, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(32.dp).align(Alignment.Center))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(entryLabel(entry), color = palette.text.toComposeColor(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val date = entryDate(entry)
            if (date.isNotEmpty()) {
                Text(date, color = palette.textDim.toComposeColor(), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
    onTrash: (() -> Unit)?,
    onColor: ((Rgba?) -> Unit)?,
    onDismissSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isDropTarget: Boolean,
    pulsing: Boolean,
    onPulseDone: () -> Unit,
    onBounds: (Rect?) -> Unit,
) {
    val palette = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    val accent = palette.accent.toComposeColor()
    val onAccent = palette.bg.toComposeColor()
    val codeColor = entry.color?.let { codeOutline(it, palette.isDark).toComposeColor() }
    // A dropped move flicks the target chip up and back; the hover state tints it with the accent veil.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(pulsing) {
        if (pulsing) {
            pulse.animateTo(1.08f, tween(110))
            pulse.animateTo(1f, tween(160))
            onPulseDone()
        }
    }
    DisposableEffect(Unit) { onDispose { onBounds(null) } }
    // A hovering drag fills the chip exactly like a selection, so the drop target reads the same.
    val active = selected || isDropTarget
    Row(
        Modifier
            .fillMaxWidth()
            .scale(pulse.value)
            .onGloballyPositioned { onBounds(it.boundsInWindow()) }
            // accent-fill toggle: active fills solid accent with content flipped to bg, otherwise a thin
            // bordered transparent box. No tap ripple — the colour invert is the only cue.
            .background(if (active) accent else Color.Transparent)
            .border(1.dp, if (active) accent else (codeColor ?: palette.border.toComposeColor()), RectangleShape)
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
        Icon(XnotesIcons.folder, null, tint = if (active) onAccent else palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            entryLabel(entry), color = if (active) onAccent else palette.text.toComposeColor(), fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        // The overflow button is kept in select mode too, so the chip width (and the row layout) never
        // shifts when selection starts; there a tap dismisses the selection instead of opening the menu.
        Box {
            IconButton(
                onClick = { if (inSelectMode) onDismissSelection() else menuOpen = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(XnotesIcons.more, "More", tint = if (active) onAccent else palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            }
            if (!inSelectMode) EntryMenu(menuOpen, { menuOpen = false }, onRename, onCopy, onCut, onDelete, onTrash, onColor = onColor)
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
    onTrash: (() -> Unit)?,
    onColor: ((Rgba?) -> Unit)?,
    onClick: () -> Unit,
    onBounds: (Rect?) -> Unit,
) {
    val palette = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onBounds(null) } }
    // Seed from the in-memory cache for an instant paint, then load/render off-thread; re-keying on
    // the file's mtime re-renders the tile after the note is edited.
    val thumb by produceState<ImageBitmap?>(editor.cachedNoteTile(entry.documentUri), entry.documentUri, entry.modified) {
        value = editor.noteTileThumbnail(entry.documentUri)
    }
    val accent = palette.accent.toComposeColor()
    val onAccent = palette.bg.toComposeColor()
    val codeColor = entry.color?.let { codeOutline(it, palette.isDark).toComposeColor() }
    Column(
        Modifier
            // Square the whole card; the thumbnail shrinks vertically to leave room for the label strip.
            .aspectRatio(1f)
            .alpha(if (dimmed) 0.4f else 1f)
            // accent-fill family: one squared outline wraps the thumbnail and the label strip. border
            // draws over its children, so it stays crisp on top of the full-bleed thumbnail.
            .border(1.dp, if (selected) accent else (codeColor ?: palette.border.toComposeColor()), RectangleShape)
            // No tap ripple — the accent border + fill is the only selection cue. The tile owns only tap
            // (open / toggle in select mode); long-press to select and the drag-to-move gesture both live
            // on the grid container, so they survive this tile scrolling out from under the finger.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onGloballyPositioned { onBounds(it.boundsInWindow()) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(palette.paper.toComposeColor()),
        ) {
            val img = thumb
            if (img != null) {
                Image(img, entryLabel(entry), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize())
            } else {
                Icon(XnotesIcons.file, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(32.dp).align(Alignment.Center))
            }
            // Selected: a translucent accent veil over the thumbnail, tying it to the accent label strip.
            if (selected) Box(Modifier.matchParentSize().background(palette.accentAlpha(38).toComposeColor()))
            if (!inSelectMode && onRename != null) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(XnotesIcons.more, "More", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                    }
                    EntryMenu(menuOpen, { menuOpen = false }, onRename, onCopy, onCut, onDelete, onTrash, onShare, onSaveCopy, onExportPdf, onColor = onColor)
                }
            }
        }
        // Label strip inside the outline; selected fills accent with the text flipped to bg.
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (selected) accent else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                entryLabel(entry), color = if (selected) onAccent else palette.text.toComposeColor(), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val date = entryDate(entry)
            if (date.isNotEmpty()) {
                Text(
                    date, color = if (selected) onAccent else palette.textDim.toComposeColor(), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
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

private const val PRESS_FILL_MS = 120L

/** Line glyph above a label in a bordered box; inverts to the accent while pressed. Matches the About pane buttons. */
@Composable
private fun PrimaryButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    // Keep the accent fill visible for a minimum time so even a millisecond tap registers.
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        val scope = this
        var pressedAt = 0L
        var clearJob: Job? = null
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    clearJob?.cancel()
                    pressedAt = System.currentTimeMillis()
                    pressed = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val remaining = PRESS_FILL_MS - (System.currentTimeMillis() - pressedAt)
                    clearJob?.cancel()
                    clearJob = scope.launch {
                        if (remaining > 0) delay(remaining)
                        pressed = false
                    }
                }
            }
        }
    }
    val accent = palette.accent.toComposeColor()
    val onAccent = palette.bg.toComposeColor()
    Column(
        modifier
            .background(if (pressed) accent else Color.Transparent)
            .border(1.dp, if (pressed) accent else palette.border.toComposeColor(), RectangleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (pressed) onAccent else accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = if (pressed) onAccent else palette.text.toComposeColor(),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyPane(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 14.sp)
    }
}
