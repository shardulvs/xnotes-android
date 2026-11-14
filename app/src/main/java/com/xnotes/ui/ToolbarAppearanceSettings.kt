package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarScale

/** Keep size presets next to the numeric size controls, not in placement. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolbarAppearanceSettings(
    geometry: ToolbarGeometry,
    vertical: Boolean,
    onGeometryChange: (ToolbarGeometry) -> Unit,
    onScaleChange: (ToolbarScale) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.toolbar_config_size_and_spacing),
            style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.pref_toolbar_size),
            style = MaterialTheme.typography.labelMedium)
        FlowRow(Modifier.fillMaxWidth().testTag("toolbar-size-presets"), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((scale, label) in listOf(
                ToolbarScale.COMPACT to R.string.toolbar_size_compact,
                ToolbarScale.REGULAR to R.string.toolbar_size_regular,
                ToolbarScale.COMFORTABLE to R.string.toolbar_size_comfortable,
            )) {
                PreferenceChoiceChip(stringResource(label), geometry.scale == scale) {
                    onScaleChange(scale)
                }
            }
        }
        HorizontalDivider()
        ToolbarGeometryControls(geometry = geometry, vertical = vertical,
            onChange = onGeometryChange)
    }
}
