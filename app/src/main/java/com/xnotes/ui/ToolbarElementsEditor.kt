package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Element controls share the pane's interaction state and emit edits to its selected profile. */
@Composable
internal fun ToolbarElementsEditor(
    layout: ToolbarLayout,
    currentLayout: () -> ToolbarLayout,
    availableItems: Set<ToolbarItem>,
    interaction: ToolbarEditorInteractionState,
    defaultEntrySize: Int,
    colorCount: Int,
    colorControlsExpanded: Boolean,
    onToggleColorControls: () -> Unit,
    onColorCountChange: (Int) -> Unit,
    onApplyLayout: (ToolbarLayout) -> Unit,
) {
    val palette = LocalPalette.current
    var selectedEntry by interaction.selectedEntry
    var dragItem by interaction.dragItem
    var dragSection by interaction.dragSection
    var dragFinger by interaction.dragFinger
    var dragGrab by interaction.dragGrab
    var dropTarget by interaction.dropTarget
    var dragRegionTarget by interaction.dragRegionTarget
    var sectionDropTarget by interaction.sectionDropTarget
    val chipBounds = interaction.chipBounds
    val laneBounds = interaction.laneBounds
    val sectionBounds = interaction.sectionBounds
    val sectionGrabBounds = interaction.sectionGrabBounds
    val regionBounds = interaction.regionBounds
    val entryPosition = selectedEntry
    val activeEntry = entryPosition?.let { (section, index) ->
        layout.sections.getOrNull(section)?.entries?.getOrNull(index)
    }
    fun retarget() = interaction.retargetItem(currentLayout())
    fun retargetSection() = interaction.retargetSection(currentLayout())
    if (activeEntry != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tamanho do elemento", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { selectedEntry = null }) { Text("Fechar") }
        }
        ToolbarEntrySizeControls(
            entry = activeEntry,
            defaultSizeDp = defaultEntrySize,
            onChange = { size ->
                onApplyLayout(currentLayout().setEntrySize(entryPosition.first, entryPosition.second, size))
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Paleta de cores", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onToggleColorControls() }) {
            Text(if (colorControlsExpanded) "Recolher" else "Configurar")
        }
    }
    if (colorControlsExpanded) {
        // Palette is shared; only the number of blocks belongs to this bar.
        ElementsFieldLabel(stringResource(R.string.pref_toolbar_colours_n, colorCount))
        Slider(
            value = colorCount.toFloat(),
            onValueChange = {
                val total = Math.round(it)
                onColorCountChange(total)
                if (currentLayout().colorBlockCount > total)
                    onApplyLayout(currentLayout().setColorBlocks(total))
            },
            valueRange = 1f..InkPalette.MAX_SWATCHES.toFloat(),
            steps = InkPalette.MAX_SWATCHES - 2,
            modifier = Modifier.width(280.dp),
        )
        ElementsFieldLabel("Distribuir cores em ${layout.colorBlockCount} blocos")
        if (colorCount > 1 && layout.colorBlockCount > 0) {
            Slider(
                value = layout.colorBlockCount.coerceIn(1, colorCount).toFloat(),
                onValueChange = { onApplyLayout(currentLayout().setColorBlocks(Math.round(it))) },
                valueRange = 1f..colorCount.toFloat(),
                steps = (colorCount - 2).coerceAtLeast(0),
                modifier = Modifier.width(280.dp),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.toolbar_distribution), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        PreferenceChoiceChip(stringResource(R.string.toolbar_continuous), layout.distribution == ToolbarDistribution.CONTINUOUS) {
            selectedEntry = null
            onApplyLayout(currentLayout().setDistribution(ToolbarDistribution.CONTINUOUS))
        }
        PreferenceChoiceChip(stringResource(R.string.toolbar_regions), layout.distribution == ToolbarDistribution.REGIONS) {
            selectedEntry = null
            onApplyLayout(currentLayout().setDistribution(ToolbarDistribution.REGIONS))
        }
    }
    if (layout.distribution == ToolbarDistribution.REGIONS &&
        layout.sections.any { it.region != ToolbarRegion.START }) {
        TextButton(onClick = {
            selectedEntry = null
            onApplyLayout(currentLayout().gatherSectionsAtStart())
        }) { Text("Reunir sections no início", fontSize = 12.sp) }
    }
    ToolbarCustomizerBody(
        layout = layout,
        availableItems = availableItems,
        dragItem = dragItem,
        selectedEntry = selectedEntry,
        dropTarget = dropTarget,
        dragRegionTarget = dragRegionTarget,
        dragSection = dragSection,
        sectionDropTarget = sectionDropTarget,
        chipBounds = chipBounds,
        laneBounds = laneBounds,
        sectionBounds = sectionBounds,
        sectionGrabBounds = sectionGrabBounds,
        regionBounds = regionBounds,
        onToggle = { s, i -> onApplyLayout(currentLayout().toggleVisible(s, i)) },
        onSelect = { s, i -> selectedEntry = s to i },
        onRemoveItem = { s, i ->
            onApplyLayout(currentLayout().removeItem(s, i))
            selectedEntry = null
        },
        onAddItem = { s, lane, item -> onApplyLayout(currentLayout().addItem(s, lane, item)) },
        onLanesChange = { s, lanes -> onApplyLayout(currentLayout().setSectionLanes(s, lanes)) },
        onRegionChange = { s, region -> onApplyLayout(currentLayout().setSectionRegion(s, region)) },
        onDelete = { s -> onApplyLayout(currentLayout().removeSection(s)) },
        onAdd = { lanes, region -> onApplyLayout(currentLayout().addSection(lanes, region)) },
        onDragStart = { section, index, local ->
            val key = section to index
            dragItem = key
            // The floating copy now has the same dimensions as the source row.
            dragGrab = local
            dragFinger = (chipBounds[key]?.topLeft ?: Offset.Zero) + local
            retarget()
        },
        onDrag = { delta -> dragFinger += delta; retarget() },
        onDrop = {
            val target = dropTarget
            val region = dragRegionTarget
            val moving = dragItem
            if (moving != null) {
                val current = currentLayout()
                val next = when {
                    target != null -> current.moveItem(moving.first, moving.second,
                        target.first, target.third, target.second)
                    region != null -> current.moveItemToNewRegionSection(moving.first, moving.second, region)
                    else -> current
                }
                if (next != current) {
                    onApplyLayout(next)
                    selectedEntry = null
                }
            }
            dragItem = null
            dropTarget = null
            dragRegionTarget = null
        },
        onCancel = {
            dragItem = null
            dropTarget = null
            dragRegionTarget = null
        },
        onSectionDragStart = { sec, local ->
            dragSection = sec
            val grab = sectionGrabBounds[sec]?.topLeft ?: sectionBounds[sec]?.topLeft ?: Offset.Zero
            val card = sectionBounds[sec]?.topLeft ?: Offset.Zero
            dragFinger = grab + local
            dragGrab = dragFinger - card
            retargetSection()
        },
        onSectionDrag = { delta -> dragFinger += delta; retargetSection() },
        onSectionDrop = {
            val from = dragSection
            val target = sectionDropTarget
            if (from != null && target != null) {
                val cur = currentLayout()
                if (cur.distribution == ToolbarDistribution.REGIONS) {
                    onApplyLayout(cur.moveSectionToRegion(from, target.region, target.beforeSection))
                } else {
                    val insertAt = target.beforeSection ?: cur.sections.size
                    val to = (if (insertAt > from) insertAt - 1 else insertAt).coerceIn(0, cur.sections.lastIndex)
                    if (to != from) onApplyLayout(cur.moveSection(from, to))
                }
            }
            dragSection = null
            sectionDropTarget = null
        },
        onSectionCancel = {
            dragSection = null
            sectionDropTarget = null
        },
    )
}

@Composable
private fun ElementsFieldLabel(text: String) {
    Text(text, color = LocalPalette.current.accent.toComposeColor(), fontSize = 13.sp)
}
