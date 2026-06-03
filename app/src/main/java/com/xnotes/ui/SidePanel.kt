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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.Page
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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
) {
    val palette = LocalPalette.current
    var tab by remember { mutableStateOf(0) }
    Column(
        Modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(palette.panel.toComposeColor()),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            SegIcon(XnotesIcons.thumbnails, "Pages", tab == 0) { tab = 0 }
            SegIcon(XnotesIcons.contents, "Contents", tab == 1) { tab = 1 }
            SegIcon(XnotesIcons.bookmark, "Bookmarks", tab == 2) { tab = 2 }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                0 -> PagesTab(editor, onSharePages, onSavePagesAsPdf, onSavePagesAsImages)
                1 -> EmptyHint("— no table of contents —")
                else -> BookmarksTab(editor)
            }
        }
        if (tab == 0 && editor.inPageSelectionMode) {
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
) {
    val listState = rememberLazyListState()
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
                PageThumb(editor, index, page, selecting, settled, Modifier.animateItem(), onSharePages, onSavePagesAsPdf, onSavePagesAsImages)
            }
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
) {
    val palette = LocalPalette.current
    val current = index == editor.pageIndex
    val selected = editor.isPageSelected(index)
    var menuOpen by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(editor.cachedPageThumbnail(page), page, editor.contentVersion, settled) {
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
            bitmap?.let { Image(it, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize()) }

            if (selected) {
                Box(Modifier.matchParentSize().background(palette.accent.toComposeColor().copy(alpha = 0.18f)))
                Box(
                    Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp)
                        .clip(CircleShape).background(palette.accent.toComposeColor()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(XnotesIcons.check, "Selected", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(13.dp))
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
                        Icon(XnotesIcons.more, "Page options", tint = palette.text.toComposeColor(), modifier = Modifier.size(16.dp))
                    }
                    PageContextMenu(editor, index, menuOpen, { menuOpen = false }, onSharePages, onSavePagesAsPdf, onSavePagesAsImages)
                }
            }
        }
        Text(
            "%02d".format(index + 1),
            color = (if (current || selected) palette.accent else palette.textDim).toComposeColor(),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Page sub-pages of the three-dot menu (main / share-format / save-format). */
private const val MENU_MAIN = 0
private const val MENU_SHARE = 1
private const val MENU_SAVE = 2

@Composable
private fun PageContextMenu(
    editor: Editor,
    index: Int,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
) {
    val palette = LocalPalette.current
    var sub by remember { mutableStateOf(MENU_MAIN) }
    LaunchedEffect(expanded) { if (!expanded) sub = MENU_MAIN } // always reopen on the main page
    val one = listOf(index)
    fun menuIcon(icon: ImageVector) = @Composable { Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        when (sub) {
            MENU_SHARE -> {
                DropdownMenuItem(text = { Text("‹  Share as") }, onClick = { sub = MENU_MAIN })
                DropdownMenuItem(text = { Text("Image (PNG)") }, leadingIcon = menuIcon(XnotesIcons.image), onClick = { onSharePages(one, false); onDismiss() })
                DropdownMenuItem(text = { Text("PDF") }, leadingIcon = menuIcon(XnotesIcons.exportDoc), onClick = { onSharePages(one, true); onDismiss() })
            }
            MENU_SAVE -> {
                DropdownMenuItem(text = { Text("‹  Save as") }, onClick = { sub = MENU_MAIN })
                DropdownMenuItem(text = { Text("Image (PNG)") }, leadingIcon = menuIcon(XnotesIcons.image), onClick = { onSavePagesAsImages(one); onDismiss() })
                DropdownMenuItem(text = { Text("PDF") }, leadingIcon = menuIcon(XnotesIcons.exportDoc), onClick = { onSavePagesAsPdf(one); onDismiss() })
            }
            else -> {
                DropdownMenuItem(text = { Text("Add page") }, leadingIcon = menuIcon(XnotesIcons.plus), onClick = { editor.insertPageAfter(index); onDismiss() })
                DropdownMenuItem(text = { Text("Copy") }, leadingIcon = menuIcon(XnotesIcons.copy), onClick = { editor.copyPages(one); onDismiss() })
                DropdownMenuItem(text = { Text("Cut") }, leadingIcon = menuIcon(XnotesIcons.cut), onClick = { editor.cutPages(one); onDismiss() })
                if (editor.canPastePages) {
                    DropdownMenuItem(text = { Text("Paste") }, leadingIcon = menuIcon(XnotesIcons.paste), onClick = { editor.pastePagesAfter(index); onDismiss() })
                }
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = menuIcon(XnotesIcons.trash), onClick = { editor.deletePages(one); onDismiss() })
                DropdownMenuItem(text = { Text("Erase page") }, leadingIcon = menuIcon(XnotesIcons.eraser), onClick = { editor.erasePage(index); onDismiss() })
                DropdownMenuItem(text = { Text("Share…") }, leadingIcon = menuIcon(XnotesIcons.share), onClick = { sub = MENU_SHARE })
                DropdownMenuItem(text = { Text("Save as…") }, leadingIcon = menuIcon(XnotesIcons.download), onClick = { sub = MENU_SAVE })
            }
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
            Icon(XnotesIcons.close, "Clear selection", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
        }
        Text("${editor.pageSelectionCount}", color = palette.text.toComposeColor(), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        BarAction(XnotesIcons.copy, "Copy") { editor.copyPages(editor.selectedPageIndices()) }
        BarAction(XnotesIcons.cut, "Cut") { editor.cutPages(editor.selectedPageIndices()) }
        BarAction(XnotesIcons.trash, "Delete") { editor.deletePages(editor.selectedPageIndices()) }
        FormatMenu(XnotesIcons.download, "Save as", { onSavePagesAsImages(editor.selectedPageIndices()) }, { onSavePagesAsPdf(editor.selectedPageIndices()) })
        FormatMenu(XnotesIcons.share, "Share", { onSharePages(editor.selectedPageIndices(), false) }, { onSharePages(editor.selectedPageIndices(), true) })
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
            DropdownMenuItem(text = { Text("Image (PNG)") }, leadingIcon = menuIcon(XnotesIcons.image), onClick = { open = false; onImage() })
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
private fun BookmarksTab(editor: Editor) {
    val palette = LocalPalette.current
    var showAdd by remember { mutableStateOf(false) }
    // Read the version so the list recomposes on add/remove.
    val version = editor.bookmarkVersion
    val bookmarks = remember(version) { editor.bookmarks }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { showAdd = true }) {
                Icon(XnotesIcons.plus, "Add bookmark", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
            }
        }
        if (bookmarks.isEmpty()) {
            EmptyHint("— no bookmarks —")
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(bookmarks) { i, bm ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editor.goToPage(bm.page) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${bm.label} · p.${bm.page + 1}",
                            color = palette.text.toComposeColor(),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { editor.removeBookmark(i) }) {
                            Icon(XnotesIcons.trash, "Remove", tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var label by remember { mutableStateOf("Page ${editor.pageIndex + 1}") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add bookmark") },
            text = { OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { editor.addBookmark(label.ifBlank { "Page ${editor.pageIndex + 1}" }); showAdd = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
            containerColor = palette.menuBg.toComposeColor(),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 12.sp)
    }
}
