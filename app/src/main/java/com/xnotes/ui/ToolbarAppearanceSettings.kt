package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarScale
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Presentation only: the caller validates placement and persists changes to the active profile. */
@Composable
internal fun ToolbarAppearanceSettings(
    geometry: ToolbarGeometry,
    onGeometryChange: (ToolbarGeometry) -> Unit,
    onScaleChange: (ToolbarScale) -> Unit,
) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    Text(toolbarStrings.getString(R.string.toolbar_config_size_and_spacing), style = MaterialTheme.typography.titleSmall)
    ToolbarGeometryControls(geometry = geometry, onChange = onGeometryChange)
    Text(
        stringResource(R.string.pref_toolbar_size),
        color = LocalPalette.current.accent.toComposeColor(),
        fontSize = 13.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
}
