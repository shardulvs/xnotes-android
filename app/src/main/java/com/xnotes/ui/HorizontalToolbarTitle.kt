package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Share one visual treatment for the horizontal XNote, XCanvas and preview title.
 * The paged editor can rename; a canvas title is intentionally read-only.
 * A bounded label prevents a long filename from consuming the toolbar's tools. */
@Composable
internal fun HorizontalToolbarTitle(
    title: String,
    onClick: (() -> Unit)? = null,
    tooltip: String = title,
) {
    Tooltip(tooltip) {
        Label(
            title,
            Modifier.widthIn(max = 160.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .testTag("toolbar-horizontal-title"),
        )
    }
}
