package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.settings.ExplorerLayout
import com.xnotes.settings.ExplorerSortKey
import com.xnotes.settings.ExplorerView
import com.xnotes.settings.FolderPlacement
import com.xnotes.settings.GroupBy
import com.xnotes.settings.ThumbShape
import com.xnotes.settings.TileSize
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

internal fun layoutIcon(l: ExplorerLayout): ImageVector = when (l) {
    ExplorerLayout.GRID -> XnotesIcons.layoutGrid
    ExplorerLayout.GALLERY -> XnotesIcons.gallery
    ExplorerLayout.LIST -> XnotesIcons.list
    ExplorerLayout.COLUMNS -> XnotesIcons.columns
    ExplorerLayout.TIMELINE -> XnotesIcons.timeline
}

@Composable
internal fun sortLabel(k: ExplorerSortKey): String = when (k) {
    ExplorerSortKey.NAME -> stringResource(R.string.sort_name)
    ExplorerSortKey.MODIFIED -> stringResource(R.string.sort_modified)
    ExplorerSortKey.CREATED -> stringResource(R.string.sort_created)
    ExplorerSortKey.SIZE -> stringResource(R.string.sort_size)
}

/** Which way a sort runs, in words that fit its field: A to Z, Newest first, Largest first. */
@Composable
internal fun directionLabel(k: ExplorerSortKey, descending: Boolean): String = when (k) {
    ExplorerSortKey.NAME -> if (descending) stringResource(R.string.sort_z_to_a) else stringResource(R.string.sort_a_to_z)
    ExplorerSortKey.SIZE -> if (descending) stringResource(R.string.largest_first) else stringResource(R.string.smallest_first)
    else -> if (descending) stringResource(R.string.newest_first) else stringResource(R.string.oldest_first)
}

/** A toggle or menu chip in the explorer's chip row and View options; [on] fills it with the accent, and without [labelled] only its icons show. */
@Composable
internal fun ExplorerChip(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: ImageVector? = null,
    labelled: Boolean = true,
    onClick: () -> Unit,
) {
    // Down to its icon, a chip names itself in a tip.
    if (labelled) ChipBody(label, on, modifier, icon, trailing, labelled, onClick)
    else Tooltip(label) { ChipBody(label, on, modifier, icon, trailing, labelled, onClick) }
}

@Composable
private fun ChipBody(
    label: String,
    on: Boolean,
    modifier: Modifier,
    icon: ImageVector?,
    trailing: ImageVector?,
    labelled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val fg = (if (on) palette.selectionForeground else palette.text).toComposeColor()
    val iconTint = (if (on) palette.selectionForeground else palette.textDim).toComposeColor()
    Row(
        modifier
            .height(32.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (on) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (on) palette.accent.toComposeColor() else palette.border.toComposeColor(), MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(start = if (icon != null) 8.dp else 12.dp, end = if (trailing != null || !labelled) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, if (labelled) null else label, tint = iconTint, modifier = Modifier.size(16.dp))
        if (labelled) Text(label, color = fg, fontSize = 13.5.sp, maxLines = 1, softWrap = false)
        if (trailing != null) Icon(trailing, null, tint = iconTint, modifier = Modifier.size(14.dp))
    }
}

/** A chip row that shows labels only when they all fit, icons alone when they don't, and scrolls if even those overflow. */
@Composable
internal fun FittedChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.(labelled: Boolean) -> Unit) {
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            SubcomposeLayout { c ->
                val loose = Constraints(maxHeight = c.maxHeight)
                fun pass(labelled: Boolean) = subcompose(labelled) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { content(labelled) }
                }.map { it.measure(loose) }
                // The labelled pass is always measured; when it's too wide it stays composed but is never placed.
                val full = pass(true)
                val shown = if ((full.maxOfOrNull { it.width } ?: 0) <= room) full else pass(false)
                val w = shown.maxOfOrNull { it.width } ?: 0
                val h = shown.maxOfOrNull { it.height } ?: 0
                layout(w, h) { shown.forEach { it.placeRelative(0, (h - it.height) / 2) } }
            }
        }
    }
}

/** Height of the explorer's header, which floats over the files once they scroll under it. */
internal val EXPLORER_HEADER = 56.dp

/** Keeps a pointer from reaching whatever lies under this element, without consuming it. */
private val BlockPointer = Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

/** A floating control's own surface, edge and shadow, faded in by [lift] (0 to 1) while files scroll under it. */
internal fun Modifier.floatingBacking(lift: () -> Float, shape: Shape, fill: Color, edge: Color, shadow: () -> Float = lift): Modifier = this
    .graphicsLayer {
        shadowElevation = 3.dp.toPx() * shadow()
        this.shape = shape
        clip = false
    }
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(1.dp.toPx())
        onDrawBehind {
            val l = lift()
            if (l > 0f) {
                drawOutline(outline, fill.copy(alpha = fill.alpha * l))
                drawOutline(outline, edge.copy(alpha = edge.alpha * l), style = stroke)
            }
        }
    }
    .then(BlockPointer)

/** Side padding that grows from nothing to [pad] with [lift], giving a floating pill room for its rounded ends. */
internal fun Modifier.liftPadding(lift: () -> Float, pad: Dp): Modifier = layout { measurable, constraints ->
    val p = (pad.toPx() * lift()).roundToInt()
    val placeable = measurable.measure(constraints.offset(horizontal = -2 * p))
    layout(placeable.width + 2 * p, placeable.height) { placeable.place(p, 0) }
}

/** The header's segmented control for jumping between layouts. */
@Composable
internal fun LayoutSwitcher(layouts: List<ExplorerLayout>, current: ExplorerLayout, lift: () -> Float = { 0f }, onPick: (ExplorerLayout) -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier.height(40.dp).floatingBacking(lift, shape, palette.surface.toComposeColor(), Color.Transparent)
            .clip(shape).border(1.dp, palette.border.toComposeColor(), shape),
    ) {
        layouts.forEach { l ->
            val on = l == current
            Tooltip(stringResource(l.labelRes)) {
                Box(
                    Modifier
                        .width(42.dp)
                        .height(40.dp)
                        .background(if (on) palette.selectionBackground.toComposeColor() else Color.Transparent)
                        .clickable { onPick(l) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(layoutIcon(l), stringResource(l.labelRes), tint = (if (on) palette.selectionForeground else palette.textDim).toComposeColor(), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** A plain icon button in the explorer's header and bars, named by a tooltip. */
@Composable
internal fun ExplorerIcon(icon: ImageVector, desc: String, tint: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(desc) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(
                icon, desc,
                tint = (tint ?: palette.text.toComposeColor()).copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A heading over one group of items: its name (with the colour's dot when grouped by colour) and how many it holds. */
@Composable
internal fun GroupHeader(label: String, count: Int, color: Rgba? = null, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (color != null) Box(Modifier.size(10.dp).clip(CircleShape).background(codeTint(color, palette)))
        Text(label, color = palette.text.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
        Text("$count", color = palette.textDim.toComposeColor(), fontSize = 13.sp)
    }
}

/** A colour code as drawn on the explorer's chrome: as stored in the dark themes, deepened in the light one. */
internal fun codeTint(c: Rgba, palette: Palette): Color =
    (if (palette.isDark) c else com.xnotes.ui.theme.ColorMath.darkenForLight(c)).toComposeColor()

/** The bar that replaces the chip row while items are selected. */
@Composable
internal fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: (() -> Unit)?,
    actions: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    val fg = palette.selectionForeground.toComposeColor()
    Row(
        // Opaque under its fill, since it stays put while files scroll beneath it, and edged so the
        // pinned bar reads as a surface rather than a tint over the ones sliding past.
        Modifier.fillMaxWidth().height(44.dp).shadow(3.dp, shape)
            .clip(shape).background(palette.bg.toComposeColor())
            .background(palette.selectionBackground.toComposeColor())
            .border(1.dp, fg.copy(alpha = 0.25f), shape)
            .then(BlockPointer).padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExplorerIcon(XnotesIcons.close, stringResource(R.string.clear_selection), fg, onClick = onClear)
        val scroll = rememberScrollState()
        SubcomposeLayout(Modifier.weight(1f)) { c ->
            val loose = Constraints(maxHeight = c.maxHeight)
            val counted = subcompose("count") {
                Text(stringResource(R.string.n_selected, count), color = fg.copy(alpha = 0.75f), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, end = 10.dp))
            }.first().measure(loose)
            val all = subcompose("all") {
                if (onSelectAll != null) Text(
                    stringResource(R.string.select_all),
                    color = fg,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onSelectAll).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }.firstOrNull()?.measure(loose)
            val tools = subcompose("tools") {
                Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) { actions() }
            }.first()
            val room = (c.maxWidth - (all?.width ?: 0)).coerceAtLeast(0)
            // The count gives way first; if the icons still overflow they scroll.
            val showCount = counted.width + tools.maxIntrinsicWidth(c.maxHeight) <= room
            val bar = tools.measure(Constraints(maxWidth = if (showCount) room - counted.width else room, maxHeight = c.maxHeight))
            layout(c.maxWidth, c.maxHeight) {
                var x = 0
                if (showCount) { counted.placeRelative(0, (c.maxHeight - counted.height) / 2); x = counted.width }
                all?.placeRelative(x, (c.maxHeight - all.height) / 2)
                bar.placeRelative(c.maxWidth - bar.width, (c.maxHeight - bar.height) / 2)
            }
        }
    }
}

/** A hairline in the selection bar, setting the destructive action apart from the rest. */
@Composable
internal fun SelectionDivider() {
    val palette = LocalPalette.current
    Box(
        Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp)
            .background(palette.selectionForeground.toComposeColor().copy(alpha = 0.25f)),
    )
}

/** A small label pinned over a thumbnail: the kind of note, or its page count. */
@Composable
internal fun TileBadge(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier
            .height(22.dp)
            .clip(shape)
            .background(palette.bg.withAlpha(if (palette.isDark) 200 else 235).toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape)
            .padding(start = 6.dp, end = if (text.isEmpty()) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(13.dp))
        if (text.isNotEmpty()) Text(text, color = palette.text.toComposeColor(), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The select-mode circle on a tile, filled with a check once the tile is picked. */
@Composable
internal fun CheckRing(selected: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) palette.accent.toComposeColor() else Color.Black.copy(alpha = 0.3f))
            .border(2.dp, if (selected) palette.accent.toComposeColor() else Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(XnotesIcons.check, null, tint = palette.onAccent.toComposeColor(), modifier = Modifier.size(14.dp))
    }
}

/** A square checkbox for list rows in select mode. */
@Composable
internal fun RowCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier
            .size(18.dp)
            .clip(shape)
            .background(if (selected) palette.accent.toComposeColor() else Color.Transparent)
            .border(2.dp, if (selected) palette.accent.toComposeColor() else palette.textDim.toComposeColor(), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(XnotesIcons.check, null, tint = palette.onAccent.toComposeColor(), modifier = Modifier.size(12.dp))
    }
}

/** Every value control in the View menu is this wide, so their edges line up down a card. */
private val PICKER_WIDTH = 148.dp

private val DIRECTION_BUTTON = 36.dp

/**
 * The explorer's View menu: every setting of [view] its layout uses, applied as it's tapped.
 * [everyFolder] is whether one view serves every folder; off, each folder keeps its own.
 */
@Composable
internal fun ViewOptionsMenu(
    view: ExplorerView,
    layouts: List<ExplorerLayout>,
    everyFolder: Boolean,
    onChange: (ExplorerView) -> Unit,
    onReset: () -> Unit,
    onEveryFolder: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val layout = view.layout
    ToolMenu(
        stringResource(R.string.toolbar_view),
        onDismiss,
        width = 360.dp,
        action = { PanelChip(stringResource(R.string.reset), onClick = onReset) },
    ) {
        // How a file is drawn.
        MenuCard(buildList<MenuGroup> {
            add {
                ViewPicker(stringResource(R.string.opt_layout), stringResource(layout.labelRes), icon = layoutIcon(layout), listWidth = 180.dp) { close ->
                    layouts.forEach { l ->
                        DropdownMenuItem(
                            text = { Text(stringResource(l.labelRes)) },
                            onClick = { close(); onChange(view.copy(layout = l)) },
                            leadingIcon = { Icon(layoutIcon(l), null, modifier = Modifier.size(18.dp)) },
                            selected = l == layout,
                        )
                    }
                }
            }
            if (layout != ExplorerLayout.LIST && layout != ExplorerLayout.COLUMNS) {
                add {
                    MenuRow(stringResource(R.string.opt_tile_size)) {
                        val sizes = TileSize.entries
                        Slider(
                            value = view.tileSize.ordinal.toFloat(),
                            onValueChange = { v ->
                                val size = sizes[v.roundToInt().coerceIn(0, sizes.lastIndex)]
                                if (size != view.tileSize) onChange(view.copy(tileSize = size))
                            },
                            valueRange = 0f..sizes.lastIndex.toFloat(),
                            steps = sizes.size - 2,
                            colors = NoTicks,
                            modifier = Modifier.width(PICKER_WIDTH).height(32.dp),
                        )
                    }
                }
            }
            if (layout == ExplorerLayout.LIST) {
                add { MenuSwitch(stringResource(R.string.opt_compact_rows), view.compactRows) { onChange(view.copy(compactRows = it)) } }
            }
            if (layout == ExplorerLayout.TIMELINE) {
                add {
                    val created = stringResource(R.string.sort_created)
                    val modified = stringResource(R.string.sort_modified)
                    ViewPicker(stringResource(R.string.opt_place_by), if (view.timelineByCreated) created else modified, listWidth = 180.dp) { close ->
                        listOf(true to created, false to modified).forEach { (byCreated, label) ->
                            ChoiceMenuItem(byCreated == view.timelineByCreated, onClick = { close(); onChange(view.copy(timelineByCreated = byCreated)) }) { fg ->
                                Text(label, color = fg)
                            }
                        }
                    }
                }
            }
            if (layout == ExplorerLayout.GRID) {
                add {
                    MenuSwitch(stringResource(R.string.opt_whole_page_thumbs), view.thumb == ThumbShape.PAGE) {
                        onChange(view.copy(thumb = if (it) ThumbShape.PAGE else ThumbShape.TOP))
                    }
                }
            }
            if (layout == ExplorerLayout.GRID || layout == ExplorerLayout.GALLERY || layout == ExplorerLayout.TIMELINE) {
                add {
                    // The Timeline's cards carry only the kind and the colour code.
                    val full = layout != ExplorerLayout.TIMELINE
                    val shown = if (full) listOf(view.showKind, view.showPages, view.showTime, view.showSize, view.showColour) else listOf(view.showKind, view.showColour)
                    ViewPicker(stringResource(R.string.opt_show_on_tiles), stringResource(R.string.shown_of, shown.count { it }, shown.size), listWidth = 200.dp) {
                        @Composable
                        fun mark(label: String, on: Boolean, flip: () -> ExplorerView) = DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onChange(flip()) },
                            leadingIcon = { RowCheck(on) },
                        )
                        mark(stringResource(R.string.group_kind), view.showKind) { view.copy(showKind = !view.showKind) }
                        if (full) {
                            mark(stringResource(R.string.show_page_count), view.showPages) { view.copy(showPages = !view.showPages) }
                            mark(stringResource(R.string.show_time), view.showTime) { view.copy(showTime = !view.showTime) }
                            mark(stringResource(R.string.sort_size), view.showSize) { view.copy(showSize = !view.showSize) }
                        }
                        mark(stringResource(R.string.colour_code), view.showColour) { view.copy(showColour = !view.showColour) }
                    }
                }
            }
        })
        // How the files are ordered.
        MenuCard(buildList<MenuGroup> {
            if (layout != ExplorerLayout.TIMELINE) {
                add {
                    ViewPicker(
                        stringResource(R.string.sort_by),
                        shortSortLabel(view.sortKey),
                        fieldWidth = PICKER_WIDTH - DIRECTION_BUTTON - 8.dp,
                        listWidth = 200.dp,
                        beside = { SortDirectionButton(view.sortKey, view.descending) { onChange(view.copy(descending = !view.descending)) } },
                    ) { close ->
                        ExplorerSortKey.entries.forEach { k ->
                            ChoiceMenuItem(k == view.sortKey, onClick = {
                                close()
                                if (k != view.sortKey) onChange(view.copy(sortKey = k, descending = k != ExplorerSortKey.NAME))
                            }) { fg -> Text(sortLabel(k), color = fg) }
                        }
                        MenuDivider()
                        // Names read A to Z first; dates and sizes newest and largest first.
                        val orders = if (view.sortKey == ExplorerSortKey.NAME) listOf(false, true) else listOf(true, false)
                        orders.forEach { descending ->
                            ChoiceMenuItem(descending == view.descending, onClick = { close(); onChange(view.copy(descending = descending)) }) { fg ->
                                Text(directionLabel(view.sortKey, descending), color = fg)
                            }
                        }
                    }
                }
            }
            if (layout != ExplorerLayout.COLUMNS && layout != ExplorerLayout.TIMELINE) {
                add {
                    ViewPicker(stringResource(R.string.opt_group_by), stringResource(view.groupBy.labelRes), listWidth = 160.dp) { close ->
                        GroupBy.entries.forEach { g ->
                            ChoiceMenuItem(g == view.groupBy, onClick = { close(); onChange(view.copy(groupBy = g)) }) { fg -> Text(stringResource(g.labelRes), color = fg) }
                        }
                    }
                }
            }
            if (layout != ExplorerLayout.TIMELINE) {
                add {
                    ViewPicker(stringResource(R.string.kind_folders), stringResource(view.folders.labelRes), listWidth = 160.dp) { close ->
                        FolderPlacement.entries.forEach { f ->
                            ChoiceMenuItem(f == view.folders, onClick = { close(); onChange(view.copy(folders = f)) }) { fg -> Text(stringResource(f.labelRes), color = fg) }
                        }
                    }
                }
            }
        })
        MenuCard(listOf<MenuGroup> { MenuSwitch(stringResource(R.string.view_every_folder), everyFolder, onChange = onEveryFolder) })
    }
}

/** The sort field's word for each key; its list spells the dates out. */
@Composable
private fun shortSortLabel(k: ExplorerSortKey): String = when (k) {
    ExplorerSortKey.NAME -> stringResource(R.string.sort_name)
    ExplorerSortKey.MODIFIED -> stringResource(R.string.modified)
    ExplorerSortKey.CREATED -> stringResource(R.string.created)
    ExplorerSortKey.SIZE -> stringResource(R.string.sort_size)
}

/** A View menu setting whose [value] sits on a field that opens [items] under it, with room [beside] it for one more button. */
@Composable
private fun ViewPicker(
    label: String,
    value: String,
    icon: ImageVector? = null,
    fieldWidth: Dp = PICKER_WIDTH,
    listWidth: Dp = 180.dp,
    beside: (@Composable () -> Unit)? = null,
    items: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    MenuRow(label) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                PickerField(value, icon, open, Modifier.width(fieldWidth)) { open = !open }
                // Hung under the field with its end in line with the row's last control.
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    modifier = Modifier.width(listWidth),
                    offset = DpOffset(PICKER_WIDTH - listWidth, 4.dp),
                ) { items { open = false } }
            }
            beside?.invoke()
        }
    }
}

/** A value on a raised field, ringed in the accent while its list is open. */
@Composable
private fun PickerField(value: String, icon: ImageVector?, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .height(36.dp)
            .clip(shape)
            .background(panelColors().raised)
            .border(1.dp, if (open) palette.accent.toComposeColor() else Color.Transparent, shape)
            .clickable(role = Role.DropdownList, onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(16.dp))
        Text(value, color = palette.text.toComposeColor(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(if (open) XnotesIcons.chevronUp else XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
    }
}

/** Turns the sort round; its arrow points the way the order runs. */
@Composable
private fun SortDirectionButton(key: ExplorerSortKey, descending: Boolean, onFlip: () -> Unit) {
    val palette = LocalPalette.current
    val label = directionLabel(key, descending)
    Tooltip(label) {
        Box(
            Modifier
                .size(DIRECTION_BUTTON)
                .clip(MaterialTheme.shapes.small)
                .background(panelColors().raised)
                .clickable(role = Role.Button, onClick = onFlip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp, label, tint = palette.accent.toComposeColor(), modifier = Modifier.size(18.dp))
        }
    }
}

/** A thin rule across the explorer, like the one under the Columns header. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.border.toComposeColor()))
}

/** Dim, centred text filling an empty explorer body. */
@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 160.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 14.sp, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}
