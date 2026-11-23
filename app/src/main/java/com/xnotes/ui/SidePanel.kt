package com.xnotes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
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
    onInsertPdf: (after: Int) -> Unit = {},
    onImportTemplate: () -> Unit = {},
    onTemplateFromImage: (com.xnotes.core.template.TemplateKind) -> Unit = {},
) {
    val palette = LocalPalette.current
    val tab = editor.sidePanelTab
    var pickingCover by remember { mutableStateOf(false) }
    if (pickingCover) {
        NewNoteScreen(
            editor = editor,
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
        PanelTabs(editor)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                SidePanelTab.PAGES -> PagesTab(editor, onSharePages, onSavePagesAsPdf, onSavePagesAsImages, onInsertPdf) { pickingCover = true }
                SidePanelTab.CONTENTS -> ContentsTab(editor)
                SidePanelTab.BOOKMARKS -> BookmarksTab(editor)
                SidePanelTab.SEARCH -> SearchTab(editor)
            }
            if (tab == SidePanelTab.PAGES && editor.inPageSelectionMode) {
                PageSelectionBar(editor, onSharePages, onSavePagesAsPdf, onSavePagesAsImages, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** The tabs along the panel's top; the chosen one sits in a pill that glides over to each new pick. */
@Composable
private fun PanelTabs(editor: Editor) {
    val palette = LocalPalette.current
    var cell by remember { mutableStateOf(0f) }
    val glide = rememberGlide(if (cell > 0f) cell * (editor.sidePanelTab.ordinal + 0.5f) else null)
    val shape = MaterialTheme.shapes.small
    val fill = palette.selectionBackground.toComposeColor()
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 8.dp)
            .onSizeChanged { cell = it.width / SidePanelTab.entries.size.toFloat() }
            .drawBehind {
                if (glide.visible == 0f) return@drawBehind
                val w = TAB_PILL_W.toPx().coerceAtMost(cell)
                val h = TAB_PILL_H.toPx()
                translate(glide.start(w), (size.height - h) / 2f) {
                    drawOutline(shape.createOutline(Size(w + glide.stretch, h), layoutDirection, this), fill.copy(alpha = fill.alpha * glide.visible))
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelTab(editor, SidePanelTab.PAGES, XnotesIcons.thumbnails, stringResource(R.string.pages))
        PanelTab(editor, SidePanelTab.CONTENTS, XnotesIcons.contents, stringResource(R.string.contents))
        PanelTab(editor, SidePanelTab.BOOKMARKS, XnotesIcons.bookmark, stringResource(R.string.bookmarks))
        PanelTab(editor, SidePanelTab.SEARCH, XnotesIcons.search, stringResource(R.string.search), Keys.SEARCH)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border.toComposeColor()))
}

/** The pill behind the chosen tab. */
private val TAB_PILL_W = 52.dp
private val TAB_PILL_H = 32.dp

@Composable
private fun RowScope.PanelTab(editor: Editor, tab: SidePanelTab, icon: ImageVector, label: String, shortcut: String? = null) {
    val palette = LocalPalette.current
    val selected = editor.sidePanelTab == tab
    val tint by animateColorAsState(
        (if (selected) palette.selectionForeground else palette.accent).toComposeColor(),
        tween(GLIDE_TINT_MS),
        label = "tabTint",
    )
    // No ripple: the pill is the feedback, as on the toolbar.
    Tooltip(label, Modifier.weight(1f).height(40.dp), shortcut = shortcut) {
        Box(
            Modifier.selectable(selected, remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { editor.sidePanelTab = tab },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PagesTab(
    editor: Editor,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    onInsertPdf: (Int) -> Unit,
    onPickCover: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Open the panel on the current page, not page 1 (re-runs each open: a hidden panel leaves composition).
    LaunchedEffect(Unit) { listState.scrollToItem(editor.pageIndex) }
    // Keyed by the page's stable id so insert/delete animate and keep each page's cached thumbnail;
    // contentVersion drives a fresh read of the order whenever the page list changes.
    val pages = remember(editor.contentVersion) { editor.pagesSnapshot() }
    val selecting = editor.inPageSelectionMode
    // A read-only note's pages are only turned to: none is picked up, moved or given a menu.
    val reading = editor.readOnly
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
        // Only selected pages are carried, so the selection bar floats over the list's foot meanwhile.
        val barRoom = with(density) { SELECTION_BAR_ROOM.toPx() }
        while (drag != null) {
            val y = finger.y
            val bottom = boxSize.height - barRoom
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
            .pointerInput(editor, reading) {
                if (reading) return@pointerInput
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
            // Room under the last page so it can scroll clear of the floating selection bar.
            contentPadding = PaddingValues(bottom = if (selecting) SELECTION_BAR_ROOM else 0.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(pages, key = { _, page -> page.uid }) { index, page ->
                val carried = drag?.pages?.any { it === page } == true
                PageThumb(editor, index, page, selecting, settled, carried, reading, Modifier.animateItem(), onSharePages, onSavePagesAsPdf, onSavePagesAsImages, onInsertPdf, onPickCover)
            }
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

/** How much of the list's foot the floating selection bar covers, its margins included. */
private val SELECTION_BAR_ROOM = 60.dp

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
    /** The note is read-only: a tap turns to the page and that is all. */
    reading: Boolean,
    rowModifier: Modifier,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    onInsertPdf: (Int) -> Unit,
    onPickCover: () -> Unit,
) {
    val palette = LocalPalette.current
    val current = index == editor.pageIndex
    val selected = editor.isPageSelected(index)
    var menuOpen by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf<Boolean?>(null) }
    extracting?.let { move -> ExtractDialog(editor, listOf(index), move) { extracting = null } }
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
                .then(if (reading) Modifier else Modifier.semantics { onLongClick { editor.togglePageSelection(index); true } }),
        ) {
            bitmap?.let { Image(it, contentDescription = stringResource(R.string.page_n, index + 1), modifier = Modifier.fillMaxSize()) }

            if (selected) {
                Box(Modifier.matchParentSize().background(palette.selectionBackground.withAlpha(77).toComposeColor()))
                SelectMark(checked = true, Modifier.align(Alignment.TopStart))
            } else if (selecting) {
                SelectMark(checked = false, Modifier.align(Alignment.TopStart))
            } else if (!reading) {
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
                        editor, index, page.cover, page.pdfPage != null, menuOpen, { menuOpen = false }, { sizing = true }, { extracting = it },
                        onSharePages, onSavePagesAsPdf, onSavePagesAsImages, onInsertPdf, onPickCover,
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

/** A page's corner mark while pages are being picked: a check on a picked page, an empty ring on the rest. */
@Composable
private fun SelectMark(checked: Boolean, modifier: Modifier) {
    val palette = LocalPalette.current
    val panel = palette.panel.toComposeColor()
    Box(
        modifier
            .padding(4.dp)
            .size(26.dp)
            .clip(CircleShape)
            // A ring of the panel's colour keeps the check clear of the page drawn under it.
            .background(if (checked) panel else Color.Transparent)
            .padding(2.dp)
            .clip(CircleShape)
            .then(
                if (checked) Modifier.background(palette.accent.toComposeColor())
                else Modifier.background(panel.copy(alpha = 0.5f)).border(1.5.dp, palette.text.toComposeColor(), CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(XnotesIcons.check, stringResource(R.string.selected), tint = palette.onAccent.toComposeColor(), modifier = Modifier.size(14.dp))
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
    MenuBack(stringResource(R.string.back_rotate), onBack)
    MenuItem(stringResource(R.string.rotate_right), XnotesIcons.rotateRight, onClick = { onTurn(1) })
    MenuItem(stringResource(R.string.rotate_left), XnotesIcons.rotateLeft, onClick = { onTurn(3) })
    MenuItem(stringResource(R.string.rotate_upside_down), XnotesIcons.rotateHalf, onClick = { onTurn(2) })
}

@Composable
private fun PageContextMenu(
    editor: Editor,
    index: Int,
    isCover: Boolean,
    isPdf: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPageSize: () -> Unit,
    onExtract: (move: Boolean) -> Unit,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    onInsertPdf: (Int) -> Unit,
    onPickCover: () -> Unit,
) {
    val palette = LocalPalette.current
    var sub by remember { mutableStateOf(MENU_MAIN) }
    LaunchedEffect(expanded) { if (!expanded) sub = MENU_MAIN } // always reopen on the main page
    val one = listOf(index)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        when (sub) {
            MENU_ROTATE -> RotateRows({ sub = MENU_MAIN }) { turns -> onDismiss(); editor.rotatePages(one, turns) }
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
                    MenuItem(stringResource(R.string.change_cover), XnotesIcons.palette, onClick = { onDismiss(); onPickCover() })
                    MenuItem(stringResource(R.string.remove_cover), XnotesIcons.trash, danger = true, onClick = { editor.removeCover(); onDismiss() })
                    MenuDivider()
                } else if (index == 0) {
                    MenuItem(stringResource(R.string.add_cover_ellipsis), XnotesIcons.palette, onClick = { onDismiss(); onPickCover() })
                    MenuDivider()
                }
                MenuItem(stringResource(R.string.add_page), XnotesIcons.plus, onClick = { editor.insertPageAfter(index); onDismiss() })
                MenuItem(stringResource(R.string.insert_pdf), XnotesIcons.importDoc, onClick = { onInsertPdf(index); onDismiss() })
                MenuItem(stringResource(R.string.duplicate), XnotesIcons.duplicate, onClick = { onDismiss(); editor.duplicatePages(one) })
                MenuItem(stringResource(R.string.copy), XnotesIcons.copy, onClick = { editor.copyPages(one); onDismiss() })
                MenuItem(stringResource(R.string.cut), XnotesIcons.cut, onClick = { editor.cutPages(one); onDismiss() })
                if (editor.canPastePages) {
                    MenuItem(stringResource(R.string.paste), XnotesIcons.paste, onClick = { editor.pastePagesAfter(index); onDismiss() })
                }
                MenuItem(stringResource(R.string.rotate_ellipsis), XnotesIcons.rotateRight, more = true, onClick = { sub = MENU_ROTATE })
                MenuItem(stringResource(R.string.page_size_ellipsis), XnotesIcons.pageSize, enabled = !isPdf, onClick = { onDismiss(); onPageSize() })
                MenuItem(stringResource(R.string.extract_to_new_note), XnotesIcons.extract, onClick = { onDismiss(); onExtract(false) })
                MenuItem(stringResource(R.string.move_to_new_note), XnotesIcons.moveToFolder, onClick = { onDismiss(); onExtract(true) })
                MenuItem(stringResource(R.string.erase_page), XnotesIcons.eraser, danger = true, onClick = { editor.erasePages(one); onDismiss() })
                MenuItem(stringResource(R.string.delete), XnotesIcons.trash, danger = true, onClick = { editor.deletePages(one); onDismiss() })
                MenuItem(stringResource(R.string.share_ellipsis), XnotesIcons.share, more = true, onClick = { sub = MENU_SHARE })
                MenuItem(stringResource(R.string.save_as_ellipsis), XnotesIcons.download, more = true, onClick = { sub = MENU_SAVE })
            }
        }
    }
}

/** Extract or Move to new note: the name the new note gets, the note's own with which pages suggested, then the pages go into it. */
@Composable
private fun ExtractDialog(editor: Editor, indices: List<Int>, move: Boolean, onDismiss: () -> Unit) {
    val suggested = remember(indices) { editor.extractName(indices) }
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(suggested, androidx.compose.ui.text.TextRange(0, suggested.length))) }
    val extract = { if (name.text.isNotBlank()) { onDismiss(); editor.extractPages(indices, name.text.trim(), move) } }
    SheetDialog(
        title = stringResource(if (move) R.string.move_to_new_note_title else R.string.extract_to_new_note_title),
        onDismiss = onDismiss,
        foot = {
            ConfirmButtons(stringResource(if (move) R.string.move else R.string.extract), onDismiss, enabled = name.text.isNotBlank(), onConfirm = extract)
        },
    ) {
        DialogField(name, { name = it }, label = stringResource(R.string.new_note_name), autoFocus = true, onDone = extract, onEscape = onDismiss)
    }
}

/** A Share or Save as sub-page: its back row, then Image (PNG) and PDF; [onPick] says which. */
@Composable
private fun PageFormatRows(share: Boolean, onBack: () -> Unit, onPick: (image: Boolean) -> Unit) {
    val palette = LocalPalette.current
    MenuBack(stringResource(if (share) R.string.back_share_as else R.string.back_save_as), onBack)
    MenuItem(stringResource(R.string.image_png), XnotesIcons.image, onClick = { onPick(true) })
    MenuItem("PDF", XnotesIcons.pdfFile, onClick = { onPick(false) })
}

/**
 * The bar floating over the panel's foot while pages are multi-selected: Select all, Copy and
 * Delete, and a three-dot menu with the rest. A tap anywhere but the pages and this bar lets the
 * selection go.
 */
@Composable
private fun PageSelectionBar(
    editor: Editor,
    onSharePages: (List<Int>, Boolean) -> Unit,
    onSavePagesAsPdf: (List<Int>) -> Unit,
    onSavePagesAsImages: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.large
    // The bar sits at the panel's foot, so its tips open upward.
    CompositionLocalProvider(LocalTipSide provides TipSide.ABOVE) {
        Row(
            modifier
                .padding(8.dp)
                .fillMaxWidth()
                .height(52.dp)
                .shadow(6.dp, shape)
                .clip(shape)
                .background(palette.surface.toComposeColor())
                // Holds taps between the buttons, which would otherwise reach the pages under the bar and end the selection.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Tooltip(stringResource(R.string.clear_selection)) {
                IconButton(onClick = { editor.clearPageSelection() }, modifier = Modifier.size(40.dp)) {
                    Icon(XnotesIcons.close, stringResource(R.string.clear_selection), tint = palette.text.toComposeColor(), modifier = Modifier.size(18.dp))
                }
            }
            Text("${editor.pageSelectionCount}", color = palette.text.toComposeColor(), fontSize = 15.sp, fontWeight = FontWeight.Medium, style = tabular())
            Spacer(Modifier.weight(1f))
            BarAction(XnotesIcons.selectAll, stringResource(R.string.select_all)) { editor.selectAllPages() }
            BarAction(XnotesIcons.copy, stringResource(R.string.copy)) { editor.copyPages(editor.selectedPageIndices()) }
            BarAction(XnotesIcons.trash, stringResource(R.string.delete), tint = palette.danger) { editor.deletePages(editor.selectedPageIndices()) }
            PageSelectionMore(editor, onSharePages, onSavePagesAsPdf, onSavePagesAsImages)
        }
    }
}

/** The current text style with figures of one width, so counts and page numbers line up. */
@Composable
private fun tabular(): TextStyle = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")

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
                    MenuItem(stringResource(R.string.cut), XnotesIcons.cut, onClick = { close(); editor.cutPages(editor.selectedPageIndices()) })
                    MenuItem(stringResource(R.string.duplicate), XnotesIcons.duplicate, onClick = { close(); editor.duplicatePages(editor.selectedPageIndices()) })
                    MenuItem(stringResource(R.string.rotate_ellipsis), XnotesIcons.rotateRight, more = true, onClick = { sub = MENU_ROTATE })
                    val plain = editor.selectedPagesInOrder().firstOrNull { it.pdfPage == null }
                    MenuItem(stringResource(R.string.page_size_ellipsis), XnotesIcons.pageSize, enabled = plain != null, onClick = {
                        close()
                        sizing = plain?.let { editor.pageSizeChoice(editor.pageIndexOf(it)) }
                    })
                    MenuItem(stringResource(R.string.erase), XnotesIcons.eraser, danger = true, onClick = { close(); editor.erasePages(editor.selectedPageIndices()) })
                    MenuItem(stringResource(R.string.extract_to_new_note), XnotesIcons.extract, onClick = {
                        close()
                        extracting = editor.selectedPageIndices() to false
                    })
                    MenuItem(stringResource(R.string.move_to_new_note), XnotesIcons.moveToFolder, onClick = {
                        close()
                        extracting = editor.selectedPageIndices() to true
                    })
                    MenuItem(stringResource(R.string.save_as_ellipsis), XnotesIcons.download, more = true, onClick = { sub = MENU_SAVE })
                    MenuItem(stringResource(R.string.share_ellipsis), XnotesIcons.share, more = true, onClick = { sub = MENU_SHARE })
                }
            }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, desc: String, tint: Rgba = LocalPalette.current.textDim, onClick: () -> Unit) {
    Tooltip(desc) {
        IconButton(onClick = onClick, modifier = Modifier.size(34.dp, 40.dp)) {
            Icon(icon, desc, tint = tint.toComposeColor(), modifier = Modifier.size(19.dp))
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
    val targets = remember(version, editor.contentVersion) { editor.tocTargets() }
    val folded = editor.tocFolded
    val rows = remember(entries, folded) { shownToc(entries, folded) }
    // The entry being read: the one leading furthest on without passing the current page, the deepest of a tie.
    val here = editor.pageIndex
    val reading = remember(targets, here) {
        var best = -1
        for (i in targets.indices) if (targets[i] in 0..here && (best < 0 || targets[i] >= targets[best])) best = i
        best
    }
    // Folded away, it shows on the row that holds it: the last open one before it.
    val marked = remember(rows, reading) { if (reading < 0) -1 else rows.indexOfLast { it.index <= reading } }
    val listState = rememberLazyListState()
    LaunchedEffect(entries) { if (marked > 0) listState.scrollToItem((marked - 3).coerceAtLeast(0)) }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
    ) {
        itemsIndexed(rows, key = { _, r -> r.index }) { i, r ->
            TocLine(editor, r, targets[r.index], marked = i == marked, folded = r.index in folded, first = i == 0)
        }
    }
}

/** An outline entry the Contents tab shows: its [index] among the entries, and whether entries nest under it. */
private class TocRow(val index: Int, val entry: com.xnotes.platform.PdfOutlineEntry, val parent: Boolean)

/** The entries not hidden under a folded one. */
private fun shownToc(entries: List<com.xnotes.platform.PdfOutlineEntry>, folded: Set<Int>): List<TocRow> {
    val rows = ArrayList<TocRow>()
    var shut = Int.MAX_VALUE
    for (i in entries.indices) {
        val e = entries[i]
        if (e.level > shut) continue
        shut = Int.MAX_VALUE
        val parent = (entries.getOrNull(i + 1)?.level ?: -1) > e.level
        rows += TocRow(i, e, parent)
        if (parent && i in folded) shut = e.level
    }
    return rows
}

/** How far each level indents, the deepest one indented, and where the first level's guide runs. */
private val TOC_STEP = 14.dp
private const val TOC_MAX_DEPTH = 5
private val TOC_GUIDE = 18.dp

/** One outline row: a fold chevron when entries nest under it, the title, and the page it opens ([target], or -1 for none). */
@Composable
private fun TocLine(editor: Editor, row: TocRow, target: Int, marked: Boolean, folded: Boolean, first: Boolean) {
    val palette = LocalPalette.current
    val depth = row.entry.level.coerceIn(0, TOC_MAX_DEPTH)
    val guide = palette.border.toComposeColor()
    val ink = when {
        marked -> palette.selectionForeground
        target < 0 -> palette.materialColors.outline
        depth >= 2 -> palette.textDim
        else -> palette.text
    }.toComposeColor()
    Row(
        Modifier
            // A gap before each top-level entry keeps the chapters apart.
            .padding(top = if (depth == 0 && !first) 6.dp else 0.dp)
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (marked) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .drawBehind {
                for (a in 0 until depth) {
                    val x = (TOC_GUIDE + TOC_STEP * a).toPx()
                    drawLine(guide, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            }
            .then(if (target >= 0) Modifier.clickable { editor.goToPage(target) } else Modifier)
            .padding(start = 4.dp + TOC_STEP * depth, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp, 36.dp)
                .then(
                    if (!row.parent) Modifier
                    else Modifier.clickable(role = Role.Button) {
                        editor.tocFolded = if (folded) editor.tocFolded - row.index else editor.tocFolded + row.index
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (row.parent) {
                Icon(
                    if (folded) XnotesIcons.chevronRight else XnotesIcons.chevronDown,
                    stringResource(if (folded) R.string.expand_entry else R.string.collapse_entry),
                    tint = (if (marked) palette.selectionForeground else palette.textDim).toComposeColor(),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            row.entry.title,
            color = ink,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 9.dp),
        )
        if (target >= 0) {
            Text(
                "${target + 1}",
                color = (if (marked) palette.selectionForeground else palette.textDim).toComposeColor(),
                fontSize = 12.sp,
                style = tabular(),
                modifier = Modifier.padding(start = 8.dp),
            )
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
        Row(
            Modifier
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
                .fillMaxWidth()
                .height(44.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(palette.selectionBackground.toComposeColor())
                .clickable(role = Role.Button) { showAdd = true },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(XnotesIcons.plus, null, tint = palette.selectionForeground.toComposeColor(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_bookmark), color = palette.selectionForeground.toComposeColor(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (bookmarks.isEmpty()) {
            EmptyHint(stringResource(R.string.no_bookmarks))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(bookmarks) { i, bm ->
                    if (i > 0) Box(Modifier.padding(start = 44.dp, end = 8.dp).fillMaxWidth().height(1.dp).background(palette.border.toComposeColor()))
                    BookmarkRow(editor, bm, here = bm.page == editor.pageIndex) { renaming = bm }
                }
            }
        }
    }

    renaming?.let { mark ->
        var label by remember(mark) { mutableStateOf(mark.label) }
        val rename = { if (label.isNotBlank()) { editor.renameBookmark(mark, label); renaming = null } }
        SheetDialog(
            title = stringResource(R.string.rename_bookmark),
            onDismiss = { renaming = null },
            foot = { ConfirmButtons(stringResource(R.string.rename), { renaming = null }, enabled = label.isNotBlank(), onConfirm = rename) },
        ) {
            DialogField(label, { label = it }, onDone = rename)
        }
    }

    if (showAdd) {
        val defaultLabel = stringResource(R.string.page_n, editor.pageIndex + 1)
        var label by remember { mutableStateOf(defaultLabel) }
        val add = { editor.addBookmark(label.ifBlank { defaultLabel }); showAdd = false }
        SheetDialog(
            title = stringResource(R.string.add_bookmark),
            onDismiss = { showAdd = false },
            foot = { ConfirmButtons(stringResource(R.string.add), { showAdd = false }, onConfirm = add) },
        ) {
            DialogField(label, { label = it }, onDone = add)
        }
    }
}

/** One bookmark: its ribbon, filled when it marks the page in view, its label and page, and its menu. */
@Composable
private fun BookmarkRow(editor: Editor, mark: com.xnotes.core.model.Bookmark, here: Boolean, onRename: () -> Unit) {
    val palette = LocalPalette.current
    val accent = palette.accent.toComposeColor()
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable { editor.goToPage(mark.page) }.padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (here) XnotesIcons.bookmarkFilled else XnotesIcons.bookmark, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            mark.label,
            color = if (here) accent else palette.text.toComposeColor(),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${mark.page + 1}",
            color = if (here) accent else palette.textDim.toComposeColor(),
            fontSize = 12.sp,
            style = tabular(),
            modifier = Modifier.padding(start = 8.dp),
        )
        BookmarkMenu(editor, mark, onRename)
    }
}

/** A bookmark row's three-dot menu: Rename and Delete. */
@Composable
private fun BookmarkMenu(editor: Editor, mark: com.xnotes.core.model.Bookmark, onRename: () -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Tooltip(stringResource(R.string.bookmark_options)) {
            IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
                Icon(XnotesIcons.more, stringResource(R.string.bookmark_options), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(stringResource(R.string.rename), XnotesIcons.edit, onClick = { open = false; onRename() })
            MenuItem(stringResource(R.string.delete), XnotesIcons.trash, danger = true, onClick = { open = false; editor.removeBookmark(mark) })
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 12.sp)
    }
}
