package com.xnotes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Page
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The side panel's tabs. */
enum class SidePanelTab { PAGES, CONTENTS, BOOKMARKS, SEARCH }

/**
 * The side panel. The Pages tab shows page thumbnails with a per-page three-dot menu (add / copy /
 * cut / paste / delete / erase / share / save as) and supports multi-select (long-press, then tap
 * more pages) with a bottom action bar. Share/Save-as need the activity's SAF launchers, so they're
 * passed in as callbacks; everything else acts on [editor] directly.
 */
@Composable
fun SidePanel(
    editor: Editor,
    onSharePages: (indices: List<Int>, asPdf: Boolean) -> Unit = { _, _ -> },
    onSavePagesAsPdf: (indices: List<Int>) -> Unit = {},
    onSavePagesAsImages: (indices: List<Int>) -> Unit = {},
    onInsertPages: (PageInsert, Int) -> Unit = { _, _ -> },
) {
    val palette = LocalPalette.current
    val tab = editor.sidePanelTab
    Column(
        Modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(palette.panel.toComposeColor()),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            SegIcon(XnotesIcons.thumbnails, stringResource(R.string.pages), tab == SidePanelTab.PAGES) { editor.sidePanelTab = SidePanelTab.PAGES }
            SegIcon(XnotesIcons.contents, stringResource(R.string.contents), tab == SidePanelTab.CONTENTS) { editor.sidePanelTab = SidePanelTab.CONTENTS }
            SegIcon(XnotesIcons.bookmark, stringResource(R.string.bookmarks), tab == SidePanelTab.BOOKMARKS) { editor.sidePanelTab = SidePanelTab.BOOKMARKS }
            SegIcon(XnotesIcons.search, stringResource(R.string.search), tab == SidePanelTab.SEARCH) { editor.sidePanelTab = SidePanelTab.SEARCH }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                SidePanelTab.PAGES -> PagesTab(editor, onSharePages, onSavePagesAsPdf, onSavePagesAsImages, onInsertPages)
                SidePanelTab.CONTENTS -> ContentsTab(editor)
                SidePanelTab.BOOKMARKS -> BookmarksTab(editor)
                SidePanelTab.SEARCH -> SearchTab(editor)
            }
        }
        if (tab == SidePanelTab.PAGES && editor.inPageSelectionMode) {
            PageSelectionBar(editor, onSharePages, onSavePagesAsPdf, onSavePagesAsImages)
        }
    }
}

@Composable
private fun SegIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (selected) palette.accent.toComposeColor() else palette.textDim.toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PagesTab(
    editor: Editor,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    onInsertPages: (PageInsert, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // Open the panel on the current page, not page 1 (re-runs each open: a hidden panel leaves composition).
    LaunchedEffect(Unit) { listState.scrollToItem(editor.pageIndex) }
    // Keyed by the page's stable id so insert/delete animate and keep each page's cached thumbnail;
    // contentVersion drives a fresh read of the order whenever the page list changes.
    val pages = remember(editor.contentVersion) { editor.pagesSnapshot() }
    val selecting = editor.inPageSelectionMode
    // One panel-wide "the list has stopped moving" flag. The custom scrollbar amplifies a slow
    // finger scrub into large page-space jumps, so the old per-row 150ms debounce still fired ~8
    // renders per window it swept past and stormed the CPU / the single-threaded PDF render lock.
    // Gating every row on this flag means a scrub renders nothing until it settles, then the
    // resting window renders once. drop(1) ignores snapshotFlow's initial emission so first-open
    // paints immediately. Keyed on listState only — not contentVersion — so edits don't reset it.
    val settled by produceState(initialValue = true, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .drop(1)
            .collectLatest {
                value = false
                delay(150)
                value = true
            }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(pages, key = { _, page -> page.uid }) { index, page ->
                PageThumb(editor, index, page, selecting, settled, Modifier.animateItem(), onSharePages, onSavePagesAsPdf, onSavePagesAsImages, onInsertPages)
            }
            if (!selecting && pages.isNotEmpty()) item(key = "add") { AddPageButton(editor, pages.lastIndex, onInsertPages) }
        }
        VerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageThumb(
    editor: Editor,
    index: Int,
    page: Page,
    selecting: Boolean,
    settled: Boolean,
    rowModifier: Modifier,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    onInsertPages: (PageInsert, Int) -> Unit,
) {
    val palette = LocalPalette.current
    val current = index == editor.pageIndex
    val selected = editor.isPageSelected(index)
    var menuOpen by remember { mutableStateOf(false) }
    val bitmap by key(editor.thumbnailVersion) {
        produceState<ImageBitmap?>(editor.cachedPageThumbnail(page), page, editor.contentVersion, editor.pdfThumbTick, settled) {
            val cached = editor.cachedPageThumbnail(page)
            if (cached != null) {
                value = cached
            } else if (settled) {
                // Render only once the panel has stopped moving (see the panel-wide `settled` flag in
                // PagesTab): a scroll keeps `settled` false, so rows swept past never render — the
                // producer relaunches and renders this row when motion settles.
                value = editor.pageThumbnail(page, 300)
            }
            // not cached and not settled: leave value as-is (placeholder); relaunch on settle renders it.
        }
    }
    // Reserve the row's height from the aspect ratio so it stays put before the bitmap loads.
    val aspect = editor.pageAspectRatio(page)
    val borderColor = if (selected || current) palette.accent else palette.border
    Column(rowModifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(150.dp)
                .height(150.dp * aspect)
                .border(if (selected || current) 2.dp else 1.dp, borderColor.toComposeColor())
                // Tap navigates (or toggles selection in select mode); long-press enters/extends
                // selection. No drag handling here, so the list scrolls normally.
                .combinedClickable(
                    onClick = { if (selecting) editor.togglePageSelection(index) else editor.goToPage(index) },
                    onLongClick = { editor.togglePageSelection(index) },
                ),
        ) {
            bitmap?.let { Image(it, contentDescription = stringResource(R.string.page_n, index + 1), modifier = Modifier.fillMaxSize()) }

            if (selected) {
                Box(Modifier.matchParentSize().background(palette.selectionBackground.withAlpha(77).toComposeColor()))
                Box(
                    Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp)
                        .clip(CircleShape).background(palette.accent.toComposeColor()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(XnotesIcons.check, stringResource(R.string.selected), tint = palette.onAccent.toComposeColor(), modifier = Modifier.size(13.dp))
                }
            } else if (!selecting) {
                // Three-dot menu, on a faint scrim so it reads over any thumbnail.
                Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .background(palette.panel.toComposeColor().copy(alpha = 0.7f))
                            .clickable { menuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(XnotesIcons.more, stringResource(R.string.page_options), tint = palette.text.toComposeColor(), modifier = Modifier.size(16.dp))
                    }
                    PageContextMenu(editor, index, menuOpen, { menuOpen = false }, onSharePages, onSavePagesAsPdf, onSavePagesAsImages, onInsertPages)
                }
            }
        }
        Text(
            "%02d".format(index + 1),
            color = (if (current || selected) palette.accent else palette.textDim).toComposeColor(),
            fontSize = 10.sp,
        )
    }
}

/** Page sub-pages of the three-dot menu (main / share-format / save-format). */
private const val MENU_MAIN = 0
private const val MENU_SHARE = 1
private const val MENU_SAVE = 2

/** What a page's menu can put in after it: another file's pages, a picked image or a photo as a page. */
enum class PageInsert { FILE, IMAGE, PHOTO }

@Composable
private fun PageContextMenu(
    editor: Editor,
    index: Int,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    onInsertPages: (PageInsert, Int) -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var sub by remember { mutableStateOf(MENU_MAIN) }
    LaunchedEffect(expanded) { if (!expanded) sub = MENU_MAIN } // always reopen on the main page
    val one = listOf(index)
    fun menuIcon(icon: ImageVector) = @Composable { Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        when (sub) {
            MENU_SHARE -> {
                DropdownMenuItem(text = { Text(stringResource(R.string.back_share_as)) }, onClick = { sub = MENU_MAIN })
                DropdownMenuItem(text = { Text(stringResource(R.string.image_png)) }, leadingIcon = menuIcon(XnotesIcons.image), onClick = { onSharePages(one, false); onDismiss() })
                DropdownMenuItem(text = { Text("PDF") }, leadingIcon = menuIcon(XnotesIcons.exportDoc), onClick = { onSharePages(one, true); onDismiss() })
            }
            MENU_SAVE -> {
                DropdownMenuItem(text = { Text(stringResource(R.string.back_save_as)) }, onClick = { sub = MENU_MAIN })
                DropdownMenuItem(text = { Text(stringResource(R.string.image_png)) }, leadingIcon = menuIcon(XnotesIcons.image), onClick = { onSavePagesAsImages(one); onDismiss() })
                DropdownMenuItem(text = { Text("PDF") }, leadingIcon = menuIcon(XnotesIcons.exportDoc), onClick = { onSavePagesAsPdf(one); onDismiss() })
            }
            else -> {
                // The ways to add a page open beside this row, so the menu stays short.
                var addOpen by remember { mutableStateOf(false) }
                var row by remember { mutableStateOf(IntSize.Zero) }
                Box(Modifier.onSizeChanged { row = it }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_ellipsis)) },
                        leadingIcon = menuIcon(XnotesIcons.plus),
                        trailingIcon = menuIcon(XnotesIcons.next),
                        onClick = { addOpen = true },
                    )
                    val side = with(LocalDensity.current) { DpOffset(row.width.toDp(), -row.height.toDp()) }
                    CompositionLocalProvider(LocalMenuOffset provides side) {
                        DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                            AddPageItems(editor, index, onInsertPages) { addOpen = false; onDismiss() }
                        }
                    }
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, leadingIcon = menuIcon(XnotesIcons.copy), onClick = { editor.copyPages(one); onDismiss() })
                DropdownMenuItem(text = { Text(stringResource(R.string.cut)) }, leadingIcon = menuIcon(XnotesIcons.cut), onClick = { editor.cutPages(one); onDismiss() })
                if (editor.canPastePages) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.paste)) }, leadingIcon = menuIcon(XnotesIcons.paste), onClick = { scope.launch { editor.pastePagesAfter(index) }; onDismiss() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = menuIcon(XnotesIcons.trash), onClick = { editor.deletePages(one); onDismiss() })
                DropdownMenuItem(text = { Text(stringResource(R.string.erase_page)) }, leadingIcon = menuIcon(XnotesIcons.eraser), onClick = { editor.erasePage(index); onDismiss() })
                DropdownMenuItem(text = { Text(stringResource(R.string.share_ellipsis)) }, leadingIcon = menuIcon(XnotesIcons.share), onClick = { sub = MENU_SHARE })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_as_ellipsis)) }, leadingIcon = menuIcon(XnotesIcons.download), onClick = { sub = MENU_SAVE })
            }
        }
    }
}

/** The ways to put a page in after [index]: blank, turned, from a file, from an image or from a photo. */
@Composable
private fun AddPageItems(editor: Editor, index: Int, onInsertPages: (PageInsert, Int) -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    fun menuIcon(icon: ImageVector) = @Composable { Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    DropdownMenuItem(text = { Text(stringResource(R.string.add_page)) }, leadingIcon = menuIcon(XnotesIcons.plus), onClick = { editor.insertPageAfter(index); onDone() })
    val turnedLabel = if (editor.isLandscapePage(index)) R.string.add_portrait_page else R.string.add_landscape_page
    DropdownMenuItem(text = { Text(stringResource(turnedLabel)) }, leadingIcon = menuIcon(XnotesIcons.plus), onClick = { editor.insertPageAfter(index, turned = true); onDone() })
    DropdownMenuItem(text = { Text(stringResource(R.string.insert_from_file)) }, leadingIcon = menuIcon(XnotesIcons.exportDoc), onClick = { onInsertPages(PageInsert.FILE, index + 1); onDone() })
    DropdownMenuItem(text = { Text(stringResource(R.string.add_image_page)) }, leadingIcon = menuIcon(XnotesIcons.image), onClick = { onInsertPages(PageInsert.IMAGE, index + 1); onDone() })
    DropdownMenuItem(text = { Text(stringResource(R.string.add_photo_page)) }, leadingIcon = menuIcon(XnotesIcons.camera), onClick = { onInsertPages(PageInsert.PHOTO, index + 1); onDone() })
}

/** The + under the last page: the same ways to add a page, at the end of the note. */
@Composable
private fun AddPageButton(editor: Editor, last: Int, onInsertPages: (PageInsert, Int) -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .width(150.dp)
                .height(44.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.extraSmall)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(XnotesIcons.plus, stringResource(R.string.add_page), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AddPageItems(editor, last, onInsertPages) { open = false }
        }
    }
}

/** Bottom action bar shown while pages are multi-selected. */
@Composable
private fun PageSelectionBar(
    editor: Editor,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(palette.surface.toComposeColor()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { editor.clearPageSelection() }) {
            Icon(XnotesIcons.close, stringResource(R.string.clear_selection), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
        }
        Text("${editor.pageSelectionCount}", color = palette.text.toComposeColor(), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        BarAction(XnotesIcons.copy, stringResource(R.string.copy)) { editor.copyPages(editor.selectedPageIndices()) }
        BarAction(XnotesIcons.cut, stringResource(R.string.cut)) { editor.cutPages(editor.selectedPageIndices()) }
        BarAction(XnotesIcons.trash, stringResource(R.string.delete)) { editor.deletePages(editor.selectedPageIndices()) }
        FormatMenu(XnotesIcons.download, stringResource(R.string.save_as), { onSavePagesAsImages(editor.selectedPageIndices()) }, { onSavePagesAsPdf(editor.selectedPageIndices()) })
        FormatMenu(XnotesIcons.share, stringResource(R.string.share), { onSharePages(editor.selectedPageIndices(), false) }, { onSharePages(editor.selectedPageIndices(), true) })
    }
}

@Composable
private fun BarAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, desc, tint = LocalPalette.current.textDim.toComposeColor(), modifier = Modifier.size(19.dp))
    }
}

/** An action that opens a small Image/PDF chooser (used by the bottom bar's Save as / Share). */
@Composable
private fun FormatMenu(icon: ImageVector, desc: String, onImage: () -> Unit, onPdf: () -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    fun menuIcon(i: ImageVector) = @Composable { Icon(i, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    Box {
        BarAction(icon, desc) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.image_png)) }, leadingIcon = menuIcon(XnotesIcons.image), onClick = { open = false; onImage() })
            DropdownMenuItem(text = { Text("PDF") }, leadingIcon = menuIcon(XnotesIcons.exportDoc), onClick = { open = false; onPdf() })
        }
    }
}

private class ScrollbarMetrics(
    val viewport: Float,
    val contentHeight: Float,
    val fraction: Float,
    val avgItem: Float,
    val totalItems: Int,
)

/**
 * A draggable scrollbar for [listState], shown only while the list overflows. Square thumb
 * (no rounded corners) that turns the accent colour while pressed/dragged. Item extents are
 * approximated from the visible items (the thumbnails are near-uniform height), which is
 * accurate enough to position the thumb and map a drag back onto a scroll.
 */
@Composable
private fun VerticalScrollbar(listState: LazyListState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val spacingPx = with(density) { 10.dp.toPx() } // matches the list's Arrangement.spacedBy
    val minThumbPx = with(density) { 28.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }

    val metrics = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            val total = info.totalItemsCount
            val viewport = info.viewportSize.height.toFloat()
            if (total == 0 || visible.isEmpty() || viewport <= 0f) return@derivedStateOf null
            val avgItem = visible.sumOf { it.size }.toFloat() / visible.size + spacingPx
            val contentHeight = avgItem * total
            if (contentHeight <= viewport) return@derivedStateOf null // everything fits: no scrollbar
            val scrolled = avgItem * listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset
            ScrollbarMetrics(viewport, contentHeight, (scrolled / (contentHeight - viewport)).coerceIn(0f, 1f), avgItem, total)
        }
    }

    val thumbColor = (if (dragging) palette.accent else palette.textDim).toComposeColor()
    Box(
        modifier
            .fillMaxHeight()
            .width(8.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (metrics.value == null) return@awaitEachGesture // nothing to scroll
                    down.consume()
                    dragging = true
                    var lastY = down.position.y
                    // Track the thumb position locally (seeded from the current scroll) and accumulate
                    // drag deltas, so rapid moves don't fight the derived state's recomposition lag.
                    var frac = metrics.value?.fraction ?: 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        val dy = change.position.y - lastY
                        lastY = change.position.y
                        val m = metrics.value
                        if (dy != 0f && m != null) {
                            val track = size.height.toFloat()
                            val thumb = (m.viewport / m.contentHeight * track).coerceAtLeast(minThumbPx)
                            val travel = track - thumb
                            if (travel > 0f && m.avgItem > 0f) {
                                // Map the thumb's new track position to an absolute page index + offset
                                // and jump straight there. scrollBy(dy * factor) instead made
                                // LazyColumn measure/compose *every* page it scrolled past on the UI
                                // thread — pegging it for a 497-page PDF. scrollToItem jumps directly
                                // to the destination and only composes the landing window.
                                frac = (frac + dy / travel).coerceIn(0f, 1f)
                                val targetPx = frac * (m.contentHeight - m.viewport)
                                val index = (targetPx / m.avgItem).toInt().coerceIn(0, m.totalItems - 1)
                                val offset = (targetPx - index * m.avgItem).toInt().coerceAtLeast(0)
                                scope.launch { listState.scrollToItem(index, offset) }
                            }
                            change.consume()
                        }
                    }
                    dragging = false
                }
            }
            .drawBehind {
                val m = metrics.value ?: return@drawBehind
                val thumb = (m.viewport / m.contentHeight * size.height).coerceAtLeast(minThumbPx)
                drawRect(
                    color = thumbColor,
                    topLeft = Offset(0f, (size.height - thumb) * m.fraction),
                    size = Size(size.width, thumb),
                )
            },
    )
}

@Composable
private fun ContentsTab(editor: Editor) {
    val palette = LocalPalette.current
    // tocVersion bumps when the off-thread outline parse lands (and to empty on document swap).
    val version = editor.tocVersion
    val entries = remember(version) { editor.tableOfContents }
    if (entries.isEmpty()) {
        EmptyHint(stringResource(R.string.no_contents))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(entries) { _, e ->
            val hasTarget = e.destPage >= 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (hasTarget) Modifier.clickable { editor.goToTocEntry(e) } else Modifier)
                    .padding(
                        start = (10 + e.level.coerceAtMost(6) * 12).dp,
                        end = 10.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    e.title,
                    color = (if (hasTarget) palette.text else palette.textDim).toComposeColor(),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BookmarksTab(editor: Editor) {
    val palette = LocalPalette.current
    var showAdd by remember { mutableStateOf(false) }
    // Read the version so the list recomposes on add/remove.
    val version = editor.bookmarkVersion
    val bookmarks = remember(version) { editor.bookmarks }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { showAdd = true }) {
                Icon(XnotesIcons.plus, stringResource(R.string.add_bookmark), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
            }
        }
        if (bookmarks.isEmpty()) {
            EmptyHint(stringResource(R.string.no_bookmarks))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(bookmarks) { i, bm ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editor.goToPage(bm.page) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.bookmark_entry, bm.label, bm.page + 1),
                            color = palette.text.toComposeColor(),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { editor.removeBookmark(i) }) {
                            Icon(XnotesIcons.trash, stringResource(R.string.remove), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        val defaultLabel = stringResource(R.string.page_n, editor.pageIndex + 1)
        var label by remember { mutableStateOf(defaultLabel) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.add_bookmark)) },
            text = { OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { editor.addBookmark(label.ifBlank { defaultLabel }); showAdd = false }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 12.sp)
    }
}
