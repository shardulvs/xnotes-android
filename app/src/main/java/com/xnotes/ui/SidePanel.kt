package com.xnotes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Page
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs
import kotlin.math.roundToInt
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
    onImportTemplate: () -> Unit = {},
    onTemplateFromImage: (com.xnotes.core.template.TemplateKind) -> Unit = {},
) {
    val palette = LocalPalette.current
    val tab = editor.sidePanelTab
    var pickingCover by remember { mutableStateOf(false) }
    if (pickingCover) {
        NewNoteScreen(
            editor = editor,
            folderName = "",
            initialName = "",
            error = null,
            busy = false,
            onImportTemplate = onImportTemplate,
            onTemplateFromImage = onTemplateFromImage,
            onCreate = { _, _ -> },
            onClose = { pickingCover = false },
            coverEdit = CoverEdit(editor.firstPageMm, editor.coverStyle) { style ->
                editor.setCover(style)
                pickingCover = false
            },
        )
    }
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
            SegIcon(XnotesIcons.search, stringResource(R.string.search), tab == SidePanelTab.SEARCH, shortcut = Keys.SEARCH) { editor.sidePanelTab = SidePanelTab.SEARCH }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                SidePanelTab.PAGES -> PagesTab(editor, onSharePages, onSavePagesAsPdf, onSavePagesAsImages, onInsertPages) { pickingCover = true }
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
private fun SegIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, selected: Boolean, shortcut: String? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(desc, shortcut = shortcut) {
        IconButton(onClick = onClick) {
            Icon(
                icon,
                contentDescription = desc,
                tint = if (selected) palette.accent.toComposeColor() else palette.textDim.toComposeColor(),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PagesTab(
    editor: Editor,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    onInsertPages: (PageInsert, Int) -> Unit,
    onPickCover: () -> Unit,
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
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val pagesNow = rememberUpdatedState(pages)
    // A drag of selected pages: what it carries, the finger in this box's space, and the gap they'd land in.
    var drag by remember { mutableStateOf<PageDrag?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var gap by remember { mutableStateOf(0) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val pad = with(density) { LIST_PAD.toPx() }
    // The gap before the first row whose middle lies below [y]; never above a cover.
    fun gapAt(y: Float): Int {
        val rows = listState.layoutInfo.visibleItemsInfo
        val g = rows.firstOrNull { y - pad < it.offset + it.size / 2f }?.index ?: ((rows.lastOrNull()?.index ?: -1) + 1)
        return if (pagesNow.value.firstOrNull()?.cover == true) g.coerceAtLeast(1) else g
    }
    // Held near the top or bottom edge, the list keeps scrolling so a far page can be reached.
    LaunchedEffect(drag != null) {
        val band = with(density) { 56.dp.toPx() }
        val step = with(density) { 22.dp.toPx() }
        while (drag != null) {
            val y = finger.y
            val bottom = boxSize.height.toFloat()
            val delta = when {
                y < band -> -((band - y) / band).coerceIn(0f, 1f) * step
                y > bottom - band -> ((y - (bottom - band)) / band).coerceIn(0f, 1f) * step
                else -> 0f
            }
            if (delta != 0f) {
                listState.scrollBy(delta)
                gap = gapAt(y)
            }
            delay(16L)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            // Long-presses live on the panel, not the rows, so a drag keeps running while the list
            // scrolls its pressed row away: the first selects a page, a second on a selected page
            // picks the whole selection up. Taps stay with the rows.
            .pointerInput(editor) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Only a held, near-still single finger qualifies; a lift, a scroll or a second finger ends the wait first.
                    var tapped = false
                    val longPress = try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id }
                                if (c == null || c.isConsumed) return@withTimeout false
                                if (!c.pressed) {
                                    tapped = true
                                    return@withTimeout false
                                }
                                if (e.changes.any { it.id != down.id && it.pressed }) return@withTimeout false
                                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeout false
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        true
                    }
                    if (!longPress) {
                        // A tap no page took landed on the panel's empty space, which lets the selection go.
                        if (tapped && editor.inPageSelectionMode) editor.clearPageSelection()
                        return@awaitEachGesture
                    }
                    val half = THUMB_W.toPx() / 2f
                    if (abs(down.position.x - size.width / 2f) > half) return@awaitEachGesture
                    val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { down.position.y - pad in it.offset.toFloat()..(it.offset + it.size).toFloat() }
                    val page = row?.let { r -> pagesNow.value.getOrNull(r.index)?.takeIf { it.uid == r.key } } ?: return@awaitEachGesture
                    // Claim the gesture to the up, ahead of the row, so its click can't undo what the press did.
                    suspend fun AwaitPointerEventScope.holdToUp() {
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.id == down.id && it.pressed })
                    }
                    if (!editor.isPageSelected(page)) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        editor.togglePageSelection(row.index)
                        holdToUp()
                        return@awaitEachGesture
                    }
                    val carried = editor.selectedPagesInOrder().filter { !it.cover }
                    if (page.cover || carried.isEmpty()) {
                        holdToUp()
                        return@awaitEachGesture
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val from = Offset((size.width - THUMB_W.toPx()) / 2f, pad + row.offset)
                    finger = down.position
                    gap = gapAt(down.position.y)
                    drag = PageDrag(carried, from, editor.cachedPageThumbnail(page), editor.pageAspectRatio(page))
                    var dropped = false
                    // Lifted and let go where it lay (the line never left), the pages go back as they were.
                    val startGap = gap
                    var travelled = false
                    try {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) {
                                // A cancelled gesture lifts the finger already consumed; only a real lift drops.
                                dropped = (travelled || gap != startGap) && !c.isConsumed
                                c.consume()
                                break
                            }
                            // Read the move before consuming it: a consumed change reports none.
                            finger += c.positionChange()
                            c.consume()
                            if ((finger - down.position).getDistance() > viewConfiguration.touchSlop) travelled = true
                            gap = gapAt(finger.y)
                        }
                    } finally {
                        val moved = dropped && editor.movePages(carried, gap)
                        drag = null
                        if (moved) editor.clearPageSelection()
                    }
                }
            },
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(LIST_PAD),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(pages, key = { _, page -> page.uid }) { index, page ->
                val carried = drag?.pages?.any { it === page } == true
                PageThumb(editor, index, page, selecting, settled, carried, Modifier.animateItem(), onSharePages, onSavePagesAsPdf, onSavePagesAsImages, onInsertPages, onPickCover)
            }
            if (!selecting && pages.isNotEmpty()) item(key = "add") { AddPageButton(editor, pages.lastIndex, onInsertPages) }
        }
        drag?.let { d ->
            DropLine(listState, gap, pad)
            DragStack(d, finger, boxSize)
        }
        VerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

/** The list's inset in the panel, and the room between its rows. */
private val LIST_PAD = 8.dp
private val ROW_GAP = 10.dp

/** A thumbnail's width in the panel. */
private val THUMB_W = 150.dp

/** Selected pages being carried; the one pressed lay at [from] in the panel and shows as [bitmap] at its [aspect]. */
private class PageDrag(val pages: List<Page>, val from: Offset, val bitmap: ImageBitmap?, val aspect: Float)

/** The accent line across the gap the carried pages would land in, while that gap is in view. */
@Composable
private fun BoxScope.DropLine(listState: LazyListState, gap: Int, pad: Float) {
    val density = LocalDensity.current
    val rows = listState.layoutInfo.visibleItemsInfo
    val half = with(density) { ROW_GAP.toPx() } / 2f
    val y = rows.firstOrNull { it.index == gap }?.let { pad + it.offset - half }
        ?: rows.lastOrNull()?.takeIf { it.index + 1 == gap }?.let { pad + it.offset + it.size + half }
        ?: return
    val thick = with(density) { DROP_LINE.toPx() }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .offset { IntOffset(0, (y - thick / 2f).roundToInt()) }
            .size(THUMB_W + 12.dp, DROP_LINE)
            .clip(CircleShape)
            .background(LocalPalette.current.accent.toComposeColor()),
    )
}

private val DROP_LINE = 3.dp

/**
 * The carried pages lifted off the list: the pressed page small and beside the finger, so the drop
 * line under it stays in sight, with up to two more peeking out behind it and their count.
 */
@Composable
private fun DragStack(drag: PageDrag, finger: Offset, room: IntSize) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val lift = remember(drag) { Animatable(0f) }
    LaunchedEffect(drag) { lift.animateTo(1f, tween(LIFT_MS)) }
    val t = lift.value
    val scale = 1f + (STACK_SCALE - 1f) * t
    val cardW = THUMB_W * scale
    val cardH = cardW * drag.aspect
    val behind = (drag.pages.size - 1).coerceAtMost(2)
    val step = 5.dp
    // Hung below and right of the finger, kept inside the panel.
    val (x, y) = with(density) {
        val spread = (step * behind).toPx()
        val toX = (finger.x + 14.dp.toPx()).coerceIn(0f, (room.width - cardW.toPx() - spread).coerceAtLeast(0f))
        val toY = (finger.y + 6.dp.toPx()).coerceIn(0f, (room.height - cardH.toPx() - spread).coerceAtLeast(0f))
        Offset(drag.from.x + (toX - drag.from.x) * t, drag.from.y + (toY - drag.from.y) * t)
    }
    Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(cardW + step * behind, cardH + step * behind)) {
        for (i in behind downTo 1) {
            Box(
                Modifier.offset(step * i, step * i).size(cardW, cardH)
                    .background(palette.paper.toComposeColor())
                    .border(1.dp, palette.accent.toComposeColor()),
            )
        }
        Box(
            Modifier.size(cardW, cardH)
                .shadow(6.dp * t)
                .background(palette.paper.toComposeColor())
                .border(2.dp, palette.accent.toComposeColor()),
        ) {
            drag.bitmap?.let { Image(it, null, modifier = Modifier.fillMaxSize()) }
            if (drag.pages.size > 1) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(palette.accent.toComposeColor()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${drag.pages.size}", color = palette.onAccent.toComposeColor(), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** How small the carried page shrinks once lifted, and how long the lift takes. */
private const val STACK_SCALE = 0.6f
private const val LIFT_MS = 150

@Composable
private fun PageThumb(
    editor: Editor,
    index: Int,
    page: Page,
    selecting: Boolean,
    settled: Boolean,
    /** Lifted in a drag: the row dims while the pages are carried. */
    carried: Boolean,
    rowModifier: Modifier,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    onInsertPages: (PageInsert, Int) -> Unit,
    onPickCover: () -> Unit,
) {
    val palette = LocalPalette.current
    val current = index == editor.pageIndex
    val selected = editor.isPageSelected(index)
    var menuOpen by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    if (sizing) {
        editor.pageSizeChoice(index)?.let { initial ->
            PageSizeDialog(initial, onApply = { sizing = false; editor.resizePages(listOf(index), it) }) { sizing = false }
        } ?: run { sizing = false }
    }
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
    Column(rowModifier.alpha(if (carried) 0.35f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(THUMB_W)
                .height(THUMB_W * aspect)
                .border(if (selected || current) 2.dp else 1.dp, borderColor.toComposeColor())
                // Tap navigates (or toggles selection in select mode). Long-presses belong to the
                // panel (see PagesTab), so a drag survives the row scrolling away.
                .clickable { if (selecting) editor.togglePageSelection(index) else editor.goToPage(index) }
                .semantics { onLongClick { editor.togglePageSelection(index); true } },
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
                    Tooltip(stringResource(R.string.page_options)) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape)
                                .background(palette.panel.toComposeColor().copy(alpha = 0.7f))
                                .clickable { menuOpen = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(XnotesIcons.more, stringResource(R.string.page_options), tint = palette.text.toComposeColor(), modifier = Modifier.size(16.dp))
                        }
                    }
                    PageContextMenu(
                        editor, index, page.cover, page.pdfPage != null, menuOpen, { menuOpen = false }, { sizing = true },
                        onSharePages, onSavePagesAsPdf, onSavePagesAsImages, onInsertPages, onPickCover,
                    )
                }
            }
        }
        val captionColor = (if (current || selected) palette.accent else palette.textDim).toComposeColor()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("%02d".format(index + 1), color = captionColor, fontSize = 10.sp)
            if (page.cover) Text(stringResource(R.string.cover), color = captionColor, fontSize = 10.sp)
        }
    }
}

/** Page sub-pages of the three-dot menu (main / share-format / save-format / rotate). */
private const val MENU_MAIN = 0
private const val MENU_SHARE = 1
private const val MENU_SAVE = 2
private const val MENU_ROTATE = 3

/** The Rotate sub-page: its back row, then a quarter turn either way and a half turn; [onTurn] gets clockwise quarter turns. */
@Composable
private fun RotateRows(onBack: () -> Unit, onTurn: (Int) -> Unit) {
    val palette = LocalPalette.current
    fun menuIcon(icon: ImageVector) = @Composable { Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    DropdownMenuItem(text = { Text(stringResource(R.string.back_rotate)) }, onClick = onBack)
    DropdownMenuItem(text = { Text(stringResource(R.string.rotate_right)) }, leadingIcon = menuIcon(XnotesIcons.rotateRight), onClick = { onTurn(1) })
    DropdownMenuItem(text = { Text(stringResource(R.string.rotate_left)) }, leadingIcon = menuIcon(XnotesIcons.rotateLeft), onClick = { onTurn(3) })
    DropdownMenuItem(text = { Text(stringResource(R.string.rotate_upside_down)) }, leadingIcon = menuIcon(XnotesIcons.rotateHalf), onClick = { onTurn(2) })
}

/** What a page's menu can put in after it: another file's pages, a picked image or a photo as a page. */
enum class PageInsert { FILE, IMAGE, PHOTO }

@Composable
private fun PageContextMenu(
    editor: Editor,
    index: Int,
    isCover: Boolean,
    isPdf: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPageSize: () -> Unit,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    onInsertPages: (PageInsert, Int) -> Unit,
    onPickCover: () -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var sub by remember { mutableStateOf(MENU_MAIN) }
    LaunchedEffect(expanded) { if (!expanded) sub = MENU_MAIN } // always reopen on the main page
    val one = listOf(index)
    fun menuIcon(icon: ImageVector) = @Composable { Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        when (sub) {
            MENU_SHARE, MENU_SAVE -> PageFormatRows(sub == MENU_SHARE, { sub = MENU_MAIN }) { image ->
                val saving = sub == MENU_SAVE
                onDismiss()
                when {
                    saving && image -> onSavePagesAsImages(one)
                    saving -> onSavePagesAsPdf(one)
                    else -> onSharePages(one, !image)
                }
            }
            else -> {
                // The cover is only ever the first page, so only it offers these.
                if (isCover) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.change_cover)) }, leadingIcon = menuIcon(XnotesIcons.palette), onClick = { onDismiss(); onPickCover() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_cover)) }, leadingIcon = menuIcon(XnotesIcons.trash), onClick = { editor.removeCover(); onDismiss() })
                    HorizontalDivider(color = palette.border.toComposeColor())
                } else if (index == 0) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_cover_ellipsis)) }, leadingIcon = menuIcon(XnotesIcons.palette), onClick = { onDismiss(); onPickCover() })
                    HorizontalDivider(color = palette.border.toComposeColor())
                }
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
                DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, leadingIcon = menuIcon(XnotesIcons.duplicate), onClick = { onDismiss(); editor.duplicatePages(one) })
                DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, leadingIcon = menuIcon(XnotesIcons.copy), onClick = { editor.copyPages(one); onDismiss() })
                DropdownMenuItem(text = { Text(stringResource(R.string.cut)) }, leadingIcon = menuIcon(XnotesIcons.cut), onClick = { editor.cutPages(one); onDismiss() })
                if (editor.canPastePages) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.paste)) }, leadingIcon = menuIcon(XnotesIcons.paste), onClick = { scope.launch { editor.pastePagesAfter(index) }; onDismiss() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.rotate_clockwise)) }, leadingIcon = menuIcon(XnotesIcons.rotateRight), onClick = { onDismiss(); editor.rotatePages(one, 1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.page_size_ellipsis)) }, leadingIcon = menuIcon(XnotesIcons.pageSize), enabled = !isPdf, onClick = { onDismiss(); onPageSize() })
                DropdownMenuItem(text = { Text(stringResource(R.string.erase_page)) }, leadingIcon = menuIcon(XnotesIcons.eraser), onClick = { editor.erasePages(one); onDismiss() })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = menuIcon(XnotesIcons.trash), onClick = { editor.deletePages(one); onDismiss() })
                DropdownMenuItem(text = { Text(stringResource(R.string.share_ellipsis)) }, leadingIcon = menuIcon(XnotesIcons.share), onClick = { sub = MENU_SHARE })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_as_ellipsis)) }, leadingIcon = menuIcon(XnotesIcons.download), onClick = { sub = MENU_SAVE })
            }
        }
    }
}

/** The ways to put a page in after [index]: blank, from a file, from an image or from a photo. */
@Composable
private fun AddPageItems(editor: Editor, index: Int, onInsertPages: (PageInsert, Int) -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    fun menuIcon(icon: ImageVector) = @Composable { Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    DropdownMenuItem(text = { Text(stringResource(R.string.add_page)) }, leadingIcon = menuIcon(XnotesIcons.plus), onClick = { editor.insertPageAfter(index); onDone() })
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

/** Extract or Move to new note: the name the new note gets, the note's own with which pages suggested, then the pages go into it. */
@Composable
private fun ExtractDialog(editor: Editor, indices: List<Int>, move: Boolean, onDismiss: () -> Unit) {
    val suggested = remember(indices) { editor.extractName(indices) }
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(suggested, androidx.compose.ui.text.TextRange(0, suggested.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (move) R.string.move_to_new_note_title else R.string.extract_to_new_note_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.new_note_name)) },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = name.text.isNotBlank(), onClick = { onDismiss(); editor.extractPages(indices, name.text.trim(), move) }) {
                Text(stringResource(if (move) R.string.move else R.string.extract))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A Share or Save as sub-page: its back row, then Image (PNG) and PDF; [onPick] says which. */
@Composable
private fun PageFormatRows(share: Boolean, onBack: () -> Unit, onPick: (image: Boolean) -> Unit) {
    val palette = LocalPalette.current
    fun menuIcon(icon: ImageVector) = @Composable { Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    DropdownMenuItem(text = { Text(stringResource(if (share) R.string.back_share_as else R.string.back_save_as)) }, onClick = onBack)
    DropdownMenuItem(text = { Text(stringResource(R.string.image_png)) }, leadingIcon = menuIcon(XnotesIcons.image), onClick = { onPick(true) })
    DropdownMenuItem(text = { Text("PDF") }, leadingIcon = menuIcon(XnotesIcons.exportDoc), onClick = { onPick(false) })
}

/**
 * Bottom action bar shown while pages are multi-selected: Select all, Copy and Delete, and a
 * three-dot menu with the rest. A tap anywhere but the pages and this bar lets the selection go.
 */
@Composable
private fun PageSelectionBar(
    editor: Editor,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
) {
    val palette = LocalPalette.current
    // The bar sits at the panel's foot, so its tips open upward.
    CompositionLocalProvider(LocalTipSide provides TipSide.ABOVE) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(palette.surface.toComposeColor()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tooltip(stringResource(R.string.clear_selection)) {
                IconButton(onClick = { editor.clearPageSelection() }) {
                    Icon(XnotesIcons.close, stringResource(R.string.clear_selection), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                }
            }
            Text("${editor.pageSelectionCount}", color = palette.text.toComposeColor(), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            BarAction(XnotesIcons.selectAll, stringResource(R.string.select_all)) { editor.selectAllPages() }
            BarAction(XnotesIcons.copy, stringResource(R.string.copy)) { editor.copyPages(editor.selectedPageIndices()) }
            BarAction(XnotesIcons.trash, stringResource(R.string.delete)) { editor.deletePages(editor.selectedPageIndices()) }
            PageSelectionMore(editor, onSharePages, onSavePagesAsPdf, onSavePagesAsImages)
        }
    }
}

/** The selection bar's three-dot menu: everything else that can be done to the selected pages. */
@Composable
private fun PageSelectionMore(
    editor: Editor,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf(MENU_MAIN) }
    var sizing by remember { mutableStateOf<com.xnotes.core.model.PageSizeChoice?>(null) }
    var extracting by remember { mutableStateOf<Pair<List<Int>, Boolean>?>(null) }
    LaunchedEffect(open) { if (!open) sub = MENU_MAIN }
    extracting?.let { (picked, move) -> ExtractDialog(editor, picked, move) { extracting = null } }
    fun menuIcon(icon: ImageVector) = @Composable { Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    fun close() { open = false }
    sizing?.let { initial ->
        PageSizeDialog(initial, onApply = { sizing = null; editor.resizePages(editor.selectedPageIndices(), it) }) { sizing = null }
    }
    Box {
        BarAction(XnotesIcons.more, stringResource(R.string.more)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { close() }) {
            when (sub) {
                MENU_ROTATE -> RotateRows({ sub = MENU_MAIN }) { turns -> close(); editor.rotatePages(editor.selectedPageIndices(), turns) }
                MENU_SHARE, MENU_SAVE -> PageFormatRows(sub == MENU_SHARE, { sub = MENU_MAIN }) { image ->
                    val picked = editor.selectedPageIndices()
                    val saving = sub == MENU_SAVE
                    close()
                    when {
                        saving && image -> onSavePagesAsImages(picked)
                        saving -> onSavePagesAsPdf(picked)
                        else -> onSharePages(picked, !image)
                    }
                }
                else -> {
                    DropdownMenuItem(text = { Text(stringResource(R.string.cut)) }, leadingIcon = menuIcon(XnotesIcons.cut), onClick = { close(); editor.cutPages(editor.selectedPageIndices()) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, leadingIcon = menuIcon(XnotesIcons.duplicate), onClick = { close(); editor.duplicatePages(editor.selectedPageIndices()) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rotate_ellipsis)) }, leadingIcon = menuIcon(XnotesIcons.rotateRight), onClick = { sub = MENU_ROTATE })
                    val plain = editor.selectedPagesInOrder().firstOrNull { it.pdfPage == null }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.page_size_ellipsis)) },
                        leadingIcon = menuIcon(XnotesIcons.pageSize),
                        enabled = plain != null,
                        onClick = {
                            close()
                            sizing = plain?.let { editor.pageSizeChoice(editor.pageIndexOf(it)) }
                        },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.erase)) }, leadingIcon = menuIcon(XnotesIcons.eraser), onClick = { close(); editor.erasePages(editor.selectedPageIndices()) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.extract_to_new_note)) }, leadingIcon = menuIcon(XnotesIcons.extract), onClick = {
                        close()
                        extracting = editor.selectedPageIndices() to false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_to_new_note)) }, leadingIcon = menuIcon(XnotesIcons.moveToFolder), onClick = {
                        close()
                        extracting = editor.selectedPageIndices() to true
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_as_ellipsis)) }, leadingIcon = menuIcon(XnotesIcons.download), onClick = { sub = MENU_SAVE })
                    DropdownMenuItem(text = { Text(stringResource(R.string.share_ellipsis)) }, leadingIcon = menuIcon(XnotesIcons.share), onClick = { sub = MENU_SHARE })
                }
            }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Tooltip(desc) {
        IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
            Icon(icon, desc, tint = LocalPalette.current.textDim.toComposeColor(), modifier = Modifier.size(19.dp))
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
    var renaming by remember { mutableStateOf<com.xnotes.core.model.Bookmark?>(null) }
    // Read the version so the list recomposes on add/remove.
    val version = editor.bookmarkVersion
    val bookmarks = remember(version) { editor.bookmarks }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            Tooltip(stringResource(R.string.add_bookmark)) {
                IconButton(onClick = { showAdd = true }) {
                    Icon(XnotesIcons.plus, stringResource(R.string.add_bookmark), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                }
            }
        }
        if (bookmarks.isEmpty()) {
            EmptyHint(stringResource(R.string.no_bookmarks))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(bookmarks) { _, bm ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editor.goToPage(bm.page) }.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.bookmark_entry, bm.label, bm.page + 1),
                            color = palette.text.toComposeColor(),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        BookmarkMenu(editor, bm) { renaming = bm }
                    }
                }
            }
        }
    }

    renaming?.let { mark ->
        var label by remember(mark) { mutableStateOf(mark.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename_bookmark)) },
            text = { OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { editor.renameBookmark(mark, label); renaming = null }, enabled = label.isNotBlank()) { Text(stringResource(R.string.rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
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

/** A bookmark row's three-dot menu: Rename and Delete. */
@Composable
private fun BookmarkMenu(editor: Editor, mark: com.xnotes.core.model.Bookmark, onRename: () -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    fun menuIcon(icon: ImageVector) = @Composable { Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp)) }
    Box {
        Tooltip(stringResource(R.string.bookmark_options)) {
            IconButton(onClick = { open = true }) {
                Icon(XnotesIcons.more, stringResource(R.string.bookmark_options), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = menuIcon(XnotesIcons.edit), onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = menuIcon(XnotesIcons.trash), onClick = { open = false; editor.removeBookmark(mark) })
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 12.sp)
    }
}
