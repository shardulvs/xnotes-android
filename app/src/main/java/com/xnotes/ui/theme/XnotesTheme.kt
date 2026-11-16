package com.xnotes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.xnotes.core.model.Rgba
import com.xnotes.settings.CornerStyle

/** Convert a core [Rgba] to a Compose [Color]. */
fun Rgba.toComposeColor(): Color = Color(r, g, b, a)

val LocalPalette = staticCompositionLocalOf { Palette.DEFAULT }

/** Orange for warnings and conflicts; the same in every theme. */
val WarningColor = Color(255, 145, 60)

@Composable
fun XnotesTheme(palette: Palette, corners: CornerStyle = CornerStyle.ROUNDED, content: @Composable () -> Unit) {
    val shapes = remember(corners) { uiShapes(corners) }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = palette.composeColorScheme(), shapes = shapes, content = content)
    }
}

internal fun Palette.composeColorScheme(): ColorScheme {
    val palette = this
    val accent = palette.accent.toComposeColor()
    // The surfaceContainer* roles must come from the palette too: components read them
    // directly (menus draw surfaceContainer), and the darkColorScheme()/lightColorScheme()
    // baselines are purple-seeded constants that ignore the palette entirely.
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = accent,
            background = palette.bg.toComposeColor(),
            onBackground = palette.text.toComposeColor(),
            surface = palette.menuBg.toComposeColor(),
            onSurface = palette.text.toComposeColor(),
            surfaceVariant = palette.surface.toComposeColor(),
            onSurfaceVariant = palette.textDim.toComposeColor(),
            outline = palette.border.toComposeColor(),
            surfaceDim = palette.bg.toComposeColor(),
            surfaceBright = palette.surfaceHi.toComposeColor(),
            surfaceContainerLowest = palette.bg.toComposeColor(),
            surfaceContainerLow = palette.panel.toComposeColor(),
            surfaceContainer = palette.menuBg.toComposeColor(),
            surfaceContainerHigh = palette.surface.toComposeColor(),
            surfaceContainerHighest = palette.surfaceHi.toComposeColor(),
        )
    } else {
        lightColorScheme(
            primary = accent,
            background = palette.bg.toComposeColor(),
            onBackground = palette.text.toComposeColor(),
            surface = palette.menuBg.toComposeColor(),
            onSurface = palette.text.toComposeColor(),
            surfaceVariant = palette.surface.toComposeColor(),
            onSurfaceVariant = palette.textDim.toComposeColor(),
            outline = palette.border.toComposeColor(),
            surfaceDim = palette.surface.toComposeColor(),
            surfaceBright = palette.paper.toComposeColor(),
            surfaceContainerLowest = palette.paper.toComposeColor(),
            surfaceContainerLow = palette.menuBg.toComposeColor(),
            surfaceContainer = palette.menuBg.toComposeColor(),
            surfaceContainerHigh = palette.panel.toComposeColor(),
            surfaceContainerHighest = palette.bg.toComposeColor(),
        )
    }
    val m = materialColors
    return scheme.copy(
        onPrimary = m.onPrimary.toComposeColor(),
        primaryContainer = m.primaryContainer.toComposeColor(),
        onPrimaryContainer = m.onPrimaryContainer.toComposeColor(),
        inversePrimary = m.inversePrimary.toComposeColor(),
        secondary = m.secondary.toComposeColor(),
        onSecondary = m.onSecondary.toComposeColor(),
        secondaryContainer = m.secondaryContainer.toComposeColor(),
        onSecondaryContainer = m.onSecondaryContainer.toComposeColor(),
        tertiary = m.tertiary.toComposeColor(),
        onTertiary = m.onTertiary.toComposeColor(),
        tertiaryContainer = m.tertiaryContainer.toComposeColor(),
        onTertiaryContainer = m.onTertiaryContainer.toComposeColor(),
        surfaceTint = m.surfaceTint.toComposeColor(),
        inverseSurface = m.inverseSurface.toComposeColor(),
        inverseOnSurface = m.inverseOnSurface.toComposeColor(),
        error = m.error.toComposeColor(),
        onError = m.onError.toComposeColor(),
        errorContainer = m.errorContainer.toComposeColor(),
        onErrorContainer = m.onErrorContainer.toComposeColor(),
        // The scheme's own outline, not palette.border: Material draws switch thumbs and
        // unfocused text-field borders with it, and the muted hairline makes them invisible.
        outline = m.outline.toComposeColor(),
        outlineVariant = m.outlineVariant.toComposeColor(),
        scrim = m.scrim.toComposeColor(),
        primaryFixed = m.primaryFixed.toComposeColor(),
        primaryFixedDim = m.primaryFixedDim.toComposeColor(),
        onPrimaryFixed = m.onPrimaryFixed.toComposeColor(),
        onPrimaryFixedVariant = m.onPrimaryFixedVariant.toComposeColor(),
        secondaryFixed = m.secondaryFixed.toComposeColor(),
        secondaryFixedDim = m.secondaryFixedDim.toComposeColor(),
        onSecondaryFixed = m.onSecondaryFixed.toComposeColor(),
        onSecondaryFixedVariant = m.onSecondaryFixedVariant.toComposeColor(),
        tertiaryFixed = m.tertiaryFixed.toComposeColor(),
        tertiaryFixedDim = m.tertiaryFixedDim.toComposeColor(),
        onTertiaryFixed = m.onTertiaryFixed.toComposeColor(),
        onTertiaryFixedVariant = m.onTertiaryFixedVariant.toComposeColor(),
    )
}
