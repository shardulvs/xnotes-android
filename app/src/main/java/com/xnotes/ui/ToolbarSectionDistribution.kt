package com.xnotes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarSection

/** The natural-size floating bar packs regions together; sized/docked bars keep physical anchors. */
internal fun toolbarCompactRegions(layout: ToolbarLayout, floating: Boolean, explicitLengthDp: Int?): Boolean =
    layout.distribution == ToolbarDistribution.REGIONS && floating && explicitLengthDp == null &&
        !layout.hasPinnedRegions

internal val LocalCompactToolbarRegions = staticCompositionLocalOf { false }

/**
 * One section is the indivisible group: its one to three lanes stay together.
 * Automatic floating bars pack Start/Center/End groups in order without artificial gaps.
 * Explicit-size and docked bars keep physical anchors; tight viewports scroll instead of overlapping tools.
 * Side toolbars use the identical algorithm on the vertical axis.
 */
@Composable
internal fun ToolbarSections(
    layout: ToolbarLayout,
    sectionContent: @Composable (Int, ToolbarSection) -> Unit,
) {
    val visible = layout.sections.withIndex().filter { it.value.hasVisible }
    if (layout.distribution == ToolbarDistribution.CONTINUOUS || LocalCompactToolbarRegions.current) {
        // In compact floating bars, group labels still determine order, not fixed-width slots.
        val arranged = if (layout.distribution == ToolbarDistribution.CONTINUOUS) visible
            else toolbarCompactRegionSections(layout)
        arranged.forEachIndexed { visual, indexed ->
            if (visual > 0) Separator()
            sectionContent(indexed.index, indexed.value)
        }
        return
    }
    if (visible.isEmpty()) return
    if (layout.hasPinnedRegions) {
        PinnedToolbarSections(layout, sectionContent)
        return
    }
    val vertical = LocalBar.current.vertical
    val groups = toolbarRegionGroups(layout)
    val present = groups.filterValues { it.isNotEmpty() }.keys

    // Each region owns its viewport: an oversized Start section can scroll locally without
    // pushing Center and End off-screen. The center always means the physical center.
    val regionContent: @Composable (ToolbarRegion) -> Unit = { region ->
        androidx.compose.runtime.key(region) {
            val group = groups.getValue(region)
            if (vertical) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    group.forEachIndexed { index, indexed ->
                        if (index > 0) Separator()
                        sectionContent(indexed.index, indexed.value)
                    }
                }
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    group.forEachIndexed { index, indexed ->
                        if (index > 0) Separator()
                        sectionContent(indexed.index, indexed.value)
                    }
                }
            }
        }
    }
    if (present.size == 1) {
        val region = present.single()
        val alignment = if (vertical) when (region) {
            ToolbarRegion.START -> androidx.compose.ui.Alignment.TopCenter
            ToolbarRegion.CENTER -> androidx.compose.ui.Alignment.Center
            ToolbarRegion.END -> androidx.compose.ui.Alignment.BottomCenter
        } else when (region) {
            ToolbarRegion.START -> androidx.compose.ui.Alignment.CenterStart
            ToolbarRegion.CENTER -> androidx.compose.ui.Alignment.Center
            ToolbarRegion.END -> androidx.compose.ui.Alignment.CenterEnd
        }
        Box(if (vertical) Modifier.fillMaxHeight() else Modifier.fillMaxWidth(), contentAlignment = alignment) {
            regionContent(region)
        }
    } else if (vertical) {
        BoxWithConstraints(Modifier.fillMaxHeight()) {
            val centerMaxHeight = maxHeight * 0.5f
            Column(Modifier.fillMaxHeight()) {
                Box(Modifier.weight(1f).clipToBounds(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                    regionContent(ToolbarRegion.START)
                }
                if (ToolbarRegion.CENTER in present) {
                    // The middle slot measures its content first, capped at half the bar.
                    // Equal weighted edge slots then keep it at the physical midpoint.
                    Box(Modifier.heightIn(max = centerMaxHeight).clipToBounds(),
                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                        regionContent(ToolbarRegion.CENTER)
                    }
                }
                Box(Modifier.weight(1f).clipToBounds(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                    regionContent(ToolbarRegion.END)
                }
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val centerMaxWidth = maxWidth * 0.5f
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f).clipToBounds(), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
                    regionContent(ToolbarRegion.START)
                }
                if (ToolbarRegion.CENTER in present) {
                    Box(Modifier.widthIn(max = centerMaxWidth).clipToBounds(),
                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                        regionContent(ToolbarRegion.CENTER)
                    }
                }
                Box(Modifier.weight(1f).clipToBounds(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                    regionContent(ToolbarRegion.END)
                }
            }
        }
    }
}

/** Sections are stable by source index, even if the user reorders their regions. */
internal data class ToolbarPinnedRegionGroups(
    val start: List<IndexedValue<ToolbarSection>>,
    val scrolling: List<IndexedValue<ToolbarSection>>,
    val end: List<IndexedValue<ToolbarSection>>,
)

/** A disabled pin joins that edge's sections to the middle scrolling track. */
internal fun toolbarPinnedRegionGroups(layout: ToolbarLayout): ToolbarPinnedRegionGroups {
    val visible = toolbarCompactRegionSections(layout)
    return ToolbarPinnedRegionGroups(
        start = if (layout.pinStart) visible.filter { it.value.region == ToolbarRegion.START } else emptyList(),
        scrolling = visible.filter { !((layout.pinStart && it.value.region == ToolbarRegion.START) ||
            (layout.pinEnd && it.value.region == ToolbarRegion.END)) },
        end = if (layout.pinEnd) visible.filter { it.value.region == ToolbarRegion.END } else emptyList(),
    )
}

/** Preserve pinned edge size; never shrink the scrolling track until the bar is constrained. */
internal fun toolbarPinnedScrollBudget(
    maxAxis: Int, startAxis: Int, endAxis: Int, hasScrollContent: Boolean, minPeek: Int,
): Int {
    if (!hasScrollContent) return 0
    val minimum = minOf(maxAxis, minPeek).coerceAtLeast(0)
    return (maxAxis - startAxis - endAxis).coerceIn(minimum, maxAxis)
}

/** Center a short middle group when possible, respecting the space owned by both pinned edges. */
internal fun toolbarPinnedMiddleOffset(extent: Int, start: Int, middle: Int, end: Int): Int {
    val centered = (extent - middle) / 2
    return if (start + middle + end <= extent) centered.coerceIn(start, extent - end - middle)
    else centered // Only underlap when the bar is genuinely too short to fit everything.
}

/**
 * The pinned groups are measured at their natural sizes. Only the scroll viewport gives way
 * when the allocated bar cannot fit everything. This is shared by live bars and preview:
 * horizontal and vertical follow exactly the same sizing policy.
 *
 * On a constrained bar the scrolling area is clipped, then Start/End are drawn on top,
 * with a small rounded, opaque seam and fade to mask tools moving under their edges.
 */
@Composable
private fun PinnedToolbarSections(
    layout: ToolbarLayout,
    sectionContent: @Composable (Int, ToolbarSection) -> Unit,
) {
    val groups = toolbarPinnedRegionGroups(layout)
    val vertical = LocalBar.current.vertical
    val scroll = rememberScrollState()
    val showEdgeFinish = scroll.maxValue > 0
    val palette = LocalPalette.current
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val fadePx = with(density) { 10.dp.roundToPx() }
    val minPeekPx = with(density) { 24.dp.roundToPx() }
    val drawSections: @Composable (List<IndexedValue<ToolbarSection>>) -> Unit = { sections ->
        sections.forEachIndexed { visual, indexed ->
            if (visual > 0) Separator()
            sectionContent(indexed.index, indexed.value)
        }
    }
    val pinnedGroup: @Composable (List<IndexedValue<ToolbarSection>>, Boolean) -> Unit = { sections, start ->
        val panel = palette.panel.toComposeColor()
        val shape = RoundedCornerShape(7.dp)
        val physicalStart = if (vertical) start else (start == (direction == LayoutDirection.Ltr))
        val finish = if (showEdgeFinish) {
            Modifier.drawWithContent {
                drawContent()
                val trailing = if (vertical) start else physicalStart
                val from = if (trailing) (if (vertical) size.height else size.width) else -fadePx.toFloat()
                val colors = if (trailing) listOf(panel, panel.copy(alpha = 0f))
                    else listOf(panel.copy(alpha = 0f), panel)
                drawRect(
                    brush = if (vertical) Brush.verticalGradient(colors, startY = from, endY = from + fadePx)
                        else Brush.horizontalGradient(colors, startX = from, endX = from + fadePx),
                    topLeft = if (vertical) Offset(0f, from) else Offset(from, 0f),
                    size = if (vertical) Size(size.width, fadePx.toFloat())
                        else Size(fadePx.toFloat(), size.height),
                )
            }
        } else Modifier
        val surface = if (showEdgeFinish) Modifier
            .shadow(2.dp, shape).clip(shape).background(panel)
            .border(0.5.dp, palette.border.toComposeColor(), shape)
        else Modifier
        Box(finish) {
            if (vertical) Column(surface, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                drawSections(sections)
            } else Row(surface, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                drawSections(sections)
            }
        }
    }
    SubcomposeLayout { constraints ->
        val relaxed = constraints.copy(minWidth = 0, minHeight = 0)
        val start = groups.start.takeIf { it.isNotEmpty() }?.let { sections ->
            subcompose("start") { pinnedGroup(sections, true) }.single().measure(relaxed)
        }
        val end = groups.end.takeIf { it.isNotEmpty() }?.let { sections ->
            subcompose("end") { pinnedGroup(sections, false) }.single().measure(relaxed)
        }
        fun axisSize(width: Int, height: Int) = if (vertical) height else width
        fun crossSize(width: Int, height: Int) = if (vertical) width else height
        val startAxis = start?.let { axisSize(it.width, it.height) } ?: 0
        val endAxis = end?.let { axisSize(it.width, it.height) } ?: 0
        val maxAxis = if (vertical) constraints.maxHeight else constraints.maxWidth
        // A tiny underlapping viewport remains when even the two pinned groups cannot fit.
        // The pinned controls retain hit priority, while the center remains a scrollable track.
        val scrollBudget = toolbarPinnedScrollBudget(maxAxis, startAxis, endAxis,
            groups.scrolling.isNotEmpty(), minPeekPx)
        val scrollConstraints = if (vertical) relaxed.copy(maxHeight = scrollBudget)
            else relaxed.copy(maxWidth = scrollBudget)
        val middle = groups.scrolling.takeIf { it.isNotEmpty() }?.let { sections ->
            subcompose("middle") {
                if (vertical) {
                    Column(Modifier.verticalScroll(scroll),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        drawSections(sections)
                    }
                } else {
                    Row(Modifier.horizontalScroll(scroll),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        drawSections(sections)
                    }
                }
            }.single().measure(scrollConstraints)
        }
        val middleAxis = middle?.let { axisSize(it.width, it.height) } ?: 0
        val naturalAxis = startAxis + middleAxis + endAxis
        val naturalCross = listOfNotNull(start, middle, end)
            .maxOfOrNull { crossSize(it.width, it.height) } ?: 0
        val width = (if (vertical) naturalCross else naturalAxis).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (if (vertical) naturalAxis else naturalCross).coerceIn(constraints.minHeight, constraints.maxHeight)
        val extent = if (vertical) height else width
        val crossExtent = if (vertical) width else height
        val middlePosition = if (middle != null) {
            // Preserve physical centering in an explicitly sized/docked bar when space permits.
            toolbarPinnedMiddleOffset(extent, startAxis, middleAxis, endAxis)
        } else 0
        layout(width, height) {
            fun place(p: androidx.compose.ui.layout.Placeable?, axis: Int) {
                if (p == null) return
                val cross = (crossExtent - crossSize(p.width, p.height)) / 2
                if (vertical) p.placeRelative(cross, axis) else p.placeRelative(axis, cross)
            }
            // The middle is the rear layer. Pinned islands/gradients always cover it.
            place(middle, middlePosition)
            place(start, 0)
            place(end, extent - endAxis)
        }
    }
}

/** The preview and live toolbars consume the same stable, original section indices.
 * Empty and hidden sections remain editable but never claim visible toolbar space. */
internal fun toolbarCompactRegionSections(layout: ToolbarLayout): List<IndexedValue<ToolbarSection>> =
    layout.sections.withIndex().filter { it.value.hasVisible }.sortedBy { it.value.region.ordinal }

internal fun toolbarRegionGroups(layout: ToolbarLayout): Map<ToolbarRegion, List<IndexedValue<ToolbarSection>>> {
    val visible = layout.sections.withIndex().filter { it.value.hasVisible }
    return ToolbarRegion.entries.associateWith { region ->
        visible.filter { it.value.region == region }
    }
}
