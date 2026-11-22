package com.xnotes.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.BoxStyle
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The fill a box takes when one is first turned on: a sticky note's yellow. */
private val FIRST_FILL = Rgba(255, 241, 150, 255)

/** The thickness a border takes when its colour is picked while it has none. */
private const val FIRST_BORDER = 1.5

/**
 * A card of a text box's frame: fill and border colour, each with None, then border thickness and
 * corner roundness. [onChange] hears every move; its flag is true while a slider is still dragged.
 */
@Composable
internal fun BoxStyleCard(style: BoxStyle, recents: List<Rgba>, onChange: (BoxStyle, preview: Boolean) -> Unit) {
    val ink = LocalPalette.current.text
    val none = stringResource(R.string.none)
    MenuCard(buildList<MenuGroup> {
        add {
            MenuRow(stringResource(R.string.box_fill)) {
                ColorOrNone(none, style.fill, FIRST_FILL, onChange = { onChange(style.copy(fill = it), false) }) { d, p ->
                    ColorPickerPopup(style.fill ?: FIRST_FILL, recents, d, p)
                }
            }
        }
        add {
            MenuRow(stringResource(R.string.box_border)) {
                ColorOrNone(
                    none, style.border.takeIf { style.outlined }, style.border ?: ink,
                    // A colour with no thickness would show nothing, so the first pick brings a thin line.
                    onChange = { onChange(style.copy(border = it, borderWidth = if (it == null || style.borderWidth > 0.0) style.borderWidth else FIRST_BORDER), false) },
                ) { d, p -> ColorPickerPopup(style.border ?: ink, recents, d, p) }
            }
        }
        add {
            MenuSlider(
                stringResource(R.string.box_thickness), style.borderWidth.toFloat(), 0f..BoxStyle.MAX_BORDER.toFloat(),
                step = 0.5f, onChangeFinished = { onChange(style, false) },
            ) { onChange(style.copy(borderWidth = it.toDouble()), true) }
        }
        add {
            MenuSlider(
                stringResource(R.string.box_corners), style.corner.toFloat(), 0f..BoxStyle.MAX_CORNER.toFloat(),
                step = 1f, onChangeFinished = { onChange(style, false) },
            ) { onChange(style.copy(corner = it.toDouble()), true) }
        }
    })
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
    ToolMenu(
        stringResource(R.string.tool_text_box), onDismiss,
        action = { PanelChip(stringResource(R.string.reset)) { editor.saveBoxDefaults(editor.factoryBox) } },
    ) {
        Text(
            stringResource(R.string.box_defaults_hint),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
        )
        MenuCard(buildList<MenuGroup> {
            add { MenuRow(stringResource(R.string.font)) { FaceDropdown(d.face) { editor.saveBoxDefaults(d.copy(face = it)) } } }
            add {
                MenuRow(stringResource(R.string.size_pt)) {
                    SpinField(d.sizePt, min = 6.0, max = 96.0, Modifier.width(116.dp)) { editor.saveBoxDefaults(d.copy(sizePt = it)) }
                }
            }
            add {
                MenuRow(stringResource(R.string.colour)) {
                    ColorOrNone(stringResource(R.string.auto), d.color, palette.text, onChange = { editor.saveBoxDefaults(d.copy(color = it)) }) { dismiss, pick ->
                        ColorPickerPopup(d.color ?: palette.text, editor.recentColors, dismiss, pick)
                    }
                }
            }
        })
        BoxStyleCard(d.box, editor.recentColors) { style, _ -> editor.saveBoxDefaults(d.copy(box = style)) }
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
    ToolMenu(stringResource(R.string.box_style), close, focusable = false) {
        BoxStyleCard(style, host.hostRecentColors) { next, preview ->
            style = next
            host.setSelectedBoxStyle(next, preview)
        }
    }
}
