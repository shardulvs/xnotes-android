package com.xnotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.tools.SplitToolbarInterface
import com.xnotes.settings.Preferences
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** A separate, collapsible group in Preferences; the same controls are available in the note. */
@Composable
internal fun SplitToolbarPreferences(preferences: Preferences, onChange: (Preferences) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp)) {
            Text(stringResource(R.string.split_settings), Modifier.weight(1f),
                color = palette.text.toComposeColor(), style = MaterialTheme.typography.titleSmall)
            Text(if (expanded) "−" else "+", color = palette.textDim.toComposeColor())
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp).widthIn(max = 520.dp)) {
                SplitToolbarSettings(preferences, mixed = true, onChange = onChange)
            }
        }
    }
}

/** Compact choice cards make the scope of shared tools visible before applying the preference. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SplitToolbarSettings(preferences: Preferences, mixed: Boolean, onChange: (Preferences) -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (shared in listOf(true, false)) {
                val selected = preferences.sharedSplitToolbars == shared
                val shape = RoundedCornerShape(10.dp)
                Column(Modifier.weight(1f).clip(shape)
                    .background((if (selected) palette.selectionBackground else palette.panel).toComposeColor())
                    .border(1.dp, (if (selected) palette.accent else palette.border).toComposeColor(), shape)
                    .selectable(selected, role = Role.RadioButton,
                        onClick = { onChange(preferences.copy(sharedSplitToolbars = shared)) }).padding(10.dp)) {
                    val ink = palette.accent.toComposeColor()
                    val paper = palette.bg.toComposeColor()
                    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                        val gap = 4.dp.toPx()
                        val half = (size.width - gap) / 2
                        drawRect(paper, size = Size(half, size.height))
                        drawRect(paper, Offset(half + gap, 0f), Size(half, size.height))
                        val height = 6.dp.toPx()
                        if (shared) drawRect(ink, size = Size(size.width, height))
                        else {
                            drawRect(ink, size = Size(half, height))
                            drawRect(ink, Offset(half + gap, 0f), Size(half, height))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(if (shared) R.string.split_shared else R.string.split_per_pane),
                        color = (if (selected) palette.selectionForeground else palette.text).toComposeColor(),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text(stringResource(if (preferences.sharedSplitToolbars) R.string.split_shared_help else R.string.split_per_pane_help),
            color = palette.textDim.toComposeColor(), style = MaterialTheme.typography.bodySmall)
        AnimatedVisibility(preferences.sharedSplitToolbars && mixed) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.split_interface_title), color = palette.text.toComposeColor(),
                    style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in SplitToolbarInterface.entries) {
                        FilterChip(selected = preferences.mixedSplitToolbar == choice,
                            onClick = { onChange(preferences.copy(mixedSplitToolbar = choice)) },
                            label = { Text(stringResource(if (choice == SplitToolbarInterface.CANVAS)
                                R.string.split_interface_canvas else R.string.split_interface_note)) })
                    }
                }
                Text(stringResource(R.string.split_mixed_help), color = palette.textDim.toComposeColor(),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
