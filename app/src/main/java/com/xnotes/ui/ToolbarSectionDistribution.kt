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
    layout.distribution == ToolbarDistribution.REGIONS && floating && explicitLengthDp == null

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
