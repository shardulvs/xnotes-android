package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConversions
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Stroke-tool configuration popup (spec 10 §3): PRESSURE / SENSITIVITY, then the
 * tool's signature control — MULTIPLIER (calligraphy), SPEED (speed pen) or TAPER
 * (taper pen) — then WIDTH, and a NEON toggle (with INTENSITY) on any stroke tool but the highlighter.
 */
@Composable
fun ToolConfigPopup(editor: Editor, tool: Tool, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(tool) }
    var pressure by remember { mutableStateOf(base.pressureEnabled) }
    var sensitivity by remember { mutableStateOf(ToolConversions.minFactorToSensitivity(base.pressureMinFactor).toFloat()) }
    var multiplier by remember { mutableStateOf(ToolConversions.directionStrengthToMultiplier(base.directionStrength).toFloat()) }
    var speed by remember { mutableStateOf(ToolConversions.strengthToSpeed(base.speedStrength).toFloat()) }
    var taper by remember { mutableStateOf(ToolConversions.amountToTaper(base.taperAmount).toFloat()) }
    var width by remember { mutableStateOf(base.baseWidth.toFloat()) }
    var glow by remember { mutableStateOf(base.neon) }
    var glowIntensity by remember { mutableStateOf(ToolConversions.neonStrengthToIntensity(base.neonStrength).toFloat()) }

    fun emit() {
        val m = ToolConversions.sensitivityToMinFactor(sensitivity.toDouble())
        val ds = if (tool == Tool.CALLIGRAPHY) ToolConversions.multiplierToDirectionStrength(multiplier.toDouble()) else 0.0
        val sp = if (tool == Tool.SPEED) ToolConversions.speedToStrength(speed.toDouble()) else 0.0
        val tp = if (tool == Tool.TAPER) ToolConversions.taperToAmount(taper.toDouble()) else 0.0
        editor.updateToolConfig(
            tool,
            base.copy(
                baseWidth = width.toDouble(),
                pressureEnabled = pressure,
                pressureMinFactor = m,
                directionStrength = ds,
                speedStrength = sp,
                taperAmount = tp,
                neon = glow,
                neonStrength = ToolConversions.intensityToNeonStrength(glowIntensity.toDouble()),
            ),
        )
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(tool.name)
            val hasPressure = tool == Tool.PEN || tool == Tool.CALLIGRAPHY || tool == Tool.SPEED || tool == Tool.TAPER
            if (hasPressure) {
                ToggleRow("PRESSURE", pressure) { pressure = it; emit() }
                SliderRow("SENSITIVITY", sensitivity, 0f..100f, enabled = pressure) { sensitivity = it; emit() }
            }
            if (tool == Tool.CALLIGRAPHY) {
                SliderRow("MULTIPLIER", multiplier, 1f..5f) { multiplier = it; emit() }
            }
            if (tool == Tool.SPEED) {
                SliderRow("SPEED", speed, 0f..100f) { speed = it; emit() }
            }
            if (tool == Tool.TAPER) {
                SliderRow("TAPER", taper, 0f..100f) { taper = it; emit() }
            }
            val range = ToolConversions.widthRange(tool)
            SliderRow("WIDTH", width, range.start.toFloat()..range.endInclusive.toFloat()) { width = it; emit() }
            // Glow is offered on every stroke tool except the highlighter (a translucent marker).
            if (tool.isStroke && tool != Tool.HIGHLIGHTER) {
                ToggleRow("NEON", glow) { glow = it; emit() }
                if (glow) {
                    SliderRow("INTENSITY", glowIntensity, 0f..100f) { glowIntensity = it; emit() }
                }
            }
        }
    }
}

/** Shape-tool configuration popup (spec 10 §3 / 04 §6): kind picker, WIDTH, FILL. */
@Composable
fun ShapeConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(editor.shapeConfig.shape) }
    var width by remember { mutableStateOf(editor.shapeConfig.strokeWidth.toFloat()) }
    var fill by remember { mutableStateOf(editor.shapeConfig.fill) }
    var glow by remember { mutableStateOf(editor.shapeConfig.neon) }
    var glowIntensity by remember { mutableStateOf(ToolConversions.neonStrengthToIntensity(editor.shapeConfig.neonStrength).toFloat()) }

    fun emit() = editor.updateShapeConfig(
        ShapeConfig(kind, width.toDouble(), fill, glow, ToolConversions.intensityToNeonStrength(glowIntensity.toDouble())),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(260.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("SHAPE")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShapeKind.entries.forEach { k ->
                    KindChip(k.id.take(4), selected = kind == k) { kind = k; emit() }
                }
            }
            SliderRow("WIDTH", width, 1f..20f) { width = it; emit() }
            ToggleRow("FILL", fill) { fill = it; emit() }
            ToggleRow("GLOW", glow) { glow = it; emit() }
            if (glow) {
                SliderRow("INTENSITY", glowIntensity, 0f..100f) { glowIntensity = it; emit() }
            }
        }
    }
}

/** Colour switcher (spec 10 §4): a hue×shade matrix, a greyscale row and recent colours. */
@Composable
fun ColorSwitcherPopup(editor: Editor, index: Int, onDismiss: () -> Unit) {
    val hues = (0 until 11).map { it * 360.0 / 11.0 }
    val shades = listOf(0.4 to 1.0, 0.7 to 1.0, 1.0 to 1.0, 1.0 to 0.8, 1.0 to 0.6, 1.0 to 0.42)

    fun pick(c: Rgba) {
        editor.setSwatchColor(index, c)
        onDismiss()
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(10.dp)) {
            val recents = editor.recentColors
            if (recents.isNotEmpty()) {
                PopupTitle("RECENT")
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    recents.take(8).forEach { Cell(it) { pick(it) } }
                }
            }
            PopupTitle("COLOUR")
            shades.forEach { (s, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    hues.forEach { h -> val c = ColorMath.hsvToRgb(h, s, v); Cell(c) { pick(c) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (0..10).forEach { i ->
                    val g = (i * 255 / 10)
                    val c = Rgba(g, g, g)
                    Cell(c) { pick(c) }
                }
            }
        }
    }
}

@Composable
private fun Cell(color: Rgba, onClick: () -> Unit) {
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color.toComposeColor())
            .border(0.5.dp, LocalPalette.current.border.toComposeColor(), RoundedCornerShape(2.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun PopupTitle(text: String) {
    Text(
        text,
        color = LocalPalette.current.accent.toComposeColor(),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.width(220.dp)) {
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column {
        Text(
            "$label  ${"%.0f".format(value)}",
            color = (if (enabled) LocalPalette.current.text else LocalPalette.current.textDim).toComposeColor(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) palette.accentAlpha(48).toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}
