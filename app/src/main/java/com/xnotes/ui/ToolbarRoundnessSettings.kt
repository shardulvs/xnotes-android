package com.xnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import androidx.compose.ui.platform.testTag
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.settings.ToolbarSize

/** Selected toolbar's corner control. Null inherits global Settings > Corners.
 * The slider max follows the effective cross-axis depth and 1–3-row limit. */
@Composable
internal fun ToolbarRoundnessSettings(
    geometry: ToolbarGeometry,
    layout: ToolbarLayout,
    size: ToolbarSize,
    vertical: Boolean,
    floating: Boolean,
    onChange: (Int?) -> Unit,
) {
    val metrics = resolvedBarMetrics(size, geometry)
    val thickness = if (vertical) toolbarWidth(metrics, layout, geometry).value
        else toolbarHeight(metrics, layout, geometry).value
    val rows = toolbarCornerRows(layout)
    val availableLength = geometry.lengthDp(vertical)?.toFloat() ?: Float.POSITIVE_INFINITY
    val limit = toolbarCornerLimitDp(rows, thickness, availableLength).toInt().coerceAtLeast(1)
    val automatic = toolbarCornerRadiusDp(null, rows, thickness,
        toolbarThemeCornerRadiusDp(), floating, availableLength)
        .toInt().coerceIn(0, limit)
    Column(Modifier.fillMaxWidth().testTag("toolbar-roundness-settings")) {
        ToolbarMeasure(
            title = stringResource(R.string.toolbar_config_roundness),
            value = geometry.cornerRadiusDp?.coerceIn(0, limit),
            defaultValue = automatic,
            minimum = 0,
            maximum = limit,
            modifier = Modifier.fillMaxWidth(),
            sliderTag = "toolbar-roundness-slider",
            onChange = onChange,
        )
        Text(
            stringResource(R.string.toolbar_config_roundness_auto_help),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
