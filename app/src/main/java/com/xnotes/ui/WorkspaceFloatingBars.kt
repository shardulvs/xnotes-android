package com.xnotes.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarAnchorResolver
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarScale
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarSize
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Hold anywhere on a toolbar's container to pick it up. Ordinary taps still reach
 * the tool buttons; the drawing canvas receives events outside the bars unchanged.
 * A completed move snaps to a safe 3x3 anchor and is persisted by the caller.
 */
@Composable
fun ToolbarDragSurface(
    id: String,
    anchor: ToolbarAnchor,
    movable: Boolean,
    paneBounds: Rect,
    onMoveBar: (String, ToolbarAnchor) -> Unit,
    /** Optional preview callbacks: shared long-press move gesture handles lane reordering. */
    onDragStart: ((Offset) -> Unit)? = null,
    onDragPosition: ((Offset) -> Unit)? = null,
    onDrop: ((Offset) -> Boolean)? = null,
    onDragCancel: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val moveBarNow by rememberUpdatedState(onMoveBar)
    val paneBoundsNow by rememberUpdatedState(paneBounds)
    val startCallback by rememberUpdatedState(onDragStart)
    val moveCallback by rememberUpdatedState(onDragPosition)
    val dropCallback by rememberUpdatedState(onDrop)
    val cancelCallback by rememberUpdatedState(onDragCancel)
    var drag by remember(id) { mutableStateOf(Offset.Zero) }
    var dragging by remember(id) { mutableStateOf(false) }
    var barBounds by remember(id) { mutableStateOf(Rect.Zero) }
    var dragStartInRoot by remember(id) { mutableStateOf(Offset.Zero) }
    // Preserve the released position until the parent acknowledges a new anchor.
    // Resetting drag *before* onMoveBar published its workspace caused the bar to
    // briefly redraw at its previous slot, then snap forward (the visible flick).
    var pendingAnchor by remember(id) { mutableStateOf<ToolbarAnchor?>(null) }
    LaunchedEffect(anchor, pendingAnchor) {
        val pending = pendingAnchor ?: return@LaunchedEffect
        if (anchor == pending) {
            // Modifier.offset already renders zero for the acknowledged anchor.
            drag = Offset.Zero
            pendingAnchor = null
        } else {
            // An invalid/rejected move must not leave a stranded toolbar forever.
            delay(1000)
            if (pendingAnchor == pending) {
                drag = Offset.Zero
                pendingAnchor = null
            }
        }
    }
    // Keep the active pointer stream alive while the preview recomposes and rescales.
    // Pane bounds are read via updated state instead of restarting pointerInput on each
    // layout measurement. Such restarts used to cancel the drag on its first frame.
    val gesture = if (!movable) Modifier else Modifier.pointerInput(id) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id)
            if (held != null) {
                pendingAnchor = null
                drag = Offset.Zero
                dragging = true
                dragStartInRoot = barBounds.topLeft + held.position
                startCallback?.invoke(dragStartInRoot)
                var canceled = false
                while (true) {
                    // A child toolbar element may consume Main-pass changes; observe
                    // Initial so the long-pressed parent can keep the drag stream.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == held.id }
                    if (change == null) {
                        canceled = true
                        break
                    }
                    if (!change.pressed) break
                    val delta = change.position - change.previousPosition
                    if (delta != Offset.Zero) {
                        change.consume()
                        drag += delta
                        moveCallback?.invoke(dragStartInRoot + drag)
                    }
                }
                if (canceled) {
                    pendingAnchor = null
                    drag = Offset.Zero
                    dragging = false
                    cancelCallback?.invoke()
                } else {
                    val moved = drag
                    val droppedAt = dragStartInRoot + moved
                    val handledByPreview = dropCallback?.invoke(droppedAt) == true
                    val handled = moved.getDistance() >= 12f && handledByPreview
                    dragging = false
                    if (!handled && moved.getDistance() >= 12f && barBounds != Rect.Zero && paneBoundsNow != Rect.Zero) {
                        val center = barBounds.center + moved - paneBoundsNow.topLeft
                        val target = ToolbarAnchorResolver.nearest(
                            center.x, center.y, paneBoundsNow.width, paneBoundsNow.height,
                        )
                        if (target != null && target != anchor) {
                            // Stay under the cursor until the new anchor is rendered.
                            // Reordering uses a different optimistic preview transaction.
                            pendingAnchor = target
                            moveBarNow(id, target)
                        } else {
                            drag = Offset.Zero
                            if (target != null) moveBarNow(id, target)
                        }
                    } else {
                        drag = Offset.Zero
                    }
                }
            }
        }
    }
    // Acknowledgement changes the anchor and parent placement together; discard
    // the old drag offset in that SAME composition to avoid a second overshoot.
    val visualDrag = if (pendingAnchor != null && anchor == pendingAnchor) Offset.Zero else drag
    Box(
        Modifier
            .offset { IntOffset(visualDrag.x.roundToInt(), visualDrag.y.roundToInt()) }
            .testTag("toolbar-drag-surface-$id")
            // A docked bar is an earlier sibling of the canvas. Lift it while moving so the
            // canvas (and adjacent split panes) cannot paint over the dragged toolbar.
            .zIndex(if (dragging || (pendingAnchor != null && anchor != pendingAnchor)) 10f else 0f)
            .onGloballyPositioned { if (drag == Offset.Zero) barBounds = it.boundsInRoot() }
            .then(gesture),
    ) {
        content()
    }
}

/** Floating bars auto-pack along screen edges and overflow to free lanes, never covering each other. */
@Composable
fun BoxScope.WorkspaceFloatingBars(
    workspace: ToolbarWorkspace,
    onMoveBar: (id: String, anchor: ToolbarAnchor) -> Unit,
    primaryContent: @Composable () -> Unit,
    content: @Composable (ToolbarBar) -> Unit,
    colorCount: Int = 5,
) {
    var paneBounds by remember { mutableStateOf(Rect.Zero) }
    val baseLook = LocalToolbarLook.current
    val showPrimary = workspace.hasMultiDock(baseLook)
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(10f).onGloballyPositioned { paneBounds = it.boundsInRoot() }) {
        val plan = toolbarFloatingPlan(workspace, baseLook, maxWidth, maxHeight, colorCount)
        for ((index, bar) in workspace.bars.withIndex()) {
            val placement = plan.placement(bar.id) ?: continue
            // Legacy floating primary is already rendered by ToolbarAround.
            if (index == 0 && !bar.geometry.floating && !showPrimary) continue
            key(bar.id) {
                val look = ToolbarLook(
                    position = placement.edge.toolbarPosition(),
                    size = toolbarBarSize(bar, index, baseLook),
                    floating = true,
                )
                Box(Modifier.offset(x = placement.x.dp, y = placement.y.dp)
                    .width(placement.width.dp).height(placement.height.dp)) {
                    ToolbarDragSurface(
                        id = bar.id,
                        anchor = bar.geometry.anchor,
                        movable = bar.geometry.movable,
                        paneBounds = paneBounds,
                        onMoveBar = onMoveBar,
                    ) {
                        CompositionLocalProvider(LocalToolbarLook provides look) {
                            if (index == 0) primaryContent() else content(bar)
                        }
                    }
                }
            }
        }
    }
}

internal fun ToolbarAnchor.alignment(): Alignment = when (this) {
    ToolbarAnchor.TOP_LEFT -> AbsoluteAlignment.TopLeft
    ToolbarAnchor.TOP_CENTER -> Alignment.TopCenter
    ToolbarAnchor.TOP_RIGHT -> AbsoluteAlignment.TopRight
    ToolbarAnchor.CENTER_LEFT -> AbsoluteAlignment.CenterLeft
    ToolbarAnchor.CENTER -> Alignment.Center
    ToolbarAnchor.CENTER_RIGHT -> AbsoluteAlignment.CenterRight
    ToolbarAnchor.BOTTOM_LEFT -> AbsoluteAlignment.BottomLeft
    ToolbarAnchor.BOTTOM_CENTER -> Alignment.BottomCenter
    ToolbarAnchor.BOTTOM_RIGHT -> AbsoluteAlignment.BottomRight
}

internal fun ToolbarAnchor.toolbarPosition(): ToolbarPosition = when (this) {
    ToolbarAnchor.CENTER_LEFT -> ToolbarPosition.LEFT
    ToolbarAnchor.CENTER_RIGHT -> ToolbarPosition.RIGHT
    ToolbarAnchor.BOTTOM_LEFT, ToolbarAnchor.BOTTOM_CENTER, ToolbarAnchor.BOTTOM_RIGHT -> ToolbarPosition.BOTTOM
    else -> ToolbarPosition.TOP
}
