package com.xnotes.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.settings.ExplorerLayout
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.util.NameTemplate
import com.xnotes.settings.Preferences
import com.xnotes.settings.MaterialStyle
import com.xnotes.settings.CornerStyle
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarSize
import com.xnotes.settings.MaterialColourMode
import com.xnotes.settings.SwatchRole
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay

/** One-tap filename templates offered beside the free-text field. */
private val NAME_TEMPLATE_PRESETS = listOf(
    NameTemplate.DEFAULT,
    "note_YYYY-MM-DD",
    "note_YYYY-MM-DD_HH-mm",
)
private val penButtonOptions = listOf("eraser" to R.string.tool_eraser, "pan" to R.string.tool_pan, "select" to R.string.tool_select, "none" to R.string.none)
private val tapGestureOptions = listOf(
    "none" to R.string.none,
    "undo" to R.string.undo,
    "redo" to R.string.redo,
    "toggle_pan" to R.string.toggle_pan,
    "toggle_eraser" to R.string.toggle_eraser,
    "toggle_previous" to R.string.toggle_previous,
)

/**
 * Preferences as a backstage pane (spec 10 §8). Edits apply live — each change is
 * pushed straight to the [Editor] (and persisted), so theme/page tweaks are seen
 * immediately, including in the surrounding backstage.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreferencesPane(
    editor: Editor,
    compact: Boolean,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
    onBackToHome: () -> Unit,
    onImportCodeTheme: () -> Unit = {},
    onImportFont: () -> Unit = {},
    onBackup: (export: Boolean) -> Unit = {},
) {
    val palette = LocalPalette.current
    val focusManager = LocalFocusManager.current
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
    var namingColor by remember { mutableStateOf<Rgba?>(null) }
    namingColor?.let { c -> ColorNameDialog(editor, c) { namingColor = null } }
    // Follow out-of-pane preference changes too (the .scm import round-trips a picker).
    LaunchedEffect(editor.prefsVersion) { prefs = editor.preferences }

    val scrollState = rememberScrollState()
    // Which bar the toolbar section is arranging. The two are separate layouts, so the drag state
    // below always belongs to whichever is on screen.
    var canvasTab by remember { mutableStateOf(false) }
    val layout = if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout
    // Toolbar drag state, hoisted here so the floating "ghost" chip can be drawn at the pane
    // root (above and outside the scroll, so it is never clipped). Bounds are plain maps read
    // only at drag time; observable state is just the dragged item, finger, and drop slot.
    var dragItem by remember { mutableStateOf<ToolbarItem?>(null) }
    var dragFinger by remember { mutableStateOf(Offset.Zero) }
    var dragGrab by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val chipBounds = remember { mutableMapOf<ToolbarItem, Rect>() }
    val sectionBounds = remember { mutableMapOf<Int, Rect>() }
    val sectionGrabBounds = remember { mutableMapOf<Int, Rect>() }
    // Section-drag state (the whole card, by its grip handle); shares the finger/grab with the
    // chip drag since only one gesture runs at a time. The drop slot is an insertion index.
    var dragSection by remember { mutableStateOf<Int?>(null) }
    var sectionDropTarget by remember { mutableStateOf<Int?>(null) }
    var paneTopLeft by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    fun editedLayout(): ToolbarLayout = if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout
    fun applyEdited(next: ToolbarLayout) {
        if (canvasTab) editor.applyCanvasToolbarLayout(next) else editor.applyToolbarLayout(next)
    }
    fun retarget() {
        dropTarget = toolbarDropTarget(editedLayout(), dragFinger, chipBounds, sectionBounds)
    }
    fun retargetSection() {
        sectionDropTarget = toolbarSectionDropTarget(editedLayout(), dragFinger, sectionBounds)
    }
    fun showTab(canvas: Boolean) {
        if (canvasTab == canvas) return
        canvasTab = canvas
        // The bounds maps are keyed by item and the two bars share most items, so stale entries
        // from the other tab would aim a drop at a chip that is no longer there.
        chipBounds.clear()
        sectionBounds.clear()
        sectionGrabBounds.clear()
        dragItem = null
        dragSection = null
        dropTarget = null
        sectionDropTarget = null
    }

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
            if (compact) {
                Tooltip(stringResource(R.string.back_to_home)) {
                    IconButton(onClick = onBackToHome) {
                        Icon(XnotesIcons.prev, stringResource(R.string.back_to_home), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
            } else if (!sidebarOpen) {
                Tooltip(stringResource(R.string.show_sidebar)) {
                    IconButton(onClick = onShowSidebar) {
                        Icon(XnotesIcons.menu, stringResource(R.string.show_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(stringResource(R.string.preferences), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { update(Preferences()) }) { Text(stringResource(R.string.reset_to_defaults), fontSize = 13.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            // Ending at the keyboard's edge scrolls a focused field up out from under it.
            Modifier.fillMaxSize().imePadding().verticalScroll(scrollState)
                .onGloballyPositioned { viewport = it.boundsInRoot() },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle(stringResource(R.string.pref_general))
            FieldLabel(stringResource(R.string.pref_ui_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.theme_system), prefs.uiAppearance == "system") { update(prefs.copy(uiAppearance = "system")) }
                Chip(stringResource(R.string.theme_dark), prefs.uiAppearance == "dark") { update(prefs.copy(uiAppearance = "dark")) }
                Chip(stringResource(R.string.theme_light), prefs.uiAppearance == "light") { update(prefs.copy(uiAppearance = "light")) }
                Chip(stringResource(R.string.theme_oled), prefs.uiAppearance == "oled") { update(prefs.copy(uiAppearance = "oled")) }
            }
            val systemColours = prefs.materialMode == MaterialColourMode.SYSTEM
            FieldLabel(stringResource(R.string.material_tone))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.material_system_colours), systemColours) {
                    update(prefs.copy(materialMode = MaterialColourMode.SYSTEM))
                }
                Chip(stringResource(R.string.material_single_tone), prefs.materialMode == MaterialColourMode.SINGLE) {
                    update(prefs.copy(materialMode = MaterialColourMode.SINGLE))
                }
                Chip(stringResource(R.string.material_dual_tone), prefs.materialMode == MaterialColourMode.DUAL) {
                    update(prefs.copy(materialMode = MaterialColourMode.DUAL))
                }
            }
            if (!systemColours) {
                key(prefs.materialMode) { MaterialColourPicker(prefs, ::update) }
                CustomMaterialControls(prefs, ::update)
            } else if (Build.VERSION.SDK_INT < 31) {
                Text(stringResource(R.string.material_system_fallback), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
            }
            FieldLabel(stringResource(R.string.pref_corners))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((style, label) in listOf(CornerStyle.SHARP to R.string.corners_sharp, CornerStyle.ROUNDED to R.string.corners_rounded, CornerStyle.SOFT to R.string.corners_soft)) {
                    Chip(stringResource(label), prefs.cornerStyle == style) { updateHome(prefs.copy(cornerStyle = style)) }
                }
            }
            CheckRow(stringResource(R.string.pref_start_fullscreen), editor.fullscreen) { editor.setFullscreenPref(it) }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle(stringResource(R.string.pref_input))
            CheckRow(stringResource(R.string.pref_finger_draws), prefs.fingerDraws) { update(prefs.copy(fingerDraws = it)) }
            FieldLabel(stringResource(R.string.pref_zoom_lock_pan))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(stringResource(R.string.pan_single), prefs.zoomLockPan == "single") { update(prefs.copy(zoomLockPan = "single")) }
                Chip(stringResource(R.string.pan_two), prefs.zoomLockPan == "double") { update(prefs.copy(zoomLockPan = "double")) }
                Chip(stringResource(R.string.pan_none), prefs.zoomLockPan == "none") { update(prefs.copy(zoomLockPan = "none")) }
            }
            CheckRow(stringResource(R.string.pref_detect_shapes), prefs.detectShapes) { update(prefs.copy(detectShapes = it)) }
            FieldLabel(stringResource(R.string.pref_pen_button_hold))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                penButtonOptions.forEach { (id, label) ->
                    Chip(stringResource(label), prefs.penButtonTool == id) { update(prefs.copy(penButtonTool = id)) }
                }
            }
            if (prefs.penButtonTool == "eraser" || prefs.penButtonTool == "pan") {
                CheckRow(stringResource(R.string.pref_pen_button_hover), prefs.penButtonHover) {
                    update(prefs.copy(penButtonHover = it))
                }
            }
            val tapOptions = tapGestureOptions.map { (id, res) -> id to stringResource(res) }
            FieldLabel(stringResource(R.string.pref_two_finger_tap))
            OptionDropdown(tapOptions, prefs.twoFingerTap) { update(prefs.copy(twoFingerTap = it)) }
            FieldLabel(stringResource(R.string.pref_three_finger_tap))
            OptionDropdown(tapOptions, prefs.threeFingerTap) { update(prefs.copy(threeFingerTap = it)) }
            FieldLabel(stringResource(R.string.pref_stylus_double_tap))
            OptionDropdown(tapOptions, prefs.stylusDoubleTap) { update(prefs.copy(stylusDoubleTap = it)) }
            FieldLabel(stringResource(R.string.pref_stylus_button_tap))
            OptionDropdown(tapOptions, prefs.stylusButtonTap) { update(prefs.copy(stylusButtonTap = it)) }
            FieldLabel(stringResource(R.string.pref_redmi_buttons))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.pen_button_1), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                    OptionDropdown(tapOptions, prefs.stylusButton1Tap) { update(prefs.copy(stylusButton1Tap = it)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.pen_button_2), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                    OptionDropdown(tapOptions, prefs.stylusButton2Tap) { update(prefs.copy(stylusButton2Tap = it)) }
                }
            }
            CheckRow(stringResource(R.string.pref_volume_keys_turn_pages), prefs.volumeKeysTurnPages) { update(prefs.copy(volumeKeysTurnPages = it)) }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle(stringResource(R.string.pref_new_notes))
            FieldLabel(stringResource(R.string.pref_filename_template))
            Text(
                stringResource(R.string.pref_filename_template_help),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            OutlinedTextField(
                value = prefs.newNoteNameTemplate,
                onValueChange = { update(prefs.copy(newNoteNameTemplate = it)) },
                singleLine = true,
                placeholder = { Text(NameTemplate.DEFAULT) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.width(280.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NAME_TEMPLATE_PRESETS.forEach { t ->
                    Chip(t, prefs.newNoteNameTemplate == t) { update(prefs.copy(newNoteNameTemplate = t)) }
                }
            }
            Text(
                stringResource(R.string.pref_next_note_named, "${editor.newNoteStem(emptySet())}.xnote"),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle(stringResource(R.string.pref_page))
            FieldLabel(stringResource(R.string.pref_default_page_size))
            SizeDropdown(prefs.defaultPageSize) { it?.let { size -> update(prefs.copy(defaultPageSize = size)) } }
            if (prefs.defaultPageSize == PageSize.CUSTOM) {
                // A custom page is taken as typed, so the orientation chips have nothing to say
                // about it and are left out rather than shown doing nothing.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MillimetreField(stringResource(R.string.width_mm), prefs.customPageWidthMm) {
                        update(prefs.copy(customPageWidthMm = it))
                    }
                    MillimetreField(stringResource(R.string.height_mm), prefs.customPageHeightMm) {
                        update(prefs.copy(customPageHeightMm = it))
                    }
                }
            } else {
                FieldLabel(stringResource(R.string.pref_orientation))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.orientation_portrait), prefs.defaultPageOrientation == Orientation.PORTRAIT) {
                        update(prefs.copy(defaultPageOrientation = Orientation.PORTRAIT))
                    }
                    Chip(stringResource(R.string.orientation_landscape), prefs.defaultPageOrientation == Orientation.LANDSCAPE) {
                        update(prefs.copy(defaultPageOrientation = Orientation.LANDSCAPE))
                    }
                }
            }
            CheckRow(stringResource(R.string.pref_hide_page_borders), prefs.hidePageBorders) {
                update(prefs.copy(hidePageBorders = it))
            }
            FieldLabel(stringResource(R.string.pref_side_margin_px, prefs.sideMargin.toInt()))
            Slider(
                value = prefs.sideMargin.toFloat(),
                onValueChange = { update(prefs.copy(sideMargin = it.toDouble())) },
                valueRange = 0f..64f,
                modifier = Modifier.width(280.dp),
            )
            FieldLabel(stringResource(R.string.pref_page_colour))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwatchCircles(editor, SwatchRole.PAPER, prefs.pageColor) { update(prefs.copy(pageColor = it)) }
            }
            CheckRow(stringResource(R.string.pref_page_colour_follows_theme), prefs.pageColor == null) {
                update(prefs.copy(pageColor = if (it) null else editor.swatches(SwatchRole.PAPER).first()))
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle(stringResource(R.string.pref_home_explorer))
            Text(
                stringResource(R.string.pref_home_explorer_help),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            FieldLabel(stringResource(R.string.pref_default_layout))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExplorerLayout.entries.forEach { l -> Chip(stringResource(l.labelRes), editor.explorerView.layout == l) { editor.setDefaultLayout(l) } }
            }
            FieldLabel(stringResource(R.string.pref_switcher_layouts))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExplorerLayout.entries.forEach { l ->
                    val on = l in prefs.switcherLayouts
                    ExplorerChip(stringResource(l.labelRes), on, icon = if (on) XnotesIcons.check else XnotesIcons.plus) {
                        val next = if (on) prefs.switcherLayouts - l else prefs.switcherLayouts + l
                        if (next.isNotEmpty()) updateHome(prefs.copy(switcherLayouts = ExplorerLayout.entries.filter { it in next }))
                    }
                }
            }
            FieldLabel(stringResource(R.string.pref_sidebar_sections))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun section(label: String, on: Boolean, flip: () -> Preferences) =
                    ExplorerChip(label, on, icon = if (on) XnotesIcons.check else XnotesIcons.plus) { updateHome(flip()) }
                section(stringResource(R.string.recent), prefs.sidebarRecent) { prefs.copy(sidebarRecent = !prefs.sidebarRecent) }
                section(stringResource(R.string.pinned), prefs.sidebarPinned) { prefs.copy(sidebarPinned = !prefs.sidebarPinned) }
                section(stringResource(R.string.toolbar_colours), prefs.sidebarColours) { prefs.copy(sidebarColours = !prefs.sidebarColours) }
                if (prefs.trashDays != 0) section(stringResource(R.string.trash), prefs.sidebarTrash) { prefs.copy(sidebarTrash = !prefs.sidebarTrash) }
            }
            FieldLabel(stringResource(R.string.pref_home_opens_to))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.top_folder), prefs.homeOpensTo == "top") { updateHome(prefs.copy(homeOpensTo = "top")) }
                Chip(stringResource(R.string.last_folder), prefs.homeOpensTo == "last") { updateHome(prefs.copy(homeOpensTo = "last")) }
                Chip(stringResource(R.string.recent_and_pinned), prefs.homeOpensTo == "shelves") { updateHome(prefs.copy(homeOpensTo = "shelves")) }
            }
            val words = rememberExplorerWords()
            FieldLabel(stringResource(R.string.pref_dates))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(words.daysAgo(2), prefs.dateStyle == "relative") { updateHome(prefs.copy(dateStyle = "relative")) }
                Chip("${words.shortWeekday(java.time.DayOfWeek.WEDNESDAY)} ${clockText(words, java.time.LocalTime.of(17, 30), true)}", prefs.dateStyle == "day") { updateHome(prefs.copy(dateStyle = "day")) }
                Chip(words.dayMonthYear(16, java.time.Month.SEPTEMBER, 2026), prefs.dateStyle == "date") { updateHome(prefs.copy(dateStyle = "date")) }
            }
            FieldLabel(stringResource(R.string.pref_tapping_file))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.tap_opens), !prefs.tapPreviews) { updateHome(prefs.copy(tapPreviews = false)) }
                Chip(stringResource(R.string.tap_previews), prefs.tapPreviews) { updateHome(prefs.copy(tapPreviews = true)) }
            }
            FieldLabel(stringResource(R.string.pref_keep_deleted))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.off), prefs.trashDays == 0) { updateHome(prefs.copy(trashDays = 0)) }
                Chip(pluralStringResource(R.plurals.days_count, 7, 7), prefs.trashDays == 7) { updateHome(prefs.copy(trashDays = 7)) }
                Chip(pluralStringResource(R.plurals.days_count, 30, 30), prefs.trashDays == 30) { updateHome(prefs.copy(trashDays = 30)) }
                Chip(stringResource(R.string.until_emptied), prefs.trashDays == Preferences.TRASH_FOREVER) { updateHome(prefs.copy(trashDays = Preferences.TRASH_FOREVER)) }
            }
            if (editor.browseRoot != null) {
                FieldLabel(stringResource(R.string.pref_colour_names))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    editor.colorNames.entries.sortedBy { it.value.lowercase() }.forEach { (c, name) ->
                        Row(
                            Modifier.clip(MaterialTheme.shapes.small).background(palette.surface.toComposeColor())
                                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                                .clickable { namingColor = c }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(codeTint(c, palette)))
                            Text(name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1)
                        }
                    }
                    var picking by remember { mutableStateOf(false) }
                    Box {
                        ExplorerChip(stringResource(R.string.name_a_colour), false, icon = XnotesIcons.plus) { picking = true }
                        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                            ColorCodeMenuContent { c -> picking = false; if (c != null) namingColor = c }
                        }
                    }
                }
            }
            CheckRow(stringResource(R.string.pref_per_folder_views), prefs.perFolderViews) { updateHome(prefs.copy(perFolderViews = it)) }
            CheckRow(stringResource(R.string.pref_show_folder_counts), prefs.showFolderCounts) { updateHome(prefs.copy(showFolderCounts = it)) }
            CheckRow(stringResource(R.string.pref_show_extensions), prefs.showExtensions) { updateHome(prefs.copy(showExtensions = it)) }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle(stringResource(R.string.pref_pdf_export))
            CheckRow(stringResource(R.string.pref_pdf_heading_bookmarks), prefs.pdfHeadingBookmarks) {
                updateHome(prefs.copy(pdfHeadingBookmarks = it))
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle(stringResource(R.string.pref_performance))
            FieldLabel(stringResource(R.string.pref_max_cache_px, prefs.maxCacheResolution))
            Slider(
                value = prefs.maxCacheResolution.toFloat(),
                onValueChange = { update(prefs.copy(maxCacheResolution = Math.round(it))) },
                valueRange = 1024f..4096f,
                steps = 5,
                modifier = Modifier.width(280.dp),
            )
            Text(
                stringResource(R.string.pref_cache_help),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            CheckRow(stringResource(R.string.pref_disable_front_buffering), prefs.disableFrontBuffering) {
                update(prefs.copy(disableFrontBuffering = it))
            }
            Text(
                stringResource(R.string.pref_front_buffering_help),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )

            if (editor.treeSitterAvailable) {
                HorizontalDivider(color = palette.border.toComposeColor())
                SectionTitle(stringResource(R.string.pref_code_highlighting))

                val langOptions = editor.scmLanguages().map { it to it }
                FieldLabel(stringResource(R.string.pref_default_code_language))
                Text(
                    stringResource(R.string.pref_code_language_help),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 12.sp,
                )
                OptionDropdown(listOf("plain" to stringResource(R.string.code_plain)) + langOptions, prefs.defaultCodeLanguage) {
                    update(prefs.copy(defaultCodeLanguage = it))
                }

                FieldLabel(stringResource(R.string.pref_code_theme))
                Text(
                    stringResource(R.string.pref_code_theme_help),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 12.sp,
                )
                if (editor.hasCustomCodeTheme) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            prefs.codeThemeName ?: stringResource(R.string.custom_theme),
                            color = palette.text.toComposeColor(),
                            fontSize = 13.sp,
                        )
                        TextButton(onClick = { editor.resetCodeTheme() }) { Text(stringResource(R.string.reset), fontSize = 13.sp) }
                    }
                }
                TextButton(onClick = { onImportCodeTheme() }) { Text(stringResource(R.string.import_theme), fontSize = 13.sp) }
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle(stringResource(R.string.pref_fonts))
            Text(
                stringResource(R.string.pref_fonts_help),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            TextButton(onClick = { onImportFont() }) { Text(stringResource(R.string.import_font), fontSize = 13.sp) }
            for (font in editor.customFonts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        font.label,
                        color = palette.text.toComposeColor(),
                        fontSize = 13.sp,
                        style = TextStyle(fontFamily = font.face.toComposeFamily()),
                    )
                    if (font.mono) {
                        Text(stringResource(R.string.mono_suffix), color = palette.accent.toComposeColor(), fontSize = 11.sp)
                    }
                    TextButton(onClick = { editor.removeCustomFont(font.face) }) { Text(stringResource(R.string.remove), fontSize = 12.sp) }
                }
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle(stringResource(R.string.pref_toolbar))
            // The look, like the swatch count below, is one setting for both bars.
            val look = prefs.toolbarLook
            FieldLabel(stringResource(R.string.pref_toolbar_style))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.toolbar_docked), !look.floating) { updateHome(prefs.copy(toolbarLook = look.copy(floating = false))) }
                Chip(stringResource(R.string.toolbar_floating), look.floating) { updateHome(prefs.copy(toolbarLook = look.copy(floating = true))) }
            }
            FieldLabel(stringResource(R.string.pref_toolbar_position))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((position, label) in listOf(
                    ToolbarPosition.TOP to R.string.edge_top,
                    ToolbarPosition.BOTTOM to R.string.edge_bottom,
                    ToolbarPosition.LEFT to R.string.edge_left,
                    ToolbarPosition.RIGHT to R.string.edge_right,
                )) {
                    Chip(stringResource(label), look.position == position) { updateHome(prefs.copy(toolbarLook = look.copy(position = position))) }
                }
            }
            FieldLabel(stringResource(R.string.pref_toolbar_size))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((size, label) in listOf(
                    ToolbarSize.COMPACT to R.string.toolbar_size_compact,
                    ToolbarSize.REGULAR to R.string.toolbar_size_regular,
                    ToolbarSize.COMFORTABLE to R.string.toolbar_size_comfortable,
                )) {
                    Chip(stringResource(label), look.size == size) { updateHome(prefs.copy(toolbarLook = look.copy(size = size))) }
                }
            }
            FieldLabel(stringResource(R.string.pref_toolbar_colours_n, editor.toolbarColorCount))
            Slider(
                value = editor.toolbarColorCount.toFloat(),
                onValueChange = { editor.applyToolbarColorCount(Math.round(it)) },
                valueRange = 1f..InkPalette.MAX_SWATCHES.toFloat(),
                steps = InkPalette.MAX_SWATCHES - 2,
                modifier = Modifier.width(280.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("xnote", selected = !canvasTab) { showTab(false) }
                Chip("xcanvas", selected = canvasTab) { showTab(true) }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        applyEdited(if (canvasTab) ToolbarLayout.CANVAS_DEFAULT else ToolbarLayout.DEFAULT)
                    },
                ) { Text(stringResource(R.string.reset), fontSize = 13.sp) }
            }
            Text(
                stringResource(R.string.pref_toolbar_help),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            ToolbarCustomizerBody(
                layout = layout,
                dragItem = dragItem,
                dropTarget = dropTarget,
                dragSection = dragSection,
                sectionDropTarget = sectionDropTarget,
                chipBounds = chipBounds,
                sectionBounds = sectionBounds,
                sectionGrabBounds = sectionGrabBounds,
                onToggle = { s, i -> applyEdited(editedLayout().toggleVisible(s, i)) },
                onDelete = { s -> applyEdited(editedLayout().removeSection(s)) },
                onAdd = { applyEdited(editedLayout().addSection()) },
                onDragStart = { item, local ->
                    dragItem = item
                    dragGrab = local
                    dragFinger = (chipBounds[item]?.topLeft ?: Offset.Zero) + local
                    retarget()
                },
                onDrag = { delta -> dragFinger += delta; retarget() },
                onDrop = {
                    val target = dropTarget
                    val moving = dragItem
                    if (target != null && moving != null) {
                        val cur = editedLayout()
                        val fSec = cur.sections.indexOfFirst { s -> s.entries.any { it.item == moving } }
                        val fIdx = if (fSec >= 0) cur.sections[fSec].entries.indexOfFirst { it.item == moving } else -1
                        if (fSec >= 0 && fIdx >= 0) {
                            applyEdited(cur.moveItem(fSec, fIdx, target.first, target.second))
                        }
                    }
                    dragItem = null
                    dropTarget = null
                },
                onCancel = {
                    dragItem = null
                    dropTarget = null
                },
                onSectionDragStart = { sec, local ->
                    dragSection = sec
                    val grab = sectionGrabBounds[sec]?.topLeft ?: sectionBounds[sec]?.topLeft ?: Offset.Zero
                    val card = sectionBounds[sec]?.topLeft ?: Offset.Zero
                    dragFinger = grab + local
                    dragGrab = dragFinger - card
                    retargetSection()
                },
                onSectionDrag = { delta -> dragFinger += delta; retargetSection() },
                onSectionDrop = {
                    val from = dragSection
                    val insertAt = sectionDropTarget
                    if (from != null && insertAt != null) {
                        val cur = editedLayout()
                        val to = (if (insertAt > from) insertAt - 1 else insertAt).coerceIn(0, cur.sections.lastIndex)
                        if (to != from) applyEdited(cur.moveSection(from, to))
                    }
                    dragSection = null
                    sectionDropTarget = null
                },
                onSectionCancel = {
                    dragSection = null
                    sectionDropTarget = null
                },
            )

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle(stringResource(R.string.pref_backup))
            Text(stringResource(R.string.pref_backup_help), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onBackup(true) }) { Text(stringResource(R.string.export_settings), fontSize = 13.sp) }
                TextButton(onClick = { onBackup(false) }) { Text(stringResource(R.string.import_settings), fontSize = 13.sp) }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
        // The dragged chip/section's floating copy: a pane-root sibling so the scroll never clips it.
        val gx = (dragFinger.x - dragGrab.x - paneTopLeft.x).toInt()
        val gy = (dragFinger.y - dragGrab.y - paneTopLeft.y).toInt()
        val ghostChip = dragItem
        val ghostSecIdx = dragSection
        if (ghostChip != null) {
            Box(Modifier.offset { IntOffset(gx, gy) }) { ToolbarDragGhost(ghostChip) }
        } else if (ghostSecIdx != null) {
            val sectionG = (if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout)
                .sections.getOrNull(ghostSecIdx)
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
                scrollState.scrollBy(delta)
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
private fun CustomMaterialControls(prefs: Preferences, update: (Preferences) -> Unit) {
    val palette = LocalPalette.current
    val styles = listOf(
        MaterialStyle.TONAL_SPOT to R.string.material_style_soft,
        MaterialStyle.VIBRANT to R.string.material_style_vivid,
        MaterialStyle.FIDELITY to R.string.material_style_original,
        MaterialStyle.EXPRESSIVE to R.string.material_style_playful,
        MaterialStyle.FRUIT_SALAD to R.string.material_style_fresh,
        MaterialStyle.RAINBOW to R.string.material_style_clean,
        MaterialStyle.NEUTRAL to R.string.material_style_muted,
        MaterialStyle.MONOCHROME to R.string.material_style_grayscale,
    )
    FieldLabel(stringResource(R.string.material_style))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        styles.forEach { (style, label) ->
            Chip(stringResource(label), prefs.materialStyle == style) {
                update(prefs.copy(materialStyle = style))
            }
        }
    }
    val description = when (prefs.materialStyle) {
        MaterialStyle.TONAL_SPOT -> R.string.material_style_soft_description
        MaterialStyle.VIBRANT -> R.string.material_style_vivid_description
        MaterialStyle.FIDELITY -> R.string.material_style_original_description
        MaterialStyle.EXPRESSIVE -> R.string.material_style_playful_description
        MaterialStyle.FRUIT_SALAD -> R.string.material_style_fresh_description
        MaterialStyle.RAINBOW -> R.string.material_style_clean_description
        MaterialStyle.NEUTRAL -> R.string.material_style_muted_description
        MaterialStyle.MONOCHROME -> R.string.material_style_grayscale_description
    }
    Text(stringResource(description), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
    val percent = Math.round(prefs.materialContrast * 100).toInt()
    FieldLabel(stringResource(R.string.material_contrast, if (percent > 0) "+$percent%" else "$percent%"))
    Slider(
        value = prefs.materialContrast.toFloat(),
        onValueChange = { update(prefs.copy(materialContrast = Math.round(it * 10) / 10.0)) },
        valueRange = -1f..1f,
        steps = 19,
        modifier = Modifier.width(280.dp),
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), MaterialTheme.shapes.small)
            .semantics { this.selected = selected }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) palette.selectionForeground.toComposeColor() else palette.text.toComposeColor(),
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun ColorDot(color: Color, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val label = Rgba.toHex(Rgba.fromArgb(color.toArgb()))
    Box(
        Modifier
            .size(30.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .then(if (selected) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, palette.border.toComposeColor(), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/** A spectrum wheel signals that this dot opens the full picker. */
private val spectrumBrush = Brush.sweepGradient(
    listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
    ),
)

/**
 * A spectrum dot that opens [grid], a popup of colour swatches — shared by the accent and
 * page-colour rows. Until a colour outside the row's presets is chosen the dot shows the
 * spectrum wheel; once one is, it fills with that colour and reads as selected.
 */
@Composable
internal fun ColorPickerDot(
    current: Rgba?,
    custom: Boolean,
    onPick: (Rgba) -> Unit,
    dismissOnPick: Boolean = true,
    enabled: Boolean = true,
    grid: @Composable (onDismiss: () -> Unit, onPick: (Rgba) -> Unit) -> Unit,
) {
    val palette = LocalPalette.current
    val label = stringResource(R.string.material_custom_colour)
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(30.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .semantics { contentDescription = label }
                .then(if (custom) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
                .padding(4.dp)
                .clip(CircleShape)
                .then(if (custom && current != null) Modifier.background(current.toComposeColor()) else Modifier.background(spectrumBrush))
                .border(1.dp, palette.border.toComposeColor(), CircleShape)
                .clickable(enabled = enabled) { open = true },
        )
        // A live picker (e.g. the page/ink popup) edits across several taps, so it stays open until a
        // tap outside; a one-shot grid (the accent swatches) closes the moment a colour is chosen.
        if (open) grid({ open = false }, { onPick(it); if (dismissOnPick) open = false })
    }
}

/** Where each [SwatchRole]'s circles are kept: the settings file, shared by every row of that role. */
interface SwatchStore {
    fun swatches(role: SwatchRole): List<Rgba>
    fun setSwatch(role: SwatchRole, index: Int, color: Rgba)
}

/**
 * A role's five colour circles. A tap applies one; a tap on the one already [selected] opens the
 * picker and recolours that circle, as the toolbar's pen swatches do. Emits the circles straight
 * into the caller's row, so a Default chip can sit before them.
 */
@Composable
internal fun SwatchCircles(store: SwatchStore, role: SwatchRole, selected: Rgba?, onPick: (Rgba) -> Unit) {
    var swatches by remember(role) { mutableStateOf(store.swatches(role)) }
    var editing by remember { mutableStateOf<Int?>(null) }
    swatches.forEachIndexed { i, c ->
        Box {
            ColorDot(c.toComposeColor(), selected == c) { if (selected == c) editing = i else onPick(c) }
            if (editing == i) PageColorGridPopup(c, onDismiss = { editing = null }) { picked ->
                swatches = swatches.toMutableList().also { it[i] = picked }
                store.setSwatch(role, i, picked)
                onPick(picked)
            }
        }
    }
}

/** One tappable colour cell in a picker grid. */
/**
 * The colour-code picker shown inside a note/folder's overflow menu: a None row to clear the colour,
 * then the picker's full matrix (pale tints through near-black plus the greyscale row). [onPick] is
 * called with null for None. Rendered directly inside the menu's own dropdown column.
 */
@Composable
internal fun ColorCodeMenuContent(onPick: (Rgba?) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.clip(MaterialTheme.shapes.extraSmall).clickable { onPick(null) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(2.dp))
                    .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(2.dp)),
            )
            Text(stringResource(R.string.none), color = palette.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        FullSwatchGrid { onPick(it) }
    }
}

/**
 * Page/pattern colour picker: the shared [ColorPickerPopup] (Swatches + Spectrum tabs, full
 * 13×13 range from pale tint to near-black plus a greyscale row, and HEX/RGB fields), so paper-like
 * and muted page backgrounds are reachable, not just the saturated ones. It keeps no recents list.
 */
@Composable
internal fun PageColorGridPopup(initial: Rgba?, onDismiss: () -> Unit, onPick: (Rgba) -> Unit) {
    ColorPickerPopup(initial = initial, recents = emptyList(), onDismiss = onDismiss, onPick = onPick)
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(2.dp))
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontSize = 14.sp)
    }
}

/**
 * One side of a custom page, in millimetres. The field keeps whatever is typed so a number can be
 * cleared and retyped; only a value that parses inside the settable range reaches the preference.
 */
@Composable
private fun MillimetreField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(formatMm(value)) }
    // Adopt an outside change (Reset to defaults) without ever rewriting what is being typed.
    LaunchedEffect(value) { if (text.trim().toDoubleOrNull() != value) text = formatMm(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            parseMm(raw)?.let(onChange)
        },
        singleLine = true,
        label = { Text(label, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.width(136.dp),
    )
}

@Composable
private fun OptionDropdown(options: List<Pair<String, String>>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val label = options.firstOrNull { it.first == selectedId }?.second ?: options.first().second
    Box {
        Box(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(label, color = palette.text.toComposeColor(), fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, lbl) ->
                DropdownMenuItem(text = { Text(lbl) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}
