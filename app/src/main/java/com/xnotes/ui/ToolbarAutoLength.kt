package com.xnotes.ui

import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.settings.ToolbarLook

/** Auto length is the sum of REAL along-axis extents, not the number of entries
 * multiplied by the default button size. Both editor preview and XNote/XCanvas
 * use this value through toolbarFloatingPlan. When natural content exceeds the
 * available screen, scrolling is still necessary, not a hidden sizing failure. */
internal fun toolbarEstimatedFloatingLength(
    bar: ToolbarBar, index: Int, look: ToolbarLook, colorCount: Int,
    vertical: Boolean = bar.geometry.anchor.floatingEdge() in
        listOf(ToolbarDockEdge.LEFT, ToolbarDockEdge.RIGHT),
): Float {
    val explicit = if (vertical) bar.geometry.heightDp else bar.geometry.widthDp
    if (explicit != null) return explicit.toFloat()
    val metrics = resolvedBarMetrics(toolbarBarSize(bar, index, look), bar.geometry)
    val visible = bar.layout.visibleSections
    val content = visible.sumOf { section ->
        toolbarEstimatedSectionLength(section, metrics, vertical, colorCount).toDouble()
    }.toFloat()
    // Separator has 4 dp padding on each end plus its 1-dp rule. A small
    // rounding allowance prevents one clipped swatch at fractional densities.
    val separators = (visible.size - 1).coerceAtLeast(0) * 9f
    return maxOf(48f, content + separators + 2 * metrics.padding.value + 2f)
}

internal fun toolbarEstimatedSectionLength(
    section: ToolbarSection, metrics: BarMetrics, vertical: Boolean, colorCount: Int,
): Float = (0 until section.crossCells).maxOfOrNull { lane ->
    val entries = section.entriesInLane(lane)
    val length = entries.sumOf { entry ->
        toolbarEstimatedEntryLength(entry, metrics, vertical, colorCount).toDouble()
    }.toFloat()
    // One-lane sections render their entries directly; multi-lane sections use
    // an Arrangement.spacedBy(metrics.spacing) on each lane.
    length + if (section.lanes > 1) (entries.size - 1).coerceAtLeast(0) * metrics.spacing.value else 0f
} ?: 0f

internal fun toolbarEstimatedEntryLength(
    entry: ToolbarEntry, metrics: BarMetrics, vertical: Boolean, colorCount: Int,
): Float {
    val sized = entryBarMetrics(metrics, entry)
    return when (entry.item) {
        ToolbarItem.SPACER -> (entry.sizeDp ?: ToolbarEntry.DEFAULT_SPACER_DP).toFloat()
        // Swatch() adds 3dp padding on BOTH sides of the active axis.
        ToolbarItem.COLORS -> entry.colorIndices(colorCount).count() * (sized.swatch.value + 6f)
        // These clusters contain two buttons plus a text label. The previous
        // 3*button estimate could be shorter than their actual measured text.
        ToolbarItem.PAGE_NAV -> 2 * sized.button.value + if (vertical) 44f else 58f
        ToolbarItem.ZOOM -> 2 * sized.button.value + if (vertical) 36f else 64f
        ToolbarItem.TITLE -> if (vertical) sized.button.value else 168f
        else -> sized.button.value
    }
}
