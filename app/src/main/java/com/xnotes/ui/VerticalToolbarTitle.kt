package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Vertical rails represent Title as a tool-sized icon with the full title in its
 * accessibility label / tooltip. XNote offers rename; XCanvas's title is read-only. */
@Composable
internal fun VerticalToolbarTitle(title: String, onClick: (() -> Unit)? = null) {
    if (onClick != null) {
        ToolbarIcon(XnotesIcons.file, title, onClick = onClick)
    } else {
        val metrics = LocalBar.current
        Tooltip(title) {
            Box(Modifier.size(metrics.button), contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.file, contentDescription = title,
                    modifier = Modifier.size(metrics.icon),
                    tint = LocalPalette.current.textDim.toComposeColor())
            }
        }
    }
}
