package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.settings.ExplorerSortKey
import com.xnotes.settings.ExplorerView
import com.xnotes.settings.ThumbShape
import com.xnotes.settings.TileSize
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Columns the grid shows at [size], from the screen's width so folding the sidebar never reflows it. */
internal fun gridColumns(screenWidthDp: Int, size: TileSize): Int {
    val tile = when (size) { TileSize.S -> 180f; TileSize.M -> 240f; TileSize.L -> 320f; TileSize.XL -> 420f }
    return Math.round(screenWidthDp / tile).coerceIn(1, 10)
}

/** Columns the gallery shows at [size]: as many upright pages as stand side by side in [width], the explorer's beside the rail. */
internal fun galleryColumns(width: Dp, size: TileSize): Int {
    val page = (galleryShelf(size) - 14.dp) * A4_RATIO + 8.dp
    return ((width + GALLERY_GAP) / (page + GALLERY_GAP)).toInt().coerceAtLeast(1)
}

internal fun galleryShelf(size: TileSize): Dp = when (size) { TileSize.S -> 170.dp; TileSize.M -> 220.dp; TileSize.L -> 290.dp; TileSize.XL -> 380.dp }

private val GALLERY_GAP = 28.dp

internal fun timelineCard(size: TileSize): Dp = when (size) { TileSize.S -> 110.dp; TileSize.M -> 140.dp; TileSize.L -> 180.dp; TileSize.XL -> 230.dp }

/**
 * Grid: the chip row, the top folder's pinned notes on a shelf, folders in a row of chips (or mixed in as cards),
 * then file cards under their group headings.
 */
@Composable
internal fun GridBody(b: ExplorerBody, state: LazyGridState, columns: Int, chips: @Composable (Dp) -> Unit, top: (LazyGridScope.() -> Unit)?, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = EXPLORER_HEADER, bottom = 88.dp),
    ) {
        item(key = "chips", span = { GridItemSpan(maxLineSpan) }, contentType = "chips") { chips(48.dp) }
        top?.invoke(this)
        if (b.pinned.isNotEmpty()) {
            item(key = "pinned", span = { GridItemSpan(maxLineSpan) }, contentType = "pinned") { PinnedShelf(b, whole = b.view.thumb == ThumbShape.PAGE) }
        }
        if (b.folders.isNotEmpty()) {
            items(b.folders, key = { it.documentUri }, contentType = { "folder" }) { FolderChipTile(b, it) }
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "gap") { Spacer(Modifier.height(4.dp)) }
        }
        b.groups.forEach { g ->
            if (g.label.isNotEmpty()) {
                item(key = "group:${g.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    GroupHeader(g.label, g.items.size, g.color, Modifier.padding(top = 2.dp))
                }
            }
            items(g.items, key = { it.documentUri }, contentType = { if (it.isDir) "folderCard" else "file" }) { e ->
                if (e.isDir) FolderCardTile(b, e) else GridFileTile(b, e)
            }
        }
    }
}

/** List: the chip row, the top folder's pinned notes on a shelf and a header of sortable column names, all scrolling away with one row per item. */
@Composable
internal fun ListBody(
    b: ExplorerBody,
    state: LazyListState,
    wide: Boolean,
    onSort: ((ExplorerSortKey) -> Unit)?,
    chips: @Composable (Dp) -> Unit,
    top: (LazyListScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val inset = 8.dp + (if (b.view.compactRows) 28.dp else 40.dp) + 14.dp + (if (b.host.selecting()) 32.dp else 0.dp)
    LazyColumn(state = state, modifier = modifier, contentPadding = PaddingValues(top = EXPLORER_HEADER, bottom = 88.dp)) {
        item(key = "chips", contentType = "chips") { chips(48.dp) }
        top?.invoke(this)
        if (b.pinned.isNotEmpty()) {
            item(key = "pinned", contentType = "pinned") { PinnedShelf(b, whole = false, Modifier.padding(top = 4.dp, bottom = 8.dp)) }
        }
        item(key = "columnHeads", contentType = "columnHeads") {
            Row(Modifier.fillMaxWidth().height(36.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ColumnHead(stringResource(R.string.sort_name), ExplorerSortKey.NAME, b, onSort, Modifier.weight(1f).padding(start = inset))
                if (wide) {
                    ColumnHead(stringResource(R.string.group_kind), null, b, null, Modifier.width(LIST_KIND_W))
                    ColumnHead(stringResource(R.string.pages), null, b, null, Modifier.width(LIST_PAGES_W), TextAlign.End)
                    ColumnHead(stringResource(R.string.sort_size), ExplorerSortKey.SIZE, b, onSort, Modifier.width(LIST_SIZE_W), TextAlign.End)
                    val byCreated = b.view.sortKey == ExplorerSortKey.CREATED
                    ColumnHead(if (byCreated) stringResource(R.string.created) else stringResource(R.string.modified), if (byCreated) ExplorerSortKey.CREATED else ExplorerSortKey.MODIFIED, b, onSort, Modifier.width(LIST_WHEN_W).padding(start = 28.dp))
                }
                Spacer(Modifier.width(40.dp))
            }
            Hairline()
        }
        items(b.folders, key = { it.documentUri }, contentType = { "row" }) { e ->
            ListRow(b, e, wide)
            Hairline()
        }
        b.groups.forEach { g ->
            if (g.label.isNotEmpty()) {
                item(key = "group:${g.key}", contentType = "header") {
                    GroupHeader(g.label, g.items.size, g.color, Modifier.padding(start = 8.dp, top = 10.dp))
                }
            }
            items(g.items, key = { it.documentUri }, contentType = { "row" }) { e ->
                ListRow(b, e, wide)
                Hairline()
            }
        }
    }
}

@Composable
private fun ColumnHead(
    label: String,
    key: ExplorerSortKey?,
    b: ExplorerBody,
    onSort: ((ExplorerSortKey) -> Unit)?,
    modifier: Modifier,
    align: TextAlign = TextAlign.Start,
) {
    val palette = LocalPalette.current
    val active = key != null && b.view.sortKey == key
    val tint = (if (active) palette.accent else palette.textDim).toComposeColor()
    Row(
        modifier.then(if (key != null && onSort != null) Modifier.clickable { onSort(key) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (align == TextAlign.End) Arrangement.End else Arrangement.Start,
    ) {
        Text(label, color = tint, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (active) {
            Spacer(Modifier.width(4.dp))
            Icon(if (b.view.descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp, null, tint = tint, modifier = Modifier.size(14.dp))
        }
    }
}

/** Gallery: whole first pages standing on shelves, a shelf per group, the chip row and folders as a row of chips on top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GalleryBody(b: ExplorerBody, state: LazyGridState, columns: Int, chips: @Composable (Dp) -> Unit, top: (LazyGridScope.() -> Unit)?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val column = (maxWidth - GALLERY_GAP * (columns - 1)) / columns
        // Upright pages fill their column, though never much taller than the size's own shelf.
        val shelf = minOf(galleryShelf(b.view.tileSize) * 1.15f, (column - 8.dp) / A4_RATIO + 14.dp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(GALLERY_GAP),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(top = EXPLORER_HEADER, bottom = 88.dp),
        ) {
            // Shorter than elsewhere since the shelves' wider row spacing already follows it.
            item(key = "chips", span = { GridItemSpan(maxLineSpan) }, contentType = "chips") { chips(40.dp) }
            top?.invoke(this)
            if (b.pinned.isNotEmpty()) {
                item(key = "pinned", span = { GridItemSpan(maxLineSpan) }, contentType = "pinned") { PinnedShelf(b, whole = true) }
            }
            if (b.folders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "folders") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        b.folders.forEach { GalleryFolderChip(b, it) }
                    }
                }
            }
            b.groups.forEach { g ->
                if (g.label.isNotEmpty()) {
                    item(key = "group:${g.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        SectionHeader(g.label, g.items.size, g.color)
                    }
                }
                items(g.items, key = { it.documentUri }, contentType = { if (it.isDir) "folder" else "page" }) { e ->
                    if (e.isDir) FolderCardTile(b, e) else GalleryItem(b, e, shelf)
                }
            }
        }
    }
}

/** A gallery shelf's or a section's heading: its icon or colour, label and count over a hairline running to the edge. */
@Composable
private fun SectionHeader(label: String, count: Int, color: com.xnotes.core.model.Rgba? = null, icon: ImageVector? = null) {
    val palette = LocalPalette.current
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(16.dp))
        if (color != null) Box(Modifier.size(10.dp).clip(CircleShape).background(codeTint(color, palette)))
        Text(label, color = palette.text.toComposeColor(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("$count", color = palette.textDim.toComposeColor(), fontSize = 13.sp)
        Hairline(Modifier.weight(1f).padding(start = 6.dp))
    }
}

/** The top folder's pinned notes on a shelf of their own that scrolls sideways, closed by the heading over the folder's own items. */
@Composable
private fun PinnedShelf(b: ExplorerBody, whole: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.pinned), b.pinned.size, icon = XnotesIcons.pin)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(b.pinned, key = { it.documentUri }) { PinnedCard(b, it, whole) }
        }
        Spacer(Modifier.height(16.dp))
        SectionHeader(b.restLabel, b.restCount)
    }
}

/**
 * Columns: one column per folder along the path from the top folder, then a preview of the picked file.
 * [levels] is that path, (document id, name) from the top down; [arrange] sorts and filters a folder's listing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ColumnsBody(
    b: ExplorerBody,
    root: String,
    levels: List<Pair<String, String>>,
    refreshKey: Int,
    arrange: (List<BrowseEntry>) -> List<BrowseEntry>,
    picked: BrowseEntry?,
    onOpenFolder: (level: Int, BrowseEntry) -> Unit,
    onPickFile: (level: Int, BrowseEntry) -> Unit,
    preview: @Composable (BrowseEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val scroll = rememberScrollState()
    // Keep the deepest column in view as the path grows; the new width lands a frame after the change.
    LaunchedEffect(levels.size, picked?.documentUri) { snapshotFlow { scroll.maxValue }.collect { scroll.animateScrollTo(it) } }
    Column(modifier) {
        Hairline()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columnW = 272.dp
            val previewMin = 360.dp
            val columnsW = (maxWidth - previewMin).coerceAtLeast(columnW)
            Row(Modifier.fillMaxSize()) {
                Row(Modifier.width(minOf(columnsW, columnW * levels.size)).fillMaxHeight().horizontalScroll(scroll)) {
                    levels.forEachIndexed { i, (docId, name) -> key(docId) {
                        val entries by produceState(b.editor.cachedChildren(root, docId), root, docId, refreshKey) {
                            value = withContext(Dispatchers.IO) { b.editor.browseChildren(root, docId) }
                        }
                        val shown = remember(entries, arrange) { entries?.let(arrange) }
                        val next = levels.getOrNull(i + 1)?.first
                        Column(
                            Modifier.width(columnW).fillMaxHeight().padding(horizontal = 6.dp),
                        ) {
                            Text(
                                if (shown == null) name else "$name · ${shown.size}",
                                color = palette.textDim.toComposeColor(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.height(36.dp).padding(start = 12.dp, top = 11.dp),
                            )
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                                items(shown.orEmpty(), key = { it.documentUri }) { e ->
                                    val onPath = e.isDir && next != null && b.editor.browseDocId(e.documentUri) == next
                                    val on = !e.isDir && picked?.documentUri == e.documentUri
                                    val sel = on || b.host.isSelected(e)
                                    val code = b.colorOf(e)?.let { codeTint(it, palette) }
                                    val shape = MaterialTheme.shapes.small
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .clip(shape)
                                            .background(
                                                when {
                                                    sel -> palette.selectionBackground.toComposeColor()
                                                    onPath -> palette.surfaceHi.toComposeColor()
                                                    else -> Color.Transparent
                                                },
                                            )
                                            .combinedClickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { if (e.isDir) onOpenFolder(i, e) else onPickFile(i, e) },
                                                onLongClick = { b.host.onLongClick(e) },
                                            )
                                            .padding(start = 12.dp, end = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        val fg = (if (sel) palette.selectionForeground else palette.text).toComposeColor()
                                        Icon(
                                            kindIcon(b.kind(e)), null,
                                            tint = if (sel) fg else (code ?: palette.textDim.toComposeColor()),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(b.label(e), color = fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        if (e.isDir) Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(palette.border.toComposeColor()))
                    } }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (picked != null) preview(picked) else EmptyNote(stringResource(R.string.pick_note_hint), Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** When [e] sits on the Timeline: the day it was created, or last saved when the view asks or no creation time is known. */
internal fun ExplorerView.timelineTime(e: BrowseEntry): Long = if (timelineByCreated && e.created > 0) e.created else e.modified

/**
 * Timeline: every file under the folder, a row per day it was created (or last saved) on, for one month at a
 * time. A heat strip across the top counts the files each day; tapping a day scrolls to it.
 */
@Composable
internal fun TimelineBody(
    b: ExplorerBody,
    items: List<BrowseEntry>,
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    listState: LazyListState,
    chips: @Composable (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val zone = b.zone
    val today = remember(b.now) { Instant.ofEpochMilli(b.now).atZone(zone).toLocalDate() }
    val view = b.view
    val byDay = remember(items, month, view.timelineByCreated) {
        items.filter { view.timelineTime(it) > 0 }
            .groupBy { Instant.ofEpochMilli(view.timelineTime(it)).atZone(zone).toLocalDate() }
            .filterKeys { YearMonth.from(it) == month }
            .toSortedMap(compareByDescending { it })
    }
    val scope = rememberCoroutineScope()
    val card = timelineCard(b.view.tileSize)
    // The chip row and the month strip scroll away with the days, which start after them.
    val lead = 2
    LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(top = EXPLORER_HEADER, bottom = 88.dp)) {
        item(key = "chips", contentType = "chips") { chips(48.dp) }
        item(key = "month", contentType = "month") {
            Row(Modifier.fillMaxWidth().height(70.dp).padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(164.dp), verticalAlignment = Alignment.CenterVertically) {
                    ExplorerIcon(XnotesIcons.prev, stringResource(R.string.previous_month), palette.textDim.toComposeColor()) { onMonth(month.minusMonths(1)) }
                    Text(
                        if (month.year == today.year) b.words.month(month.month) else b.words.monthYear(month.month, month.year),
                        color = palette.text.toComposeColor(), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
                    )
                    ExplorerIcon(XnotesIcons.next, stringResource(R.string.next_month), palette.textDim.toComposeColor(), enabled = month < YearMonth.from(today)) { onMonth(month.plusMonths(1)) }
                }
                Spacer(Modifier.width(16.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val counts = remember(items, month, view.timelineByCreated) {
                        items.filter { view.timelineTime(it) > 0 }.groupingBy { Instant.ofEpochMilli(view.timelineTime(it)).atZone(zone).toLocalDate() }.eachCount()
                    }
                    for (d in 1..month.lengthOfMonth()) {
                        val date = month.atDay(d)
                        val ahead = date.isAfter(today)
                        val n = counts[date] ?: 0
                        val shape = RoundedCornerShape(3.dp)
                        val fill = when {
                            ahead -> Color.Transparent
                            n == 0 -> palette.surface.toComposeColor()
                            n <= 2 -> palette.accentAlpha(72).toComposeColor()
                            n == 3 -> palette.accentAlpha(140).toComposeColor()
                            else -> palette.accent.toComposeColor()
                        }
                        Box(
                            Modifier
                                .size(24.dp)
                                .then(if (date == today) Modifier.border(1.5.dp, palette.text.toComposeColor(), shape) else Modifier)
                                .clip(shape)
                                .background(fill)
                                .then(if (ahead) Modifier.border(1.dp, palette.border.toComposeColor(), shape) else Modifier)
                                .clickable(enabled = n > 0) {
                                    val index = byDay.keys.indexOf(date)
                                    if (index >= 0) scope.launch { listState.animateScrollToItem(lead + index) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$d", fontSize = 10.5.sp,
                                color = when {
                                    ahead -> palette.textDim.toComposeColor()
                                    n >= 4 -> palette.onAccent.toComposeColor()
                                    else -> palette.text.toComposeColor()
                                },
                            )
                        }
                    }
                }
            }
        }
        if (byDay.isEmpty()) {
            item(key = "none", contentType = "none") { EmptyNote(stringResource(if (view.timelineByCreated) R.string.nothing_created_in else R.string.nothing_saved_in, b.words.month(month.month))) }
        } else {
            byDay.forEach { (date, files) ->
                item(key = date.toString()) { TimelineDay(b, date, today, files, card) }
            }
        }
    }
}

@Composable
private fun TimelineDay(b: ExplorerBody, date: LocalDate, today: LocalDate, files: List<BrowseEntry>, card: Dp) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(card + 64.dp)) {
        Column(Modifier.width(84.dp)) {
            Text("${date.dayOfMonth}", color = palette.text.toComposeColor(), fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(b.words.shortWeekday(date.dayOfWeek), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
            val rel = when (date) {
                today -> b.words.today
                today.minusDays(1) -> b.words.yesterday
                else -> null
            }
            if (rel != null) Text(rel, color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        }
        Box(Modifier.width(28.dp).fillMaxHeight()) {
            Box(Modifier.offset(x = 4.dp, y = 12.dp).width(1.dp).fillMaxHeight().background(palette.border.toComposeColor()))
            Box(Modifier.offset(y = 8.dp).size(9.dp).clip(CircleShape).background(palette.accent.toComposeColor()))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
            items(files.sortedByDescending { b.view.timelineTime(it) }, key = { it.documentUri }) { TimelineCard(b, it, card) }
        }
    }
}
