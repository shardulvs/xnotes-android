package com.xnotes.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.settings.CornerStyle

/**
 * Material's shape roles, every radius scaled by [style], used by how deep a thing sits: a floating
 * surface (menu, panel, dialog, bar) is large, a block on one (card, tile) medium, a control in a
 * block small, a mark extraSmall. extraLarge is large too, so nothing of material's own is rounder
 * than the app's surfaces. Paper, hairlines and circles keep their own corners.
 */
fun uiShapes(style: CornerStyle): Shapes {
    val k = style.scale
    return Shapes(
        extraSmall = RoundedCornerShape((4 * k).dp),
        small = RoundedCornerShape((8 * k).dp),
        medium = RoundedCornerShape((12 * k).dp),
        large = RoundedCornerShape((16 * k).dp),
        extraLarge = RoundedCornerShape((16 * k).dp),
    )
}

/** The shape that sits [gap] inside this one with its corners curving about the same centres. */
fun CornerBasedShape.nested(gap: Dp, density: Density): RoundedCornerShape {
    // The roles are fixed radii, so the size they are resolved against does not matter.
    val outer = topStart.toPx(Size.Unspecified, density)
    return RoundedCornerShape((outer - gap.value * density.density).coerceAtLeast(0f))
}
