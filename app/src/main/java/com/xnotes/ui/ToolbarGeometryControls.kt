package com.xnotes.ui

import com.xnotes.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import kotlin.math.roundToInt

/**
 * Size controls for the selected bar only. Null leaves the bar at its natural
 * size. Sliding any control creates an explicit override; Reset returns to auto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolbarGeometryControls(
    geometry: ToolbarGeometry,
    vertical: Boolean,
    onChange: (ToolbarGeometry) -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    val screen = LocalConfiguration.current
    val axisDp = if (vertical) screen.screenHeightDp else screen.screenWidthDp
    val acrossDp = if (vertical) screen.screenWidthDp else screen.screenHeightDp
    val maxLength = axisDp.coerceIn(48, 2048)
    val maxThickness = toolbarSafeDockDepth(acrossDp.toFloat()).roundToInt().coerceAtLeast(48)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_length), geometry.lengthDp(vertical),
            maxLength, 48, maxLength) {
            onChange(geometry.withLengthDp(it, vertical))
        }
        ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_thickness), geometry.thicknessDp(vertical),
            minOf(56, maxThickness), 48, maxThickness) {
            onChange(geometry.withThicknessDp(it, vertical))
        }
        ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_padding), geometry.paddingDp, 4, 0, 32) {
            onChange(geometry.copy(paddingDp = it))
        }
        ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_spacing), geometry.spacingDp, 2, 0, 32) {
            onChange(geometry.copy(spacingDp = it))
        }
        ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_buttons), geometry.elementSizeDp, 42, 28, 72) {
            onChange(geometry.copy(elementSizeDp = it))
        }
    }
}

@Composable
private fun ToolbarMeasure(
    title: String,
    value: Int?,
    defaultValue: Int,
    minimum: Int,
    maximum: Int,
    onChange: (Int?) -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.width(196.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$title: ${value?.let { "$it dp" } ?: toolbarStrings.getString(R.string.toolbar_config_automatic)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // Keep the button's space even in auto mode; otherwise the row shifts
            // when the first slider touch creates an override.
            TextButton(enabled = value != null, onClick = { onChange(null) }) {
                Text(toolbarStrings.getString(R.string.toolbar_config_auto), style = MaterialTheme.typography.labelSmall)
            }
        }
        Slider(
            value = (value ?: defaultValue).coerceIn(minimum, maximum).toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = minimum.toFloat()..maximum.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value != null && value > maximum) {
            Text(
                toolbarStrings.getString(R.string.toolbar_config_current_screen_limit, maximum),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** A preview tool can have its own button size, without resizing other occurrences. */
@Composable
internal fun ToolbarEntrySizeControls(
    entry: ToolbarEntry?,
    defaultSizeDp: Int,
    onChange: (Int?) -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    if (entry == null) {
        Text(
            toolbarStrings.getString(R.string.toolbar_config_tap_a_tool_in_the_preview_to_adjust_its_individual_size),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Text(
        stringResource(entry.item.labelRes),
        style = MaterialTheme.typography.titleSmall,
    )
    val isSpacer = entry.item == com.xnotes.core.tools.ToolbarItem.SPACER
    ToolbarMeasure(
        title = if (isSpacer) toolbarStrings.getString(R.string.toolbar_config_space_between_tools) else toolbarStrings.getString(R.string.toolbar_config_individual_size),
        value = entry.sizeDp,
        defaultValue = if (isSpacer) ToolbarEntry.DEFAULT_SPACER_DP else defaultSizeDp.coerceIn(28, 72),
        minimum = if (isSpacer) 4 else 28,
        maximum = if (isSpacer) 320 else 72,
        onChange = onChange,
    )
}
