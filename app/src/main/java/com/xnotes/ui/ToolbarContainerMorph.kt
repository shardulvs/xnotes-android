package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarSection
import kotlin.math.roundToInt

/** Material-style container transform. Both the outer silhouette and individual item
 * positions use the SAME drop-settlement fraction. No bitmap scaling of icons/text. */
internal data class ToolbarContainerMorph(
    val fromVertical: Boolean,
    val toVertical: Boolean,
    val fraction: Float,
) {
    val progress get() = fraction.coerceIn(0f, 1f)
}

internal val LocalToolbarContainerMorph = compositionLocalOf<ToolbarContainerMorph?> { null }

internal data class ToolbarMorphPosition(val x: Int, val y: Int)
internal data class ToolbarMorphPlan(
    val size: IntSize,
    val positions: List<ToolbarMorphPosition>,
)

/** Old and new orientations share one consistent layout order and lane identity. */
internal fun toolbarMorphPlan(
    sizes: List<IntSize>, lanes: List<Int>, vertical: Boolean, spacingPx: Int,
): ToolbarMorphPlan {
    require(sizes.size == lanes.size)
    val positions = MutableList(sizes.size) { ToolbarMorphPosition(0, 0) }
    val spacing = spacingPx.coerceAtLeast(0)
    var across = 0
    var maxAlong = 0
    for (lane in 0..(lanes.maxOrNull() ?: -1)) {
        val memberIndices = lanes.indices.filter { lanes[it] == lane }
        var along = 0
        var depth = 0
        for (i in memberIndices) {
            positions[i] = if (vertical) ToolbarMorphPosition(across, along)
                else ToolbarMorphPosition(along, across)
            along += (if (vertical) sizes[i].height else sizes[i].width) + spacing
            depth = maxOf(depth, if (vertical) sizes[i].width else sizes[i].height)
        }
        if (memberIndices.isNotEmpty()) {
            maxAlong = maxOf(maxAlong, along - spacing)
            across += depth + spacing
        }
    }
    if (across > 0) across -= spacing
    return ToolbarMorphPlan(
        if (vertical) IntSize(across, maxAlong) else IntSize(maxAlong, across),
        positions,
    )
}

/** Pinned Start/Center/End groups keep their physical anchors during the morph.
 * Without this, an End group jumps from the middle back to the far edge on commit. */
internal fun toolbarMorphRegionPlan(
    sizes: List<IntSize>, regions: List<ToolbarRegion>, vertical: Boolean,
    extent: Int, spacingPx: Int,
): ToolbarMorphPlan {
    require(sizes.size == regions.size)
    val spacing = spacingPx.coerceAtLeast(0)
    val naturalCross = sizes.maxOfOrNull { if (vertical) it.width else it.height } ?: 0
    val naturalLength = sizes.sumOf { if (vertical) it.height else it.width } +
        spacing * (sizes.size - 1).coerceAtLeast(0)
    val length = if (extent == Constraints.Infinity) naturalLength else extent.coerceAtLeast(0)
    val positions = MutableList(sizes.size) { ToolbarMorphPosition(0, 0) }
    fun axisSize(index: Int) = if (vertical) sizes[index].height else sizes[index].width
    fun groupLength(group: ToolbarRegion): Int {
        val members = regions.indices.filter { regions[it] == group }
        return members.sumOf { axisSize(it) } + spacing * (members.size - 1).coerceAtLeast(0)
    }
    val startLength = groupLength(ToolbarRegion.START)
    val middleLength = groupLength(ToolbarRegion.CENTER)
    val endLength = groupLength(ToolbarRegion.END)
    val middleStart = toolbarPinnedMiddleOffset(length, startLength, middleLength, endLength)
    val groups = listOf(ToolbarRegion.START to 0,
        ToolbarRegion.CENTER to middleStart,
        ToolbarRegion.END to (length - endLength))
    for ((region, start) in groups) {
        var axis = start
        for (index in regions.indices.filter { regions[it] == region }) {
            val across = (naturalCross - if (vertical) sizes[index].width else sizes[index].height) / 2
            positions[index] = if (vertical) ToolbarMorphPosition(across, axis)
                else ToolbarMorphPosition(axis, across)
            axis += axisSize(index) + spacing
        }
    }
    return ToolbarMorphPlan(if (vertical) IntSize(naturalCross, length)
        else IntSize(length, naturalCross), positions)
}

internal fun toolbarMorphInterpolate(from: Int, to: Int, progress: Float): Int =
    (from + (to - from) * progress.coerceIn(0f, 1f)).roundToInt()

/** Compound entries can emit several independent controls (Colors, Zoom,
 * Page Navigation). Reposition EVERY child rather than squeezing all children
 * into a Box and accidentally stacking them on top of one another. */
@Composable
private fun ToolbarMorphEntry(
    entry: ToolbarEntry,
    base: BarMetrics,
    cell: @Composable (ToolbarEntry) -> Unit,
) {
    val morph = LocalToolbarContainerMorph.current ?: return
    val metrics = entryBarMetrics(base, entry).copy(vertical = morph.toVertical)
    val spacingPx = 0 // Compound buttons originally touch in their Row/Column.
    val colorCrossPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        if (entry.item == com.xnotes.core.tools.ToolbarItem.COLORS)
            metrics.button.roundToPx() else 0
    }
    Layout(content = {
        androidx.compose.runtime.CompositionLocalProvider(LocalBar provides metrics) {
            cell(entry)
        }
    }) { measurables, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val nodes = measurables.map { it.measure(childConstraints) }
        val sizes = nodes.map { IntSize(it.width, it.height) }
        val lanes = List(nodes.size) { 0 }
        val before = toolbarMorphPlan(sizes, lanes, morph.fromVertical, spacingPx)
        val after = toolbarMorphPlan(sizes, lanes, morph.toVertical, spacingPx)
        // Swatches occupy a full button-thickness track, centred cross-axis.
        // Otherwise the color palette floats up when the toolbar starts morphing.
        val beforeWidth = maxOf(before.size.width,
            if (morph.fromVertical) colorCrossPx else 0)
        val beforeHeight = maxOf(before.size.height,
            if (morph.fromVertical) 0 else colorCrossPx)
        val afterWidth = maxOf(after.size.width,
            if (morph.toVertical) colorCrossPx else 0)
        val afterHeight = maxOf(after.size.height,
            if (morph.toVertical) 0 else colorCrossPx)
        layout(
            toolbarMorphInterpolate(beforeWidth, afterWidth, morph.progress)
                .coerceIn(constraints.minWidth, constraints.maxWidth),
            toolbarMorphInterpolate(beforeHeight, afterHeight, morph.progress)
                .coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            nodes.forEachIndexed { i, child ->
                val beforeX = before.positions[i].x +
                    if (morph.fromVertical) (beforeWidth - before.size.width) / 2 else 0
                val afterX = after.positions[i].x +
                    if (morph.toVertical) (afterWidth - after.size.width) / 2 else 0
                val beforeY = before.positions[i].y +
                    if (!morph.fromVertical) (beforeHeight - before.size.height) / 2 else 0
                val afterY = after.positions[i].y +
                    if (!morph.toVertical) (afterHeight - after.size.height) / 2 else 0
                child.placeRelative(
                    toolbarMorphInterpolate(beforeX, afterX, morph.progress),
                    toolbarMorphInterpolate(beforeY, afterY, morph.progress),
                )
            }
        }
    }
}

/** Insertion-preserving reflow: children keep their composition state while moving
 * between row/column and 1–3 lane grid positions. */
@Composable
internal fun ToolbarMorphSectionGrid(
    section: ToolbarSection,
    cell: @Composable (ToolbarEntry) -> Unit,
) {
    val morph = LocalToolbarContainerMorph.current ?: return
    val base = LocalBar.current
    val entries = (0 until section.lanes).flatMap { lane ->
        section.entriesInLane(lane).map { lane to it }
    }
    val spacingPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        if (section.lanes == 1) 0 else base.spacing.roundToPx()
    }
    Layout(content = {
        entries.forEachIndexed { index, (lane, entry) ->
            key(index, lane, entry) {
                ToolbarMorphEntry(entry, base, cell)
            }
        }
    }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nodes = measurables.map { it.measure(loose) }
        val sizes = nodes.map { IntSize(it.width, it.height) }
        val lanes = entries.map { it.first }
        val before = toolbarMorphPlan(sizes, lanes, morph.fromVertical, spacingPx)
        val after = toolbarMorphPlan(sizes, lanes, morph.toVertical, spacingPx)
        val width = toolbarMorphInterpolate(
            before.size.width, after.size.width, morph.progress).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = toolbarMorphInterpolate(
            before.size.height, after.size.height, morph.progress).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            nodes.forEachIndexed { i, node ->
                node.placeRelative(
                    toolbarMorphInterpolate(before.positions[i].x, after.positions[i].x, morph.progress),
                    toolbarMorphInterpolate(before.positions[i].y, after.positions[i].y, morph.progress),
                )
            }
        }
    }
}

/** Sections themselves reflow, so icons are not repositioned inside a stationary
 * group while the groups snap abruptly into a column. */
@Composable
internal fun ToolbarMorphSections(
    layout: ToolbarLayout,
    sectionContent: @Composable (Int, ToolbarSection) -> Unit,
) {
    val morph = LocalToolbarContainerMorph.current ?: return
    val useRegions = layout.distribution == ToolbarDistribution.REGIONS &&
        !LocalCompactToolbarRegions.current
    val sections = if (useRegions) toolbarCompactRegionSections(layout)
        else layout.sections.withIndex().filter { it.value.hasVisible }
    val spacingPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        LocalBar.current.spacing.roundToPx()
    }
    Layout(content = {
        sections.forEach { indexed ->
            key(indexed.index) { Box { sectionContent(indexed.index, indexed.value) } }
        }
    }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nodes = measurables.map { it.measure(loose) }
        val sizes = nodes.map { IntSize(it.width, it.height) }
        val lanes = List(nodes.size) { 0 }
        val regions = sections.map { it.value.region }
        val before = if (useRegions)
            toolbarMorphRegionPlan(sizes, regions, morph.fromVertical,
                if (morph.fromVertical) constraints.maxHeight else constraints.maxWidth, spacingPx)
        else toolbarMorphPlan(sizes, lanes, morph.fromVertical, spacingPx)
        val after = if (useRegions)
            toolbarMorphRegionPlan(sizes, regions, morph.toVertical,
                if (morph.toVertical) constraints.maxHeight else constraints.maxWidth, spacingPx)
        else toolbarMorphPlan(sizes, lanes, morph.toVertical, spacingPx)
        layout(toolbarMorphInterpolate(before.size.width,
            after.size.width, morph.progress).coerceIn(constraints.minWidth, constraints.maxWidth),
            toolbarMorphInterpolate(before.size.height,
                after.size.height, morph.progress).coerceIn(constraints.minHeight, constraints.maxHeight)) {
            nodes.forEachIndexed { i, node ->
                node.placeRelative(
                    toolbarMorphInterpolate(before.positions[i].x, after.positions[i].x, morph.progress),
                    toolbarMorphInterpolate(before.positions[i].y, after.positions[i].y, morph.progress),
                )
            }
        }
    }
}
