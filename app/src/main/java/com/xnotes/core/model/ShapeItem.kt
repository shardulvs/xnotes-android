package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Mark
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.shapes.ConnectionPoint
import com.xnotes.core.shapes.ConnectorPaths
import com.xnotes.core.shapes.EditDot
import com.xnotes.core.shapes.FreePath
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.PathRole
import com.xnotes.core.shapes.PathSpot
import com.xnotes.core.shapes.ShapeGeometry
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.shapes.ShapePainter
import com.xnotes.core.shapes.ShapeParams
import com.xnotes.core.shapes.ShapePath
import com.xnotes.core.shapes.SplineKind
import com.xnotes.core.shapes.VertexEdit
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An editable geometric shape (spec 02 §5.4). Its [shape] kind builds the outline inside the box
 * spanned by [start]/[end], [flip] mirrors it there, and [rotation] turns the result about the box
 * centre, so a turned or mirrored shape keeps its kind and its own frame. Line and arrow are drawn
 * from their two ends instead; polygon, polyline and curve carry their own outline in [path].
 * Closed shapes are stroked and optionally filled. Shapes erase like ink: whole on contact in
 * STROKE mode, and cut into outline fragments by the AREA eraser.
 */
class ShapeItem(
    var shape: ShapeKind,
    var start: Pt,
    var end: Pt,
    var strokeRgba: Rgba,
    var strokeWidth: Double = 3.0,
    var fillRgba: Rgba? = null,
    /** Render the outline as a glowing neon tube (halo + white-hot core). */
    var neon: Boolean = false,
    /** Glow intensity in [0, 1] (halo size + brightness); used only when [neon]. */
    var neonStrength: Double = 0.6,
    /** A free kind's outline, normalized to [box] so it reshapes with it; null for every other kind. */
    var path: List<VectorContour>? = null,
    /** Stroke the outline dashed (dash/gap run lengths in content px). */
    var dashed: Boolean = false,
    var dashLength: Double = 10.0,
    var dashGap: Double = 8.0,
    /** Turn about the box centre in radians, clockwise on screen; always zero for line/arrow. */
    var rotation: Double = 0.0,
    /** Knob values, as the kind defines them. */
    var adj: DoubleArray = shape.defaultAdj.copyOf(),
    /** A star's points or a polygon's sides; 0 for kinds without. */
    var n: Int = shape.defaultN,
    /** What an open shape's two ends carry; an arrow starts with a chevron at its end. */
    var headStart: Head = shape.defaultHeadStart,
    var headEnd: Head = shape.defaultHeadEnd,
) : CanvasItem, Resizable, Labelled {

    override val kind = KIND
    override val resizable = true
    override var locked = false
    override var link: String? = null
    override var group: Int? = null
    override var sync: SyncMark? = null

    /** What a [ShapeKind.FUNCTION] plots; its sampled curve lives in [path]. */
    var function: FunctionSpec? = null

    init {
        // A graph always plots something, so it can be drawn, hit and written like any free shape.
        if (shape == ShapeKind.FUNCTION && path == null) setFunction(FunctionSpec.PRESETS.first())
        if (shape == ShapeKind.SPLINE && path == null) path = SplineKind.DEFAULT
    }

    /** Drawn text is words, which a mirror would turn backwards; a graph is its formula, which a mirror would not follow. */
    override val mirrorable: Boolean get() = shape != ShapeKind.GLYPHS && shape != ShapeKind.FUNCTION

    /** How the kind's drawing is mirrored inside the box, before [rotation] turns it; always none for line, arrow and free outlines. */
    var flip: Flip = Flip.NONE

    /** The typed label: centred in a closed kind's text rect and turning with it, or upright on an open kind's line; null for none. */
    override var label: ShapeLabel? = null

    /** True while the label is being typed: the editor shows it, so the shape draws without. Never saved. */
    @Volatile
    override var labelHidden = false

    /** Where along an open shape's line its label sits, as a share of the line's length. */
    var labelAt: Double = 0.5

    /** Normalized AABB of the two drag points: the shape's frame before [rotation] turns it. */
    val box: Rect get() = Rect.fromPoints(start, end)

    /** True for the kinds that hold their outline in [path] rather than drawing it from the box. */
    val isFree: Boolean get() = shape.isFree

    /** True for a dropped colour's area: only fill, which an eraser takes whole and nothing glues to. */
    val isFill: Boolean get() = shape == ShapeKind.FILL

    /** [p] from the shape's unturned frame onto the page: turned by [rotation] about the box centre. */
    fun toPage(p: Pt): Pt {
        if (rotation == 0.0) return p
        val c = box.center
        val cs = cos(rotation)
        val sn = sin(rotation)
        val dx = p.x - c.x
        val dy = p.y - c.y
        return Pt(c.x + dx * cs - dy * sn, c.y + dx * sn + dy * cs)
    }

    /** [p] from the page into the shape's unturned frame; the inverse of [toPage]. */
    fun toLocal(p: Pt): Pt {
        if (rotation == 0.0) return p
        val c = box.center
        val cs = cos(rotation)
        val sn = sin(rotation)
        val dx = p.x - c.x
        val dy = p.y - c.y
        return Pt(c.x + dx * cs + dy * sn, c.y - dx * sn + dy * cs)
    }

    /** A free kind's vertices on the page (every contour's start and segment ends); null for the rest. */
    fun vertices(): List<Pt>? {
        if (!isFree) return null
        val contours = path ?: return null
        return FreePath.place(contours, box).flatMap { FreePath.vertices(it) }.map(::toPage)
    }

    /** The outline pen the shape's style describes, before any role adjusts it. */
    internal fun pen() =
        Pen(color = strokeRgba, width = strokeWidth, cosmetic = false, dashed = dashed, dashOn = dashLength, dashGap = dashGap)

    /**
     * The kind's geometry in the shape's unturned frame (map it with [toPage]): its paths, vertex
     * dots, glue points and label area. A line or arrow is built from its two ends.
     */
    fun localGeometry(): ShapeGeometry {
        val g = if (shape.isEndpointShape) {
            ShapeGeometry(listOf(ShapePath.polyline(listOf(start, end))), textRect = Rect.fromPoints(start, end))
        } else {
            kindGeometry()
        }
        if (shape.isClosed || !hasHeads()) return g
        return ShapeGeometry(Head.attach(g.paths, headStart, headEnd, strokeWidth), g.corners, g.connections, g.textRect)
    }

    /** What the kind builds in the box, mirrored by [flip]; the unturned frame, no heads. */
    private fun kindGeometry(): ShapeGeometry = shape.build(box, ShapeParams(adj, n, path, strokeWidth)).flipped(flip, box)

    /** True when either end of an open shape carries a head. */
    fun hasHeads(): Boolean = shape.isOpen && (headStart != Head.NONE || headEnd != Head.NONE)

    internal fun ellipsePolygon(segments: Int = 48): List<Pt> {
        val b = box
        val cx = b.centerX
        val cy = b.centerY
        val rx = b.w / 2.0
        val ry = b.h / 2.0
        return (0 until segments).map {
            val a = 2.0 * PI * it / segments
            Pt(cx + rx * cos(a), cy + ry * sin(a))
        }
    }

    /**
     * The shape's geometry as paths in page space: the one outline the note, the canvas and the PDF
     * all draw. Open kinds are stroke-only, and an arrow's chevron is a [PathRole.HEAD] so it stays
     * solid on a dashed arrow.
     */
    fun paths(): List<ShapePath> {
        val local = localPaths()
        return if (rotation == 0.0) local else local.map { it.mapped(::toPage) }
    }

    /** The kind's paths in the unturned frame, an open shape's line broken round its label. */
    private fun localPaths(): List<ShapePath> {
        val paths = localGeometry().paths
        if (shape.isClosed) return paths
        val l = label ?: return paths
        val r = (if (labelHidden) labelFrame() else labelRect()) ?: return paths
        val gap = r.outset(l.lineHeight * LABEL_GAP_LINES)
        return paths.map { p ->
            if (p.role != PathRole.STROKE_ONLY) p
            else ShapePath(p.contours.flatMap { if (it.closed) listOf(it) else ConnectorPaths.clipOut(it, gap) }, p.role, p.fillRule)
        }
    }

    /** The line an open shape's label rides: its first stroked run, in the unturned frame. */
    private fun labelLine(): VectorContour? =
        localGeometry().paths.firstOrNull { it.role == PathRole.STROKE_ONLY }?.contours?.firstOrNull { it.segments.isNotEmpty() }

    override fun paint(r: Renderer) {
        paintBody(r)
        paintLabel(r)
    }

    /** Everything but the label: fills, then the outline, glowing when neon. */
    fun paintBody(r: Renderer) {
        val paths = paths()
        if (neon) return Outline.paintNeon(r, paths, fillRgba, pen(), neonStrength, paintBounds())
        ShapePainter.fill(r, paths, fillRgba, strokeRgba)
        ShapePainter.stroke(r, paths, pen())
    }

    /** The label set in [labelRect] and turned with the shape, as text a PDF reader can select. */
    fun paintLabel(r: Renderer) {
        if (labelHidden) return
        val l = label ?: return
        val rect = labelRect() ?: return
        val c = toPage(rect.center)
        val placed = Rect(c.x - rect.w / 2.0, c.y - rect.h / 2.0, rect.w, rect.h)
        if (r.writesText) r.beginMark(Mark.TextBox(Rect.bounding(labelCorners())))
        r.drawTextTurned(l.text, placed, l.font, l.style.rgba, ShapeLabel.FLAGS, rotation)
        if (r.writesText) r.endMark()
    }

    /**
     * Where the label's lines set, in the unturned frame: as wide as the kind's text rect (never
     * under [ShapeLabel.MIN_WIDTH_LINES] lines' height), as tall as they wrap, centred on the rect
     * and spilling past it evenly when long. Null without a label.
     */
    override fun labelRect(): Rect? = label?.takeIf { it.text.isNotEmpty() }?.let(::frameOf)

    /** [labelRect] even while the label is empty, one line tall: where the editor types it. */
    override fun labelFrame(): Rect? = label?.let(::frameOf)

    private fun frameOf(l: ShapeLabel): Rect {
        // An open shape's label sits on its line, as wide as its longest line, as a connector's does.
        if (shape.isOpen) {
            labelLine()?.let { line ->
                val w = (ceil(l.naturalWidth) + LABEL_SLACK).coerceIn(l.lineHeight * ShapeLabel.MIN_WIDTH_LINES, l.lineHeight * ConnectorItem.MAX_WIDTH_LINES)
                val h = l.height(w)
                val c = ConnectorPaths.pointAt(line, labelAt)
                return Rect(c.x - w / 2.0, c.y - h / 2.0, w, h)
            }
        }
        val t = localGeometry().textRect
        val w = max(t.w, l.lineHeight * ShapeLabel.MIN_WIDTH_LINES)
        val h = l.height(w)
        return Rect(t.centerX - w / 2.0, t.centerY - h / 2.0, w, h)
    }

    /** Where a connector may glue, on the page, each with its ways out turned with the shape; none for a line or arrow. */
    fun connectionPoints(): List<ConnectionPoint> {
        if (shape.isEndpointShape) return emptyList()
        val local = localGeometry().connections
        if (rotation == 0.0) return local
        val cs = cos(rotation)
        val sn = sin(rotation)
        return local.map { cp -> ConnectionPoint(toPage(cp.at), cp.normals.map { n -> Pt(n.x * cs - n.y * sn, n.x * sn + n.y * cs) }) }
    }

    override val takesLabel: Boolean get() = shape.takesLabel

    override fun labelToPage(p: Pt): Pt = toPage(p)

    override val labelTurn: Double get() = rotation

    override val labelSlides: Boolean get() = shape.isOpen

    override fun slideLabel(p: Pt) {
        val line = labelLine() ?: return
        labelAt = ConnectorPaths.shareNear(line, toLocal(p))
    }

    override val labelInk: Rgba get() = strokeRgba

    override val labelBackdrop: Rgba? get() = fillRgba

    /**
     * What a note keeps of the label when the area eraser cuts the shape: a text box centred where
     * the words were, upright since boxes never turn. Null without a label.
     */
    fun labelAsTextBox(): TextItem? = labelRect()?.let { r -> label?.asTextBox(r, ::toPage) }

    /**
     * What a canvas keeps of the label when the area eraser cuts the shape: its letters as a
     * filled [ShapeKind.GLYPHS] shape exactly where they were, turned with it. Null without a label,
     * or when the host cannot outline text.
     */
    fun labelAsGlyphs(): ShapeItem? = labelRect()?.let { r -> label?.asGlyphs(r, ::toPage, rotation) }

    /** The label's four corners on the page, turned with the shape; empty without a label. */
    fun labelCorners(): List<Pt> {
        val r = labelRect() ?: return emptyList()
        return listOf(r.topLeft, Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom)).map(::toPage)
    }

    /** True when page point [p] lands on the label's lines. */
    override fun labelContains(p: Pt): Boolean = labelRect()?.contains(toLocal(p)) == true

    override fun paintBounds(): Rect =
        if (neon) bounds().outset(Outline.glowRadius(strokeWidth, neonStrength) * 2.0 + 4.0)
        else bounds()

    override fun bounds(): Rect {
        val pad = strokeWidth / 2.0 + 1.0
        val own = if (shape.isEndpointShape && !hasHeads()) Rect.fromPoints(start, end).outset(pad) else outlineBounds().outset(pad)
        val corners = labelCorners()
        return if (corners.isEmpty()) own else own.union(Rect.bounding(corners))
    }

    /**
     * The shape's own frame as a selection box in page space, turned with it; null for a line or
     * arrow, which its two endpoints frame instead. The chrome draws it [framePad] further out.
     */
    fun frameObb(): Obb? {
        if (shape.isEndpointShape) return null
        val b = box
        return Obb(b.center, b.w / 2.0, b.h / 2.0, rotation)
    }

    /** How far past the frame the outline itself reaches: half its width, and a pixel to spare. */
    val framePad: Double get() = strokeWidth / 2.0 + 1.0

    /** True when the knobs and point count are the kind's own defaults, as every older file's are. */
    private fun hasDefaultParams(): Boolean = n == shape.defaultN &&
        shape.defaultAdj.indices.all { abs(shape.adjOf(adj, it) - shape.defaultAdj[it]) < 1e-12 }

    // --- knobs ---

    /** Where the kind's knobs sit on the page; none for a free outline, a line or an arrow. */
    fun knobDots(): List<Pt> =
        if (shape.isEndpointShape) emptyList() else shape.knobs.map { toPage(flip.map(it.position(box, adj, n), box)) }

    /** True when page point [p] is within [reach] of any knob, vertex dot or edge dot. */
    fun dotNear(p: Pt, reach: Double): Boolean =
        knobDots().any { it.distanceTo(p) <= reach } ||
            vertexDots().any { it.at.distanceTo(p) <= reach } ||
            edgeDots().any { it.at.distanceTo(p) <= reach }

    /** Drag knob [index] to page point [to]: the kind turns that into new knob values, or a new box. */
    fun dragKnob(index: Int, to: Pt) {
        val knob = shape.knobs.getOrNull(index) ?: return
        val reframe = knob.reframe
        // Knobs work in the kind's own drawing, so the pointer is mirrored back into it first.
        val at = flip.map(toLocal(to), box)
        if (reframe == null) {
            adj = knob.drag(at, box, adj, n)
            return
        }
        // The new box shares the old one's centre, so the turn about it carries on unchanged.
        val b = flip.map(reframe(at, box), box)
        val ltr = start.x <= end.x
        val down = start.y <= end.y
        start = Pt(if (ltr) b.left else b.right, if (down) b.top else b.bottom)
        end = Pt(if (ltr) b.right else b.left, if (down) b.bottom else b.top)
    }

    // --- point editing ---

    /** The outline a point edit works on: a free kind's own, or the curves a box kind would become. */
    private fun editOutline(): List<VectorContour> {
        val contours = when {
            shape.isEndpointShape -> listOf(FreePath.lines(listOf(start, end), closed = false))
            isFree -> FreePath.place(path.orEmpty(), box)
            else -> kindGeometry().paths
                .filter { it.role == PathRole.OUTLINE || it.role == PathRole.STROKE_ONLY }.flatMap { it.contours }
        }
        return contours.map(VertexEdit::canonical)
    }

    /** True when the shape shows point dots: a line or arrow (its middle), a free outline, a kind with corners. */
    private fun editable(): Boolean =
        shape.pointEditable && (shape.isEndpointShape || isFree || localGeometry().corners.isNotEmpty())

    /**
     * The dots on the vertices a drag can move, on the page; none for a smooth kind, and none for a
     * line or arrow, whose two ends are already its resize handles.
     */
    fun vertexDots(): List<EditDot> {
        if (!editable() || shape.isEndpointShape) return emptyList()
        return editOutline().flatMapIndexed { ci, c ->
            VertexEdit.vertices(c).mapIndexed { vi, v -> EditDot(toPage(v), PathSpot(ci, vi)) }
        }
    }

    /**
     * The dots halfway along each edge, which a drag splits into a new vertex; only beside vertex
     * dots, and only on edges whose ends are at least [minLength] apart, so a short one stays clear.
     */
    fun edgeDots(minLength: Double = 0.0): List<EditDot> {
        if (!editable()) return emptyList()
        // An open shape's label sits on its line; a dot it covers moves to where the line shows.
        val gap = if (shape.isOpen) label?.let { l -> labelRect()?.outset(l.lineHeight * LABEL_GAP_LINES) } else null
        val curve = splineCurve()
        return editOutline().flatMapIndexed { ci, c ->
            c.segments.indices.mapNotNull { si ->
                val from = VertexEdit.vertexAt(c, si)
                val seg = c.segments[si]
                if (from.distanceTo(seg.end) < minLength) return@mapNotNull null
                val mid = VertexEdit.midpoint(curve ?: c, si)
                val at = if (gap != null && seg is VectorSeg.Line) ConnectorPaths.clearOf(from, seg.end, mid, gap) else mid
                EditDot(toPage(at), PathSpot(ci, si))
            }
        }
    }

    /**
     * Become a free outline of the curves this kind draws, if not one already, so its points can
     * move one by one. Style, fill and frame are kept; the kind's own knobs go. True when it changed.
     */
    fun toFree(): Boolean {
        if (isFree) return false
        val outline = editOutline()
        shape = if (shape.isClosed) ShapeKind.POLYGON else ShapeKind.POLYLINE
        adj = shape.defaultAdj.copyOf()
        n = shape.defaultN
        edited(outline)
        return true
    }

    /** Move a free outline's vertex [spot] to page point [to]. */
    fun moveVertex(spot: PathSpot, to: Pt) {
        if (!isFree) return
        edited(VertexEdit.moveVertex(editOutline(), spot, toLocal(to)))
    }

    /** Split a free outline's edge [spot] at its middle; the new vertex's spot, or null when not free. */
    fun splitEdge(spot: PathSpot): PathSpot? {
        if (!isFree) return null
        val (contours, added) = VertexEdit.split(editOutline(), spot)
        // A curve's new point goes where the curve already runs, so adding it changes nothing yet.
        edited(splineCurve()?.let { VertexEdit.moveVertex(contours, added, VertexEdit.midpoint(it, spot.index)) } ?: contours)
        return added
    }

    /** A spline's drawn curve in the unturned frame, one cubic per span between its points; null for every other kind. */
    private fun splineCurve(): VectorContour? =
        if (shape == ShapeKind.SPLINE) localGeometry().paths.first().contours.firstOrNull() else null

    /** Delete a free outline's vertex [spot]; false when too few would be left. */
    fun deleteVertex(spot: PathSpot): Boolean {
        if (!isFree) return false
        if (shape == ShapeKind.SPLINE && editOutline().sumOf { VertexEdit.vertices(it).size } <= SplineKind.MIN_POINTS) return false
        edited(VertexEdit.delete(editOutline(), spot) ?: return false)
        return true
    }

    /** Store an edited outline (unturned frame), reboxing it in the same turned frame. */
    private fun edited(contours: List<VectorContour>) = refit(FreePath.map(contours, ::toPage), rotation)

    /** The turned outline's page-space bounds, before the stroke's own width. */
    private fun outlineBounds(): Rect {
        if (rotation == 0.0 && shape.staysInBox && !hasHeads()) return box
        if (shape == ShapeKind.ELLIPSE || shape == ShapeKind.CIRCLE) {
            val b = box
            val cs = cos(rotation)
            val sn = sin(rotation)
            val ex = hypot(b.w / 2.0 * cs, b.h / 2.0 * sn)
            val ey = hypot(b.w / 2.0 * sn, b.h / 2.0 * cs)
            return Rect(b.centerX - ex, b.centerY - ey, 2.0 * ex, 2.0 * ey)
        }
        val pts = localGeometry().paths.flatMap { p -> p.contours.flatMap { PathFlattener.flatten(it, BOUNDS_FLATTEN) } }
        return if (pts.isEmpty()) box else Rect.bounding(pts.map(::toPage))
    }

    override fun translate(dx: Double, dy: Double) {
        start = Pt(start.x + dx, start.y + dy)
        end = Pt(end.x + dx, end.y + dy)
    }

    override fun contains(p: Pt): Boolean = hits(toLocal(p), max(strokeWidth / 2.0, HIT_TOLERANCE)) || labelContains(p)

    override fun centroid(): Pt = bounds().center

    /**
     * True when page point [p] lands on the shape itself: within [tolerance] of a stroked edge (or
     * half the outline's width, if wider), anywhere in a fill, or on its label. The empty middle of
     * an unfilled shape is not the shape, so a press there can start a new one inside it.
     */
    fun touches(p: Pt, tolerance: Double): Boolean = hits(toLocal(p), max(strokeWidth / 2.0, tolerance)) || labelContains(p)

    /** True if an eraser circle of [radius] at (cx,cy) touches the shape's geometry; a fill's, even at its edge. */
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        if (bounds().distanceTo(Pt(cx, cy)) > radius) return false // cheap AABB reject
        if (isFill) return fillMeets(cx, cy, radius)
        // A circle turns into itself, so the test runs in the unturned frame.
        return hits(toLocal(Pt(cx, cy)), radius + strokeWidth / 2.0)
    }

    /** True when page point [p] lands on the outline itself, within [tolerance] (or half its width, if wider); never its fill or label. */
    fun onOutline(p: Pt, tolerance: Double): Boolean =
        Outline.hits(localPaths(), toLocal(p), max(strokeWidth / 2.0, tolerance), filled = false)

    /** The rings the shape's own fill covers, on the page, curves flattened to [tolerance]; none for an open kind. */
    fun fillRings(tolerance: Double): List<List<Pt>> = paths()
        .filter { it.role == PathRole.OUTLINE || it.role == PathRole.FILL_ONLY }
        .flatMap { path -> path.contours.map { PathFlattener.flatten(it, tolerance) } }
        .filter { it.size >= 3 }

    /** True when page point [p] lies inside the outline, filled or not: where a label would go. */
    fun encloses(p: Pt): Boolean {
        val q = toLocal(p)
        return localGeometry().paths.any { path ->
            (path.role == PathRole.OUTLINE || path.role == PathRole.FILL_ONLY) &&
                Outline.inRings(path.contours.map { PathFlattener.flatten(it, Outline.HIT_FLATTEN) }, q)
        }
    }

    /** Does [p] (unturned frame) land within [tol] of a stroked edge, or inside the fill? */
    private fun hits(p: Pt, tol: Double): Boolean = Outline.hits(localPaths(), p, tol, fillRgba != null)

    /**
     * AREA-erase: what survives an eraser circle (page-local [cx], [cy], [radius]), with the same
     * contract as [Stroke.erasedBy]: `null` when untouched, an empty list to remove the whole
     * shape, else the stroked lines left outside the circle, cut cleanly at its edge, as ordinary ink.
     * A cut shape stops being a shape: its pieces are pen strokes in its colour and width, dashed
     * where it was dashed (a solid's hidden edges too) and neon where it glowed. A fill cannot
     * survive a cut, so it goes; an eraser that only crosses the fill leaves the shape alone here,
     * and takes just the fill instead (see [EraseRule]). A filled head keeps its outline. A
     * dropped colour's fill and drawn text go whole.
     */
    fun erasedBy(cx: Double, cy: Double, radius: Double): List<CanvasItem>? {
        val c = Pt(cx, cy)
        if (bounds().distanceTo(c) > radius) return null
        if (isFill || shape == ShapeKind.GLYPHS) return if (intersectsCircle(cx, cy, radius)) emptyList() else null
        val (hit, ink) = Outline.cut(paths(), pen(), neon, neonStrength, c, radius)
            ?: return if (outlineMeets(cx, cy, radius)) emptyList() else null
        return if (hit) ink else null
    }

    /** True when an eraser circle at ([cx], [cy]) with [radius] touches the outline itself; never the fill or label. */
    fun outlineMeets(cx: Double, cy: Double, radius: Double): Boolean {
        if (bounds().distanceTo(Pt(cx, cy)) > radius) return false
        return Outline.hits(localPaths(), toLocal(Pt(cx, cy)), radius + strokeWidth / 2.0, filled = false)
    }

    /** True when an eraser circle at ([cx], [cy]) with [radius] touches the shape's fill at all, even its edge; false without one. */
    fun fillMeets(cx: Double, cy: Double, radius: Double): Boolean {
        if (fillRgba == null) return false
        val c = Pt(cx, cy)
        if (bounds().distanceTo(c) > radius) return false
        val q = toLocal(c)
        val rings = localGeometry().paths
            .filter { it.role == PathRole.OUTLINE || it.role == PathRole.FILL_ONLY }
            .flatMap { path -> path.contours.map { PathFlattener.flatten(it, Outline.HIT_FLATTEN) } }
        return Outline.inRings(rings, q) || rings.any { Outline.nearPolyline(it, q, radius, closed = true) }
    }

    override fun geometry(): GeoHandle = ShapeHandle(start, end)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is ShapeHandle) {
            start = handle.start
            end = handle.end
        }
    }

    override fun snapshotGeometry(): GeometrySnapshot = ShapeSnapshot(shape, start, end, path, rotation, adj.copyOf(), n, labelAt, flip, function)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is ShapeSnapshot) return
        shape = snap.shape
        start = snap.start
        end = snap.end
        path = snap.path
        rotation = snap.rotation
        adj = snap.adj.copyOf()
        n = snap.n
        labelAt = snap.labelAt
        flip = snap.flip
        function = snap.function
    }

    /** Plot [spec] in the current box instead; false (and unchanged) when it cannot be drawn. */
    fun setFunction(spec: FunctionSpec): Boolean {
        val runs = spec.normalizedRuns() ?: return false
        function = spec
        path = runs.map { FreePath.lines(it, false) }
        return true
    }

    /**
     * Bake a transform into the shape, keeping its kind wherever the result is still that kind. A
     * line/arrow moves its two endpoints. A box kind whose axes stay square to each other (any
     * scale along them, any turn, any mirror) gets a new box, [rotation] and [flip]; an ellipse
     * sheared by a stretch across its axes is still an ellipse, refound from the map's singular
     * values; any other sheared kind becomes a free outline of its own curves. Free outlines map
     * exactly. The outline keeps its width: a shape is redrawn, not magnified.
     */
    override fun applyTransform(t: Affine) {
        if (shape.isEndpointShape) {
            start = t.apply(start)
            end = t.apply(end)
            return
        }
        val b = box
        val cs = cos(rotation)
        val sn = sin(rotation)
        // Where the frame's unit axes land: the transform's linear part after the shape's own turn.
        val ux = Pt(t.a * cs + t.c * sn, t.b * cs + t.d * sn)
        val uy = Pt(-t.a * sn + t.c * cs, -t.b * sn + t.d * cs)
        val square = abs(ux.x * uy.x + ux.y * uy.y) <= 1e-9 * ux.length() * uy.length()
        val mirrors = t.determinant < 0.0
        when {
            isFree -> refit(FreePath.map(FreePath.place(path.orEmpty(), b)) { t.apply(toPage(it)) }, uprightTurn(ux, mirrors))
            square -> {
                val (turn, across) = if (mirrors) Flip.across(t, rotation) else atan2(uy.y, uy.x) - PI / 2.0 to Flip.NONE
                // A kind symmetric along that axis looks the same either way, so it carries no flip.
                flip = flip.toggled(across.and(shape.flips))
                setFrame(t.apply(b.center), ux.length() * b.w, uy.length() * b.h, turn)
            }
            !square && (shape == ShapeKind.ELLIPSE || shape == ShapeKind.CIRCLE) -> {
                // The ellipse is the unit circle under A = M diag(rx, ry); its axes are A's singular pairs.
                val rx = b.w / 2.0
                val ry = b.h / 2.0
                val p = ux.x * ux.x * rx * rx + uy.x * uy.x * ry * ry
                val q = ux.x * ux.y * rx * rx + uy.x * uy.y * ry * ry
                val r = ux.y * ux.y * rx * rx + uy.y * uy.y * ry * ry
                val mid = (p + r) / 2.0
                val spread = hypot((p - r) / 2.0, q)
                val major = sqrt(mid + spread)
                val minor = sqrt((mid - spread).coerceAtLeast(0.0))
                setFrame(t.apply(b.center), 2.0 * major, 2.0 * minor, 0.5 * atan2(2.0 * q, p - r))
            }
            else -> {
                val outline = localGeometry().paths
                    .filter { it.role == PathRole.OUTLINE || it.role == PathRole.STROKE_ONLY }
                    .flatMap { it.contours }
                shape = if (shape.isClosed) ShapeKind.POLYGON else ShapeKind.POLYLINE
                adj = shape.defaultAdj.copyOf()
                n = shape.defaultN
                refit(FreePath.map(outline) { t.apply(toPage(it)) }, uprightTurn(ux, mirrors))
            }
        }
        if (shape == ShapeKind.CIRCLE && abs(box.w - box.h) > 1e-9 * max(box.w, box.h)) shape = ShapeKind.ELLIPSE
    }

    /** The turn of a frame whose width axis lands on [ux]; a [mirrored] outline takes whichever way along it is nearer upright. */
    private fun uprightTurn(ux: Pt, mirrored: Boolean): Double {
        val a = atan2(ux.y, ux.x)
        return if (mirrored && abs(normalizeTurn(a)) > PI / 2.0 + TURN_SNAP) a - PI else a
    }

    /** Make the unturned box [w] x [h] about [centre], turned by [turn], snapping a quarter turn upright. */
    private fun setFrame(centre: Pt, w: Double, h: Double, turn: Double) {
        var ww = w
        var hh = h
        var a = normalizeTurn(turn)
        val quarters = round(a / (PI / 2.0))
        // A rectangle or ellipse turned a whole number of quarters is just an upright one, unless its label turns too.
        val symmetric = label == null && (shape == ShapeKind.RECTANGLE || shape == ShapeKind.ELLIPSE || shape == ShapeKind.CIRCLE)
        if (symmetric && abs(a - quarters * PI / 2.0) < TURN_SNAP) {
            if (quarters.toInt() % 2 != 0) {
                ww = h
                hh = w
            }
            a = 0.0
        }
        if (abs(a) < TURN_SNAP) a = 0.0
        start = Pt(centre.x - ww / 2.0, centre.y - hh / 2.0)
        end = Pt(centre.x + ww / 2.0, centre.y + hh / 2.0)
        rotation = a
    }

    /** Re-derive the box and the normalized [path] from page-space [contours], in a frame turned by [turn]; they are as drawn, so nothing is mirrored. */
    private fun refit(contours: List<VectorContour>, turn: Double) {
        if (contours.isEmpty()) return
        flip = Flip.NONE
        var a = normalizeTurn(turn)
        val quarters = round(a / (PI / 2.0))
        // The bounds read the same either way, but a label turns with its frame.
        if (abs(a - quarters * PI / 2.0) < TURN_SNAP && (label == null || quarters == 0.0)) a = 0.0
        val cs = cos(a)
        val sn = sin(a)
        val unturned = FreePath.map(contours) { Pt(it.x * cs + it.y * sn, -it.x * sn + it.y * cs) }
        val lb = FreePath.bounds(unturned) ?: return
        val c = Pt(lb.centerX * cs - lb.centerY * sn, lb.centerX * sn + lb.centerY * cs)
        start = Pt(c.x - lb.w / 2.0, c.y - lb.h / 2.0)
        end = Pt(c.x + lb.w / 2.0, c.y + lb.h / 2.0)
        rotation = a
        path = FreePath.normalize(unturned, lb)
    }

    /**
     * What a reader that predates turned, curved or newer kinds should draw instead: null when
     * the shape is plain enough for it as is, else the page outline its [legacyId] stands in with.
     * The codecs write that stand-in and then the real shape for current readers.
     */
    fun legacyOutline(): List<Pt>? {
        if (shape.isEndpointShape) return null
        val plain = shape.isLegacy && rotation == 0.0 && flip == Flip.NONE && hasDefaultParams() &&
            (!isFree || path?.let(FreePath::isVertexList) != false)
        if (plain) return null
        if (shape == ShapeKind.ELLIPSE || shape == ShapeKind.CIRCLE) return ellipsePolygon(LEGACY_ELLIPSE_POINTS).map(::toPage)
        val outer = localGeometry().paths
            .firstOrNull { it.role == PathRole.OUTLINE || it.role == PathRole.STROKE_ONLY }
            ?.contours?.firstOrNull() ?: return null
        val pts = if (outer.segments.all { it is VectorSeg.Line }) FreePath.vertices(outer) else PathFlattener.flatten(outer, LEGACY_FLATTEN)
        return pts.map(::toPage)
    }

    /** The legacy kind id a file writes for this shape: its own, or the polygon/polyline of its stand-in. */
    fun legacyId(): String = when {
        // A graph or a spline is free but no older reader knows it: it stands in as the line it draws.
        shape == ShapeKind.FUNCTION || shape == ShapeKind.SPLINE -> ShapeKind.POLYLINE.id
        legacyOutline() == null || isFree -> shape.id
        shape.isClosed -> ShapeKind.POLYGON.id
        else -> ShapeKind.POLYLINE.id
    }

    /**
     * Turn a stand-in read from a file back into the shape it stands for: its real [kind] (null
     * keeps this one), its unturned [frame], the [turn] about the frame's centre and, for a free
     * kind, its outline normalized to the frame ([freePath], else the stand-in's own vertices).
     */
    fun restoreFrame(kind: ShapeKind?, frame: Rect, turn: Double, freePath: List<VectorContour>? = null) {
        val target = kind ?: shape
        val finite = frame.x.isFinite() && frame.y.isFinite() && frame.w.isFinite() && frame.h.isFinite()
        if (target.isEndpointShape || !finite || !turn.isFinite()) return
        val pagePoints = vertices()
        if (target != shape) {
            // The stand-in's own parameters mean nothing to the real kind; the file's come after.
            adj = target.defaultAdj.copyOf()
            n = target.defaultN
            headStart = target.defaultHeadStart
            headEnd = target.defaultHeadEnd
        }
        shape = target
        start = frame.topLeft
        end = Pt(frame.right, frame.bottom)
        rotation = turn
        path = when {
            !isFree -> null
            freePath != null -> freePath
            else -> pagePoints?.let { pts -> FreePath.normalize(listOf(FreePath.lines(pts.map(::toLocal), target.isClosed)), box) }
        }
    }

    /**
     * Earlier versions baked a turned rectangle into a 4-point polygon and a turned ellipse into a
     * 48-point one. Spot either, exact to rounding, and make it the turned shape it was.
     */
    fun unbake() {
        if (shape != ShapeKind.POLYGON || rotation != 0.0) return
        if (path?.let(FreePath::isVertexList) != true) return
        val p = vertices() ?: return
        if (p.size == 4) unbakeRect(p) else if (p.size == BAKED_ELLIPSE_POINTS) unbakeEllipse(p)
    }

    private fun unbakeRect(p: List<Pt>) {
        val e1 = p[1] - p[0]
        val e2 = p[2] - p[1]
        val e3 = p[3] - p[2]
        val e4 = p[0] - p[3]
        val size = max(e1.length(), e2.length())
        if (size < 1e-9) return
        val tol = 1e-9 * size
        if ((e1 + e3).length() > tol || (e2 + e4).length() > tol) return
        if (abs(e1.x * e2.x + e1.y * e2.y) > tol * size) return
        shape = ShapeKind.RECTANGLE
        path = null
        setFrame((p[0] + p[2]) * 0.5, e1.length(), e2.length(), atan2(e1.y, e1.x))
    }

    private fun unbakeEllipse(p: List<Pt>) {
        var cx = 0.0
        var cy = 0.0
        for (q in p) {
            cx += q.x
            cy += q.y
        }
        val c = Pt(cx / p.size, cy / p.size)
        // The bake sampled angle 2 pi k / 48, so point 0 and point 12 are the images of the two axes.
        val a1 = p[0] - c
        val a2 = p[BAKED_ELLIPSE_POINTS / 4] - c
        val size = max(a1.length(), a2.length())
        if (size < 1e-9) return
        for (k in p.indices) {
            val a = 2.0 * PI * k / BAKED_ELLIPSE_POINTS
            val want = c + a1 * cos(a) + a2 * sin(a)
            if (want.distanceTo(p[k]) > 1e-7 * size) return
        }
        val pp = a1.x * a1.x + a2.x * a2.x
        val q = a1.x * a1.y + a2.x * a2.y
        val r = a1.y * a1.y + a2.y * a2.y
        val mid = (pp + r) / 2.0
        val spread = hypot((pp - r) / 2.0, q)
        val major = sqrt(mid + spread)
        val minor = sqrt((mid - spread).coerceAtLeast(0.0))
        shape = if (major - minor <= 1e-9 * major) ShapeKind.CIRCLE else ShapeKind.ELLIPSE
        path = null
        setFrame(c, 2.0 * major, 2.0 * minor, 0.5 * atan2(2.0 * q, pp - r))
    }

    companion object {
        const val KIND = "shape"
        const val HIT_TOLERANCE = 6.0

        /** A turn this close to a whole number of quarters counts as one, so upright stays upright. */
        private const val TURN_SNAP = 1e-9

        /** Points in the polygon a turned ellipse is stored as, for readers that predate turning. */
        private const val LEGACY_ELLIPSE_POINTS = 64

        /** Points earlier versions baked a turned ellipse into. */
        private const val BAKED_ELLIPSE_POINTS = 48

        /** Content px a curve may stray when flattened for bounds and stand-ins. */
        private const val BOUNDS_FLATTEN = 0.05

        /** The gap an open shape's line leaves round its label, in lines of the label's height. */
        private const val LABEL_GAP_LINES = 0.2

        /** Room past a label's longest line, so rounding never wraps it, in content px. */
        private const val LABEL_SLACK = 2.0

        /** The kind a file gives drawn text, which readers that predate it skip rather than misdraw. */
        const val GLYPHS_KIND = "glyphs"

        /** The kind a file gives a dropped colour's area, which readers that predate it skip. */
        const val FILL_KIND = "fill"

        private const val LEGACY_FLATTEN = 0.25

        /** [a] folded into (-PI, PI]. */
        private fun normalizeTurn(a: Double): Double {
            var r = a % (2.0 * PI)
            if (r <= -PI) r += 2.0 * PI
            if (r > PI) r -= 2.0 * PI
            return r
        }

        /** Build a polygon/polyline from absolute [vertices], stored normalized to their box. */
        fun poly(
            shape: ShapeKind,
            vertices: List<Pt>,
            strokeRgba: Rgba,
            strokeWidth: Double = 3.0,
            fillRgba: Rgba? = null,
            neon: Boolean = false,
            neonStrength: Double = 0.6,
            dashed: Boolean = false,
            dashLength: Double = 10.0,
            dashGap: Double = 8.0,
        ): ShapeItem {
            val box = Rect.bounding(vertices)
            val outline = FreePath.normalize(listOf(FreePath.lines(vertices, shape.isClosed)), box)
            return ShapeItem(
                shape, box.topLeft, Pt(box.right, box.bottom), strokeRgba, strokeWidth,
                fillRgba, neon, neonStrength, outline, dashed, dashLength, dashGap,
            )
        }

        /** A dropped colour's area: the page-space [rings], holes by parity, filled solid in [color] and never stroked. */
        fun fill(rings: List<List<Pt>>, color: Rgba): ShapeItem {
            val contours = rings.filter { it.size >= 3 }.map { FreePath.lines(it, closed = true) }
            val box = FreePath.bounds(contours) ?: Rect(0.0, 0.0, 0.0, 0.0)
            return ShapeItem(
                ShapeKind.FILL, box.topLeft, Pt(box.right, box.bottom), color, 0.0, color,
                path = FreePath.normalize(contours, box),
            )
        }

        /** A free shape whose outline is the page-space [contours], boxed tight around what they draw. */
        fun free(shape: ShapeKind, contours: List<VectorContour>, strokeRgba: Rgba, strokeWidth: Double = 3.0): ShapeItem {
            val box = FreePath.bounds(contours) ?: Rect(0.0, 0.0, 0.0, 0.0)
            return ShapeItem(
                shape, box.topLeft, Pt(box.right, box.bottom), strokeRgba, strokeWidth,
                path = FreePath.normalize(contours, box),
            )
        }
    }
}

/** Snapshot of a shape's transformable geometry (kind can change when a box shape is sheared). */
private class ShapeSnapshot(
    val shape: ShapeKind,
    val start: Pt,
    val end: Pt,
    val path: List<VectorContour>?,
    val rotation: Double,
    val adj: DoubleArray,
    val n: Int,
    val labelAt: Double,
    val flip: Flip,
    val function: FunctionSpec?,
) : GeometrySnapshot {
    // Value equality, so a drag that changed nothing records nothing.
    override fun equals(other: Any?): Boolean = other is ShapeSnapshot && shape == other.shape &&
        start == other.start && end == other.end && path === other.path && rotation == other.rotation &&
        adj.contentEquals(other.adj) && n == other.n && labelAt == other.labelAt && flip == other.flip && function == other.function

    override fun hashCode(): Int = start.hashCode() * 31 + end.hashCode()
}
