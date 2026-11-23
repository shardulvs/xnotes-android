package com.xnotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.platform.PdfOutlineEntry
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/*
 * What a read-only note shows in place of its toolbar: islands on the floating bar's pill, over
 * the page. Home and the side panel, the title and the gear along the top; the page arrows, the
 * page number and a scrubber along the bottom. A tap on the page shows or hides all but the gear,
 * and moving the page hides them.
 */

/** How far the islands sit in from the canvas edges. */
internal val ISLAND_MARGIN = 12.dp

/** The air inside an island's ends, and the gap between an island and the panel it opens. */
private val ISLAND_PAD = 3.dp
private val PANEL_GAP = 8.dp

/** The widest the bottom island grows. */
private val BOTTOM_MAX = 560.dp

/** The title drops out once less than this is left between the top corners, and keeps this clear of them. */
private val TITLE_MIN = 120.dp
private val TITLE_GAP = 10.dp

/** How far the top and the bottom islands drift toward their edge as they fade. */
private val TOP_DRIFT = 6.dp
private val BOTTOM_DRIFT = 8.dp

/** Islands answer a tap at once, coming and going alike. */
private const val SHOW_MS = 150
private const val SHOW_DRIFT_MS = 200
private const val HIDE_MS = 150

/** The glide the islands settle into place with. */
private val Settle: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** The page number's room, wide enough for "188 of 188". */
private val NUMBER_W = 92.dp

/** Chapter marks on the scrubber, up to this many; past it they would only speckle the line. */
private const val MAX_CHAPTERS = 24

/** The scrubber's touch reaches this far past each end of its line, and its line keeps this clear of its neighbours. */
private val TRACK_INSET = 8.dp
private val TRACK_GAP = 14.dp

/** The card over the scrubber, and the most room its page takes either way. */
private val CARD_W = 176.dp
private val THUMB_W = 92.dp
private val THUMB_H = 130.dp

/** How long the finger rests on a page before its thumbnail is drawn, so a fast scrub draws none. */
private const val THUMB_REST_MS = 90L

/** The width side-panel thumbnails render at, so the card and the panel share one cache. */
private const val THUMB_PX = 300

private const val HOME = "home"
private const val TITLE = "title"
private const val GEAR = "gear"

/** One pane's reading islands: up or faded, and what holds them up. */
internal class ReadingChrome {
    var awake by mutableStateOf(true)
    var panelOpen by mutableStateOf(false)
    var playerOpen by mutableStateOf(false)

    /** The page under the finger while it scrubs, or -1. */
    var scrubPage by mutableIntStateOf(-1)

    /** True while the page number is a field. */
    var typing by mutableStateOf(false)

    val held: Boolean get() = panelOpen || playerOpen || scrubPage >= 0 || typing
}

/** How an island fades: gone at once once the note is written in again, quickly otherwise. */
private fun fade(reading: Boolean, shown: Boolean, ms: Int, easing: Easing): AnimationSpec<Float> = when {
    !reading -> snap()
    shown -> tween(ms, easing = easing)
    else -> tween(HIDE_MS, easing = EaseIn)
}

/** The islands over a read-only note's pages; nothing while the note is written in. */
@Composable
internal fun BoxScope.ReadingIslands(
    editor: Editor,
    chrome: ReadingChrome,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onImportTemplate: () -> Unit,
    onHome: () -> Unit,
    onClose: (() -> Unit)?,
    refocus: () -> Unit,
) {
    val reading = editor.readOnly
    LaunchedEffect(reading) {
        chrome.awake = true
        chrome.scrubPage = -1
        chrome.typing = false
    }
    // A tap on the pages shows or hides the islands, unless it only put the page field away; moving them hides the islands.
    DisposableEffect(editor) {
        var heldAtPress = false
        editor.onCanvasPress = {
            heldAtPress = chrome.held
            chrome.typing = false
        }
        editor.onCanvasTap = { if (editor.readOnly && !heldAtPress) chrome.awake = !chrome.awake }
        editor.onCanvasDrag = { if (editor.readOnly) chrome.awake = false }
        onDispose {
            editor.onCanvasPress = null
            editor.onCanvasTap = null
            editor.onCanvasDrag = null
        }
    }
    val shown = reading && chrome.awake
    val alpha = animateFloatAsState(if (shown) 1f else 0f, fade(reading, shown, SHOW_MS, EaseOut), label = "islandsAlpha")
    val drift = animateFloatAsState(if (shown) 0f else 1f, fade(reading, shown, SHOW_DRIFT_MS, Settle), label = "islandsDrift")
    val gear = animateFloatAsState(if (reading) 1f else 0f, if (reading) tween(SHOW_MS, easing = EaseOut) else snap(), label = "gearAlpha")
    // Once faded out they leave, so a touch where they stood reaches the page.
    val gone by remember { derivedStateOf { alpha.value == 0f } }
    if (!reading) return
    val up = shown || !gone

    val marks = remember(editor.tocVersion, editor.contentVersion) { editor.tableOfContentsPages() }
    val chapters = remember(marks) {
        val top = marks.minOfOrNull { it.first.level } ?: 0
        marks.filter { it.first.level == top && it.second > 0 }.map { it.second }.distinct().takeIf { it.size <= MAX_CHAPTERS }.orEmpty()
    }
    val root = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var trackX by remember { mutableFloatStateOf(0f) }
    var trackW by remember { mutableFloatStateOf(0f) }
    val inset = with(LocalDensity.current) { TRACK_INSET.toPx() }
    val bar = barMetrics(LocalToolbarLook.current.size)
    CompositionLocalProvider(LocalBar provides bar) {
        Box(Modifier.fillMaxSize().onPlaced { root[0] = it }) {
            TopIslands {
                if (up) {
                    HomeIsland(editor, chrome, onHome, Modifier.layoutId(HOME).drifting(alpha, drift, -TOP_DRIFT))
                    TitleIsland(editor, Modifier.layoutId(TITLE).drifting(alpha, drift, -TOP_DRIFT))
                }
                GearIsland(editor, chrome, fullscreen, onToggleFullscreen, onImportTemplate, onClose, Modifier.layoutId(GEAR).graphicsLayer { this.alpha = gear.value })
            }
            if (up) {
                BottomIsland(editor, chrome, chapters, Modifier.drifting(alpha, drift, BOTTOM_DRIFT), refocus) { c ->
                    val r = root[0]?.takeIf { it.isAttached } ?: return@BottomIsland
                    trackX = r.localPositionOf(c, Offset.Zero).x + inset
                    trackW = c.size.width - 2 * inset
                }
            }
            ScrubCard(editor, chrome.scrubPage, marks) { page ->
                val last = (editor.pageCount - 1).coerceAtLeast(1)
                trackX + trackW * page / last
            }
        }
    }
}

/** An island as it fades: [alpha], and [drift] of the way to [toward] from its place. */
private fun Modifier.drifting(alpha: State<Float>, drift: State<Float>, toward: Dp): Modifier = graphicsLayer {
    this.alpha = alpha.value
    translationY = toward.toPx() * drift.value
}

/**
 * The row along the top: the home island at the start, the gear at the end, and between them the
 * title, centred and given what room is left, or left out when that is too little.
 */
@Composable
private fun TopIslands(islands: @Composable () -> Unit) {
    Layout(
        content = islands,
        modifier = Modifier.fillMaxWidth().padding(ISLAND_MARGIN),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val home = measurables.firstOrNull { it.layoutId == HOME }?.measure(loose)
        val end = measurables.firstOrNull { it.layoutId == GEAR }?.measure(loose)
        val side = maxOf(home?.width ?: 0, end?.width ?: 0) + TITLE_GAP.roundToPx()
        val room = constraints.maxWidth - 2 * side
        val title = if (room >= TITLE_MIN.roundToPx()) measurables.firstOrNull { it.layoutId == TITLE }?.measure(loose.copy(maxWidth = room)) else null
        val height = maxOf(home?.height ?: 0, end?.height ?: 0, title?.height ?: 0)
        layout(constraints.maxWidth, height) {
            home?.place(0, 0)
            title?.place((constraints.maxWidth - title.width) / 2, 0)
            end?.place(constraints.maxWidth - end.width, 0)
        }
    }
}

/** An island: a row of the toolbar's buttons on its floating pill. */
@Composable
private fun Island(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(LocalBar.current.thickness).barPill().keepsTouches().padding(horizontal = ISLAND_PAD),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Holds a touch that lands on an island between its buttons, so the page under it stays put. */
private fun Modifier.keepsTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

@Composable
private fun HomeIsland(editor: Editor, chrome: ReadingChrome, onHome: () -> Unit, modifier: Modifier) {
    CompositionLocalProvider(LocalTipSide provides TipSide.BELOW) {
        Island(modifier) {
            ToolbarIcon(XnotesIcons.prev, stringResource(R.string.toolbar_home), onClick = onHome)
            ToolbarIcon(XnotesIcons.sidebar, stringResource(R.string.side_panel), active = editor.sidebarVisible, shortcut = Keys.SIDEBAR) { editor.toggleSidebar() }
            AudioPlayback.session?.takeIf { it.owner === editor }?.let { session -> PlaybackChip(session) { chrome.playerOpen = it } }
        }
    }
}

/** The note's name beside an open book, which says the note is being read. */
@Composable
private fun TitleIsland(editor: Editor, modifier: Modifier) {
    val palette = LocalPalette.current
    val doc = editor.state.document
    val name = if (doc.displayName == null && doc.path == null) stringResource(R.string.untitled) else editor.title
    Row(
        modifier.height(LocalBar.current.thickness).barPill().keepsTouches().padding(start = 15.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(XnotesIcons.bookOpen, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(17.dp))
        Text(name, color = palette.text.toComposeColor(), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The gear, which never fades, and beside it in a split the pane's close. */
@Composable
private fun GearIsland(
    editor: Editor,
    chrome: ReadingChrome,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onImportTemplate: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier,
) {
    val bar = LocalBar.current
    CompositionLocalProvider(LocalTipSide provides TipSide.BELOW) {
        Island(modifier) {
            // The panel hangs just under the island, its end lined up with the island's.
            CompositionLocalProvider(LocalMenuOffset provides DpOffset(ISLAND_PAD, (bar.thickness - bar.button) / 2 + PANEL_GAP)) {
                NoteSettingsButton(editor, fullscreen, onToggleFullscreen, onImportTemplate) { chrome.panelOpen = it }
            }
            if (onClose != null) {
                Separator()
                ToolbarIcon(XnotesIcons.close, stringResource(R.string.close_pane), onClick = onClose)
            }
        }
    }
}

/**
 * The page arrows round the page number and the scrubber; [onTrack] hears where the scrubber lies,
 * and [refocus] gives the pane back the keyboard once the page field goes.
 */
@Composable
private fun BoxScope.BottomIsland(
    editor: Editor,
    chrome: ReadingChrome,
    chapters: List<Int>,
    modifier: Modifier,
    refocus: () -> Unit,
    onTrack: (LayoutCoordinates) -> Unit,
) {
    val count = editor.pageCount
    val last = (count - 1).coerceAtLeast(0)
    val scrubbing = chrome.scrubPage >= 0
    val index = if (scrubbing) chrome.scrubPage else editor.pageIndex.coerceIn(0, last)
    var text by remember { mutableStateOf("") }
    fun jump() {
        text.toIntOrNull()?.let { editor.goToPage((it - 1).coerceIn(0, last)) }
        chrome.typing = false
    }
    // While the number is a field it has the keyboard, so its digits and Delete are its own.
    val typing = chrome.typing
    DisposableEffect(typing) {
        if (typing) editor.panelFieldFocused = true
        onDispose {
            if (typing) {
                editor.panelFieldFocused = false
                refocus()
            }
        }
    }
    CompositionLocalProvider(LocalTipSide provides TipSide.ABOVE) {
        Island(Modifier.align(Alignment.BottomCenter).padding(ISLAND_MARGIN).widthIn(max = BOTTOM_MAX).fillMaxWidth().then(modifier)) {
            ToolbarIcon(XnotesIcons.prev, stringResource(R.string.previous_page), enabled = index > 0, shortcut = Keys.PREV_PAGE) { editor.prevPage() }
            if (typing) {
                PageField(text, { text = it }, count, ::jump) { chrome.typing = false }
            } else {
                PageNumber(index, count) {
                    text = "${editor.pageIndex + 1}"
                    chrome.typing = true
                }
            }
            PageScrubber(
                index = index,
                last = last,
                chapters = chapters,
                scrubbing = scrubbing,
                onScrub = { chrome.scrubPage = it },
                onLetGo = {
                    val page = chrome.scrubPage
                    chrome.scrubPage = -1
                    if (page >= 0) editor.goToPage(page)
                },
                modifier = Modifier.weight(1f).padding(horizontal = TRACK_GAP - TRACK_INSET).onGloballyPositioned(onTrack),
            )
            if (typing) GoButton(::jump)
            else ToolbarIcon(XnotesIcons.next, stringResource(R.string.next_page), enabled = index < last, shortcut = Keys.NEXT_PAGE) { editor.nextPage() }
        }
    }
}

/** "88 of 186"; a tap turns it into a field to type a page into. */
@Composable
private fun PageNumber(index: Int, count: Int, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val figures = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
    Row(
        Modifier
            .widthIn(min = NUMBER_W)
            .height(LocalBar.current.button)
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.title_go_to_page), onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", color = palette.text.toComposeColor(), fontSize = 16.sp, fontWeight = FontWeight.Medium, style = figures)
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.page_of_count, count), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
    }
}

/** The page number as a field, sized to the note's longest page number. */
@Composable
private fun PageField(text: String, onText: (String) -> Unit, count: Int, onGo: () -> Unit, onBlur: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    val digits = count.toString().length.coerceAtLeast(2)
    Row(
        Modifier.widthIn(min = NUMBER_W).height(LocalBar.current.button),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(32.dp)
                .clip(shape)
                .background(palette.bg.toComposeColor())
                .border(2.dp, palette.accent.toComposeColor(), shape)
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            NativeField(
                value = text,
                onText = onText,
                modifier = Modifier.width((digits * 9 + 4).dp),
                numeric = true,
                maxLen = digits,
                endAlign = true,
                autoFocus = true,
                textSp = 15f,
                onDone = onGo,
                onBlur = onBlur,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.page_of_count, count), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
    }
}

/** Go, in the next arrow's place while a page is being typed. */
@Composable
private fun GoButton(onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .padding(end = 4.dp)
            .height(LocalBar.current.circle)
            .clip(CircleShape)
            .background(palette.accent.toComposeColor())
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.go), color = palette.onAccent.toComposeColor(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Where the page sits in the note, as a line the finger drags along: read so far up to the thumb,
 * a nick where each chapter starts. A press puts the thumb under the finger; letting go turns to
 * that page.
 */
@Composable
private fun PageScrubber(
    index: Int,
    last: Int,
    chapters: List<Int>,
    scrubbing: Boolean,
    onScrub: (Int) -> Unit,
    onLetGo: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val on = palette.accent.toComposeColor()
    val past = on.copy(alpha = 0.4f)
    val rest = palette.border.toComposeColor()
    val cut = palette.panel.toComposeColor()
    val thumb by animateDpAsState(if (scrubbing) 18.dp else 14.dp, tween(120), label = "scrubThumb")
    val scrub by rememberUpdatedState(onScrub)
    val letGo by rememberUpdatedState(onLetGo)
    val label = stringResource(R.string.caption_page)
    Canvas(
        modifier
            .fillMaxHeight()
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..last.toFloat().coerceAtLeast(1f), steps = (last - 1).coerceAtLeast(0))
                setProgress { v ->
                    scrub(v.roundToInt().coerceIn(0, last))
                    letGo()
                    true
                }
            }
            .pointerInput(last) {
                val inset = TRACK_INSET.toPx()
                fun pageAt(x: Float): Int {
                    if (last == 0) return 0
                    val f = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                    return (f * last).roundToInt()
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    scrub(pageAt(down.position.x))
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            scrub(pageAt(change.position.x))
                        }
                    } finally {
                        letGo()
                    }
                }
            },
    ) {
        val inset = TRACK_INSET.toPx()
        val h = 4.dp.toPx()
        val w = size.width - 2 * inset
        val cy = size.height / 2
        val top = cy - h / 2
        val f = if (last == 0) 0f else index / last.toFloat()
        val r = CornerRadius(h / 2)
        drawRoundRect(rest, Offset(inset, top), Size(w, h), r)
        drawRoundRect(past, Offset(inset, top), Size(w * f, h), r)
        val nick = 2.dp.toPx()
        for (p in chapters) {
            val x = inset + w * p / last.coerceAtLeast(1)
            drawRect(cut, Offset(x - nick / 2, top - 1.dp.toPx()), Size(nick, h + 2.dp.toPx()))
        }
        val cx = inset + w * f
        val tr = thumb.toPx() / 2
        drawCircle(cut, tr + 3.dp.toPx(), Offset(cx, cy))
        drawCircle(on, tr, Offset(cx, cy))
    }
}

/** The heading [page] falls under: the outline entry that starts last at or before it. */
private fun sectionAt(marks: List<Pair<PdfOutlineEntry, Int>>, page: Int): String? {
    var best: Pair<PdfOutlineEntry, Int>? = null
    for (m in marks) if (m.second <= page && (best == null || m.second >= best.second)) best = m
    return best?.first?.title
}

/** The card over the scrubber while it is dragged: the page under the finger, its number and its heading. */
@Composable
private fun BoxScope.ScrubCard(editor: Editor, scrubPage: Int, marks: List<Pair<PdfOutlineEntry, Int>>, thumbX: (Int) -> Float) {
    val palette = LocalPalette.current
    val bar = LocalBar.current
    val density = LocalDensity.current
    // The card keeps the page it last showed while it goes.
    val held = remember { mutableIntStateOf(0) }
    SideEffect { if (scrubPage >= 0) held.intValue = scrubPage }
    val page = if (scrubPage >= 0) scrubPage else held.intValue
    val margin = with(density) { 8.dp.roundToPx() }
    val lift = with(density) { 6.dp.roundToPx() }
    val shape = MaterialTheme.shapes.large
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .padding(bottom = ISLAND_MARGIN + bar.thickness + 10.dp)
            .layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                val x = (thumbX(page) - p.width / 2f).roundToInt().coerceIn(margin, (c.maxWidth - margin - p.width).coerceAtLeast(margin))
                layout(c.maxWidth, p.height) { p.place(x, 0) }
            },
    ) {
        AnimatedVisibility(
            visible = scrubPage >= 0,
            enter = fadeIn(tween(120)) + scaleIn(tween(160), 0.97f, TransformOrigin(0.5f, 1f)) + slideInVertically(tween(160)) { lift },
            exit = fadeOut(tween(120)) + scaleOut(tween(160), 0.97f, TransformOrigin(0.5f, 1f)) + slideOutVertically(tween(160)) { lift },
        ) {
            Column(
                Modifier
                    .width(CARD_W)
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .background(palette.panel.toComposeColor())
                    .border(1.dp, palette.border.toComposeColor(), shape)
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 11.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                PageThumbnail(editor, page)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.page_n, page + 1), color = palette.text.toComposeColor(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    sectionAt(marks, page)?.let { section ->
                        Text(
                            section,
                            color = palette.textDim.toComposeColor(),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Page [index] as the side panel draws it, held to the card's room; drawn once the finger rests on it. */
@Composable
private fun PageThumbnail(editor: Editor, index: Int) {
    val palette = LocalPalette.current
    val page = editor.state.document.pages.getOrNull(index) ?: return
    val aspect = editor.pageAspectRatio(page)
    val (w, h) = if (aspect * THUMB_W.value > THUMB_H.value) (THUMB_H / aspect) to THUMB_H else THUMB_W to (THUMB_W * aspect)
    val bitmap by produceState<ImageBitmap?>(editor.cachedPageThumbnail(page), page, editor.contentVersion) {
        value = editor.cachedPageThumbnail(page)
        if (value == null) {
            delay(THUMB_REST_MS)
            value = editor.pageThumbnail(page, THUMB_PX)
        }
    }
    Box(
        Modifier
            .size(w, h)
            .background(editor.state.paperColor(page).toComposeColor())
            .border(1.dp, palette.border.toComposeColor()),
    ) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
