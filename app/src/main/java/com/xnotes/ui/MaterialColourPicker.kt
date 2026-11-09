package com.xnotes.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.settings.MaterialColourMode
import com.xnotes.settings.MaterialStyle
import com.xnotes.settings.Preferences
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

private data class ColourPreset(@param:StringRes val name: Int, val accent: Rgba, val surface: Rgba? = null)
private fun rgb(value: Int) = Rgba.fromArgb(value or (0xff shl 24))

private val singleColourSize = 32.dp
private val singleColourSpacing = 4.dp

private val singleTonePresets = listOf(
    ColourPreset(R.string.hue_red, Preferences.DEFAULT_MATERIAL_SINGLE),
    ColourPreset(R.string.hue_pink, rgb(0xe91e63)),
    ColourPreset(R.string.hue_purple, rgb(0x9c27b0)),
    ColourPreset(R.string.material_indigo, rgb(0x3f51b5)),
    ColourPreset(R.string.hue_blue, rgb(0x2196f3)),
    ColourPreset(R.string.hue_teal, rgb(0x009688)),
    ColourPreset(R.string.hue_green, rgb(0x4caf50)),
    ColourPreset(R.string.material_amber, rgb(0xffc107)),
)

// Row one wears the launcher icons' colours; then Base2Tone D3 accents and B3 surfaces (assets/licenses/base2tone.txt).
private val dualTonePresets = listOf(
    ColourPreset(R.string.material_bubblegum, Preferences.DEFAULT_MATERIAL_DUAL, Preferences.DEFAULT_MATERIAL_SURFACE),
    ColourPreset(R.string.material_rose, rgb(0xff6b8e), rgb(0xf5f0e4)),
    ColourPreset(R.string.material_lime, rgb(0xc8f55a), rgb(0x7a5cff)),
    ColourPreset(R.string.material_aqua, rgb(0x7df4ea), rgb(0x2fa6b3)),
    ColourPreset(R.string.material_morning, rgb(0x9a7c42), rgb(0x3d75e6)),
    ColourPreset(R.string.material_evening, rgb(0xffa142), rgb(0x9a86fd)),
    ColourPreset(R.string.material_sea, rgb(0x0db57d), rgb(0x57718e)),
    ColourPreset(R.string.material_forest, rgb(0xb1c44f), rgb(0x687d68)),
    ColourPreset(R.string.material_earth, rgb(0xcda956), rgb(0x88786d)),
    ColourPreset(R.string.material_lavender, rgb(0xca80ff), rgb(0xa286fd)),
    ColourPreset(R.string.material_lake, rgb(0xc4b031), rgb(0x499fbc)),
    ColourPreset(R.string.material_desert, rgb(0xe58748), rgb(0x957e50)),
)

@Composable
internal fun MaterialColourPicker(prefs: Preferences, update: (Preferences) -> Unit) {
    val palette = LocalPalette.current
    val dual = prefs.materialMode == MaterialColourMode.DUAL
    val accent = if (dual) prefs.materialDualSeed else prefs.materialSingleSeed
    val coloured = prefs.materialStyle != MaterialStyle.MONOCHROME
    val tinted = coloured && prefs.materialStyle != MaterialStyle.RAINBOW
    val presets = if (dual) dualTonePresets else singleTonePresets
    val selected = presets.firstOrNull { it.accent == accent && (!dual || it.surface == prefs.materialSurfaceSeed) }
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dual) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.material_colour_presets), color = palette.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.weight(1f))
                val name = selected?.name ?: R.string.material_custom
                Text(stringResource(name), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = when { maxWidth >= 480.dp -> 4; maxWidth >= 360.dp -> 3; else -> 2 }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { preset ->
                                PresetChoice(preset, preset == selected, coloured, Modifier.weight(1f)) {
                                    update(prefs.copy(materialDualSeed = preset.accent, materialSurfaceSeed = requireNotNull(preset.surface)))
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(singleColourSpacing)) {
                presets.forEach { preset ->
                    SingleColourChoice(preset, preset == selected, coloured) {
                        update(prefs.copy(materialSingleSeed = preset.accent))
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val accentField: @Composable (Modifier) -> Unit = { modifier ->
                CustomColourField(
                    stringResource(R.string.pref_accent_colour), accent, coloured, modifier, compact = !dual,
                ) { update(if (dual) prefs.copy(materialDualSeed = it) else prefs.copy(materialSingleSeed = it)) }
            }
            val surfaceField: @Composable (Modifier) -> Unit = { modifier ->
                CustomColourField(
                    stringResource(R.string.material_surface_colour), prefs.materialSurfaceSeed, tinted, modifier,
                ) { update(prefs.copy(materialSurfaceSeed = it)) }
            }
            if (dual && maxWidth >= 400.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accentField(Modifier.weight(1f))
                    surfaceField(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val singleWidth = singleColourSize * singleTonePresets.size + singleColourSpacing * (singleTonePresets.size - 1)
                    accentField(if (dual) Modifier.fillMaxWidth() else Modifier.widthIn(max = singleWidth).fillMaxWidth())
                    if (dual) surfaceField(Modifier.fillMaxWidth())
                }
            }
        }
        if (dual && !tinted) {
            Text(stringResource(R.string.material_untinted_surfaces), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SingleColourChoice(preset: ColourPreset, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val name = stringResource(preset.name)
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier.size(singleColourSize).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .border(2.dp, if (selected) palette.accent.toComposeColor() else Color.Transparent, shape)
            .semantics { contentDescription = name }
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp),
    ) {
        Box(Modifier.size(24.dp).clip(MaterialTheme.shapes.extraSmall).background(preset.accent.toComposeColor()))
    }
}

@Composable
private fun PresetChoice(preset: ColourPreset, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    val foreground = if (selected) palette.selectionForeground else palette.text
    Column(
        modifier.alpha(if (enabled) 1f else 0.4f).clip(shape)
            .background((if (selected) palette.selectionBackground else palette.surface).toComposeColor())
            .border(1.dp, (if (selected) palette.accent else palette.border).toComposeColor(), shape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(16.dp).clip(MaterialTheme.shapes.extraSmall), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.weight(1f).height(16.dp).background(preset.accent.toComposeColor()))
            preset.surface?.let { Box(Modifier.weight(1f).height(16.dp).background(it.toComposeColor())) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(preset.name), color = foreground.toComposeColor(), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (selected) Icon(XnotesIcons.check, null, tint = foreground.toComposeColor(), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun CustomColourField(
    label: String, colour: Rgba, enabled: Boolean, modifier: Modifier, compact: Boolean = false, onPick: (Rgba) -> Unit,
) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).clip(shape)
                .background(palette.surface.toComposeColor()).border(1.dp, palette.border.toComposeColor(), shape)
                .clickable(enabled = enabled, role = Role.Button) { open = true }
                .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(if (compact) 20.dp else 24.dp).clip(MaterialTheme.shapes.extraSmall).background(colour.toComposeColor()))
            if (compact) {
                Text(label, color = palette.text.toComposeColor(), fontSize = 12.sp, modifier = Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, color = palette.text.toComposeColor(), fontSize = 12.sp)
                    Text(
                        Rgba.toHex(colour).uppercase(),
                        color = palette.textDim.toComposeColor(), fontSize = 12.sp,
                    )
                }
            }
            Icon(XnotesIcons.palette, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
        }
        if (open && enabled) ColorPickerPopup(colour, emptyList(), { open = false }, onPick)
    }
}
