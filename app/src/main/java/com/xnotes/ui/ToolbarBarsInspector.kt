package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarScale
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarSize
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** A reusable inspector for XNote and XCanvas. The inspector has no dependency on
 * Editor, preferences storage, toolbar gestures or the current document. */
@Composable
internal fun ToolbarBarsInspector(
    workspace: ToolbarWorkspace,
    selectedBar: ToolbarBar,
    look: ToolbarLook,
    screenWidth: Dp,
    screenHeight: Dp,
    colorCount: Int,
    onUpdateBar: ((ToolbarBar) -> ToolbarBar) -> Unit,
    onConfigureDock: (ToolbarGeometry, ToolbarLook) -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val selectedIndex = workspace.bars.indexOfFirst { it.id == selectedBar.id }.coerceAtLeast(0)
    val effectiveEdge = toolbarDockEdge(selectedBar, selectedIndex, look)
        ?: selectedBar.geometry.anchor.floatingEdge()
    val vertical = effectiveEdge == ToolbarDockEdge.LEFT || effectiveEdge == ToolbarDockEdge.RIGHT
    // Geometry planning can traverse every toolbar and lane. Do it only when the
    // workspace or viewport changes, not on unrelated inspector recompositions.
    val plan = remember(workspace, look, screenWidth, screenHeight, colorCount) {
        toolbarPreviewLayoutPlan(workspace, look, screenWidth, screenHeight, colorCount)
    }
    val docked = toolbarDockEdge(selectedBar, selectedIndex, look) != null
    val placement = if (docked) plan.docked.placement(selectedBar.id)
        else plan.floating.placement(selectedBar.id)

    // Identity is separate from all behavior and placement modifiers.
    ToolbarInspectorCard {
        Text(stringResource(R.string.toolbar_config_bar_details), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = selectedBar.name,
            onValueChange = { value -> onUpdateBar { bar -> bar.copy(name = value.take(48)) } },
            label = { Text(stringResource(R.string.toolbar_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val placementControls: @Composable () -> Unit = {
        ToolbarInspectorCard {
            ToolbarBarSettings(
                currentBar = selectedBar,
                barIndex = selectedIndex,
                barCount = workspace.bars.size,
                look = look,
                onUpdateBar = onUpdateBar,
                onConfigureDock = onConfigureDock,
            )
        }
    }
    val sizeControls: @Composable () -> Unit = {
        ToolbarInspectorCard {
            ToolbarAppearanceSettings(
                geometry = selectedBar.geometry,
                vertical = vertical,
                onGeometryChange = { geometry -> onConfigureDock(geometry, look) },
                onScaleChange = { scale ->
                    val size = when (scale) {
                        ToolbarScale.COMPACT -> ToolbarSize.COMPACT
                        ToolbarScale.REGULAR -> ToolbarSize.REGULAR
                        ToolbarScale.COMFORTABLE -> ToolbarSize.COMFORTABLE
                    }
                    onConfigureDock(selectedBar.geometry.copy(scale = scale),
                        if (selectedIndex == 0) look.copy(size = size) else look)
                },
            )
            if (placement != null && placement.lane != selectedBar.geometry.dockLane) {
                Text(context.getString(R.string.toolbar_config_redistributed_lane, placement.lane + 1),
                    color = palette.accent.toComposeColor(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 660.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { placementControls() }
                Box(Modifier.weight(1f)) { sizeControls() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                placementControls()
                sizeControls()
            }
        }
    }
}

@Composable
internal fun ToolbarInspectorCard(content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth()
            .background(palette.panel.toComposeColor().copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .border(1.dp, palette.border.toComposeColor().copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}
