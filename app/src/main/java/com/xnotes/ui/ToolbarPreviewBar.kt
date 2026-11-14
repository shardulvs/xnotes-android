package com.xnotes.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.settings.ToolbarSize
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

@Composable
internal fun PreviewBar(
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
    val morph = LocalToolbarContainerMorph.current
    // Match the live ToolbarFrame shape, including the user's corner-style setting.
    val shape = if (floating) androidx.compose.material3.MaterialTheme.shapes.extraLarge
        else RoundedCornerShape(0.dp)
    // Animate only the toolbar body's dimensions; the surrounding drag slot
    // remains at its target coordinates so cross-edge releases cannot flicker.
    val surface = Modifier
        // The morph already controls size on every gesture frame. A second
        // animateContentSize tween lags behind it, then overshoots at commit.
        .then(if (morph == null) Modifier.animateContentSize(
            animationSpec = androidx.compose.animation.core.tween(220)) else Modifier)
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

    if (morph != null) {
        // Both orientations share one animated container and stable item instances.
        // Morph progress comes from the same 220-ms tween moving the dragged bar.
        CompositionLocalProvider(LocalBar provides metrics.copy(vertical = morph.toVertical)) {
            Box(
                surface.width(availableWidth).height(availableHeight)
                    .padding(metrics.padding),
                contentAlignment = Alignment.Center,
            ) {
                PreviewSections(bar.layout, metrics, colors, activeColorIndex, pageNumber,
                    pageCount, zoomLabel, titleLabel, onSelectEntry, selectedEntry,
                    vertical = morph.toVertical, compactRegions = compactRegions)
            }
        }
    } else if (vertical) {
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
                if (LocalToolbarContainerMorph.current != null) {
                    // During a container transform the shared reflow engine must
                    // receive each color/page/zoom control as a separate measurable.
                    // Wrapping PreviewTool in a Box would turn the entire group into
                    // one child and cause its inner Row/Column to snap on release.
                    PreviewTool(entry, colors, activeColorIndex, pageNumber, pageCount,
                        zoomLabel, titleLabel,
                        { onSelectEntry(sectionIndex, entryIndex) }, vertical)
                } else {
                    Box(
                        Modifier
                            .then(if (selected) Modifier.border(
                                1.dp, palette.accent.toComposeColor(), RoundedCornerShape(5.dp),
                            ) else Modifier)
                            .clickable { onSelectEntry(sectionIndex, entryIndex) },
                    ) {
                        PreviewTool(entry, colors, activeColorIndex, pageNumber, pageCount,
                            zoomLabel, titleLabel,
                            { onSelectEntry(sectionIndex, entryIndex) }, vertical)
                    }
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
        if (LocalToolbarContainerMorph.current != null) {
            // ToolbarMorphEntry arranges the emitted controls continuously.
            controls()
        } else if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally) { controls() }
        else Row(verticalAlignment = Alignment.CenterVertically) { controls() }
    } else {
        controls()
    }
}
