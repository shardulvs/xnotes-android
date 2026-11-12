package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Bar controls emit edits; profile selection, placement validation and persistence belong to the caller. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolbarBarSettings(
    currentBar: ToolbarBar,
    barIndex: Int,
    visualIndex: Int = barIndex,
    barCount: Int,
    look: ToolbarLook,
    onUpdateBar: ((ToolbarBar) -> ToolbarBar) -> Unit,
    onConfigureDock: (ToolbarGeometry, ToolbarLook) -> Unit,
    onReorder: (Int) -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalContext.current
    val isPrimary = barIndex == 0
    val docked = if (isPrimary) toolbarDockEdge(currentBar, barIndex, look) != null
        else currentBar.geometry.dockEdge != null && !currentBar.geometry.floating
    val floating = !docked
    fun configureDock(geometry: ToolbarGeometry, nextLook: ToolbarLook = look) {
        onConfigureDock(geometry, nextLook)
    }
    val edge = if (isPrimary) toolbarDockEdge(currentBar, barIndex, look)
        else currentBar.geometry.dockEdge
    val selectedAnchor = if (!docked) currentBar.geometry.anchor else when (edge) {
        ToolbarDockEdge.TOP -> ToolbarAnchor.TOP_CENTER
        ToolbarDockEdge.BOTTOM -> ToolbarAnchor.BOTTOM_CENTER
        ToolbarDockEdge.LEFT -> ToolbarAnchor.CENTER_LEFT
        ToolbarDockEdge.RIGHT -> ToolbarAnchor.CENTER_RIGHT
        null -> currentBar.geometry.anchor
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Balanced group pairs: short toggle groups take equal space, while the anchor
        // matrix stays adjacent to lane and alignment controls. Narrow screens stack.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val blockWidth = if (maxWidth >= 370.dp) (maxWidth - 12.dp) / 2 else maxWidth
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.width(blockWidth).testTag("toolbar-behavior-controls"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Related switches live together; no identity text field inside modifier groups.
        ToolbarSettingsLabel(stringResource(R.string.toolbar_config_behavior))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreferenceChoiceChip(stringResource(R.string.toolbar_movable), currentBar.geometry.movable) {
                onUpdateBar { it.copy(geometry = it.geometry.copy(movable = !it.geometry.movable)) }
            }
            if (!isPrimary) {
                PreferenceChoiceChip(stringResource(R.string.toolbar_visible), currentBar.visible) {
                    onUpdateBar { it.copy(visible = !it.visible) }
                }
            }
        }
                }
                Column(Modifier.width(blockWidth).testTag("toolbar-placement-controls"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolbarSettingsLabel(stringResource(R.string.toolbar_config_placement))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreferenceChoiceChip(stringResource(R.string.toolbar_docked), docked) {
                val geometry = toolbarDockedGeometry(currentBar, barIndex, look)
                configureDock(geometry, if (isPrimary) look.copy(floating = false,
                    position = geometry.dockEdge!!.toolbarPosition()) else look)
            }
            PreferenceChoiceChip(stringResource(R.string.toolbar_floating), floating) {
                configureDock(toolbarFloatingGeometry(currentBar, barIndex, look),
                    if (isPrimary) look.copy(floating = true) else look)
            }
        }
                }
            }
        }
        androidx.compose.material3.HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 375.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(164.dp).testTag("toolbar-position-controls"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolbarSettingsLabel(stringResource(R.string.pref_toolbar_position))
        ToolbarAnchorGrid(selected = selectedAnchor, docked = docked, onSelect = { anchor ->
            if (docked) {
                val nextEdge = when (anchor) {
                    ToolbarAnchor.TOP_CENTER -> ToolbarDockEdge.TOP
                    ToolbarAnchor.BOTTOM_CENTER -> ToolbarDockEdge.BOTTOM
                    ToolbarAnchor.CENTER_LEFT -> ToolbarDockEdge.LEFT
                    ToolbarAnchor.CENTER_RIGHT -> ToolbarDockEdge.RIGHT
                    else -> edge ?: ToolbarDockEdge.TOP
                }
                val geometry = toolbarGeometryForDockSelection(currentBar, barIndex, look, nextEdge)
                configureDock(geometry, if (isPrimary) look.copy(floating = false,
                    position = nextEdge.toolbarPosition()) else look)
            } else {
                configureDock(currentBar.geometry.movedTo(anchor, floating = true,
                    fromEdge = edge ?: currentBar.geometry.anchor.floatingEdge()))
            }
        })
                    }
                    Column(Modifier.weight(1f).testTag("toolbar-lane-controls"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolbarSettingsLabel(stringResource(R.string.toolbar_config_lane_and_alignment))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolbarSettingsLabel(stringResource(if (floating)
                    R.string.toolbar_config_preferred_floating_lane_maximum_3
                    else R.string.toolbar_config_dock_lane_maximum_3_per_edge))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (lane in 0..2) {
                        PreferenceChoiceChip(strings.getString(R.string.toolbar_config_lane_number, lane + 1),
                            currentBar.geometry.dockLane == lane) {
                            configureDock(currentBar.geometry.copy(dockLane = lane))
                        }
                    }
                }
                ToolbarSettingsLabel(stringResource(R.string.toolbar_config_lane_alignment))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for ((align, label) in listOf(
                        ToolbarDockAlignment.START to strings.getString(R.string.toolbar_config_start),
                        ToolbarDockAlignment.CENTER to strings.getString(R.string.toolbar_config_center),
                        ToolbarDockAlignment.END to strings.getString(R.string.toolbar_config_end))) {
                        val selectedAlignment = if (floating) toolbarFloatingAlignment(currentBar)
                            else currentBar.geometry.dockAlignment
                        PreferenceChoiceChip(label, selectedAlignment == align) {
                            val anchor = if (floating) when (currentBar.geometry.anchor.floatingEdge()) {
                                ToolbarDockEdge.TOP -> when (align) {
                                    ToolbarDockAlignment.START -> ToolbarAnchor.TOP_LEFT
                                    ToolbarDockAlignment.CENTER -> ToolbarAnchor.TOP_CENTER
                                    ToolbarDockAlignment.END -> ToolbarAnchor.TOP_RIGHT
                                }
                                ToolbarDockEdge.BOTTOM -> when (align) {
                                    ToolbarDockAlignment.START -> ToolbarAnchor.BOTTOM_LEFT
                                    ToolbarDockAlignment.CENTER -> ToolbarAnchor.BOTTOM_CENTER
                                    ToolbarDockAlignment.END -> ToolbarAnchor.BOTTOM_RIGHT
                                }
                                ToolbarDockEdge.LEFT -> ToolbarAnchor.CENTER_LEFT
                                ToolbarDockEdge.RIGHT -> ToolbarAnchor.CENTER_RIGHT
                            } else currentBar.geometry.anchor
                            configureDock(currentBar.geometry.copy(dockAlignment = align, anchor = anchor))
                        }
                    }
                }
            }
        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolbarSettingsLabel(stringResource(R.string.pref_toolbar_position))
        ToolbarAnchorGrid(selected = selectedAnchor, docked = docked, onSelect = { anchor ->
            if (docked) {
                val nextEdge = when (anchor) {
                    ToolbarAnchor.TOP_CENTER -> ToolbarDockEdge.TOP
                    ToolbarAnchor.BOTTOM_CENTER -> ToolbarDockEdge.BOTTOM
                    ToolbarAnchor.CENTER_LEFT -> ToolbarDockEdge.LEFT
                    ToolbarAnchor.CENTER_RIGHT -> ToolbarDockEdge.RIGHT
                    else -> edge ?: ToolbarDockEdge.TOP
                }
                val geometry = toolbarGeometryForDockSelection(currentBar, barIndex, look, nextEdge)
                configureDock(geometry, if (isPrimary) look.copy(floating = false,
                    position = nextEdge.toolbarPosition()) else look)
            } else {
                configureDock(currentBar.geometry.movedTo(anchor, floating = true,
                    fromEdge = edge ?: currentBar.geometry.anchor.floatingEdge()))
            }
        })
                    androidx.compose.material3.HorizontalDivider()
        ToolbarSettingsLabel(stringResource(R.string.toolbar_config_lane_and_alignment))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolbarSettingsLabel(stringResource(if (floating)
                    R.string.toolbar_config_preferred_floating_lane_maximum_3
                    else R.string.toolbar_config_dock_lane_maximum_3_per_edge))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (lane in 0..2) {
                        PreferenceChoiceChip(strings.getString(R.string.toolbar_config_lane_number, lane + 1),
                            currentBar.geometry.dockLane == lane) {
                            configureDock(currentBar.geometry.copy(dockLane = lane))
                        }
                    }
                }
                ToolbarSettingsLabel(stringResource(R.string.toolbar_config_lane_alignment))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for ((align, label) in listOf(
                        ToolbarDockAlignment.START to strings.getString(R.string.toolbar_config_start),
                        ToolbarDockAlignment.CENTER to strings.getString(R.string.toolbar_config_center),
                        ToolbarDockAlignment.END to strings.getString(R.string.toolbar_config_end))) {
                        val selectedAlignment = if (floating) toolbarFloatingAlignment(currentBar)
                            else currentBar.geometry.dockAlignment
                        PreferenceChoiceChip(label, selectedAlignment == align) {
                            val anchor = if (floating) when (currentBar.geometry.anchor.floatingEdge()) {
                                ToolbarDockEdge.TOP -> when (align) {
                                    ToolbarDockAlignment.START -> ToolbarAnchor.TOP_LEFT
                                    ToolbarDockAlignment.CENTER -> ToolbarAnchor.TOP_CENTER
                                    ToolbarDockAlignment.END -> ToolbarAnchor.TOP_RIGHT
                                }
                                ToolbarDockEdge.BOTTOM -> when (align) {
                                    ToolbarDockAlignment.START -> ToolbarAnchor.BOTTOM_LEFT
                                    ToolbarDockAlignment.CENTER -> ToolbarAnchor.BOTTOM_CENTER
                                    ToolbarDockAlignment.END -> ToolbarAnchor.BOTTOM_RIGHT
                                }
                                ToolbarDockEdge.LEFT -> ToolbarAnchor.CENTER_LEFT
                                ToolbarDockEdge.RIGHT -> ToolbarAnchor.CENTER_RIGHT
                            } else currentBar.geometry.anchor
                            configureDock(currentBar.geometry.copy(dockAlignment = align, anchor = anchor))
                        }
                    }
                }
            }
        }
                }
            }
        }
        if (barCount > 1) {
            Text(stringResource(R.string.toolbar_config_reorder_drag_hint),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = LocalPalette.current.textDim.toComposeColor())
        }
    }
}

@Composable
private fun ToolbarSettingsLabel(text: String) {
    Text(text, color = LocalPalette.current.accent.toComposeColor(), fontSize = 13.sp)
}

@Composable
private fun ToolbarAnchorGrid(
    selected: ToolbarAnchor,
    docked: Boolean = false,
    onSelect: (ToolbarAnchor) -> Unit,
) {
    val palette = LocalPalette.current
    val symbols = listOf("↖", "↑", "↗", "←", "", "→", "↙", "↓", "↘")
    val dockable = setOf(
        ToolbarAnchor.TOP_CENTER, ToolbarAnchor.CENTER_LEFT,
        ToolbarAnchor.CENTER_RIGHT, ToolbarAnchor.BOTTOM_CENTER,
    )
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (column in 0..2) {
                    val index = row * 3 + column
                    if (index == 4) {
                        Spacer(Modifier.size(48.dp))
                        continue
                    }
                    val anchor = ToolbarAnchor.entries[index]
                    val enabled = !docked || anchor in dockable
                    val active = selected == anchor && enabled
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (active) palette.accent.toComposeColor().copy(alpha = 0.18f)
                                else palette.panel.toComposeColor(),
                            )
                            .border(
                                if (active) 2.dp else 1.dp,
                                (if (active) palette.accent else palette.border).toComposeColor(),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable(enabled = enabled) { onSelect(anchor) }
                            .semantics {
                                contentDescription = anchor.name.replace('_', ' ').lowercase()
                                this.selected = active
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            symbols[index],
                            color = (if (!enabled) palette.textDim else if (active) palette.accent else palette.text).toComposeColor()
                                .copy(alpha = if (enabled) 1f else 0.35f),
                            fontSize = 21.sp,
                        )
                    }
                }
            }
        }
        if (docked) {
            Text(
                stringResource(R.string.toolbar_docked_positions_hint),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
        }
    }
}
