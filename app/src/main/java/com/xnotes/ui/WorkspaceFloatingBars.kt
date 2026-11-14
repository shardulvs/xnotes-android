package com.xnotes.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDropPosition
import com.xnotes.core.tools.ToolbarAnchorResolver
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarScale
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarSize
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Keep the visible toolbar inside its window even when the pointer reaches an edge.
 * Hit testing still uses the raw pointer; only the rendered drag is constrained.
 * Otherwise a vertical bar grabbed near its centre projects above TOP, then jumps
 * back inside the window while morphing beside a toolbar already docked there. */
internal fun toolbarVisibleDrag(offset: Offset, slot: Rect, pane: Rect): Offset {
    if (slot == Rect.Zero || pane == Rect.Zero || pane.width <= 0f || pane.height <= 0f ||
        !offset.x.isFinite() || !offset.y.isFinite()) return offset
    fun fit(raw: Float, from: Float, to: Float, low: Float, high: Float): Float {
        val minimum = low - from
        val maximum = high - to
        // For an oversized bar, preserve the pointer motion while keeping the
        // viewport covered instead of jumping to the centre at gesture start.
        return if (minimum <= maximum) raw.coerceIn(minimum, maximum)
        else raw.coerceIn(maximum, minimum)
    }
    return Offset(
        fit(offset.x, slot.left, slot.right, pane.left, pane.right),
        fit(offset.y, slot.top, slot.bottom, pane.top, pane.bottom),
    )
}

/** End-of-morph translation uses the invariant outer slot, not the child
 * toolbar's initial inner bounds. Horizontal/vertical alignment changes that
 * inset during reflow; subtracting it from the target leaves a final-frame jump. */
internal fun toolbarSettleDestinationOffset(
    targetInRoot: Offset, parentSlotInRoot: Offset?, measuredContentSlot: Rect,
): Offset = targetInRoot - (parentSlotInRoot ?: measuredContentSlot.topLeft)

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
    onMoveBar: (String, ToolbarAnchor, ToolbarDockAlignment) -> Unit,
    /** Current parent slot root position, derived from the layout plan in composition.
     * Unlike onGloballyPositioned, it updates in the same pass as the new anchor. */
    parentSlotInRoot: Offset? = null,
    currentAlignment: ToolbarDockAlignment = ToolbarDockAlignment.CENTER,
    /** Optional preview callbacks: shared long-press move gesture handles lane reordering. */
    onDragStart: ((Offset) -> Unit)? = null,
    onDragPosition: ((Offset) -> Unit)? = null,
    onDrop: ((Offset) -> Boolean)? = null,
    onDragCancel: (() -> Unit)? = null,
    /** Optional projected destination in root pixels, shared with the drop indicator. */
    snapTargetInRoot: ((ToolbarDropPosition) -> Offset?)? = null,
    /** Called during dragging only; never changes the saved workspace. */
    onPlacementPreview: ((ToolbarDropPosition?) -> Unit)? = null,
    /** Starts and clears the destination's size change with the SAME 220-ms snap. */
    onSettleTarget: ((ToolbarDropPosition?) -> Unit)? = null,
    /** One progress source for translation, contour and individual tool positions. */
    onSettleProgress: ((Float) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val moveBarNow by rememberUpdatedState(onMoveBar)
    val paneBoundsNow by rememberUpdatedState(paneBounds)
    val parentSlotNow by rememberUpdatedState(parentSlotInRoot)
    val startCallback by rememberUpdatedState(onDragStart)
    val moveCallback by rememberUpdatedState(onDragPosition)
    val dropCallback by rememberUpdatedState(onDrop)
    val cancelCallback by rememberUpdatedState(onDragCancel)
    val snapTargetNow by rememberUpdatedState(snapTargetInRoot)
    val previewPlacementNow by rememberUpdatedState(onPlacementPreview)
    val settleTargetNow by rememberUpdatedState(onSettleTarget)
    val settleProgressNow by rememberUpdatedState(onSettleProgress)
    val settleScope = rememberCoroutineScope()
    var settleJob by remember(id) { mutableStateOf<Job?>(null) }
    var drag by remember(id) { mutableStateOf(Offset.Zero) }
    var dragging by remember(id) { mutableStateOf(false) }
    var barBounds by remember(id) { mutableStateOf(Rect.Zero) }
    var dragStartInRoot by remember(id) { mutableStateOf(Offset.Zero) }
    // Preserve the released position until the parent acknowledges a new anchor.
    // Resetting drag *before* onMoveBar published its workspace caused the bar to
    // briefly redraw at its previous slot, then snap forward (the visible flick).
    var pendingPlacement by remember(id) { mutableStateOf<ToolbarDropPosition?>(null) }
    // The projected destination is fixed in root pixels. The parent planner may
    // acknowledge the new anchor one frame before its slot actually relocates.
    // Retain the root position through that handoff rather than resetting drag.
    var pendingRootTarget by remember(id) { mutableStateOf<Offset?>(null) }
    var stableHover by remember(id) { mutableStateOf<ToolbarDropPosition?>(null) }
    // pointerInput(id) intentionally survives recomposition. Read the CURRENT
    // anchor as state, or a subsequent RIGHT -> LEFT drag will still compare
    // against the LEFT anchor captured on the first gesture and be dropped as a no-op.
    val anchorNow by rememberUpdatedState(anchor)
    val alignmentNow by rememberUpdatedState(currentAlignment)
    fun resolvedPlacement(offset: Offset): ToolbarDropPosition? {
        val pane = paneBoundsNow
        if (barBounds == Rect.Zero || pane == Rect.Zero) return null
        // Resolve the edge under the PEN/FINGER, never the centre of the dragged
        // toolbar. A full-height LEFT rail can keep its centre next to LEFT even
        // when the user releases the stylus at BOTTOM; the old rule accidentally
        // turned cross-edge moves into same-lane reorders (or no-op drops).
        val pointer = dragStartInRoot + offset - pane.topLeft
        return ToolbarAnchorResolver.stablePlacement(
            pointer.x, pointer.y, pane.width, pane.height,
            stableHover ?: ToolbarDropPosition(anchorNow, alignmentNow),
        )
    }
    // Every terminal gesture path (cancel, rejected target, unchanged placement,
    // and failed acknowledgement) must release the *same* preview transaction.
    // Resetting only drag used to teleport the source home while leaving the
    // indicator and its neighbours at the projected destination indefinitely.
    fun discardGesture() {
        drag = Offset.Zero
        pendingPlacement = null
        pendingRootTarget = null
        stableHover = null
        settleTargetNow?.invoke(null)
        previewPlacementNow?.invoke(null)
        cancelCallback?.invoke()
    }
    LaunchedEffect(anchor, currentAlignment, pendingPlacement) {
        val pending = pendingPlacement ?: return@LaunchedEffect
        if (anchor == pending.anchor && currentAlignment == pending.alignment) {
            val targetInRoot = pendingRootTarget
            if (targetInRoot != null) {
                // State acknowledgement and the parent slot's layout pass do NOT
                // happen atomically. Dropping the offset now briefly paints the
                // source at its old location (the final-frame blink). Keep the
                // source painted at its projected root position until the actual
                // untransformed parent slot reaches that destination.
                withTimeoutOrNull(600) {
                    snapshotFlow { parentSlotNow ?: barBounds.topLeft }.first { slot ->
                        (slot - targetInRoot).getDistance() <= 3f
                    }
                }
            }
            if (pendingPlacement == pending) {
                drag = Offset.Zero
                pendingPlacement = null
                pendingRootTarget = null
                settleTargetNow?.invoke(null)
            }
        } else {
            // An invalid/rejected move must not leave a stranded toolbar forever.
            delay(1000)
            if (pendingPlacement == pending) discardGesture()
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
                settleJob?.cancel()
                settleJob = null
                settleTargetNow?.invoke(null)
                pendingPlacement = null
                pendingRootTarget = null
                drag = Offset.Zero
                stableHover = ToolbarDropPosition(anchorNow, alignmentNow)
                dragging = true
                dragStartInRoot = barBounds.topLeft + held.position
                startCallback?.invoke(dragStartInRoot)
                val initialDestination = resolvedPlacement(Offset.Zero)
                stableHover = initialDestination
                previewPlacementNow?.invoke(initialDestination)
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
                        val destination = resolvedPlacement(drag)
                        stableHover = destination
                        previewPlacementNow?.invoke(destination)
                    }
                }
                if (canceled) {
                    dragging = false
                    discardGesture()
                } else {
                    val moved = drag
                    val droppedAt = dragStartInRoot + moved
                    val handledByPreview = dropCallback?.invoke(droppedAt) == true
                    val handled = moved.getDistance() >= 12f && handledByPreview
                    dragging = false
                    if (!handled && moved.getDistance() >= 12f &&
                        barBounds != Rect.Zero && paneBoundsNow != Rect.Zero) {
                        val target = stableHover ?: resolvedPlacement(moved)
                        if (target != null) {
                            val changed = target.anchor != anchorNow || target.alignment != alignmentNow
                            val projectedRoot = snapTargetNow?.invoke(target)
                            if (projectedRoot != null) {
                                // Slide while the parent still owns the OLD slot. Only after
                                // reaching the ghost destination do we persist the move.
                                // This avoids drawing one frame at the original slot or
                                // animating the source *from* its old position after release.
                                if (changed) {
                                    pendingPlacement = target
                                    pendingRootTarget = projectedRoot
                                }
                                // The dragged content may be centred *inside* its original
                                // slot. During an orientation morph that inset disappears.
                                // barBounds.topLeft is the old content origin, so using it
                                // for the FINAL offset leaves a gap at the destination and
                                // the acknowledged layout visibly jumps to close it.
                                // The parent slot is stable across the morph and matches
                                // the target slot's coordinate space.
                                val destinationOffset = toolbarSettleDestinationOffset(
                                    projectedRoot, parentSlotNow, barBounds,
                                )
                                // Announce the target before the first translation frame:
                                // size and movement use a shared 220-ms easing curve.
                                if (changed) {
                                    settleTargetNow?.invoke(target)
                                    settleProgressNow?.invoke(0f)
                                }
                                // Start from the actual visible offset, not the raw
                                // pointer delta (which may place half a vertical bar
                                // beyond an edge). This avoids a pause at the clamp
                                // followed by a visible jump into the occupied lane.
                                val releaseOffset = toolbarVisibleDrag(moved, barBounds, paneBoundsNow)
                                settleJob = settleScope.launch {
                                    animate(0f, 1f,
                                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                                    ) { fraction, _ ->
                                        drag = releaseOffset + (destinationOffset - releaseOffset) * fraction
                                        if (changed) settleProgressNow?.invoke(fraction)
                                    }
                                    if (changed) {
                                        moveBarNow(id, target.anchor, target.alignment)
                                    } else {
                                        // A no-op drop still needs the reorder controller
                                        // released. Otherwise its hover projection survives
                                        // after the bar eases back to its original slot.
                                        discardGesture()
                                    }
                                }
                            } else {
                                if (changed && snapTargetNow != null) {
                                    // The preview and the destination planner disagreed.
                                    // Restore the peer allocation and clear the ghost.
                                    discardGesture()
                                } else if (changed) {
                                    // Legacy single-bar gestures have no projection
                                    // callback and retain the direct-commit path.
                                    pendingPlacement = target
                                    pendingRootTarget = null
                                    moveBarNow(id, target.anchor, target.alignment)
                                } else {
                                    discardGesture()
                                }
                            }
                        } else {
                            discardGesture()
                        }
                    } else if (handled) {
                        drag = Offset.Zero
                    } else {
                        discardGesture()
                    }
                }
            }
        }
    }
    // Keep the final root position fixed during asynchronous editor/planner
    // acknowledgement. The source's new parent slot can arrive one layout pass
    // later, so an immediate zero offset would flash the source at its old slot.
    val acknowledged = pendingPlacement != null && anchor == pendingPlacement?.anchor &&
        currentAlignment == pendingPlacement?.alignment
    val rawVisualDrag = when {
        acknowledged && pendingRootTarget != null &&
            (parentSlotInRoot != null || barBounds != Rect.Zero) ->
            pendingRootTarget!! - (parentSlotInRoot ?: barBounds.topLeft)
        acknowledged -> Offset.Zero // Legacy path without a projected slot.
        else -> drag
    }
    // The root target is computed by the same planner as the committed parent.
    // After acknowledgement its parent has already moved, but barBounds may still
    // describe the previous (much narrower) RIGHT rail for one layout frame.
    // Clamping a valid zero offset against that stale rail applies its oversized
    // horizontal extent a second time: a one-frame jump LEFT before snapping back.
    // Constrain only the active gesture / pre-acknowledgement settle. Once the
    // target is acknowledged, use its exact root-space compensation unmodified.
    val visualDrag = when {
        acknowledged && pendingRootTarget != null && parentSlotInRoot != null -> rawVisualDrag
        dragging || pendingPlacement != null -> toolbarVisibleDrag(rawVisualDrag, barBounds, paneBoundsNow)
        else -> rawVisualDrag
    }
    Box(
        Modifier
            .offset { IntOffset(visualDrag.x.roundToInt(), visualDrag.y.roundToInt()) }
            .testTag("toolbar-drag-surface-$id")
            // A docked bar is an earlier sibling of the canvas. Lift it while moving so the
            // canvas (and adjacent split panes) cannot paint over the dragged toolbar.
            .zIndex(if (dragging || pendingPlacement != null) 10f else 0f)
            .onGloballyPositioned { coordinates ->
                // Track the PARENT SLOT even while an old offset is still being
                // acknowledged. Previously we stopped recording bounds while
                // drag != 0; after moving LEFT -> RIGHT the next drag could use
                // the OLD LEFT coordinates and resolve the next drop as a no-op.
                val rendered = coordinates.boundsInRoot()
                val slot = Rect(
                    rendered.left - visualDrag.x,
                    rendered.top - visualDrag.y,
                    rendered.right - visualDrag.x,
                    rendered.bottom - visualDrag.y,
                )
                // A transient offscreen/zero-size measurement is not the new slot.
                // Never save it as gesture geometry or a subsequent drag can start
                // from (0,0), visibly teleporting across the preview.
                if (rendered.width > 0f && rendered.height > 0f && barBounds != slot)
                    barBounds = slot
            }
            .then(gesture),
    ) {
        content()
    }
}

/** Floating bars auto-pack along screen edges and overflow to free lanes, never covering each other. */
@Composable
fun BoxScope.WorkspaceFloatingBars(
    workspace: ToolbarWorkspace,
    onMoveBar: (id: String, anchor: ToolbarAnchor, alignment: ToolbarDockAlignment) -> Unit,
    primaryContent: @Composable () -> Unit,
    content: @Composable (ToolbarBar) -> Unit,
    colorCount: Int = 5,
    onReorderBar: ((String, String) -> Unit)? = null,
) {
    val reorder = remember { ToolbarWorkspaceReorderController() }
    ToolbarReorderRecoveryEffect(reorder)
    LaunchedEffect(workspace) { reorder.acknowledge(workspace) }
    var paneBounds by remember { mutableStateOf(Rect.Zero) }
    val baseLook = LocalToolbarLook.current
    val showPrimary = workspace.hasMultiDock(baseLook) ||
        workspace.usesSharedFloatingPrimary(baseLook)
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(10f).onGloballyPositioned { paneBounds = it.boundsInRoot() }) {
        val basePlan = toolbarFloatingPlan(workspace, baseLook, maxWidth, maxHeight, colorCount)
        val reordered = reorder.displayedWorkspace(workspace)
        val hoveredId = reorder.sourceId
        val hoveredTarget = reorder.hoverPlacement
        val previewMove = if (hoveredId != null && hoveredTarget != null &&
            reorder.targetId == null) toolbarProjectedMove(workspace, baseLook, hoveredId,
                hoveredTarget) else null
        val proposedWorkspace = previewMove?.first ?: reordered
        val proposedLook = previewMove?.second ?: baseLook
        val proposedPlan = if (proposedWorkspace == workspace && proposedLook == baseLook) basePlan
            else toolbarFloatingPlan(proposedWorkspace, proposedLook, maxWidth, maxHeight, colorCount)
        val peers = remember(workspace, basePlan, onReorderBar != null) {
            if (onReorderBar == null) emptyMap() else workspace.bars.associate { bar ->
                bar.id to toolbarReorderPeersInPlan(workspace, bar.id, basePlan, floating = true)
            }
        }
        if (hoveredId != null) {
            val slot = when {
                reorder.targetId != null ->
                    toolbarReorderDockSlot(workspace, baseLook, hoveredId, proposedPlan)
                hoveredTarget != null ->
                    toolbarFloatingDropSlot(workspace, baseLook, hoveredId, hoveredTarget,
                        maxWidth, maxHeight, colorCount)
                else -> null
            }
            slot?.let { ToolbarDropIndicator(it, hoveredId) }
        }
        for ((index, bar) in workspace.bars.withIndex()) {
            val placement = (if (reorder.sourceId == bar.id) basePlan else proposedPlan)
                .placement(bar.id) ?: continue
            // Legacy floating primary is already rendered by ToolbarAround.
            if (index == 0 && !bar.geometry.floating && !showPrimary) continue
            key(bar.id) {
                val look = ToolbarLook(
                    position = placement.edge.toolbarPosition(),
                    size = toolbarBarSize(bar, index, baseLook),
                    floating = true,
                )
                ToolbarLiveReorderBar(
                    bar = bar, placement = placement,
                    x = placement.x, y = placement.y,
                    anchor = if (index == 0 && !bar.geometry.floating && baseLook.floating)
                        when (baseLook.position) {
                            ToolbarPosition.TOP -> ToolbarAnchor.TOP_CENTER
                            ToolbarPosition.BOTTOM -> ToolbarAnchor.BOTTOM_CENTER
                            ToolbarPosition.LEFT -> ToolbarAnchor.CENTER_LEFT
                            ToolbarPosition.RIGHT -> ToolbarAnchor.CENTER_RIGHT
                        } else bar.geometry.anchor,
                    alignment = toolbarFloatingAlignment(bar),
                    paneBounds = paneBounds,
                    workspace = workspace,
                    peers = peers[bar.id].orEmpty(),
                    reorder = reorder,
                    onMoveBar = onMoveBar,
                    onReorderBar = onReorderBar,
                    projectedSlot = { target ->
                        toolbarFloatingDropSlot(workspace, baseLook, bar.id, target,
                            maxWidth, maxHeight, colorCount)
                    },
                ) {
                    CompositionLocalProvider(LocalToolbarLook provides look) {
                        if (index == 0) primaryContent() else content(bar)
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
