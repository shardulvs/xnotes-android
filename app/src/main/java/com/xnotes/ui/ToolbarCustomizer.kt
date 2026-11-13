package com.xnotes.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Inline toolbar editor: a single continuous layer list or Start/Center/End columns.
 * Each section is a draggable card containing one to three lanes of draggable tool rows.
 * Gesture ghosts and edge scrolling are hoisted to PreferencesPane to avoid clipping.
 */
@Composable
fun ToolbarCustomizerBody(
    layout: ToolbarLayout,
    availableItems: Set<ToolbarItem>,
    dragItem: Pair<Int, Int>?,
    selectedEntry: Pair<Int, Int>?,
    dropTarget: Triple<Int, Int, Int>?,
    dragRegionTarget: ToolbarRegion?,
    dragSection: Int?,
    sectionDropTarget: ToolbarSectionTarget?,
    chipBounds: MutableMap<Pair<Int, Int>, Rect>,
    laneBounds: MutableMap<Pair<Int, Int>, Rect>,
    sectionBounds: MutableMap<Int, Rect>,
    sectionGrabBounds: MutableMap<Int, Rect>,
    regionBounds: MutableMap<ToolbarRegion, Rect>,
    onToggle: (Int, Int) -> Unit,
    onSelect: (Int, Int) -> Unit,
    onRemoveItem: (Int, Int) -> Unit,
    onAddItem: (Int, Int, ToolbarItem) -> Unit,
    onLanesChange: (Int, Int) -> Unit,
    onRegionChange: (Int, ToolbarRegion) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: (Int, ToolbarRegion) -> Unit,
    onDragStart: (Int, Int, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onSectionDragStart: (Int, Offset) -> Unit,
    onSectionDrag: (Offset) -> Unit,
    onSectionDrop: () -> Unit,
    onSectionCancel: () -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalPalette.current
    val dragging = dragItem != null
    val draggingSection = dragSection != null
    // All section pickers share the same eligibility rules. Calculate once when
    // entries change rather than re-scanning the entire layout per row/drag frame.
    val addableItems = remember(layout, availableItems) {
        toolbarAddableItems(layout, availableItems)
    }
    val groups: List<ToolbarRegion?> = if (layout.distribution == ToolbarDistribution.REGIONS)
        ToolbarRegion.entries else listOf(null)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep every layer readable on phones: scroll the three columns instead of squeezing them.
        val compactColumns = layout.distribution == ToolbarDistribution.REGIONS && maxWidth < 720.dp
        Row(Modifier.fillMaxWidth().then(if (compactColumns) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { region ->
            Column(
                (if (compactColumns) Modifier.width(260.dp) else Modifier.weight(1f)).then(if (region != null)
                    Modifier.onGloballyPositioned { regionBounds[region] = it.boundsInRoot() }
                        .border(1.dp, (if (dragging && dragRegionTarget == region) palette.accent else palette.border)
                            .toComposeColor(), MaterialTheme.shapes.medium).padding(10.dp)
                    else Modifier),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            if (region != null) {
                Text(
                    stringResource(when (region) {
                        ToolbarRegion.START -> R.string.toolbar_region_start
                        ToolbarRegion.CENTER -> R.string.toolbar_region_center
                        ToolbarRegion.END -> R.string.toolbar_region_end
                    }),
                    color = palette.text.toComposeColor(), style = MaterialTheme.typography.titleSmall,
                )
            }
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
        layout.sections.forEachIndexed { sec, section ->
            if (region != null && section.region != region) return@forEachIndexed
            if (draggingSection && sectionDropTarget == ToolbarSectionTarget(region ?: ToolbarRegion.START, sec)) SectionCaret()
            SectionCard(
                label = toolbarStrings.getString(R.string.toolbar_config_section_number, sec + 1),
                canDelete = layout.sections.size > 1,
                lanes = section.lanes,
                region = section.region,
                regionsEnabled = region != null,
                onLanesChange = { onLanesChange(sec, it) },
                onRegionChange = { onRegionChange(sec, it) },
                targeted = dragging && dropTarget?.first == sec,
                dimmed = dragSection == sec,
                onDelete = { onDelete(sec) },
                onBounds = { sectionBounds[sec] = it },
                onGrabBounds = { sectionGrabBounds[sec] = it },
                onGrabDragStart = { onSectionDragStart(sec, it) },
                onGrabDrag = onSectionDrag,
                onGrabDrop = onSectionDrop,
                onGrabCancel = onSectionCancel,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    repeat(section.lanes) { lane ->
                        val cells = section.entries.withIndex().filter { it.value.lane == lane }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { laneBounds[sec to lane] = it.boundsInRoot() }
                                .border(
                                    1.dp,
                                    (if (dragging && dropTarget?.first == sec && dropTarget.second == lane)
                                        palette.accent else palette.border).toComposeColor(),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (section.lanes > 1) {
                                Text(toolbarStrings.getString(R.string.toolbar_config_row_number, lane + 1), color = palette.textDim.toComposeColor(), fontSize = 11.sp)
                            }
                            if (cells.isEmpty()) {
                                Text(
                                    stringResource(R.string.drop_here),
                                    color = palette.textDim.toComposeColor(),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                            cells.forEach { (idx, entry) ->
                                if (dragging && dropTarget == Triple(sec, lane, idx)) InsertionCaret()
                                ChipCell(
                                    item = entry.item,
                                    visible = entry.visible,
                                    layerStyle = true,
                                    dragging = dragItem == (sec to idx),
                                    selected = selectedEntry == (sec to idx),
                                    onTap = { onSelect(sec, idx) },
                                    onToggle = { onToggle(sec, idx) },
                                    onRemove = { onRemoveItem(sec, idx) },
                                    onDragStart = { local -> onDragStart(sec, idx, local) },
                                    onDrag = onDrag,
                                    onDrop = onDrop,
                                    onCancel = onCancel,
                                    onBounds = { chipBounds[sec to idx] = it },
                                )
                            }
                            if (dragging && dropTarget == Triple(sec, lane, cells.lastOrNull()?.index?.plus(1) ?: section.entries.size)) InsertionCaret()
                            AddItemPicker(
                                items = addableItems,
                                onChoose = { item -> onAddItem(sec, lane, item) },
                            )
                        }
                    }
                }
            }
        }
        if (draggingSection && sectionDropTarget == ToolbarSectionTarget(region ?: ToolbarRegion.START, null)) SectionCaret()
        if (dragging && region != null && dragRegionTarget == region && dropTarget == null) {
            Text(toolbarStrings.getString(R.string.toolbar_config_drop_to_create_a_section_here), color = palette.accent.toComposeColor(), fontSize = 12.sp)
        }
        AddSectionChip(onClick = { lines -> onAdd(lines, region ?: ToolbarRegion.START) })
            }
            }
        }
        }
    }
}
