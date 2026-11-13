package com.xnotes.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val screenDensity = LocalDensity.current
    var stageBounds by remember { mutableStateOf(Rect.Zero) }
    val reorder = remember { ToolbarPreviewReorderController() }
    LaunchedEffect(workspace) { reorder.acknowledge(workspace) }
    val commitReorder by rememberUpdatedState(onReorderBar)
    // Pure layout resolver remains independent from Compose state and gesture lifecycle.
    val basePlan = remember(workspace, look, screenWidth, screenHeight, colors.size) {
        toolbarPreviewLayoutPlan(workspace, look, screenWidth, screenHeight, colors.size)
    }
    val peersByBar = remember(workspace, basePlan, onReorderBar != null) {
        if (onReorderBar == null) emptyMap() else toolbarPreviewPeers(workspace, basePlan)
    }
    val displayedWorkspace = reorder.displayedWorkspace(workspace)
    val previewPlan = if (displayedWorkspace == workspace) basePlan
        else remember(displayedWorkspace, look, screenWidth, screenHeight, colors.size) {
            toolbarPreviewLayoutPlan(displayedWorkspace, look, screenWidth, screenHeight, colors.size)
        }
    val dockPlan = previewPlan.docked
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
                // The dragged bar must remain at its original slot throughout the gesture.
                // Its ToolbarDragSurface applies the pointer delta; only peers move in the
                // preview reorder plan. Using the animated destination here moves the source
                // a second time and makes it jump away from the pen/finger.
                val isDraggedBar = reorder.sourceId == bar.id
                val barPlan = if (isDraggedBar) basePlan else previewPlan
                val barPosition = barPlan.position(bar.id) ?: return@forEachIndexed
                val placement = barPosition.placement
                val floating = barPosition.floating
                val anchor = bar.geometry.anchor
                val vertical = placement.edge == com.xnotes.core.tools.ToolbarDockEdge.LEFT ||
                    placement.edge == com.xnotes.core.tools.ToolbarDockEdge.RIGHT
                val x = barPosition.x
                val y = barPosition.y
                // Only peers animate. Source follows the stylus while dragging and
                // lands directly on the committed slot after release. Returning the
                // source through an animated coordinate causes a visible snapback.
                val sourceIsControlled = isDraggedBar || reorder.lastReleasedId == bar.id
                val animatedX by animateDpAsState(x.dp,
                    animationSpec = if (sourceIsControlled) snap() else androidx.compose.animation.core.spring(),
                    label = "barReorderX-${bar.id}")
                val animatedY by animateDpAsState(y.dp,
                    animationSpec = if (sourceIsControlled) snap() else androidx.compose.animation.core.spring(),
                    label = "barReorderY-${bar.id}")
                val visibleX = if (sourceIsControlled) x.dp else animatedX
                val visibleY = if (sourceIsControlled) y.dp else animatedY
                // The lane uses its tallest/widest peer as its depth. Align each actual
                // bar against the occupied screen edge, rather than centering a short
                // one-row floating bar in a two-row peer's taller allocation.
                val edgeAlignment = when (placement.edge) {
                    com.xnotes.core.tools.ToolbarDockEdge.TOP -> Alignment.TopCenter
                    com.xnotes.core.tools.ToolbarDockEdge.BOTTOM -> Alignment.BottomCenter
                    com.xnotes.core.tools.ToolbarDockEdge.LEFT -> androidx.compose.ui.AbsoluteAlignment.CenterLeft
                    com.xnotes.core.tools.ToolbarDockEdge.RIGHT -> androidx.compose.ui.AbsoluteAlignment.CenterRight
                }
                val positionModifier = Modifier.offset(x = visibleX, y = visibleY)
                    .width(placement.width.dp).height(placement.height.dp)
                    .testTag("preview-bar-${bar.id}")
                    .zIndex(if (reorder.sourceId == bar.id) 2f else if (reorder.targetId == bar.id) 1f else 0f)
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInRoot()
                        reorder.updateBounds(bar.id, bounds)
                    }
                val peers = peersByBar[bar.id].orEmpty()
                // Gestures and animation state must belong to a bar ID, not to its
                // list position when a workspace adds/removes or reorders neighbors.
                key(bar.id) {
                Box(positionModifier, contentAlignment = edgeAlignment) {
                    ToolbarDragSurface(
                        id = bar.id,
                        anchor = anchor,
                        movable = bar.geometry.movable,
                        paneBounds = stageBounds,
                        onMoveBar = { movedId, destination ->
                            // Parent placement and drag offset must change atomically;
                            // do not spring the moved source back through its old edge.
                            reorder.markAnchorMoveReleased(movedId)
                            onMoveBar(movedId, destination)
                        },
                        onDragStart = if (onReorderBar == null) null else { _ ->
                            reorder.start(bar.id, peers, vertical, workspace)
                        },
                        onDragPosition = if (onReorderBar == null) null else { finger ->
                            reorder.hover(bar.id, finger)
                        },
                        onDrop = if (onReorderBar == null) null else { finger ->
                            reorder.drop(bar.id, finger) { source, destination ->
                                commitReorder?.invoke(source, destination)
                            }
                        },
                        onDragCancel = if (onReorderBar == null) null else ({
                            reorder.cancel(bar.id)
                        }),
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
                    if (reorder.targetId == bar.id) {
                        // The destination placeholder moves with the live rearranged toolbar.
                        Box(Modifier.matchParentSize().border(2.dp,
                            palette.accent.toComposeColor(), RoundedCornerShape(8.dp))
                            .testTag("preview-reorder-target-${bar.id}"))
                    }
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
        .testTag("preview-content-${bar.id}")

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
                else HorizontalToolbarTitle(titleLabel, onClick = onSelect)
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
