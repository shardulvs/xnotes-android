package com.xnotes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.platform.ImageDecoder
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Toolbar(
    editor: Editor,
    onToggleFullscreen: () -> Unit,
    onOpenBackstage: () -> Unit,
    onInsertImage: () -> Unit,
    onAddStickers: () -> Unit,
    onClosePane: (() -> Unit)? = null,
    onImportTemplate: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onImagePage: () -> Unit = {},
) {
    // The five stroke tools use the designed vector drawables (res/drawable/ic_stroke_*),
    // tinted at the call site like every other icon; the rest use the built-in line set.
    val toolIcons: Map<Tool, ImageVector> = mapOf(
        Tool.PEN to ImageVector.vectorResource(R.drawable.ic_stroke_regular),
        Tool.DASHED to ImageVector.vectorResource(R.drawable.ic_stroke_dashed),
        Tool.CALLIGRAPHY to ImageVector.vectorResource(R.drawable.ic_stroke_calligraphy),
        Tool.SPEED to ImageVector.vectorResource(R.drawable.ic_stroke_speed),
        Tool.TAPER to ImageVector.vectorResource(R.drawable.ic_stroke_taper),
        Tool.HIGHLIGHTER to ImageVector.vectorResource(R.drawable.ic_stroke_highlighter),
        Tool.ERASER to XnotesIcons.eraser,
        Tool.PAN to XnotesIcons.pan,
        Tool.SELECT to XnotesIcons.select,
        Tool.LASSO to XnotesIcons.lasso,
        Tool.SCREENSHOT to XnotesIcons.scissors,
        Tool.SHAPE to XnotesIcons.shape,
        Tool.TEXT to XnotesIcons.text,
        Tool.TEXT_BOX to XnotesIcons.textBox,
        Tool.TABLE to XnotesIcons.table,
        Tool.MARKUP to XnotesIcons.markup,
    )
    var configForTool by remember { mutableStateOf<Tool?>(null) }
    var switcherIndex by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf(false) }
    // Pinned outside the scrolling strip so closing a split pane is always one tap away.
    ToolbarFrame(armed = editor.tool, trailing = onClosePane?.let { { ClosePaneButton(it) } }) {
        // The bar is driven by the user-customisable layout; separators sit between non-empty
        // sections, and each item dispatches to its renderer (see ToolbarItemView).
        editor.toolbarLayout.visibleSections.forEachIndexed { si, section ->
            if (si > 0) Separator()
            section.visibleEntries.forEach { entry ->
                ToolbarItemView(
                    editor = editor,
                    item = entry.item,
                    toolIcons = toolIcons,
                    configForTool = configForTool,
                    setConfigForTool = { configForTool = it },
                    switcherIndex = switcherIndex,
                    setSwitcherIndex = { switcherIndex = it },
                    onRename = { renaming = true },
                    onOpenBackstage = onOpenBackstage,
                    onInsertImage = onInsertImage,
                    onTakePhoto = onTakePhoto,
                    onImagePage = onImagePage,
                    onAddStickers = onAddStickers,
                    onToggleFullscreen = onToggleFullscreen,
                    onImportTemplate = onImportTemplate,
                )
            }
        }
    }

    if (renaming) {
        val renameFailed = stringResource(R.string.err_rename_note)
        RenameDialog(
            initial = editor.title,
            onConfirm = { name ->
                renaming = false
                if (!editor.renameCurrentDocument(name)) editor.message = renameFailed
            },
            onDismiss = { renaming = false },
        )
    }
}

/** Renders one toolbar item by id, reusing the same controls/popups the bar has always used. */
@Composable
private fun ToolbarItemView(
    editor: Editor,
    item: ToolbarItem,
    toolIcons: Map<Tool, ImageVector>,
    configForTool: Tool?,
    setConfigForTool: (Tool?) -> Unit,
    switcherIndex: Int?,
    setSwitcherIndex: (Int?) -> Unit,
    onRename: () -> Unit,
    onOpenBackstage: () -> Unit,
    onInsertImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onImagePage: () -> Unit,
    onAddStickers: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onImportTemplate: () -> Unit,
) {
    when (item) {
        // Canvas-only items; a stored paged layout can never hold one, so nothing is drawn.
        ToolbarItem.WAYPOINTS, ToolbarItem.MINIMAP -> Unit

        ToolbarItem.HOME -> ToolbarIcon(XnotesIcons.prev, stringResource(R.string.toolbar_home)) { onOpenBackstage() }
        // A name has no room down a side rail.
        ToolbarItem.TITLE -> if (!LocalBar.current.vertical) Label(
            if (editor.state.document.displayName == null && editor.state.document.path == null) stringResource(R.string.untitled) else editor.title,
            modifier = Modifier
                .widthIn(max = 160.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable { onRename() },
        )
        ToolbarItem.SIDEBAR ->
            ToolbarIcon(XnotesIcons.sidebar, stringResource(R.string.side_panel), active = editor.sidebarVisible) { editor.toggleSidebar() }

        ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
        ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER, ToolbarItem.PAN,
        ToolbarItem.SELECT, ToolbarItem.LASSO, ToolbarItem.SCREENSHOT, ToolbarItem.SHAPE,
        ToolbarItem.TEXT, ToolbarItem.TEXT_BOX, ToolbarItem.TABLE -> {
            val tool = Tool.fromId(item.id)
            if (tool != null) ToolButton(editor, tool, toolIcons[tool], configForTool, setConfigForTool)
        }

        // Marks a PDF's text, so a note without a PDF has no use for it.
        ToolbarItem.MARKUP ->
            if (editor.hasPdf) ToolButton(editor, Tool.MARKUP, toolIcons[Tool.MARKUP], configForTool, setConfigForTool)

        ToolbarItem.WAND ->
            ToolbarIcon(XnotesIcons.magicWand, stringResource(R.string.tool_wand), active = editor.wandEnabled) { editor.toggleWand() }
        ToolbarItem.RULER ->
            ToolbarIcon(XnotesIcons.ruler, stringResource(R.string.tool_ruler), active = editor.rulerVisible) { editor.toggleRuler() }

        ToolbarItem.IMAGE -> ImageMenu(editor, onInsertImage, onTakePhoto, onImagePage, onAddStickers)

        ToolbarItem.UNDO -> ToolbarIcon(XnotesIcons.undo, stringResource(R.string.undo), enabled = editor.canUndo) { editor.undo() }
        ToolbarItem.REDO -> ToolbarIcon(XnotesIcons.redo, stringResource(R.string.redo), enabled = editor.canRedo) { editor.redo() }

        ToolbarItem.PAGE_NAV -> {
            ToolbarIcon(XnotesIcons.prev, stringResource(R.string.previous_page)) { editor.prevPage() }
            var jumpOpen by remember { mutableStateOf(false) }
            Box {
                PageCounter(
                    editor.pageIndex + 1,
                    editor.pageCount,
                    Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { jumpOpen = true },
                )
                if (jumpOpen) PageJumpPopup(editor) { jumpOpen = false }
            }
            ToolbarIcon(XnotesIcons.next, stringResource(R.string.next_page)) { editor.nextPage() }
        }
        ToolbarItem.SEARCH -> {
            val open = editor.sidebarVisible && editor.sidePanelTab == SidePanelTab.SEARCH
            ToolbarIcon(XnotesIcons.search, stringResource(R.string.search), active = open) {
                if (open) editor.sidebarVisible = false else editor.openSearch()
            }
        }
        ToolbarItem.STYLES -> StylesButton(editor, onImportTemplate)
        ToolbarItem.MARGINS -> MarginsButton(editor)
        ToolbarItem.VIEW -> ViewButton(editor)

        ToolbarItem.ZOOM -> {
            ToolbarIcon(XnotesIcons.zoomOut, stringResource(R.string.zoom_out), enabled = !editor.zoomLocked) { editor.zoomOut() }
            var zoomMenuOpen by remember { mutableStateOf(false) }
            Box {
                Label(
                    "${editor.zoomPercent}%",
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { zoomMenuOpen = true },
                )
                if (zoomMenuOpen) ZoomMenuPopup(editor) { zoomMenuOpen = false }
            }
            ToolbarIcon(XnotesIcons.zoomIn, stringResource(R.string.zoom_in), enabled = !editor.zoomLocked) { editor.zoomIn() }
        }
        ToolbarItem.FIT -> FitMenu(editor)
        ToolbarItem.ZOOM_LOCK -> ToolbarIcon(
            if (editor.zoomLocked) XnotesIcons.lock else XnotesIcons.unlock,
            stringResource(R.string.toolbar_zoom_lock),
            active = editor.zoomLocked,
        ) { editor.toggleZoomLock() }

        ToolbarItem.FULLSCREEN -> ToolbarIcon(XnotesIcons.fullscreen, stringResource(R.string.toolbar_fullscreen)) { onToggleFullscreen() }

        ToolbarItem.COLORS -> editor.toolbarColors.take(editor.toolbarColorCount).forEachIndexed { i, color ->
            Box {
                Swatch(
                    color = color.toComposeColor(),
                    active = i == editor.activeColorIndex,
                    onClick = { if (i == editor.activeColorIndex) setSwitcherIndex(i) else editor.pickColor(i) },
                )
                if (switcherIndex == i) ColorSwitcherPopup(editor, i) { setSwitcherIndex(null) }
            }
        }
    }
}

/** A stroke/edit tool button: arms the tool, and re-clicking an armed config tool opens its popup. */
@Composable
private fun ToolButton(
    editor: Editor,
    tool: Tool,
    icon: ImageVector?,
    configForTool: Tool?,
    setConfigForTool: (Tool?) -> Unit,
) {
    if (icon == null) return
    Box {
        ToolbarIcon(icon, stringResource(tool.labelRes), active = editor.tool == tool, glideKey = tool) {
            if (editor.tool == tool && (tool.isStroke || tool == Tool.SHAPE || tool == Tool.ERASER || tool == Tool.SELECT || tool == Tool.LASSO || tool == Tool.TEXT || tool == Tool.TABLE || tool == Tool.MARKUP)) {
                setConfigForTool(tool)
            } else {
                editor.selectTool(tool)
                setConfigForTool(null)
            }
        }
        if (configForTool == tool) {
            when {
                tool == Tool.SHAPE -> ShapeConfigPopup(editor) { setConfigForTool(null) }
                tool == Tool.TABLE -> TableConfigPopup(editor) { setConfigForTool(null) }
                tool == Tool.ERASER -> EraserConfigPopup(editor) { setConfigForTool(null) }
                tool == Tool.SELECT || tool == Tool.LASSO -> SelectConfigPopup(editor, tool) { setConfigForTool(null) }
                tool == Tool.TEXT -> TextToolConfigPopup(editor) { setConfigForTool(null) }
                tool == Tool.MARKUP -> MarkupToolPopup(editor) { setConfigForTool(null) }
                else -> ToolConfigPopup(editor, tool) { setConfigForTool(null) }
            }
        }
    }
}

/** Renames the open note: a small prefilled text field; the ".xnote" suffix is implicit. */
@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_note)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isBlank()) onDismiss() else onConfirm(text) }) { Text(stringResource(R.string.rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Opens the page-styles popup (paper colour + ruling) for the document and the current page. */
@Composable
private fun StylesButton(editor: Editor, onImportTemplate: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    PrewarmTemplateThumbs(editor, open)
    Box {
        ToolbarIcon(XnotesIcons.sliders, stringResource(R.string.toolbar_styles)) { open = true }
        if (open) StylesPopup(editor, onImportTemplate) { open = false }
    }
}

/** Opens the page-margins popup (extra paper on any edge, for the document or the current page). */
@Composable
private fun MarginsButton(editor: Editor) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.margins, stringResource(R.string.toolbar_margins)) { open = true }
        if (open) MarginsPopup(editor) { open = false }
    }
}

/** Opens the view menu (viewing mode, scroll direction, PDF filters, rotation, scrollbar). */
@Composable
private fun ViewButton(editor: Editor) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.view, stringResource(R.string.toolbar_view)) { open = true }
        if (open) ViewMenuPopup(editor) { open = false }
    }
}

private const val SELECT_FADE_MS = 150

/**
 * The armed-tool circle of one bar, drawn once behind the row so it can glide from the old tool to
 * the new one. Tool buttons report their centres here and the row paints the circle.
 */
internal class ToolGlide {
    val centers = mutableStateMapOf<Any, Offset>()
    var row: LayoutCoordinates? = null
}

internal val LocalToolGlide = staticCompositionLocalOf<ToolGlide?> { null }

/** One hop of the glide along the bar: where it set out from and is headed, and how far it may stretch. */
private class GlideTrip {
    var from = 0f
    var to = 0f
    /** Where the circle sits across the bar. */
    var across = 0f
    var stretch = 0f
    /** Stretch left over from a hop cut short, let go of over the new one. */
    var carry = 0f

    /** Where the circle's centre is at [t] of the way: eased in and out along a half cosine. */
    fun x(t: Float) = from + (to - from) * (1 - cos(PI * t).toFloat()) / 2

    /** How drawn out it is at [t]: in step with its speed, so it swells and settles smoothly. */
    fun drawnOut(t: Float) = stretch * sin(PI * t).toFloat() + carry * (1 + cos(PI * t).toFloat()) / 2
}

/**
 * Paints [glide]'s circle under the button keyed [armed]. On a change it eases across to the new
 * tool, drawn out in proportion to its speed: the stretch builds slowly, peaks midway and gathers
 * back into a circle as it lands.
 */
@Composable
internal fun Modifier.toolGlide(glide: ToolGlide, armed: Any?): Modifier {
    val target = glide.centers[armed]
    val density = LocalDensity.current
    val bar = LocalBar.current
    val r = with(density) { bar.circle.toPx() / 2 }
    val trip = remember { GlideTrip() }
    val progress = remember { Animatable(1f) }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (target == null) {
            shown.animateTo(0f, tween(SELECT_FADE_MS))
            return@LaunchedEffect
        }
        val t = progress.value
        val along = if (bar.vertical) target.y else target.x
        trip.carry = if (shown.value == 0f) 0f else trip.drawnOut(t)
        trip.from = if (shown.value == 0f) along else trip.x(t)
        trip.to = along
        trip.across = if (bar.vertical) target.x else target.y
        val hopDp = abs(trip.to - trip.from) / density.density
        // Longer hops stretch further, levelling off towards the cap rather than hitting it.
        trip.stretch = with(density) { GLIDE_MAX_STRETCH.toPx() } * (1 - exp(-hopDp / GLIDE_REACH_DP))
        launch { shown.animateTo(1f, tween(SELECT_FADE_MS)) }
        progress.snapTo(0f)
        progress.animateTo(1f, tween((GLIDE_BASE_MS + hopDp * GLIDE_MS_PER_DP).toInt().coerceAtMost(GLIDE_MAX_MS), easing = LinearEasing))
    }
    val fill = LocalPalette.current.selectionBackground.toComposeColor()
    return onPlaced { glide.row = it }.drawBehind {
        if (shown.value == 0f) return@drawBehind
        val t = progress.value
        val x = trip.x(t)
        val s = trip.drawnOut(t)
        // The leading side takes most of the stretch, so the tail seems to trail behind.
        val ahead = if (trip.to >= trip.from) GLIDE_LEAD else 1 - GLIDE_LEAD
        val start = x - r - s * (1 - ahead)
        drawRoundRect(
            fill.copy(alpha = fill.alpha * shown.value),
            topLeft = if (bar.vertical) Offset(trip.across - r, start) else Offset(start, trip.across - r),
            size = if (bar.vertical) Size(2 * r, 2 * r + s) else Size(2 * r + s, 2 * r),
            cornerRadius = CornerRadius(r),
        )
    }
}

private val GLIDE_MAX_STRETCH = 48.dp
private const val GLIDE_REACH_DP = 120f
private const val GLIDE_LEAD = 0.65f
private const val GLIDE_MS_PER_DP = 0.6f
private const val GLIDE_BASE_MS = 80f
private const val GLIDE_MAX_MS = 300

/**
 * A toolbar button. With a [glideKey] it is one of the bar's tools and its selection is the bar's
 * gliding circle; otherwise [active] fades in a circle of its own.
 */
@Composable
internal fun ToolbarIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    glideKey: Any? = null,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val bar = LocalBar.current
    val glide = if (glideKey != null) LocalToolGlide.current else null
    val tint by animateColorAsState(
        when {
            !enabled -> palette.disabled.toComposeColor()
            active -> palette.selectionForeground.toComposeColor()
            else -> palette.textDim.toComposeColor()
        },
        // A gliding circle takes a moment to arrive, so the icon turns with it rather than ahead of it.
        tween(if (glide != null) 2 * SELECT_FADE_MS else SELECT_FADE_MS), label = "toolTint",
    )
    val fill by animateFloatAsState(if (active && glide == null) 1f else 0f, tween(SELECT_FADE_MS), label = "toolFill")
    if (glide != null) DisposableEffect(glide, glideKey) { onDispose { glide.centers.remove(glideKey) } }
    val circle = palette.selectionBackground.toComposeColor()
    // No ripple: the circle is the feedback, and a white flash over it reads as a glitch.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.85f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "toolPress")
    Box(
        Modifier.size(bar.button).clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(bar.circle)
                .then(
                    if (glide == null || glideKey == null) Modifier
                    else Modifier.onGloballyPositioned { c ->
                        glide.row?.let { glide.centers[glideKey] = it.localPositionOf(c, Offset(c.size.width / 2f, c.size.height / 2f)) }
                    },
                )
                .drawBehind { if (fill > 0f) drawCircle(circle.copy(alpha = circle.alpha * fill)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(bar.icon).graphicsLayer { scaleX = press; scaleY = press })
        }
    }
}

/** Closes this pane of a split, leaving the other one to fill the window. Shown on both toolbars. */
@Composable
internal fun ClosePaneButton(onClose: () -> Unit) {
    Rule()
    ToolbarIcon(XnotesIcons.close, stringResource(R.string.close_pane), onClick = onClose)
}

@Composable
internal fun Swatch(color: androidx.compose.ui.graphics.Color, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .then(if (LocalBar.current.vertical) Modifier.padding(vertical = 3.dp) else Modifier.padding(horizontal = 3.dp))
            .size(LocalBar.current.swatch)
            // The selection ring is white on a dark theme and black on a light one, so it shows whatever the swatch.
            .then(if (active) Modifier.border(2.dp, if (LocalPalette.current.isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black, CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontSize = LocalBar.current.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

@Composable
internal fun Separator() {
    Box(Modifier.padding(if (LocalBar.current.vertical) PaddingValues(vertical = 4.dp) else PaddingValues(horizontal = 4.dp))) { Rule() }
}

/** A hairline across the bar. */
@Composable
private fun Rule() {
    val bar = LocalBar.current
    Box(
        Modifier
            .then(if (bar.vertical) Modifier.height(1.dp).width(bar.rule) else Modifier.width(1.dp).height(bar.rule))
            .background(LocalPalette.current.border.toComposeColor()),
    )
}

/** "3 / 12" along the bar; down a side rail, where that is too wide, the two numbers stack. */
@Composable
private fun PageCounter(current: Int, count: Int, modifier: Modifier) {
    if (!LocalBar.current.vertical) {
        Label("$current / $count", modifier)
        return
    }
    Column(modifier.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Label("$current")
        Rule()
        Label("$count")
    }
}

@Composable
private fun ImageMenu(editor: Editor, onInsertImage: () -> Unit, onTakePhoto: () -> Unit, onImagePage: () -> Unit, onAddStickers: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var stickersOpen by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.image, stringResource(R.string.tool_image)) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.paste_image)) }, onClick = { editor.pasteImage(); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.insert_image_ellipsis)) }, onClick = { onInsertImage(); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.take_photo)) }, onClick = { onTakePhoto(); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.image_as_page)) }, onClick = { onImagePage(); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.stickers)) }, onClick = { expanded = false; stickersOpen = true })
        }
        if (stickersOpen) StickersMenu(editor, onAddStickers) { stickersOpen = false }
    }
}

/**
 * The sticker library popup: a grid of saved images that insert with one tap, so a
 * recurring image never needs the gallery round trip. Stickers live on disk (see
 * [Editor.stickers]); each tile decodes its own small preview off the main thread.
 */
@Composable
private fun StickersMenu(editor: Editor, onAddStickers: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.add_stickers)) },
            leadingIcon = { Icon(XnotesIcons.plus, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = onAddStickers,
        )
        if (editor.stickers.isEmpty()) {
            Text(
                stringResource(R.string.no_stickers),
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        } else {
            // Both dimensions must be fixed: the menu measures its content by intrinsics, which a
            // lazy grid cannot answer (it crashes) — a fixed size short-circuits the query.
            val rows = ((editor.stickers.size + 2) / 3).coerceAtMost(3)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .width(72.dp * 3 + 6.dp * 2)
                    .height(72.dp * rows + 6.dp * (rows - 1)),
            ) {
                items(editor.stickers, key = { it.name }) { file ->
                    StickerTile(
                        file = file,
                        onInsert = { editor.insertSticker(file); onDismiss() },
                        onRemove = { editor.removeSticker(file) },
                    )
                }
            }
            Text(
                stringResource(R.string.stickers_hint),
                color = palette.textDim.toComposeColor(),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerTile(file: java.io.File, onInsert: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    val thumbPx = with(LocalDensity.current) { 72.dp.roundToPx() }
    val thumb by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            ImageDecoder.decodeSampledFile(file.path, thumbPx, thumbPx)?.asImageBitmap()
        }
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
            .combinedClickable(onClick = onInsert, onLongClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.sticker),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(3.dp),
            )
        }
    }
}

@Composable
private fun FitMenu(editor: Editor) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.fit, stringResource(R.string.toolbar_fit)) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.fit_page)) }, onClick = { editor.fitPage(); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.fit_width)) }, onClick = { editor.fitWidth(); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.fit_height)) }, onClick = { editor.fitHeight(); expanded = false })
        }
    }
}

