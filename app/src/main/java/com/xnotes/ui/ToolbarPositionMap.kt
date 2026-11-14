package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Four screen edges × three positions, without ambiguous corner arrows.
 * The 12 stops are shown around a miniature page; this exactly matches what
 * the editor and real drag gesture can persist (edge + lane alignment).
 */
@Composable
internal fun ToolbarPositionMap(
    selectedEdge: ToolbarDockEdge,
    selectedAlignment: ToolbarDockAlignment,
    onSelect: (ToolbarDockEdge, ToolbarDockAlignment) -> Unit,
) {
    val palette = LocalPalette.current
    val edgeLabels = mapOf(
        ToolbarDockEdge.TOP to stringResource(R.string.toolbar_config_top),
        ToolbarDockEdge.RIGHT to stringResource(R.string.toolbar_config_right),
        ToolbarDockEdge.BOTTOM to stringResource(R.string.toolbar_config_bottom),
        ToolbarDockEdge.LEFT to stringResource(R.string.toolbar_config_left),
    )
    val alignmentLabels = mapOf(
        ToolbarDockAlignment.START to stringResource(R.string.toolbar_config_start),
        ToolbarDockAlignment.CENTER to stringResource(R.string.toolbar_config_center),
        ToolbarDockAlignment.END to stringResource(R.string.toolbar_config_end),
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (r in 0..4) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                for (c in 0..4) {
                    val target = when {
                        r == 0 && c in 1..3 -> ToolbarDockEdge.TOP to ToolbarDockAlignment.entries[c - 1]
                        r == 4 && c in 1..3 -> ToolbarDockEdge.BOTTOM to ToolbarDockAlignment.entries[c - 1]
                        c == 0 && r in 1..3 -> ToolbarDockEdge.LEFT to ToolbarDockAlignment.entries[r - 1]
                        c == 4 && r in 1..3 -> ToolbarDockEdge.RIGHT to ToolbarDockAlignment.entries[r - 1]
                        else -> null
                    }
                    val modifier = Modifier.size(44.dp)
                    if (target == null) {
                        if (r == 2 && c == 2) {
                            Box(modifier, contentAlignment = Alignment.Center) {
                                Text("▣", color = palette.textDim.toComposeColor(), fontSize = 20.sp)
                            }
                        } else Box(modifier)
                    } else {
                        val (edge, alignment) = target
                        val active = selectedEdge == edge && selectedAlignment == alignment
                        val label = "${edgeLabels.getValue(edge)} · ${alignmentLabels.getValue(alignment)}"
                        Box(modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (active) palette.accent.toComposeColor().copy(alpha = 0.21f)
                                else palette.panel.toComposeColor())
                            .border(if (active) 2.dp else 1.dp,
                                (if (active) palette.accent else palette.border).toComposeColor(), RoundedCornerShape(7.dp))
                            .clickable { onSelect(edge, alignment) }
                            .testTag("toolbar-position-${edge.name}-${alignment.name}")
                            .semantics { contentDescription = label; selected = active },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (active) "●" else "·", fontSize = if (active) 19.sp else 22.sp,
                                color = (if (active) palette.accent else palette.textDim).toComposeColor())
                        }
                    }
                }
            }
        }
        Text("${edgeLabels.getValue(selectedEdge)} · ${alignmentLabels.getValue(selectedAlignment)}",
            color = palette.textDim.toComposeColor(), fontSize = 12.sp)
    }
}
