package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.FreePath
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.shapes.SolidMathKinds
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Shapes that n-notes wrote before it took upstream's shape kinds: its X-Y axes, number line and
 * smooth curve, and function graphs saved as samples broken by empty points. Each one is read back
 * as the upstream kind that replaced it, so old notes keep drawing what they drew.
 */
internal object ForkShapes {

    /** Kind ids only the fork wrote. */
    val IDS = setOf("axes", "numberline", "spline")

    /** True when a shape item read as [id], with or without a [frame], was written by the fork. */
    fun isForkShape(id: String?, frame: Rect?, points: List<Pt>?): Boolean =
        id in IDS || (id == ShapeKind.FUNCTION.id && frame == null) || points?.any { it.x.isNaN() } == true

    /**
     * The shape a fork-era item stands for, in [stroke] colour and [width] wide, or null when the
     * item is not one. [angle] is the turn the fork kept on its axes; [function] what a graph plots.
     */
    fun build(
        id: String?,
        start: Pt?,
        end: Pt?,
        points: List<Pt>?,
        angle: Double,
        function: FunctionSpec?,
        frame: Rect?,
        stroke: Rgba,
        width: Double,
    ): ShapeItem? {
        if (!isForkShape(id, frame, points)) return null
        val a = start ?: points?.firstOrNull { !it.x.isNaN() } ?: return null
        val b = end ?: points?.lastOrNull { !it.x.isNaN() } ?: return null
        return when (id) {
            "axes" -> ShapeItem(SolidMathKinds.AXES_2D, a, b, stroke, width, rotation = angle.takeIf { it.isFinite() } ?: 0.0, n = AXES_TICKS)
            "numberline" -> numberLine(a, b, stroke, width)
            "spline" -> ShapeItem.free(ShapeKind.CURVE, listOf(curve(points?.filterNot { it.x.isNaN() }?.takeIf { it.size >= 2 } ?: listOf(a, (a + b) * 0.5, b))), stroke, width)
            else -> graph(a, b, points, function, stroke, width)
        }
    }

    /** The fork's number line ran from [a] to [b] with an arrowhead and nine ticks; upstream's is a turned box. */
    private fun numberLine(a: Pt, b: Pt, stroke: Rgba, width: Double): ShapeItem {
        val len = a.distanceTo(b)
        val head = max(5.0, min(width * 1.7, len * 0.08))
        val tick = max(width * 0.8, min(head * 0.45, len * 0.025))
        // Upstream's ticks reach 0.3 of the box's height either side of the line.
        val h = max(tick / 0.3, 1.0)
        val c = (a + b) * 0.5
        return ShapeItem(
            SolidMathKinds.NUMBER_LINE, Pt(c.x - len / 2.0, c.y - h / 2.0), Pt(c.x + len / 2.0, c.y + h / 2.0), stroke, width,
            rotation = atan2(b.y - a.y, b.x - a.x), n = NUMBER_LINE_TICKS,
        )
    }

    /** The fork's uniform Catmull-Rom spline through [c], as the cubic Béziers that trace it exactly. */
    internal fun curve(c: List<Pt>): VectorContour {
        val segs = ArrayList<VectorSeg>(c.size - 1)
        for (i in 0 until c.size - 1) {
            val p0 = c[max(i - 1, 0)]
            val p1 = c[i]
            val p2 = c[i + 1]
            val p3 = c[min(i + 2, c.size - 1)]
            segs.add(VectorSeg.Cubic(p1 + (p2 - p0) * (1.0 / 6.0), p2 - (p3 - p1) * (1.0 / 6.0), p2))
        }
        return VectorContour(c.first(), segs, false)
    }

    /** A graph replotted from its [function] in the box [a] to [b]; else its saved runs as a plain line. */
    private fun graph(a: Pt, b: Pt, points: List<Pt>?, function: FunctionSpec?, stroke: Rgba, width: Double): ShapeItem? {
        if (function != null) {
            val shape = ShapeItem(ShapeKind.FUNCTION, a, b, stroke, width)
            if (shape.setFunction(function)) return shape
        }
        val runs = FunctionSpec.runs(points ?: return null)
        if (runs.isEmpty()) return null
        return ShapeItem.free(ShapeKind.POLYLINE, runs.map { FreePath.lines(it, false) }, stroke, width)
    }

    private const val AXES_TICKS = 4
    private const val NUMBER_LINE_TICKS = 9
}
