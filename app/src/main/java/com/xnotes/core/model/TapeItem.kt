package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.tape.TapeGeometry
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** How a tape is laid: one straight strip, a box dragged corner to corner, or a band that follows the hand. */
enum class TapeMode(val id: String) {
    STRAIGHT("straight"),
    RECTANGLE("rectangle"),
    FREEHAND("freehand");

    companion object {
        fun fromId(id: String?): TapeMode = entries.firstOrNull { it.id == id } ?: STRAIGHT
    }
}

/** What is printed on a covered tape, in a shade of its own colour. */
enum class TapePattern(val id: String) {
    SOLID("solid"),
    STRIPES("stripes"),
    DOTS("dots"),
    GRID("grid");

    companion object {
        fun fromId(id: String?): TapePattern = entries.firstOrNull { it.id == id } ?: SOLID
    }
}

/**
 * A tape's geometry: its [mode], the [points] it runs along and its [width] across them. A straight
 * strip and a rectangle hold two points, the ends of their axis; a freehand band holds the hand's
 * path. Immutable, so a thread painting or meshing it reads it whole while an edit swaps in another;
 * what it draws is worked out once, on first use.
 */
data class TapeShape(val mode: TapeMode, val points: List<Pt>, val width: Double) {

    val half: Double get() = width / 2.0

    /** Its body as convex pieces. */
    val body: List<List<Pt>> by lazy { TapeGeometry.body(this) }

    /** The edge a peeled tape shows. */
    val outline: List<TapeGeometry.Line> by lazy { TapeGeometry.outline(this) }

    val bounds: Rect by lazy { TapeGeometry.bounds(this) }

    @Volatile
    private var printed: Pair<TapePattern, List<List<Pt>>>? = null

    /** [pattern] cut to the body, kept for the pattern last asked for. */
    fun pattern(pattern: TapePattern): List<List<Pt>> {
        printed?.let { if (it.first == pattern) return it.second }
        return TapeGeometry.pattern(this, pattern).also { printed = pattern to it }
    }

    /** A strip's or a rectangle's own turned box: its axis, and the width across it. Null for a freehand band. */
    fun frame(): Obb? {
        if (mode == TapeMode.FREEHAND) return null
        val a = points[0]
        val b = points[1]
        val angle = if (a == b) 0.0 else atan2(b.y - a.y, b.x - a.x)
        return Obb(Pt((a.x + b.x) / 2.0, (a.y + b.y) / 2.0), a.distanceTo(b) / 2.0, half, angle)
    }

    fun translated(dx: Double, dy: Double): TapeShape = copy(points = points.map { Pt(it.x + dx, it.y + dy) })

    /**
     * Under [t]: the points move, and the width scales by what [t] does across the axis, so a strip
     * stretched along its length keeps its width and one stretched across it widens. A freehand band
     * scales its width by the transform's overall factor, as ink does.
     */
    fun transformed(t: Affine): TapeShape {
        val moved = points.map { t.apply(it) }
        if (mode == TapeMode.FREEHAND || points[0] == points[1]) return copy(points = moved, width = width * t.linearScale)
        val u = TapeGeometry.axes(points[0], points[1]).first
        val along = hypot(t.a * u.x + t.c * u.y, t.b * u.x + t.d * u.y)
        val across = if (along < 1e-12) t.linearScale else abs(t.determinant) / along
        return copy(points = moved, width = width * across)
    }
}

/**
 * A strip of tape laid over the page to hide what is under it, for testing yourself: a tap peels it
 * back to a dashed outline and another covers it again. It is an ordinary item in the z-order, so
 * ink written after it lands on top of it. Whether it is peeled is a view state, never saved, copied
 * or undone, so every tape is covered again when the file next opens. It never reaches an export.
 */
class TapeItem(
    shape: TapeShape,
    rgba: Rgba,
    pattern: TapePattern = TapePattern.SOLID,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true
    override var locked = false
    override var group: Int? = null

    /** Swapped whole on every edit, never changed in place, so other threads see one shape or the next. */
    @Volatile
    var shape: TapeShape = shape

    @Volatile
    var rgba: Rgba = rgba

    @Volatile
    var pattern: TapePattern = pattern

    /** Peeled back, showing what it covers. */
    @Volatile
    var peeled: Boolean = false

    val mode: TapeMode get() = shape.mode

    override val exported: Boolean get() = false

    /** The colour it covers with: its own, always opaque, since hiding is the point. */
    val coverColor: Rgba get() = rgba.withAlpha(255)

    /** The pattern's shade of the cover colour: darker on a light tape, lighter on a dark one. */
    val printColor: Rgba
        get() {
            val c = coverColor
            return if (c.luminance() > 0.3) mix(c, 0, 0.28) else mix(c, 255, 0.32)
        }

    override fun paint(r: Renderer) {
        val s = shape
        if (peeled) {
            val pen = Pen(coverColor, TapeGeometry.OUTLINE_WIDTH, cosmetic = false, dashed = true, dashOn = TapeGeometry.DASH_ON, dashGap = TapeGeometry.DASH_GAP)
            for (line in s.outline) if (line.closed) r.strokePolygon(line.pts, pen) else r.strokePolyline(line.pts, pen)
            return
        }
        val cover = coverColor
        for (piece in s.body) r.fillPolygon(piece, cover)
        val p = pattern
        if (p != TapePattern.SOLID) {
            val print = printColor
            for (mark in s.pattern(p)) r.fillPolygon(mark, print)
        }
    }

    override fun bounds(): Rect = shape.bounds

    /** The peeled outline is stroked along the body's edge, so half of it lies outside. */
    override fun paintBounds(): Rect = shape.bounds.outset(TapeGeometry.OUTLINE_WIDTH)

    override fun translate(dx: Double, dy: Double) {
        shape = shape.translated(dx, dy)
    }

    /** Whether [p] lies on the body, within [reach], peeled or not: where a tap peels the tape or covers it again. */
    fun under(p: Pt, reach: Double = 0.0): Boolean = TapeGeometry.distance(shape, p) <= reach

    /**
     * Hit where it shows: a covered tape anywhere on its body, a peeled one only along its dashed
     * edge, so what shows through it can still be selected and erased without taking the tape.
     */
    override fun contains(p: Pt): Boolean = if (peeled) TapeGeometry.edgeDistance(shape, p) <= EDGE_REACH else under(p)

    override fun centroid(): Pt = shape.bounds.center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        if (peeled) TapeGeometry.edgeDistance(shape, Pt(cx, cy)) <= radius + TapeGeometry.OUTLINE_WIDTH / 2.0 else under(Pt(cx, cy), radius)

    override fun snapshotGeometry(): GeometrySnapshot = TapeSnapshot(shape)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap is TapeSnapshot) shape = snap.shape
    }

    override fun applyTransform(t: Affine) {
        shape = shape.transformed(t)
    }

    override fun geometry(): GeoHandle = TapeHandle(shape)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is TapeHandle) shape = handle.shape
    }

    companion object {
        const val KIND = "tape"

        /** About 6 mm at the default 150 dpi: one line of handwriting. */
        const val DEFAULT_WIDTH = 36.0
        const val MIN_WIDTH = 8.0
        const val MAX_WIDTH = 120.0

        /** A drag shorter than this lays nothing; it was a tap. */
        const val MIN_DRAG = 6.0

        /** How near its dashed edge a peeled tape is still hit. */
        const val EDGE_REACH = 4.0

        /** How far a freehand band's path may be simplified at the pen's lift. */
        const val FREEHAND_TOLERANCE = 0.75

        private fun mix(c: Rgba, to: Int, f: Double): Rgba = Rgba(
            (c.r + (to - c.r) * f).roundToInt(),
            (c.g + (to - c.g) * f).roundToInt(),
            (c.b + (to - c.b) * f).roundToInt(),
            c.a,
        )
    }
}

/** A tape's geometry as resize exchanges it: the whole shape. */
data class TapeHandle(val shape: TapeShape) : GeoHandle

private data class TapeSnapshot(val shape: TapeShape) : GeometrySnapshot
