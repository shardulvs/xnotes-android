package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.settings.Preferences
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

private val accentPresets = listOf(
    Rgba(0, 230, 118), Rgba(255, 138, 30), Rgba(255, 77, 77), Rgba(255, 210, 30),
)
private val pageColorPresets = listOf(
    Rgba(22, 22, 22), Rgba(13, 13, 13), Rgba(255, 255, 255), Rgba(247, 243, 233), Rgba(232, 232, 232),
)
private val penButtonOptions = listOf("eraser" to "Eraser", "pan" to "Pan", "select" to "Select", "none" to "None")

/**
 * Preferences as a backstage pane (spec 10 §8). Edits apply live — each change is
 * pushed straight to the [Editor] (and persisted), so theme/page tweaks are seen
 * immediately, including in the surrounding backstage.
 */
@Composable
fun PreferencesPane(editor: Editor) {
    val palette = LocalPalette.current
    var prefs by remember { mutableStateOf(editor.preferences) }
    fun update(p: Preferences) {
        prefs = p
        editor.applyPreferences(p)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Preferences", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { update(Preferences()) }) { Text("Reset to defaults", fontSize = 13.sp) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("General")
            FieldLabel("UI theme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Dark", prefs.uiAppearance == "dark") { update(prefs.copy(uiAppearance = "dark")) }
                Chip("Light", prefs.uiAppearance == "light") { update(prefs.copy(uiAppearance = "light")) }
            }
            FieldLabel("Accent colour")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                accentPresets.forEach { c ->
                    ColorDot(c.toComposeColor(), prefs.accentColor == c) { update(prefs.copy(accentColor = c)) }
                }
                ColorPickerDot(
                    prefs.accentColor,
                    custom = prefs.accentColor !in accentPresets,
                    onPick = { update(prefs.copy(accentColor = it)) },
                ) { onDismiss, onPick -> AccentColorGridPopup(onDismiss, onPick) }
            }
            Column {
                CheckRow("Open PDFs in dark mode (invert pages)", prefs.pdfDarkMode) { update(prefs.copy(pdfDarkMode = it)) }
                if (prefs.pdfDarkMode) {
                    CheckRow("Don't invert images", prefs.pdfKeepImageColors) { update(prefs.copy(pdfKeepImageColors = it)) }
                }
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("Input")
            CheckRow("Draw with finger (off = finger pans)", prefs.fingerDraws) { update(prefs.copy(fingerDraws = it)) }
            FieldLabel("S Pen side button (hold)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                penButtonOptions.forEach { (id, label) ->
                    Chip(label, prefs.penButtonTool == id) { update(prefs.copy(penButtonTool = id)) }
                }
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("Page")
            FieldLabel("Default page size")
            SizeDropdown(prefs.defaultPageSize) { update(prefs.copy(defaultPageSize = it)) }
            FieldLabel("Orientation")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Portrait", prefs.defaultPageOrientation == Orientation.PORTRAIT) {
                    update(prefs.copy(defaultPageOrientation = Orientation.PORTRAIT))
                }
                Chip("Landscape", prefs.defaultPageOrientation == Orientation.LANDSCAPE) {
                    update(prefs.copy(defaultPageOrientation = Orientation.LANDSCAPE))
                }
            }
            FieldLabel("Side margin  ${prefs.sideMargin.toInt()} px")
            Slider(
                value = prefs.sideMargin.toFloat(),
                onValueChange = { update(prefs.copy(sideMargin = it.toDouble())) },
                valueRange = 0f..64f,
                modifier = Modifier.width(280.dp),
            )
            CheckRow("Page colour follows the theme", prefs.pageColor == null) {
                update(prefs.copy(pageColor = if (it) null else pageColorPresets.first()))
            }
            val pageColor = prefs.pageColor
            if (pageColor != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pageColorPresets.forEach { c ->
                        ColorDot(c.toComposeColor(), pageColor == c) { update(prefs.copy(pageColor = c)) }
                    }
                    ColorPickerDot(
                        pageColor,
                        custom = pageColor !in pageColorPresets,
                        onPick = { update(prefs.copy(pageColor = it)) },
                    ) { onDismiss, onPick -> PageColorGridPopup(onDismiss, onPick) }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalPalette.current.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = LocalPalette.current.accent.toComposeColor(), fontSize = 13.sp)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) palette.accentAlpha(48).toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(), fontSize = 14.sp)
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(30.dp)
            .then(if (selected) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, palette.border.toComposeColor(), CircleShape)
            .clickable(onClick = onClick),
    )
}

/** A spectrum wheel signals that this dot opens the full picker. */
private val spectrumBrush = Brush.sweepGradient(
    listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
    ),
)

/**
 * A spectrum dot that opens [grid], a popup of colour swatches — shared by the accent and
 * page-colour rows. Until a colour outside the row's presets is chosen the dot shows the
 * spectrum wheel; once one is, it fills with that colour and reads as selected.
 */
@Composable
private fun ColorPickerDot(
    current: Rgba,
    custom: Boolean,
    onPick: (Rgba) -> Unit,
    grid: @Composable (onDismiss: () -> Unit, onPick: (Rgba) -> Unit) -> Unit,
) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(30.dp)
                .then(if (custom) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
                .padding(4.dp)
                .clip(CircleShape)
                .then(if (custom) Modifier.background(current.toComposeColor()) else Modifier.background(spectrumBrush))
                .border(1.dp, palette.border.toComposeColor(), CircleShape)
                .clickable { open = true },
        )
        if (open) grid({ open = false }, { onPick(it); open = false })
    }
}

/** One tappable colour cell in a picker grid. */
@Composable
private fun Swatch(c: Rgba, onPick: (Rgba) -> Unit) {
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(c.toComposeColor())
            .border(0.5.dp, LocalPalette.current.border.toComposeColor(), RoundedCornerShape(2.dp))
            .clickable { onPick(c) },
    )
}

/** Picker grid restricted to bright, saturated hues — no greys, no washed-out tints. */
@Composable
private fun AccentColorGridPopup(onDismiss: () -> Unit, onPick: (Rgba) -> Unit) {
    val hues = (0 until 12).map { it * 360.0 / 12.0 }
    val shades = listOf(1.0 to 1.0, 1.0 to 0.82, 0.78 to 1.0)
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shades.forEach { (s, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    hues.forEach { h -> Swatch(ColorMath.hsvToRgb(h, s, v), onPick) }
                }
            }
        }
    }
}

/**
 * Page-colour picker: the full range rather than only vivid hues. A greyscale row (white
 * through black) sits above every hue drawn from pale tint to deep shade, so paper-like and
 * muted page backgrounds are reachable, not just the saturated ones.
 */
@Composable
private fun PageColorGridPopup(onDismiss: () -> Unit, onPick: (Rgba) -> Unit) {
    val hues = (0 until 12).map { it * 360.0 / 12.0 }
    val tones = listOf(0.25 to 1.0, 0.5 to 1.0, 0.85 to 1.0, 1.0 to 1.0, 1.0 to 0.7, 1.0 to 0.45)
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                hues.indices.forEach { i ->
                    Swatch(ColorMath.hsvToRgb(0.0, 0.0, 1.0 - i / (hues.size - 1.0)), onPick)
                }
            }
            tones.forEach { (s, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    hues.forEach { h -> Swatch(ColorMath.hsvToRgb(h, s, v), onPick) }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(2.dp))
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontSize = 14.sp)
    }
}

@Composable
private fun SizeDropdown(size: PageSize, onSelect: (PageSize) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(size.displayName, color = palette.text.toComposeColor(), fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PageSize.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.displayName) }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}
