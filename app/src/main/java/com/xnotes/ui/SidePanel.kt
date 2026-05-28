package com.xnotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SidePanel(editor: Editor) {
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
        Box(Modifier.fillMaxWidth().width(224.dp)) {
            when (tab) {
                0 -> PagesTab(editor)
                1 -> EmptyHint("— no table of contents —")
                else -> BookmarksTab(editor)
            }
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
private fun PagesTab(editor: Editor) {
    val palette = LocalPalette.current
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(editor.pageCount) { index ->
                val current = index == editor.pageIndex
                val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                    editor.cachedPageThumbnail(index), index, editor.contentVersion,
                ) {
                    val cached = editor.cachedPageThumbnail(index)
                    if (cached != null) {
                        value = cached
                    } else {
                        // Don't render pages that are flicked past: this settle delay is cancelled
                        // (the row leaves composition) before it elapses unless the page lingers
                        // long enough to actually be seen.
                        delay(150)
                        value = editor.pageThumbnail(index, 300)
                    }
                }
                // Reserve the row's height from the page's aspect ratio so it stays put whether or
                // not the thumbnail has rendered yet (a stable layout keeps the scrollbar steady).
                val aspect = editor.pageAspectRatio(index) ?: 1.4f
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .width(150.dp)
                            .height(150.dp * aspect)
                            .border(if (current) 2.dp else 1.dp, (if (current) palette.accent else palette.border).toComposeColor())
                            .clickable { editor.goToPage(index) },
                    ) {
                        bitmap?.let { Image(it, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize()) }
                    }
                    Text(
                        "%02d".format(index + 1),
                        color = (if (current) palette.accent else palette.textDim).toComposeColor(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        VerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

private class ScrollbarMetrics(val viewport: Float, val contentHeight: Float, val fraction: Float)

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
            ScrollbarMetrics(viewport, contentHeight, (scrolled / (contentHeight - viewport)).coerceIn(0f, 1f))
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
                            val factor = if (travel > 0f) (m.contentHeight - m.viewport) / travel else 0f
                            scope.launch { listState.scrollBy(dy * factor) }
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
