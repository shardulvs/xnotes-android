package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection

/** Empty, selectable layout item; for side toolbars it runs vertically. */
@Composable
internal fun ToolbarSpacer(entry: ToolbarEntry) {
    val bar = LocalBar.current
    val length = (entry.sizeDp ?: ToolbarEntry.DEFAULT_SPACER_DP).dp
    if (bar.vertical) Spacer(Modifier.height(length).width(bar.button))
    else Spacer(Modifier.width(length).height(bar.button))
}

/** Maximum thickness measured perpendicular to a bar. */
internal fun toolbarCrossCells(layout: ToolbarLayout?): Int =
    layout?.visibleSections?.maxOfOrNull { it.crossCells }?.coerceIn(1, 3) ?: 1

/** Identical layout in notes, canvas and settings preview.
 * Sections hold 1–3 independent lanes. Sidebars transpose rows into columns.
 * Composite controls retain their natural dimensions. */
@Composable
internal fun ToolbarSectionGrid(
    section: ToolbarSection,
    cell: @Composable (ToolbarEntry) -> Unit,
) {
    val base = LocalBar.current
    val measuredCell: @Composable (ToolbarEntry) -> Unit = { entry ->
        val metrics = if (entry.item == ToolbarItem.SPACER) base else entryBarMetrics(base, entry)
        CompositionLocalProvider(LocalBar provides metrics) {
            if (entry.item == ToolbarItem.COLORS) {
                // Swatches are shorter than tool buttons. Give the entire palette block
                // a button-height slot, centered on the cross-axis in any orientation.
                if (base.vertical) {
                    Column(Modifier.width(metrics.button), horizontalAlignment = Alignment.CenterHorizontally) {
                        cell(entry)
                    }
                } else {
                    Row(Modifier.height(metrics.button), verticalAlignment = Alignment.CenterVertically) {
                        cell(entry)
                    }
                }
            } else cell(entry)
        }
    }
    if (section.lanes == 1) {
        section.visibleEntries.forEach { measuredCell(it) }
        return
    }
    if (LocalBar.current.vertical) {
        Row(horizontalArrangement = Arrangement.spacedBy(LocalBar.current.spacing)) {
            repeat(section.lanes) { lane ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(LocalBar.current.spacing),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    section.entriesInLane(lane).forEach { measuredCell(it) }
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(LocalBar.current.spacing)) {
            repeat(section.lanes) { lane ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LocalBar.current.spacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    section.entriesInLane(lane).forEach { measuredCell(it) }
                }
            }
        }
    }
}
