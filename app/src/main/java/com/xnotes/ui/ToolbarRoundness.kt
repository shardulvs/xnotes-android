package com.xnotes.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.settings.ToolbarSize

/** Use the thickest visible section: a three-row bar is not a single-row pill. */
internal fun toolbarCornerRows(layout: ToolbarLayout): Int =
    layout.visibleSections.maxOfOrNull { it.crossCells }?.coerceIn(1, 3) ?: 1

/** Limits are independent of orientation and never exceed a physically valid half-depth. */
internal fun toolbarCornerLimitDp(
    rows: Int, thicknessDp: Float, explicitLengthDp: Float = Float.POSITIVE_INFINITY,
): Float {
    val maxByRows = when {
        rows >= 3 -> 20f
        rows == 2 -> 26f
        else -> 40f
    }
    return minOf(maxByRows, (thicknessDp / 2f).coerceAtLeast(0f),
        (explicitLengthDp / 2f).coerceAtLeast(0f))
}

/** Null means follow the global theme, with automatic softening for multi-row bars.
 * User overrides may be sharper or rounder, but cannot exceed the bar's safe radius.
 * Docked bars default to square because they touch their containing edge. */
internal fun toolbarCornerRadiusDp(
    overrideDp: Int?, rows: Int, thicknessDp: Float, themeRadiusDp: Float,
    floating: Boolean, explicitLengthDp: Float = Float.POSITIVE_INFINITY,
): Float {
    val limit = toolbarCornerLimitDp(rows, thicknessDp, explicitLengthDp)
    val requested = overrideDp?.toFloat() ?: if (!floating) 0f else {
        themeRadiusDp * when {
            rows >= 3 -> 0.5f
            rows == 2 -> 0.68f
            else -> 1f
        }
    }
    return requested.coerceIn(0f, limit)
}

/** Extract the actual Material corner radius so global Settings > Corners
 * drives the automatic bar result, including live preference changes. */
@Composable
internal fun toolbarThemeCornerRadiusDp(): Float {
    val density = LocalDensity.current
    val theme = MaterialTheme.shapes.extraLarge
    val themePx = (theme.createOutline(Size(4096f, 4096f),
        androidx.compose.ui.unit.LayoutDirection.Ltr, density) as? Outline.Rounded)
        ?.roundRect?.topLeftCornerRadius?.x ?: with(density) { 28.dp.toPx() }
    return with(density) { themePx.toDp().value }
}

/** Shared shape for the live toolbar and the miniature editor preview. */
@Composable
internal fun toolbarSurfaceShape(
    geometry: ToolbarGeometry?, layout: ToolbarLayout, size: ToolbarSize,
    vertical: Boolean, floating: Boolean,
): Shape {
    val metrics = resolvedBarMetrics(size, geometry)
    val thickness = if (vertical) toolbarWidth(metrics, layout, geometry).value
        else toolbarHeight(metrics, layout, geometry).value
    val radius = toolbarCornerRadiusDp(geometry?.cornerRadiusDp,
        toolbarCornerRows(layout), thickness, toolbarThemeCornerRadiusDp(), floating,
        geometry?.lengthDp(vertical)?.toFloat() ?: Float.POSITIVE_INFINITY)
    return RoundedCornerShape(radius.dp)
}
