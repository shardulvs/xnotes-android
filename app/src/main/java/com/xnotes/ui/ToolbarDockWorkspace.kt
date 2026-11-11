package com.xnotes.ui

import androidx.compose.ui.unit.Dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarDockLayout
import com.xnotes.core.tools.ToolbarDockPlan
import com.xnotes.core.tools.ToolbarDockRequest
import com.xnotes.core.tools.ToolbarScale
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarSize

internal fun ToolbarPosition.dockEdge(): ToolbarDockEdge = when (this) {
    ToolbarPosition.TOP -> ToolbarDockEdge.TOP
    ToolbarPosition.BOTTOM -> ToolbarDockEdge.BOTTOM
    ToolbarPosition.LEFT -> ToolbarDockEdge.LEFT
    ToolbarPosition.RIGHT -> ToolbarDockEdge.RIGHT
}

internal fun ToolbarDockEdge.toolbarPosition(): ToolbarPosition = when (this) {
    ToolbarDockEdge.TOP -> ToolbarPosition.TOP
    ToolbarDockEdge.BOTTOM -> ToolbarPosition.BOTTOM
    ToolbarDockEdge.LEFT -> ToolbarPosition.LEFT
    ToolbarDockEdge.RIGHT -> ToolbarPosition.RIGHT
}

internal fun toolbarBarSize(bar: ToolbarBar, index: Int, look: ToolbarLook): ToolbarSize =
    if (index == 0) look.size else when (bar.geometry.scale) {
        ToolbarScale.COMPACT -> ToolbarSize.COMPACT
        ToolbarScale.REGULAR -> ToolbarSize.REGULAR
        ToolbarScale.COMFORTABLE -> ToolbarSize.COMFORTABLE
    }

internal fun toolbarDockEdge(bar: ToolbarBar, index: Int, look: ToolbarLook): ToolbarDockEdge? =
    if (bar.geometry.floating) null
    else if (bar.geometry.dockEdge != null) bar.geometry.dockEdge
    else if (index == 0 && look.floating) null
    else bar.geometry.dockEdge ?: if (index == 0) look.position.dockEdge() else null

/** Floating orientation must follow the current dock, not an older saved floating anchor. */
internal fun toolbarFloatingGeometry(bar: ToolbarBar, index: Int, look: ToolbarLook): ToolbarGeometry {
    val edge = toolbarDockEdge(bar, index, look)
        ?: if (index == 0 && !bar.geometry.floating && look.floating) look.position.dockEdge() else null
    val anchor = when (edge) {
        ToolbarDockEdge.TOP -> ToolbarAnchor.TOP_CENTER
        ToolbarDockEdge.BOTTOM -> ToolbarAnchor.BOTTOM_CENTER
        ToolbarDockEdge.LEFT -> ToolbarAnchor.CENTER_LEFT
        ToolbarDockEdge.RIGHT -> ToolbarAnchor.CENTER_RIGHT
        null -> bar.geometry.anchor
    }
    return bar.geometry.copy(anchor = anchor, floating = true, dockEdge = null)
}

/** Reattach to the edge occupied by the floating bar, including corner anchors. */
internal fun toolbarDockedGeometry(bar: ToolbarBar, index: Int, look: ToolbarLook): ToolbarGeometry {
    val edge = toolbarDockEdge(bar, index, look)
        ?: if (index == 0 && !bar.geometry.floating && look.floating) look.position.dockEdge()
        else bar.geometry.anchor.floatingEdge()
    return bar.geometry.copy(floating = false, dockEdge = edge)
}

/** A null length is deliberately treated as a full-edge bar, not an optimistic guess. */
internal fun toolbarDockPlan(
    workspace: ToolbarWorkspace,
    look: ToolbarLook,
    windowWidth: Dp,
    windowHeight: Dp,
): ToolbarDockPlan {
    val requests = workspace.bars.mapIndexedNotNull { index, bar ->
        val edge = toolbarDockEdge(bar, index, look)?.takeIf { bar.visible } ?: return@mapIndexedNotNull null
        val metrics = resolvedBarMetrics(toolbarBarSize(bar, index, look), bar.geometry)
        val vertical = edge == ToolbarDockEdge.LEFT || edge == ToolbarDockEdge.RIGHT
        ToolbarDockRequest(
            id = bar.id,
            edge = edge,
            lane = bar.geometry.dockLane,
            alignment = bar.geometry.dockAlignment,
            length = if (vertical) (bar.geometry.heightDp?.toFloat() ?: windowHeight.value)
                else (bar.geometry.widthDp?.toFloat() ?: windowWidth.value),
            depth = if (vertical) toolbarWidth(metrics, bar.layout, bar.geometry).value
                else toolbarHeight(metrics, bar.layout, bar.geometry).value,
            autoLength = if (vertical) bar.geometry.heightDp == null else bar.geometry.widthDp == null,
        )
    }
    return packToolbarLanes(windowWidth.value, windowHeight.value, requests)
}

/** Preferred lanes are tried first, then other lanes on the same edge, for both dock modes. */
private fun packToolbarLanes(
    width: Float,
    height: Float,
    requests: List<ToolbarDockRequest>,
    gap: Float = 4f,
): ToolbarDockPlan {
    val accepted = mutableListOf<ToolbarDockRequest>()
    val unplaced = mutableSetOf<String>()
    for (request in requests) {
        val candidates = (request.lane until ToolbarDockLayout.MAX_LANES) + (0 until request.lane)
        val placed = candidates.asSequence().map { request.copy(lane = it) }.firstOrNull { shifted ->
            ToolbarDockLayout.arrange(width, height, accepted + shifted, gap).rejected.isEmpty()
        }
        if (placed == null) unplaced.add(request.id) else accepted.add(placed)
    }
    val packed = ToolbarDockLayout.arrange(width, height, accepted, gap)
    return packed.copy(rejected = packed.rejected + unplaced)
}

internal fun ToolbarWorkspace.hasMultiDock(look: ToolbarLook): Boolean =
    bars.withIndex().any { (index, bar) ->
        bar.visible && toolbarDockEdge(bar, index, look) != null &&
            (index > 0 || bar.geometry.dockEdge != null ||
                bar.geometry.dockLane != 0 ||
                bar.geometry.dockAlignment != ToolbarDockAlignment.CENTER)
    }

internal fun ToolbarAnchor.floatingEdge(): ToolbarDockEdge = when (this) {
    ToolbarAnchor.TOP_LEFT, ToolbarAnchor.TOP_CENTER, ToolbarAnchor.TOP_RIGHT, ToolbarAnchor.CENTER ->
        ToolbarDockEdge.TOP
    ToolbarAnchor.BOTTOM_LEFT, ToolbarAnchor.BOTTOM_CENTER, ToolbarAnchor.BOTTOM_RIGHT ->
        ToolbarDockEdge.BOTTOM
    ToolbarAnchor.CENTER_LEFT -> ToolbarDockEdge.LEFT
    ToolbarAnchor.CENTER_RIGHT -> ToolbarDockEdge.RIGHT
}

internal fun toolbarFloatingAlignment(bar: ToolbarBar): ToolbarDockAlignment = when {
    bar.geometry.dockAlignment != ToolbarDockAlignment.CENTER -> bar.geometry.dockAlignment
    bar.geometry.anchor == ToolbarAnchor.TOP_LEFT || bar.geometry.anchor == ToolbarAnchor.BOTTOM_LEFT ->
        ToolbarDockAlignment.START
    bar.geometry.anchor == ToolbarAnchor.TOP_RIGHT || bar.geometry.anchor == ToolbarAnchor.BOTTOM_RIGHT ->
        ToolbarDockAlignment.END
    else -> ToolbarDockAlignment.CENTER
}

/** Conservative natural length. The allocated box bounds controls and permits scrolling. */
private fun estimatedFloatingLength(bar: ToolbarBar, index: Int, look: ToolbarLook, colors: Int): Float {
    val m = resolvedBarMetrics(toolbarBarSize(bar, index, look), bar.geometry)
    val vertical = bar.geometry.anchor.floatingEdge() in listOf(ToolbarDockEdge.LEFT, ToolbarDockEdge.RIGHT)
    val explicit = if (vertical) bar.geometry.heightDp else bar.geometry.widthDp
    if (explicit != null) return explicit.toFloat()
    val sections = bar.layout.visibleSections
    fun sectionLength(section: com.xnotes.core.tools.ToolbarSection): Float =
        (0 until section.crossCells).maxOfOrNull { lane ->
            section.entriesInLane(lane).sumOf { entry ->
                val unit = entry.sizeDp?.toFloat() ?: m.button.value
                when (entry.item) {
                    com.xnotes.core.tools.ToolbarItem.SPACER ->
                        entry.sizeDp?.toFloat() ?: com.xnotes.core.tools.ToolbarEntry.DEFAULT_SPACER_DP.toFloat()
                    com.xnotes.core.tools.ToolbarItem.COLORS ->
                        entry.colorIndices(colors).count().coerceAtLeast(1) * m.circle.value
                    com.xnotes.core.tools.ToolbarItem.PAGE_NAV,
                    com.xnotes.core.tools.ToolbarItem.ZOOM -> 3f * unit
                    com.xnotes.core.tools.ToolbarItem.TITLE -> if (vertical) 0f else 120f
                    else -> unit
                }.toDouble()
            }.toFloat()
        }?.toFloat() ?: 0f
    fun groupLength(group: List<com.xnotes.core.tools.ToolbarSection>): Float =
        group.sumOf { sectionLength(it).toDouble() }.toFloat() +
            (group.size - 1).coerceAtLeast(0) * 14f
    // Auto-sized floating regions are compact: no mirrored phantom space at the end.
    // Explicit-width/height bars retain their physical Start/Center/End anchors.
    return maxOf(48f, groupLength(sections) + 2f * m.padding.value)
}

/** Auto-packs floating bars into the next free lane; never reserves document space. */
internal fun toolbarFloatingPlan(
    workspace: ToolbarWorkspace,
    look: ToolbarLook,
    stageWidth: Dp,
    stageHeight: Dp,
    colorCount: Int,
): ToolbarDockPlan {
    val width = (stageWidth.value - 16f).coerceAtLeast(0f)
    val height = (stageHeight.value - 16f).coerceAtLeast(0f)
    val requests = mutableListOf<ToolbarDockRequest>()
    for ((index, bar) in workspace.bars.withIndex()) {
        if (!bar.visible || toolbarDockEdge(bar, index, look) != null) continue
        val anchor = if (index == 0 && !bar.geometry.floating && look.floating) when (look.position) {
            ToolbarPosition.TOP -> ToolbarAnchor.TOP_CENTER
            ToolbarPosition.BOTTOM -> ToolbarAnchor.BOTTOM_CENTER
            ToolbarPosition.LEFT -> ToolbarAnchor.CENTER_LEFT
            ToolbarPosition.RIGHT -> ToolbarAnchor.CENTER_RIGHT
        } else bar.geometry.anchor
        val edge = anchor.floatingEdge()
        val vertical = edge == ToolbarDockEdge.LEFT || edge == ToolbarDockEdge.RIGHT
        val m = resolvedBarMetrics(toolbarBarSize(bar, index, look), bar.geometry)
        val axisLength = if (vertical) height else width
        val length = estimatedFloatingLength(bar, index, look, colorCount).coerceAtMost(axisLength)
        val depth = if (vertical) toolbarWidth(m, bar.layout, bar.geometry).value
            else toolbarHeight(m, bar.layout, bar.geometry).value
        requests.add(ToolbarDockRequest(
            id = bar.id, edge = edge, lane = bar.geometry.dockLane,
            alignment = toolbarFloatingAlignment(bar), length = length, depth = depth,
        ))
    }
    val packed = packToolbarLanes(width, height, requests, gap = 8f)
    return packed.copy(
        placements = packed.placements.map { it.copy(x = it.x + 8f, y = it.y + 8f) },
    )
}
