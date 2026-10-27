package com.xnotes.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.xnotes.R
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.Rgba
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * What the selection menu needs from whichever editor is open.
 *
 * The bar is the same bar on either canvas, so it is the same composable rather than a second one
 * that resembles it. Taking the few members it reads through an interface is what makes "identical"
 * a property of the code rather than something to keep checking, exactly as [ToolPopupHost] does
 * for the tool popups.
 */
interface SelectionMenuHost : SwatchHost {
    /** Where the settled selection sits in viewport pixels, or null to hide the bar. */
    val selectionMenuRect: com.xnotes.core.geometry.Rect?

    fun deleteSelection()
    fun cutSelection()
    fun copySelection()
    /** Move the selection up or down the stack of items it sits in, as one undo step. */
    fun arrange(how: com.xnotes.core.model.Arrange)
    fun duplicateSelection()

    /** The audio pill selected on its own, which the bar offers to play. */
    fun selectedAudio(): com.xnotes.core.model.AudioItem?

    /** Play or pause [selectedAudio]: the way to play it when a finger draws rather than pans. */
    fun playSelectedAudio()

    /** Give [selectedAudio] a new name, undoably. */
    fun renameSelectedAudio(name: String)

    /** Untie the ink written during [selectedAudio] from it, undoably, with Undo on the message. */
    fun unlinkSelectedAudio()
    fun dismissSelectionMenu()

    /** Pin the selection where it is and put it away; a held finger over it offers to release it. */
    fun lockSelection()

    /** The colour and width of every selected stroke/shape, for the restyle popup to open on. */
    fun selectionStyles(): List<DrawStyle>

    /**
     * Recolour and/or re-thicken the selection; a null [color] or [width] leaves that half alone.
     * A [preview] call skips history, and the next call without it records everything since as one
     * undo step, so dragging the thickness slider is a single edit rather than one per sample.
     */
    fun restyleSelection(color: Rgba?, width: Double?, preview: Boolean = false)

    /** The fills of the selected shapes that have one, for the restyle popup to open on. */
    fun selectionFills(): List<Rgba>

    /** The first selected tape's pattern, for the restyle popup to open on; null when no tape is selected. */
    fun selectionTapePattern(): com.xnotes.core.model.TapePattern?

    /** Print [pattern] on every selected tape, as one undo step. */
    fun setSelectionTapePattern(pattern: com.xnotes.core.model.TapePattern)

    /**
     * Recolour the selected shapes' fills in [color], each keeping its opacity; null fills each in
     * its outline's colour. A [preview] call skips history, and the next call without it records
     * everything since as one undo step, so a drag through the picker is a single edit.
     */
    fun refillSelection(color: Rgba?, preview: Boolean = false)

    /** A lone selected star's points or polygon's sides and the counts it can take; null otherwise. */
    fun selectionCount(): ShapeCount?

    /** Set that count, as one undo step. */
    fun setSelectionCount(n: Int)

    /** True when the lone selection is a shape that can take a typed label. */
    fun canLabelSelection(): Boolean

    /** Open the editor on that shape's label. */
    fun labelSelection()

    /** True when the lone selection is a connector whose route can be planned afresh. */
    fun canRerouteSelection(): Boolean

    /** Plan that connector's route afresh round what is in its way, as one undo step. */
    fun rerouteSelection()

    /** True when the selection can be mirrored: it holds no text, which would read backwards. */
    fun canMirrorSelection(): Boolean

    /** Mirror the selection about its own centre, left to right or [upsideDown], as one undo step. */
    fun mirrorSelection(upsideDown: Boolean)

    /** Put the selection, drawn alone over what lies beneath it and cut to its bounds, on the clipboard as a picture. */
    fun copySelectionAsImage()

    /** That picture as a PNG ready for the share sheet, or null (with a message) when it could not be made. */
    fun selectionImageForShare(): java.io.File?

    /** True when the selection is two or more items not already one group. */
    fun canGroupSelection(): Boolean

    /** True when the selection holds a group. */
    fun canUngroupSelection(): Boolean

    /** Put the selection in one group, any groups in it merged, as one undo step. */
    fun groupSelection()

    /** Free every item of the groups in the selection, as one undo step. */
    fun ungroupSelection()

    /** True when the selection is a lone picture, which the bar offers to crop. */
    fun canCropSelection(): Boolean

    /** Put that picture in crop mode, the frame standing over it. */
    fun startCrop()
}

/**
 * Floating action bar shown above a settled selection (spec-adjacent): delete,
 * cut, copy, arrange, duplicate, and an overflow menu for the rest.
 * Hidden while moving/resizing. Rotation is not here: everything the bar can be
 * shown over turns by its own grip.
 */
@Composable
fun SelectionMenu(
    host: SelectionMenuHost,
    onSaveAudio: ((com.xnotes.core.model.AudioItem) -> Unit)? = null,
    onShareAudio: ((com.xnotes.core.model.AudioItem) -> Unit)? = null,
    onShareImage: ((java.io.File) -> Unit)? = null,
) {
    val rect = host.selectionMenuRect ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var overflowOpen by remember { mutableStateOf(false) }
    var arrangeOpen by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var renamingAudio by remember { mutableStateOf<com.xnotes.core.model.AudioItem?>(null) }

    val audio = host.selectedAudio()
    val crops = host.canCropSelection()
    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { ((6 + (if (audio != null) 1 else 0) + (if (crops) 1 else 0)) * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val barAbove = rect.top.toFloat() - barHeightPx - gap > 0f
    val yPx = if (barAbove) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + gap
    }
    val xDp = with(density) { xPx.toDp() }
    val yDp = with(density) { yPx.toDp() }

    CompositionLocalProvider(LocalTipSide provides tipSideFor(barAbove)) {
        Row(
            modifier = Modifier
                .offset(xDp, yDp)
                .clip(MaterialTheme.shapes.medium)
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
        ) {
            if (audio != null) {
                val playing = AudioPlayback.session?.let { it.item === audio && it.playing } == true
                ActionIcon(if (playing) XnotesIcons.pause else XnotesIcons.play, stringResource(if (playing) R.string.audio_pause else R.string.audio_play)) { host.playSelectedAudio() }
            }
            if (crops) ActionIcon(XnotesIcons.crop, stringResource(R.string.crop)) { host.startCrop() }
            ActionIcon(XnotesIcons.trash, stringResource(R.string.delete), shortcut = Keys.DELETE) { host.deleteSelection() }
            ActionIcon(XnotesIcons.cut, stringResource(R.string.cut)) { host.cutSelection() }
            ActionIcon(XnotesIcons.copy, stringResource(R.string.copy)) { host.copySelection(); host.dismissSelectionMenu() }
            Box {
                ActionIcon(XnotesIcons.front, stringResource(R.string.arrange)) { arrangeOpen = true }
                DropdownMenu(
                    expanded = arrangeOpen,
                    onDismissRequest = { arrangeOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    for ((how, label) in ARRANGE_ROWS) {
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { arrangeOpen = false; host.arrange(how) })
                    }
                }
            }
            ActionIcon(XnotesIcons.duplicate, stringResource(R.string.duplicate)) { host.duplicateSelection() }
            Box {
                ActionIcon(XnotesIcons.more, stringResource(R.string.more)) { overflowOpen = true }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    // A lone recording's own actions come first.
                    if (audio != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { overflowOpen = false; renamingAudio = audio },
                        )
                        onSaveAudio?.let { save ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.audio_save)) },
                                onClick = { overflowOpen = false; save(audio) },
                            )
                        }
                        onShareAudio?.let { share ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.audio_share)) },
                                onClick = { overflowOpen = false; share(audio) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.audio_unlink)) },
                            onClick = { overflowOpen = false; host.unlinkSelectedAudio() },
                        )
                        androidx.compose.material3.HorizontalDivider(color = palette.border.toComposeColor())
                    }
                    // Empty when nothing selected carries ink or a fill: an image or a text box has
                    // no colour-and-width pair to restyle.
                    val styles = host.selectionStyles()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.change_style)) },
                        enabled = styles.isNotEmpty() || host.selectionFills().isNotEmpty(),
                        onClick = { overflowOpen = false; styleOpen = true },
                    )
                    if (host.canLabelSelection()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shape_label)) },
                            onClick = { overflowOpen = false; host.labelSelection() },
                        )
                    }
                    if (host.canRerouteSelection()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.connector_reroute)) },
                            onClick = { overflowOpen = false; host.rerouteSelection() },
                        )
                    }
                    val mirrors = host.canMirrorSelection()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mirror_left_right)) },
                        enabled = mirrors,
                        onClick = { overflowOpen = false; host.mirrorSelection(upsideDown = false) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mirror_upside_down)) },
                        enabled = mirrors,
                        onClick = { overflowOpen = false; host.mirrorSelection(upsideDown = true) },
                    )
                    if (host.canGroupSelection()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group)) },
                            onClick = { overflowOpen = false; host.groupSelection() },
                        )
                    }
                    if (host.canUngroupSelection()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ungroup)) },
                            onClick = { overflowOpen = false; host.ungroupSelection() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_as_image)) },
                        onClick = { overflowOpen = false; host.copySelectionAsImage() },
                    )
                    onShareImage?.let { share ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_as_image)) },
                            onClick = { overflowOpen = false; host.selectionImageForShare()?.let(share) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lock)) },
                        onClick = { overflowOpen = false; host.lockSelection() },
                    )
                }
                if (styleOpen) {
                    // Closing settles any preview the slider left open.
                    SelectionStylePopup(host) { host.restyleSelection(null, null); styleOpen = false }
                }
                renamingAudio?.let { item ->
                    AudioNameDialog(item.name, onConfirm = { renamingAudio = null; host.renameSelectedAudio(it) }) { renamingAudio = null }
                }
            }
        }
    }
}

/** The Arrange menu's rows, top of the stack first. */
private val ARRANGE_ROWS = listOf(
    com.xnotes.core.model.Arrange.FRONT to R.string.bring_to_front,
    com.xnotes.core.model.Arrange.FORWARD to R.string.bring_forward,
    com.xnotes.core.model.Arrange.BACKWARD to R.string.send_backward,
    com.xnotes.core.model.Arrange.BACK to R.string.send_to_back,
)

/** Rename a recording: its name, ready to type over. */
@Composable
private fun AudioNameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_recording)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.audio_name)) },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.text.isBlank()) onDismiss() else onConfirm(text.text) }) { Text(stringResource(R.string.rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * "Change style" on a settled selection: the toolbar's swatches plus the full colour picker, and a
 * thickness slider. Both apply live, so the result can be judged on the page rather than guessed
 * at. The controls open on the styles the selection had when the popup did — a shared colour when
 * every item agrees, the first item's otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionStylePopup(host: SelectionMenuHost, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val opened = remember { host.selectionStyles() }
    val first = opened.firstOrNull()
    val shared = opened.firstOrNull()?.color?.takeIf { c -> opened.all { it.color == c } }
    var color by remember { mutableStateOf(shared ?: first?.color ?: Rgba(0, 0, 0)) }
    var width by remember { mutableStateOf((first?.width ?: 1.0).toFloat()) }
    val fills = remember { host.selectionFills() }
    // Only dropped colours selected: they have a fill and nothing else to restyle.
    val fillsOnly = first == null
    if (fillsOnly && fills.isEmpty()) return

    DropdownMenu(expanded = true, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_change_style))
            if (!fillsOnly) StyleCaption(stringResource(R.string.caption_colour))
            if (!fillsOnly) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                host.hostToolbarColors.forEach { c ->
                    ColorDot(c.toComposeColor(), selected = color == c) {
                        color = c
                        host.restyleSelection(c, null)
                    }
                }
                ColorPickerDot(
                    color,
                    custom = color !in host.hostToolbarColors,
                    onPick = { color = it; host.restyleSelection(it, null) },
                    dismissOnPick = false,
                ) { dismiss, pick ->
                    ColorPickerPopup(
                        initial = color,
                        recents = host.hostRecentColors,
                        onDismiss = dismiss,
                        onPick = pick,
                    )
                }
            }
            host.selectionTapePattern()?.let { opened ->
                var pattern by remember { mutableStateOf(opened) }
                StyleCaption(stringResource(R.string.caption_pattern))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in com.xnotes.core.model.TapePattern.entries) {
                        ModeChip(stringResource(p.labelRes), selected = pattern == p) {
                            pattern = p
                            host.setSelectionTapePattern(p)
                        }
                    }
                }
            }
            if (fills.isNotEmpty()) {
                var fill by remember { mutableStateOf<Rgba?>(fills.first().copy(a = 255)) }
                var editingFill by remember { mutableStateOf<Int?>(null) }
                val outline = color
                StyleCaption(stringResource(R.string.caption_fill_colour))
                // The toolbar's own swatches; tapping the chosen one again opens the picker on it, as the toolbar does.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!fillsOnly) {
                        MatchDot(outline, selected = fill == null || fill == outline) {
                            fill = null
                            host.refillSelection(null)
                        }
                    }
                    host.shownSwatches.forEachIndexed { i, c ->
                        val on = fill == c
                        Box {
                            ColorDot(c.toComposeColor(), selected = on) {
                                if (on) {
                                    editingFill = i
                                } else {
                                    fill = c
                                    host.refillSelection(c)
                                }
                            }
                            if (editingFill == i) {
                                // However the picker closes, the drag through it settles as one undo step.
                                val latest by rememberUpdatedState(fill)
                                DisposableEffect(Unit) { onDispose { host.refillSelection(latest) } }
                                ColorPickerPopup(
                                    initial = c,
                                    recents = host.hostRecentColors,
                                    onDismiss = {
                                        host.rememberSwatchColor(i)
                                        editingFill = null
                                    },
                                    onPick = { picked ->
                                        host.recolorSwatch(i, picked)
                                        fill = picked
                                        host.refillSelection(picked, preview = true)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            host.selectionCount()?.let { sc ->
                val count = sc.n
                val range = sc.range
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StyleCaption(stringResource(countLabel(sc.kind)))
                    Spacer(Modifier.width(12.dp))
                    StepDot("\u2212", stringResource(R.string.count_fewer), enabled = count > range.first) { host.setSelectionCount(count - 1) }
                    Text("$count", color = palette.text.toComposeColor(), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    StepDot("+", stringResource(R.string.count_more), enabled = count < range.last) { host.setSelectionCount(count + 1) }
                }
            }
            if (fillsOnly) return@Column
            Spacer(Modifier.size(8.dp))
            // The drag previews live and commits on release, so it is one undo step, not fifty.
            SliderRow(
                stringResource(R.string.caption_thickness),
                width,
                DrawStyle.MIN_WIDTH.toFloat()..DrawStyle.MAX_WIDTH.toFloat(),
                onChangeFinished = { host.restyleSelection(null, null) },
            ) { w ->
                width = w
                host.restyleSelection(null, w.toDouble(), preview = true)
            }
            Text(
                stringResource(R.string.change_style_hint),
                color = palette.textDim.toComposeColor(),
                fontSize = 11.sp,
            )
        }
    }
}

/** A star's [n] points, a polygon's [n] sides and so on ([kind] says which), and the counts it can take. */
class ShapeCount(val n: Int, val range: IntRange, val kind: com.xnotes.core.shapes.CountKind) {
    companion object {
        fun of(shape: com.xnotes.core.model.ShapeItem?): ShapeCount? {
            val s = shape ?: return null
            val range = s.shape.nRange ?: return null
            return ShapeCount(s.n.coerceIn(range.first, range.last), range, s.shape.countKind)
        }
    }
}

/** "Match outline" for a fill: the outline's colour with a link over it. */
@Composable
private fun MatchDot(outline: Rgba, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(stringResource(R.string.fill_match_outline)) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(outline.toComposeColor())
                .border(if (selected) 2.dp else 1.dp, (if (selected) palette.accent else palette.border).toComposeColor(), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (outline.luminance() > 0.55) Rgba(0, 0, 0) else Rgba(255, 255, 255)
            Icon(XnotesIcons.link, contentDescription = null, tint = tint.toComposeColor(), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun StepDot(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(label) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).border(1.dp, palette.border.toComposeColor(), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = (if (enabled) palette.text else palette.textDim).toComposeColor(), fontSize = 16.sp)
        }
    }
}

/**
 * The screenshot tool's floating action, shown above the frozen capture rectangle: a single
 * "Copy as image" button that renders the region and puts it on the system clipboard.
 */
@Composable
fun ScreenshotMenu(editor: Editor, onShare: (java.io.File) -> Unit) {
    val rect = editor.screenshotMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    val barHeightPx = with(density) { 44.dp.toPx() }
    val barWidthPx = with(density) { 216.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + gap
    }
    Row(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clickable { editor.copyScreenshotAsImage() }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                XnotesIcons.copy,
                contentDescription = stringResource(R.string.copy_as_image),
                tint = palette.text.toComposeColor(),
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.copy_as_image),
                color = palette.text.toComposeColor(),
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(Modifier.width(1.dp).height(24.dp).background(palette.border.toComposeColor()))
        ActionIcon(XnotesIcons.share, stringResource(R.string.share)) { editor.screenshotImageForShare()?.let(onShare) }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, desc: String, enabled: Boolean = true, shortcut: String? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val tint = palette.text.toComposeColor().let { if (enabled) it else it.copy(alpha = 0.35f) }
    Tooltip(desc, shortcut = shortcut) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(46.dp)) {
            Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

/** A bar placed beside what it acts on opens its tips away from it, on the same side as itself. */
private fun tipSideFor(barAbove: Boolean) = if (barAbove) TipSide.ABOVE else TipSide.BELOW

/**
 * What the long-press menu needs from whichever editor is open. Same reasoning as
 * [SelectionMenuHost]: one menu, taken through an interface, rather than two that resemble each
 * other and drift.
 */
interface LongPressMenuHost {
    /** Where the press landed, or null when no menu is open. */
    val contextMenu: ContextMenuTarget?

    val hasClipboardItems: Boolean
    val clipboardHasImage: Boolean

    fun pasteItemsAt(content: com.xnotes.core.geometry.Pt)
    fun pasteClipboardImageAt(content: com.xnotes.core.geometry.Pt)
    fun dismissContextMenu()

    /** Release [item], so it can be selected again. */
    fun unlockItem(item: com.xnotes.core.model.CanvasItem)
}

/**
 * Long-press menu: paste copied items or an image from the system clipboard at the press point, or
 * insert an image there. A press that landed on a locked item offers only to release it, since
 * nothing else can be done with one and there is no other way back.
 */
@Composable
fun LongPressMenu(host: LongPressMenuHost, onInsertImageAt: (com.xnotes.core.geometry.Pt) -> Unit) {
    val target = host.contextMenu ?: return
    val density = LocalDensity.current
    val xDp = with(density) { target.viewportX.toFloat().toDp() }
    val yDp = with(density) { target.viewportY.toFloat().toDp() }

    Box(modifier = Modifier.offset(xDp, yDp).size(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = { host.dismissContextMenu() }) {
            val locked = target.locked
            if (locked != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.unlock)) }, onClick = {
                    host.unlockItem(locked); host.dismissContextMenu()
                })
                return@DropdownMenu
            }
            if (host.hasClipboardItems) {
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_here)) }, onClick = {
                    host.pasteItemsAt(target.content); host.dismissContextMenu()
                })
            }
            if (host.clipboardHasImage) {
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_image)) }, onClick = {
                    host.pasteClipboardImageAt(target.content); host.dismissContextMenu()
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.insert_image_ellipsis)) }, onClick = {
                onInsertImageAt(target.content); host.dismissContextMenu()
            })
        }
    }
}

/**
 * The flow-editing action bar (long press with the text tool, after the word
 * selection lands): a thin icon row like [SelectionMenu], anchored above the
 * selection so the drag handles stay visible. Not a popup: it never steals
 * focus (the keyboard stays up) and any canvas touch quietly retires it. The
 * paste icon expands the explicit paste modes (no auto-detection anywhere).
 */
@Composable
fun FlowEditMenu(editor: Editor) {
    val rect = editor.flowContextMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val hasClip = editor.clipboardHasText()
    val hasSelection = editor.flowHasSelection
    var pasteOpen by remember { mutableStateOf(false) }

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (4 * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    // When pushed below the selection, also clear the teardrop handles hanging there.
    val handleClearance = with(density) { (2 * com.xnotes.canvas.TextHandles.RADIUS_DP).dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val barAbove = rect.top.toFloat() - barHeightPx - gap > 0f
    val yPx = if (barAbove) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + handleClearance + gap
    }

    CompositionLocalProvider(LocalTipSide provides tipSideFor(barAbove)) {
        Row(
            modifier = Modifier
                .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
                .clip(MaterialTheme.shapes.medium)
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
        ) {
            ActionIcon(XnotesIcons.cut, stringResource(R.string.cut), enabled = hasSelection, shortcut = Keys.CUT) {
                editor.flowCut(); editor.dismissFlowContextMenu()
            }
            ActionIcon(XnotesIcons.copy, stringResource(R.string.copy), enabled = hasSelection, shortcut = Keys.COPY) {
                editor.flowCopy(); editor.dismissFlowContextMenu()
            }
            Box {
                ActionIcon(XnotesIcons.paste, stringResource(R.string.paste), enabled = hasClip) { pasteOpen = true }
                DropdownMenu(
                    expanded = pasteOpen,
                    onDismissRequest = { pasteOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.paste)) }, onClick = {
                        pasteOpen = false; editor.pastePlainAtCaret(); editor.dismissFlowContextMenu()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.paste_markdown)) }, onClick = {
                        pasteOpen = false; editor.pasteMarkdownAtCaret(); editor.dismissFlowContextMenu()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.paste_code)) }, onClick = {
                        pasteOpen = false; editor.pasteAsCodeAtCaret(); editor.dismissFlowContextMenu()
                    })
                }
            }
            ActionIcon(XnotesIcons.trash, stringResource(R.string.delete), enabled = hasSelection, shortcut = Keys.DELETE) {
                editor.flowDeleteSelection(); editor.dismissFlowContextMenu()
            }
        }
    }
}

/**
 * The PDF text selection's bar: Copy, the four marks (made in the active ink colour), and behind
 * the overflow the apps that act on selected text ("Translate", "Search" and the like). Never takes
 * focus; a canvas touch hides it until the selection, or the view under it, settles again.
 */
@Composable
fun PdfSelectionMenu(editor: Editor) {
    val rect = editor.pdfTextMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val actions = remember { editor.pdfTextActions() }
    var overflowOpen by remember { mutableStateOf(false) }

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { ((1 + PDF_MARKS.size + if (actions.isEmpty()) 0 else 1) * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    // When pushed below the selection, also clear the teardrop handles hanging there.
    val handleClearance = with(density) { (2 * com.xnotes.canvas.TextHandles.RADIUS_DP).dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(margin)
    val above = rect.top.toFloat() - barHeightPx - gap
    val below = rect.bottom.toFloat() + handleClearance + gap
    // A selection taller than the view leaves no room either side: keep the bar on screen.
    val maxY = (editor.state.viewportH - barHeightPx - margin).coerceAtLeast(margin)
    val yPx = (if (above > 0f) above else below).coerceIn(margin, maxY)
    val barAbove = above > 0f

    CompositionLocalProvider(LocalTipSide provides tipSideFor(barAbove)) {
        Row(
            modifier = Modifier
                .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
                .clip(MaterialTheme.shapes.medium)
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
        ) {
            ActionIcon(XnotesIcons.copy, stringResource(R.string.copy), shortcut = Keys.COPY) { editor.copyPdfText() }
            for ((type, icon, label) in PDF_MARKS) {
                ActionIcon(icon, stringResource(label)) { editor.markPdfSelection(type) }
            }
            if (actions.isNotEmpty()) {
                Box {
                    ActionIcon(XnotesIcons.more, stringResource(R.string.more)) { overflowOpen = true }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                        properties = PopupProperties(focusable = false),
                    ) {
                        for ((label, app) in actions) {
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                overflowOpen = false
                                editor.processPdfText(app)
                            })
                        }
                    }
                }
            }
        }
    }
}

/**
 * A markup's tap menu: its colour and kind (each a small list), its note, Copy (the text it marks),
 * Delete, and Open link when the tap was on a link. Placed like the selection bar, by the markup.
 */
@Composable
fun MarkupMenu(editor: Editor) {
    val menu = editor.markupMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var colorsOpen by remember { mutableStateOf(false) }
    var kindsOpen by remember { mutableStateOf(false) }
    val icons = 5 + if (menu.openLink != null) 1 else 0
    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (icons * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val rect = menu.anchor
    val xPx = (((rect.left + rect.right) / 2.0).toFloat() - barWidthPx / 2f).coerceAtLeast(margin)
    val above = rect.top.toFloat() - barHeightPx - gap
    val maxY = (editor.state.viewportH - barHeightPx - margin).coerceAtLeast(margin)
    val yPx = (if (above > 0f) above else rect.bottom.toFloat() + gap).coerceIn(margin, maxY)
    val barAbove = above > 0f
    val m = menu.markup

    CompositionLocalProvider(LocalTipSide provides tipSideFor(barAbove)) {
        Row(
            modifier = Modifier
                .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
                .clip(MaterialTheme.shapes.medium)
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                val colourLabel = stringResource(R.string.markup_colour)
                Tooltip(colourLabel) {
                    IconButton(onClick = { colorsOpen = true }, modifier = Modifier.size(46.dp).semantics { contentDescription = colourLabel }) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(m.color.toComposeColor()))
                    }
                }
                DropdownMenu(expanded = colorsOpen, onDismissRequest = { colorsOpen = false }, properties = PopupProperties(focusable = false)) {
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        editor.toolbarColors.take(editor.toolbarColorCount).forEach { c ->
                            MarkupDot(c.toComposeColor(), active = c.r == m.color.r && c.g == m.color.g && c.b == m.color.b) {
                                colorsOpen = false
                                editor.recolorMarkup(c)
                            }
                        }
                    }
                }
            }
            Box {
                ActionIcon(markIcon(m.type), stringResource(R.string.markup_kind)) { kindsOpen = true }
                DropdownMenu(expanded = kindsOpen, onDismissRequest = { kindsOpen = false }, properties = PopupProperties(focusable = false)) {
                    for ((type, icon, label) in PDF_MARKS) {
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                kindsOpen = false
                                if (type != m.type) editor.retypeMarkup(type)
                            },
                        )
                    }
                }
            }
            ActionIcon(XnotesIcons.note, stringResource(R.string.markup_note)) { editor.editMarkupNote() }
            ActionIcon(XnotesIcons.copy, stringResource(R.string.copy)) { editor.copyMarkupText() }
            ActionIcon(XnotesIcons.trash, stringResource(R.string.delete)) { editor.deleteMarkup() }
            if (menu.openLink != null) ActionIcon(XnotesIcons.link, stringResource(R.string.open_link)) { editor.openMarkupLink() }
        }
    }
}

/**
 * A markup's note, shown by a first tap on it: placed as its menu would be, then moving with the
 * page, one size at any zoom. It takes no focus and leaves the page working around it; on itself it
 * takes touches, so a long note scrolls. It goes once scrolled out of view.
 */
@Composable
fun MarkupNotePeek(editor: Editor) {
    val peek = editor.notePeek ?: return
    val note = peek.markup.note ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val scroll = remember(peek) { ScrollState(0) }
    val maxHeight = with(density) { (editor.state.viewportH * NOTE_PEEK_MAX_HEIGHT).toDp() }.coerceAtMost(240.dp)
    Box(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                editor.notePeekTick
                editor.notePeekLaidOut(p.width, p.height)
                val at = editor.notePeekRect(p.width, p.height)
                layout(p.width, p.height) {
                    if (at != null) p.place(at.left.roundToInt(), at.top.roundToInt())
                }
            }
            .widthIn(max = 300.dp)
            .heightIn(max = maxHeight)
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(note, style = MaterialTheme.typography.bodyMedium, color = palette.text.toComposeColor())
    }
}

/** The most of the view's height a markup's note window takes before it scrolls. */
private const val NOTE_PEEK_MAX_HEIGHT = 0.4f

/** A markup's note to read or write: plain text, kept on Save, dropped when emptied or deleted. */
@Composable
fun MarkupNoteDialog(editor: Editor) {
    val target = editor.markupNote ?: return
    var text by remember(target) { mutableStateOf(target.markup.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = { editor.dismissMarkupNote() },
        title = { Text(stringResource(R.string.markup_note)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.markup_note_hint)) },
                minLines = 3,
                maxLines = 10,
            )
        },
        confirmButton = { TextButton(onClick = { editor.saveMarkupNote(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (target.markup.note != null) {
                    TextButton(onClick = { editor.saveMarkupNote("") }) { Text(stringResource(R.string.delete)) }
                }
                TextButton(onClick = { editor.dismissMarkupNote() }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun MarkupDot(color: Color, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .then(if (active) Modifier.border(2.dp, color, CircleShape) else Modifier)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

private fun markIcon(type: com.xnotes.core.model.MarkupType): ImageVector = PDF_MARKS.first { it.first == type }.second

/** The selection bar's marks, in the order the markup tool's popup lists them. */
private val PDF_MARKS = listOf(
    Triple(com.xnotes.core.model.MarkupType.HIGHLIGHT, XnotesIcons.highlight, R.string.markup_highlight),
    Triple(com.xnotes.core.model.MarkupType.UNDERLINE, XnotesIcons.underline, R.string.underline),
    Triple(com.xnotes.core.model.MarkupType.STRIKEOUT, XnotesIcons.strikethrough, R.string.strikethrough),
    Triple(com.xnotes.core.model.MarkupType.SQUIGGLY, XnotesIcons.squiggly, R.string.markup_squiggly),
)

private const val TABLE_BAR_ICONS = 4

/**
 * Where the table bar sits in viewport px: centred over the top of the table's
 * first slice and kept on screen (it overlaps the table rather than leave the
 * top of the view). Null when the table is not laid out.
 */
private fun tableBarRect(editor: Editor, table: com.xnotes.core.text.FlowTable, density: androidx.compose.ui.unit.Density): androidx.compose.ui.geometry.Rect? {
    editor.tableChromeTick
    val (pi, frag) = editor.tableFrags(table).firstOrNull() ?: return null
    val t = editor.pageRectToViewport(pi, frag.rect) ?: return null
    val w = with(density) { (TABLE_BAR_ICONS * 46).dp.toPx() }
    val h = with(density) { 48.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val x = (((t.left + t.right) / 2.0).toFloat() - w / 2f).coerceAtLeast(margin)
    val y = (t.top.toFloat() - h - gap).coerceAtLeast(margin)
    return androidx.compose.ui.geometry.Rect(x, y, x + w, y + h)
}

/**
 * The table's action bar: edit its structure, fit its columns (the magic wand),
 * restyle it, delete it. Opens the moment a long press holds the table itself
 * (a rule, padding, empty cell space), above the table whatever part was
 * pressed. Like the text bar it never takes focus; the next canvas touch
 * retires it.
 */
@Composable
fun FlowTableMenu(editor: Editor) {
    val table = editor.tableMenu ?: return
    if (editor.editingTable != null) return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val bar = tableBarRect(editor, table, density) ?: return
    // The bar sits over the table's top edge, so its tips open upward, off the table.
    CompositionLocalProvider(LocalTipSide provides TipSide.ABOVE) {
        Row(
            modifier = Modifier
                .offset(with(density) { bar.left.toDp() }, with(density) { bar.top.toDp() })
                .clip(MaterialTheme.shapes.medium)
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
        ) {
            ActionIcon(XnotesIcons.tableEdit, stringResource(R.string.edit_table)) { editor.startTableEdit(table) }
            ActionIcon(XnotesIcons.magicWand, stringResource(R.string.fit_columns)) { editor.tableAutoFit(table) }
            ActionIcon(Icons.Outlined.TableChart, stringResource(R.string.table_style)) { editor.openTableStyle(table) }
            ActionIcon(Icons.Outlined.GridOff, stringResource(R.string.delete_table)) { editor.tableDelete(table) }
        }
    }
}
