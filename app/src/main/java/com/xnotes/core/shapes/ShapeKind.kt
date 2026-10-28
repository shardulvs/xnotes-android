package com.xnotes.core.shapes

import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FillRule
import com.xnotes.core.vector.VectorContour
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A point a connector can glue to, and the ways out of the shape there (one per edge it sits on). */
class ConnectionPoint(val at: Pt, val normals: List<Pt>)

/**
 * Everything a kind builds from its box and parameters, in the shape's unturned frame: the paths
 * it draws, where its vertex dots go (none for a smooth shape), where connectors may glue, and
 * where a typed label wraps.
 */
class ShapeGeometry(
    val paths: List<ShapePath>,
    val corners: List<Pt> = emptyList(),
    val connections: List<ConnectionPoint> = emptyList(),
    val textRect: Rect,
) {
    /** This geometry mirrored by [flip] inside [box]; the text rect moves but stays a rect, so labels read as ever. */
    fun flipped(flip: Flip, box: Rect): ShapeGeometry {
        if (flip == Flip.NONE) return this
        val f = { p: Pt -> flip.map(p, box) }
        return ShapeGeometry(
            paths.map { it.mapped(f) },
            corners.map(f),
            connections.map { c -> ConnectionPoint(f(c.at), c.normals.map(flip::direction)) },
            flip.map(textRect, box),
        )
    }
}

/**
 * One of a kind's knobs (PowerPoint's yellow adjust handles): where it sits for the given box and
 * parameters, and the knob values a drag of it to a point makes. Both work in the unturned frame;
 * [drag] clamps to what the kind can draw. A knob with [reframe] sizes the shape itself instead:
 * a drag gives the new box, about the same centre, and leaves the knob values alone.
 */
class KnobDef(
    val position: (box: Rect, adj: DoubleArray, n: Int) -> Pt,
    val drag: (to: Pt, box: Rect, adj: DoubleArray, n: Int) -> DoubleArray,
    val reframe: ((to: Pt, box: Rect) -> Rect)? = null,
)

/** What a kind builds from beside its box: knob values, a count, a free outline and the line width. */
class ShapeParams(
    val adj: DoubleArray = DoubleArray(0),
    val n: Int = 0,
    /** A free kind's outline, normalized to the box; null for every other kind. */
    val path: List<VectorContour>? = null,
    /** The outline's width, which sizes any arrowheads a kind draws itself. */
    val width: Double = 2.0,
)

/** What a kind's minus/plus counts, which names it in the picker. */
enum class CountKind { SIDES, POINTS, TICKS, CELLS }

/**
 * A kind of shape: how it draws inside its box, and what it offers the editor. Every kind lives in
 * [all] and a file names it by [id], so a kind is never renamed. The nine kinds the first versions
 * wrote are [isLegacy]: those readers draw any other id as a rectangle, so the codecs hand them a
 * polygon stand-in instead.
 */
abstract class ShapeKind internal constructor(val id: String) {

    /** Closed kinds fill and stroke a ring; open kinds are only stroked. */
    abstract val isClosed: Boolean
    val isOpen: Boolean get() = !isClosed

    /** Line and arrow: two endpoints rather than a box. */
    open val isEndpointShape: Boolean get() = false

    /** Polygon, polyline and curve: the outline is stored with the shape rather than built. */
    open val isFree: Boolean get() = false

    /** Whether a selected shape of this kind shows dots its points can be dragged by. */
    open val pointEditable: Boolean get() = true

    /** Whether a shape of this kind takes a typed label: a closed one in its text area, an open one on its line. */
    open val takesLabel: Boolean get() = true

    /** Square and circle: a corner drag keeps them as wide as they are tall. */
    open val keepsAspect: Boolean get() = false

    /** The width-to-height its picker glyph is drawn at. */
    open val iconAspect: Double get() = 1.0

    /** The width-to-height the shape tool's Perfect switch draws it at: square, or its regular form's. */
    open fun perfectAspect(n: Int): Double = 1.0

    /** True when nothing the kind draws reaches outside its box, so the box bounds it. */
    open val staysInBox: Boolean get() = true

    open val defaultAdj: DoubleArray get() = NO_ADJ
    open val defaultN: Int get() = 0

    /** The point or side counts a minus/plus may set, for a star or a polygon; null for the rest. */
    open val nRange: IntRange? get() = null

    /** What the minus/plus counts: a polygon's sides, a star's points, an axis's ticks, a grid's cells. */
    open val countKind: CountKind get() = CountKind.SIDES

    /** The heads a new shape of this open kind starts with: an arrow ends in a chevron. */
    open val defaultHeadStart: Head get() = Head.NONE
    open val defaultHeadEnd: Head get() = Head.NONE

    /** The knobs a shape of this kind shows; none for most. */
    open val knobs: List<KnobDef> get() = emptyList()

    /**
     * The flips a shape of this kind carries: those that change how it draws for some knob or
     * count. None where it is symmetric both ways, and none for line, arrow and free outlines,
     * which mirror by moving their own points.
     */
    open val flips: Flip get() = Flip.XY

    /**
     * The flips a drag toward the left or upward gives a new shape, its chip being what a drag
     * down and right draws. Shapes with a natural upright (a heart, a star) keep it.
     */
    open val dragFlips: Flip get() = flips

    /** Knob value [i] out of [adj], falling back to the default for one a file left out. */
    fun adjOf(adj: DoubleArray, i: Int): Double = adj.getOrNull(i)?.takeIf { it.isFinite() } ?: defaultAdj[i]

    val isLegacy: Boolean get() = id in LEGACY_IDS

    /** The kind's geometry inside [box] (the unturned frame). Endpoint kinds draw from their ends instead. */
    abstract fun build(box: Rect, p: ShapeParams): ShapeGeometry

    override fun toString(): String = id

    companion object {
        private val NO_ADJ = DoubleArray(0)
        private val LEGACY_IDS = setOf("line", "arrow", "rectangle", "ellipse", "circle", "triangle", "polygon", "polyline", "curve")

        val LINE: ShapeKind = EndpointKind("line", Head.NONE)
        val ARROW: ShapeKind = EndpointKind("arrow", Head.OPEN)
        val RECTANGLE: ShapeKind = RectangleKind("rectangle", keepsAspect = false)
        val ELLIPSE: ShapeKind = EllipseKind("ellipse", keepsAspect = false)
        val CIRCLE: ShapeKind = EllipseKind("circle", keepsAspect = true)
        val TRIANGLE: ShapeKind = TriangleKind("triangle")
        val POLYGON: ShapeKind = FreeKind("polygon", closed = true)
        val POLYLINE: ShapeKind = FreeKind("polyline", closed = false)
        val CURVE: ShapeKind = FreeKind("curve", closed = false)

        val ROUNDED_RECT: ShapeKind = RoundedRectKind("rounded_rect", capsule = false)
        val SQUARE: ShapeKind = RectangleKind("square", keepsAspect = true)
        val CAPSULE: ShapeKind = RoundedRectKind("capsule", capsule = true)
        val RIGHT_TRIANGLE: ShapeKind = RightTriangleKind("right_triangle")
        val DIAMOND: ShapeKind = DiamondKind("diamond")
        val PARALLELOGRAM: ShapeKind = ParallelogramKind("parallelogram")
        val TRAPEZOID: ShapeKind = TrapezoidKind("trapezoid")
        val REGULAR_POLYGON: ShapeKind = RegularPolygonKind("regular_polygon")
        val STAR: ShapeKind = StarKind("star")
        val CROSS: ShapeKind = CrossKind("cross")
        val HEART: ShapeKind = HeartKind("heart")
        val CLOUD: ShapeKind = CloudKind("cloud")
        val RING: ShapeKind = RingKind("ring")
        val PIE: ShapeKind = SliceKind("pie", pie = true)
        val ARC: ShapeKind = SliceKind("arc", pie = false)

        /** A smooth curve through points a drag moves, adds or removes. */
        val SPLINE: ShapeKind = SplineKind("spline")

        /** The graph of a function, sampled into its own outline (breaking at poles). */
        val FUNCTION: ShapeKind = FunctionKind("function")

        /** A label an eraser left behind as drawn text: its letters as filled outlines. */
        val GLYPHS: ShapeKind = GlyphsKind("glyphs")

        /** A dropped colour's area: the rings it fills, never stroked. */
        val FILL: ShapeKind = FillKind("fill")

        /**
         * Every kind, in a stable order. Lazy, because the page families are objects whose own
         * kinds initialize this class: whichever loads first, the list is only read once both have.
         */
        val all: List<ShapeKind> by lazy {
            listOf(
                LINE, ARROW, RECTANGLE, ELLIPSE, CIRCLE, TRIANGLE, POLYGON, POLYLINE, CURVE,
                ROUNDED_RECT, SQUARE, CAPSULE, RIGHT_TRIANGLE, DIAMOND, PARALLELOGRAM, TRAPEZOID,
                REGULAR_POLYGON, STAR, CROSS, HEART, CLOUD, RING, PIE, ARC, GLYPHS, FUNCTION, SPLINE, FILL,
            ) + FlowchartKinds.all + ArrowKinds.all + SolidMathKinds.all
        }

        private val byId: Map<String, ShapeKind> by lazy { all.associateBy { it.id } }

        /** Kinds the shape tool offers; polygon/polyline/curve arrive only from recognition. */
        val DRAW_TOOL_KINDS = listOf(LINE, ARROW, RECTANGLE, ELLIPSE, CIRCLE, TRIANGLE)

        fun fromId(id: String?): ShapeKind = fromIdOrNull(id) ?: RECTANGLE

        /** The kind [id] names, or null for one this version does not know. */
        fun fromIdOrNull(id: String?): ShapeKind? = id?.let { byId[it] }

        /** A box's inner area a label wraps in: inset by a little of its shorter side. */
        internal fun inset(box: Rect, frac: Double = 0.06): Rect {
            val d = minOf(box.w, box.h) * frac
            return Rect(box.x + d, box.y + d, (box.w - 2 * d).coerceAtLeast(0.0), (box.h - 2 * d).coerceAtLeast(0.0))
        }

        /** Connection points at a closed polygon's corners and edge middles, with outward normals. */
        internal fun polygonConnections(corners: List<Pt>): List<ConnectionPoint> {
            val n = corners.size
            if (n < 3) return corners.map { ConnectionPoint(it, emptyList()) }
            val area = signedArea(corners)
            val normals = List(n) { i -> edgeNormal(corners[i], corners[(i + 1) % n], area) }
            val out = ArrayList<ConnectionPoint>(2 * n)
            for (i in 0 until n) out.add(ConnectionPoint(corners[i], listOf(normals[(i + n - 1) % n], normals[i])))
            for (i in 0 until n) {
                val a = corners[i]
                val b = corners[(i + 1) % n]
                out.add(ConnectionPoint(Pt((a.x + b.x) / 2.0, (a.y + b.y) / 2.0), listOf(normals[i])))
            }
            return out
        }

        /** The unit normal of edge [a]->[b] pointing out of a ring wound with signed [area]. */
        internal fun edgeNormal(a: Pt, b: Pt, area: Double): Pt {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < 1e-12) return Pt.ZERO
            // Screen space is y-down, so a positive shoelace area runs clockwise on screen.
            return if (area >= 0.0) Pt(dy / len, -dx / len) else Pt(-dy / len, dx / len)
        }

        internal fun signedArea(pts: List<Pt>): Double {
            var a = 0.0
            for (i in pts.indices) {
                val p = pts[i]
                val q = pts[(i + 1) % pts.size]
                a += p.x * q.y - q.x * p.y
            }
            return a / 2.0
        }
    }
}

/** Line and arrow: drawn from their two ends by [com.xnotes.core.model.ShapeItem], never from a box. */
private class EndpointKind(id: String, override val defaultHeadEnd: Head) : ShapeKind(id) {
    override val isClosed get() = false
    override val isEndpointShape get() = true
    override val staysInBox get() = false
    override val flips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry =
        ShapeGeometry(listOf(ShapePath.polyline(listOf(box.topLeft, Pt(box.right, box.bottom)))), textRect = box)
}

internal class RectangleKind(id: String, override val keepsAspect: Boolean) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = if (keepsAspect) 1.0 else 4.0 / 3.0
    override val flips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val corners = listOf(Pt(box.left, box.top), Pt(box.right, box.top), Pt(box.right, box.bottom), Pt(box.left, box.bottom))
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, polygonConnections(corners), inset(box))
    }
}

private class EllipseKind(id: String, override val keepsAspect: Boolean) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = if (keepsAspect) 1.0 else 1.5
    override val flips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val cx = box.centerX
        val cy = box.centerY
        val rx = box.w / 2.0
        val ry = box.h / 2.0
        val connections = ellipseConnections(box)
        // The biggest box inside an ellipse is the one inscribed at 45 degrees.
        val k = 1.0 / sqrt(2.0)
        val text = Rect(cx - rx * k, cy - ry * k, 2 * rx * k, 2 * ry * k)
        return ShapeGeometry(listOf(ShapePath.ellipse(cx, cy, rx, ry)), emptyList(), connections, text)
    }
}

/** Apex on the top edge, at the fraction of its width the one knob sets (the middle by default); base along the bottom. */
private class TriangleKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = 1.2
    override val defaultAdj = doubleArrayOf(0.5)
    override val knobs = listOf(
        KnobDef(
            position = { box, adj, _ -> Pt(box.left + box.w * adjOf(adj, 0), box.top) },
            drag = { to, box, _, _ -> doubleArrayOf(if (box.w > 0.0) ((to.x - box.left) / box.w).coerceIn(0.0, 1.0) else 0.5) },
        ),
    )
    // Left to right it only changes once the knob slides the apex off centre.
    override val dragFlips get() = Flip.Y
    // Equilateral.
    override fun perfectAspect(n: Int) = 2.0 / sqrt(3.0)
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val apex = Pt(box.left + box.w * adjOf(p.adj, 0), box.top)
        val corners = listOf(apex, Pt(box.right, box.bottom), Pt(box.left, box.bottom))
        // The widest box a triangle holds is half its width and half its height, on the base.
        val text = Rect(box.x + box.w / 4.0, box.y + box.h / 2.0, box.w / 2.0, box.h / 2.0)
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, polygonConnections(corners), text)
    }
}

/** Letter outlines stored like a free outline, filled and never stroked; too many points to drag one by one. */
private class GlyphsKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val isFree get() = true
    override val pointEditable get() = false
    override val takesLabel get() = false
    override val flips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry =
        ShapeGeometry(listOf(ShapePath(FreePath.place(p.path.orEmpty(), box), PathRole.FILL_ONLY, FillRule.NONZERO)), textRect = box)
}

/** A dropped colour's rings stored like a free outline, filled by parity so holes stay open, and never stroked. */
private class FillKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val isFree get() = true
    override val pointEditable get() = false
    override val takesLabel get() = false
    override val flips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry =
        ShapeGeometry(listOf(ShapePath(FreePath.place(p.path.orEmpty(), box), PathRole.FILL_ONLY, FillRule.EVEN_ODD)), textRect = box)
}

/** Polygon, polyline, curve: the shape's own stored outline, placed in its box. */
private class FreeKind(id: String, private val closed: Boolean) : ShapeKind(id) {
    override val isClosed get() = closed
    override val isFree get() = true
    override val flips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val contours = FreePath.place(p.path.orEmpty(), box)
        val role = if (closed) PathRole.OUTLINE else PathRole.STROKE_ONLY
        val corners = contours.flatMap { FreePath.vertices(it) }
        val connections = if (closed && contours.size == 1) {
            polygonConnections(FreePath.vertices(contours[0]))
        } else {
            corners.map { ConnectionPoint(it, emptyList()) }
        }
        return ShapeGeometry(listOf(ShapePath(contours, role)), corners, connections, inset(box))
    }
}

/** A plotted function: the stored runs of its graph, only stroked, and replotted rather than point-edited. */
private class FunctionKind(id: String) : ShapeKind(id) {
    override val isClosed get() = false
    override val isFree get() = true
    override val pointEditable get() = false
    override val takesLabel get() = false
    override val flips get() = Flip.NONE
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry =
        ShapeGeometry(listOf(ShapePath(FreePath.place(p.path.orEmpty(), box), PathRole.STROKE_ONLY)), textRect = box)
}
