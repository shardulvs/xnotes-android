package com.xnotes.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import com.xnotes.R
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** N-Notes' stroke icon assets, shared by the launcher, gesture fan and toolbar preview. */
@Composable
internal fun PenSilhouette(item: ToolbarItem, modifier: Modifier = Modifier) {
    val icon = penStrokeIcon(item)
    Icon(
        imageVector = ImageVector.vectorResource(icon),
        contentDescription = null,
        modifier = modifier,
        tint = LocalPalette.current.text.toComposeColor(),
    )
}

/** The same N-Notes vector assets are used by the launcher and the settings picker. */
internal fun penStrokeIcon(item: ToolbarItem): Int =
    penStrokeIcon(Tool.fromId(item.id) ?: Tool.PEN)

/** Resolve the same resource for the existing tool-settings chips. */
internal fun penStrokeIcon(tool: Tool): Int = when (tool) {
    Tool.PEN -> R.drawable.ic_stroke_regular
    Tool.DASHED -> R.drawable.ic_stroke_dashed
    Tool.CALLIGRAPHY -> R.drawable.ic_stroke_calligraphy
    Tool.SPEED -> R.drawable.ic_stroke_speed
    Tool.TAPER -> R.drawable.ic_stroke_taper
    Tool.HIGHLIGHTER -> R.drawable.ic_stroke_highlighter
    else -> R.drawable.ic_stroke_regular
}
