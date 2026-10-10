package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
    val shape = RoundedCornerShape(3.dp)
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

/**
 * The View options popover's contents: every setting of [view] the current [layout] uses, applied as it's tapped.
 * [everyFolder] is whether one view serves every folder; off, each folder keeps its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ViewOptionsContent(
    view: ExplorerView,
    layouts: List<ExplorerLayout>,
    everyFolder: Boolean,
    hideDotItems: Boolean,
    onChange: (ExplorerView) -> Unit,
    onReset: () -> Unit,
    onEveryFolder: (Boolean) -> Unit,
    onHideDotItems: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalPalette.current
    val layout = view.layout
    Column(Modifier.widthIn(max = 372.dp).padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.toolbar_view), color = palette.text.toComposeColor(), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.reset),
                color = palette.accent.toComposeColor(),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onReset).padding(horizontal = 8.dp, vertical = 6.dp),
            )
            ExplorerIcon(XnotesIcons.close, stringResource(R.string.close_view_options), palette.textDim.toComposeColor(), onClick = onClose)
        }
        OptionBlock(stringResource(R.string.opt_layout)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                layouts.forEach { l ->
                    val on = l == layout
                    val shape = MaterialTheme.shapes.medium
                    Column(
                        Modifier
                            .width(62.dp)
                            .height(62.dp)
                            .clip(shape)
                            .background(if (on) palette.selectionBackground.toComposeColor() else Color.Transparent)
                            .border(1.dp, if (on) palette.accent.toComposeColor() else palette.border.toComposeColor(), shape)
                            .clickable { onChange(view.copy(layout = l)) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val tint = (if (on) palette.selectionForeground else palette.textDim).toComposeColor()
                        Icon(layoutIcon(l), null, tint = tint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(5.dp))
                        Text(stringResource(l.labelRes), color = tint, fontSize = 11.5.sp, lineHeight = 14.sp, maxLines = 1)
                    }
                }
            }
        }
        if (layout == ExplorerLayout.LIST) {
            OptionBlock(stringResource(R.string.opt_rows)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExplorerChip(stringResource(R.string.rows_comfortable), !view.compactRows) { onChange(view.copy(compactRows = false)) }
                    ExplorerChip(stringResource(R.string.rows_compact), view.compactRows) { onChange(view.copy(compactRows = true)) }
                }
            }
        } else if (layout != ExplorerLayout.COLUMNS) {
            OptionBlock(stringResource(R.string.opt_tile_size)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TileSize.entries.forEach { t -> ExplorerChip(t.label, view.tileSize == t, Modifier.widthIn(min = 48.dp)) { onChange(view.copy(tileSize = t)) } }
                }
                if (layout == ExplorerLayout.GRID) {
                    Text(stringResource(R.string.pinch_hint), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                }
            }
        }
        if (layout == ExplorerLayout.TIMELINE) {
            OptionBlock(stringResource(R.string.opt_place_by)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExplorerChip(stringResource(R.string.sort_created), view.timelineByCreated) { onChange(view.copy(timelineByCreated = true)) }
                    ExplorerChip(stringResource(R.string.sort_modified), !view.timelineByCreated) { onChange(view.copy(timelineByCreated = false)) }
                }
            }
        }
        if (layout == ExplorerLayout.GRID) {
            OptionBlock(stringResource(R.string.opt_thumbnail)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExplorerChip(stringResource(R.string.thumb_top), view.thumb == ThumbShape.TOP) { onChange(view.copy(thumb = ThumbShape.TOP)) }
                    ExplorerChip(stringResource(R.string.thumb_page), view.thumb == ThumbShape.PAGE) { onChange(view.copy(thumb = ThumbShape.PAGE)) }
                }
            }
        }
        if (layout == ExplorerLayout.GRID || layout == ExplorerLayout.GALLERY || layout == ExplorerLayout.TIMELINE) {
            OptionBlock(stringResource(R.string.opt_show_on_tiles)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable
                    fun toggle(label: String, on: Boolean, flip: () -> ExplorerView) =
                        ExplorerChip(label, on, icon = if (on) XnotesIcons.check else XnotesIcons.plus) { onChange(flip()) }
                    toggle(stringResource(R.string.group_kind), view.showKind) { view.copy(showKind = !view.showKind) }
                    if (layout != ExplorerLayout.TIMELINE) {
                        toggle(stringResource(R.string.show_page_count), view.showPages) { view.copy(showPages = !view.showPages) }
                        toggle(stringResource(R.string.show_time), view.showTime) { view.copy(showTime = !view.showTime) }
                        toggle(stringResource(R.string.sort_size), view.showSize) { view.copy(showSize = !view.showSize) }
                    }
                    toggle(stringResource(R.string.colour_code), view.showColour) { view.copy(showColour = !view.showColour) }
                }
            }
        }
        if (layout != ExplorerLayout.COLUMNS && layout != ExplorerLayout.TIMELINE) {
            OptionBlock(stringResource(R.string.opt_group_by)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupBy.entries.forEach { g -> ExplorerChip(stringResource(g.labelRes), view.groupBy == g) { onChange(view.copy(groupBy = g)) } }
                }
            }
        }
        if (layout != ExplorerLayout.TIMELINE) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sort_by), color = palette.accent.toComposeColor(), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(directionLabel(view.sortKey, view.descending), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ExplorerSortKey.NAME to stringResource(R.string.sort_name), ExplorerSortKey.MODIFIED to stringResource(R.string.modified), ExplorerSortKey.CREATED to stringResource(R.string.created), ExplorerSortKey.SIZE to stringResource(R.string.sort_size)).forEach { (k, label) ->
                        val active = view.sortKey == k
                        ExplorerChip(label, active, trailing = if (active) (if (view.descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp) else null) {
                            onChange(if (active) view.copy(descending = !view.descending) else view.copy(sortKey = k, descending = k != ExplorerSortKey.NAME))
                        }
                    }
                }
            }
            OptionBlock(stringResource(R.string.kind_folders)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderPlacement.entries.forEach { f -> ExplorerChip(stringResource(f.labelRes), view.folders == f) { onChange(view.copy(folders = f)) } }
                }
            }
        }
        HorizontalDivider(color = palette.border.toComposeColor())
        CheckOption(everyFolder, stringResource(R.string.view_every_folder), stringResource(R.string.view_every_folder_hint)) { onEveryFolder(!everyFolder) }
        CheckOption(hideDotItems, stringResource(R.string.view_hide_dot_items), stringResource(R.string.view_hide_dot_items_hint)) { onHideDotItems(!hideDotItems) }
    }
}

@Composable
private fun CheckOption(on: Boolean, title: String, hint: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RowCheck(on, Modifier.padding(top = 3.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = palette.text.toComposeColor(), fontSize = 14.sp)
            Text(hint, color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        }
    }
}

@Composable
private fun OptionBlock(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = LocalPalette.current.accent.toComposeColor(), fontSize = 13.sp)
        content()
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
