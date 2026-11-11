package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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
    barCount: Int,
    look: ToolbarLook,
    onUpdateBar: ((ToolbarBar) -> ToolbarBar) -> Unit,
    onConfigureDock: (ToolbarGeometry, ToolbarLook) -> Unit,
    onReorder: (Int) -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    val isPrimary = barIndex == 0
    fun configureDock(geometry: ToolbarGeometry, nextLook: ToolbarLook = look) {
        onConfigureDock(geometry, nextLook)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = currentBar.name,
            onValueChange = { name -> onUpdateBar { it.copy(name = name.take(48)) } },
            label = { Text(stringResource(R.string.toolbar_name)) },
            singleLine = true,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        PreferenceChoiceChip(stringResource(R.string.toolbar_movable), currentBar.geometry.movable) {
            onUpdateBar { bar -> bar.copy(geometry = bar.geometry.copy(movable = !bar.geometry.movable)) }
        }
    }
    if (!isPrimary) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PreferenceChoiceChip(stringResource(R.string.toolbar_visible), currentBar.visible) {
                onUpdateBar { it.copy(visible = !it.visible) }
            }
            if (barIndex > 1) {
                TextButton(onClick = {
                    onReorder(barIndex - 1)
                }) { Text(stringResource(R.string.toolbar_move_up)) }
            }
            if (barIndex in 1 until barCount - 1) {
                TextButton(onClick = {
                    onReorder(barIndex + 1)
                }) { Text(stringResource(R.string.toolbar_move_down)) }
            }
        }
        val secondaryDocked = currentBar.geometry.dockEdge != null && !currentBar.geometry.floating
        ToolbarSettingsLabel(toolbarStrings.getString(R.string.toolbar_config_placement))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreferenceChoiceChip(toolbarStrings.getString(R.string.toolbar_config_docked), secondaryDocked) {
                configureDock(toolbarDockedGeometry(currentBar, barIndex, look))
            }
            PreferenceChoiceChip(toolbarStrings.getString(R.string.toolbar_config_floating), !secondaryDocked) {
                configureDock(toolbarFloatingGeometry(currentBar, barIndex, look))
            }
        }
        if (secondaryDocked) {
            ToolbarSettingsLabel(toolbarStrings.getString(R.string.toolbar_config_edge))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((edge, label) in listOf(
                    ToolbarDockEdge.TOP to toolbarStrings.getString(R.string.toolbar_config_top), ToolbarDockEdge.BOTTOM to toolbarStrings.getString(R.string.toolbar_config_bottom),
                    ToolbarDockEdge.LEFT to toolbarStrings.getString(R.string.toolbar_config_left), ToolbarDockEdge.RIGHT to toolbarStrings.getString(R.string.toolbar_config_right),
                )) {
                    PreferenceChoiceChip(label, currentBar.geometry.dockEdge == edge) {
                        val geo = currentBar.geometry
                        val vertical = edge == ToolbarDockEdge.LEFT || edge == ToolbarDockEdge.RIGHT
                        configureDock(geo.copy(
                            dockEdge = edge,
                            widthDp = if (vertical && geo.widthDp == 200) null else geo.widthDp ?: if (vertical) null else 200,
                            heightDp = if (!vertical && geo.heightDp == 200) null else geo.heightDp ?: if (vertical) 200 else null,
                        ))
                    }
                }
            }
        } else {
            ToolbarSettingsLabel(stringResource(R.string.pref_toolbar_position))
            ToolbarAnchorGrid(
                selected = currentBar.geometry.anchor,
                onSelect = { anchor ->
                    onUpdateBar { bar -> bar.copy(geometry = bar.geometry.copy(anchor = anchor)) }
                },
            )
        }
    } else {
        ToolbarSettingsLabel(stringResource(R.string.toolbar_primary_appearance))
        val floatingPrimary = toolbarDockEdge(currentBar, barIndex, look) == null
        val dockAnchor = when (currentBar.geometry.dockEdge?.toolbarPosition() ?: look.position) {
            ToolbarPosition.TOP -> ToolbarAnchor.TOP_CENTER
            ToolbarPosition.BOTTOM -> ToolbarAnchor.BOTTOM_CENTER
            ToolbarPosition.LEFT -> ToolbarAnchor.CENTER_LEFT
            ToolbarPosition.RIGHT -> ToolbarAnchor.CENTER_RIGHT
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreferenceChoiceChip(stringResource(R.string.toolbar_docked), !floatingPrimary) {
                val geometry = toolbarDockedGeometry(currentBar, barIndex, look)
                configureDock(geometry, look.copy(floating = false, position = geometry.dockEdge!!.toolbarPosition()))
            }
            PreferenceChoiceChip(stringResource(R.string.toolbar_floating), floatingPrimary) {
                configureDock(toolbarFloatingGeometry(currentBar, barIndex, look), look.copy(floating = true))
            }
        }
        ToolbarSettingsLabel(stringResource(R.string.pref_toolbar_position))
        ToolbarAnchorGrid(
            selected = if (floatingPrimary) currentBar.geometry.anchor else dockAnchor,
            docked = !floatingPrimary,
            onSelect = { anchor ->
                if (!floatingPrimary) {
                    val position = when (anchor) {
                        ToolbarAnchor.TOP_CENTER -> ToolbarPosition.TOP
                        ToolbarAnchor.BOTTOM_CENTER -> ToolbarPosition.BOTTOM
                        ToolbarAnchor.CENTER_LEFT -> ToolbarPosition.LEFT
                        ToolbarAnchor.CENTER_RIGHT -> ToolbarPosition.RIGHT
                        else -> look.position
                    }
                    configureDock(currentBar.geometry.copy(anchor = anchor, floating = false), look.copy(floating = false, position = position))
                } else {
                    configureDock(currentBar.geometry.copy(anchor = anchor, floating = true))
                }
            },
        )
    }
    val isPrimaryDock = isPrimary && toolbarDockEdge(currentBar, barIndex, look) != null
    val isSecondaryDock = !isPrimary &&
        currentBar.geometry.dockEdge != null && !currentBar.geometry.floating
    val floatingBar = !isPrimaryDock && !isSecondaryDock
    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolbarSettingsLabel(if (floatingBar) toolbarStrings.getString(R.string.toolbar_config_preferred_floating_lane_maximum_3)
                else toolbarStrings.getString(R.string.toolbar_config_dock_lane_maximum_3_per_edge))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (lane in 0..2) {
                    PreferenceChoiceChip(toolbarStrings.getString(R.string.toolbar_config_lane_number, lane + 1), currentBar.geometry.dockLane == lane) {
                        configureDock(currentBar.geometry.copy(dockLane = lane))
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolbarSettingsLabel(toolbarStrings.getString(R.string.toolbar_config_lane_alignment))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((align, label) in listOf(
                    ToolbarDockAlignment.START to toolbarStrings.getString(R.string.toolbar_config_start),
                    ToolbarDockAlignment.CENTER to toolbarStrings.getString(R.string.toolbar_config_center),
                    ToolbarDockAlignment.END to toolbarStrings.getString(R.string.toolbar_config_end),
                )) {
                    PreferenceChoiceChip(label, (if (floatingBar) toolbarFloatingAlignment(currentBar)
                            else currentBar.geometry.dockAlignment) == align) {
                        val anchor = if (floatingBar) when (currentBar.geometry.anchor.floatingEdge()) {
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
