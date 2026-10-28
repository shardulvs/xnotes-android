package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
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
import com.xnotes.R
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.settings.SwatchRole
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * The infinite canvas's background styles: pattern, spacing, pattern colour and paper colour.
 *
 * Unlike the paged [StylesPopup] there is no inheritance to express, because a canvas has no page
 * level under it, so every control sets a real value rather than choosing between "default" and an
 * override. Everything here is per canvas and saved with it, apart from the "Default for new
 * canvases" checkbox, which stamps the current background onto every canvas made from then on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanvasStylesPopup(editor: InfiniteEditor, onDismiss: () -> Unit) {
    var background by remember { mutableStateOf(editor.document.background) }
    // Mirrors the paged StylesPopup: the row shows once the background differs from the saved
    // new-canvas default and stays for the popup session, and a stock background hides it.
    var showNewCanvasRow by remember { mutableStateOf(editor.document.background != editor.newCanvasBackground) }

    fun apply(next: CanvasBackground) {
        background = next
        editor.setBackground(next)
        if (next != editor.newCanvasBackground) showNewCanvasRow = true
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(286.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_styles))

            StyleCaption(stringResource(R.string.caption_pattern))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModeChip(stringResource(R.string.none), background.pattern == PagePattern.NONE) {
                    apply(background.copy(pattern = PagePattern.NONE))
                }
                ModeChip(stringResource(R.string.pattern_lines), background.pattern == PagePattern.LINES) {
                    apply(background.copy(pattern = PagePattern.LINES))
                }
                ModeChip(stringResource(R.string.pattern_dots), background.pattern == PagePattern.DOTS) {
                    apply(background.copy(pattern = PagePattern.DOTS))
                }
                ModeChip(stringResource(R.string.pattern_grid), background.pattern == PagePattern.GRID) {
                    apply(background.copy(pattern = PagePattern.GRID))
                }
            }

            Spacer(Modifier.size(12.dp))
            SliderRow(
                stringResource(R.string.caption_spacing),
                background.clampedSpacing.toFloat(),
                PageStyle.MIN_SPACING.toFloat()..PageStyle.MAX_SPACING.toFloat(),
                enabled = background.pattern != PagePattern.NONE,
            ) { apply(background.copy(spacing = it.toDouble())) }

            Spacer(Modifier.size(8.dp))
            StyleCaption(stringResource(R.string.caption_pattern_colour))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The hue at full strength; opacity is its own control.
                SwatchCircles(editor.swatchStore, SwatchRole.LINES, background.patternColor.copy(a = 255)) {
                    apply(background.copy(patternColor = it.copy(a = background.patternColor.a)))
                }
            }
            SliderRow(
                stringResource(R.string.caption_opacity),
                background.patternColor.a / 255f * 100f,
                5f..100f,
                enabled = background.pattern != PagePattern.NONE,
            ) { pct ->
                val alpha = (pct / 100f * 255f).roundToInt().coerceIn(0, 255)
                apply(background.copy(patternColor = background.patternColor.copy(a = alpha)))
            }

            Spacer(Modifier.size(8.dp))
            StyleCaption(stringResource(R.string.caption_paper))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeChip(stringResource(R.string.paper_theme), background.paperColor == null) { apply(background.copy(paperColor = null)) }
                SwatchCircles(editor.swatchStore, SwatchRole.PAPER, background.paperColor) {
                    apply(background.copy(paperColor = it))
                }
            }

            Spacer(Modifier.size(8.dp))
            if (showNewCanvasRow && background != CanvasBackground()) {
                // The checkbox's 48dp touch frame insets the drawn box; pull the row back to align it.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().offset(x = (-14).dp)) {
                    Checkbox(
                        checked = background == editor.newCanvasBackground,
                        onCheckedChange = { on -> editor.saveNewCanvasBackground(if (on) background else null) },
                    )
                    Text(
                        stringResource(R.string.default_for_new_canvases),
                        color = LocalPalette.current.text.toComposeColor(),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.size(4.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.reset), selected = false) { apply(CanvasBackground()) }
            }
        }
    }
}
