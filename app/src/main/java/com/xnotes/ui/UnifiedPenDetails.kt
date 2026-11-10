package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConversions
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * One Samsung Notes-inspired panel, shared by note and canvas; all controls update the
 * SAME ToolConfig used by the existing per-tool popup. The advanced section expands in-place
 * and adapts to the selected pen. No nested tool-settings window.
 */
@Composable
internal fun UnifiedPenDetails(
    host: ToolPopupHost,
    items: List<ToolbarItem>,
    current: ToolbarItem,
    ink: Color,
    onChoose: (ToolbarItem) -> Unit,
    onSelectColor: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val surface = palette.panel.toComposeColor()
    val foreground = palette.text.toComposeColor()
    val border = palette.border.toComposeColor()
    val tool = Tool.fromId(current.id) ?: Tool.PEN
    val initial = host.toolConfig(tool)
    val widthRange = ToolConversions.widthRange(tool)
    var width by remember(tool) { mutableFloatStateOf(initial.baseWidth.toFloat()) }
    var advanced by remember { mutableStateOf(false) }
    var pressure by remember(tool) { mutableStateOf(initial.pressureEnabled) }
    var sensitivity by remember(tool) {
        mutableFloatStateOf(ToolConversions.minFactorToSensitivity(initial.pressureMinFactor).toFloat())
    }
    var multiplier by remember(tool) {
        mutableFloatStateOf(ToolConversions.directionStrengthToMultiplier(initial.directionStrength).toFloat())
    }
    var speed by remember(tool) {
        mutableFloatStateOf(ToolConversions.strengthToSpeed(initial.speedStrength).toFloat())
    }
    var tipWidth by remember(tool) { mutableFloatStateOf((initial.taperMinFactor * 100).toFloat()) }
    var dash by remember(tool) { mutableFloatStateOf(initial.dashLength.toFloat()) }
    var gap by remember(tool) { mutableFloatStateOf(initial.dashGap.toFloat()) }
    var highlighterIntensity by remember(tool) {
        mutableFloatStateOf(ToolConversions.highlighterAlphaToIntensity(initial.highlighterAlpha).toFloat())
    }
    var straight by remember(tool) { mutableStateOf(initial.straightLine) }
    var inverse by remember(tool) { mutableStateOf(initial.highlighterInverse) }
    var scale by remember(tool) { mutableStateOf(initial.scale) }
    var neon by remember(tool) { mutableStateOf(initial.neon) }
    var neonIntensity by remember(tool) {
        mutableFloatStateOf(ToolConversions.neonStrengthToIntensity(initial.neonStrength).toFloat())
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(360.dp)
                .heightIn(max = 510.dp)
                .verticalScroll(rememberScrollState())
                .background(surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.toolbar_unified_pen),
                    color = foreground,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(com.xnotes.ui.icons.XnotesIcons.close, contentDescription = stringResource(R.string.cancel))
                }
            }
            HorizontalDivider(color = border)
            Text("Canetas", color = palette.textDim.toComposeColor(),
                style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEach { item ->
                    val active = item == current
                    Column(
                        Modifier
                            .width(64.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (active) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
                            .border(
                                if (active) 1.5.dp else 1.dp,
                                if (active) palette.accent.toComposeColor() else border,
                                MaterialTheme.shapes.medium,
                            )
                            .clickable { onChoose(item) }
                            .padding(horizontal = 4.dp, vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        PenSilhouette(item, ink, Modifier.size(width = 22.dp, height = 31.dp))
                        Text(
                            stringResource(item.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                            color = foreground,
                        )
                    }
                }
            }
            HorizontalDivider(color = border)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.caption_width), color = foreground,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(width.roundToInt().toString(), color = palette.textDim.toComposeColor(),
                    style = MaterialTheme.typography.labelLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    width = (width - 1f).coerceAtLeast(widthRange.start.toFloat())
                    host.updateToolConfig(tool, host.toolConfig(tool).copy(baseWidth = width.toDouble()))
                }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Slider(
                    value = width.coerceIn(widthRange.start.toFloat(), widthRange.endInclusive.toFloat()),
                    onValueChange = {
                        width = it
                        host.updateToolConfig(tool, host.toolConfig(tool).copy(baseWidth = it.toDouble()))
                    },
                    valueRange = widthRange.start.toFloat()..widthRange.endInclusive.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    width = (width + 1f).coerceAtMost(widthRange.endInclusive.toFloat())
                    host.updateToolConfig(tool, host.toolConfig(tool).copy(baseWidth = width.toDouble()))
                }) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
            Box(
                Modifier.fillMaxWidth().height(44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(palette.selectionBackground.toComposeColor()),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 24.dp)) {
                    drawLine(
                        color = foreground,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = (width / widthRange.endInclusive.toFloat()).coerceAtLeast(.08f) * 10.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            Text(stringResource(R.string.caption_colour), color = foreground,
                style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                host.hostToolbarColors.take(host.hostToolbarColorCount).forEachIndexed { index, rgba ->
                    // A small swatch sits inside a larger touch target for finger and stylus.
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).clickable { onSelectColor(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(rgba.toComposeColor())
                                .border(
                                    if (index == host.hostActiveColorIndex) 2.dp else 1.dp,
                                    if (index == host.hostActiveColorIndex) foreground else border,
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
            HorizontalDivider(color = border)
            TextButton(onClick = { advanced = !advanced }) {
                Text(
                    stringResource(R.string.toolbar_pen_advanced_settings) + if (advanced) "  ▴" else "  ▾",
                    color = palette.accent.toComposeColor(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (advanced) {
                // Every change keeps the tool's other fields: no default-value reset.
                PenDetailToggle(stringResource(R.string.caption_scale), scale) {
                    scale = it
                    host.updateToolConfig(tool, host.toolConfig(tool).copy(scale = it))
                }
                if (tool == Tool.PEN || tool == Tool.CALLIGRAPHY || tool == Tool.SPEED || tool == Tool.TAPER) {
                    PenDetailToggle(stringResource(R.string.caption_pressure), pressure) {
                        pressure = it
                        host.updateToolConfig(tool, host.toolConfig(tool).copy(pressureEnabled = it))
                    }
                    if (pressure) {
                        PenDetailSlider(stringResource(R.string.caption_sensitivity), sensitivity, 0f..100f) {
                            sensitivity = it
                            host.updateToolConfig(
                                tool, host.toolConfig(tool).copy(
                                    pressureMinFactor = ToolConversions.sensitivityToMinFactor(it.toDouble()),
                                ),
                            )
                        }
                    }
                }
                when (tool) {
                    Tool.CALLIGRAPHY ->
                        PenDetailSlider(stringResource(R.string.caption_multiplier), multiplier, 1f..5f) {
                            multiplier = it
                            host.updateToolConfig(tool, host.toolConfig(tool).copy(
                                directionStrength = ToolConversions.multiplierToDirectionStrength(it.toDouble()),
                            ))
                        }
                    Tool.SPEED ->
                        PenDetailSlider(stringResource(R.string.caption_speed), speed, 0f..100f) {
                            speed = it
                            host.updateToolConfig(tool, host.toolConfig(tool).copy(
                                speedStrength = ToolConversions.speedToStrength(it.toDouble()),
                            ))
                        }
                    Tool.TAPER ->
                        PenDetailSlider(stringResource(R.string.caption_tip_width), tipWidth, 0f..100f) {
                            tipWidth = it
                            host.updateToolConfig(tool, host.toolConfig(tool).copy(taperMinFactor = it.toDouble() / 100.0))
                        }
                    Tool.DASHED -> {
                        PenDetailSlider(stringResource(R.string.caption_dash), dash, 2f..40f) {
                            dash = it
                            host.updateToolConfig(tool, host.toolConfig(tool).copy(dashLength = it.toDouble()))
                        }
                        PenDetailSlider(stringResource(R.string.caption_gap), gap, 2f..40f) {
                            gap = it
                            host.updateToolConfig(tool, host.toolConfig(tool).copy(dashGap = it.toDouble()))
                        }
                    }
                    Tool.HIGHLIGHTER -> {
                        PenDetailSlider(stringResource(R.string.caption_intensity), highlighterIntensity, 10f..100f) {
                            highlighterIntensity = it
                            host.updateToolConfig(tool, host.toolConfig(tool).copy(
                                highlighterAlpha = ToolConversions.intensityToHighlighterAlpha(it.toDouble()),
                            ))
                        }
                        PenDetailToggle(stringResource(R.string.caption_straight_line), straight) {
                            straight = it
                            host.updateToolConfig(tool, host.toolConfig(tool).copy(straightLine = it))
                        }
                        PenDetailToggle(stringResource(R.string.caption_inverse), inverse) {
                            inverse = it
                            host.updateToolConfig(tool, host.toolConfig(tool).copy(highlighterInverse = it))
                        }
                    }
                    else -> Unit
                }
                if (tool != Tool.HIGHLIGHTER && tool != Tool.DASHED) {
                    PenDetailToggle(stringResource(R.string.caption_neon), neon) {
                        neon = it
                        host.updateToolConfig(tool, host.toolConfig(tool).copy(neon = it))
                    }
                    if (neon) {
                        PenDetailSlider(stringResource(R.string.caption_glow_intensity), neonIntensity, 0f..100f) {
                            neonIntensity = it
                            host.updateToolConfig(tool, host.toolConfig(tool).copy(
                                neonStrength = ToolConversions.intensityToNeonStrength(it.toDouble()),
                            ))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PenDetailToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, color = LocalPalette.current.text.toComposeColor(),
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PenDetailSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = LocalPalette.current.text.toComposeColor(),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value.roundToInt().toString(), color = LocalPalette.current.textDim.toComposeColor(),
                style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}
