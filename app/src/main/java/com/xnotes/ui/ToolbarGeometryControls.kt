package com.xnotes.ui

import com.xnotes.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import kotlin.math.roundToInt

/**
 * Size controls for the selected bar only. Null leaves the bar at its natural
 * size. Sliding any control creates an explicit override; Reset returns to auto.
 */
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
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("toolbar-dimensions")) {
        // Group controls by purpose rather than giving Length an arbitrary larger
        // slider. Both groups use equally wide tracks on expanded tablet panes;
        // narrow panes stack the groups, keeping all controls full-width.
        val sideBySide = maxWidth >= 460.dp
        val groupWidth = if (sideBySide) ((maxWidth - 16.dp) / 2).coerceAtMost(300.dp) else maxWidth
        val groupModifier = Modifier.width(groupWidth)
        val dimensions: @Composable () -> Unit = {
            Column(groupModifier.testTag("toolbar-measure-group-dimensions"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.toolbar_config_dimensions_group),
                    style = MaterialTheme.typography.labelMedium)
                ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_length),
                    geometry.lengthDp(vertical), maxLength, 48, maxLength, Modifier.fillMaxWidth()) {
                    onChange(geometry.withLengthDp(it, vertical))
                }
                ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_thickness),
                    geometry.thicknessDp(vertical), minOf(56, maxThickness), 48, maxThickness,
                    Modifier.fillMaxWidth()) {
                    onChange(geometry.withThicknessDp(it, vertical))
                }
                ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_buttons),
                    geometry.elementSizeDp, 42, 28, 72, Modifier.fillMaxWidth()) {
                    onChange(geometry.copy(elementSizeDp = it))
                }
            }
        }
        val spacing: @Composable () -> Unit = {
            Column(groupModifier.testTag("toolbar-measure-group-spacing"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.toolbar_config_inner_spacing_group),
                    style = MaterialTheme.typography.labelMedium)
                ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_padding),
                    geometry.paddingDp, 4, 0, 32, Modifier.fillMaxWidth()) {
                    onChange(geometry.copy(paddingDp = it))
                }
                ToolbarMeasure(toolbarStrings.getString(R.string.toolbar_config_spacing),
                    geometry.spacingDp, 2, 0, 32, Modifier.fillMaxWidth()) {
                    onChange(geometry.copy(spacingDp = it))
                }
            }
        }
        if (sideBySide) {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top) {
                dimensions()
                spacing()
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                dimensions()
                spacing()
            }
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
    modifier: Modifier = Modifier.width(196.dp),
    onChange: (Int?) -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    Column(modifier.testTag("toolbar-measure")) {
        Row(Modifier.heightIn(min = 48.dp).testTag("toolbar-measure-header"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (value == null) {
                Text(toolbarStrings.getString(R.string.toolbar_config_automatic),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            } else {
                Text("$value dp", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                TextButton(onClick = { onChange(null) }) {
                    Text(toolbarStrings.getString(R.string.toolbar_config_auto),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Slider(
            value = (value ?: defaultValue).coerceIn(minimum, maximum).toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = minimum.toFloat()..maximum.toFloat(),
            modifier = Modifier.fillMaxWidth().testTag("toolbar-measure-slider"),
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
