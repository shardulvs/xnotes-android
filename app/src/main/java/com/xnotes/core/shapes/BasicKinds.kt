package com.xnotes.core.shapes

import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FillRule
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// The Basic page's kinds beyond the six the first versions drew. Each builds inside its box; the
// knobs work in the same unturned frame.

/** A rectangle with round corners, the knob setting their radius as a share of the shorter side. */
internal class RoundedRectKind(id: String, private val capsule: Boolean) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = if (capsule) 2.0 else 1.5
    override val flips get() = Flip.NONE
    // Squared up a capsule is a circle, so its perfect form is its chip's pill.
    override fun perfectAspect(n: Int) = if (capsule) iconAspect else 1.0
    override val defaultAdj = if (capsule) doubleArrayOf() else doubleArrayOf(1.0 / 6.0)
    override val knobs = if (capsule) emptyList() else listOf(
        KnobDef(
            position = { box, adj, _ -> Pt(box.left + radius(box, adj), box.top) },
            drag = { to, box, _, _ ->
                val side = min(box.w, box.h)
                doubleArrayOf(if (side > 0.0) ((to.x - box.left) / side).coerceIn(0.0, 0.5) else 0.0)
            },
        ),
    )

    private fun radius(box: Rect, adj: DoubleArray): Double =
        if (capsule) min(box.w, box.h) / 2.0 else min(box.w, box.h) * adjOf(adj, 0).coerceIn(0.0, 0.5)

    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val r = radius(box, p.adj)
        val cx = box.centerX
        val cy = box.centerY
        val contour = roundedRect(box, r)
        val d = r * (1.0 - 1.0 / sqrt(2.0))
        val diag = 1.0 / sqrt(2.0)
        val connections = mutableListOf(
            ConnectionPoint(Pt(cx, box.top), listOf(Pt(0.0, -1.0))),
            ConnectionPoint(Pt(box.right, cy), listOf(Pt(1.0, 0.0))),
            ConnectionPoint(Pt(cx, box.bottom), listOf(Pt(0.0, 1.0))),
            ConnectionPoint(Pt(box.left, cy), listOf(Pt(-1.0, 0.0))),
        )
        if (!capsule && r > 0.0) {
            connections += ConnectionPoint(Pt(box.left + d, box.top + d), listOf(Pt(-diag, -diag)))
            connections += ConnectionPoint(Pt(box.right - d, box.top + d), listOf(Pt(diag, -diag)))
            connections += ConnectionPoint(Pt(box.right - d, box.bottom - d), listOf(Pt(diag, diag)))
            connections += ConnectionPoint(Pt(box.left + d, box.bottom - d), listOf(Pt(-diag, diag)))
        }
        val text = Rect(box.x + d, box.y + d, (box.w - 2 * d).coerceAtLeast(0.0), (box.h - 2 * d).coerceAtLeast(0.0))
        return ShapeGeometry(listOf(ShapePath(listOf(contour))), emptyList(), connections, ShapeKind.inset(text))
    }
}

/** Right angle at the bottom left, the long side running from the top left to the bottom right. */
internal class RightTriangleKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = 1.2
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val corners = listOf(Pt(box.left, box.top), Pt(box.right, box.bottom), Pt(box.left, box.bottom))
        val text = Rect(box.x, box.y + box.h / 2.0, box.w / 2.0, box.h / 2.0)
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, polygonConnections(corners), ShapeKind.inset(text))
    }
}

/** Corners on the middles of the box's sides. */
internal class DiamondKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = 1.3
    override val flips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val corners = listOf(Pt(box.centerX, box.top), Pt(box.right, box.centerY), Pt(box.centerX, box.bottom), Pt(box.left, box.centerY))
        val text = Rect(box.x + box.w / 4.0, box.y + box.h / 4.0, box.w / 2.0, box.h / 2.0)
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, polygonConnections(corners), text)
    }
}

/** Leaning right: the knob slides the top edge along, as a share of the width. */
internal class ParallelogramKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = 1.5
    override val defaultAdj = doubleArrayOf(0.25)
    override val knobs = listOf(
        KnobDef(
            position = { box, adj, _ -> Pt(box.left + box.w * adjOf(adj, 0), box.top) },
            drag = { to, box, _, _ -> doubleArrayOf(if (box.w > 0.0) ((to.x - box.left) / box.w).coerceIn(0.0, 0.9) else 0.25) },
        ),
    )
    // Upside down it leans the other way too, which a drag to the left already gives.
    override val dragFlips get() = Flip.X
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val s = box.w * adjOf(p.adj, 0).coerceIn(0.0, 0.9)
        val corners = listOf(Pt(box.left + s, box.top), Pt(box.right, box.top), Pt(box.right - s, box.bottom), Pt(box.left, box.bottom))
        val text = Rect(box.x + s / 2.0, box.y, (box.w - s).coerceAtLeast(0.0), box.h)
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, polygonConnections(corners), ShapeKind.inset(text))
    }
}

/** Narrower on top: the knob pulls both top corners in, as a share of the width. */
internal class TrapezoidKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = 1.5
    override val defaultAdj = doubleArrayOf(0.25)
    override val knobs = listOf(
        KnobDef(
            position = { box, adj, _ -> Pt(box.left + box.w * adjOf(adj, 0), box.top) },
            drag = { to, box, _, _ -> doubleArrayOf(if (box.w > 0.0) ((to.x - box.left) / box.w).coerceIn(0.0, 0.5) else 0.25) },
        ),
    )
    override val flips get() = Flip.Y
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val i = box.w * adjOf(p.adj, 0).coerceIn(0.0, 0.5)
        val corners = listOf(Pt(box.left + i, box.top), Pt(box.right - i, box.top), Pt(box.right, box.bottom), Pt(box.left, box.bottom))
        val text = Rect(box.x + i / 2.0, box.y, (box.w - i).coerceAtLeast(0.0), box.h)
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, polygonConnections(corners), ShapeKind.inset(text))
    }
}

/** A regular polygon of n sides standing on a side, stretched to fill its box. */
internal class RegularPolygonKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val defaultN get() = 5
    override val nRange = 3..24
    // An odd count stands on a side with a point up, and keeps standing so however it is dragged.
    override val flips get() = Flip.Y
    override val dragFlips get() = Flip.NONE
    override fun perfectAspect(n: Int) = aspectOf(regularUnit(n.coerceIn(3, 24)))
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val n = p.n.coerceIn(3, 24)
        val unit = regularUnit(n)
        val corners = fitToBox(unit, box)
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, polygonConnections(corners), ShapeKind.inset(box, 0.18))
    }
}

/**
 * A star of n points, stretched to fill its box. The knob sets how deep the dents go, as the
 * inner radius's share of the outer; left alone it gives each point count its classic star.
 */
internal class StarKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val defaultN get() = 5
    override val nRange = 3..24
    override val countKind get() = CountKind.POINTS
    override val flips get() = Flip.Y
    override val dragFlips get() = Flip.NONE
    override val defaultAdj = doubleArrayOf(-1.0)
    override val knobs = listOf(
        KnobDef(
            position = { box, adj, n -> starPoints(box, depth(adj, n), n.coerceIn(3, 24))[1] },
            drag = { to, box, adj, n ->
                val count = n.coerceIn(3, 24)
                val u = unfit(to, regularUnit(count), box)
                val a = -PI / 2.0 + PI / count
                doubleArrayOf((u.x * cos(a) + u.y * sin(a)).coerceIn(0.05, 0.95))
            },
        ),
    )

    /** The inner radius's share: the knob's, or the classic star for [n] points. */
    private fun depth(adj: DoubleArray, n: Int): Double {
        val k = adjOf(adj, 0)
        if (k > 0.0) return k
        val count = n.coerceIn(3, 24)
        return if (count >= 5) cos(2.0 * PI / count) / cos(PI / count) else 0.38
    }

    private fun starPoints(box: Rect, k: Double, n: Int): List<Pt> {
        val unit = (0 until 2 * n).map { i -> unitPoint(-PI / 2.0 + PI * i / n, if (i % 2 == 0) 1.0 else k) }
        // Fitted by the outer points alone, which is where the star meets its box.
        return fitToBox(unit, box, regularUnit(n))
    }

    override fun perfectAspect(n: Int) = aspectOf(regularUnit(n.coerceIn(3, 24)))

    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val n = p.n.coerceIn(3, 24)
        val k = depth(p.adj, n)
        val corners = starPoints(box, k, n)
        val c = box.center
        val connections = corners.filterIndexed { i, _ -> i % 2 == 0 }.map { at ->
            val d = at - c
            ConnectionPoint(at, listOf(if (d.length() > 0.0) d.normalized() else Pt.ZERO))
        }
        val s = 0.5 * k
        val text = Rect(c.x - box.w * s / 2.0, c.y - box.h * s / 2.0, box.w * s, box.h * s)
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, connections, text)
    }
}

/** A plus sign: the knob sets how thick its arms are, as a share of the shorter side. */
internal class CrossKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val flips get() = Flip.NONE
    override val defaultAdj = doubleArrayOf(1.0 / 3.0)
    override val knobs = listOf(
        KnobDef(
            position = { box, adj, _ -> Pt(box.centerX + arm(box, adj) / 2.0, box.top) },
            drag = { to, box, _, _ ->
                val side = min(box.w, box.h)
                doubleArrayOf(if (side > 0.0) (2.0 * abs(to.x - box.centerX) / side).coerceIn(0.05, 1.0) else 1.0 / 3.0)
            },
        ),
    )

    private fun arm(box: Rect, adj: DoubleArray) = min(box.w, box.h) * adjOf(adj, 0).coerceIn(0.05, 1.0)

    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val h = arm(box, p.adj) / 2.0
        val cx = box.centerX
        val cy = box.centerY
        val corners = listOf(
            Pt(cx - h, box.top), Pt(cx + h, box.top), Pt(cx + h, cy - h), Pt(box.right, cy - h),
            Pt(box.right, cy + h), Pt(cx + h, cy + h), Pt(cx + h, box.bottom), Pt(cx - h, box.bottom),
            Pt(cx - h, cy + h), Pt(box.left, cy + h), Pt(box.left, cy - h), Pt(cx - h, cy - h),
        )
        val ends = listOf(
            ConnectionPoint(Pt(cx, box.top), listOf(Pt(0.0, -1.0))),
            ConnectionPoint(Pt(box.right, cy), listOf(Pt(1.0, 0.0))),
            ConnectionPoint(Pt(cx, box.bottom), listOf(Pt(0.0, 1.0))),
            ConnectionPoint(Pt(box.left, cy), listOf(Pt(-1.0, 0.0))),
        )
        val text = Rect(cx - h, cy - h, 2 * h, 2 * h)
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, ends, text)
    }
}

/** A heart of six curves, its dip and tip on the box's middle line. */
internal class HeartKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val flips get() = Flip.Y
    override val dragFlips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        fun at(x: Double, y: Double) = Pt(box.left + x * box.w, box.top + y * box.h)
        val contour = VectorContour(
            at(0.5, 0.22),
            listOf(
                VectorSeg.Cubic(at(0.5, 0.08), at(0.38, 0.0), at(0.25, 0.0)),
                VectorSeg.Cubic(at(0.1, 0.0), at(0.0, 0.12), at(0.0, 0.3)),
                VectorSeg.Cubic(at(0.0, 0.55), at(0.25, 0.75), at(0.5, 1.0)),
                VectorSeg.Cubic(at(0.75, 0.75), at(1.0, 0.55), at(1.0, 0.3)),
                VectorSeg.Cubic(at(1.0, 0.12), at(0.9, 0.0), at(0.75, 0.0)),
                VectorSeg.Cubic(at(0.62, 0.0), at(0.5, 0.08), at(0.5, 0.22)),
            ),
            closed = true,
        )
        val connections = listOf(
            ConnectionPoint(at(0.5, 0.22), listOf(Pt(0.0, -1.0))),
            ConnectionPoint(at(0.5, 1.0), listOf(Pt(0.0, 1.0))),
            ConnectionPoint(at(0.0, 0.3), listOf(Pt(-1.0, 0.0))),
            ConnectionPoint(at(1.0, 0.3), listOf(Pt(1.0, 0.0))),
        )
        val text = Rect(box.left + 0.2 * box.w, box.top + 0.25 * box.h, 0.6 * box.w, 0.4 * box.h)
        return ShapeGeometry(listOf(ShapePath(listOf(contour))), emptyList(), connections, text)
    }
}

/** A cloud: round bumps around an oval, each a curve meeting the next at a crease. */
internal class CloudKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = 1.5
    // Its bumps are uneven but face no way, so a drag leaves it as its chip.
    override val dragFlips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        // Uneven steps round the oval, so the bumps read as a cloud rather than a gear.
        val turns = doubleArrayOf(0.0, 0.11, 0.24, 0.36, 0.47, 0.6, 0.71, 0.83, 0.93)
        val base = turns.map { t -> val a = 2.0 * PI * t - PI / 2.0; Pt(cos(a), sin(a)) }
        val segs = ArrayList<VectorSeg>(base.size)
        for (i in base.indices) {
            val a = base[i]
            val b = base[(i + 1) % base.size]
            // Each bump bulges out along the radius at both of its feet.
            val lift = a.distanceTo(b) * 0.62
            segs += VectorSeg.Cubic(a + a * lift, b + b * lift, b)
        }
        val unit = VectorContour(base[0], segs, closed = true)
        val tight = FreePath.bounds(listOf(unit)) ?: Rect(-1.0, -1.0, 2.0, 2.0)
        val placed = FreePath.place(FreePath.normalize(listOf(unit), tight), box).single()
        return ShapeGeometry(listOf(ShapePath(listOf(placed))), emptyList(), ellipseConnections(box), ShapeKind.inset(box, 0.2))
    }
}

/** A donut: the knob sets the ring's thickness as a share of the radius. */
internal class RingKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val flips get() = Flip.NONE
    override val defaultAdj = doubleArrayOf(0.25)
    override val knobs = listOf(
        KnobDef(
            position = { box, adj, _ -> Pt(box.centerX, box.top + box.h / 2.0 * thick(adj)) },
            drag = { to, box, _, _ -> doubleArrayOf(if (box.h > 0.0) ((to.y - box.top) / (box.h / 2.0)).coerceIn(0.02, 0.98) else 0.25) },
        ),
    )

    private fun thick(adj: DoubleArray) = adjOf(adj, 0).coerceIn(0.02, 0.98)

    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val t = thick(p.adj)
        val rx = box.w / 2.0
        val ry = box.h / 2.0
        val inner = 1.0 - t
        val path = ShapePath(
            listOf(
                ShapePath.ellipseContour(box.centerX, box.centerY, rx, ry),
                ShapePath.ellipseContour(box.centerX, box.centerY, rx * inner, ry * inner),
            ),
            PathRole.OUTLINE,
            FillRule.EVEN_ODD,
        )
        val k = inner / sqrt(2.0)
        val text = Rect(box.centerX - rx * k, box.centerY - ry * k, 2 * rx * k, 2 * ry * k)
        return ShapeGeometry(listOf(path), emptyList(), ellipseConnections(box), text)
    }
}

/**
 * A slice of the ellipse (a pie) or its rim alone (an arc), from a start angle to an end angle,
 * clockwise in degrees from three o'clock; a knob on each end turns it.
 */
internal class SliceKind(id: String, private val pie: Boolean) : ShapeKind(id) {
    override val isClosed get() = pie
    override val defaultAdj = if (pie) doubleArrayOf(0.0, 270.0) else doubleArrayOf(270.0, 0.0)
    override val knobs = listOf(knob(0), knob(1))

    private fun knob(i: Int) = KnobDef(
        position = { box, adj, _ -> onRim(box, adjOf(adj, i)) },
        drag = { to, box, adj, _ ->
            val out = doubleArrayOf(adjOf(adj, 0), adjOf(adj, 1))
            out[i] = angleOf(to, box)
            out
        },
    )

    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val a0 = adjOf(p.adj, 0)
        val a1 = adjOf(p.adj, 1)
        var sweep = norm(a1 - a0)
        if (sweep < 1e-9) sweep = 360.0
        val c = box.center
        val rx = box.w / 2.0
        val ry = box.h / 2.0
        val arc = arcSegments(c, rx, ry, Math.toRadians(a0), Math.toRadians(sweep))
        val from = onRim(box, a0)
        val to = onRim(box, a0 + sweep)
        val mid = onRim(box, a0 + sweep / 2.0)
        val contour = if (pie) {
            VectorContour(c, listOf(VectorSeg.Line(from)) + arc + VectorSeg.Line(c), closed = true)
        } else {
            VectorContour(from, arc, closed = false)
        }
        val corners = if (pie) listOf(c, from, to) else listOf(from, to)
        val connections = listOf(from, mid, to).map { at ->
            val d = Pt((at.x - c.x) / max(rx * rx, 1e-12), (at.y - c.y) / max(ry * ry, 1e-12))
            ConnectionPoint(at, listOf(if (d.length() > 0.0) d.normalized() else Pt.ZERO))
        }
        val role = if (pie) PathRole.OUTLINE else PathRole.STROKE_ONLY
        return ShapeGeometry(listOf(ShapePath(listOf(contour), role)), corners, connections, ShapeKind.inset(box, 0.25))
    }

    private fun onRim(box: Rect, deg: Double): Pt {
        val a = Math.toRadians(deg)
        return Pt(box.centerX + box.w / 2.0 * cos(a), box.centerY + box.h / 2.0 * sin(a))
    }

    private fun angleOf(p: Pt, box: Rect): Double {
        val x = if (box.w > 0.0) (p.x - box.centerX) / (box.w / 2.0) else 0.0
        val y = if (box.h > 0.0) (p.y - box.centerY) / (box.h / 2.0) else 0.0
        return norm(Math.toDegrees(atan2(y, x)))
    }

    private fun norm(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0.0) r + 360.0 else r
    }
}

/**
 * A smooth curve through its control points (uniform Catmull-Rom, as the fork drew it). The points
 * are stored as a polyline in [ShapeParams.path], so they are the vertex dots a drag moves; an edge
 * dot sits on the curve itself and adds a point there.
 */
internal class SplineKind(id: String) : ShapeKind(id) {
    override val isClosed get() = false
    override val isFree get() = true
    // The curve swings past its points between them.
    override val staysInBox get() = false
    override val iconAspect get() = 1.5
    override val flips get() = Flip.NONE

    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val points = FreePath.place(p.path ?: DEFAULT, box).firstOrNull()?.let(FreePath::vertices).orEmpty()
        val curve = if (points.size >= 2) listOf(catmullRom(points)) else emptyList()
        return ShapeGeometry(listOf(ShapePath(curve, PathRole.STROKE_ONLY)), points, points.map { ConnectionPoint(it, emptyList()) }, ShapeKind.inset(box))
    }

    companion object {
        /** Fewer points and the curve is a straight line, which a line already is. */
        const val MIN_POINTS = 3

        /** A new curve's points, normalized to its box: nearly straight bottom-left to top-right, with a slight wave. */
        val DEFAULT = listOf(FreePath.lines(listOf(Pt(0.0, 1.0), Pt(0.3, 0.6), Pt(0.7, 0.4), Pt(1.0, 0.0)), closed = false))
    }
}

/** The uniform Catmull-Rom spline through [c] as the cubic Beziers that trace it exactly. */
internal fun catmullRom(c: List<Pt>): VectorContour {
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

/** A point on the unit circle at angle [a], at radius [r]. */
private fun unitPoint(a: Double, r: Double) = Pt(r * cos(a), r * sin(a))

/** The corners of a regular [n]-gon on the unit circle, the first straight up. */
private fun regularUnit(n: Int) = (0 until n).map { k -> unitPoint(-PI / 2.0 + 2.0 * PI * k / n, 1.0) }

/** The width-to-height of [pts]' bounds: a box of it fits them without a stretch. */
private fun aspectOf(pts: List<Pt>) = Rect.bounding(pts).let { if (it.h > 0.0) it.w / it.h else 1.0 }

/** [unit] points stretched so that [by] (default: the points themselves) exactly fill [box]. */
private fun fitToBox(unit: List<Pt>, box: Rect, by: List<Pt> = unit): List<Pt> {
    val b = Rect.bounding(by)
    val sx = if (b.w > 0.0) box.w / b.w else 0.0
    val sy = if (b.h > 0.0) box.h / b.h else 0.0
    return unit.map { Pt(box.left + (it.x - b.left) * sx, box.top + (it.y - b.top) * sy) }
}

/** The unit-space point [p] in [box] stands for, under the same stretch [fitToBox] applies to [by]. */
private fun unfit(p: Pt, by: List<Pt>, box: Rect): Pt {
    val b = Rect.bounding(by)
    val x = if (box.w > 0.0) b.left + (p.x - box.left) * b.w / box.w else 0.0
    val y = if (box.h > 0.0) b.top + (p.y - box.top) * b.h / box.h else 0.0
    return Pt(x, y)
}

/** Eight glue points round the ellipse in [box], each facing out along its normal. */
internal fun ellipseConnections(box: Rect): List<ConnectionPoint> {
    val rx = box.w / 2.0
    val ry = box.h / 2.0
    return (0 until 8).map { k ->
        val a = k * PI / 4.0
        val at = Pt(box.centerX + rx * cos(a), box.centerY + ry * sin(a))
        val nx = if (rx > 0.0) cos(a) / rx else 0.0
        val ny = if (ry > 0.0) sin(a) / ry else 0.0
        val len = hypot(nx, ny)
        ConnectionPoint(at, listOf(if (len > 0.0) Pt(nx / len, ny / len) else Pt.ZERO))
    }
}

/** [box] as a closed contour with corners rounded to [r], clockwise from the end of the top-left curve. */
internal fun roundedRect(box: Rect, r: Double): VectorContour {
    val rr = r.coerceIn(0.0, min(box.w, box.h) / 2.0)
    if (rr <= 1e-9) return ShapePath.lines(listOf(box.topLeft, Pt(box.right, box.top), Pt(box.right, box.bottom), Pt(box.left, box.bottom)), closed = true)
    val k = rr * ShapePath.KAPPA
    val l = box.left
    val t = box.top
    val rt = box.right
    val b = box.bottom
    return VectorContour(
        Pt(l + rr, t),
        listOf(
            VectorSeg.Line(Pt(rt - rr, t)),
            VectorSeg.Cubic(Pt(rt - rr + k, t), Pt(rt, t + rr - k), Pt(rt, t + rr)),
            VectorSeg.Line(Pt(rt, b - rr)),
            VectorSeg.Cubic(Pt(rt, b - rr + k), Pt(rt - rr + k, b), Pt(rt - rr, b)),
            VectorSeg.Line(Pt(l + rr, b)),
            VectorSeg.Cubic(Pt(l + rr - k, b), Pt(l, b - rr + k), Pt(l, b - rr)),
            VectorSeg.Line(Pt(l, t + rr)),
            VectorSeg.Cubic(Pt(l, t + rr - k), Pt(l + rr - k, t), Pt(l + rr, t)),
        ),
        closed = true,
    )
}

/** An elliptical arc as cubics of a quarter turn or less: [start] and [sweep] in radians, clockwise on screen. */
internal fun arcSegments(c: Pt, rx: Double, ry: Double, start: Double, sweep: Double): List<VectorSeg> {
    val pieces = max(1, kotlin.math.ceil(abs(sweep) / (PI / 2.0) - 1e-9).toInt())
    val step = sweep / pieces
    val k = 4.0 / 3.0 * tan(step / 4.0)
    val out = ArrayList<VectorSeg>(pieces)
    var a = start
    repeat(pieces) {
        val b = a + step
        val p0 = Pt(cos(a), sin(a))
        val p3 = Pt(cos(b), sin(b))
        val c1 = p0 + Pt(-sin(a), cos(a)) * k
        val c2 = p3 - Pt(-sin(b), cos(b)) * k
        fun place(u: Pt) = Pt(c.x + rx * u.x, c.y + ry * u.y)
        out += VectorSeg.Cubic(place(c1), place(c2), place(p3))
        a = b
    }
    return out
}
