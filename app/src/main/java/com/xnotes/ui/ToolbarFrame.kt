package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import com.xnotes.settings.ToolbarLanding
import com.xnotes.settings.toolbarLanding
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarSize
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The toolbar look from Preferences, provided once above both panes. */
val LocalToolbarLook = staticCompositionLocalOf { ToolbarLook() }

/** What every toolbar widget sizes itself by, and which way the bar runs. */
internal data class BarMetrics(
    val button: Dp,
    val circle: Dp,
    val icon: Dp,
    val swatch: Dp,
    val rule: Dp,
    val label: TextUnit,
    val vertical: Boolean = false,
) {
    /** Across the bar: one button with a little air either side. */
    val thickness: Dp get() = button + 8.dp
}

internal fun barMetrics(size: ToolbarSize): BarMetrics = when (size) {
    ToolbarSize.COMPACT -> BarMetrics(36.dp, 30.dp, 20.dp, 24.dp, 22.dp, 11.sp)
    ToolbarSize.REGULAR -> BarMetrics(42.dp, 34.dp, 22.dp, 28.dp, 26.dp, 12.sp)
    ToolbarSize.COMFORTABLE -> BarMetrics(50.dp, 40.dp, 26.dp, 34.dp, 30.dp, 13.sp)
}

internal val LocalBar = staticCompositionLocalOf { barMetrics(ToolbarSize.REGULAR) }

/** Where a menu opens from its anchor; a side rail sets it so its menus open beside the rail. */
internal val LocalMenuOffset = compositionLocalOf { DpOffset.Zero }

/** How far a floating bar sits in from the canvas edge. */
private val FLOAT_MARGIN = 8.dp

/** What a floating bar covers of the canvas, for overlays that must stay out from under it. */
internal val LocalToolbarCover = compositionLocalOf { PaddingValues(0.dp) }

/**
 * Lays a pane out with its [bar] along the edge it was last put on and [content] in the rest. A
 * floating bar is handed to [content] to lay over its canvas instead, and [onCover] learns how many
 * px of each edge (left, top, right, bottom) it covers so the canvas keeps its pages clear of it.
 * [strip] runs along the bottom as the bar does, docked or floating, and over a bar that is there
 * too. A long press on the bar lifts it, and [onPlace] hears where it was put down. While
 * [reading], the bar fades off toward its edge, its room goes to the pages and [islands] lie over
 * the canvas in its place, covering nothing.
 */
@Composable
internal fun ColumnScope.ToolbarAround(
    bar: @Composable () -> Unit,
    onCover: (Double, Double, Double, Double) -> Unit,
    onPlace: (ToolbarPosition, floating: Boolean) -> Unit,
    strip: @Composable () -> Unit = {},
    reading: Boolean = false,
    islands: @Composable BoxScope.() -> Unit = {},
    content: @Composable (floatingBar: @Composable BoxScope.() -> Unit) -> Unit,
) {
    val look = LocalToolbarLook.current
    val move = remember { ToolbarMove() }
    move.onPlace = onPlace
    // The canvas keeps its view and the panels their state while the bar changes edge around them.
    val latest by rememberUpdatedState(content)
    val body = remember { movableContentOf<@Composable BoxScope.() -> Unit> { floatingBar -> latest(floatingBar) } }
    val depth = barMetrics(look.size).thickness + FLOAT_MARGIN
    val floats = look.floating && !reading
    val px = if (floats) with(LocalDensity.current) { depth.toPx().toDouble() } else 0.0
    // A floating strip covers the bottom too, over a bar floating there.
    val stripPx = if (floats && move.strip > 0) move.strip + with(LocalDensity.current) { FLOAT_MARGIN.toPx().toDouble() } else 0.0
    val position = look.position
    val fade by animateFloatAsState(if (reading) 0f else 1f, tween(BAR_FADE_MS, easing = Ease), label = "barFade")
    val drift by animateFloatAsState(if (reading) 1f else 0f, tween(BAR_DRIFT_MS, easing = Ease), label = "barDrift")
    // The bar stays only to fade, and takes no touch meanwhile.
    val barUp = !reading || fade > 0f
    val leaving = Modifier.graphicsLayer {
        alpha = fade
        val d = BAR_DRIFT.toPx() * drift
        when (position) {
            ToolbarPosition.TOP -> translationY = -d
            ToolbarPosition.BOTTOM -> translationY = d
            ToolbarPosition.LEFT -> translationX = -d
            ToolbarPosition.RIGHT -> translationX = d
        }
    }
    // Overlays keep clear of the islands along the top as they do of a floating bar.
    val readingCover = PaddingValues(top = ISLAND_MARGIN + barMetrics(look.size).thickness)
    SideEffect {
        onCover(
            if (position == ToolbarPosition.LEFT) px else 0.0,
            if (position == ToolbarPosition.TOP) px else 0.0,
            if (position == ToolbarPosition.RIGHT) px else 0.0,
            (if (position == ToolbarPosition.BOTTOM) px else 0.0) + stripPx,
        )
    }
    CompositionLocalProvider(LocalToolbarMove provides move) {
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { move.area = it }) {
            if (!look.floating) {
                // A docked bar lays nothing over the canvas; only the islands do.
                val docked: @Composable BoxScope.() -> Unit = { islands() }
                // Read-only, it gives its room to the pages at once and fades out over them.
                val dockedBar: @Composable () -> Unit = {
                    if (barUp) Box(Modifier.zIndex(1f).yieldsRoom(reading, position).then(leaving)) {
                        bar()
                        if (reading) TouchSink()
                    }
                }
                // Left and right are the screen's, as the canvas insets are, whatever the language.
                val across = Arrangement.Absolute.Left
                CompositionLocalProvider(LocalToolbarCover provides if (reading) readingCover else PaddingValues(0.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (position) {
                                ToolbarPosition.TOP -> Column(Modifier.fillMaxSize()) { dockedBar(); Box(Modifier.weight(1f).fillMaxWidth()) { body(docked) } }
                                ToolbarPosition.BOTTOM -> body(docked)
                                ToolbarPosition.LEFT -> Row(Modifier.fillMaxSize(), across) { dockedBar(); Box(Modifier.weight(1f).fillMaxHeight()) { body(docked) } }
                                ToolbarPosition.RIGHT -> Row(Modifier.fillMaxSize(), across) { Box(Modifier.weight(1f).fillMaxHeight()) { body(docked) }; dockedBar() }
                            }
                        }
                        Box(Modifier.onSizeChanged { move.strip = it.height }) { strip() }
                        // The bar keeps the edge it is docked on, so the strip shows over it and never moves it.
                        if (position == ToolbarPosition.BOTTOM) dockedBar()
                    }
                }
            } else {
                val cover = when (position) {
                    ToolbarPosition.TOP -> PaddingValues(top = depth)
                    ToolbarPosition.BOTTOM -> PaddingValues(bottom = depth)
                    ToolbarPosition.LEFT -> PaddingValues.Absolute(left = depth)
                    ToolbarPosition.RIGHT -> PaddingValues.Absolute(right = depth)
                }
                CompositionLocalProvider(LocalToolbarCover provides if (reading) readingCover else cover) {
                    Box(Modifier.fillMaxSize()) {
                        body {
                            if (barUp) Box(Modifier.align(edgeOf(position)).padding(FLOAT_MARGIN).then(leaving)) {
                                bar()
                                if (reading) TouchSink()
                            }
                            // The strip floats along the bottom of what the bar leaves clear.
                            if (!reading) Box(Modifier.align(Alignment.BottomCenter).padding(cover).padding(FLOAT_MARGIN).onSizeChanged { move.strip = it.height }) { strip() }
                            islands()
                        }
                    }
                }
            }
            ToolbarLandingMark(move, look.size)
        }
    }
}

/** How the bar leaves for read-only and comes back: a fade, and a drift toward its edge. */
private const val BAR_FADE_MS = 200
private const val BAR_DRIFT_MS = 260
private val BAR_DRIFT = 10.dp

/**
 * Measures the bar as ever, but while [yielding] takes no room along its edge, so the pages have
 * it at once; the bar still draws there, over them, as it fades.
 */
private fun Modifier.yieldsRoom(yielding: Boolean, position: ToolbarPosition): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val across = position.vertical
    layout(if (yielding && across) 0 else p.width, if (yielding && !across) 0 else p.height) {
        p.place(
            if (yielding && position == ToolbarPosition.RIGHT) -p.width else 0,
            if (yielding && position == ToolbarPosition.BOTTOM) -p.height else 0,
        )
    }
}

/** Takes every touch on what it covers, so a fading bar's buttons can no longer be pressed. */
@Composable
private fun BoxScope.TouchSink() {
    Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
}

/** Where a bar on [position] sits in its pane. */
private fun edgeOf(position: ToolbarPosition): Alignment = when (position) {
    ToolbarPosition.TOP -> Alignment.TopCenter
    ToolbarPosition.BOTTOM -> Alignment.BottomCenter
    ToolbarPosition.LEFT -> AbsoluteAlignment.CenterLeft
    ToolbarPosition.RIGHT -> AbsoluteAlignment.CenterRight
}

/** A toolbar lifted by a long press and carried to another edge of its pane. */
internal class ToolbarMove {
    /** The pane the bar is carried about, which the finger is placed in. */
    var area: LayoutCoordinates? = null
    var onPlace: (ToolbarPosition, Boolean) -> Unit = { _, _ -> }

    /** How long the bar is when it hugs its tools, in px along its edge. */
    var hug by mutableFloatStateOf(0f)

    /** How tall the strip along the bottom is, docked or floating, in px; 0 while it is hidden. */
    var strip by mutableIntStateOf(0)

    /** Where the bar would land if put down now; null while it rests. */
    var landing by mutableStateOf<ToolbarLanding?>(null)
        private set
    private var from: ToolbarLanding? = null

    fun lift(look: ToolbarLook) {
        from = ToolbarLanding(look.position, look.floating)
        landing = from
    }

    /** The finger is at [at] in [area]; [thickness] is the bar's, and with [density] gives the bands in px. */
    fun carry(at: Offset, thickness: Float, density: Float) {
        val home = from ?: return
        val size = area?.size ?: return
        landing = toolbarLanding(
            at.x, at.y, size.width.toFloat(), size.height.toFloat(), home,
            pull = thickness + PULL_CLEAR_DP * density, push = PUSH_IN_DP * density, other = OTHER_EDGE_DP * density,
        )
    }

    fun drop() {
        val to = landing
        val home = from
        rest()
        if (to != null && to != home) onPlace(to.position, to.floating)
    }

    fun rest() {
        from = null
        landing = null
    }

    private companion object {
        /** A docked bar floats once the finger is this far past its own thickness from the edge. */
        const val PULL_CLEAR_DP = 24f

        /** A floating bar docks once the finger is pushed this near its edge. */
        const val PUSH_IN_DP = 12f

        /** On an edge the bar did not come from, it docks within this of it. */
        const val OTHER_EDGE_DP = 32f
    }
}

/** The move a toolbar's long press drives; null where a bar cannot be carried. */
internal val LocalToolbarMove = staticCompositionLocalOf<ToolbarMove?> { null }

/** The outline of where a carried bar would land: the whole edge docked, or as long as its tools floating. */
@Composable
private fun BoxScope.ToolbarLandingMark(move: ToolbarMove, size: ToolbarSize) {
    val landing = move.landing ?: return
    val accent = LocalPalette.current.accent.toComposeColor()
    val thickness = barMetrics(size).thickness
    val vertical = landing.position.vertical
    val length = with(LocalDensity.current) { move.hug.toDp() }
    val shape = if (landing.floating) MaterialTheme.shapes.large else RectangleShape
    val frame = when {
        !landing.floating && vertical -> Modifier.fillMaxHeight().width(thickness)
        !landing.floating -> Modifier.fillMaxWidth().height(thickness)
        vertical -> Modifier.padding(FLOAT_MARGIN).height(length).width(thickness)
        else -> Modifier.padding(FLOAT_MARGIN).width(length).height(thickness)
    }
    // A bar docked on another edge stops at the strip docked along the bottom; any other landing has the strip over it.
    val strip = if (landing.floating || landing.position == ToolbarPosition.BOTTOM) 0.dp else with(LocalDensity.current) { move.strip.toDp() }
    Box(Modifier.align(edgeOf(landing.position)).padding(bottom = strip).then(frame).background(accent.copy(alpha = 0.22f), shape).border(2.dp, accent, shape))
}

/** The lifted pill a floating toolbar is drawn as, and the format bar that floats with it. */
@Composable
internal fun Modifier.barPill(): Modifier {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.large
    return shadow(6.dp, shape).clip(shape).background(palette.panel.toComposeColor()).border(1.dp, palette.border.toComposeColor(), shape)
}

/**
 * A long press on the bar lifts it for [move]; the drag that follows carries it, and it is put down
 * where the finger leaves. Until the press has lasted, a tap, a scroll and a colour pulled off the
 * bar are all left alone.
 */
private fun Modifier.carriesToolbar(move: ToolbarMove, look: ToolbarLook, thickness: Dp): Modifier {
    val where = arrayOfNulls<LayoutCoordinates>(1)
    return this
        .onGloballyPositioned { where[0] = it }
        .pointerInput(move, look) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || event.changes.size > 1) break
                        if ((change.position - down.position).getDistance() > slop) break
                    }
                }
                // The loop ended before the wait did: the press let go, or went somewhere.
                if (moved != null) return@awaitEachGesture
                fun inArea(p: Offset): Offset? {
                    val bar = where[0]?.takeIf { it.isAttached } ?: return null
                    val area = move.area?.takeIf { it.isAttached } ?: return null
                    return area.localPositionOf(bar, p)
                }
                move.lift(look)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Nothing under the finger may take this press for its own tap or scroll.
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) {
                            move.drop()
                            break
                        }
                        inArea(change.position)?.let { move.carry(it, thickness.toPx(), density) }
                    }
                } finally {
                    move.rest()
                }
            }
        }
}

/**
 * The strip both toolbars are laid out in: a row along the top or bottom, a column down either
 * side. [content] runs along it, centred while it is short and scrolling once it runs long,
 * [trailing] stays pinned at the end, and [armed] is the tool the gliding circle sits under.
 */
@Composable
internal fun ToolbarFrame(
    armed: Any?,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val look = LocalToolbarLook.current
    val bar = barMetrics(look.size).copy(vertical = look.position.vertical)
    val glide = remember { ToolGlide() }
    val palette = LocalPalette.current
    // Docked, the strip fills its edge; floating, a pill only as long as its tools, up to that edge.
    val surface = if (!look.floating) Modifier.background(palette.panel.toComposeColor()) else Modifier.barPill()
    val stretch = when {
        look.floating -> Modifier
        bar.vertical -> Modifier.fillMaxHeight()
        else -> Modifier.fillMaxWidth()
    }
    // Material places a menu below its anchor; shifting it one bar across and one button up puts it
    // beside the rail, and on a right rail material mirrors it to the left side on its own.
    val menuOffset = if (bar.vertical) DpOffset(bar.thickness, -bar.button) else DpOffset.Zero
    val tipSide = when (look.position) {
        ToolbarPosition.TOP -> TipSide.BELOW
        ToolbarPosition.BOTTOM -> TipSide.ABOVE
        ToolbarPosition.LEFT -> TipSide.RIGHT
        ToolbarPosition.RIGHT -> TipSide.LEFT
    }
    val move = LocalToolbarMove.current
    // A carried bar fades where it stands, so the mark of where it will land reads over it.
    val carried by animateFloatAsState(if (move?.landing != null) 0.45f else 1f, tween(150), label = "toolbarCarried")
    val carry = if (move != null) Modifier.carriesToolbar(move, look, bar.thickness).graphicsLayer { alpha = carried } else Modifier
    var tools by remember { mutableIntStateOf(0) }
    var pinned by remember { mutableIntStateOf(0) }
    if (move != null) SideEffect { move.hug = (tools + pinned).toFloat() }
    CompositionLocalProvider(LocalToolGlide provides glide, LocalBar provides bar, LocalMenuOffset provides menuOffset, LocalTipSide provides tipSide) {
        if (bar.vertical) {
            Column(
                stretch.width(bar.thickness).then(carry).then(surface),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .weight(1f, fill = !look.floating)
                        .verticalScroll(rememberScrollState())
                        .onSizeChanged { tools = it.height }
                        .padding(vertical = 4.dp)
                        .toolGlide(glide, armed),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { content() }
                if (trailing != null) Column(Modifier.onSizeChanged { pinned = it.height }, horizontalAlignment = Alignment.CenterHorizontally) { trailing() }
            }
        } else {
            Row(
                stretch.height(bar.thickness).then(carry).then(surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f, fill = !look.floating)
                        .horizontalScroll(rememberScrollState())
                        .onSizeChanged { tools = it.width }
                        .padding(horizontal = 4.dp)
                        .toolGlide(glide, armed),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { content() }
                if (trailing != null) Row(Modifier.onSizeChanged { pinned = it.width }, verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        }
    }
}
