package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.settings.ToolbarLook
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
    val selectedEdge = edge ?: if (isPrimary && !currentBar.geometry.floating && look.floating)
        look.position.dockEdge() else currentBar.geometry.anchor.floatingEdge()
    val selectedAlignment = if (floating) toolbarFloatingAlignment(currentBar)
        else currentBar.geometry.dockAlignment

    fun configure(geometry: ToolbarGeometry, nextLook: ToolbarLook = look) =
        onConfigureDock(geometry, nextLook)

    fun choosePlacement(targetEdge: ToolbarDockEdge, alignment: ToolbarDockAlignment) {
        if (docked) {
            val nextGeometry = toolbarGeometryForDockSelection(currentBar, barIndex, look, targetEdge)
                .copy(dockAlignment = alignment)
            configure(nextGeometry,
                if (isPrimary) look.copy(floating = false, position = targetEdge.toolbarPosition()) else look)
        } else {
            val anchor = toolbarAnchorForAlignment(targetEdge, alignment)
            val nextGeometry = currentBar.geometry.movedTo(anchor, floating = true,
                fromEdge = selectedEdge).copy(dockAlignment = alignment)
            configure(nextGeometry)
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
            if (maxWidth >= 460.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(240.dp).testTag("toolbar-position-controls"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolbarSettingsLabel(stringResource(R.string.toolbar_config_position_and_alignment))
                        ToolbarPositionMap(selectedEdge, selectedAlignment, ::choosePlacement)
                    }
                    Column(Modifier.weight(1f).testTag("toolbar-lane-controls"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolbarLaneSelector(currentBar, floating, ::configure)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolbarSettingsLabel(stringResource(R.string.toolbar_config_position_and_alignment))
                    ToolbarPositionMap(selectedEdge, selectedAlignment, ::choosePlacement)
                    androidx.compose.material3.HorizontalDivider()
                    ToolbarLaneSelector(currentBar, floating, ::configure)
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

/** Preferred lane is independent from edge/alignment and can overflow when full. */
@Composable
private fun ToolbarLaneSelector(
    bar: ToolbarBar,
    floating: Boolean,
    onConfigure: (ToolbarGeometry) -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalContext.current
    ToolbarSettingsLabel(stringResource(R.string.toolbar_config_preferred_lane))
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
