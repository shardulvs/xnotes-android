package com.xnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/** Floating zoom pill: lock, the zoom (tap for saved zooms) and save. Long-press drags it, kept on screen. */
@Composable
fun BoxScope.ZoomBar(
    zoomPercent: Int,
    locked: Boolean,
    onToggleLock: () -> Unit,
    favorites: List<Float>,
    onPick: (Float) -> Unit,
    onToggleFavorite: (Float) -> Unit,
) {
    val palette = LocalPalette.current
    var offset by remember { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var savedOpen by remember { mutableStateOf(false) }
    val current = zoomPercent / 100f
    val saved = favorites.any { isSameZoom(it, current) }

    Box(Modifier.matchParentSize().padding(LocalToolbarCover.current).padding(12.dp).onSizeChanged { area = it }) {
        Surface(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress { change, drag ->
                        change.consume()
                        offset = Offset(
                            (offset.x + drag.x).coerceIn(-(area.width - size.width).coerceAtLeast(0).toFloat(), 0f),
                            (offset.y + drag.y).coerceIn(0f, (area.height - size.height).coerceAtLeast(0).toFloat()),
                        )
                    }
                },
            shape = MenuDefaults.shape,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
            border = BorderStroke(1.dp, palette.border.toComposeColor()),
        ) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(
                    if (locked) XnotesIcons.lock else XnotesIcons.unlock,
                    stringResource(if (locked) R.string.unlock_zoom else R.string.lock_zoom),
                    locked,
                    onToggleLock,
                )
                Box {
                    Text(
                        "$zoomPercent%",
                        color = palette.text.toComposeColor(),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable(enabled = favorites.isNotEmpty()) { savedOpen = true }
                            .widthIn(min = 44.dp)
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                    DropdownMenu(expanded = savedOpen && favorites.isNotEmpty(), onDismissRequest = { savedOpen = false }) {
                        Box(Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            SavedZoomChips(favorites, current, locked, { onPick(it); savedOpen = false }, onToggleFavorite)
                        }
                    }
                }
                BarButton(XnotesIcons.bookmark, stringResource(R.string.zoom_save, zoomPercent), saved) { onToggleFavorite(current) }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Icon(
        icon,
        contentDescription = description,
        tint = (if (active) palette.accent else palette.textDim).toComposeColor(),
        modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick).padding(8.dp).size(18.dp),
    )
}
