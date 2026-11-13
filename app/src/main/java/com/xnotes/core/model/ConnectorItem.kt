package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Mark
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.shapes.ConnectionPoint
import com.xnotes.core.shapes.ConnectorPaths
import com.xnotes.core.shapes.Elbow
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.PathRole
import com.xnotes.core.shapes.ShapePainter
import com.xnotes.core.shapes.ShapePath
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.ceil
import kotlin.math.max

/** How a connector runs between its two ends. */
enum class Routing(val id: String) {
    /** A straight line, bending only at the waypoints put on it. */
    STRAIGHT("straight"),

    /** Square corners, every segment along a page axis. */
    ELBOW("elbow"),

    /** An elbow with its corners rounded off. */
    ROUNDED("rounded"),

    /** A smooth curve that an elbow route guides. */
    CURVED("curved");

    /** True for the routings that run along the page axes, which the router plans. */
    val orthogonal: Boolean get() = this != STRAIGHT

    companion object {
        fun fromId(id: String?): Routing? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One end of a connector: where it sits, and the item and connection point it is glued to, if any.
 * The place is kept while glued too, so the connector draws without asking its target and goes loose
 * right where it was when the target goes. Immutable.
 */
class ConnectorEnd(val at: Pt, val target: CanvasItem? = null, val point: Int = -1) {

    val glued: Boolean get() = target != null

    /** This end moved to [p], still glued to whatever it was. */
    fun at(p: Pt) = ConnectorEnd(p, target, point)

    /** This end let go, where it is. */
    fun loose() = ConnectorEnd(at)

    override fun equals(other: Any?): Boolean =
        other is ConnectorEnd && other.at == at && other.target === target && other.point == point

    override fun hashCode(): Int = at.hashCode()
}

/**
 * A line that joins two items, or two places, and stays joined: each end is glued to a connection
 * point on a shape, a text box, an image or another connector, or left loose where it was put. A
 * straight connector runs through its [waypoints]; the others follow an [Elbow] of square corners,
 * drawn square, rounded or as a curve. It is styled, painted, hit and erased like an open shape, heads
 * included, and its typed label sits on the line, which breaks to make room for it.
 */
class ConnectorItem(
    var routing: Routing,
    var start: ConnectorEnd,
    var end: ConnectorEnd,
    var strokeRgba: Rgba,
    var strokeWidth: Double = 3.0,
    var headStart: Head = Head.NONE,
    var headEnd: Head = Head.NONE,
    var dashed: Boolean = false,
    var dashLength: Double = 10.0,
    var dashGap: Double = 8.0,
    var neon: Boolean = false,
    var neonStrength: Double = 0.6,
) : CanvasItem, Labelled {

    override val kind = KIND
    override val resizable = true
    override var locked = false
    override var group: Int? = null
    override var sync: SyncMark? = null

    /** A straight connector's bends between its ends, in order; the other routings ignore them. */
    var waypoints: List<Pt> = emptyList()

    /** An orthogonal connector's route; a straight one ignores it. */
    var elbow: Elbow = Elbow.PLAIN

    /** The typed label, upright on the line; null for none. */
    override var label: ShapeLabel? = null

    /** True while the label is being typed: the editor shows it, so the connector draws without. Never saved. */
    @Volatile
    override var labelHidden = false

    /** Where along the line the label sits, as a share of its length from the start. */
    var labelAt: Double = 0.5

    /** The points the line runs through: its two ends and every bend between, repeats dropped. */
    fun route(): List<Pt> {
        val out = if (routing.orthogonal) {
            Elbow.corners(elbow.points(start.at, end.at)).toMutableList()
        } else {
            ArrayList<Pt>(waypoints.size + 2).also { out ->
                for (p in listOf(start.at) + waypoints + end.at) if (out.isEmpty() || out.last().distanceTo(p) > 1e-9) out += p
            }
        }
        if (out.size == 1) out += out[0]
        return out
    }

    /** The line itself, before heads trim it or the label breaks it. */
    fun body(): VectorContour {
        val pts = route()
        return when (routing) {
            Routing.STRAIGHT, Routing.ELBOW -> ConnectorPaths.straight(pts)
            Routing.ROUNDED -> ConnectorPaths.rounded(pts, max(MIN_CORNER, strokeWidth * CORNER_PER_WIDTH))
            Routing.CURVED -> ConnectorPaths.curved(pts)
        }
    }

    /** How far the line runs straight out of a box before it may turn: room for a head and then some. */
    val clearance: Double get() = Head.length(strokeWidth) + strokeWidth * 2.0

    /** The pen the line draws with, before a head's role adjusts it. */
    internal fun pen() =
        Pen(color = strokeRgba, width = strokeWidth, cosmetic = false, dashed = dashed, dashOn = dashLength, dashGap = dashGap)

    /** The line and its heads, whole. */
    private fun drawn(): List<ShapePath> =
        Head.attach(listOf(ShapePath(listOf(body()), PathRole.STROKE_ONLY)), headStart, headEnd, strokeWidth)

    /** What the note, the canvas and the PDF draw: the line with its heads, broken where the label sits. */
    fun paths(): List<ShapePath> {
        val drawn = drawn()
        val gap = labelGap() ?: return drawn
        return drawn.map { p ->
            if (p.role != PathRole.STROKE_ONLY) p else ShapePath(p.contours.flatMap { ConnectorPaths.clipOut(it, gap) }, p.role, p.fillRule)
        }
    }

    override fun paint(r: Renderer) {
        paintBody(r)
        paintLabel(r)
    }

    /** Everything but the label, glowing when neon. */
    fun paintBody(r: Renderer) {
        val paths = paths()
        if (neon) return Outline.paintNeon(r, paths, null, pen(), neonStrength, paintBounds())
        ShapePainter.fill(r, paths, null, strokeRgba)
        ShapePainter.stroke(r, paths, pen())
    }

    /** The label, upright where it sits on the line, as text a PDF reader can select. */
    fun paintLabel(r: Renderer) {
        if (labelHidden) return
        val l = label ?: return
        val rect = labelRect() ?: return
        if (r.writesText) r.beginMark(Mark.TextBox(rect))
        r.drawText(l.text, rect, l.font, l.style.rgba, ShapeLabel.FLAGS)
        if (r.writesText) r.endMark()
    }

    /**
     * Where the label's lines set: upright, centred on the line [labelAt] of the way along, as wide as
     * its longest line (between [ShapeLabel.MIN_WIDTH_LINES] and [MAX_WIDTH_LINES] lines' height, past
     * which it wraps) and as tall as it wraps. Null without a label.
     */
    override fun labelRect(): Rect? = label?.takeIf { it.text.isNotEmpty() }?.let(::frameOf)

    /** [labelRect] even while the label is empty: where the editor types it. */
    override fun labelFrame(): Rect? = label?.let(::frameOf)

    override val takesLabel: Boolean get() = true

    override fun labelToPage(p: Pt): Pt = p

    override val labelTurn: Double get() = 0.0

    override val labelSlides: Boolean get() = true

    override fun slideLabel(p: Pt) {
        labelAt = ConnectorPaths.shareNear(body(), p)
    }

    override val labelInk: Rgba get() = strokeRgba

    override val labelBackdrop: Rgba? get() = null

    private fun frameOf(l: ShapeLabel): Rect {
        val w = (ceil(l.naturalWidth) + LABEL_SLACK).coerceIn(l.lineHeight * ShapeLabel.MIN_WIDTH_LINES, l.lineHeight * MAX_WIDTH_LINES)
        val h = l.height(w)
        val c = ConnectorPaths.pointAt(body(), labelAt)
        return Rect(c.x - w / 2.0, c.y - h / 2.0, w, h)
    }

    /** The gap the line leaves around the label, also while it is typed; null without one. */
    private fun labelGap(): Rect? {
        val l = label ?: return null
        val r = (if (labelHidden) labelFrame() else labelRect()) ?: return null
        return r.outset(l.lineHeight * GAP_LINES)
    }

    /** What a note keeps of the label when the area eraser cuts the connector: a text box where the words were. */
    fun labelAsTextBox(): TextItem? = labelRect()?.let { r -> label?.asTextBox(r) { it } }

    /** What a canvas keeps of it: the letters as drawn text where they were. */
    fun labelAsGlyphs(): ShapeItem? = labelRect()?.let { r -> label?.asGlyphs(r, { it }, 0.0) }

    /** True when page point [p] lands on the label's lines. */
    override fun labelContains(p: Pt): Boolean = labelRect()?.contains(p) == true

    override fun bounds(): Rect {
        val pts = drawn().flatMap { p -> p.contours.flatMap { PathFlattener.flatten(it, BOUNDS_FLATTEN) } }
        val own = (if (pts.isEmpty()) Rect(start.at.x, start.at.y, 0.0, 0.0) else Rect.bounding(pts)).outset(strokeWidth / 2.0 + 1.0)
        return labelRect()?.let { own.union(it) } ?: own
    }

    override fun paintBounds(): Rect =
        if (neon) bounds().outset(Outline.glowRadius(strokeWidth, neonStrength) * 2.0 + 4.0) else bounds()

    override fun translate(dx: Double, dy: Double) {
        start = start.at(Pt(start.at.x + dx, start.at.y + dy))
        end = end.at(Pt(end.at.x + dx, end.at.y + dy))
        waypoints = waypoints.map { Pt(it.x + dx, it.y + dy) }
        elbow = elbow.translated(dx, dy)
    }

    override fun contains(p: Pt): Boolean = touches(p, ShapeItem.HIT_TOLERANCE)

    /** True when page point [p] lands on the line within [tolerance] (or half its width, if wider), a head, or the label. */
    fun touches(p: Pt, tolerance: Double): Boolean =
        Outline.hits(paths(), p, max(strokeWidth / 2.0, tolerance), filled = false) || labelContains(p)

    override fun centroid(): Pt = bounds().center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        val c = Pt(cx, cy)
        if (bounds().distanceTo(c) > radius) return false
        return Outline.hits(paths(), c, radius + strokeWidth / 2.0, filled = false)
    }

    /**
     * AREA-erase, as for a shape: null when untouched, else the line's runs left outside the circle as
     * pen strokes in its colour and width. A cut connector stops being one; its glue goes with it.
     */
    fun erasedBy(cx: Double, cy: Double, radius: Double): List<CanvasItem>? {
        val c = Pt(cx, cy)
        if (bounds().distanceTo(c) > radius) return null
        val (hit, ink) = Outline.cut(paths(), pen(), neon, neonStrength, c, radius) ?: return null
        return if (hit) ink else null
    }

    /**
     * Where another connector may glue to this one: the middle of each stretch of line and each bend
     * between, offering the ways out to either side of a stretch and away from both sides of a bend.
     * A rounded corner offers its arc's middle instead of its bend.
     */
    fun connectionPoints(): List<ConnectionPoint> {
        val b = body()
        val out = ArrayList<ConnectionPoint>(2 * b.segments.size)
        var from = b.start
        for ((i, seg) in b.segments.withIndex()) {
            val (mid, dir) = midOf(from, seg)
            if (dir.length() > 1e-9) out += ConnectionPoint(mid, listOf(dir.perp(), dir.perp() * -1.0))
            val next = b.segments.getOrNull(i + 1)
            if (next != null && routing != Routing.ROUNDED) {
                val u = exitOf(from, seg)
                val v = entryOf(seg.end, next)
                if (u.length() > 1e-9 && v.length() > 1e-9) {
                    val straight = u.x * v.x + u.y * v.y > COS_STRAIGHT
                    out += ConnectionPoint(seg.end, if (straight) listOf(u.perp(), u.perp() * -1.0) else listOf(u, v * -1.0))
                }
            }
            from = seg.end
        }
        return out
    }

    // --- editing ---

    /** The handle on each middle segment of a square route, by segment from the first middle one; none for a straight connector. */
    fun segmentHandles(): List<Pt> {
        if (!routing.orthogonal) return emptyList()
        val pts = elbow.points(start.at, end.at)
        val gap = labelGap()
        return (1..elbow.coords.size).map { i ->
            val mid = (pts[i] + pts[i + 1]) * 0.5
            // A handle the label covers moves to where the segment shows, so both stay in reach.
            if (gap == null) mid else ConnectorPaths.clearOf(pts[i], pts[i + 1], mid, gap)
        }
    }

    /** Slide middle segment [segment] (1 to the coordinate count) across to where [to] is. */
    fun slideSegment(segment: Int, to: Pt) {
        elbow = elbow.withCoord(segment, elbow.axisOf(segment).across(to))
    }

    /** The middle of each stretch of a straight connector, where a drag adds a waypoint; none for a square route. */
    fun midpointDots(): List<Pt> {
        if (routing.orthogonal) return emptyList()
        val pts = listOf(start.at) + waypoints + end.at
        val gap = labelGap()
        return (0 until pts.size - 1).map {
            val mid = (pts[it] + pts[it + 1]) * 0.5
            if (gap == null) mid else ConnectorPaths.clearOf(pts[it], pts[it + 1], mid, gap)
        }
    }

    /** Add a waypoint at [to], [index]th among them. */
    fun insertWaypoint(index: Int, to: Pt) {
        waypoints = waypoints.toMutableList().also { it.add(index.coerceIn(0, it.size), to) }
    }

    fun moveWaypoint(index: Int, to: Pt) {
        if (index in waypoints.indices) waypoints = waypoints.toMutableList().also { it[index] = to }
    }

    fun removeWaypoint(index: Int) {
        if (index in waypoints.indices) waypoints = waypoints.toMutableList().also { it.removeAt(index) }
    }

    /** Let go of both ends where they are. */
    fun letGo() {
        start = start.loose()
        end = end.loose()
    }

    override fun snapshotGeometry(): GeometrySnapshot = ConnectorSnapshot(start, end, waypoints, elbow, labelAt)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is ConnectorSnapshot) return
        start = snap.start
        end = snap.end
        waypoints = snap.waypoints
        elbow = snap.elbow
        labelAt = snap.labelAt
    }

    /**
     * Bake a transform in: the ends and a straight connector's waypoints map exactly, and an orthogonal
     * route stays square to the page ([Elbow.transformed]). Glue is kept; whoever moves the connector
     * puts glued ends back on targets that stayed. The line keeps its width and the label its size.
     */
    override fun applyTransform(t: Affine) {
        elbow = elbow.transformed(t, start.at, end.at)
        waypoints = waypoints.map(t::apply)
        start = start.at(t.apply(start.at))
        end = end.at(t.apply(end.at))
    }

    companion object {
        const val KIND = "connector"

        /** A rounded corner's radius: so many line widths, never under the minimum. */
        private const val CORNER_PER_WIDTH = 4.0
        private const val MIN_CORNER = 10.0

        /** The widest a label sets before it wraps, in lines of its own height. */
        const val MAX_WIDTH_LINES = 16.0

        /** Room past the label's longest line, so rounding never wraps it, in content px. */
        private const val LABEL_SLACK = 2.0

        /** The gap the line leaves around its label, in lines of the label's height. */
        private const val GAP_LINES = 0.2

        private const val BOUNDS_FLATTEN = 0.05

        /** Two stretches meeting closer than this to straight (as a cosine) make no bend. */
        private const val COS_STRAIGHT = 0.9998

        private fun midOf(from: Pt, seg: VectorSeg): Pair<Pt, Pt> = when (seg) {
            is VectorSeg.Line -> (from + seg.end) * 0.5 to (seg.end - from).normalized()
            is VectorSeg.Cubic -> {
                val mid = (from + seg.c1 * 3.0 + seg.c2 * 3.0 + seg.end) * 0.125
                mid to ((seg.end + seg.c2 - seg.c1 - from) * 0.75).normalized()
            }
        }

        /** The way [seg] is heading where it ends. */
        private fun exitOf(from: Pt, seg: VectorSeg): Pt = when (seg) {
            is VectorSeg.Line -> (seg.end - from).normalized()
            is VectorSeg.Cubic -> (seg.end - (listOf(seg.c2, seg.c1).firstOrNull { it.distanceTo(seg.end) > 1e-9 } ?: from)).normalized()
        }

        /** The way [seg], starting at [at], sets off. */
        private fun entryOf(at: Pt, seg: VectorSeg): Pt = when (seg) {
            is VectorSeg.Line -> (seg.end - at).normalized()
            is VectorSeg.Cubic -> ((listOf(seg.c1, seg.c2).firstOrNull { it.distanceTo(at) > 1e-9 } ?: seg.end) - at).normalized()
        }
    }
}

/** Snapshot of a connector's geometry: its ends with their glue, its route and where its label sits. */
private class ConnectorSnapshot(
    val start: ConnectorEnd,
    val end: ConnectorEnd,
    val waypoints: List<Pt>,
    val elbow: Elbow,
    val labelAt: Double,
) : GeometrySnapshot {
    // Value equality, so a drag that changed nothing records nothing.
    override fun equals(other: Any?): Boolean = other is ConnectorSnapshot && start == other.start && end == other.end &&
        waypoints == other.waypoints && elbow == other.elbow && labelAt == other.labelAt

    override fun hashCode(): Int = start.hashCode() * 31 + end.hashCode()
}
