package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Neither pane of a split may be squeezed below this share of the split axis. */
private const val MIN_PANE_RATIO = 0.18f

/** How wide the divider is to a finger. Mostly empty around the line, so it is easy to catch. */
private val DIVIDER = 16.dp

/** The accent line drawn down the middle of the divider, the same weight as a pane's focus line. */
private val DIVIDER_LINE = 2.dp

/**
 * Makes the open panes opaque to touch. Compose stops hit-testing lower siblings once a higher one
 * is hit, so this node — hit anywhere over the panes — keeps a tap that no child handled from
 * reaching the backstage composed underneath. It never consumes, so children still see every event.
 */
private val SwallowTouches = Modifier.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial)
    }
}

/**
 * Lays the open panes over the backstage. Both open is a split: side by side in landscape, stacked
 * in portrait, with a draggable divider between them. One open is that pane full-screen, and none
 * leaves the backstage showing.
 *
 * The panes are placed by offset and size inside one Box rather than by a Row that becomes a Column,
 * so a pane keeps its composition node — and with it its canvas view, raster caches and GL context —
 * when the split opens, closes or the device turns.
 */
@Composable
internal fun SplitHost(editor: Editor, actions: PaneActions) {
    val second = editor.secondary
    val split = editor.noteOpen && second?.noteOpen == true
    // A pane that closed on its own leaves the other one full-screen; once neither is open the
    // second editor is released, freeing its canvas and GL surfaces.
    LaunchedEffect(editor.noteOpen, second?.noteOpen) {
        if (second != null && !second.noteOpen) editor.releaseClosedSecondary()
    }
    if (!editor.noteOpen && second?.noteOpen != true) return

    val sideBySide = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val ratio = editor.splitRatio.coerceIn(MIN_PANE_RATIO, 1f - MIN_PANE_RATIO)
    val active = editor.active
    val mixed = split && editor.canvasOpen != second?.canvasOpen
    val preferences = remember(editor.prefsVersion) { editor.preferences }
    val sharedChrome = !split || preferences.sharedSplitToolbars
    val useCanvasLayout = if (mixed) preferences.mixedSplitToolbar == com.xnotes.core.tools.SplitToolbarInterface.CANVAS else active.canvasOpen
    val layout = if (useCanvasLayout) editor.canvasToolbarLayout else editor.toolbarLayout
    val occlusion = remember { com.xnotes.ui.SharedToolbarOcclusion() }
    Column(Modifier.fillMaxSize().then(SwallowTouches).imePadding()) {
        if (split) {
            com.xnotes.ui.SplitWorkspaceHeader(editor, second, mixed, onClose = actions.onClosePane)
        }
        val bar: @Composable () -> Unit = { WorkspaceToolbar(active, layout, actions, occlusion) }
        OptionalToolbarAround(sharedChrome, bar) { floatingBar ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fullW = maxWidth
                val fullH = maxHeight
                val full = if (sideBySide) fullW else fullH
                // The panes meet along the split axis and take all of it; the divider is not a gap between
                // them but a handle floating over the seam, so there is no strip of its own colour to show
                // beside the toolbars. Across the axis both run full.
                val firstExtent = if (split) full * ratio else full
                val secondExtent = if (split) full - firstExtent else full

                /** Sizes a pane to [extent] along the split axis and the whole window across it. */
                fun paneSize(extent: Dp) = Modifier.size(
                    if (sideBySide) extent else fullW,
                    if (sideBySide) fullH else extent,
                )

                /** Offsets a pane [along] the split axis from the top-left of the editor area. */
                fun paneOffset(along: Dp) = Modifier.offset(
                    x = if (sideBySide) along else 0.dp,
                    y = if (sideBySide) 0.dp else along,
                )

                if (editor.noteOpen) {
                    EditorPane(
                        editor = editor,
                        app = editor,
                        actions = actions,
                        closable = split,
                        sharedOcclusion = if (sharedChrome) occlusion else null,
                        modifier = paneSize(firstExtent),
                    )
                }
                if (second?.noteOpen == true) {
                    EditorPane(
                        editor = second,
                        app = editor,
                        actions = actions,
                        closable = split,
                        sharedOcclusion = if (sharedChrome) occlusion else null,
                        modifier = paneOffset(if (split) firstExtent else 0.dp).then(paneSize(secondExtent)),
                    )
                }
                // Composed last so it sits over both panes and catches the drag before either of them.
                if (split) {
                    SplitDivider(
                        sideBySide = sideBySide,
                        extentPx = with(LocalDensity.current) { full.toPx() },
                        ratio = editor.splitRatio,
                        onRatio = { editor.splitRatio = it },
                        modifier = paneOffset((firstExtent - DIVIDER / 2).coerceAtLeast(0.dp))
                            .then(paneSize(DIVIDER)),
                    )
                }
                Box(Modifier.fillMaxSize().zIndex(1f)) { floatingBar() }
            }
        }
        if (!active.canvasOpen) com.xnotes.ui.TextFormatBar(active)
    }
}

/** A toolbar frame can surround the workspace or each pane without duplicating surface code. */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.OptionalToolbarAround(
    visible: Boolean,
    bar: @Composable () -> Unit,
    content: @Composable (floatingBar: @Composable BoxScope.() -> Unit) -> Unit,
) {
    if (visible) ToolbarAround(bar, onCover = { _, _, _, _ -> }, content = content)
    else Box(Modifier.weight(1f).fillMaxWidth()) { content {} }
}

/**
 * The handle over the seam between two split panes. Dragging it moves the boundary along the split
 * axis, keeping both panes at least [MIN_PANE_RATIO] of it; double-tapping puts it back in the
 * middle. Only the line is painted — the margin either side of it is what the finger catches, and it
 * stays transparent so the panes it lies over show through.
 */
@Composable
private fun SplitDivider(
    sideBySide: Boolean,
    extentPx: Float,
    ratio: Float,
    onRatio: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    // Read inside the long-lived drag gesture, which does not restart as the ratio moves.
    val ratioNow = rememberUpdatedState(ratio)
    val onRatioNow = rememberUpdatedState(onRatio)
    var dragged by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .pointerInput(sideBySide, extentPx) {
                detectDragGestures(
                    onDragStart = { dragged = ratioNow.value },
                    onDrag = { change, drag ->
                        change.consume()
                        if (extentPx <= 0f) return@detectDragGestures
                        val along = if (sideBySide) drag.x else drag.y
                        dragged = (dragged + along / extentPx).coerceIn(MIN_PANE_RATIO, 1f - MIN_PANE_RATIO)
                        onRatioNow.value(dragged)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onRatioNow.value(0.5f) })
            },
        contentAlignment = Alignment.Center,
    ) {
        // The line itself: thin, accent, and running the whole way, so it closes the frame the two
        // panes' focus lines start along their toolbars. The empty margin either side of it is what
        // the finger actually catches.
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (sideBySide) (DIVIDER - DIVIDER_LINE) / 2 else 0.dp,
                    vertical = if (sideBySide) 0.dp else (DIVIDER - DIVIDER_LINE) / 2,
                )
                .clip(RoundedCornerShape(DIVIDER_LINE / 2))
                .background(palette.accent.toComposeColor()),
        )
    }
}
