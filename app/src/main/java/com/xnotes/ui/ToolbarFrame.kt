package com.xnotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarSize
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The toolbar look from Preferences, provided once above both panes. */
val LocalToolbarLook = staticCompositionLocalOf { ToolbarLook() }

/** What every toolbar widget sizes itself by, and which way the bar runs. */
internal data class BarMetrics(
    val button: Dp,
    val circle: Dp,
    val icon: Dp,
    val swatch: Dp,
    val rule: Dp,
    val label: TextUnit,
    val vertical: Boolean = false,
) {
    /** Across the bar: one button with a little air either side. */
    val thickness: Dp get() = button + 8.dp
}

internal fun barMetrics(size: ToolbarSize): BarMetrics = when (size) {
    ToolbarSize.COMPACT -> BarMetrics(36.dp, 30.dp, 20.dp, 24.dp, 22.dp, 11.sp)
    ToolbarSize.REGULAR -> BarMetrics(42.dp, 34.dp, 22.dp, 28.dp, 26.dp, 12.sp)
    ToolbarSize.COMFORTABLE -> BarMetrics(50.dp, 40.dp, 26.dp, 34.dp, 30.dp, 13.sp)
}

internal val LocalBar = staticCompositionLocalOf { barMetrics(ToolbarSize.REGULAR) }

/** Where a menu opens from its anchor; a side rail sets it so its menus open beside the rail. */
internal val LocalMenuOffset = compositionLocalOf { DpOffset.Zero }

/** How far a floating bar sits in from the canvas edge. */
private val FLOAT_MARGIN = 8.dp

/** Optional document navigation slot supplied by the workspace, without coupling toolbar tools to tabs. */
internal class ToolbarTabExtension(
    val expanded: Boolean,
    val onToggle: () -> Unit,
    val content: @Composable () -> Unit,
    val showToggle: Boolean = true,
)

internal val LocalToolbarTabExtension = compositionLocalOf<ToolbarTabExtension?> { null }
internal val LocalGroupedToolbarSurface = compositionLocalOf { false }

/** What a floating bar covers of the canvas, for overlays that must stay out from under it. */
internal val LocalToolbarCover = compositionLocalOf { PaddingValues(0.dp) }

/**
 * Lays a pane out with its [bar] along the edge Preferences chose and [content] in the rest. A
 * floating bar is handed to [content] to lay over its canvas instead, and [onCover] learns how many
 * px of each edge (left, top, right, bottom) it covers so the canvas keeps its pages clear of it.
 */
@Composable
internal fun ColumnScope.ToolbarAround(
    bar: @Composable () -> Unit,
    onCover: (Double, Double, Double, Double) -> Unit,
    tabExtension: ToolbarTabExtension? = null,
    content: @Composable (floatingBar: @Composable BoxScope.() -> Unit) -> Unit,
) {
    val look = LocalToolbarLook.current
    val rest = Modifier.weight(1f).fillMaxWidth()
    if (!look.floating) {
        SideEffect { onCover(0.0, 0.0, 0.0, 0.0) }
        val docked: @Composable BoxScope.() -> Unit = {}
        // Left and right are the screen's, as the canvas insets are, whatever the language.
        val across = Arrangement.Absolute.Left
        when (look.position) {
            ToolbarPosition.TOP -> { bar(); Box(rest) { content(docked) } }
            ToolbarPosition.BOTTOM -> { Box(rest) { content(docked) }; bar() }
            ToolbarPosition.LEFT -> Row(rest, across) { bar(); Box(Modifier.weight(1f).fillMaxHeight()) { content(docked) } }
            ToolbarPosition.RIGHT -> Row(rest, across) { Box(Modifier.weight(1f).fillMaxHeight()) { content(docked) }; bar() }
        }
        return
    }
    val depth = barMetrics(look.size).thickness + FLOAT_MARGIN
    val px = with(LocalDensity.current) { depth.toPx().toDouble() }
    val position = look.position
    SideEffect {
        onCover(
            if (position == ToolbarPosition.LEFT) px else 0.0,
            if (position == ToolbarPosition.TOP) px else 0.0,
            if (position == ToolbarPosition.RIGHT) px else 0.0,
            if (position == ToolbarPosition.BOTTOM) px else 0.0,
        )
    }
    val (cover, edge) = when (position) {
        ToolbarPosition.TOP -> PaddingValues(top = depth) to Alignment.TopCenter
        ToolbarPosition.BOTTOM -> PaddingValues(bottom = depth) to Alignment.BottomCenter
        ToolbarPosition.LEFT -> PaddingValues.Absolute(left = depth) to AbsoluteAlignment.CenterLeft
        ToolbarPosition.RIGHT -> PaddingValues.Absolute(right = depth) to AbsoluteAlignment.CenterRight
    }
    CompositionLocalProvider(LocalToolbarCover provides cover) {
        BoxWithConstraints(rest) {
            val availableTabWidth = (maxWidth - FLOAT_MARGIN * 2).coerceAtLeast(120.dp)
            content {
                if (position == ToolbarPosition.TOP && tabExtension != null) {
                    val shape = MaterialTheme.shapes.extraLarge
                    val palette = LocalPalette.current
                    Box(Modifier.align(edge).padding(FLOAT_MARGIN)) {
                        Column(
                            Modifier.shadow(6.dp, shape).clip(shape)
                                .background(palette.panel.toComposeColor())
                                .border(1.dp, palette.border.toComposeColor(), shape),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AnimatedVisibility(
                                visible = tabExtension.expanded,
                                enter = expandVertically(animationSpec = tween(200)) + fadeIn(tween(150)),
                                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
                            ) {
                                Box(Modifier.width(availableTabWidth)) { tabExtension.content() }
                            }
                            CompositionLocalProvider(
                                LocalToolbarTabExtension provides tabExtension,
                                LocalGroupedToolbarSurface provides true,
                            ) { bar() }
                        }
                    }
                } else {
                    Box(Modifier.align(edge).padding(FLOAT_MARGIN)) { bar() }
                }
            }
        }
    }
}

/**
 * The strip both toolbars are laid out in: a row along the top or bottom, a column down either
 * side. [content] runs along it and scrolls when it runs long, [trailing] stays pinned at the end,
 * and [armed] is the tool the gliding circle sits under.
 */
@Composable
internal fun ToolbarFrame(
    armed: Any?,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val look = LocalToolbarLook.current
    val bar = barMetrics(look.size).copy(vertical = look.position.vertical)
    val glide = remember { ToolGlide() }
    val palette = LocalPalette.current
    val tabExtension = LocalToolbarTabExtension.current
    val groupedSurface = LocalGroupedToolbarSurface.current
    val shape = MaterialTheme.shapes.extraLarge
    // The grouped floating host paints a single shared surface around tabs and tools.
    val surface = if (groupedSurface) {
        Modifier
    } else if (!look.floating) {
        Modifier.background(palette.panel.toComposeColor())
    } else {
        Modifier
            .shadow(6.dp, shape)
            .clip(shape)
            .background(palette.panel.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape)
    }
    val stretch = when {
        look.floating -> Modifier
        bar.vertical -> Modifier.fillMaxHeight()
        else -> Modifier.fillMaxWidth()
    }
    // Material places a menu below its anchor; shifting it one bar across and one button up puts it
    // beside the rail, and on a right rail material mirrors it to the left side on its own.
    val menuOffset = if (bar.vertical) DpOffset(bar.thickness, -bar.button) else DpOffset.Zero
    val tipSide = when (look.position) {
        ToolbarPosition.TOP -> TipSide.BELOW
        ToolbarPosition.BOTTOM -> TipSide.ABOVE
        ToolbarPosition.LEFT -> TipSide.RIGHT
        ToolbarPosition.RIGHT -> TipSide.LEFT
    }
    CompositionLocalProvider(LocalToolGlide provides glide, LocalBar provides bar, LocalMenuOffset provides menuOffset, LocalTipSide provides tipSide) {
        if (bar.vertical) {
            Column(
                stretch.width(bar.thickness).then(surface),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .weight(1f, fill = !look.floating)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                        .toolGlide(glide, armed),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { content() }
                trailing?.invoke()
            }
        } else {
            Row(
                stretch.height(bar.thickness).then(surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f, fill = !look.floating)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp)
                        .toolGlide(glide, armed),
                    verticalAlignment = Alignment.CenterVertically,
                ) { content() }
                trailing?.invoke()
                if (tabExtension?.showToggle == true) {
                    IconButton(onClick = tabExtension.onToggle, modifier = Modifier.size(bar.button)) {
                        Icon(
                            if (tabExtension.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (tabExtension.expanded) "Hide document tabs" else "Show document tabs",
                            tint = palette.text.toComposeColor(),
                        )
                    }
                }
            }
        }
    }
}
