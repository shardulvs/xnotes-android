package com.xnotes.core.fill

import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.RefillShapes
import com.xnotes.core.infinite.ShapeTessellator
import com.xnotes.core.infinite.StrokeTessellator
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool

/** What a colour dropped at a point does, as [ColorDrop.decide] finds it. */
sealed interface DropAction {
    /** Recolour [item]: ink, a shape's outline, a connector, a text box's words, drawn text or a covered tape. */
    class Recolour(val item: CanvasItem) : DropAction

    /** Give [shape] the colour as its own fill, keeping the opacity of any fill it has. */
    class FillShape(val shape: ShapeItem) : DropAction

    /** Recolour the older fill [fill] in place, its area unchanged. */
    class Refill(val fill: ShapeItem) : DropAction

    /** Add [fill] directly above [above], or atop the fills at the bottom when null, taking out the older fills in [replaced]. */
    class AddFill(val fill: ShapeItem, val above: CanvasItem?, val replaced: List<ShapeItem>) : DropAction

    /** The point is on paper that no lines close off. */
    data object NotClosed : DropAction

    /** Nothing the drop may change: lines all round the point. */
    data object None : DropAction
}

/**
 * Where a dropped colour goes. A drop on a line recolours it; inside a shape whose inside is exactly
 * the closed area, the shape takes the colour; anywhere else that lines close off gets a fill of its
 * own, under everything but a filled shape it lies in. Pure, so both canvases decide alike.
 */
object ColorDrop {

    /** Cells a curve may stray when a line or fill is cut into triangles or rings for the grid. */
    private const val TOLERANCE_CELLS = 0.25

    /** True for what closes an area: ink, shape outlines and connectors. Never highlighters, fills, drawn text, pictures or text. */
    fun closes(item: CanvasItem): Boolean = when (item) {
        is Stroke -> item.tool != Tool.HIGHLIGHTER && !item.isEmpty
        is ShapeItem -> !item.isFill && item.shape != ShapeKind.GLYPHS && item.strokeWidth > 0.0
        is ConnectorItem -> true
        else -> false
    }

    /**
     * The sealed map of what closes areas in [grid]'s window, from [items] in z-order: their lines,
     * and where older fills paint. Null when [cancelled] says to stop.
     */
    fun map(items: List<CanvasItem>, grid: FillGrid, cancelled: () -> Boolean = { false }): FillMap? {
        val m = FillMap(grid)
        val tol = TOLERANCE_CELLS / grid.scale
        for (item in items) {
            if (cancelled()) return null
            if (!item.paintBounds().intersects(grid.window)) continue
            if (item is ShapeItem && item.isFill) {
                m.addPaint(item.fillRings(tol))
                continue
            }
            if (!closes(item)) continue
            when (item) {
                is Stroke -> {
                    val g = item.geometry()
                    var half = 0.0
                    for (k in 0 until g.pointCount) half = maxOf(half, g.hw(k))
                    m.addLine(StrokeTessellator.tessellate(g, tol), half)
                }
                is ShapeItem -> m.addLine(ShapeTessellator.outlineMesh(item, tol), item.strokeWidth / 2.0)
                is ConnectorItem -> for (part in ShapeTessellator.tessellate(item, tol)) m.addLine(part.mesh, item.strokeWidth / 2.0)
                else -> Unit
            }
        }
        m.seal()
        return m
    }

    /**
     * What a drop of [color] at [at] does among [items] (z-order, the ones [map] was built from). A
     * line within [reach] content px counts as hit; closed bits up to [smallHole] cells across fill
     * along with the area around them.
     */
    fun decide(items: List<CanvasItem>, map: FillMap, at: Pt, color: Rgba, reach: Double, smallHole: Int): DropAction {
        lineAt(items, at, reach)?.let { return DropAction.Recolour(it) }
        return when (val probe = map.probe(at)) {
            Probe.Open -> fillAt(items, at)?.let { DropAction.Refill(it) } ?: DropAction.NotClosed
            Probe.Blocked -> DropAction.None
            is Probe.Enclosed -> enclosed(items, map, probe, at, color, smallHole)
        }
    }

    /** The edit that gives [shape] [color] as its fill, keeping the opacity of any fill it has. */
    fun refill(shape: ShapeItem, color: Rgba): RefillShapes.Entry {
        val before = shape.fillRgba
        return RefillShapes.Entry(shape, before, before?.let { color.copy(a = it.a) } ?: color.withAlpha(255))
    }

    /** The line a drop at [at] lands on, the topmost within [reach]; null for none, a locked one, or one something covers there. */
    fun lineAt(items: List<CanvasItem>, at: Pt, reach: Double): CanvasItem? {
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (!item.bounds().outset(reach).contains(at)) continue
            if (isLine(item) && onLine(item, at, reach)) return item.takeUnless { it.locked }
            if (hides(item, at)) return null
        }
        return null
    }

    /** What a drop on a closed area does, once found. */
    private fun enclosed(items: List<CanvasItem>, map: FillMap, probe: Probe.Enclosed, at: Pt, color: Rgba, smallHole: Int): DropAction {
        var area = map.finish(probe, smallHole = smallHole)
        if (area.isEmpty) return DropAction.None
        val tol = TOLERANCE_CELLS / map.grid.scale
        // A shape whose inside is just this area takes the colour itself, so it moves with the shape.
        for (i in items.indices.reversed()) {
            val s = items[i] as? ShapeItem ?: continue
            if (s.locked || !fillable(s) || !s.encloses(at)) continue
            if (area.compare(s.fillRings(tol)).same) return DropAction.FillShape(s)
        }
        // Lying in a filled shape, it goes just above it, where that fill cannot hide it, and keeps off its outline.
        var above: ShapeItem? = null
        for (i in items.indices.reversed()) {
            val s = items[i] as? ShapeItem ?: continue
            if (!fillable(s) || s.fillRgba == null || !s.bounds().intersects(area.bounds)) continue
            if (area.compare(s.fillRings(tol)).within) {
                above = s
                break
            }
        }
        if (above != null) area = map.finish(probe, keepOut = listOf(ShapeTessellator.outlineMesh(above, tol)), smallHole = smallHole)
        if (area.isEmpty) return DropAction.None
        val replaced = ArrayList<ShapeItem>()
        for (item in items) {
            val f = item as? ShapeItem ?: continue
            if (!f.isFill || f.locked || !f.bounds().intersects(area.bounds)) continue
            val o = area.compare(f.fillRings(tol))
            if (o.same) return DropAction.Refill(f)
            if (o.covers) replaced.add(f)
        }
        return DropAction.AddFill(ShapeItem.fill(area.rings, color.withAlpha(255)), above, replaced)
    }

    /** A closed shape that can carry a fill of its own. */
    private fun fillable(s: ShapeItem) = s.shape.isClosed && !s.isFill && s.shape != ShapeKind.GLYPHS

    /** The topmost fill at [at] that a drop may recolour. */
    private fun fillAt(items: List<CanvasItem>, at: Pt): ShapeItem? {
        for (i in items.indices.reversed()) {
            val f = items[i] as? ShapeItem ?: continue
            if (f.isFill && !f.locked && f.contains(at)) return f
        }
        return null
    }

    private fun isLine(item: CanvasItem) = when (item) {
        is Stroke -> item.tool != Tool.HIGHLIGHTER && !item.isEmpty
        is ShapeItem -> !item.isFill
        is ConnectorItem, is TextItem -> true
        // A peeled tape shows what is under it, so the drop goes through it.
        is TapeItem -> !item.peeled
        else -> false
    }

    private fun onLine(item: CanvasItem, at: Pt, reach: Double) = when (item) {
        is Stroke -> item.contains(at) || item.intersectsCircle(at.x, at.y, reach)
        is ShapeItem -> if (item.shape == ShapeKind.GLYPHS) item.contains(at) else item.onOutline(at, reach)
        is ConnectorItem -> item.touches(at, reach)
        is TextItem, is TapeItem -> item.contains(at)
        else -> false
    }

    /** True when [item] covers what lies under it at [at]: a picture or a fill. */
    private fun hides(item: CanvasItem, at: Pt) = when (item) {
        is ImageItem, is AudioItem -> item.contains(at)
        is ShapeItem -> item.fillRgba != null && item.encloses(at)
        else -> false
    }
}
