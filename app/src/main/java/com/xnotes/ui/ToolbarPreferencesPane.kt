package com.xnotes.ui

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.Preferences
import com.xnotes.settings.ToolbarSize
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay

private enum class ToolbarPropertyTab { SETTINGS, ELEMENTS }

/** Dedicated toolbar editor, independent of the fork's general preferences. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolbarPreferencesPane(editor: Editor, onBack: () -> Unit) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    BackHandler(onBack = onBack)
    val palette = LocalPalette.current
    val focusManager = LocalFocusManager.current
    val screenConfiguration = LocalConfiguration.current
    var prefs by remember { mutableStateOf(editor.preferences) }
    fun update(p: Preferences) {
        prefs = p
        editor.applyPreferences(p)
    }
    // Home, explorer and export settings leave the open note alone, so they skip its canvas refresh.
    fun updateHome(p: Preferences) {
        prefs = p
        editor.applyHomePreferences(p)
    }
    // Follow out-of-pane preference changes too (the .scm import round-trips a picker).
    LaunchedEffect(editor.prefsVersion) { prefs = editor.preferences }

    val scrollState = rememberScrollState()
    val toolbarPropertiesScroll = rememberScrollState()
    val toolbarElementsScroll = rememberScrollState()
    var previewExpanded by remember { mutableStateOf(false) }
    var toolbarPropertyTab by remember { mutableStateOf(ToolbarPropertyTab.SETTINGS) }
    var colorControlsExpanded by remember { mutableStateOf(false) }
    var dockError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toolbarPropertyTab) { toolbarPropertiesScroll.scrollTo(0) }
    // Which bar the toolbar section is arranging. The two are separate layouts, so the drag state
    // below always belongs to whichever is on screen.
    var canvasTab by remember { mutableStateOf(false) }
    var selectedBarId by remember { mutableStateOf<String?>(null) }
    var barMenuExpanded by remember { mutableStateOf(false) }
    var toolbarActionsExpanded by remember { mutableStateOf(false) }
    var transferDraft by remember { mutableStateOf<com.xnotes.core.tools.ToolbarTransferDraft?>(null) }
    val workspaceLibrary = remember { ToolbarWorkspaceLibraryState(editor.toolbarWorkspacePresets) }
    fun activeWorkspacePreset() = com.xnotes.settings.ToolbarWorkspacePreset(
        id = "current-export",
        name = toolbarStrings.getString(R.string.toolbar_config_current_workspace),
        workspaces = editor.toolbarWorkspaces,
        look = prefs.toolbarLook,
        colors = editor.toolbarColors,
        colorCount = editor.toolbarColorCount,
    )
    val toolbarInteraction = remember { ToolbarEditorInteractionState() }
    var selectedEntry by toolbarInteraction.selectedEntry
    fun editedContext(): ToolbarContext = if (canvasTab) ToolbarContext.CANVAS else ToolbarContext.NOTE
    fun editedWorkspace(): ToolbarWorkspace =
        if (canvasTab) editor.toolbarWorkspaces.canvas else editor.toolbarWorkspaces.note
    fun selectedBar(): ToolbarBar = editedWorkspace().bars.firstOrNull { it.id == selectedBarId }
        ?: editedWorkspace().bars.first()
    val layout = selectedBar().layout
    // Toolbar drag state, hoisted here so the floating "ghost" chip can be drawn at the pane
    // root (above and outside the scroll, so it is never clipped). Bounds are plain maps read
    // only at drag time; observable state is just the dragged item, finger, and drop slot.
    val dragItem by toolbarInteraction.dragItem
    val dragFinger by toolbarInteraction.dragFinger
    val dragGrab by toolbarInteraction.dragGrab
    val chipBounds = toolbarInteraction.chipBounds
    val sectionBounds = toolbarInteraction.sectionBounds
    // Section-drag state (the whole card, by its grip handle); shares the finger/grab with the
    // chip drag since only one gesture runs at a time. The drop slot is an insertion index.
    val dragSection by toolbarInteraction.dragSection
    var paneTopLeft by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    fun editedLayout(): ToolbarLayout = selectedBar().layout
    fun applyEdited(next: ToolbarLayout) {
        val workspace = editedWorkspace()
        val bar = selectedBar()
        if (bar.id == workspace.bars.first().id) {
            if (canvasTab) editor.applyCanvasToolbarLayout(next) else editor.applyToolbarLayout(next)
        } else {
            val profiles = editor.toolbarWorkspaces
            val changed = workspace.replaceBar(bar.copy(layout = next))
            editor.applyToolbarWorkspaces(profiles.updateWorkspace(editedContext()) { changed })
        }
    }
    fun updateSelectedBar(transform: (ToolbarBar) -> ToolbarBar) {
        val profiles = editor.toolbarWorkspaces
        val changed = editedWorkspace().replaceBar(transform(selectedBar()))
        editor.applyToolbarWorkspaces(profiles.updateWorkspace(editedContext()) { changed })
    }
    fun configureDock(next: ToolbarGeometry, nextLook: com.xnotes.settings.ToolbarLook = prefs.toolbarLook) {
        val bar = selectedBar()
        val candidate = editedWorkspace().replaceBar(bar.copy(geometry = next.constrained()))
        if (!toolbarPlacementFits(
                candidate, nextLook, screenConfiguration.screenWidthDp.dp,
                screenConfiguration.screenHeightDp.dp, editor.toolbarColorCount,
                dockWorkspace = candidate.onlyItems(if (canvasTab) ToolbarLayout.CANVAS_ITEMS else ToolbarLayout.NOTE_ITEMS),
            )) {
            dockError = toolbarStrings.getString(R.string.toolbar_config_no_space_available_resize_the_bar_change_its_edge_or_choose_another_lane)
            return
        }
        dockError = null
        updateSelectedBar { it.copy(geometry = next.constrained()) }
        if (nextLook != prefs.toolbarLook) updateHome(prefs.copy(toolbarLook = nextLook))
    }

    fun beginCopyToolbar() {
        val profiles = editor.toolbarWorkspaces
        if (canvasTab && profiles.shared) {
            dockError = toolbarStrings.getString(R.string.toolbar_config_disable_the_shared_profile_before_copying_an_xcanvas_bar)
            return
        }
        val targetCanvas = !canvasTab
        val destination = if (targetCanvas) profiles.canvas else profiles.note
        val target = if (targetCanvas) com.xnotes.core.tools.ToolbarContext.CANVAS
            else com.xnotes.core.tools.ToolbarContext.NOTE
        val source = selectedBar()
        val initial = com.xnotes.core.tools.prepareToolbarTransfer(source, destination, target)
        if (initial.retainedCount == 0) {
            dockError = toolbarStrings.getString(R.string.toolbar_config_this_bar_has_no_tools_compatible_with_the_destination_editor)
            return
        }
        val draft = planToolbarCopy(source,
            editedWorkspace().bars.first().id == source.id, destination, target,
            prefs.toolbarLook, screenConfiguration.screenWidthDp.dp,
            screenConfiguration.screenHeightDp.dp, editor.toolbarColorCount)
        if (draft == null) {
            dockError = toolbarStrings.getString(R.string.toolbar_config_there_is_no_room_for_this_bar_at_the_destination_resize_it_before_copying)
            return
        }
        dockError = null
        transferDraft = draft
    }

    fun retarget() {
        toolbarInteraction.retargetItem(editedLayout())
    }
    fun retargetSection() {
        toolbarInteraction.retargetSection(editedLayout())
    }
    fun showTab(canvas: Boolean) {
        if (canvasTab == canvas) return
        canvasTab = canvas
        selectedBarId = null
        toolbarInteraction.resetForSelectionChange()
    }
    fun showBar(id: String) {
        if (selectedBarId == id) return
        selectedBarId = id
        toolbarInteraction.resetForSelectionChange()
    }

    transferDraft?.let { draft ->
        val profiles = editor.toolbarWorkspaces
        val targetCanvas = !canvasTab
        val destination = if (targetCanvas) profiles.canvas else profiles.note
        ToolbarCopyDialog(
            targetLabel = if (targetCanvas) "XCanvas" else "XNotes",
            candidate = destination.addBar(draft.bar),
            draft = draft,
            look = prefs.toolbarLook,
            colors = editor.toolbarColors.take(editor.toolbarColorCount),
            activeColorIndex = editor.activeColorIndex,
            sharedCanvasWillDisable = targetCanvas && profiles.shared,
            onCopy = {
                val changed = destination.addBar(draft.bar)
                editor.applyToolbarWorkspaces(
                    if (targetCanvas) profiles.copy(canvas = changed, shared = false)
                    else profiles.copy(note = changed),
                )
                transferDraft = null
                showTab(targetCanvas)
                showBar(draft.bar.id)
            },
            onDismiss = { transferDraft = null },
        )
    }
    ToolbarWorkspaceLibrary(
        state = workspaceLibrary,
        canvasInitially = canvasTab,
        currentPreset = { activeWorkspacePreset() },
        getPresets = { editor.toolbarWorkspacePresets },
        onSave = { editor.saveToolbarWorkspacePreset(it) },
        onDelete = { editor.deleteToolbarWorkspacePreset(it) },
        onImport = { editor.importToolbarWorkspacePresets(it) },
        onApply = { preset, targetContext ->
            editor.applyToolbarWorkspacePreset(preset, targetContext)
            prefs = editor.preferences
            selectedBarId = null
            selectedEntry = null
        },
    )

    // A tap on empty space dismisses the template field's focus; children consume their own taps.
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .onGloballyPositioned { paneTopLeft = it.boundsInRoot().topLeft },
    ) {
    Column(Modifier.fillMaxSize()) {
        // Leading button inline with the title, at a constant row height so toggling the sidebar never
        // shifts the settings below: a back arrow to Home on compact, else a hamburger when hidden.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {

                Tooltip(stringResource(R.string.preferences)) {
                    IconButton(onClick = onBack) {
                        Icon(XnotesIcons.prev, stringResource(R.string.preferences), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pref_toolbar), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)

        }
        Spacer(Modifier.height(12.dp))
        Column(
            // Ending at the keyboard's edge scrolls a focused field up out from under it.
            Modifier.fillMaxSize().imePadding().verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {


            val barLabel = stringResource(R.string.pref_toolbar)
            val workspace = editedWorkspace()
            val currentBar = selectedBar()
            val toolbarActions: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = {
                    toolbarActionsExpanded = false
                    val planned = planToolbarAdd(
                        editedWorkspace(), editedContext(), barLabel, prefs.toolbarLook,
                        screenConfiguration.screenWidthDp.dp, screenConfiguration.screenHeightDp.dp,
                        editor.toolbarColorCount,
                    )
                    if (planned == null) {
                        dockError = toolbarStrings.getString(R.string.toolbar_config_the_current_lanes_are_full_free_up_space_before_adding_a_bar)
                    } else {
                        dockError = null
                        val profiles = editor.toolbarWorkspaces
                        editor.applyToolbarWorkspaces(
                            profiles.updateWorkspace(editedContext()) { planned.workspace },
                        )
                        showBar(planned.selectedBarId)
                    }
                }) { Text(stringResource(R.string.add) + " " + barLabel) }
                TextButton(onClick = {
                    toolbarActionsExpanded = false
                    val planned = planToolbarDuplicate(
                        editedWorkspace(), selectedBar().id, barLabel, prefs.toolbarLook,
                        screenConfiguration.screenWidthDp.dp, screenConfiguration.screenHeightDp.dp,
                        editor.toolbarColorCount,
                    )
                    if (planned == null) {
                        dockError = toolbarStrings.getString(R.string.toolbar_config_the_copy_does_not_fit_in_the_available_lanes_adjust_the_size_or_arrangement)
                    } else {
                        dockError = null
                        val profiles = editor.toolbarWorkspaces
                        editor.applyToolbarWorkspaces(
                            profiles.updateWorkspace(editedContext()) { planned.workspace },
                        )
                        showBar(planned.selectedBarId)
                    }
                }) { Text(stringResource(R.string.duplicate)) }
                if (workspace.bars.size > 1 && currentBar.id != workspace.bars.first().id) {
                    TextButton(onClick = {
                        toolbarActionsExpanded = false
                        val old = editedWorkspace()
                        val changed = old.removeBar(selectedBar().id)
                        val profiles = editor.toolbarWorkspaces
                        editor.applyToolbarWorkspaces(
                            profiles.updateWorkspace(editedContext()) { changed },
                        )
                        showBar(changed.bars.first().id)
                    }) { Text(stringResource(R.string.remove)) }
                }
            }
            TextButton(onClick = { toolbarActionsExpanded = false; beginCopyToolbar() }) {
                Text(toolbarStrings.getString(R.string.toolbar_config_copy_to, if (canvasTab) "XNotes" else "XCanvas"))
            }
            }
            run {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chip("xnote", selected = !canvasTab) { showTab(false) }
                    Chip("xcanvas", selected = canvasTab) { showTab(true) }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { barMenuExpanded = true },
                            modifier = Modifier.widthIn(max = 120.dp)) {
                            Text("${currentBar.name} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(
                            expanded = barMenuExpanded,
                            onDismissRequest = { barMenuExpanded = false },
                        ) {
                            workspace.bars.forEachIndexed { index, bar ->
                                DropdownMenuItem(
                                    text = { Text("${index + 1}. ${bar.name}") },
                                    onClick = {
                                        showBar(bar.id)
                                        barMenuExpanded = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reset)) },
                                onClick = {
                                    val candidate = planToolbarReset(editedWorkspace(), selectedBar().id,
                                        editedContext(), prefs.toolbarLook,
                                        screenConfiguration.screenWidthDp.dp, screenConfiguration.screenHeightDp.dp,
                                        editor.toolbarColorCount)
                                    if (candidate == null) {
                                        dockError = toolbarStrings.getString(R.string.toolbar_config_the_default_does_not_fit_alongside_the_other_bars_resize_or_move_them_befor)
                                    } else {
                                        dockError = null
                                        applyEdited(candidate.bars.first { it.id == selectedBar().id }.layout)
                                        toolbarInteraction.resetForSelectionChange()
                                    }
                                    barMenuExpanded = false
                                },
                            )
                        }
                    }
                    TextButton(onClick = {
                        workspaceLibrary.show(editor.toolbarWorkspacePresets)
                    }) { Text(toolbarStrings.getString(R.string.toolbar_config_workspaces) + " ▾", maxLines = 1) }
                    Box {
                        IconButton(onClick = { toolbarActionsExpanded = true }) {
                            Icon(XnotesIcons.more, toolbarStrings.getString(R.string.toolbar_config_create_duplicate_or_copy_a_bar))
                        }
                        DropdownMenu(
                            expanded = toolbarActionsExpanded,
                            onDismissRequest = { toolbarActionsExpanded = false },
                        ) {
                            Column(Modifier.widthIn(min = 290.dp).padding(8.dp)) {
                                toolbarActions()
                            }
                        }
                    }
                }
                dockError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            val barSize = if (currentBar.id == workspace.bars.first().id) prefs.toolbarLook.size
                else when (currentBar.geometry.scale) {
                    com.xnotes.core.tools.ToolbarScale.COMPACT -> ToolbarSize.COMPACT
                    com.xnotes.core.tools.ToolbarScale.REGULAR -> ToolbarSize.REGULAR
                    com.xnotes.core.tools.ToolbarScale.COMFORTABLE -> ToolbarSize.COMFORTABLE
                }
            val defaultEntrySize = resolvedBarMetrics(barSize, currentBar.geometry).button.value.toInt()
            val preview: @Composable () -> Unit = {
                ToolbarWorkspacePreview(
                    workspace = workspace.onlyItems(if (canvasTab) ToolbarLayout.CANVAS_ITEMS else ToolbarLayout.NOTE_ITEMS),
                    look = prefs.toolbarLook,
                    selectedId = currentBar.id,
                    selectedEntry = selectedEntry,
                    colors = editor.toolbarColors.take(editor.toolbarColorCount),
                    activeColorIndex = editor.activeColorIndex,
                    pageNumber = editor.pageIndex + 1,
                    pageCount = editor.pageCount,
                    zoomLabel = "${editor.zoomPercent}%",
                    titleLabel = editor.title,
                    onSelectBar = { id ->
                        showBar(id)
                        selectedEntry = null
                        toolbarPropertyTab = ToolbarPropertyTab.SETTINGS
                    },
                    onSelectEntry = { id, section, index ->
                        showBar(id)
                        selectedEntry = section to index
                        toolbarPropertyTab = ToolbarPropertyTab.ELEMENTS
                    },
                    onMoveBar = { id, anchor ->
                        editor.moveToolbarBar(
                            if (canvasTab) com.xnotes.core.tools.ToolbarContext.CANVAS else com.xnotes.core.tools.ToolbarContext.NOTE,
                            id, anchor,
                        )
                    },
                    onReorderBar = { id, target ->
                        val old = editedWorkspace()
                        val updated = old.reorderVisualBar(id, target)
                        if (updated != old) {
                            editor.applyToolbarWorkspaces(editor.toolbarWorkspaces.updateWorkspace(editedContext()) { updated })
                            selectedBarId = id
                        }
                    },
                )
            }
            val propertyTabs: @Composable () -> Unit = {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chip(toolbarStrings.getString(R.string.toolbar_config_bars), toolbarPropertyTab == ToolbarPropertyTab.SETTINGS) {
                        selectedEntry = null
                        toolbarPropertyTab = ToolbarPropertyTab.SETTINGS
                    }
                    Chip(toolbarStrings.getString(R.string.toolbar_config_elements), toolbarPropertyTab == ToolbarPropertyTab.ELEMENTS) {
                        toolbarPropertyTab = ToolbarPropertyTab.ELEMENTS
                    }

                 }
            }
            val inspector: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = if (toolbarPropertyTab == ToolbarPropertyTab.ELEMENTS) 380.dp else 580.dp)
                        .onGloballyPositioned { viewport = it.boundsInRoot() }
                        .verticalScroll(if (toolbarPropertyTab == ToolbarPropertyTab.ELEMENTS)
                            toolbarElementsScroll else toolbarPropertiesScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (toolbarPropertyTab == ToolbarPropertyTab.ELEMENTS) {
                        ToolbarElementsEditor(
                            layout = layout,
                            currentLayout = { editedLayout() },
                            availableItems = if (canvasTab) ToolbarLayout.CANVAS_ITEMS else ToolbarLayout.NOTE_ITEMS,
                            interaction = toolbarInteraction,
                            defaultEntrySize = defaultEntrySize,
                            colorCount = editor.toolbarColorCount,
                            colorControlsExpanded = colorControlsExpanded,
                            onToggleColorControls = { colorControlsExpanded = !colorControlsExpanded },
                            onColorCountChange = { editor.applyToolbarColorCount(it) },
                            onApplyLayout = { applyEdited(it) },
                        )
                    }
                    if (toolbarPropertyTab == ToolbarPropertyTab.SETTINGS) {
                        ToolbarBarsInspector(
                            workspace = workspace,
                            selectedBar = currentBar,
                            look = prefs.toolbarLook,
                            screenWidth = screenConfiguration.screenWidthDp.dp,
                            screenHeight = screenConfiguration.screenHeightDp.dp,
                            colorCount = editor.toolbarColorCount,
                            onUpdateBar = { updateSelectedBar(it) },
                            onConfigureDock = { geometry, look -> configureDock(geometry, look) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.toolbar_preview))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { previewExpanded = !previewExpanded }) {
                    Text(if (previewExpanded) toolbarStrings.getString(R.string.toolbar_config_collapse) else toolbarStrings.getString(R.string.toolbar_config_expand))
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = if (previewExpanded) 680.dp else 390.dp)) { preview() }
            }
            propertyTabs()
            inspector()



        }
    }
        // The dragged chip/section's floating copy: a pane-root sibling so the scroll never clips it.
        val gx = (dragFinger.x - dragGrab.x - paneTopLeft.x).toInt()
        val gy = (dragFinger.y - dragGrab.y - paneTopLeft.y).toInt()
        val ghostEntry = dragItem?.let { (sec, idx) ->
            editedLayout().sections.getOrNull(sec)?.entries?.getOrNull(idx)
        }
        val ghostSecIdx = dragSection
        if (ghostEntry != null) {
            val widthPx = dragItem?.let { chipBounds[it]?.width }
            if (widthPx != null) {
                Box(Modifier.offset { IntOffset(gx, gy) }) {
                    ToolbarDragGhost(ghostEntry.item, ghostEntry.visible, widthPx)
                }
            }
        } else if (ghostSecIdx != null) {
            val sectionG = editedLayout().sections.getOrNull(ghostSecIdx)
            val widthG = sectionBounds[ghostSecIdx]?.width
            if (sectionG != null && widthG != null) {
                Box(Modifier.offset { IntOffset(gx, gy) }) { SectionCardGhost(sectionG, widthG) }
            }
        }
    }

    // While dragging near a vertical edge of the scroll viewport, ease the list along so items
    // off-screen can be reached without lifting the finger.
    LaunchedEffect(dragItem != null || dragSection != null) {
        while (dragItem != null || dragSection != null) {
            val band = 90f
            val y = dragFinger.y
            val delta = when {
                y < viewport.top + band -> -((viewport.top + band - y) / band) * 14f
                y > viewport.bottom - band -> ((y - (viewport.bottom - band)) / band) * 14f
                else -> 0f
            }
            if (delta != 0f) {
                if (toolbarPropertyTab == ToolbarPropertyTab.ELEMENTS)
                    toolbarElementsScroll.scrollBy(delta)
                else toolbarPropertiesScroll.scrollBy(delta)
                if (dragItem != null) retarget() else retargetSection()
            }
            delay(16L)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalPalette.current.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = LocalPalette.current.accent.toComposeColor(), fontSize = 13.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    PreferenceChoiceChip(label, selected, onClick)
}
