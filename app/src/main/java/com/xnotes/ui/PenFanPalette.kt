package com.xnotes.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

@Composable
internal fun PenFanPalette(
    items: List<ToolbarItem>,
    direction: PenFanDirection,
    selected: Int,
    active: ToolbarItem,
    progress: Float,
    onItemPosition: (Int, Offset) -> Unit,
) {
    val horizontal = direction == PenFanDirection.DOWN || direction == PenFanDirection.UP
    val width = if (horizontal) 260.dp else 185.dp
    val height = if (horizontal) 185.dp else 260.dp
    val originX = if (horizontal) width / 2 else if (direction == PenFanDirection.RIGHT) 35.dp else width - 35.dp
    val originY = if (!horizontal) height / 2 else if (direction == PenFanDirection.DOWN) 35.dp else height - 35.dp
    val offsets = remember(items.size, direction) {
        List(items.size) { penFanOffset(it, items.size, direction, 110f) }
    }
    val palette = LocalPalette.current
    val surface = palette.panel.toComposeColor()
    val stroke = palette.border.toComposeColor()

    Box(Modifier.width(width).height(height)) {
        items.forEachIndexed { index, item ->
            // 110 dp radius provides clear spacing, even when the hovered circle grows.
            val displacement = offsets[index]
            val reveal = progress.coerceIn(0f, 1f)
            val x = originX + (displacement.x * reveal).dp - 23.dp
            val y = originY + (displacement.y * reveal).dp - 23.dp
            val hovered = index == selected
            val scale by animateFloatAsState(
                targetValue = if (hovered) 1.17f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "pen-fan-hover",
            )
            Box(
                Modifier
                    .offset(x = x, y = y)
                    .size(46.dp)
                    .onGloballyPositioned { coords ->
                        val center = Offset(coords.size.width / 2f, coords.size.height / 2f)
                        onItemPosition(index, coords.localToScreen(center))
                    }
                    .graphicsLayer {
                        alpha = reveal
                        scaleX = (0.65f + 0.35f * reveal) * scale
                        scaleY = (0.65f + 0.35f * reveal) * scale
                    }
                    .clip(CircleShape)
                    .background(if (hovered) palette.selectionBackground.toComposeColor() else surface)
                    .border(
                        if (hovered) 2.5.dp else 1.2.dp,
                        if (hovered || item == active) palette.accent.toComposeColor() else stroke,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                PenSilhouette(item, Modifier.width(18.dp).height(27.dp))
            }
        }
        if (selected in items.indices) {
            val name = stringResource(items[selected].labelRes)
            Box(Modifier.align(Alignment.Center).padding(4.dp), contentAlignment = Alignment.Center) {
                Text(name, color = palette.text.toComposeColor(), fontSize = 12.sp)
            }
        }
    }
}
