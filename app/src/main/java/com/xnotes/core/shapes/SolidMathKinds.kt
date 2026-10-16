package com.xnotes.core.shapes

import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// The 3D & math page. Solids are drawn in an oblique view: the visible outline and edges solid,
// the hidden edges dashed, and when filled the faces turned up or aside tinted lighter or darker.
// A knob sets the depth the view shows.

/** The depth a solid's knob sets, as a share of the box's shorter side. */
private fun depthOf(kind: ShapeKind, box: Rect, adj: DoubleArray) = kind.adjOf(adj, 0).coerceIn(0.05, 0.6) * min(box.w, box.h)

private fun poly(points: List<Pt>, role: PathRole) = ShapePath.polygon(points, role)

private fun line(role: PathRole, vararg points: Pt) = ShapePath.polyline(points.toList(), role)

private fun arc(c: Pt, rx: Double, ry: Double, startDeg: Double, sweepDeg: Double, role: PathRole) =
    ShapePath(listOf(VectorContour(onEllipse(c, rx, ry, startDeg), arcSegments(c, rx, ry, Math.toRadians(startDeg), Math.toRadians(sweepDeg)), false)), role)

private fun onEllipse(c: Pt, rx: Double, ry: Double, deg: Double) =
    Pt(c.x + rx * cos(Math.toRadians(deg)), c.y + ry * sin(Math.toRadians(deg)))

/** A solid: its paths for a depth, where its depth knob sits, and how a drag sets the depth. */
internal class SolidKind(
    id: String,
    override val iconAspect: Double,
    override val flips: Flip,
    private val knobAt: (Rect, Double) -> Pt,
    private val depthFrom: (Pt, Rect) -> Double,
    private val draw: (Rect, Double) -> List<ShapePath>,
    private val text: (Rect, Double) -> Rect = { b, _ -> ShapeKind.inset(b, 0.2) },
) : ShapeKind(id) {
    override val isClosed get() = true
    override val defaultAdj = doubleArrayOf(0.25)
    override val knobs = listOf(
        KnobDef(
            position = { box, adj, _ -> knobAt(box, depthOf(this, box, adj)) },
            drag = { to, box, _, _ ->
                val side = min(box.w, box.h)
                doubleArrayOf(if (side > 0.0) (depthFrom(to, box) / side).coerceIn(0.05, 0.6) else 0.25)
            },
        ),
    )
    // Seen from above whichever side it shows, so a drag never turns it over.
    override val dragFlips get() = flips.and(Flip.X)

    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val d = depthOf(this, box, p.adj)
        return ShapeGeometry(draw(box, d), emptyList(), cardinalConnections(box), text(box, d))
    }
}

/** A notation drawing: lines, ticks and its own arrowheads, never filled unless it is a grid. */
internal class NotationKind(
    id: String,
    override val iconAspect: Double,
    private val closed: Boolean = false,
    override val defaultN: Int = 0,
    override val nRange: IntRange? = null,
    override val countKind: CountKind = CountKind.SIDES,
    override val staysInBox: Boolean = true,
    override val defaultAdj: DoubleArray = DoubleArray(0),
    override val knobs: List<KnobDef> = emptyList(),
    override val flips: Flip = Flip.XY,
    override val dragFlips: Flip = flips,
    private val corners: (Rect) -> List<Pt> = { emptyList() },
    private val draw: (Rect, ShapeParams) -> List<ShapePath>,
) : ShapeKind(id) {
    override val isClosed get() = closed
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry =
        ShapeGeometry(draw(box, p), corners(box), if (closed) boxConnections(box) else emptyList(), ShapeKind.inset(box, 0.1))
}

internal object SolidMathKinds {

    val CUBE: ShapeKind = SolidKind(
        "solid_cube", 1.0, flips = Flip.XY,
        knobAt = { b, d -> Pt(b.left + d, b.top) },
        depthFrom = { to, b -> to.x - b.left },
        draw = { b, d ->
            val l = b.left
            val t = b.top
            val r = b.right
            val bt = b.bottom
            listOf(
                poly(listOf(Pt(l, t + d), Pt(l + d, t), Pt(r, t), Pt(r - d, t + d)), PathRole.SHADE_LIGHT),
                poly(listOf(Pt(r - d, t + d), Pt(r, t), Pt(r, bt - d), Pt(r - d, bt)), PathRole.SHADE_DARK),
                poly(listOf(Pt(l, t + d), Pt(r - d, t + d), Pt(r - d, bt), Pt(l, bt)), PathRole.OUTLINE),
                ShapePath(
                    listOf(
                        ShapePath.lines(listOf(Pt(l, t + d), Pt(l + d, t), Pt(r, t), Pt(r - d, t + d)), false),
                        ShapePath.lines(listOf(Pt(r, t), Pt(r, bt - d), Pt(r - d, bt)), false),
                    ),
                    PathRole.STROKE_ONLY,
                ),
                ShapePath(
                    listOf(
                        ShapePath.lines(listOf(Pt(l + d, t), Pt(l + d, bt - d), Pt(r, bt - d)), false),
                        ShapePath.lines(listOf(Pt(l + d, bt - d), Pt(l, bt)), false),
                    ),
                    PathRole.HIDDEN,
                ),
            )
        },
        text = { b, d -> ShapeKind.inset(Rect(b.left, b.top + d, b.w - d, b.h - d)) },
    )

    val CYLINDER: ShapeKind = SolidKind(
        "solid_cylinder", 0.8, flips = Flip.Y,
        knobAt = { b, d -> Pt(b.centerX, b.top + d) },
        depthFrom = { to, b -> to.y - b.top },
        draw = { b, d ->
            val ry = d / 2.0
            val rx = b.w / 2.0
            val top = Pt(b.centerX, b.top + ry)
            val bottom = Pt(b.centerX, b.bottom - ry)
            val body = VectorContour(
                Pt(b.left, top.y),
                listOf(VectorSeg.Line(Pt(b.left, bottom.y))) + arcSegments(bottom, rx, ry, PI, -PI) +
                    VectorSeg.Line(Pt(b.right, top.y)) + arcSegments(top, rx, ry, 0.0, -PI),
                closed = true,
            )
            listOf(
                ShapePath(listOf(body)),
                ShapePath(listOf(ShapePath.ellipseContour(top.x, top.y, rx, ry)), PathRole.SHADE_LIGHT),
                arc(top, rx, ry, 180.0, -180.0, PathRole.STROKE_ONLY),
                arc(bottom, rx, ry, 180.0, 180.0, PathRole.HIDDEN),
            )
        },
        text = { b, d -> ShapeKind.inset(Rect(b.left, b.top + d, b.w, b.h - 1.5 * d)) },
    )

    val CONE: ShapeKind = SolidKind(
        "solid_cone", 0.85, flips = Flip.Y,
        knobAt = { b, d -> Pt(b.centerX, b.bottom - d) },
        depthFrom = { to, b -> b.bottom - to.y },
        draw = { b, d ->
            val ry = d / 2.0
            val base = Pt(b.centerX, b.bottom - ry)
            val body = VectorContour(
                Pt(b.centerX, b.top),
                listOf(VectorSeg.Line(Pt(b.left, base.y))) + arcSegments(base, b.w / 2.0, ry, PI, -PI),
                closed = true,
            )
            listOf(ShapePath(listOf(body)), arc(base, b.w / 2.0, ry, 180.0, 180.0, PathRole.HIDDEN))
        },
        text = { b, d -> ShapeKind.inset(Rect(b.left + b.w / 4.0, b.top + b.h / 2.0, b.w / 2.0, b.h / 2.0 - d)) },
    )

    val SPHERE: ShapeKind = SolidKind(
        "solid_sphere", 1.0, flips = Flip.Y,
        knobAt = { b, d -> Pt(b.centerX, b.centerY + d / 2.0) },
        depthFrom = { to, b -> 2.0 * abs(to.y - b.centerY) },
        draw = { b, d ->
            val ry = d / 2.0
            listOf(
                ShapePath(listOf(ShapePath.ellipseContour(b.centerX, b.centerY, b.w / 2.0, b.h / 2.0))),
                arc(b.center, b.w / 2.0, ry, 180.0, -180.0, PathRole.STROKE_ONLY),
                arc(b.center, b.w / 2.0, ry, 180.0, 180.0, PathRole.HIDDEN),
            )
        },
        text = { b, _ -> ShapeKind.inset(b, 0.25) },
    )

    val PYRAMID: ShapeKind = SolidKind(
        "solid_pyramid", 1.0, flips = Flip.XY,
        knobAt = { b, d -> Pt(b.left + d, b.bottom - d) },
        depthFrom = { to, b -> to.x - b.left },
        draw = { b, d ->
            val apex = Pt(b.centerX, b.top)
            val fl = Pt(b.left, b.bottom)
            val fr = Pt(b.right - d, b.bottom)
            val br = Pt(b.right, b.bottom - d)
            val bl = Pt(b.left + d, b.bottom - d)
            listOf(
                poly(listOf(apex, fl, fr, br), PathRole.OUTLINE),
                poly(listOf(apex, fr, br), PathRole.SHADE_DARK),
                line(PathRole.STROKE_ONLY, apex, fr),
                ShapePath(listOf(ShapePath.lines(listOf(br, bl, fl), false), ShapePath.lines(listOf(apex, bl), false)), PathRole.HIDDEN),
            )
        },
        text = { b, d -> ShapeKind.inset(Rect(b.left + b.w / 4.0, b.top + b.h / 2.0, b.w / 2.0 - d / 2.0, b.h / 2.0)) },
    )

    val TETRAHEDRON: ShapeKind = SolidKind(
        "solid_tetrahedron", 1.0, flips = Flip.XY,
        knobAt = { b, d -> Pt(b.right, b.bottom - d) },
        depthFrom = { to, b -> b.bottom - to.y },
        draw = { b, d ->
            val apex = Pt(b.left + 0.45 * b.w, b.top)
            val fl = Pt(b.left, b.bottom)
            val fr = Pt(b.left + 0.68 * b.w, b.bottom)
            val back = Pt(b.right, b.bottom - d)
            listOf(
                poly(listOf(apex, fl, fr, back), PathRole.OUTLINE),
                poly(listOf(apex, fr, back), PathRole.SHADE_DARK),
                line(PathRole.STROKE_ONLY, apex, fr),
                line(PathRole.HIDDEN, fl, back),
            )
        },
        text = { b, _ -> ShapeKind.inset(Rect(b.left + 0.2 * b.w, b.top + 0.5 * b.h, 0.4 * b.w, 0.45 * b.h)) },
    )

    val TRI_PRISM: ShapeKind = SolidKind(
        "solid_tri_prism", 1.2, flips = Flip.XY,
        knobAt = { b, d -> Pt(b.right, b.bottom - d) },
        depthFrom = { to, b -> b.bottom - to.y },
        draw = { b, d ->
            val f0 = Pt(b.left, b.bottom)
            val f1 = Pt(b.right - d, b.bottom)
            val f2 = Pt((b.left + b.right - d) / 2.0, b.top + d)
            val shift = Pt(d, -d)
            val b0 = f0 + shift
            val b1 = f1 + shift
            val b2 = f2 + shift
            listOf(
                poly(listOf(f0, f1, b1, b2, f2), PathRole.OUTLINE),
                poly(listOf(f1, b1, b2, f2), PathRole.SHADE_DARK),
                line(PathRole.STROKE_ONLY, f1, f2),
                ShapePath(listOf(ShapePath.lines(listOf(b1, b0, b2), false), ShapePath.lines(listOf(b0, f0), false)), PathRole.HIDDEN),
            )
        },
        text = { b, d -> ShapeKind.inset(Rect(b.left + (b.w - d) / 4.0, b.top + b.h / 2.0, (b.w - d) / 2.0, b.h / 2.0)) },
    )

    val HEX_PRISM: ShapeKind = SolidKind(
        "solid_hex_prism", 0.8, flips = Flip.Y,
        knobAt = { b, d -> Pt(b.centerX, b.top + d) },
        depthFrom = { to, b -> to.y - b.top },
        draw = { b, d ->
            val ry = d / 2.0
            val rx = b.w / 2.0
            val top = Pt(b.centerX, b.top + ry)
            val bottom = Pt(b.centerX, b.bottom - ry)
            val t = (0 until 6).map { onEllipse(top, rx, ry, 60.0 * it) }
            val u = (0 until 6).map { onEllipse(bottom, rx, ry, 60.0 * it) }
            // Corners 0 to 3 run round the front (sines at or below zero in screen space are the back).
            listOf(
                poly(listOf(t[3], t[4], t[5], t[0], u[0], u[1], u[2], u[3]), PathRole.OUTLINE),
                poly(t, PathRole.SHADE_LIGHT),
                ShapePath(
                    listOf(
                        ShapePath.lines(listOf(t[0], t[1], t[2], t[3]), false),
                        ShapePath.lines(listOf(t[1], u[1]), false),
                        ShapePath.lines(listOf(t[2], u[2]), false),
                    ),
                    PathRole.STROKE_ONLY,
                ),
                ShapePath(
                    listOf(
                        ShapePath.lines(listOf(u[3], u[4], u[5], u[0]), false),
                        ShapePath.lines(listOf(t[4], u[4]), false),
                        ShapePath.lines(listOf(t[5], u[5]), false),
                    ),
                    PathRole.HIDDEN,
                ),
            )
        },
        text = { b, d -> ShapeKind.inset(Rect(b.left, b.top + d, b.w, b.h - 1.5 * d)) },
    )

    val FRUSTUM: ShapeKind = SolidKind(
        "solid_frustum", 1.0, flips = Flip.Y,
        knobAt = { b, d -> Pt(b.centerX, b.bottom - d) },
        depthFrom = { to, b -> b.bottom - to.y },
        draw = { b, d ->
            val ry = d / 2.0
            val rx = b.w / 2.0
            val tx = rx * 0.6
            val ty = ry * 0.6
            val top = Pt(b.centerX, b.top + ty)
            val bottom = Pt(b.centerX, b.bottom - ry)
            val body = VectorContour(
                Pt(top.x - tx, top.y),
                listOf(VectorSeg.Line(Pt(b.left, bottom.y))) + arcSegments(bottom, rx, ry, PI, -PI) +
                    VectorSeg.Line(Pt(top.x + tx, top.y)) + arcSegments(top, tx, ty, 0.0, -PI),
                closed = true,
            )
            listOf(
                ShapePath(listOf(body)),
                ShapePath(listOf(ShapePath.ellipseContour(top.x, top.y, tx, ty)), PathRole.SHADE_LIGHT),
                arc(top, tx, ty, 180.0, -180.0, PathRole.STROKE_ONLY),
                arc(bottom, rx, ry, 180.0, 180.0, PathRole.HIDDEN),
            )
        },
        text = { b, d -> ShapeKind.inset(Rect(b.left + b.w * 0.2, b.top + d, b.w * 0.6, b.h - 1.5 * d)) },
    )

    val HEMISPHERE: ShapeKind = SolidKind(
        "solid_hemisphere", 1.4, flips = Flip.Y,
        knobAt = { b, d -> Pt(b.centerX, b.bottom - d) },
        depthFrom = { to, b -> b.bottom - to.y },
        draw = { b, d ->
            val ry = d / 2.0
            val base = Pt(b.centerX, b.bottom - ry)
            val dome = VectorContour(
                Pt(b.left, base.y),
                arcSegments(base, b.w / 2.0, base.y - b.top, PI, PI) + arcSegments(base, b.w / 2.0, ry, 0.0, PI),
                closed = true,
            )
            listOf(ShapePath(listOf(dome)), arc(base, b.w / 2.0, ry, 180.0, 180.0, PathRole.HIDDEN))
        },
        text = { b, d -> ShapeKind.inset(Rect(b.left + b.w * 0.2, b.top + b.h * 0.3, b.w * 0.6, b.h * 0.7 - d)) },
    )

    // --- notation ---

    /** [count] ticks across the line [from]-[to], the first on [from] and the last on [to]. */
    private fun ticksEndToEnd(from: Pt, to: Pt, count: Int, half: Double): List<VectorContour> {
        if (count <= 0) return emptyList()
        val d = to - from
        val len = d.length()
        if (len < 1e-9) return emptyList()
        val n = d.perp() * (half / len)
        return (0 until count).map { i ->
            val at = if (count == 1) from + d * 0.5 else from + d * (i.toDouble() / (count - 1))
            ShapePath.lines(listOf(at - n, at + n), false)
        }
    }

    /**
     * Ticks from [o] back towards [end], spaced as the [count] ticks out to [ahead] are, as many as
     * fit short of [end] and never more than [count]: none when the axes cross near the corner.
     */
    private fun backTicks(o: Pt, end: Pt, ahead: Pt, count: Int, half: Double): List<VectorContour> {
        val step = o.distanceTo(ahead) / (count + 1)
        val room = o.distanceTo(end)
        if (step < 1e-9 || room < 1e-9) return emptyList()
        val fit = min(count, kotlin.math.floor(room / step - 1.0).toInt())
        return if (fit <= 0) emptyList() else ticks(o, o + (end - o) * ((fit + 1) * step / room), fit, half)
    }

    /** [count] ticks spread evenly between [from] and [to], neither end ticked. */
    private fun ticks(from: Pt, to: Pt, count: Int, half: Double): List<VectorContour> {
        if (count <= 0) return emptyList()
        val d = to - from
        val len = d.length()
        if (len < 1e-9) return emptyList()
        val n = d.perp() * (half / len)
        return (1..count).map { i ->
            val at = from + d * (i.toDouble() / (count + 1))
            ShapePath.lines(listOf(at - n, at + n), false)
        }
    }

    /** Where the axes cross: the knob's shares of the box from its left and its bottom. */
    private fun origin(b: Rect, adj: DoubleArray) =
        Pt(b.left + (adj.getOrNull(0) ?: 0.12).coerceIn(0.0, 0.8) * b.w, b.bottom - (adj.getOrNull(1) ?: 0.12).coerceIn(0.0, 0.8) * b.h)

    val AXES_2D: ShapeKind = NotationKind(
        "math_axes_2d", 1.0, defaultN = 5, nRange = 0..20, countKind = CountKind.TICKS, staysInBox = false,
        dragFlips = Flip.NONE,
        defaultAdj = doubleArrayOf(0.12, 0.12),
        knobs = listOf(
            KnobDef(
                position = { b, adj, _ -> origin(b, adj) },
                drag = { to, b, _, _ ->
                    doubleArrayOf(
                        if (b.w > 0.0) ((to.x - b.left) / b.w).coerceIn(0.0, 0.8) else 0.12,
                        if (b.h > 0.0) ((b.bottom - to.y) / b.h).coerceIn(0.0, 0.8) else 0.12,
                    )
                },
            ),
        ),
    ) { b, p ->
        val o = origin(b, p.adj)
        val tick = 0.025 * min(b.w, b.h)
        val head = Head.length(p.width)
        val yTip = Pt(o.x, b.top)
        val xTip = Pt(b.right, o.y)
        val yEnd = Pt(o.x, b.top + head)
        val xEnd = Pt(b.right - head, o.y)
        listOf(
            ShapePath(
                listOf(ShapePath.lines(listOf(Pt(o.x, b.bottom), yTip), false), ShapePath.lines(listOf(Pt(b.left, o.y), xTip), false)) +
                    ticks(o, yEnd, p.n, tick) + ticks(o, xEnd, p.n, tick) +
                    backTicks(o, Pt(o.x, b.bottom), yEnd, p.n, tick) + backTicks(o, Pt(b.left, o.y), xEnd, p.n, tick),
                PathRole.STROKE_ONLY,
            ),
        ) + Head.OPEN.paths(yTip, Pt(0.0, -1.0), p.width) + Head.OPEN.paths(xTip, Pt(1.0, 0.0), p.width)
    }

    val AXES_3D: ShapeKind = NotationKind("math_axes_3d", 1.0, staysInBox = false, dragFlips = Flip.NONE) { b, p ->
        val o = Pt(b.left + 0.35 * b.w, b.bottom - 0.35 * b.h)
        val tips = listOf(Pt(o.x, b.top), Pt(b.right, o.y), Pt(b.left, b.bottom))
        listOf(ShapePath(tips.map { ShapePath.lines(listOf(o, it), false) }, PathRole.STROKE_ONLY)) +
            tips.flatMap { tip -> Head.OPEN.paths(tip, (tip - o).normalized(), p.width) }
    }

    val NUMBER_LINE: ShapeKind = NotationKind(
        "math_number_line", 3.0, defaultN = 9, nRange = 2..41, countKind = CountKind.TICKS, staysInBox = false,
        flips = Flip.NONE,
    ) { b, p ->
        val y = b.centerY
        val inset = Head.length(p.width) * 1.5
        listOf(
            ShapePath(
                listOf(ShapePath.lines(listOf(Pt(b.left, y), Pt(b.right, y)), false)) +
                    ticksEndToEnd(Pt(b.left + inset, y), Pt(b.right - inset, y), p.n, 0.3 * b.h),
                PathRole.STROKE_ONLY,
            ),
        ) + Head.OPEN.paths(Pt(b.right, y), Pt(1.0, 0.0), p.width) + Head.OPEN.paths(Pt(b.left, y), Pt(-1.0, 0.0), p.width)
    }

    val GRID: ShapeKind = NotationKind(
        "math_grid", 1.0, closed = true, defaultN = 4, nRange = 1..24, countKind = CountKind.CELLS, flips = Flip.NONE,
    ) { b, p ->
        val n = p.n.coerceIn(1, 24)
        val inner = (1 until n).flatMap { i ->
            val x = b.left + b.w * i / n
            val y = b.top + b.h * i / n
            listOf(ShapePath.lines(listOf(Pt(x, b.top), Pt(x, b.bottom)), false), ShapePath.lines(listOf(Pt(b.left, y), Pt(b.right, y)), false))
        }
        listOf(ShapePath.rect(b), ShapePath(inner, PathRole.STROKE_ONLY))
    }

    val ANGLE: ShapeKind = NotationKind(
        "math_angle", 1.2,
        defaultAdj = doubleArrayOf(50.0),
        knobs = listOf(
            KnobDef(
                position = { b, adj, _ ->
                    val deg = (adj.getOrNull(0) ?: 50.0).coerceIn(5.0, 175.0)
                    vertex(b, deg) + ray(deg) * (0.75 * min(b.w, b.h))
                },
                drag = { to, b, adj, _ ->
                    // Measured from where the vertex sits now, so the drag does not jump as it slides.
                    val v = vertex(b, (adj.getOrNull(0) ?: 50.0).coerceIn(5.0, 175.0))
                    doubleArrayOf(Math.toDegrees(atan2(v.y - to.y, to.x - v.x)).coerceIn(5.0, 175.0))
                },
            ),
        ),
    ) { b, p ->
        val deg = (p.adj.getOrNull(0) ?: 50.0).coerceIn(5.0, 175.0)
        val v = vertex(b, deg)
        val dir = ray(deg)
        // The second ray runs out to wherever it meets the box: its top, or a side.
        val reach = listOfNotNull(
            if (dir.y < -1e-9) b.h / -dir.y else null,
            if (dir.x > 1e-9) (b.right - v.x) / dir.x else null,
            if (dir.x < -1e-9) (v.x - b.left) / -dir.x else null,
        ).filter { it > 1e-9 }.minOrNull() ?: b.h
        // The arc fits inside both rays, however short a slid vertex leaves the first.
        val r = minOf(0.3 * min(b.w, b.h), 0.6 * (b.right - v.x), 0.6 * reach)
        listOf(
            ShapePath(listOf(ShapePath.lines(listOf(Pt(b.right, b.bottom), v, v + dir * reach), false)), PathRole.STROKE_ONLY),
            ShapePath(listOf(VectorContour(Pt(v.x + r, v.y), arcSegments(v, r, r, 0.0, -Math.toRadians(deg)), false)), PathRole.STROKE_ONLY),
        )
    }

    val RIGHT_ANGLE: ShapeKind = NotationKind("math_right_angle", 1.0) { b, _ ->
        val s = 0.22 * min(b.w, b.h)
        listOf(
            ShapePath(
                listOf(
                    ShapePath.lines(listOf(Pt(b.right, b.bottom), Pt(b.left, b.bottom), b.topLeft), false),
                    ShapePath.lines(listOf(Pt(b.left + s, b.bottom), Pt(b.left + s, b.bottom - s), Pt(b.left, b.bottom - s)), false),
                ),
                PathRole.STROKE_ONLY,
            ),
        )
    }

    val CURLY_BRACE: ShapeKind = NotationKind("math_curly_brace", 0.4, flips = Flip.X) { b, _ ->
        fun at(x: Double, y: Double) = Pt(b.left + x * b.w, b.top + y * b.h)
        val c = VectorContour(
            at(1.0, 0.0),
            listOf(
                VectorSeg.Cubic(at(0.5, 0.0), at(0.5, 0.06), at(0.5, 0.12)),
                VectorSeg.Line(at(0.5, 0.38)),
                VectorSeg.Cubic(at(0.5, 0.45), at(0.25, 0.5), at(0.0, 0.5)),
                VectorSeg.Cubic(at(0.25, 0.5), at(0.5, 0.55), at(0.5, 0.62)),
                VectorSeg.Line(at(0.5, 0.88)),
                VectorSeg.Cubic(at(0.5, 0.94), at(0.5, 1.0), at(1.0, 1.0)),
            ),
            false,
        )
        listOf(ShapePath(listOf(c), PathRole.STROKE_ONLY))
    }

    val SQUARE_BRACKET: ShapeKind = NotationKind(
        "math_square_bracket", 0.4, flips = Flip.X,
        corners = { b -> listOf(Pt(b.right, b.top), b.topLeft, Pt(b.left, b.bottom), Pt(b.right, b.bottom)) },
    ) { b, _ ->
        listOf(ShapePath.polyline(listOf(Pt(b.right, b.top), b.topLeft, Pt(b.left, b.bottom), Pt(b.right, b.bottom))))
    }

    val ROUND_BRACKET: ShapeKind = NotationKind("math_round_bracket", 0.4, flips = Flip.X) { b, _ ->
        listOf(ShapePath(listOf(VectorContour(Pt(b.right, b.top), arcSegments(Pt(b.right, b.centerY), b.w, b.h / 2.0, -PI / 2.0, -PI), false)), PathRole.STROKE_ONLY))
    }

    /** Where an angle of [deg] puts its vertex: the bottom left, slid right just enough for an obtuse ray to fit. */
    private fun vertex(b: Rect, deg: Double): Pt {
        val d = ray(deg)
        val slide = if (d.x < 0.0) (b.h * -d.x / -d.y).coerceAtMost(b.w * 0.8) else 0.0
        return Pt(b.left + slide, b.bottom)
    }

    /** The unit direction of a ray [deg] degrees anticlockwise from the right, on screen. */
    private fun ray(deg: Double) = Pt(cos(Math.toRadians(deg)), -sin(Math.toRadians(deg)))

    val all: List<ShapeKind> = listOf(
        CUBE, CYLINDER, CONE, SPHERE, PYRAMID, TETRAHEDRON, TRI_PRISM, HEX_PRISM, FRUSTUM, HEMISPHERE,
        AXES_2D, AXES_3D, NUMBER_LINE, GRID, ANGLE, RIGHT_ANGLE, CURLY_BRACE, SQUARE_BRACKET, ROUND_BRACKET,
    )
}
