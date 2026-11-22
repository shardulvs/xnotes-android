package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageSizeChoice
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The page size menu: every named size and Custom, after an [unset] choice (picking null) when one is named. */
@Composable
internal fun SizeDropdown(size: PageSize?, unset: String? = null, onSelect: (PageSize?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    Box {
        Row(
            Modifier
                .height(36.dp)
                .clip(shape)
                .background(palette.bg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), shape)
                .clickable { expanded = true }
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(size?.let { pageSizeLabel(it) } ?: unset.orEmpty(), color = palette.text.toComposeColor(), fontSize = 14.sp)
            Icon(XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (unset != null) ChoiceMenuItem(size == null, onClick = { onSelect(null); expanded = false }) { fg -> Text(unset, color = fg) }
            PageSize.entries.forEach { s ->
                ChoiceMenuItem(s == size, onClick = { onSelect(s); expanded = false }) { fg -> Text(pageSizeLabel(s), color = fg) }
            }
        }
    }
}

/** A typed side of a custom page in millimetres, or null unless it parses inside the settable range. */
internal fun parseMm(raw: String): Double? =
    raw.trim().toDoubleOrNull()?.takeIf { it in PageSize.CUSTOM_MIN_MM..PageSize.CUSTOM_MAX_MM }

/** A side to 0.01 mm without trailing zeros, so "210" not "210.0", and px round-trip noise never shows. */
internal fun formatMm(v: Double): String =
    if (!v.isFinite()) v.toString()
    else java.math.BigDecimal.valueOf(Math.round(v * 100)).movePointLeft(2).stripTrailingZeros().toPlainString()

/** Page size…: a named size turned either way, or Custom's sides, for the pages it was opened on. */
@Composable
internal fun PageSizeDialog(initial: PageSizeChoice, onApply: (PageSizeChoice) -> Unit, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf(initial) }
    SheetDialog(
        title = stringResource(R.string.page_size),
        onDismiss = onDismiss,
        foot = { ConfirmButtons(stringResource(R.string.apply), onDismiss) { onApply(choice) } },
    ) {
        val c = choice
        MenuCard(buildList<MenuGroup> {
            add {
                MenuRow(stringResource(R.string.caption_size)) {
                    SizeDropdown(c.size) { size -> size?.let { choice = choice.copy(size = it) } }
                }
            }
            add {
                if (c.size == PageSize.CUSTOM) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SideField(stringResource(R.string.width_mm), c.customWidthMm, Modifier.weight(1f)) { mm -> choice = choice.copy(customWidthMm = mm) }
                        SideField(stringResource(R.string.height_mm), c.customHeightMm, Modifier.weight(1f)) { mm -> choice = choice.copy(customHeightMm = mm) }
                    }
                } else {
                    MenuSegmented(
                        listOf(stringResource(R.string.orientation_portrait), stringResource(R.string.orientation_landscape)),
                        if (c.orientation == Orientation.PORTRAIT) 0 else 1,
                    ) { choice = c.copy(orientation = if (it == 0) Orientation.PORTRAIT else Orientation.LANDSCAPE) }
                    Spacer(Modifier.height(10.dp))
                }
            }
        })
    }
}
