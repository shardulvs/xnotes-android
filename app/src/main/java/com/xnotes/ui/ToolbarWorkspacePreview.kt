package com.xnotes.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarScale
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarSize
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Scale a toolbar editor preview as a miniature of the real screen, rather than
 * rendering full-size controls into an arbitrarily narrow preferences column.
 */
internal fun toolbarPreviewScale(previewWidth: Dp, screenWidth: Dp): Float {
    val available = previewWidth.value
    val reference = screenWidth.value
    if (!available.isFinite() || !reference.isFinite() || available <= 0f || reference <= 0f) return 1f
    return (available / reference).coerceIn(0.25f, 1f)
}

internal fun toolbarPreviewAspectRatio(screenWidth: Dp, screenHeight: Dp): Float {
    val width = screenWidth.value
    val height = screenHeight.value
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return 16f / 10f
    return width / height
}

/**
 * A note-like preview of the actual window width. Controls use the same dp/sp sizes
 * and composables as the live ToolbarFrame, all scaled by the same viewport ratio.
 * Tool clicks select a bar and never dispatch actions to the document.
 */
@Composable
internal fun ToolbarWorkspacePreview(
    workspace: ToolbarWorkspace,
    look: ToolbarLook,
    selectedId: String,
    colors: List<Rgba>,
    activeColorIndex: Int,
    pageNumber: Int,
    pageCount: Int,
    zoomLabel: String,
    titleLabel: String,
    onSelectBar: (String) -> Unit,
    onSelectEntry: (String, Int, Int) -> Unit,
    selectedEntry: Pair<Int, Int>?,
    onMoveBar: (String, ToolbarAnchor) -> Unit,
    /** Optional only in the editor, never in read-only workspace thumbnails. */
    onReorderBar: ((String, String) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val reorderHandleLabel = stringResource(R.string.toolbar_config_reorder_handle)
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val screenDensity = LocalDensity.current
    var stageBounds by remember { mutableStateOf(Rect.Zero) }
    val barBounds = remember { mutableStateMapOf<String, Rect>() }
    val gripBounds = remember { mutableStateMapOf<String, Rect>() }
    var reorderSource by remember { mutableStateOf<String?>(null) }
    var reorderTarget by remember { mutableStateOf<String?>(null) }
    var reorderFinger by remember { mutableStateOf(Offset.Zero) }
    var frozenBounds by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    var frozenPeers by remember { mutableStateOf<List<String>>(emptyList()) }
    var dragVertical by remember { mutableStateOf(false) }
    val commitReorder by rememberUpdatedState(onReorderBar)
    // Preview the *actual* new order without changing the stored workspace until drop.
    val displayedWorkspace = if (reorderSource != null && reorderTarget != null)
        workspace.reorderVisualBar(reorderSource!!, reorderTarget!!) else workspace
    val dockPlan = toolbarDockPlan(displayedWorkspace, look, screenWidth, screenHeight)
    val floatingPlan = toolbarFloatingPlan(
        displayedWorkspace, look,
        screenWidth - dockPlan.leftInset.dp - dockPlan.rightInset.dp,
        screenHeight - dockPlan.topInset.dp - dockPlan.bottomInset.dp,
        colors.size,
    )
    // Keep candidate lanes based on the original layout while the animated preview moves.
    // Compute once per preview, rather than running both planners for every bar.
    val baseDockPlan = if (displayedWorkspace == workspace) dockPlan
        else toolbarDockPlan(workspace, look, screenWidth, screenHeight)
    val baseFloatingPlan = if (displayedWorkspace == workspace) floatingPlan
        else toolbarFloatingPlan(workspace, look,
            screenWidth - baseDockPlan.leftInset.dp - baseDockPlan.rightInset.dp,
            screenHeight - baseDockPlan.topInset.dp - baseDockPlan.bottomInset.dp,
            colors.size)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(toolbarPreviewAspectRatio(screenWidth, screenHeight))
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFE7EAF0))
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(14.dp))
            .onGloballyPositioned { stageBounds = it.boundsInRoot() },
    ) {
        // Layout against the actual window width, then scale all dp/sp measurements together.
        // This preserves the bar-to-screen ratio even in a narrower settings pane.
        val scale = toolbarPreviewScale(maxWidth, screenWidth)
        val stageWidth = maxWidth / scale
        val stageHeight = maxHeight / scale
        CompositionLocalProvider(
            LocalDensity provides Density(screenDensity.density * scale, screenDensity.fontScale),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(palette.panel.toComposeColor())
                val left = dockPlan.leftInset.dp.toPx()
                val top = dockPlan.topInset.dp.toPx()
                val right = dockPlan.rightInset.dp.toPx()
                val bottom = dockPlan.bottomInset.dp.toPx()
                val paperWidth = (size.width - left - right).coerceAtLeast(0f)
                val paperHeight = (size.height - top - bottom).coerceAtLeast(0f)
                drawRect(
                    Color(0xFFFAFAFD),
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(paperWidth, paperHeight),
                )
                val inset = 14.dp.toPx()
                val rule = 30.dp.toPx()
                var y = top + 54.dp.toPx()
                while (y < size.height - bottom - inset) {
                    drawLine(
                        Color(0xFFE8EDF5),
                        androidx.compose.ui.geometry.Offset(left + inset, y),
                        androidx.compose.ui.geometry.Offset(size.width - right - inset, y),
                        1.dp.toPx(),
                    )
                    y += rule
                }
            }
            workspace.bars.forEachIndexed { index, bar ->
                if (!bar.visible) return@forEachIndexed
                val dockPlacement = dockPlan.placement(bar.id)
                val floating = dockPlacement == null
                val placement = dockPlacement ?: floatingPlan.placement(bar.id) ?: return@forEachIndexed
                val anchor = bar.geometry.anchor
                val vertical = placement.edge == com.xnotes.core.tools.ToolbarDockEdge.LEFT ||
                    placement.edge == com.xnotes.core.tools.ToolbarDockEdge.RIGHT
                val x = placement.x + if (floating) dockPlan.leftInset else 0f
                val y = placement.y + if (floating) dockPlan.topInset else 0f
                val smoothX by animateDpAsState(x.dp, label = "barReorderX-${bar.id}")
                val smoothY by animateDpAsState(y.dp, label = "barReorderY-${bar.id}")
                val positionModifier = Modifier.offset(x = smoothX, y = smoothY)
                    .width(placement.width.dp).height(placement.height.dp)
                    .testTag("preview-bar-${bar.id}")
                    .zIndex(if (reorderSource == bar.id) 2f else if (reorderTarget == bar.id) 1f else 0f)
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInRoot()
                        if (barBounds[bar.id] != bounds) barBounds[bar.id] = bounds
                    }
                val peers = if (onReorderBar != null) toolbarPreviewReorderPeers(
                    workspace, look, bar.id, baseDockPlan, baseFloatingPlan)
                    else emptyList()
                Box(positionModifier, contentAlignment = Alignment.Center) {
                    ToolbarDragSurface(
                        id = bar.id,
                        anchor = anchor,
                        movable = bar.geometry.movable,
                        paneBounds = stageBounds,
                        onMoveBar = onMoveBar,
                    ) {
                        PreviewBar(
                            bar = bar,
                            selected = bar.id == selectedId,
                            effectiveSize = if (index == 0) look.size else when (bar.geometry.scale) {
                                ToolbarScale.COMPACT -> ToolbarSize.COMPACT
                                ToolbarScale.REGULAR -> ToolbarSize.REGULAR
                                ToolbarScale.COMFORTABLE -> ToolbarSize.COMFORTABLE
                            },
                            vertical = vertical,
                            floating = floating,
                            availableWidth = placement?.width?.dp ?: (stageWidth - 20.dp),
                            availableHeight = placement?.height?.dp ?: (stageHeight - 20.dp),
                            colors = colors,
                            activeColorIndex = activeColorIndex,
                            pageNumber = pageNumber,
                            pageCount = pageCount,
                            zoomLabel = zoomLabel,
                            titleLabel = titleLabel,
                            onSelect = { onSelectBar(bar.id) },
                            onSelectEntry = { section, entry -> onSelectEntry(bar.id, section, entry) },
                            selectedEntry = if (bar.id == selectedId) selectedEntry else null,
                        )
                    }
                    if (reorderTarget == bar.id) {
                        // The destination placeholder moves with the live rearranged toolbar.
                        Box(Modifier.matchParentSize().border(2.dp,
                            palette.accent.toComposeColor(), RoundedCornerShape(8.dp))
                            .testTag("preview-reorder-target-${bar.id}"))
                    }
                    if (bar.id == selectedId && peers.size > 1 && onReorderBar != null) {
                        val handle = Modifier.align(Alignment.TopEnd)
                            // The whole preview is scaled; keep the drag affordance touchable.
                            .requiredSize((30f / scale).dp)
                            .onGloballyPositioned { coordinates ->
                                gripBounds[bar.id] = coordinates.boundsInRoot()
                            }
                            .clip(RoundedCornerShape(9.dp))
                            .background(palette.panel.toComposeColor())
                            .border(1.dp, palette.accent.toComposeColor(), RoundedCornerShape(9.dp))
                            .testTag("preview-reorder-handle-${bar.id}")
                            .semantics { contentDescription = reorderHandleLabel }
                            .pointerInput(bar.id, peers) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { finger ->
                                        reorderSource = bar.id
                                        reorderTarget = null
                                        frozenBounds = barBounds.toMap()
                                        frozenPeers = peers
                                        dragVertical = vertical
                                        reorderFinger = (gripBounds[bar.id]?.topLeft ?: Offset.Zero) + finger
                                    },
                                    onDrag = { change, displacement ->
                                        change.consume()
                                        reorderFinger += displacement
                                        reorderTarget = toolbarPreviewReorderHit(
                                            bar.id, frozenPeers, reorderFinger, frozenBounds,
                                            dragVertical,
                                        )
                                    },
                                    onDragEnd = {
                                        val from = reorderSource
                                        val to = reorderTarget
                                        if (from != null && to != null && to in frozenPeers)
                                            commitReorder?.invoke(from, to)
                                        reorderSource = null
                                        reorderTarget = null
                                        frozenBounds = emptyMap()
                                    },
                                    onDragCancel = {
                                        reorderSource = null
                                        reorderTarget = null
                                        frozenBounds = emptyMap()
                                    },
                                )
                            }
                        Box(handle, contentAlignment = Alignment.Center) {
                            Text("≡", color = palette.accent.toComposeColor(),
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
            if (reorderSource != null && stageBounds != Rect.Zero) {
                val source = workspace.bars.firstOrNull { it.id == reorderSource }
                if (source != null) {
                    Box(Modifier.offset {
                        androidx.compose.ui.unit.IntOffset(
                            (reorderFinger.x - stageBounds.left + 12f).toInt(),
                            (reorderFinger.y - stageBounds.top - 24f).toInt(),
                        )
                    }.zIndex(20f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.panel.toComposeColor())
                        .border(1.dp, palette.accent.toComposeColor(), RoundedCornerShape(8.dp))
                        .padding((7f / scale).dp)
                        .testTag("preview-reorder-ghost")) {
                        Text(source.name, color = palette.text.toComposeColor(),
                            fontSize = (13f / scale).sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBar(
    bar: ToolbarBar,
    selected: Boolean,
    effectiveSize: ToolbarSize,
    vertical: Boolean,
    floating: Boolean,
    availableWidth: Dp,
    availableHeight: Dp,
    colors: List<Rgba>,
    activeColorIndex: Int,
    pageNumber: Int,
    pageCount: Int,
    zoomLabel: String,
    titleLabel: String,
    onSelect: () -> Unit,
    onSelectEntry: (Int, Int) -> Unit,
    selectedEntry: Pair<Int, Int>?,
) {
    val palette = LocalPalette.current
    val metrics = resolvedBarMetrics(effectiveSize, bar.geometry).copy(vertical = vertical)
    val compactRegions = toolbarCompactRegions(bar.layout, floating,
        if (vertical) bar.geometry.heightDp else bar.geometry.widthDp)
    val autoLengthPinned = floating && bar.layout.hasPinnedRegions &&
        (if (vertical) bar.geometry.heightDp == null else bar.geometry.widthDp == null)
    // Match the live ToolbarFrame shape, including the user's corner-style setting.
    val shape = if (floating) androidx.compose.material3.MaterialTheme.shapes.extraLarge
        else RoundedCornerShape(0.dp)
    val surface = Modifier
        .then(if (floating) Modifier.shadow(6.dp, shape) else Modifier)
        .clip(shape)
        .background(palette.panel.toComposeColor())
        .border(
            if (selected) 2.dp else if (floating) 1.dp else 0.dp,
            (if (selected) palette.accent else palette.border).toComposeColor(),
            shape,
        )
        .clickable(onClick = onSelect)

    if (vertical) {
        Column(
            surface
                .then(if (bar.geometry.heightDp != null) Modifier.height(bar.geometry.heightDp.dp)
                    else if (bar.layout.distribution == ToolbarDistribution.REGIONS && !autoLengthPinned) Modifier.height(availableHeight)
                    else if (floating) Modifier.heightIn(max = availableHeight) else Modifier.fillMaxHeight())
                .width(toolbarWidth(metrics, bar.layout, bar.geometry))
                .then(if (bar.layout.distribution == ToolbarDistribution.CONTINUOUS || compactRegions)
                    Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(vertical = metrics.padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (bar.layout.distribution == ToolbarDistribution.REGIONS && !compactRegions && !autoLengthPinned) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PreviewSections(bar.layout, metrics, colors, activeColorIndex, pageNumber, pageCount, zoomLabel, titleLabel, onSelectEntry, selectedEntry, vertical = true, compactRegions = compactRegions)
                }
            } else {
                PreviewSections(bar.layout, metrics, colors, activeColorIndex, pageNumber, pageCount, zoomLabel, titleLabel, onSelectEntry, selectedEntry, vertical = true, compactRegions = compactRegions)
            }
        }
    } else {
        Row(
            surface
                .then(if (bar.geometry.widthDp != null) Modifier.width(bar.geometry.widthDp.dp)
                    else if (bar.layout.distribution == ToolbarDistribution.REGIONS && !autoLengthPinned) Modifier.width(availableWidth)
                    else if (floating) Modifier.widthIn(max = availableWidth) else Modifier.fillMaxWidth())
                .height(toolbarHeight(metrics, bar.layout, bar.geometry))
                .then(if (bar.layout.distribution == ToolbarDistribution.CONTINUOUS || compactRegions)
                    Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = metrics.padding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bar.layout.distribution == ToolbarDistribution.REGIONS && !compactRegions && !autoLengthPinned) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    PreviewSections(bar.layout, metrics, colors, activeColorIndex, pageNumber, pageCount, zoomLabel, titleLabel, onSelectEntry, selectedEntry, vertical = false, compactRegions = compactRegions)
                }
            } else {
                PreviewSections(bar.layout, metrics, colors, activeColorIndex, pageNumber, pageCount, zoomLabel, titleLabel, onSelectEntry, selectedEntry, vertical = false, compactRegions = compactRegions)
            }
        }
    }
}

@Composable
private fun PreviewSections(
    layout: ToolbarLayout,
    metrics: BarMetrics,
    colors: List<Rgba>,
    activeColorIndex: Int,
    pageNumber: Int,
    pageCount: Int,
    zoomLabel: String,
    titleLabel: String,
    onSelectEntry: (Int, Int) -> Unit,
    selectedEntry: Pair<Int, Int>?,
    vertical: Boolean,
    compactRegions: Boolean,
) {
    val palette = LocalPalette.current
    // Use the same controls, swatches, labels and separators as a live toolbar.
    // Preview clicks select the edited bar; they never edit the open document.
    CompositionLocalProvider(LocalBar provides metrics, LocalCompactToolbarRegions provides compactRegions) {
        ToolbarSections(layout) { sectionIndex, section ->
            ToolbarSectionGrid(section) { entry ->
                // Reference identity distinguishes repeated Colors occurrences with equal values.
                val entryIndex = section.entries.indexOfFirst { it === entry }
                val selected = selectedEntry == (sectionIndex to entryIndex)
                Box(
                    Modifier
                        .then(if (selected) Modifier.border(
                            1.dp, palette.accent.toComposeColor(), RoundedCornerShape(5.dp),
                        ) else Modifier)
                        .clickable { onSelectEntry(sectionIndex, entryIndex) },
                ) {
                    PreviewTool(
                        entry, colors, activeColorIndex, pageNumber, pageCount,
                        zoomLabel, titleLabel,
                        { onSelectEntry(sectionIndex, entryIndex) }, vertical,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewTool(
    entry: com.xnotes.core.tools.ToolbarEntry,
    colors: List<Rgba>,
    activeColorIndex: Int,
    pageNumber: Int,
    pageCount: Int,
    zoomLabel: String,
    titleLabel: String,
    onSelect: () -> Unit,
    vertical: Boolean,
) {
    val item = entry.item
    val controls: @Composable () -> Unit = {
        when (item) {
            ToolbarItem.SPACER -> ToolbarSpacer(entry)
            ToolbarItem.COLORS -> entry.colorIndices(colors.size).forEach { index ->
                Swatch(colors[index].toComposeColor(), active = index == activeColorIndex, onClick = onSelect)
            }
            ToolbarItem.PAGE_NAV -> {
                ToolbarIcon(XnotesIcons.prev, stringResource(R.string.previous_page), onClick = onSelect)
                PageCounter(pageNumber, pageCount, Modifier)
                ToolbarIcon(XnotesIcons.next, stringResource(R.string.next_page), onClick = onSelect)
            }
            ToolbarItem.ZOOM -> {
                ToolbarIcon(XnotesIcons.zoomOut, stringResource(R.string.zoom_out), onClick = onSelect)
                Label(zoomLabel)
                ToolbarIcon(XnotesIcons.zoomIn, stringResource(R.string.zoom_in), onClick = onSelect)
            }
            ToolbarItem.TITLE -> {
                if (vertical) VerticalToolbarTitle(titleLabel, onClick = onSelect)
                else Label(titleLabel, Modifier.widthIn(max = 160.dp))
            }
            else -> {
                // The real document/canvas toolbar uses the back arrow, not the Home house icon.
                val icon = if (item == ToolbarItem.HOME) XnotesIcons.prev else itemIcon(item)
                ToolbarIcon(icon, stringResource(item.labelRes), onClick = onSelect)
            }
        }
    }
    if (item == ToolbarItem.COLORS || item == ToolbarItem.PAGE_NAV || item == ToolbarItem.ZOOM) {
        if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally) { controls() }
        else Row(verticalAlignment = Alignment.CenterVertically) { controls() }
    } else {
        controls()
    }
}
