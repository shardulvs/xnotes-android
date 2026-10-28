package com.xnotes.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageSizeChoice
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The page size menu: every named size and Custom, after an [unset] choice (picking null) when one is named. */
@Composable
internal fun SizeDropdown(size: PageSize?, unset: String? = null, onSelect: (PageSize?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Box {
        Box(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(size?.let { pageSizeLabel(it) } ?: unset.orEmpty(), color = palette.text.toComposeColor(), fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (unset != null) DropdownMenuItem(text = { Text(unset) }, onClick = { onSelect(null); expanded = false })
            PageSize.entries.forEach { s ->
                DropdownMenuItem(text = { Text(pageSizeLabel(s)) }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}

/** A typed side of a custom page in millimetres, or null unless it parses inside the settable range. */
internal fun parseMm(raw: String): Double? =
    raw.trim().toDoubleOrNull()?.takeIf { it in PageSize.CUSTOM_MIN_MM..PageSize.CUSTOM_MAX_MM }

/** Drop a whole number's ".0" so the field reads "210", not "210.0". */
internal fun formatMm(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toInt().toString() else v.toString()

/** Page size…: a named size turned either way, or Custom's sides, for the pages it was opened on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageSizeDialog(initial: PageSizeChoice, onApply: (PageSizeChoice) -> Unit, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SizeDropdown(choice.size) { size -> size?.let { choice = choice.copy(size = it) } }
                val c = choice
                if (c.size == PageSize.CUSTOM) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SideField(stringResource(R.string.width_mm), c.customWidthMm, Modifier.weight(1f)) { mm -> choice = choice.copy(customWidthMm = mm) }
                        SideField(stringResource(R.string.height_mm), c.customHeightMm, Modifier.weight(1f)) { mm -> choice = choice.copy(customHeightMm = mm) }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip(stringResource(R.string.orientation_portrait), c.orientation == Orientation.PORTRAIT) {
                            choice = c.copy(orientation = Orientation.PORTRAIT)
                        }
                        ModeChip(stringResource(R.string.orientation_landscape), c.orientation == Orientation.LANDSCAPE) {
                            choice = c.copy(orientation = Orientation.LANDSCAPE)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(choice) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
