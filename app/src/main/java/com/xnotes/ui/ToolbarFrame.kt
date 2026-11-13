package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarDockAlignment
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarLayout
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
    val padding: Dp = 4.dp,
    val spacing: Dp = 2.dp,
) {
    /** Across the bar: a button with configurable breathing room on each side. */
    val thickness: Dp get() = button + padding * 2
}

internal fun barMetrics(size: ToolbarSize): BarMetrics = when (size) {
    ToolbarSize.COMPACT -> BarMetrics(36.dp, 30.dp, 20.dp, 24.dp, 22.dp, 11.sp)
    ToolbarSize.REGULAR -> BarMetrics(42.dp, 34.dp, 22.dp, 28.dp, 26.dp, 12.sp)
    ToolbarSize.COMFORTABLE -> BarMetrics(50.dp, 40.dp, 26.dp, 34.dp, 30.dp, 13.sp)
}

/** Shared geometry for real toolbars and the miniature editor preview. */
internal fun resolvedBarMetrics(size: ToolbarSize, geometry: ToolbarGeometry?): BarMetrics {
    val base = barMetrics(size)
    val button = geometry?.elementSizeDp?.dp ?: base.button
    val factor = button.value / base.button.value
    return base.copy(
        button = button,
        circle = base.circle * factor,
        icon = base.icon * factor,
        swatch = base.swatch * factor,
        rule = base.rule * factor,
        label = (base.label.value * factor).sp,
        padding = geometry?.paddingDp?.dp ?: base.padding,
        spacing = geometry?.spacingDp?.dp ?: base.spacing,
    )
}

internal fun entryBarMetrics(base: BarMetrics, entry: ToolbarEntry): BarMetrics {
    if (entry.item == com.xnotes.core.tools.ToolbarItem.SPACER) return base
    val size = entry.sizeDp?.dp ?: return base
    val factor = size.value / base.button.value
    return base.copy(
        button = size,
        circle = base.circle * factor,
        icon = base.icon * factor,
        swatch = base.swatch * factor,
        rule = base.rule * factor,
        label = (base.label.value * factor).sp,
    )
}

/** Largest transverse lane stack, including individually oversized controls. */
internal fun toolbarDepth(bar: BarMetrics, layout: ToolbarLayout?): Dp {
    val measured = layout?.visibleSections?.maxOfOrNull { section ->
        (0 until section.crossCells).fold(0.dp) { total, lane ->
            val laneButton = section.entriesInLane(lane)
                .maxOfOrNull { entry ->
                    if (entry.item == com.xnotes.core.tools.ToolbarItem.SPACER) bar.button
                    else entry.sizeDp?.dp ?: bar.button
                } ?: bar.button
            total + maxOf(bar.button, laneButton)
        } + bar.spacing * (section.crossCells - 1)
    } ?: bar.button
    return measured + bar.padding * 2
}

internal fun toolbarWidth(bar: BarMetrics, layout: ToolbarLayout?, geometry: ToolbarGeometry?): Dp =
    maxOf(toolbarDepth(bar, layout), geometry?.widthDp?.dp ?: 0.dp)

internal fun toolbarHeight(bar: BarMetrics, layout: ToolbarLayout?, geometry: ToolbarGeometry?): Dp =
    maxOf(toolbarDepth(bar, layout), geometry?.heightDp?.dp ?: 0.dp)

internal val LocalBar = staticCompositionLocalOf { barMetrics(ToolbarSize.REGULAR) }

/** Where a menu opens from its anchor; a side rail sets it so its menus open beside the rail. */
internal val LocalMenuOffset = compositionLocalOf { DpOffset.Zero }

/** How far a floating bar sits in from the canvas edge. */
private val FLOAT_MARGIN = 8.dp

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
    suppressBar: Boolean = false,
    barLayout: ToolbarLayout? = null,
    barGeometry: ToolbarGeometry? = null,
    workspace: ToolbarWorkspace? = null,
    dockedContent: (@Composable (ToolbarBar) -> Unit)? = null,
    onMoveBar: ((String, ToolbarAnchor, ToolbarDockAlignment) -> Unit)? = null,
    onReorderBar: ((String, String) -> Unit)? = null,
    content: @Composable (floatingBar: @Composable BoxScope.() -> Unit) -> Unit,
) {
    val look = LocalToolbarLook.current
    val rest = Modifier.weight(1f).fillMaxWidth()
    // Multi-dock occupies genuine document space on all four edges. The same plan drives
    // the miniature preview; floating bars remain in the content's overlay layer.
    if (workspace != null && dockedContent != null && workspace.hasMultiDock(look)) {
        SideEffect { onCover(0.0, 0.0, 0.0, 0.0) }
        val reorder = remember { ToolbarWorkspaceReorderController() }
        LaunchedEffect(workspace) { reorder.acknowledge(workspace) }
        var paneBounds by remember { mutableStateOf(Rect.Zero) }
        BoxWithConstraints(rest.onGloballyPositioned { paneBounds = it.boundsInRoot() }) {
            val basePlan = toolbarDockPlan(workspace, look, maxWidth, maxHeight)
            val proposedWorkspace = reorder.displayedWorkspace(workspace)
            val proposedPlan = if (proposedWorkspace == workspace) basePlan else
                toolbarDockPlan(proposedWorkspace, look, maxWidth, maxHeight)
            val peers = remember(workspace, basePlan, onReorderBar != null) {
                if (onReorderBar == null) emptyMap() else workspace.bars.associate { dockBar ->
                    dockBar.id to toolbarReorderPeersInPlan(workspace, dockBar.id, basePlan,
                        floating = false)
                }
            }
            Box(Modifier.fillMaxSize().padding(
                start = basePlan.leftInset.dp, top = basePlan.topInset.dp,
                end = basePlan.rightInset.dp, bottom = basePlan.bottomInset.dp,
            )) { content({}) }
            for ((index, dockBar) in workspace.bars.withIndex()) {
                val placement = (if (reorder.sourceId == dockBar.id) basePlan else proposedPlan)
                    .placement(dockBar.id) ?: continue
                val barLook = look.copy(
                    position = placement.edge.toolbarPosition(),
                    size = toolbarBarSize(dockBar, index, look),
                    floating = false,
                )
                key(dockBar.id) {
                    ToolbarLiveReorderBar(
                        bar = dockBar, placement = placement,
                        x = placement.x, y = placement.y,
                        anchor = toolbarAnchorForAlignment(placement.edge, dockBar.geometry.dockAlignment),
                        alignment = dockBar.geometry.dockAlignment,
                        paneBounds = paneBounds,
                        workspace = workspace,
                        peers = peers[dockBar.id].orEmpty(),
                        reorder = reorder,
                        onMoveBar = onMoveBar,
                        onReorderBar = onReorderBar,
                    ) {
                        CompositionLocalProvider(LocalToolbarLook provides barLook) {
                            if (index == 0) bar() else dockedContent(dockBar)
                        }
                    }
                }
            }
        }
        return
    }
    // A moved primary is rendered in the shared floating workspace, not twice here.
    if (suppressBar) {
        SideEffect { onCover(0.0, 0.0, 0.0, 0.0) }
        Box(rest) { content({}) }
        return
    }
    if (!look.floating) {
        SideEffect { onCover(0.0, 0.0, 0.0, 0.0) }
        val docked: @Composable BoxScope.() -> Unit = {}
        // Match the preview: manually shortened docked bars stay centered on their edge.
        val dockedBar: @Composable () -> Unit = {
            when {
                !look.position.vertical && barGeometry?.widthDp != null ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { bar() }
                look.position.vertical && barGeometry?.heightDp != null ->
                    Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { bar() }
                else -> bar()
            }
        }
        // Left and right are the screen's, as the canvas insets are, whatever the language.
        val across = Arrangement.Absolute.Left
        when (look.position) {
            ToolbarPosition.TOP -> { dockedBar(); Box(rest) { content(docked) } }
            ToolbarPosition.BOTTOM -> { Box(rest) { content(docked) }; dockedBar() }
            ToolbarPosition.LEFT -> Row(rest, across) { dockedBar(); Box(Modifier.weight(1f).fillMaxHeight()) { content(docked) } }
            ToolbarPosition.RIGHT -> Row(rest, across) { Box(Modifier.weight(1f).fillMaxHeight()) { content(docked) }; dockedBar() }
        }
        return
    }
    val metrics = resolvedBarMetrics(look.size, barGeometry)
    val depth = (if (look.position.vertical) toolbarWidth(metrics, barLayout, barGeometry)
        else toolbarHeight(metrics, barLayout, barGeometry)) + FLOAT_MARGIN
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
        Box(rest) { content { Box(Modifier.align(edge).padding(FLOAT_MARGIN)) { bar() } } }
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
    layout: ToolbarLayout,
    geometry: ToolbarGeometry? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val look = LocalToolbarLook.current
    val bar = resolvedBarMetrics(look.size, geometry).copy(vertical = look.position.vertical)
    val depth = toolbarDepth(bar, layout)
    val compactRegions = toolbarCompactRegions(layout, look.floating,
        if (bar.vertical) geometry?.heightDp else geometry?.widthDp)
    val autoLengthPinned = look.floating && layout.hasPinnedRegions &&
        (if (bar.vertical) geometry?.heightDp == null else geometry?.widthDp == null)
    val glide = remember { ToolGlide() }
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.extraLarge
    // Docked, the strip fills its edge; floating, a pill only as long as its tools, up to that edge.
    val surface = if (!look.floating) {
        Modifier.background(palette.panel.toComposeColor())
    } else {
        Modifier
            .shadow(6.dp, shape)
            .clip(shape)
            .background(palette.panel.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape)
    }
    val stretch = when {
        bar.vertical -> when {
            geometry?.heightDp != null -> Modifier.height(geometry.heightDp.dp)
            autoLengthPinned -> Modifier
            look.floating && layout.distribution == ToolbarDistribution.REGIONS &&
                geometry?.floating == true -> Modifier.fillMaxHeight()
            look.floating -> Modifier
            else -> Modifier.fillMaxHeight()
        }
        geometry?.widthDp != null -> Modifier.width(geometry.widthDp.dp)
        autoLengthPinned -> Modifier
        look.floating && layout.distribution == ToolbarDistribution.REGIONS &&
            geometry?.floating == true -> Modifier.fillMaxWidth()
        look.floating -> Modifier
        else -> Modifier.fillMaxWidth()
    }
    // Material places a menu below its anchor; shifting it one bar across and one button up puts it
    // beside the rail, and on a right rail material mirrors it to the left side on its own.
    val menuOffset = if (bar.vertical) DpOffset(depth, -bar.button) else DpOffset.Zero
    val tipSide = when (look.position) {
        ToolbarPosition.TOP -> TipSide.BELOW
        ToolbarPosition.BOTTOM -> TipSide.ABOVE
        ToolbarPosition.LEFT -> TipSide.RIGHT
        ToolbarPosition.RIGHT -> TipSide.LEFT
    }
    CompositionLocalProvider(LocalToolGlide provides glide, LocalBar provides bar,
        LocalCompactToolbarRegions provides compactRegions, LocalMenuOffset provides menuOffset,
        LocalTipSide provides tipSide) {
        if (bar.vertical) {
            Column(
                stretch.width(toolbarWidth(bar, layout, geometry)).then(surface).testTag("toolbar-frame"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .then(if (autoLengthPinned) Modifier else Modifier.weight(1f,
                            fill = !look.floating || (layout.distribution == ToolbarDistribution.REGIONS && !compactRegions)))
                        .then(if (layout.distribution == ToolbarDistribution.CONTINUOUS || compactRegions)
                            Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(vertical = bar.padding)
                        .toolGlide(glide, armed),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { content() }
                trailing?.invoke()
            }
        } else {
            Row(
                stretch.height(toolbarHeight(bar, layout, geometry)).then(surface).testTag("toolbar-frame"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .then(if (autoLengthPinned) Modifier else Modifier.weight(1f,
                            fill = !look.floating || (layout.distribution == ToolbarDistribution.REGIONS && !compactRegions)))
                        .then(if (layout.distribution == ToolbarDistribution.CONTINUOUS || compactRegions)
                            Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = bar.padding)
                        .toolGlide(glide, armed),
                    verticalAlignment = Alignment.CenterVertically,
                ) { content() }
                trailing?.invoke()
            }
        }
    }
}
