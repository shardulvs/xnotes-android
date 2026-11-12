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
import androidx.compose.material3.MaterialTheme
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

/** Bar controls emit edits; validation and persistence belong to the caller.
 * Layout changes only how the controls are arranged, never their callbacks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolbarBarSettings(
    currentBar: ToolbarBar,
    barIndex: Int,
    barCount: Int,
    look: ToolbarLook,
    onUpdateBar: ((ToolbarBar) -> ToolbarBar) -> Unit,
    onConfigureDock: (ToolbarGeometry, ToolbarLook) -> Unit,
) {
    val isPrimary = barIndex == 0
    val edge = toolbarDockEdge(currentBar, barIndex, look)
    val docked = edge != null
    val floating = !docked
    val selectedAnchor = if (floating) currentBar.geometry.anchor else when (edge) {
        ToolbarDockEdge.TOP -> ToolbarAnchor.TOP_CENTER
        ToolbarDockEdge.BOTTOM -> ToolbarAnchor.BOTTOM_CENTER
        ToolbarDockEdge.LEFT -> ToolbarAnchor.CENTER_LEFT
        ToolbarDockEdge.RIGHT -> ToolbarAnchor.CENTER_RIGHT
    }

    fun configure(geometry: ToolbarGeometry, nextLook: ToolbarLook = look) =
        onConfigureDock(geometry, nextLook)

    fun chooseAnchor(anchor: ToolbarAnchor) {
        if (docked) {
            val newEdge = when (anchor) {
                ToolbarAnchor.TOP_CENTER -> ToolbarDockEdge.TOP
                ToolbarAnchor.BOTTOM_CENTER -> ToolbarDockEdge.BOTTOM
                ToolbarAnchor.CENTER_LEFT -> ToolbarDockEdge.LEFT
                ToolbarAnchor.CENTER_RIGHT -> ToolbarDockEdge.RIGHT
                else -> edge ?: ToolbarDockEdge.TOP
            }
            configure(toolbarGeometryForDockSelection(currentBar, barIndex, look, newEdge),
                if (isPrimary) look.copy(floating = false, position = newEdge.toolbarPosition()) else look)
        } else {
            configure(currentBar.geometry.movedTo(anchor, floating = true,
                fromEdge = edge ?: currentBar.geometry.anchor.floatingEdge()))
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Two compact groups share the width, stacking when the pane is narrow.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val groupWidth = if (maxWidth >= 370.dp) (maxWidth - 12.dp) / 2 else maxWidth
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.width(groupWidth).testTag("toolbar-behavior-controls"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolbarSettingsLabel(stringResource(R.string.toolbar_config_behavior))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PreferenceChoiceChip(stringResource(R.string.toolbar_movable),
                            currentBar.geometry.movable) {
                            onUpdateBar { bar -> bar.copy(geometry = bar.geometry.copy(
                                movable = !bar.geometry.movable)) }
                        }
                        if (!isPrimary) {
                            PreferenceChoiceChip(stringResource(R.string.toolbar_visible), currentBar.visible) {
                                onUpdateBar { bar -> bar.copy(visible = !bar.visible) }
                            }
                        }
                    }
                }
                Column(Modifier.width(groupWidth).testTag("toolbar-placement-controls"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolbarSettingsLabel(stringResource(R.string.toolbar_config_placement))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PreferenceChoiceChip(stringResource(R.string.toolbar_docked), docked) {
                            val geometry = toolbarDockedGeometry(currentBar, barIndex, look)
                            configure(geometry, if (isPrimary) look.copy(
                                floating = false, position = geometry.dockEdge!!.toolbarPosition()) else look)
                        }
                        PreferenceChoiceChip(stringResource(R.string.toolbar_floating), floating) {
                            configure(toolbarFloatingGeometry(currentBar, barIndex, look),
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
                    Column(Modifier.width(164.dp).testTag("toolbar-position-controls"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolbarPositionSelector(selectedAnchor, docked, ::chooseAnchor)
                    }
                    Column(Modifier.weight(1f).testTag("toolbar-lane-controls"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolbarLaneAlignmentSelector(currentBar, floating, ::configure)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolbarPositionSelector(selectedAnchor, docked, ::chooseAnchor)
                    androidx.compose.material3.HorizontalDivider()
                    ToolbarLaneAlignmentSelector(currentBar, floating, ::configure)
                }
            }
        }
        if (barCount > 1) {
            Text(stringResource(R.string.toolbar_config_reorder_drag_hint),
                style = MaterialTheme.typography.bodySmall,
                color = LocalPalette.current.textDim.toComposeColor())
        }
    }
}

/** One position selector, shared by the wide and narrow inspector layouts. */
@Composable
private fun ToolbarPositionSelector(
    selected: ToolbarAnchor,
    docked: Boolean,
    onSelect: (ToolbarAnchor) -> Unit,
) {
    ToolbarSettingsLabel(stringResource(R.string.pref_toolbar_position))
    ToolbarAnchorGrid(selected = selected, docked = docked, onSelect = onSelect)
}

/** Lane/alignment selection has the same semantics in either responsive layout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolbarLaneAlignmentSelector(
    bar: ToolbarBar,
    floating: Boolean,
    onConfigure: (ToolbarGeometry) -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalContext.current
    ToolbarSettingsLabel(stringResource(R.string.toolbar_config_lane_and_alignment))
    ToolbarSettingsLabel(stringResource(if (floating)
        R.string.toolbar_config_preferred_floating_lane_maximum_3
        else R.string.toolbar_config_dock_lane_maximum_3_per_edge))
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (lane in 0..2) {
            PreferenceChoiceChip(strings.getString(R.string.toolbar_config_lane_number, lane + 1),
                bar.geometry.dockLane == lane) {
                onConfigure(bar.geometry.copy(dockLane = lane))
            }
        }
    }
    ToolbarSettingsLabel(stringResource(R.string.toolbar_config_lane_alignment))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val selected = if (floating) toolbarFloatingAlignment(bar) else bar.geometry.dockAlignment
        for ((alignment, label) in listOf(
            ToolbarDockAlignment.START to strings.getString(R.string.toolbar_config_start),
            ToolbarDockAlignment.CENTER to strings.getString(R.string.toolbar_config_center),
            ToolbarDockAlignment.END to strings.getString(R.string.toolbar_config_end))) {
            PreferenceChoiceChip(label, selected == alignment) {
                val anchor = if (floating) toolbarAnchorForAlignment(
                    bar.geometry.anchor.floatingEdge(), alignment) else bar.geometry.anchor
                onConfigure(bar.geometry.copy(dockAlignment = alignment, anchor = anchor))
            }
        }
    }
}

/** Keep alignment-to-anchor conversion outside Compose callbacks for unit testing. */
internal fun toolbarAnchorForAlignment(
    edge: ToolbarDockEdge,
    alignment: ToolbarDockAlignment,
): ToolbarAnchor = when (edge) {
    ToolbarDockEdge.TOP -> when (alignment) {
        ToolbarDockAlignment.START -> ToolbarAnchor.TOP_LEFT
        ToolbarDockAlignment.CENTER -> ToolbarAnchor.TOP_CENTER
        ToolbarDockAlignment.END -> ToolbarAnchor.TOP_RIGHT
    }
    ToolbarDockEdge.BOTTOM -> when (alignment) {
        ToolbarDockAlignment.START -> ToolbarAnchor.BOTTOM_LEFT
        ToolbarDockAlignment.CENTER -> ToolbarAnchor.BOTTOM_CENTER
        ToolbarDockAlignment.END -> ToolbarAnchor.BOTTOM_RIGHT
    }
    ToolbarDockEdge.LEFT -> ToolbarAnchor.CENTER_LEFT
    ToolbarDockEdge.RIGHT -> ToolbarAnchor.CENTER_RIGHT
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
