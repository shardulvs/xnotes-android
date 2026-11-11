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

/**
 * A single center viewport scrolls, while separately measured Start/End islands never move.
 * Cap each island at 40% of the bar so the center always has a usable viewport. Overflowing
 * pinned islands clip rather than stealing scroll gestures or obscuring the other regions.
 * The same composable is shared by real bars and the workspace preview.
 */
@Composable
private fun PinnedToolbarSections(
    layout: ToolbarLayout,
    sectionContent: @Composable (Int, ToolbarSection) -> Unit,
) {
    val groups = toolbarPinnedRegionGroups(layout)
    val drawSections: @Composable (List<IndexedValue<ToolbarSection>>) -> Unit = { sections ->
        sections.forEachIndexed { visual, indexed ->
            if (visual > 0) Separator()
            sectionContent(indexed.index, indexed.value)
        }
    }
    if (LocalBar.current.vertical) {
        BoxWithConstraints(Modifier.fillMaxHeight()) {
            val maxPinned = maxHeight * 0.4f
            Column(Modifier.fillMaxHeight(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                if (groups.start.isNotEmpty()) {
                    Box(Modifier.heightIn(max = maxPinned).clipToBounds()) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            drawSections(groups.start)
                        }
                    }
                }
                Box(Modifier.weight(1f).clipToBounds(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Column(Modifier.verticalScroll(rememberScrollState()),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        drawSections(groups.scrolling)
                    }
                }
                if (groups.end.isNotEmpty()) {
                    Box(Modifier.heightIn(max = maxPinned).clipToBounds()) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            drawSections(groups.end)
                        }
                    }
                }
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maxPinned = maxWidth * 0.4f
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (groups.start.isNotEmpty()) {
                    Box(Modifier.widthIn(max = maxPinned).clipToBounds()) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            drawSections(groups.start)
                        }
                    }
                }
                Box(Modifier.weight(1f).clipToBounds(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        drawSections(groups.scrolling)
                    }
                }
                if (groups.end.isNotEmpty()) {
                    Box(Modifier.widthIn(max = maxPinned).clipToBounds()) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            drawSections(groups.end)
                        }
                    }
                }
            }
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
