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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.settings.Preferences
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
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Preferences", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { update(Preferences()) }) { Text("Reset to defaults") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
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
            }
            CheckRow("Open PDFs in dark mode (invert pages)", prefs.pdfDarkMode) { update(prefs.copy(pdfDarkMode = it)) }

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
                modifier = Modifier.fillMaxWidth(),
            )
            CheckRow("Page colour follows the theme", prefs.pageColor == null) {
                update(prefs.copy(pageColor = if (it) null else pageColorPresets.first()))
            }
            if (prefs.pageColor != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pageColorPresets.forEach { c ->
                        ColorDot(c.toComposeColor(), prefs.pageColor == c) { update(prefs.copy(pageColor = c)) }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalPalette.current.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = LocalPalette.current.accent.toComposeColor())
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
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor())
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(34.dp)
            .then(if (selected) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, palette.border.toComposeColor(), CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, color = LocalPalette.current.text.toComposeColor())
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
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(size.displayName, color = palette.text.toComposeColor())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PageSize.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.displayName) }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}
