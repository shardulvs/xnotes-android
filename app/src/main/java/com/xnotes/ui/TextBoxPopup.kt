package com.xnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.xnotes.R
import com.xnotes.core.model.BoxDefaults
import com.xnotes.core.model.BoxStyle
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The fill a box takes when one is first turned on: a sticky note's yellow. */
private val FIRST_FILL = Rgba(255, 241, 150, 255)

/** The thickness a border takes when its colour is picked while it has none. */
private const val FIRST_BORDER = 1.5

/**
 * A text box's frame: fill and border colour, each with None, then border thickness and corner
 * roundness. [onChange] hears every move; its flag is true while a slider is still being dragged.
 */
@Composable
internal fun BoxStyleControls(style: BoxStyle, recents: List<Rgba>, onChange: (BoxStyle, preview: Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.box_fill), color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(74.dp))
        ModeChip(stringResource(R.string.none), style.fill == null) { onChange(style.copy(fill = null), false) }
        Spacer(Modifier.width(8.dp))
        ColorPickerDot(
            style.fill,
            custom = style.fill != null,
            onPick = { onChange(style.copy(fill = it), false) },
            dismissOnPick = false,
        ) { d, p -> ColorPickerPopup(style.fill ?: FIRST_FILL, recents, d, p) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.box_border), color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(74.dp))
        ModeChip(stringResource(R.string.none), !style.outlined) { onChange(style.copy(border = null), false) }
        Spacer(Modifier.width(8.dp))
        ColorPickerDot(
            style.border,
            custom = style.outlined,
            // A colour with no thickness would show nothing, so the first pick brings a thin line.
            onPick = { onChange(style.copy(border = it, borderWidth = if (style.borderWidth > 0.0) style.borderWidth else FIRST_BORDER), false) },
            dismissOnPick = false,
        ) { d, p -> ColorPickerPopup(style.border ?: palette.text, recents, d, p) }
    }
    SliderRow(
        stringResource(R.string.box_thickness),
        style.borderWidth.toFloat(),
        0f..BoxStyle.MAX_BORDER.toFloat(),
        step = 0.5f,
        onChangeFinished = { onChange(style, false) },
    ) { onChange(style.copy(borderWidth = it.toDouble()), true) }
    SliderRow(
        stringResource(R.string.box_corners),
        style.corner.toFloat(),
        0f..BoxStyle.MAX_CORNER.toFloat(),
        step = 1f,
        onChangeFinished = { onChange(style, false) },
    ) { onChange(style.copy(corner = it.toDouble()), true) }
}

/**
 * The Text box tool's popup (re-tap the armed tool): what a new box starts as. Font, size and
 * text colour (Auto follows the theme), then its frame, which is off until set. App-wide, and
 * never changed by styling a box.
 */
@Composable
internal fun TextBoxConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val d = editor.boxDefaults
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.tool_text_box), color = palette.text.toComposeColor(), fontSize = 15.sp)
            Text(
                stringResource(R.string.box_defaults_hint),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.font), color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(74.dp))
                FaceDropdown(d.face) { editor.saveBoxDefaults(d.copy(face = it)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(stringResource(R.string.size_pt), color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(74.dp))
                SpinField(d.sizePt, min = 6.0, max = 96.0, Modifier.width(116.dp)) { editor.saveBoxDefaults(d.copy(sizePt = it)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(stringResource(R.string.colour), color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(74.dp))
                ModeChip(stringResource(R.string.auto), d.color == null) { editor.saveBoxDefaults(d.copy(color = null)) }
                Spacer(Modifier.width(8.dp))
                ColorPickerDot(
                    d.color,
                    custom = d.color != null,
                    onPick = { editor.saveBoxDefaults(d.copy(color = it)) },
                    dismissOnPick = false,
                ) { dismiss, pick -> ColorPickerPopup(d.color ?: palette.text, editor.recentColors, dismiss, pick) }
            }
            BoxStyleControls(d.box, editor.recentColors) { style, _ -> editor.saveBoxDefaults(d.copy(box = style)) }
            Spacer(Modifier.size(4.dp))
            Row(Modifier.align(Alignment.End)) {
                ModeChip(stringResource(R.string.reset), false) { editor.saveBoxDefaults(editor.factoryBox) }
            }
        }
    }
}

/** "Box style" on a selected text box: the same frame controls, applied live to that box. */
@Composable
internal fun BoxStylePopup(host: SelectionMenuHost, onDismiss: () -> Unit) {
    val box = host.selectedTextBox() ?: return
    var style by remember { mutableStateOf(box.box) }
    // Closing settles whatever a slider left as a preview.
    val close = {
        host.setSelectedBoxStyle(style, preview = false)
        onDismiss()
    }
    DropdownMenu(expanded = true, onDismissRequest = close, properties = PopupProperties(focusable = false)) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.box_style))
            BoxStyleControls(style, host.hostRecentColors) { next, preview ->
                style = next
                host.setSelectedBoxStyle(next, preview)
            }
        }
    }
}
