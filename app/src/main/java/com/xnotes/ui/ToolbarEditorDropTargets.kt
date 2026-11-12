package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarRegion

/**
 * Pure hit-testing for the toolbar editor, independent of Compose state.
 * Coordinates are in root space. Resolve the drop slot (sectionIndex, entryIndex) for a finger position in root coordinates. The
 * section is the card the finger is inside, else the nearest card. Within it, the first chip the
 * finger sits "before" (an earlier row, or left of its centre on the same row) wins; an empty card
 * takes slot 0. Returns null only when no card has been measured yet.
 */
internal fun toolbarDropTarget(
    layout: ToolbarLayout,
    finger: Offset,
    chipBounds: Map<Pair<Int, Int>, Rect>,
    laneBounds: Map<Pair<Int, Int>, Rect>,
    sectionBounds: Map<Int, Rect>,
): Triple<Int, Int, Int>? {
    var sec = layout.sections.indices.firstOrNull { sectionBounds[it]?.contains(finger) == true } ?: -1
    if (sec < 0) {
        var best = Float.MAX_VALUE
        for (i in layout.sections.indices) {
            val r = sectionBounds[i] ?: continue
            val dx = maxOf(r.left - finger.x, 0f, finger.x - r.right)
            val dy = maxOf(r.top - finger.y, 0f, finger.y - r.bottom)
            val d = dx * dx + dy * dy
            if (d < best) { best = d; sec = i }
        }
    }
    if (sec < 0) return null
    val section = layout.sections[sec]
    val lane = if (section.lanes == 1) 0 else (0 until section.lanes).minByOrNull { row ->
        val r = laneBounds[sec to row] ?: return@minByOrNull Float.MAX_VALUE
        val dx = maxOf(r.left - finger.x, 0f, finger.x - r.right)
        val dy = maxOf(r.top - finger.y, 0f, finger.y - r.bottom)
        dx * dx + dy * dy
    } ?: 0
    val indexed = section.entries.withIndex().filter { it.value.lane == lane }
    if (indexed.isEmpty()) return Triple(sec, lane, section.entries.size)
    fun before(r: Rect): Boolean = finger.y < r.center.y
    for ((idx, _) in indexed) {
        val r = chipBounds[sec to idx] ?: continue
        if (before(r)) return Triple(sec, lane, idx)
    }
    return Triple(sec, lane, indexed.last().index + 1)
}

internal data class ToolbarItemTarget(val region: ToolbarRegion?, val slot: Triple<Int, Int, Int>?)

internal fun toolbarItemDropTarget(
    layout: ToolbarLayout, finger: Offset,
    chipBounds: Map<Pair<Int, Int>, Rect>, laneBounds: Map<Pair<Int, Int>, Rect>,
    sectionBounds: Map<Int, Rect>, regionBounds: Map<ToolbarRegion, Rect>,
): ToolbarItemTarget? {
    if (layout.distribution == ToolbarDistribution.CONTINUOUS) {
        return toolbarDropTarget(layout, finger, chipBounds, laneBounds, sectionBounds)
            ?.let { ToolbarItemTarget(null, it) }
    }
    val region = nearestToolbarRegion(finger, regionBounds) ?: return null
    val withinRegion = sectionBounds.filterKeys { layout.sections.getOrNull(it)?.region == region }
    return ToolbarItemTarget(region, toolbarDropTarget(layout, finger, chipBounds, laneBounds, withinRegion))
}

private fun nearestToolbarRegion(finger: Offset, bounds: Map<ToolbarRegion, Rect>): ToolbarRegion? =
    bounds.entries.minByOrNull { (_, rect) ->
        val dx = maxOf(rect.left - finger.x, 0f, finger.x - rect.right)
        val dy = maxOf(rect.top - finger.y, 0f, finger.y - rect.bottom)
        dx * dx + dy * dy
    }?.key

/** Target column and position before a section; null means append to that column. */
data class ToolbarSectionTarget(val region: ToolbarRegion, val beforeSection: Int? = null)

internal fun toolbarSectionDropTarget(
    layout: ToolbarLayout,
    finger: Offset,
    sectionBounds: Map<Int, Rect>,
    regionBounds: Map<ToolbarRegion, Rect> = emptyMap(),
): ToolbarSectionTarget {
    if (layout.distribution == ToolbarDistribution.CONTINUOUS) {
        val before = layout.sections.indices.firstOrNull { i ->
            sectionBounds[i]?.let { r -> finger.y < r.center.y } == true
        }
        return ToolbarSectionTarget(ToolbarRegion.START, before)
    }
    // A drop can reach an empty region: use the column bounds, not the card bounds.
    val region = nearestToolbarRegion(finger, regionBounds) ?: ToolbarRegion.START
    val before = layout.sections.indices.firstOrNull { index ->
        layout.sections[index].region == region &&
            sectionBounds[index]?.let { finger.y < it.center.y } == true
    }
    return ToolbarSectionTarget(region, before)
}
