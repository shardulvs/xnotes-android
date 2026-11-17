package com.xnotes.core.tools

/** Docking is independent of floating anchors. Lane 0 touches the window edge. */
enum class ToolbarDockEdge { TOP, BOTTOM, LEFT, RIGHT }
enum class ToolbarDockAlignment { START, CENTER, END }

data class ToolbarDockRequest(
    val id: String,
    val edge: ToolbarDockEdge,
    val lane: Int,
    val alignment: ToolbarDockAlignment,
    /** Along the edge, in logical dp. Auto-sized docked bars claim the full edge. */
    val length: Float,
    /** Into the document, in logical dp. */
    val depth: Float,
    val autoLength: Boolean = false,
)

data class ToolbarDockPlacement(
    val id: String,
    val edge: ToolbarDockEdge,
    val lane: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class ToolbarDockPlan(
    val placements: List<ToolbarDockPlacement>,
    val rejected: Set<String>,
    val topInset: Float,
    val bottomInset: Float,
    val leftInset: Float,
    val rightInset: Float,
) {
    fun placement(id: String): ToolbarDockPlacement? = placements.firstOrNull { it.id == id }
}

/**
 * Deterministic screen-space packing shared by editor validation, live document and preview.
 * Top/bottom own the corners; left/right occupy only the space between them. Bars never
 * overlap. Three lanes maximum on each edge; a full lane rejects later additions.
 */
object ToolbarDockLayout {
    const val MAX_LANES = 3

    fun arrange(
        width: Float,
        height: Float,
        requests: List<ToolbarDockRequest>,
        gap: Float = 4f,
    ): ToolbarDockPlan {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
            return ToolbarDockPlan(emptyList(), requests.mapTo(mutableSetOf()) { it.id }, 0f, 0f, 0f, 0f)
        }
        val spacing = gap.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val rejected = mutableSetOf<String>()
        val accepted = mutableListOf<ToolbarDockPlacement>()
        val depths = mutableMapOf<Pair<ToolbarDockEdge, Int>, Float>()
        val seen = mutableSetOf<String>()

        fun fit(edge: ToolbarDockEdge, axisStart: Float, axisLength: Float) {
            for (lane in 0 until MAX_LANES) {
                val laneRequests = requests.filter { it.edge == edge && it.lane == lane }
                val kept = mutableListOf<ToolbarDockRequest>()
                var used = 0f
                var laneDepth = 0f
                val other = when (edge) {
                    ToolbarDockEdge.TOP -> ToolbarDockEdge.BOTTOM
                    ToolbarDockEdge.BOTTOM -> ToolbarDockEdge.TOP
                    ToolbarDockEdge.LEFT -> ToolbarDockEdge.RIGHT
                    ToolbarDockEdge.RIGHT -> ToolbarDockEdge.LEFT
                }
                val previousDepth = (0 until lane).sumOf { (depths[edge to it] ?: 0f).toDouble() }.toFloat()
                val opposingDepth = (0 until MAX_LANES).sumOf { (depths[other to it] ?: 0f).toDouble() }.toFloat()
                val acrossLimit = if (edge == ToolbarDockEdge.TOP || edge == ToolbarDockEdge.BOTTOM) height else width
                for (r in laneRequests) {
                    val len = if (r.autoLength) axisLength else r.length
                    if (!seen.add(r.id) || !len.isFinite() || !r.depth.isFinite() ||
                        len <= 0f || r.depth <= 0f ||
                        previousDepth + opposingDepth + maxOf(laneDepth, r.depth) > acrossLimit ||
                        used + len + (if (kept.isEmpty()) 0f else spacing) > axisLength + 0.001f
                    ) {
                        rejected.add(r.id)
                    } else {
                        used += len + (if (kept.isEmpty()) 0f else spacing)
                        laneDepth = maxOf(laneDepth, r.depth)
                        kept.add(r.copy(length = len))
                    }
                }
                if (kept.isEmpty()) continue
                depths[edge to lane] = kept.maxOf { it.depth }
                // Within each lane, start/end clusters hug their edges. The middle cluster
                // is centered in the remaining gap, never crossing its neighbors.
                val starts = kept.filter { it.alignment == ToolbarDockAlignment.START }
                val middles = kept.filter { it.alignment == ToolbarDockAlignment.CENTER }
                val ends = kept.filter { it.alignment == ToolbarDockAlignment.END }
                fun clusterLength(items: List<ToolbarDockRequest>): Float =
                    items.sumOf { it.length.toDouble() }.toFloat() + spacing * (items.size - 1).coerceAtLeast(0)
                val a = clusterLength(starts)
                val b = clusterLength(middles)
                val c = clusterLength(ends)
                val endStart = axisStart + axisLength - c
                val minMid = axisStart + a + if (starts.isNotEmpty() && middles.isNotEmpty()) spacing else 0f
                val maxMid = endStart - b - if (ends.isNotEmpty() && middles.isNotEmpty()) spacing else 0f
                val midStart = (axisStart + (axisLength - b) / 2f).coerceIn(minMid, maxOf(minMid, maxMid))
                val runs = listOf(starts to axisStart, middles to midStart, ends to endStart)
                for ((items, runStart) in runs) {
                    var along = runStart
                    for (r in items) {
                        val d = depths.getValue(edge to lane)
                        val before = (0 until lane).sumOf { (depths[edge to it] ?: 0f).toDouble() }.toFloat()
                        val cross = when (edge) {
                            ToolbarDockEdge.TOP -> before
                            ToolbarDockEdge.BOTTOM -> height - before - d
                            ToolbarDockEdge.LEFT -> before
                            ToolbarDockEdge.RIGHT -> width - before - d
                        }
                        val (x, y, w, h) = when (edge) {
                            ToolbarDockEdge.TOP, ToolbarDockEdge.BOTTOM -> listOf(along, cross, r.length, d)
                            ToolbarDockEdge.LEFT, ToolbarDockEdge.RIGHT -> listOf(cross, along, d, r.length)
                        }
                        accepted.add(ToolbarDockPlacement(r.id, edge, lane, x, y, w, h))
                        along += r.length + spacing
                    }
                }
            }
        }

        // Compute top/bottom depths first so lateral bars cannot occupy the same corners.
        fit(ToolbarDockEdge.TOP, 0f, width)
        fit(ToolbarDockEdge.BOTTOM, 0f, width)
        val top = (0 until MAX_LANES).sumOf { (depths[ToolbarDockEdge.TOP to it] ?: 0f).toDouble() }.toFloat()
        val bottom = (0 until MAX_LANES).sumOf { (depths[ToolbarDockEdge.BOTTOM to it] ?: 0f).toDouble() }.toFloat()
        val freeHeight = (height - top - bottom).coerceAtLeast(0f)
        fit(ToolbarDockEdge.LEFT, top, freeHeight)
        fit(ToolbarDockEdge.RIGHT, top, freeHeight)
        val left = (0 until MAX_LANES).sumOf { (depths[ToolbarDockEdge.LEFT to it] ?: 0f).toDouble() }.toFloat()
        val right = (0 until MAX_LANES).sumOf { (depths[ToolbarDockEdge.RIGHT to it] ?: 0f).toDouble() }.toFloat()
        // If opposing lane stacks exceed the viewport, later stacks cannot be admitted.
        // Callers should validate the candidate configuration before saving.
        for (r in requests) if (r.lane !in 0 until MAX_LANES || r.id !in seen) rejected.add(r.id)
        return ToolbarDockPlan(accepted, rejected, top, bottom, left, right)
    }
}
