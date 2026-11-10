package com.xnotes.ui

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
    onChange: (ToolbarGeometry) -> Unit,
) {
    val screen = LocalConfiguration.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolbarMeasure("Largura", geometry.widthDp, screen.screenWidthDp.coerceIn(48, 1200), 48, 1200) {
            onChange(geometry.copy(widthDp = it))
        }
        ToolbarMeasure("Altura", geometry.heightDp, 56, 48, 800) {
            onChange(geometry.copy(heightDp = it))
        }
        ToolbarMeasure("Padding", geometry.paddingDp, 4, 0, 32) {
            onChange(geometry.copy(paddingDp = it))
        }
        ToolbarMeasure("Espaçamento", geometry.spacingDp, 2, 0, 32) {
            onChange(geometry.copy(spacingDp = it))
        }
        ToolbarMeasure("Botões", geometry.elementSizeDp, 42, 28, 72) {
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
    Column(Modifier.width(196.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$title: ${value?.let { "$it dp" } ?: "automático"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // Keep the button's space even in auto mode; otherwise the row shifts
            // when the first slider touch creates an override.
            TextButton(enabled = value != null, onClick = { onChange(null) }) {
                Text("Auto", style = MaterialTheme.typography.labelSmall)
            }
        }
        Slider(
            value = (value ?: defaultValue).coerceIn(minimum, maximum).toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = minimum.toFloat()..maximum.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A preview tool can have its own button size, without resizing other occurrences. */
@Composable
internal fun ToolbarEntrySizeControls(
    entry: ToolbarEntry?,
    defaultSizeDp: Int,
    onChange: (Int?) -> Unit,
) {
    if (entry == null) {
        Text(
            "Toque em uma ferramenta do preview para ajustar o tamanho somente dela.",
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
        title = if (isSpacer) "Espaço entre elementos" else "Tamanho individual",
        value = entry.sizeDp,
        defaultValue = if (isSpacer) ToolbarEntry.DEFAULT_SPACER_DP else defaultSizeDp.coerceIn(28, 72),
        minimum = if (isSpacer) 4 else 28,
        maximum = if (isSpacer) 320 else 72,
        onChange = onChange,
    )
}
