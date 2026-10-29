package com.xnotes.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.workspace.NoteTabs
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** A document switcher overlay. Expanding it never changes the editor's measured size. */
@Composable
fun FloatingDocumentTabs(
    tabs: NoteTabs,
    activeUri: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.extraLarge
    val active = tabs.tabs.firstOrNull { it.uri == activeUri } ?: tabs.tabs.firstOrNull()
    val title = active?.title?.replace(Regex("(?i)\\.(xnote|xcanvas)$"), "") ?: "Documents"
    val canvas = active?.uri?.substringBefore('?')?.endsWith(".xcanvas", true) == true
    val maxWidth = (LocalConfiguration.current.screenWidthDp.dp - 16.dp).coerceAtLeast(200.dp)
    Column(
        modifier.shadow(5.dp, shape).clip(shape)
            .background(palette.panel.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape)
            .animateContentSize(animationSpec = spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)),
    ) {
        if (expanded) {
            Box(Modifier.width(maxWidth).height(42.dp)) {
                NoteTabBar(tabs, activeUri,
                    onSelect = onSelect,
                    onClose = onClose,
                    modifier = Modifier.padding(end = 36.dp),
                    floatingSurface = true,
                )
                Box(
                    Modifier.align(Alignment.CenterEnd).size(36.dp)
                        .clickable { onExpandedChange(false) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Collapse document tabs",
                        modifier = Modifier.size(20.dp), tint = palette.textDim.toComposeColor())
                }
            }
        } else {
            Row(
                Modifier.clickable { onExpandedChange(true) }
                    .height(40.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (canvas) Icons.Outlined.Gesture else Icons.AutoMirrored.Outlined.Article,
                    contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = palette.text.toComposeColor(),
                )
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                    fontSize = 12.sp, color = palette.text.toComposeColor())
                if (tabs.tabs.size > 1) {
                    Text("${tabs.tabs.size}", fontSize = 11.sp, color = palette.textDim.toComposeColor())
                }
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Expand document tabs",
                    modifier = Modifier.size(18.dp), tint = palette.textDim.toComposeColor())
            }
        }
    }
}
