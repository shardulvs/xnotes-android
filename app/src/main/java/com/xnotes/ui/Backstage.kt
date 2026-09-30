package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableIntStateOf
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import com.xnotes.settings.ExplorerView
import com.xnotes.settings.ExplorerLayout
import com.xnotes.settings.FolderPlacement
import com.xnotes.settings.GroupBy
import com.xnotes.settings.TileSize
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.ReaderKind
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import com.xnotes.settings.ExplorerSortKey
import com.xnotes.settings.PinnedFolder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

/** Which pane the backstage shows on the right. */
enum class BackstageView { HOME, PREFERENCES, ABOUT, TRASH }

/** Whether the Home explorer is awaiting a new file/folder name. */
private enum class CreateMode { NONE, FILE, CANVAS, FOLDER }

/** Entries copied or cut in the explorer; each remembers the folder it was listed in. */
private data class ClipItem(val entries: List<BrowseEntry>, val isCut: Boolean)

/** The stem (no extension) offered for a fresh note in [entries], from the filename template. */
private fun nextUntitled(editor: Editor, entries: List<BrowseEntry>?): String =
    editor.newNoteStem(entries.orEmpty().filter { !it.isDir }.map { it.name.lowercase() }.toSet())

/**
 * The full-screen "File" area (the home screen): an in-app file explorer rooted at a folder the user
 * granted, beside a navigation sidebar. On wide screens the sidebar collapses into an icon rail; on
 * phones it is a slide-over drawer. Creating things lives in the explorer's create button.
 */
@Composable
fun Backstage(
    editor: Editor,
    view: BackstageView,
    onSelectView: (BackstageView) -> Unit,
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
    /** Two picked files are to be opened together, one per pane of a split view. */
    onOpenSplit: (String, String) -> Unit = { _, _ -> },
    /** Several files are to be shared at once, as they are. */
    onShareFiles: (List<String>) -> Unit = {},
    /** Opens a file beside the note last open; null when there's no note to pair it with. */
    onOpenBeside: (String) -> (() -> Unit)? = { null },
    onImportIntoNote: (String) -> Unit = {},
) {
    // Below this width the sidebar becomes a slide-over drawer instead of a persistent pane.
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP
    // A folder is required to import into; without one, send the user to pick a folder first.
    val calls = ExplorerCalls(
        openFile = onOpenFile,
        pickRoot = onPickRoot,
        importPdf = { if (editor.browseRoot != null) onImportPdf() else onPickRoot() },
        shareFile = onShareFile,
        shareFiles = onShareFiles,
        saveCopyFile = onSaveCopyFile,
        exportFilePdf = onExportFilePdf,
        openSplit = onOpenSplit,
        openBeside = onOpenBeside,
        importInto = onImportIntoNote,
    )
    // The backstage is the root of the stack — ordinary base content, not a dialog. The activity
    // window already runs edge-to-edge with the system bars hidden (MainActivity.applyFullscreen).
    BackstageContent(editor, compact, view, onSelectView, calls, onExitApp, onImportCodeTheme, onImportFont)
}

/** How far a swipe right on Home travels before it opens the sidebar. */
private val SIDEBAR_SWIPE = 64.dp

/** Width at or above which the sidebar is a persistent pane rather than a drawer. */
internal const val COMPACT_WIDTH_DP = 600

/** Open/close animation duration for the sidebar drawer/pane and its scrim. */
private const val SIDEBAR_ANIM_MS = 150

private val SIDEBAR_WIDTH = 264.dp
private val RAIL_WIDTH = 80.dp
private val DRAWER_WIDTH = 296.dp

/**
 * The home-first layout: the explorer (or Preferences) fills the screen beside the sidebar. Wide
 * screens show either the full sidebar or its rail, remembered across launches; phones slide the
 * sidebar over the explorer from a hamburger.
 */
@Composable
private fun BackstageContent(
    editor: Editor,
    compact: Boolean,
    view: BackstageView,
    onSelectView: (BackstageView) -> Unit,
    calls: ExplorerCalls,
    onExitApp: () -> Unit,
    onImportCodeTheme: () -> Unit,
    onImportFont: () -> Unit,
) {
    val palette = LocalPalette.current
    var createMode by remember { mutableStateOf(CreateMode.NONE) }
    var drawerOpen by remember { mutableStateOf(false) }
    val railed = editor.backstageRail
    val prefs = remember(editor.prefsVersion) { editor.preferences }
    // Close animates only on a true dismiss (scrim, back); a command swaps the pane already composed
    // underneath, so it closes instantly.
    var animateClose by remember { mutableStateOf(true) }
    val dismissDrawer = { animateClose = true; drawerOpen = false }
    val setRailed: (Boolean) -> Unit = { editor.showRail(it) }

    val selectView: (BackstageView) -> Unit = { v ->
        if (v == BackstageView.HOME) createMode = CreateMode.NONE
        onSelectView(v)
        animateClose = false
        drawerOpen = false
    }
    // A sidebar pick the explorer has yet to act on, and the folder or colour filter it is showing.
    var explorerNav by remember { mutableStateOf<ExplorerNav?>(null) }
    var explorerFolder by remember { mutableStateOf<String?>(null) }
    var explorerColor by remember { mutableStateOf<Rgba?>(null) }
    var explorerRecent by remember { mutableStateOf(false) }
    val link = remember(explorerNav) {
        ExplorerLink(explorerNav, { explorerNav = null }) { folder, color, recent -> explorerFolder = folder; explorerColor = color; explorerRecent = recent }
    }
    val scope = rememberCoroutineScope()
    var renamingColor by remember { mutableStateOf<Rgba?>(null) }
    var focusSync by remember { mutableStateOf(false) }
    val pinsOpen = rememberSaveable { mutableStateOf(false) }
    // Colour names live in the notes folder, so pick up another device's edits whenever Home comes back.
    LaunchedEffect(editor.browseRoot, editor.noteOpen) { withContext(Dispatchers.IO) { editor.loadColorNames() } }
    val colors = editor.colorNames.entries.sortedBy { it.value.lowercase() }.map { it.key to it.value }
    val activeColor = if (view == BackstageView.HOME) explorerColor else null
    val recentActive = view == BackstageView.HOME && explorerRecent
    val pins = if (prefs.sidebarPinned) editor.sidebarPins else emptyList()
    val activePin = if (view != BackstageView.HOME || explorerFolder == null || activeColor != null || recentActive) -1
    else pins.indexOfFirst { runCatching { editor.browseDocId(it.uri) }.getOrNull() == explorerFolder }
    val shownColors = if (prefs.sidebarColours) colors else emptyList()
    // With Trash off it leaves the sidebar, once whatever was already in it is restored or emptied.
    val trashCount = if (prefs.sidebarTrash && editor.browseRoot != null && (prefs.trashDays != 0 || editor.trashCount > 0)) editor.trashCount else -1
    LaunchedEffect(editor.browseRoot) { editor.browseRoot?.let { r -> withContext(Dispatchers.IO) { editor.purgeExpiredTrash(r, prefs.trashDays) } } }
    // Kept while its inputs hold, so folding the sidebar never recomposes the rail or the sidebar.
    val showRecent = prefs.sidebarRecent && editor.browseRoot != null
    val nav = remember(view, shownColors, activeColor, pins, activePin, trashCount, showRecent, recentActive, compact, prefs) {
        SidebarNav(
            view = view,
            homeSelected = view == BackstageView.HOME && activePin < 0 && activeColor == null && !recentActive,
            recent = if (!showRecent) null else recentActive,
            onRecent = { selectView(BackstageView.HOME); explorerNav = ExplorerNav(null, recent = true) },
            colors = shownColors,
            activeColor = activeColor,
            pins = pins,
            activePin = activePin,
            trashCount = trashCount,
            onHome = { selectView(BackstageView.HOME); explorerNav = ExplorerNav(null) },
            onSelectView = selectView,
            onOpenColor = { selectView(BackstageView.HOME); explorerNav = ExplorerNav(null, it) },
            onRenameColor = { renamingColor = it },
            onForgetColor = { c -> scope.launch { withContext(Dispatchers.IO) { editor.setColorName(c, null) } } },
            onOpenPin = { selectView(BackstageView.HOME); explorerNav = ExplorerNav(it.uri) },
            onUnpin = { editor.unpinFolder(it.uri) },
            onOpenFile = { e -> if (compact) dismissDrawer(); calls.openFile(e.documentUri) },
            onOpenFolder = { e -> selectView(BackstageView.HOME); explorerNav = ExplorerNav(e.documentUri) },
            onSyncSettings = { focusSync = true; selectView(BackstageView.PREFERENCES) },
            pinsOpen = pinsOpen,
            showFiles = prefs.sidebarFiles,
            showSync = prefs.sidebarSync,
            showAbout = prefs.sidebarAbout,
        )
    }
    renamingColor?.let { c -> ColorNameDialog(editor, c) { renamingColor = null } }

    // Home is the app's root, so it owns every back press while it's up (the editor sits
    // underneath in the same activity — letting the dialog dismiss would just bounce back to
    // it, and the editor's own handler would re-open Home: an endless loop). Back peels off
    // one layer at a time — drawer, Preferences, an in-progress create — and once at the bare
    // Home screen it leaves the app instead. A deeper explorer folder is popped first by the
    // explorer's own (more-nested) handler before this one ever sees the press.
    BackHandler {
        when {
            compact && drawerOpen -> dismissDrawer()
            // Preferences, About and Trash are sub-pages of Home: back lands on Home rather than leaving the app.
            view != BackstageView.HOME -> selectView(BackstageView.HOME)
            createMode != CreateMode.NONE -> createMode = CreateMode.NONE
            else -> onExitApp()
        }
    }

    if (compact) {
        Box(Modifier.fillMaxSize().background(palette.bg.toComposeColor())) {
            BackstageMain(
                Modifier.fillMaxSize(), editor, view, compact, drawerOpen, { animateClose = true; drawerOpen = true }, { selectView(BackstageView.HOME) },
                calls, createMode, { createMode = it }, onImportCodeTheme, onImportFont, link, { selectView(BackstageView.PREFERENCES) },
                focusSync, { focusSync = false },
            )
            AnimatedVisibility(
                visible = drawerOpen,
                enter = fadeIn(animationSpec = tween(SIDEBAR_ANIM_MS)),
                exit = if (animateClose) fadeOut(animationSpec = tween(SIDEBAR_ANIM_MS)) else ExitTransition.None,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().background(palette.materialColors.scrim.withAlpha(82).toComposeColor()).clickable { dismissDrawer() })
            }
            AnimatedVisibility(
                visible = drawerOpen,
                enter = slideInHorizontally(animationSpec = tween(SIDEBAR_ANIM_MS), initialOffsetX = { -it }),
                exit = if (animateClose) slideOutHorizontally(animationSpec = tween(SIDEBAR_ANIM_MS), targetOffsetX = { -it }) else ExitTransition.None,
            ) {
                BackstageSidebar(Modifier.width(DRAWER_WIDTH), editor, nav, dismissDrawer)
            }
        }
    } else {
        // Expanding pushes the explorer along with the panel; collapsing hands it the room at once and the panel
        // folds away over it, as the old sidebar did, since resizing the grid on every frame is what a fold costs.
        // The width is read only in layout, so neither direction recomposes anything.
        val width = animateDpAsState(if (railed) RAIL_WIDTH else SIDEBAR_WIDTH, tween(SIDEBAR_ANIM_MS), label = "sidebarWidth")
        Box(Modifier.fillMaxSize().background(palette.bg.toComposeColor())) {
            // A navigation surface is always on screen here, so the panes never need their own menu button.
            BackstageMain(
                Modifier.fillMaxSize().layout { measurable, constraints ->
                    val inset = (if (railed) RAIL_WIDTH else width.value).roundToPx().coerceIn(0, constraints.maxWidth)
                    val w = constraints.maxWidth - inset
                    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(inset, 0) }
                },
                editor, view, compact, true, { setRailed(false) }, { selectView(BackstageView.HOME) },
                calls, createMode, { createMode = it }, onImportCodeTheme, onImportFont, link, { selectView(BackstageView.PREFERENCES) },
                focusSync, { focusSync = false },
            )
            // Both stay composed so a fold never pays to build one; only the current one is measured and placed.
            Layout(
                content = {
                    BackstageRail(Modifier.width(RAIL_WIDTH), nav) { setRailed(false) }
                    BackstageSidebar(Modifier.width(SIDEBAR_WIDTH), editor, nav) { setRailed(true) }
                },
                modifier = Modifier
                    .fillMaxHeight()
                    .layout { measurable, constraints ->
                        val w = width.value.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
                        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                        layout(w, placeable.height) { placeable.place(0, 0) }
                    }
                    .background(palette.panel.toComposeColor())
                    .clipToBounds(),
            ) { measurables, constraints ->
                val shown = measurables[if (railed) 0 else 1].measure(Constraints(maxHeight = constraints.maxHeight, minHeight = constraints.maxHeight))
                layout(constraints.maxWidth, constraints.maxHeight) { shown.place(0, 0) }
            }
        }
    }
}

/** A sidebar pick for the explorer: a pinned folder, a colour to filter the whole tree by, or neither for the root. */
private class ExplorerNav(val folderUri: String?, val color: Rgba? = null, val recent: Boolean = false)

/** The sidebar's line into the explorer: a pick to act on, and where the explorer reports what it shows. */
private class ExplorerLink(
    val nav: ExplorerNav?,
    val onNavHandled: () -> Unit,
    val onPlace: (folderDocId: String?, color: Rgba?, recent: Boolean) -> Unit,
)

/** What the sidebar and its rail show and do, shared so the two never drift apart. */
private class SidebarNav(
    val view: BackstageView,
    val homeSelected: Boolean,
    /** Whether Recent is showing; null leaves Recent out of the sidebar. */
    val recent: Boolean?,
    val onRecent: () -> Unit,
    /** Named colours only, by name; unnamed ones never reach the sidebar. */
    val colors: List<Pair<Rgba, String>>,
    val activeColor: Rgba?,
    val pins: List<PinnedFolder>,
    /** Index into [pins] of the folder the explorer is showing, or -1. */
    val activePin: Int,
    /** How many items wait in Trash, or -1 to leave Trash out of the sidebar. */
    val trashCount: Int,
    val onHome: () -> Unit,
    val onSelectView: (BackstageView) -> Unit,
    val onOpenColor: (Rgba) -> Unit,
    val onRenameColor: (Rgba) -> Unit,
    val onForgetColor: (Rgba) -> Unit,
    val onOpenPin: (PinnedFolder) -> Unit,
    val onUnpin: (PinnedFolder) -> Unit,
    val onOpenFile: (BrowseEntry) -> Unit,
    val onOpenFolder: (BrowseEntry) -> Unit,
    val onSyncSettings: () -> Unit,
    /** Whether the sidebar's Pinned group is unfolded; kept here so the phone drawer remembers it. */
    val pinsOpen: MutableState<Boolean>,
    val showFiles: Boolean,
    val showSync: Boolean,
    val showAbout: Boolean,
)

/** The full sidebar: a pane on wide screens, a slide-over drawer on phones. */
@Composable
private fun BackstageSidebar(modifier: Modifier, editor: Editor, nav: SidebarNav, onCollapse: () -> Unit) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxHeight().background(palette.panel.toComposeColor()).padding(vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("n-notes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCollapse) {
                Icon(XnotesIcons.prev, stringResource(R.string.collapse_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            nav.recent?.let { on -> Command(XnotesIcons.clock, stringResource(R.string.recent), selected = on) { nav.onRecent() } }
            Command(XnotesIcons.home, stringResource(R.string.home), selected = nav.homeSelected) { nav.onHome() }
            if (nav.trashCount >= 0) {
                Command(XnotesIcons.trash, stringResource(R.string.trash), selected = nav.view == BackstageView.TRASH, count = nav.trashCount.takeIf { it > 0 }?.toString()) {
                    nav.onSelectView(BackstageView.TRASH)
                }
            }
            if (nav.pins.isNotEmpty()) {
                var pinsOpen by nav.pinsOpen
                Command(XnotesIcons.pin, stringResource(R.string.pinned), folded = !pinsOpen) { pinsOpen = !pinsOpen }
                if (pinsOpen) nav.pins.forEachIndexed { i, pin ->
                    key(pin.uri) {
                        PinnedCommand(pin.name, selected = i == nav.activePin, onClick = { nav.onOpenPin(pin) }, onUnpin = { nav.onUnpin(pin) })
                    }
                }
            }
            if (nav.showFiles) {
                if (editor.browseRoot != null) RailDivider()
                SidebarFileTree(editor, nav.onOpenFile, nav.onOpenFolder)
            }
            if (nav.colors.isNotEmpty()) {
                SidebarLabel(stringResource(R.string.toolbar_colours))
                nav.colors.forEach { (color, name) ->
                    key(color) {
                        ColorCommand(
                            color, name, selected = color == nav.activeColor,
                            onClick = { nav.onOpenColor(color) }, onRename = { nav.onRenameColor(color) }, onForget = { nav.onForgetColor(color) },
                        )
                    }
                }
            }
        }
        RailDivider()
        if (nav.showSync) FilenSyncNowCommand(nav.onSyncSettings)
        Command(XnotesIcons.sliders, stringResource(R.string.preferences), selected = nav.view == BackstageView.PREFERENCES) { nav.onSelectView(BackstageView.PREFERENCES) }
        if (nav.showAbout) Command(XnotesIcons.info, stringResource(R.string.about), selected = nav.view == BackstageView.ABOUT) { nav.onSelectView(BackstageView.ABOUT) }
        FilenSidebarStatus()
    }
}

/** The sidebar collapsed to icons on wide screens; its menu button expands it back. */
@Composable
private fun BackstageRail(modifier: Modifier, nav: SidebarNav, onExpand: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier.fillMaxHeight().background(palette.panel.toComposeColor()).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onExpand, modifier = Modifier.size(48.dp)) {
            Icon(XnotesIcons.menu, stringResource(R.string.expand_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            nav.recent?.let { on -> RailItem(XnotesIcons.clock, stringResource(R.string.recent), selected = on) { nav.onRecent() } }
            RailItem(XnotesIcons.home, stringResource(R.string.home), selected = nav.homeSelected) { nav.onHome() }
            if (nav.trashCount >= 0) RailItem(XnotesIcons.trash, stringResource(R.string.trash), selected = nav.view == BackstageView.TRASH) { nav.onSelectView(BackstageView.TRASH) }
            if (nav.pins.isNotEmpty()) Spacer(Modifier.height(8.dp))
            nav.pins.forEachIndexed { i, pin ->
                key(pin.uri) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        RailItem(XnotesIcons.folder, pin.name, selected = i == nav.activePin, onLongClick = { menuOpen = true }) { nav.onOpenPin(pin) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.unpin_from_sidebar)) }, onClick = { menuOpen = false; nav.onUnpin(pin) })
                        }
                    }
                }
            }
        }
        if (nav.showSync) FilenSyncRailItem(nav.onSyncSettings)
        RailItem(XnotesIcons.sliders, stringResource(R.string.preferences), selected = nav.view == BackstageView.PREFERENCES) { nav.onSelectView(BackstageView.PREFERENCES) }
        if (nav.showAbout) RailItem(XnotesIcons.info, stringResource(R.string.about), selected = nav.view == BackstageView.ABOUT) { nav.onSelectView(BackstageView.ABOUT) }
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
    calls: ExplorerCalls,
    createMode: CreateMode,
    onCreateMode: (CreateMode) -> Unit,
    onImportCodeTheme: () -> Unit,
    onImportFont: () -> Unit,
    link: ExplorerLink,
    onOpenPreferences: () -> Unit,
    focusSync: Boolean,
    onSyncFocused: () -> Unit,
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
                        Icon(XnotesIcons.prev, stringResource(R.string.back_to_home), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                } else if (!sidebarOpen) {
                    IconButton(onClick = onShowSidebar) {
                        Icon(XnotesIcons.menu, stringResource(R.string.show_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            when (view) {
                BackstageView.HOME -> HomePane(editor, calls, createMode, onCreateMode, sidebarOpen, onShowSidebar, link)
                BackstageView.PREFERENCES -> PreferencesPane(editor, compact, sidebarOpen, onShowSidebar, onBackToHome, onImportCodeTheme, onImportFont, focusSync, onSyncFocused)
                BackstageView.TRASH -> TrashPane(editor, sidebarOpen, onShowSidebar, onOpenPreferences)
                BackstageView.ABOUT -> AboutPane()
            }
        }
    }
}

// --- left rail ---

/** A sidebar row; [folded] adds a chevron for a group that unfolds in place. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Command(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    count: String? = null,
    folded: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(if (selected) Modifier.background(palette.selectionBackground.toComposeColor()) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = (if (selected) palette.selectionForeground else palette.accent).toComposeColor(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            color = if (selected) palette.selectionForeground.toComposeColor() else palette.text.toComposeColor(),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (count != null) Text(count, color = palette.textDim.toComposeColor(), fontSize = 13.sp)
        if (folded != null) {
            Icon(
                XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor(),
                modifier = Modifier.size(18.dp).rotate(if (folded) -90f else 0f),
            )
        }
    }
}

/** One rail destination: an icon in a pill that fills when selected, its label beneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val pill = MaterialTheme.shapes.large
    Column(
        Modifier.width(RAIL_WIDTH).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(top = 6.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 32.dp).clip(pill)
                .background(if (selected) palette.selectionBackground.toComposeColor() else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = (if (selected) palette.selectionForeground else palette.accent).toComposeColor(), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = palette.text.toComposeColor(),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** A small caption over a group of sidebar rows. */
@Composable
private fun SidebarLabel(text: String) {
    Text(
        text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** A named colour in the sidebar: shows everything carrying it; long-press to rename or forget the name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorCommand(color: Rgba, name: String, selected: Boolean, onClick: () -> Unit, onRename: () -> Unit, onForget: () -> Unit) {
    val palette = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .then(if (selected) Modifier.background(palette.selectionBackground.toComposeColor()) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(codeOutline(color, palette.isDark).toComposeColor()))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                name,
                color = (if (selected) palette.selectionForeground else palette.text).toComposeColor(),
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menuOpen = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.remove_name)) }, onClick = { menuOpen = false; onForget() })
        }
    }
}

/** Names a colour for every folder; a blank name forgets it, which also drops it from the sidebar. */
@Composable
internal fun ColorNameDialog(editor: Editor, color: Rgba, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    NameDialog(
        title = stringResource(R.string.name_this_colour),
        initial = editor.colorNames[color].orEmpty(),
        confirmLabel = stringResource(R.string.save),
        placeholder = stringResource(R.string.colour_name_placeholder),
        allowEmpty = true,
        onConfirm = { name -> scope.launch { withContext(Dispatchers.IO) { editor.setColorName(color, name) }; onDone() } },
        onDismiss = onDone,
        onRemove = if (editor.colorNames[color] == null) null else {
            { scope.launch { withContext(Dispatchers.IO) { editor.setColorName(color, null) }; onDone() } }
        },
    )
}

/** A pinned folder in the sidebar; long-press offers to unpin it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedCommand(label: String, selected: Boolean, onClick: () -> Unit, onUnpin: () -> Unit) {
    val palette = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .then(if (selected) Modifier.background(palette.selectionBackground.toComposeColor()) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(XnotesIcons.folder, null, tint = (if (selected) palette.selectionForeground else palette.accent).toComposeColor(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                color = (if (selected) palette.selectionForeground else palette.text).toComposeColor(),
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.unpin_from_sidebar)) }, onClick = { menuOpen = false; onUnpin() })
        }
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
    val active = key == activeKey
    ChoiceMenuItem(
        active,
        onClick = { if (active) onPick(key, !descending) else onPick(key, key != ExplorerSortKey.NAME) },
        trailingIcon = if (active) {
            { fg ->
                Icon(
                    if (descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp,
                    if (descending) stringResource(R.string.sort_descending) else stringResource(R.string.sort_ascending),
                    tint = fg,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else null,
    ) { fg -> Text(label, color = fg) }
}

// --- home pane: the folder explorer ---

/** The things the explorer can add to the current folder; shared by the quick-create button and the ⋮ menu. */
@Composable
private fun NewItemMenuItems(
    onClose: () -> Unit,
    onCreateMode: (CreateMode) -> Unit,
    onImportPdf: () -> Unit,
) {
    val tint = LocalPalette.current.text.toComposeColor()
    // Taller than a default menu row (the explorer's primary create affordance), but only as wide
    // as the longest label plus a margin. The end padding is the larger of the two on purpose: the
    // icons carry ~1dp of their own inset, so an equal split reads as tight on the text side.
    val row = Modifier.height(52.dp)
    val pad = PaddingValues(start = 14.dp, end = 18.dp)
    @Composable
    fun item(icon: ImageVector, label: String, onPick: () -> Unit) = DropdownMenuItem(
        text = { Text(label, fontSize = 16.sp) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp)) },
        onClick = { onClose(); onPick() },
        modifier = row,
        contentPadding = pad,
    )
    item(XnotesIcons.edit, stringResource(R.string.new_note_menu)) { onCreateMode(CreateMode.FILE) }
    item(XnotesIcons.canvas, stringResource(R.string.new_canvas_menu)) { onCreateMode(CreateMode.CANVAS) }
    item(XnotesIcons.importDoc, stringResource(R.string.import_file)) { onImportPdf() }
    item(XnotesIcons.newFolder, stringResource(R.string.new_folder_menu)) { onCreateMode(CreateMode.FOLDER) }
}

/** What the explorer hands back to the activity: opening, sharing and exporting files it can't do itself. */
private class ExplorerCalls(
    val openFile: (String) -> Unit,
    val pickRoot: () -> Unit,
    val importPdf: () -> Unit,
    val shareFile: (String) -> Unit,
    val shareFiles: (List<String>) -> Unit,
    val saveCopyFile: (String) -> Unit,
    val exportFilePdf: (String) -> Unit,
    val openSplit: (String, String) -> Unit,
    /** Opens a file beside the note last open, or null when there is none to pair it with. */
    val openBeside: (String) -> (() -> Unit)?,
    /** Picks a file and appends it to the note at this URI. */
    val importInto: (String) -> Unit,
)

@Composable
private fun HomePane(
    editor: Editor,
    calls: ExplorerCalls,
    createMode: CreateMode,
    onCreateMode: (CreateMode) -> Unit,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
    link: ExplorerLink,
) {
    val palette = LocalPalette.current
    val focusManager = LocalFocusManager.current
    val prefs = remember(editor.prefsVersion) { editor.preferences }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    // A tap on empty space anywhere in the pane drops focus from the search field, dismissing it
    // (children like tiles and buttons consume their own taps, so this only fires "outside").
    // A swipe right brings in the sidebar; scrolling children claim their own horizontal drags first.
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .pointerInput(onShowSidebar) {
                val trigger = SIDEBAR_SWIPE.toPx()
                var dragged = 0f
                detectHorizontalDragGestures(onDragStart = { dragged = 0f }) { change, dx ->
                    change.consume()
                    dragged += dx
                    if (dragged > trigger) {
                        dragged = Float.NEGATIVE_INFINITY
                        onShowSidebar()
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Filen sync heads-up when a note was changed on two devices; renders nothing otherwise.
            SyncConflictBanner()
            // Pulling down from the top syncs with Filen when it is set up, then lists the folder again.
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    scope.launch {
                        if (com.xnotes.sync.filen.FilenSyncManager.isConfigured(ctx)) com.xnotes.sync.filen.FilenSyncManager.syncNow(ctx)
                        editor.relistTree()
                        refreshing = false
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                ExplorerSection(editor, calls, createMode, onCreateMode, sidebarOpen, onShowSidebar, link)
            }
        }
        // A round quick-create button: the same things as the ⋮ menu's New block, landed in the current
        // folder. Only with a folder granted, else there's nowhere to create.
        if (editor.browseRoot != null && prefs.showCreateButton) {
            var createMenuOpen by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                FloatingActionButton(
                    onClick = { createMenuOpen = true },
                    shape = CircleShape,
                ) {
                    Icon(XnotesIcons.edit, stringResource(R.string.create_new), modifier = Modifier.size(24.dp))
                }
                DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                    NewItemMenuItems({ createMenuOpen = false }, onCreateMode, calls.importPdf)
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
    calls: ExplorerCalls,
    createMode: CreateMode,
    onCreateMode: (CreateMode) -> Unit,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
    link: ExplorerLink,
) {
    val palette = LocalPalette.current
    val root = editor.browseRoot
    val nav = link.nav
    if (root == null) {
        if (nav != null) LaunchedEffect(nav) { link.onNavHandled() }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sidebarOpen) {
                    IconButton(onClick = onShowSidebar) {
                        Icon(XnotesIcons.menu, stringResource(R.string.show_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("n-notes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                EmptyIllustration(EmptyArt.PAGES)
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.choose_folder_hint), color = palette.textDim.toComposeColor(), fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(XnotesIcons.folder, stringResource(R.string.choose_folder), Modifier.fillMaxHeight(), calls.pickRoot)
                    PrimaryButton(XnotesIcons.database, stringResource(R.string.use_app_storage), Modifier.fillMaxHeight()) { editor.useInternalStorage() }
                }
                Spacer(Modifier.weight(1f))
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val words = rememberExplorerWords()
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val compactScreen = screenWidthDp < COMPACT_WIDTH_DP
    val prefs = remember(editor.prefsVersion) { editor.preferences }
    val rootDocId = remember(root) { editor.browseRootDocId(root) }
    val stack = remember(root) { mutableStateListOf<Pair<String, String>>() }
    val currentDocId = if (stack.isEmpty()) rootDocId else stack.last().first
    var refreshKey by remember(root) { mutableIntStateOf(0) }
    var fieldError by remember(root) { mutableStateOf<String?>(null) }
    var renaming by remember(root) { mutableStateOf<BrowseEntry?>(null) }
    val selection = remember(root) { mutableStateListOf<BrowseEntry>() }
    var clipboard by remember(root) { mutableStateOf<ClipItem?>(null) }
    var pendingDelete by remember(root) { mutableStateOf<List<BrowseEntry>?>(null) }
    var moving by remember(root) { mutableStateOf<List<BrowseEntry>?>(null) }
    var merging by remember(root) { mutableStateOf<List<BrowseEntry>?>(null) }
    var previewing by remember(root) { mutableStateOf<BrowseEntry?>(null) }
    var opError by remember(root) { mutableStateOf<String?>(null) }
    var query by remember(root) { mutableStateOf("") }
    // Set from the sidebar: the whole tree's items carrying this colour, shown in place of the folder.
    var colorFilter by remember(root) { mutableStateOf<Rgba?>(null) }
    var kindFilter by remember(root) { mutableStateOf<EntryKind?>(null) }
    // Set from the sidebar: what was opened lately, from every folder, in place of the folder.
    var showRecent by remember(root) { mutableStateOf(false) }
    var includeSubfolders by remember(root) { mutableStateOf(true) }
    var timelineMonth by remember(root) { mutableStateOf<YearMonth?>(null) }
    var columnsPick by remember(root) { mutableStateOf<BrowseEntry?>(null) }
    var namingColor by remember(root) { mutableStateOf<Rgba?>(null) }
    var converting by remember(root) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var metaTick by remember(root) { mutableIntStateOf(0) }
    fun clearUp() { selection.clear(); opError = null; columnsPick = null }
    // A sidebar pick either filters by a colour or replaces the path with a folder's chain from the root.
    LaunchedEffect(nav) {
        val target = nav ?: return@LaunchedEffect
        val color = target.color
        if (target.recent) {
            showRecent = true
            colorFilter = null
        } else if (color != null) {
            colorFilter = color
            showRecent = false
        } else {
            val folderUri = target.folderUri
            val chain = if (folderUri == null) emptyList() else withContext(Dispatchers.IO) { editor.folderChain(root, folderUri) }
            if (chain == null) {
                opError = context.getString(R.string.err_open_folder)
            } else {
                stack.clear()
                stack.addAll(chain)
                opError = null
            }
            colorFilter = null
            showRecent = false
        }
        selection.clear()
        columnsPick = null
        query = ""
        link.onNavHandled()
    }
    LaunchedEffect(currentDocId, colorFilter, showRecent) { link.onPlace(if (stack.isEmpty()) null else currentDocId, colorFilter, showRecent) }
    LaunchedEffect(query) { if (query.isNotBlank()) { colorFilter = null; showRecent = false } }
    // Home opens on the folder last shown when set to; until that's settled, nothing is saved over it.
    var settled by remember(root) { mutableStateOf(false) }
    LaunchedEffect(root) {
        val last = editor.lastFolder
        if (prefs.homeOpensTo == "last" && nav == null && last != null && stack.isEmpty()) {
            withContext(Dispatchers.IO) { editor.folderChain(root, last) }?.let { chain -> if (stack.isEmpty()) stack.addAll(chain) }
        }
        settled = true
    }
    LaunchedEffect(currentDocId, settled) {
        if (settled) editor.setLastFolder(if (stack.isEmpty()) null else android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(root), currentDocId).toString())
    }
    // Changing folders drops a stale query so it can't carry into a folder the user just opened.
    LaunchedEffect(currentDocId) { query = "" }
    // Inside a subfolder, back climbs one level out (this sits below the Backstage's root
    // handler, so it's consulted first and only fires while there's a folder to leave).
    BackHandler(enabled = stack.isNotEmpty() && colorFilter == null) {
        stack.removeAt(stack.lastIndex)
        clearUp()
    }
    BackHandler(enabled = colorFilter != null) {
        colorFilter = null
        selection.clear()
    }
    BackHandler(enabled = showRecent) {
        showRecent = false
        selection.clear()
    }
    BackHandler(enabled = selection.isNotEmpty()) { selection.clear() }

    val folderKey = editor.folderKey(root, currentDocId)
    val view = editor.viewFor(folderKey)
    val setView: (ExplorerView) -> Unit = { editor.setView(folderKey, it) }
    val setViewNow = rememberUpdatedState(setView)
    val searching = query.isNotBlank()
    val flat = searching || colorFilter != null || showRecent
    val layout = when {
        flat && (view.layout == ExplorerLayout.COLUMNS || view.layout == ExplorerLayout.TIMELINE) -> ExplorerLayout.GRID
        compactScreen && view.layout == ExplorerLayout.COLUMNS -> ExplorerLayout.LIST
        else -> view.layout
    }
    val switcherLayouts = prefs.switcherLayouts.filter { !(compactScreen && it == ExplorerLayout.COLUMNS) }

    // Listing. Re-keyed on noteOpen so returning from the editor re-queries the folder, picking up
    // the just-closed note's new mtime (its tile refreshes) and any newly created/discovered items.
    val entries by produceState(editor.cachedChildren(root, currentDocId), root, currentDocId, refreshKey, editor.noteOpen, editor.treeVersion) {
        value = withContext(Dispatchers.IO) { editor.browseChildren(root, currentDocId) }
    }
    val trimmed = query.trim()
    // When searching, recurse the whole subtree (debounced) and show only the matching notes — no
    // folders, since a deep hit doesn't belong to the folder the breadcrumb is sitting in.
    val results by produceState<List<BrowseEntry>?>(emptyList(), root, currentDocId, trimmed, refreshKey, editor.noteOpen, editor.treeVersion) {
        if (trimmed.isEmpty()) { value = emptyList(); return@produceState }
        value = null
        delay(250) // debounce keystrokes before walking the tree
        value = withContext(Dispatchers.IO) { editor.searchNotes(root, currentDocId, trimmed) }
    }
    val colorResults by produceState<List<BrowseEntry>?>(null, root, colorFilter, refreshKey, editor.noteOpen, editor.treeVersion) {
        val c = colorFilter ?: run { value = emptyList(); return@produceState }
        value = null
        value = withContext(Dispatchers.IO) { editor.findByColor(root, c) }
    }
    val timeline by produceState<TreeFiles?>(null, root, currentDocId, includeSubfolders, refreshKey, editor.noteOpen, editor.treeVersion, layout == ExplorerLayout.TIMELINE) {
        if (layout != ExplorerLayout.TIMELINE) return@produceState
        value = withContext(Dispatchers.IO) { editor.filesUnder(root, currentDocId, includeSubfolders) }
    }
    val shelves = prefs.homeOpensTo == "shelves" && stack.isEmpty() && !flat &&
        (layout == ExplorerLayout.GRID || layout == ExplorerLayout.GALLERY || layout == ExplorerLayout.LIST)
    val recents by produceState<List<RecentEntry>?>(null, root, showRecent || shelves, editor.recentDocs, refreshKey, editor.noteOpen, editor.treeVersion) {
        if (showRecent || shelves) value = withContext(Dispatchers.IO) { editor.recentEntries(root) }
    }
    val recentByUri = remember(recents) { recents.orEmpty().associateBy { it.entry.documentUri } }
    val source: List<BrowseEntry>? = when {
        showRecent -> recents?.map { it.entry }
        colorFilter != null -> colorResults
        searching -> results
        layout == ExplorerLayout.TIMELINE -> timeline?.files
        else -> entries
    }
    // Page counts, PDF flags and the created times files record fill in behind the listing, a few files at a time.
    LaunchedEffect(source) {
        val todo = source?.filter { !it.isDir && editor.cachedMeta(it) == null && DocumentKind.isDocument(it.name) }
        if (todo.isNullOrEmpty()) return@LaunchedEffect
        for (chunk in todo.chunked(6)) {
            withContext(Dispatchers.IO) { chunk.forEach { editor.docMetaFor(it) } }
            metaTick++
        }
    }
    val kindOf: (BrowseEntry) -> EntryKind = { entryKind(it, editor.cachedMeta(it)) }
    val withCreated: (BrowseEntry) -> BrowseEntry = { e -> editor.createdOf(e).let { if (it == e.created) e else e.copy(created = it) } }
    val arrange: (List<BrowseEntry>) -> List<BrowseEntry> = remember(view.sortKey, view.descending, view.folders, kindFilter, metaTick) {
        { list ->
            list.filter { e -> if (e.isDir) view.folders != FolderPlacement.HIDDEN && kindFilter == null else kindFilter == null || kindOf(e) == kindFilter }
                .map(withCreated)
                .sortedWith(explorerComparator(view.sortKey, view.descending, foldersFirst = view.folders != FolderPlacement.MIXED) { it.created })
        }
    }
    // Recent keeps the order things were opened in.
    val arranged = remember(source, arrange, showRecent) {
        if (showRecent) source?.filter { kindFilter == null || kindOf(it) == kindFilter } else source?.let(arrange)
    }
    // Columns are how one gets around in that layout, so their folders stay whatever the placement says.
    val columnsArrange: (List<BrowseEntry>) -> List<BrowseEntry> = remember(view.sortKey, view.descending, kindFilter, metaTick) {
        { list ->
            list.filter { e -> e.isDir || kindFilter == null || kindOf(e) == kindFilter }
                .map(withCreated)
                .sortedWith(explorerComparator(view.sortKey, view.descending) { it.created })
        }
    }
    val now = remember(arranged) { System.currentTimeMillis() }
    val folderRow = remember(arranged, view.folders, layout) {
        if (view.folders == FolderPlacement.TOP && layout != ExplorerLayout.TIMELINE) arranged.orEmpty().filter { it.isDir } else emptyList()
    }
    val pool = remember(arranged, view.folders) { if (view.folders == FolderPlacement.MIXED) arranged.orEmpty() else arranged.orEmpty().filterNot { it.isDir } }
    val colorNames = editor.colorNames
    val groups = remember(pool, view.groupBy, view.sortKey, view.descending, colorNames, metaTick, showRecent) {
        groupEntries(
            words,
            pool, if (showRecent) GroupBy.NONE else view.groupBy, view.sortKey, view.descending, kindOf, { colorNames[it] }, now, ZoneId.systemDefault(),
            WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek,
        )
    }
    val metas = remember(arranged, metaTick) { arranged.orEmpty().filter { !it.isDir }.associate { it.documentUri to editor.cachedMeta(it) } }
    val counts by produceState(emptyMap<String, Int>(), root, arranged, prefs.showFolderCounts) {
        val folders = arranged.orEmpty().filter { it.isDir }
        value = if (!prefs.showFolderCounts || folders.isEmpty()) emptyMap()
        else withContext(Dispatchers.IO) { folders.associate { it.documentUri to editor.folderItemCount(root, it) } }
    }

    // Drag-to-move state. While a selection is being dragged onto a folder, [dragPos] is the finger
    // position in window coords, [folderSpots] maps each visible folder to its coordinates for
    // hit-testing, [dropTargetUri] is the folder under the finger, and [pulseUri] flashes the folder a
    // dropped move just landed in. [boxCoords] anchors the floating preview into the body's own space.
    // Coordinates, not rects: tiles move every frame of a scroll or resize, and only a drag needs bounds.
    val folderSpots = remember(root) { HashMap<String, LayoutCoordinates>() }
    val fileSpots = remember(root) { HashMap<String, LayoutCoordinates>() }
    var dragItems by remember(root) { mutableStateOf<List<BrowseEntry>>(emptyList()) }
    var dragPos by remember(root) { mutableStateOf<Offset?>(null) }
    var dropTargetUri by remember(root) { mutableStateOf<String?>(null) }
    var pulseUri by remember(root) { mutableStateOf<String?>(null) }
    var boxCoords by remember(root) { mutableStateOf<LayoutCoordinates?>(null) }
    var dragCardSize by remember(root) { mutableStateOf<IntSize?>(null) }
    val gridState = rememberLazyGridState()
    val galleryState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val timelineState = rememberLazyListState()
    val scrollNow = rememberUpdatedState<ScrollableState>(
        when (layout) { ExplorerLayout.LIST -> listState; ExplorerLayout.GALLERY -> galleryState; ExplorerLayout.TIMELINE -> timelineState; else -> gridState },
    )
    fun toggleSelect(e: BrowseEntry) {
        val i = selection.indexOfFirst { it.documentUri == e.documentUri }
        if (i >= 0) selection.removeAt(i) else selection.add(e)
    }
    // The folder under the finger, ignoring any folder that's itself part of the dragged selection.
    fun updateDropTarget(pos: Offset) {
        dropTargetUri = folderSpots.entries
            .firstOrNull { (uri, c) -> c.isAttached && c.boundsInWindow().contains(pos) && dragItems.none { it.documentUri == uri } }?.key
    }
    // While dragging a selection near the top/bottom edge of the body, keep it scrolling so a folder
    // that's currently off-screen can still be reached, the same way the toolbar drag does.
    val autoScrollBand = with(density) { 72.dp.toPx() }
    val headerBand = with(density) { EXPLORER_HEADER.toPx() }
    LaunchedEffect(dragPos != null) {
        while (dragPos != null) {
            val pos = dragPos
            val bc = boxCoords
            if (pos != null && bc != null) {
                val b = bc.boundsInWindow()
                val top = b.top + headerBand // the files run up under the header, so its bottom is their top edge
                val delta = when {
                    pos.y < top + autoScrollBand -> -((top + autoScrollBand - pos.y) / autoScrollBand).coerceIn(0f, 1f) * autoScrollBand
                    pos.y > b.bottom - autoScrollBand -> ((pos.y - (b.bottom - autoScrollBand)) / autoScrollBand).coerceIn(0f, 1f) * autoScrollBand
                    else -> 0f
                }
                if (delta != 0f) {
                    scrollNow.value.scrollBy(delta)
                    updateDropTarget(pos)
                }
            }
            delay(16L)
        }
    }

    val callsNow = rememberUpdatedState(calls)
    val prefsNow = rememberUpdatedState(prefs)
    val rootName by produceState(editor.cachedRootName(root), root) { value = withContext(Dispatchers.IO) { editor.browseRootName(root) } }
    fun openChain(folderUri: String) {
        scope.launch {
            val chain = withContext(Dispatchers.IO) { editor.folderChain(root, folderUri) }
            if (chain == null) opError = context.getString(R.string.err_open_folder) else {
                stack.clear()
                stack.addAll(chain)
                colorFilter = null
                showRecent = false
                query = ""
                clearUp()
            }
        }
    }
    fun openFolder(e: BrowseEntry) {
        opError = null
        // Read afresh: the tile callbacks outlive the composition this was built in.
        if (query.isNotBlank() || colorFilter != null || showRecent) openChain(e.documentUri) else {
            stack.add(editor.browseDocId(e.documentUri) to e.name)
            columnsPick = null
        }
    }
    // Into Trash with an Undo, or through the delete-for-good dialog when Trash is off or can't take an item.
    fun remove(items: List<BrowseEntry>) {
        if (items.isEmpty()) return
        if (prefsNow.value.trashDays == 0) { pendingDelete = items; return }
        selection.clear(); opError = null
        scope.launch {
            val (trashed, failed) = withContext(Dispatchers.IO) { editor.trashEntries(root, items) }
            refreshKey++
            if (trashed.isNotEmpty()) {
                val what = if (trashed.size == 1) context.getString(R.string.moved_one_to_trash, entryLabel(trashed.first().entry)) else context.resources.getQuantityString(R.plurals.moved_items_to_trash, trashed.size, trashed.size)
                editor.say(what, context.getString(R.string.undo) to { editor.undoTrash(root, trashed) })
            }
            if (failed.isNotEmpty()) {
                opError = context.resources.getQuantityString(R.plurals.err_move_items_to_trash, failed.size, failed.size)
                pendingDelete = failed
            }
        }
    }
    // PDFs go in as they are; the other reader files are printed to PDF first so the note looks like the reader.
    fun convertToNote(e: BrowseEntry) {
        opError = null
        val name = e.name.substringBeforeLast('.')
        val title = context.getString(R.string.convert_to_note)
        if (ReaderKind.ofName(e.name) == ReaderKind.PDF) {
            editor.requestImport(ImportKind.PDF, name, e.documentUri, e.name, "application/pdf", e.parentDocId, title)
            return
        }
        converting?.cancel()
        converting = scope.launch {
            val pdf = readerFileToPdf(context, editor, ReaderFile(e.documentUri, e.name))
            converting = null
            if (pdf == null) opError = context.getString(R.string.err_convert_to_note)
            else editor.requestImport(ImportKind.PDF, name, android.net.Uri.fromFile(pdf).toString(), pdf.name, "application/pdf", e.parentDocId, title)
        }
    }
    fun recolor(items: List<BrowseEntry>, c: Rgba?) {
        scope.launch {
            withContext(Dispatchers.IO) {
                items.groupBy { it.parentDocId }.forEach { (parent, list) -> editor.setItemColors(root, parent, list.associate { it.name to c }) }
            }
            refreshKey++
        }
    }
    val host = remember(root) {
        TileHost(
            isSelected = { e -> selection.any { it.documentUri == e.documentUri } },
            selecting = { selection.isNotEmpty() },
            isCut = { e -> clipboard?.let { c -> c.isCut && c.entries.any { it.documentUri == e.documentUri } } == true },
            isPinned = { e -> e.isDir && editor.isPinned(e.documentUri) },
            isDropTarget = { e -> dragPos != null && dropTargetUri == e.documentUri },
            isPulsing = { e -> pulseUri == e.documentUri },
            onPulseDone = { e -> if (pulseUri == e.documentUri) pulseUri = null },
            onClick = { e ->
                opError = null
                when {
                    selection.isNotEmpty() -> toggleSelect(e)
                    e.isDir -> openFolder(e)
                    prefsNow.value.tapPreviews && !ReaderKind.isReadable(e.name) -> previewing = e
                    else -> callsNow.value.openFile(e.documentUri)
                }
            },
            onLongClick = { e ->
                renaming = null; opError = null
                if (selection.none { it.documentUri == e.documentUri }) selection.add(e)
            },
            onPlaced = { e, c ->
                val spots = if (e.isDir) folderSpots else fileSpots
                if (c == null) spots.remove(e.documentUri) else spots[e.documentUri] = c
            },
            menu = EntryActions(
                rename = { renaming = it },
                copy = { clipboard = ClipItem(listOf(it), false) },
                cut = { clipboard = ClipItem(listOf(it), true) },
                moveTo = { moving = listOf(it) },
                delete = { remove(listOf(it)) },
                color = { e, c -> recolor(listOf(e), c) },
                nameColor = { namingColor = it },
                togglePin = { e -> if (editor.isPinned(e.documentUri)) editor.unpinFolder(e.documentUri) else editor.pinFolder(e.documentUri, e.name) },
                share = { callsNow.value.shareFile(it.documentUri) },
                saveCopy = { callsNow.value.saveCopyFile(it.documentUri) },
                exportPdf = { callsNow.value.exportFilePdf(it.documentUri) },
                preview = { previewing = it },
                convertToNote = { convertToNote(it) },
                importInto = { callsNow.value.importInto(it.documentUri) },
            ),
        )
    }
    val folderNames = timeline?.folderNames
    val clock24 = android.text.format.DateFormat.is24HourFormat(context)
    // Kept while what it describes holds, so a drag (which recomposes this every frame) leaves the tiles be.
    val body = remember(view, folderRow, groups, metas, counts, now, clock24, prefs, host, showRecent, recentByUri, folderNames, layout, words) { ExplorerBody(
        editor = editor,
        view = view,
        folders = folderRow,
        groups = groups,
        metas = metas,
        counts = counts,
        now = now,
        clock24 = clock24,
        dateStyle = prefs.dateStyle,
        showExtensions = prefs.showExtensions,
        deleteLabel = if (prefs.trashDays == 0) context.getString(R.string.delete) else context.getString(R.string.move_to_trash),
        host = host,
        words = words,
        whereOf = when {
            showRecent -> ({ e -> recentByUri[e.documentUri]?.where })
            layout == ExplorerLayout.TIMELINE && folderNames != null -> ({ e -> folderNames[e.parentDocId] })
            else -> null
        },
        metaOverride = if (showRecent) ({ e -> recentByUri[e.documentUri]?.let { openedLabel(words, it.opened, now) } }) else null,
    ) }
    val pathText = (listOf(rootName ?: stringResource(R.string.folder)) + stack.map { it.second }).joinToString(" / ")
    // The Timeline opens on the month of the newest note, until a month is picked.
    val newestMonth = remember(arranged, view.timelineByCreated) {
        arranged.orEmpty().maxOfOrNull { view.timelineTime(it) }?.takeIf { it > 0 }
            ?.let { YearMonth.from(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: YearMonth.now()
    }
    val shownMonth = timelineMonth ?: newestMonth
    fun previewActions(e: BrowseEntry) = PreviewActions(
        open = { previewing = null; calls.openFile(e.documentUri) },
        openBeside = calls.openBeside(e.documentUri)?.let { go -> { previewing = null; go() } },
        share = { calls.shareFile(e.documentUri) },
        exportPdf = { calls.exportFilePdf(e.documentUri) },
        color = { c -> recolor(listOf(e), c) },
    )

    fun paste(clip: ClipItem) {
        opError = null
        scope.launch {
            val allOk = withContext(Dispatchers.IO) {
                val done = if (clip.isCut) editor.moveEntriesInto(root, clip.entries, currentDocId)
                else editor.copyEntriesInto(root, clip.entries, currentDocId)
                done == clip.entries.size
            }
            refreshKey++
            if (allOk) clipboard = null else opError = context.getString(R.string.err_paste_some)
        }
    }

    // A failed operation shows in the app's snackbar, which stays in view however far the files are scrolled.
    LaunchedEffect(opError) { opError?.let { editor.say(it); opError = null } }
    val empty = when {
        colorFilter != null && colorResults == null -> EmptyState(stringResource(R.string.finding))
        colorFilter != null && colorResults!!.isEmpty() -> EmptyState(stringResource(R.string.nothing_this_colour), EmptyArt.SEARCH)
        searching && results == null -> EmptyState(stringResource(R.string.searching))
        searching && results!!.isEmpty() -> EmptyState(stringResource(R.string.no_notes_match, trimmed), EmptyArt.SEARCH)
        showRecent && recents == null -> EmptyState(stringResource(R.string.loading))
        showRecent && recents!!.isEmpty() -> EmptyState(stringResource(R.string.recent_empty), EmptyArt.RECENT)
        source == null -> EmptyState(stringResource(R.string.loading))
        layout == ExplorerLayout.COLUMNS -> null
        source.isEmpty() -> EmptyState(
            if (layout == ExplorerLayout.TIMELINE) stringResource(R.string.nothing_here_yet) else stringResource(R.string.folder_empty),
            EmptyArt.PAGES, offersCreate = true,
        )
        arranged.isNullOrEmpty() -> EmptyState(
            when (kindFilter) {
                null -> stringResource(R.string.nothing_to_show)
                EntryKind.PDF -> stringResource(R.string.no_pdf_notes_here)
                EntryKind.FOLDER -> stringResource(R.string.kind_no_folders)
                EntryKind.NOTE -> stringResource(R.string.kind_no_notes)
                EntryKind.CANVAS -> stringResource(R.string.kind_no_canvases)
                EntryKind.FILE -> stringResource(R.string.kind_no_read_only_files)
            },
            EmptyArt.SEARCH,
        )
        else -> null
    }
    // Files scroll under the header except in Columns and the empty states, which keep it over plain background.
    val liftSource = rememberUpdatedState(if ((empty == null || shelves) && layout != ExplorerLayout.COLUMNS) scrollNow.value else null)
    val headerLift = remember { Animatable(0f) }
    LaunchedEffect(headerLift) {
        snapshotFlow { liftSource.value?.canScrollBackward == true }.collectLatest { headerLift.animateTo(if (it) 1f else 0f, tween(180)) }
    }
    val liftNow: () -> Float = remember(headerLift) { { headerLift.value } }
    val floatFill = palette.surface.toComposeColor()
    val floatEdge = palette.border.toComposeColor()
    // The chip row is the files' first row, so it scrolls away with them; under the pinned selection bar it stays empty.
    val chipRow: @Composable (Dp) -> Unit = { height ->
        Box(Modifier.fillMaxWidth().height(height).padding(top = 8.dp)) {
            if (selection.isEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FittedChipRow(Modifier.weight(1f)) { labelled ->
                        if (layout == ExplorerLayout.TIMELINE) {
                            var byOpen by remember { mutableStateOf(false) }
                            Box {
                                ExplorerChip(if (view.timelineByCreated) stringResource(R.string.sort_created) else stringResource(R.string.sort_modified), true, icon = XnotesIcons.sort, trailing = XnotesIcons.chevronDown, labelled = labelled) { byOpen = true }
                                DropdownMenu(expanded = byOpen, onDismissRequest = { byOpen = false }) {
                                    listOf(true to stringResource(R.string.sort_created), false to stringResource(R.string.sort_modified)).forEach { (created, label) ->
                                        ChoiceMenuItem(created == view.timelineByCreated, onClick = { byOpen = false; setView(view.copy(timelineByCreated = created)) }) { fg ->
                                            Text(label, color = fg)
                                        }
                                    }
                                }
                            }
                            ExplorerChip(stringResource(R.string.include_subfolders), includeSubfolders, icon = XnotesIcons.folder) { includeSubfolders = !includeSubfolders }
                        } else if (layout != ExplorerLayout.LIST && layout != ExplorerLayout.COLUMNS && !showRecent) {
                            var sortOpen by remember { mutableStateOf(false) }
                            Box {
                                ExplorerChip(sortLabel(view.sortKey), true, icon = XnotesIcons.sort, trailing = if (view.descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp, labelled = labelled) { sortOpen = true }
                                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                                    val pick: (ExplorerSortKey, Boolean) -> Unit = { k, d -> sortOpen = false; setView(view.copy(sortKey = k, descending = d)) }
                                    ExplorerSortKey.entries.forEach { k -> SortOption(sortLabel(k), k, view.sortKey, view.descending, pick) }
                                }
                            }
                        }
                        if (layout != ExplorerLayout.TIMELINE && layout != ExplorerLayout.COLUMNS && !showRecent) {
                            var groupOpen by remember { mutableStateOf(false) }
                            Box {
                                val grouped = view.groupBy != GroupBy.NONE
                                ExplorerChip(stringResource(view.groupBy.chipRes), grouped, icon = XnotesIcons.layers, trailing = XnotesIcons.chevronDown, labelled = labelled) { groupOpen = true }
                                DropdownMenu(expanded = groupOpen, onDismissRequest = { groupOpen = false }) {
                                    GroupBy.entries.forEach { g ->
                                        ChoiceMenuItem(g == view.groupBy, onClick = { groupOpen = false; setView(view.copy(groupBy = g)) }) { fg ->
                                            Text(stringResource(g.labelRes), color = fg)
                                        }
                                    }
                                }
                            }
                        }
                        var kindOpen by remember { mutableStateOf(false) }
                        Box {
                            val k = kindFilter
                            ExplorerChip(k?.let { words.kinds(it) } ?: stringResource(R.string.all_kinds), k != null, icon = XnotesIcons.filter, trailing = XnotesIcons.chevronDown, labelled = labelled) { kindOpen = true }
                            DropdownMenu(expanded = kindOpen, onDismissRequest = { kindOpen = false }) {
                                (listOf<EntryKind?>(null) + listOf(EntryKind.NOTE, EntryKind.PDF, EntryKind.CANVAS, EntryKind.FILE)).forEach { option ->
                                    ChoiceMenuItem(option == kindFilter, onClick = { kindOpen = false; kindFilter = option }) { fg ->
                                        Text(option?.let { words.kinds(it) } ?: stringResource(R.string.all_kinds), color = fg)
                                    }
                                }
                            }
                        }
                        clipboard?.let { clip ->
                            ExplorerChip(pluralStringResource(R.plurals.paste_items, clip.entries.size, clip.entries.size), true, icon = XnotesIcons.paste) { paste(clip) }
                            ExplorerIcon(XnotesIcons.close, stringResource(R.string.clear_clipboard), palette.textDim.toComposeColor()) { clipboard = null }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val files = arranged.orEmpty().count { !it.isDir }
                    val folders = arranged.orEmpty().count { it.isDir }
                    val label = if (layout == ExplorerLayout.TIMELINE) {
                        val m = shownMonth
                        val n = arranged.orEmpty().count { !it.isDir && view.timelineTime(it) > 0 && YearMonth.from(java.time.Instant.ofEpochMilli(view.timelineTime(it)).atZone(ZoneId.systemDefault())) == m }
                        pluralStringResource(if (view.timelineByCreated) R.plurals.notes_created_in else R.plurals.notes_saved_in, n, n, words.month(m.month))
                    } else {
                        val size = if (layout == ExplorerLayout.LIST) arranged.orEmpty().filterNot { it.isDir }.sumOf { it.size }.takeIf { it > 0 }?.let { formatSize(it) } else null
                        listOfNotNull(countsLabel(words, folders, files).ifEmpty { null }, size).joinToString(" · ")
                    }
                    Text(label, color = palette.textDim.toComposeColor(), fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
                }
            }
        }
    }

    val viewNow = rememberUpdatedState(view)
    val pinchable = layout == ExplorerLayout.GRID || layout == ExplorerLayout.GALLERY || layout == ExplorerLayout.TIMELINE
    val filesNow = rememberUpdatedState(arranged.orEmpty())
    Box(Modifier.fillMaxSize()) {
        Box(
            // The keyboard slides over the files; ending the body at its edge lets the last ones scroll clear of it.
            Modifier.fillMaxSize().imePadding()
                .onPlaced { boxCoords = it }
                // Pinching the tiles steps their size, a step each time the fingers spread or close far enough.
                .then(if (!pinchable) Modifier else Modifier.pointerInput(layout) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.changes.count { it.pressed } >= 2) {
                                zoom *= e.calculateZoom()
                                e.changes.forEach { if (it.positionChanged()) it.consume() }
                                val step = when { zoom > 1.3f -> 1; zoom < 0.77f -> -1; else -> 0 }
                                if (step != 0) {
                                    val v = viewNow.value
                                    val size = TileSize.entries[(v.tileSize.ordinal + step).coerceIn(0, TileSize.entries.lastIndex)]
                                    if (size != v.tileSize) setViewNow.value(v.copy(tileSize = size))
                                    zoom = 1f
                                }
                            }
                        } while (e.changes.any { it.pressed })
                    }
                })
                // Drag-to-move lives on the container (not the tiles) so the gesture keeps running while
                // the body auto-scrolls and the picked-up tile scrolls out of view. We can't reuse the
                // tiles' clickable for the long-press because a child consuming it (consumeUntilUp) would
                // starve this ancestor, so this is a hand-rolled long-press that hit-tests the file tiles
                // and consumes in the Initial pass (ahead of the tiles) to claim the gesture cleanly.
                .pointerInput(root) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Long-press gate: only a held, near-stationary single finger qualifies. A quick
                        // lift (tap), an early move (scroll) or a second finger (pinch) returns from the
                        // timeout normally so the body keeps those; holding past it throws, which is the signal.
                        val longPress = try {
                            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id }
                                    if (c == null || !c.pressed || c.isConsumed) return@withTimeout false
                                    if (e.changes.any { it.id != down.id && it.pressed }) return@withTimeout false
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
                        val hitUri = fileSpots.entries.firstOrNull { it.value.isAttached && it.value.boundsInWindow().contains(winDown) }?.key
                        if (hitUri == null) return@awaitEachGesture
                        if (selection.none { it.documentUri == hitUri }) {
                            // First long-press selects. Consume to the up (Initial pass, ahead of the
                            // tile) so its click can't toggle the selection straight back off.
                            (filesNow.value.firstOrNull { it.documentUri == hitUri } ?: timeline?.files?.firstOrNull { it.documentUri == hitUri })?.let {
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
                        val rect = fileSpots[hitUri]?.takeIf { it.isAttached }?.boundsInWindow()
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
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val carried = items.filter { it.documentUri != target }
                                    editor.moveEntriesInto(root, carried, targetDocId) == carried.size
                                }
                                selection.clear(); refreshKey++
                                if (!ok) opError = context.getString(R.string.err_move_some)
                            }
                        }
                    }
                }
                .then(
                    // In select mode, tapping empty space (not a tile) clears the selection.
                    if (selection.isNotEmpty()) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { selection.clear() } else Modifier,
                ),
        ) {
            val shelfBlock: (@Composable () -> Unit)? = if (!shelves) null else ({
                HomeShelves(
                    body, recents.orEmpty(), editor.sidebarPins, rootName ?: stringResource(R.string.folder),
                    counts = countsLabel(words, arranged.orEmpty().count { it.isDir }, arranged.orEmpty().count { !it.isDir }),
                    onSeeAll = { showRecent = true; selection.clear() },
                    onOpenRecent = { host.onClick(it) },
                    onOpenPin = { openChain(it.uri) },
                )
            })
            val gridTop: (LazyGridScope.() -> Unit)? = shelfBlock?.let { c -> { item(key = "shelves", span = { GridItemSpan(maxLineSpan) }, contentType = "shelves") { c() } } }
            when {
                empty != null && !shelves -> Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(EXPLORER_HEADER))
                    chipRow(48.dp)
                    EmptyPane(empty.text, empty.art, if (!empty.offersCreate) null else { {
                        PrimaryButton(XnotesIcons.edit, stringResource(R.string.new_note_menu)) { onCreateMode(CreateMode.FILE) }
                        PrimaryButton(XnotesIcons.canvas, stringResource(R.string.new_canvas_menu)) { onCreateMode(CreateMode.CANVAS) }
                        PrimaryButton(XnotesIcons.importDoc, stringResource(R.string.import_pdf), onClick = calls.importPdf)
                    } })
                }
                layout == ExplorerLayout.GRID -> GridBody(body, gridState, gridColumns(screenWidthDp, view.tileSize), chipRow, gridTop, Modifier.fillMaxSize())
                layout == ExplorerLayout.GALLERY -> GalleryBody(body, galleryState, galleryColumns(screenWidthDp, view.tileSize), chipRow, gridTop, Modifier.fillMaxSize())
                layout == ExplorerLayout.LIST -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    ListBody(
                        body, listState, wide = maxWidth >= 720.dp,
                        onSort = if (showRecent) null else ({ k -> setView(if (view.sortKey == k) view.copy(descending = !view.descending) else view.copy(sortKey = k, descending = k != ExplorerSortKey.NAME)) }),
                        chips = chipRow,
                        top = shelfBlock?.let { c -> { item(key = "shelves", contentType = "shelves") { c() } } }, modifier = Modifier.fillMaxSize(),
                    )
                }
                layout == ExplorerLayout.TIMELINE -> TimelineBody(body, arranged.orEmpty().filterNot { it.isDir }, shownMonth, { timelineMonth = it }, timelineState, chipRow, Modifier.fillMaxSize())
                // Each column scrolls on its own, so here the chip row stays put under the header.
                else -> Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(EXPLORER_HEADER))
                    chipRow(48.dp)
                    ColumnsBody(
                        body, root,
                        levels = listOf(rootDocId to (rootName ?: stringResource(R.string.folder))) + stack,
                        refreshKey = refreshKey,
                        arrange = columnsArrange,
                        picked = columnsPick,
                        onOpenFolder = { level, e ->
                            if (selection.isNotEmpty()) { toggleSelect(e); return@ColumnsBody }
                            while (stack.size > level) stack.removeAt(stack.lastIndex)
                            stack.add(editor.browseDocId(e.documentUri) to e.name)
                            columnsPick = null
                        },
                        onPickFile = { level, e ->
                            if (selection.isNotEmpty()) { toggleSelect(e); return@ColumnsBody }
                            while (stack.size > level) stack.removeAt(stack.lastIndex)
                            columnsPick = e
                        },
                        preview = { e -> FilePreview(body, e, pathText, previewActions(e), Modifier.fillMaxSize()) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Floating stack of the dragged notes, the primary card centred on the finger and lifted.
            val pos = dragPos
            val bc = boxCoords
            val sz = dragCardSize
            if (pos != null && bc != null && sz != null && sz.width > 0) {
                val origin = bc.positionInWindow()
                val lift = with(density) { 24.dp.toPx() }
                val dx = pos.x - origin.x - sz.width / 2f
                val dy = pos.y - origin.y - sz.height / 2f - lift
                DragPreview(editor, dragItems, sz, Modifier.offset { IntOffset(dx.roundToInt(), dy.roundToInt()) })
            }
        }
        // The header floats over the files; its controls take on their own backing once files slide under them.
        Column(Modifier.fillMaxWidth()) {
            // Header: where we are, then search, the layout switcher, View options and ⋮.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 760.dp
                val headerWidth = maxWidth
                Row(Modifier.fillMaxWidth().height(EXPLORER_HEADER), verticalAlignment = Alignment.CenterVertically) {
                    if (!sidebarOpen) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(40.dp).floatingBacking(liftNow, CircleShape, floatFill, floatEdge)
                                    .clip(CircleShape).clickable(role = Role.Button, onClick = onShowSidebar),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(XnotesIcons.menu, stringResource(R.string.show_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.height(40.dp).floatingBacking(liftNow, CircleShape, floatFill, floatEdge).liftPadding(liftNow, 14.dp), contentAlignment = Alignment.CenterStart) {
                            val filter = colorFilter
                            if (showRecent) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(XnotesIcons.clock, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.recent), color = palette.text.toComposeColor(), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.opened_on_device), color = palette.textDim.toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (!recents.isNullOrEmpty()) {
                                    Text(
                                        stringResource(R.string.clear), color = palette.accent.toComposeColor(), fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(start = 6.dp).clip(MaterialTheme.shapes.extraSmall).clickable { editor.clearRecents() }.padding(horizontal = 6.dp, vertical = 4.dp),
                                    )
                                }
                            } else if (filter != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(codeTint(filter, palette)))
                                Spacer(Modifier.width(8.dp))
                                Text(editor.colorNames[filter] ?: hueName(words, filter), color = palette.text.toComposeColor(), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.in_every_folder), color = palette.textDim.toComposeColor(), fontSize = 14.sp, maxLines = 1)
                                ExplorerIcon(XnotesIcons.close, stringResource(R.string.clear_colour_filter), palette.textDim.toComposeColor()) { colorFilter = null; selection.clear() }
                            } else Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    XnotesIcons.home, stringResource(R.string.top_folder),
                                    tint = (if (stack.isEmpty()) palette.accent else palette.textDim).toComposeColor(),
                                    modifier = Modifier.size(18.dp).clip(MaterialTheme.shapes.extraSmall).clickable { stack.clear(); clearUp() },
                                )
                                Spacer(Modifier.width(8.dp))
                                Crumb(rootName ?: stringResource(R.string.folder), current = stack.isEmpty()) { stack.clear(); clearUp() }
                                stack.forEachIndexed { i, (_, name) ->
                                    Text("/", color = palette.textDim.toComposeColor(), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                    Crumb(name, current = i == stack.lastIndex) {
                                        while (stack.size > i + 1) stack.removeAt(stack.lastIndex)
                                        clearUp()
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    ExplorerSearchField(query, { query = it }, expandedWidth = if (wide) 320.dp else (headerWidth - 144.dp).coerceAtLeast(160.dp), lift = liftNow)
                    if (wide && switcherLayouts.size > 1) {
                        Spacer(Modifier.width(8.dp))
                        LayoutSwitcher(switcherLayouts, layout, liftNow) { setView(view.copy(layout = it)); columnsPick = null }
                    }
                    Spacer(Modifier.width(4.dp))
                    var optionsOpen by remember { mutableStateOf(false) }
                    Box(Modifier.floatingBacking(liftNow, CircleShape, floatFill, floatEdge)) {
                        ExplorerIcon(XnotesIcons.sliders, stringResource(R.string.view_options)) { optionsOpen = true }
                        DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                            ViewOptionsContent(
                                view = view.copy(layout = layout),
                                layouts = ExplorerLayout.entries.filter { !(compactScreen && it == ExplorerLayout.COLUMNS) },
                                everyFolder = !prefs.perFolderViews,
                                hideDotItems = prefs.hideDotItems,
                                // Results shown in the grid for now leave the folder's own layout alone unless another is picked.
                                onChange = { v -> setView(if (v.layout == layout) v.copy(layout = view.layout) else v); if (v.layout != layout) columnsPick = null },
                                onReset = { editor.setView(folderKey, null) },
                                onEveryFolder = { every -> editor.applyHomePreferences(editor.preferences.copy(perFolderViews = !every)) },
                                onHideDotItems = { editor.setHideDotItems(it) },
                                onClose = { optionsOpen = false },
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    var moreOpen by remember { mutableStateOf(false) }
                    Box(Modifier.floatingBacking(liftNow, CircleShape, floatFill, floatEdge)) {
                        ExplorerIcon(XnotesIcons.more, stringResource(R.string.more)) { moreOpen = true }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            NewItemMenuItems({ moreOpen = false }, onCreateMode, calls.importPdf)
                            HorizontalDivider(color = palette.border.toComposeColor())
                            clipboard?.let { clip ->
                                DropdownMenuItem(text = { Text(pluralStringResource(R.plurals.paste_items_here, clip.entries.size, clip.entries.size)) }, onClick = { moreOpen = false; paste(clip) })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.select_all)) }, onClick = { moreOpen = false; selection.clear(); selection.addAll(arranged.orEmpty()) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.change_folder)) }, onClick = { moreOpen = false; calls.pickRoot() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.forget_folder)) }, onClick = { moreOpen = false; editor.clearBrowseRoot() })
                        }
                    }
                }
            }
            // While items are picked, the selection bar stays pinned where the chip row starts.
            if (selection.isNotEmpty()) Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                SelectionBar(selection.size, onClear = { selection.clear() }, onSelectAll = { selection.clear(); selection.addAll(arranged.orEmpty()) }) {
                    val files = selection.filterNot { it.isDir }
                    val fg = palette.selectionForeground.toComposeColor()
                    if (selection.size == 1) ExplorerIcon(XnotesIcons.edit, stringResource(R.string.rename), fg) { renaming = selection.first(); selection.clear() }
                    val pair = files.map { it.documentUri }.distinct().takeIf { it.size == 2 && files.size == selection.size }
                    if (pair != null) ExplorerIcon(XnotesIcons.split, stringResource(R.string.open_side_by_side), fg) { selection.clear(); calls.openSplit(pair[0], pair[1]) }
                    ExplorerIcon(XnotesIcons.moveToFolder, stringResource(R.string.move_to_folder), fg) { moving = selection.toList() }
                    // Only editable notes merge: canvases, reader files and folders have no pages to join.
                    val notes = selection.all { DocumentKind.ofName(it.name) == DocumentKind.NOTE }
                    ExplorerIcon(XnotesIcons.merge, stringResource(R.string.merge_notes), fg, enabled = selection.size >= 2 && notes) { merging = selection.toList() }
                    ExplorerIcon(XnotesIcons.copy, stringResource(R.string.copy), fg) { clipboard = ClipItem(selection.toList(), false); selection.clear() }
                    ExplorerIcon(XnotesIcons.cut, stringResource(R.string.cut), fg) { clipboard = ClipItem(selection.toList(), true); selection.clear() }
                    var colorsOpen by remember { mutableStateOf(false) }
                    Box {
                        ExplorerIcon(XnotesIcons.palette, stringResource(R.string.colour_code), fg) { colorsOpen = true }
                        DropdownMenu(expanded = colorsOpen, onDismissRequest = { colorsOpen = false }) {
                            ColorCodeMenuContent { c -> colorsOpen = false; recolor(selection.toList(), c); selection.clear() }
                        }
                    }
                    ExplorerIcon(XnotesIcons.share, stringResource(R.string.share), fg, enabled = files.size == selection.size && files.none { ReaderKind.isReadable(it.name) }) {
                        val uris = files.map { it.documentUri }
                        selection.clear()
                        if (uris.size == 1) calls.shareFile(uris[0]) else calls.shareFiles(uris)
                    }
                    SelectionDivider()
                    ExplorerIcon(XnotesIcons.trash, if (prefs.trashDays == 0) stringResource(R.string.delete) else stringResource(R.string.move_to_trash), palette.danger.toComposeColor()) { remove(selection.toList()) }
                }
            }
        }
    }

    // A multi-file pick gets no name prompt: it imports straight into the folder on screen, one file
    // at a time, taking each note's name from its source. This runs here because the explorer is
    // where the target folder is known. Cancelling keeps whatever already landed.
    val pendingImports = editor.pendingImports
    LaunchedEffect(pendingImports) {
        if (pendingImports.isNotEmpty()) {
            val n = editor.commitImportsAsync(root, currentDocId)
            refreshKey++
            if (n > 0) editor.message = context.resources.getQuantityString(R.plurals.imported_pdfs, n, n)
        }
    }

    val pendingImport = editor.pendingImport
    // Clear any stale error when a fresh name dialog opens for a new operation.
    LaunchedEffect(createMode, pendingImport) { fieldError = null }
    // Name entry for a new note, new folder, or a pending PDF import. Hidden while an import is
    // actually being written, so only the "Importing…" dialog shows.
    if (createMode == CreateMode.FILE && pendingImport == null && !editor.importing) {
        NewNoteDialog(
            editor = editor,
            initial = nextUntitled(editor, editor.cachedChildren(root, currentDocId)),
            error = fieldError,
            onConfirm = { n, style ->
                scope.launch {
                    val uri = withContext(Dispatchers.IO) { editor.createBlankNoteFile(root, currentDocId, n, style) }
                    if (uri != null) { onCreateMode(CreateMode.NONE); refreshKey++; calls.openFile(uri) } else fieldError = context.getString(R.string.err_create_note)
                }
            },
            onDismiss = { fieldError = null; onCreateMode(CreateMode.NONE) },
        )
    } else if ((createMode != CreateMode.NONE || pendingImport != null) && !editor.importing) {
        val isFolder = pendingImport == null && createMode == CreateMode.FOLDER
        val default = when {
            pendingImport != null -> pendingImport.defaultName // import names default to the source file
            createMode == CreateMode.FILE || createMode == CreateMode.CANVAS ->
                nextUntitled(editor, editor.cachedChildren(root, currentDocId))
            else -> "" // new folder
        }
        NameDialog(
            title = when {
                pendingImport != null -> pendingImport.title ?: stringResource(R.string.import_title)
                isFolder -> stringResource(R.string.new_folder)
                createMode == CreateMode.CANVAS -> stringResource(R.string.new_canvas)
                else -> stringResource(R.string.new_note)
            },
            initial = default,
            confirmLabel = if (pendingImport != null) stringResource(R.string.save) else stringResource(R.string.create),
            placeholder = if (isFolder) stringResource(R.string.folder_name) else null,
            allowEmpty = !isFolder, // a folder needs a name; a blank note name becomes "untitled_N"
            error = fieldError,
            onConfirm = { n ->
                when {
                    pendingImport != null -> scope.launch {
                        // Land the import in the current folder; it opens only when the user taps it.
                        // commitImportAsync drives the "Importing…" dialog and runs the copy off-thread.
                        val uri = editor.commitImportAsync(root, pendingImport.parentDocId ?: currentDocId, n)
                        when {
                            uri != null -> refreshKey++
                            editor.pendingImport != null -> fieldError = context.getString(R.string.err_save_that_note) // genuine failure; keep the prompt
                            // else: cancelled — the prompt already dismissed (pendingImport cleared)
                        }
                    }
                    isFolder -> scope.launch {
                        val ok = withContext(Dispatchers.IO) { editor.createFolder(root, currentDocId, n) }
                        if (ok) { onCreateMode(CreateMode.NONE); refreshKey++ } else fieldError = context.getString(R.string.err_create_folder)
                    }
                    createMode == CreateMode.CANVAS -> scope.launch {
                        val uri = withContext(Dispatchers.IO) { editor.createBlankCanvasFile(root, currentDocId, n) }
                        if (uri != null) { onCreateMode(CreateMode.NONE); refreshKey++; calls.openFile(uri) } else fieldError = context.getString(R.string.err_create_canvas)
                    }
                }
            },
            onDismiss = { fieldError = null; if (pendingImport != null) editor.cancelImport() else onCreateMode(CreateMode.NONE) },
        )
    }

    namingColor?.let { c -> ColorNameDialog(editor, c) { namingColor = null } }
    if (converting != null) com.xnotes.SpinnerDialog(stringResource(R.string.converting_to_note)) { converting?.cancel(); converting = null }

    renaming?.let { entry ->
        NameDialog(
            title = if (entry.isDir) stringResource(R.string.rename_folder) else stringResource(R.string.rename_note),
            initial = entryLabel(entry),
            confirmLabel = stringResource(R.string.rename),
            allowEmpty = false,
            onConfirm = { raw ->
                val kind = DocumentKind.ofName(entry.name)
                val newName = when {
                    entry.isDir -> raw
                    kind != null -> DocumentKind.withSuffix(DocumentKind.stripSuffix(raw), kind)
                    // A reader file that lost its extension would drop out of the explorer, so keep the old one.
                    !ReaderKind.isReadable(raw) -> "$raw.${entry.name.substringAfterLast('.')}"
                    else -> raw
                }
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

    merging?.let { items ->
        MergeDialog(
            initial = context.getString(R.string.merged_name, DocumentKind.stripSuffix(items.first().name)),
            onConfirm = { name, trashOriginals ->
                merging = null
                selection.clear()
                scope.launch {
                    val uri = editor.mergeNotes(items.map { it.documentUri }, root, currentDocId, name)
                    if (uri == null) opError = context.getString(R.string.err_merge_notes)
                    else if (trashOriginals) remove(items)
                    refreshKey++
                }
            },
            onDismiss = { merging = null },
        )
    }

    moving?.let { items ->
        val movingFolders = remember(items) { items.filter { it.isDir }.map { editor.browseDocId(it.documentUri) } }
        FolderPickerDialog(
            editor, root, rootName ?: stringResource(R.string.folder),
            title = if (items.size == 1) stringResource(R.string.move_one_to, entryLabel(items.first())) else pluralStringResource(R.plurals.move_items_to, items.size, items.size),
            confirmLabel = stringResource(R.string.move_here),
            start = stack.toList(),
            blocked = { id -> movingFolders.any { com.xnotes.core.util.DocKeys.within(id, it) } },
            onPick = { target ->
                moving = null
                scope.launch {
                    val moved = withContext(Dispatchers.IO) { editor.moveEntriesInto(root, items, target) }
                    selection.clear(); refreshKey++
                    opError = if (moved < items.size) context.getString(R.string.err_move_some) else null
                }
            },
            onDismiss = { moving = null },
        )
    }

    previewing?.let { e -> FilePreviewDialog(body, e, pathText.takeIf { !flat }, previewActions(e)) { previewing = null } }

    pendingDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                Text(
                    if (targets.size == 1) stringResource(R.string.delete_one_confirm, entryLabel(targets.first()))
                    else pluralStringResource(R.plurals.delete_items_confirm, targets.size, targets.size),
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
                        if (!allOk) opError = context.getString(R.string.err_delete_some)
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Height of the search pill (also its rounded-end radius via CircleShape), and the idle icon's width. */
private val SEARCH_HEIGHT = 40.dp

/**
 * The explorer's recursive name filter. Idle it's a bare magnifier like the header's other icons; tapped,
 * it eases out into a rounded pill of [expandedWidth] holding the field and stops (no overshoot). Tapping
 * outside drops focus, which clears the query and lets it ease back. A trailing ✕ does the same by hand.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExplorerSearchField(query: String, onQueryChange: (String) -> Unit, expandedWidth: Dp, lift: () -> Float = { 0f }) {
    val palette = LocalPalette.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // `active` bootstraps the field into composition on tap so its FocusRequester can grab focus;
    // `focused` is the real focus state. Either keeps the pill expanded.
    var active by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val expanded = active || focused
    // 0 is the bare icon, 1 the open pill; eased so it decelerates into place with no rebound.
    val open by animateFloatAsState(
        if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "searchOpen",
    )
    val surface = palette.surface.toComposeColor()
    val border = palette.border.toComposeColor()
    LaunchedEffect(active) { if (active) runCatching { focus.requestFocus() } }
    // Back only hides the keyboard and leaves focus put, so a keyboard that was up going away ends the search too.
    val imeUp = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp, focused) {
        when {
            !focused -> imeSeen = false
            imeUp -> imeSeen = true
            imeSeen -> focusManager.clearFocus()
        }
    }
    Row(
        Modifier
            .width(lerp(SEARCH_HEIGHT, expandedWidth, open))
            .height(SEARCH_HEIGHT)
            // The pill's backing shows once it opens, or while files scroll under it; the shadow only for the latter.
            .floatingBacking({ maxOf(open, lift()) }, CircleShape, surface, border, shadow = lift)
            .clip(CircleShape)
            .clickable(enabled = !expanded, role = Role.Button) { active = true }
            .padding(horizontal = lerp(10.dp, 12.dp, open)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            XnotesIcons.search, stringResource(R.string.search_notes),
            tint = lerp(palette.text.toComposeColor(), palette.textDim.toComposeColor(), open),
            modifier = Modifier.size(lerp(20.dp, 18.dp, open)),
        )
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
                    XnotesIcons.close, stringResource(R.string.clear_search),
                    tint = palette.textDim.toComposeColor(),
                    modifier = Modifier.size(16.dp).clip(CircleShape)
                        .clickable { onQueryChange(""); focusManager.clearFocus() },
                )
            }
        }
    }
}

@Composable
internal fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        text,
        color = (if (current) palette.text else palette.textDim).toComposeColor(),
        fontSize = 14.sp,
        maxLines = 1,
        modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 2.dp),
    )
}


private fun entryLabel(entry: BrowseEntry): String =
    if (entry.isDir) entry.name else com.xnotes.core.util.DocumentKind.stripSuffix(entry.name)

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
internal fun EntryMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onCut: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onShare: (() -> Unit)? = null,
    onSaveCopy: (() -> Unit)? = null,
    onExportPdf: (() -> Unit)? = null,
    onColor: ((Rgba?) -> Unit)? = null,
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onNameColor: (() -> Unit)? = null,
    onMoveTo: (() -> Unit)? = null,
    onPreview: (() -> Unit)? = null,
    onConvertToNote: (() -> Unit)? = null,
    onImportInto: (() -> Unit)? = null,
    deleteLabel: String = stringResource(R.string.delete),
) {
    val palette = LocalPalette.current
    // "Colour code" swaps the menu's contents for the swatch picker until a colour (or None) is chosen;
    // closing the menu resets it so it always reopens on the main list.
    var showColors by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) showColors = false }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showColors) {
            ColorCodeMenuContent { c -> onDismiss(); onColor?.invoke(c) }
        } else {
            if (onPreview != null) DropdownMenuItem(text = { Text(stringResource(R.string.preview)) }, onClick = { onDismiss(); onPreview() })
            if (onConvertToNote != null) DropdownMenuItem(text = { Text(stringResource(R.string.convert_to_note)) }, onClick = { onDismiss(); onConvertToNote() })
            if (onImportInto != null) DropdownMenuItem(text = { Text(stringResource(R.string.import_into_note)) }, onClick = { onDismiss(); onImportInto() })
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { onDismiss(); onRename?.invoke() })
            if (onMoveTo != null) DropdownMenuItem(text = { Text(stringResource(R.string.move_to_folder_ellipsis)) }, onClick = { onDismiss(); onMoveTo() })
            DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = { onDismiss(); onCopy?.invoke() })
            DropdownMenuItem(text = { Text(stringResource(R.string.cut)) }, onClick = { onDismiss(); onCut?.invoke() })
            if (onColor != null) DropdownMenuItem(text = { Text(stringResource(R.string.colour_code)) }, onClick = { showColors = true })
            if (onNameColor != null) DropdownMenuItem(text = { Text(stringResource(R.string.name_colour_ellipsis)) }, onClick = { onDismiss(); onNameColor() })
            if (onTogglePin != null) {
                DropdownMenuItem(text = { Text(if (pinned) stringResource(R.string.unpin_from_sidebar) else stringResource(R.string.pin_to_sidebar)) }, onClick = { onDismiss(); onTogglePin() })
            }
            DropdownMenuItem(text = { Text(deleteLabel) }, onClick = { onDismiss(); onDelete?.invoke() })
            if (onShare != null) {
                HorizontalDivider(color = palette.border.toComposeColor())
                DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { onDismiss(); onShare() })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_copy_ellipsis)) }, onClick = { onDismiss(); onSaveCopy?.invoke() })
                DropdownMenuItem(text = { Text(stringResource(R.string.export_pdf)) }, onClick = { onDismiss(); onExportPdf?.invoke() })
            }
        }
    }
}

/** Slanted parallel lines that shade a colour-coded card without hiding what's on it. */
internal fun Modifier.colorHatch(color: Color): Modifier = drawBehind {
    val step = 10.dp.toPx()
    val stroke = 1.5.dp.toPx()
    val faint = color.copy(alpha = 0.28f)
    var x = -size.height
    while (x < size.width) {
        drawLine(faint, Offset(x, size.height), Offset(x + size.height, 0f), stroke)
        x += step
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
    val shape = MaterialTheme.shapes.medium
    Column(modifier.clip(shape).background(palette.bg.toComposeColor()).border(1.dp, palette.accent.toComposeColor(), shape)) {
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

// --- shared bits ---

/** The new-note prompt: its name plus the toolbar's page-style controls (template, page colour), preset from the new-note default. */
@Composable
private fun NewNoteDialog(
    editor: Editor,
    initial: String,
    error: String?,
    onConfirm: (String, com.xnotes.core.model.PageStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    var style by remember { mutableStateOf(editor.newNoteStyle) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val confirm = { onConfirm(text.text.trim(), style) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_note)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
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
                PageStyleControls(style, inheritFrom = null) { style = it }
            }
        },
        confirmButton = { TextButton(onClick = { confirm() }) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = palette.menuBg.toComposeColor(),
    )
}

/**
 * A small modal that asks for a single name, used for new notes, new folders, renames, and
 * naming a pending import. Pre-fills [initial] (fully selected so typing replaces it), confirms
 * on the keyboard's Done action or hardware Enter, and dismisses on Cancel, the scrim, or Esc.
 * When [allowEmpty] is false the confirm button stays disabled until something is typed; a
 * non-null [error] shows under the field and keeps the dialog open after a failed operation.
 * A non-null [onRemove] adds a Delete button before Cancel.
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
    onRemove: (() -> Unit)? = null,
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
                supportingText = error?.let { { Text(it) } },
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
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRemove != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private const val PRESS_FILL_MS = 120L

/** Line glyph above a label in a bordered box; fills with the selection container while pressed. Matches the About pane buttons. */
@Composable
private fun PrimaryButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    // Keep the pressed fill visible for a minimum time so even a millisecond tap registers.
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
    val onAccent = palette.selectionForeground.toComposeColor()
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier
            .clip(shape)
            .background(if (pressed) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .border(1.dp, if (pressed) accent else palette.border.toComposeColor(), shape)
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

/** What an empty explorer body says, the picture above it, and whether it offers to make something. */
private class EmptyState(val text: String, val art: EmptyArt? = null, val offersCreate: Boolean = false)

/** An empty body: a picture, what is (not) here, and ways to fill it; scrolls when the window is short. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyPane(text: String, art: EmptyArt? = null, actions: (@Composable FlowRowScope.() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (art != null) {
                EmptyIllustration(art)
                Spacer(Modifier.height(20.dp))
            }
            Text(text, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
            if (actions != null) {
                Spacer(Modifier.height(20.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = actions,
                )
            }
        }
    }
}

/** A "Sync now" sidebar action, shown only when a Filen account is signed in; tap syncs, long-press opens sync settings. */
@Composable
private fun FilenSyncNowCommand(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    if (!com.xnotes.sync.filen.FilenSyncManager.isConfigured(ctx)) return
    val status by com.xnotes.sync.filen.FilenSyncManager.status.collectAsState()
    val scope = rememberCoroutineScope()
    Command(Icons.Filled.Sync, if (status.running) "Syncing..." else "Sync now", onLongClick = onOpenSettings) {
        if (!status.running) scope.launch { com.xnotes.sync.filen.FilenSyncManager.syncNow(ctx) }
    }
}

/** [FilenSyncNowCommand] for the collapsed rail: tap syncs, hold opens the sync settings. */
@Composable
private fun FilenSyncRailItem(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    if (!com.xnotes.sync.filen.FilenSyncManager.isConfigured(ctx)) return
    val status by com.xnotes.sync.filen.FilenSyncManager.status.collectAsState()
    val scope = rememberCoroutineScope()
    RailItem(Icons.Filled.Sync, if (status.running) "Syncing..." else "Sync now", selected = status.running, onLongClick = onOpenSettings) {
        if (!status.running) scope.launch { com.xnotes.sync.filen.FilenSyncManager.syncNow(ctx) }
    }
}

/**
 * A quiet line at the foot of the sidebar: when Filen sync is set up, how long ago the last sync
 * ran and how many files are tracked. Nothing shows until the account is configured.
 */
@Composable
private fun FilenSidebarStatus() {
    val palette = LocalPalette.current
    val ctx = LocalContext.current
    if (!com.xnotes.sync.filen.FilenSyncManager.isConfigured(ctx)) return
    LaunchedEffect(Unit) { com.xnotes.sync.filen.FilenSyncManager.primeStatus(ctx) }
    val status by com.xnotes.sync.filen.FilenSyncManager.status.collectAsState()
    val line = when {
        status.running -> "Syncing…"
        status.lastSyncMs == 0L -> "Filen: not synced yet"
        else -> {
            val ago = android.text.format.DateUtils.getRelativeTimeSpanString(
                status.lastSyncMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            )
            val files = if (status.fileCount == 1) "1 file" else "${status.fileCount} files"
            "Last synced $ago · $files"
        }
    }
    Text(
        line,
        color = palette.textDim.toComposeColor(),
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 8.dp),
    )
}

/**
 * A dismissible heads-up shown above the explorer when the last sync found a note that was changed
 * on two devices (no merge is done — the other device's version is kept beside yours). Lists the
 * affected notes so they can be reviewed; Dismiss clears it.
 */
@Composable
private fun SyncConflictBanner() {
    val palette = LocalPalette.current
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { com.xnotes.sync.filen.FilenSyncManager.primeStatus(ctx) }
    val status by com.xnotes.sync.filen.FilenSyncManager.status.collectAsState()
    val conflicts = status.conflicts
    if (conflicts.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(palette.accentAlpha(28).toComposeColor())
            .border(1.dp, palette.accentAlpha(90).toComposeColor(), MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(XnotesIcons.info, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (conflicts.size == 1) "A note was changed on another device too" else "${conflicts.size} notes were changed on another device too",
                color = palette.text.toComposeColor(), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { com.xnotes.sync.filen.FilenSyncManager.clearConflicts(ctx) }) { Text("Dismiss") }
        }
        Spacer(Modifier.height(4.dp))
        conflicts.take(6).forEach { line ->
            Text("- $line", color = palette.textDim.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(start = 26.dp, top = 2.dp))
        }
        if (conflicts.size > 6) {
            Text("and ${conflicts.size - 6} more", color = palette.textDim.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(start = 26.dp, top = 2.dp))
        }
    }
}

/** Names the note that selected notes merge into, and whether the originals go to trash after. */
@Composable
private fun MergeDialog(initial: String, onConfirm: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var trash by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.merge_notes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { trash = !trash }) {
                    androidx.compose.material3.Checkbox(checked = trash, onCheckedChange = { trash = it })
                    Text(stringResource(R.string.merge_trash_originals))
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim(), trash) }) { Text(stringResource(R.string.merge)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
