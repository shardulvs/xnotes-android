package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.pal.FontFace
import com.xnotes.core.text.FlowDefaults
import com.xnotes.core.text.FlowMargins
import com.xnotes.platform.FontCatalog
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * The inline text tool's config popup (re-tap the armed tool): the flow's page
 * margins as millimetre spinfields, plus the document's default face, size and
 * colour (Auto = follow the theme). Changes apply immediately (live reflow) and
 * are not undoable, matching the page-style precedent. Like the styles popup,
 * the config can be saved as the default stamped onto new notes, or Reset.
 * Markdown shortcuts and Slash commands sit apart from all that: they are app
 * preferences, so neither Reset nor the new-note default touches them.
 */
@Composable
internal fun TextToolConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf(editor.flowConfigValue()) }
    // The new-note row shows once the config differs from the saved default and
    // stays for the popup session; an all-default config hides it (see StylesPopup).
    var showNewNoteRow by remember { mutableStateOf(editor.flowConfigValue() != editor.newNoteFlow) }

    fun apply(next: FlowDefaults) {
        config = next
        editor.setFlowConfig(next)
        if (next != editor.newNoteFlow) showNewNoteRow = true
    }

    ToolMenu(
        stringResource(R.string.tool_text), onDismiss,
        action = { PanelChip(stringResource(R.string.reset)) { apply(editor.factoryFlow) } },
    ) {
        MenuCard(buildList<MenuGroup> {
            add { MenuRow(stringResource(R.string.font)) { FaceDropdown(config.face) { apply(config.copy(face = it)) } } }
            add { MenuRow(stringResource(R.string.mono_font)) { FaceDropdown(config.monoFace, monoOnly = true) { apply(config.copy(monoFace = it)) } } }
            add {
                MenuRow(stringResource(R.string.size_pt)) {
                    SpinField(config.sizePt, min = 6.0, max = 96.0, Modifier.width(116.dp)) { apply(config.copy(sizePt = it)) }
                }
            }
            add {
                MenuRow(stringResource(R.string.colour)) {
                    ColorOrNone(stringResource(R.string.auto), config.color, editor.flowDefaultColor(), onChange = { apply(config.copy(color = it)) }) { d, p ->
                        ColorPickerPopup(config.color ?: editor.flowDefaultColor(), editor.recentColors, d, p)
                    }
                }
            }
        })
        PanelCard(padding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 14.dp)) {
            PanelCaption(stringResource(R.string.margins_mm))
            val m = config.margins
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MarginField(stringResource(R.string.edge_left), m.leftMm, Modifier.weight(1f)) { apply(config.copy(margins = m.copy(leftMm = it))) }
                MarginField(stringResource(R.string.edge_right), m.rightMm, Modifier.weight(1f)) { apply(config.copy(margins = m.copy(rightMm = it))) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MarginField(stringResource(R.string.edge_top), m.topMm, Modifier.weight(1f)) { apply(config.copy(margins = m.copy(topMm = it))) }
                MarginField(stringResource(R.string.edge_bottom), m.bottomMm, Modifier.weight(1f)) { apply(config.copy(margins = m.copy(bottomMm = it))) }
            }
        }
        if (showNewNoteRow && config != editor.factoryFlow) {
            MenuCard(buildList<MenuGroup> {
                add {
                    MenuSwitch(
                        stringResource(R.string.default_for_new_notes),
                        checked = editor.newNoteFlow != editor.factoryFlow && config == editor.newNoteFlow,
                    ) { on -> editor.saveNewNoteFlow(if (on) config else editor.factoryFlow) }
                }
            })
        }
        // App preferences, not document defaults: Reset and the new-note switch skip them.
        MenuCard(buildList<MenuGroup> {
            add { MenuSwitch(stringResource(R.string.markdown_shortcuts), editor.markdownInput) { editor.setMarkdownInputPref(it) } }
            add { MenuSwitch(stringResource(R.string.slash_commands), editor.slashCommands) { editor.setSlashCommandsPref(it) } }
        })
    }
}

/** A font as a field that opens the list of them. */
@Composable
internal fun FaceDropdown(current: FontFace, monoOnly: Boolean = false, onPick: (FontFace) -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .width(150.dp)
                .height(36.dp)
                .clip(shape)
                .background(palette.bg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), shape)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                fontLabel(current),
                color = palette.text.toComposeColor(),
                style = TextStyle(fontFamily = current.toComposeFamily(), fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FontMenuItems(current = current, monoOnly = monoOnly) {
                if (it != null) onPick(it)
                open = false
            }
        }
    }
}

/** One page margin: its edge's name over its millimetres. */
@Composable
private fun MarginField(label: String, mm: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    Column(modifier) {
        Text(
            label,
            color = LocalPalette.current.textDim.toComposeColor().copy(alpha = 0.8f),
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        SpinField(mm, FlowMargins.MIN_MM, FlowMargins.MAX_MM, Modifier.fillMaxWidth(), onChange)
    }
}

/** A whole number stepped by 1 inside [min]..[max]. */
@Composable
internal fun SpinField(value: Double, min: Double, max: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    MenuStepper(
        value.roundToInt().toString(), stringResource(R.string.decrease), stringResource(R.string.increase), modifier,
        canLess = value > min, canMore = value < max,
        onLess = { onChange((value - 1.0).coerceIn(min, max)) }, onMore = { onChange((value + 1.0).coerceIn(min, max)) },
    )
}
