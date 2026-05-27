package com.xnotes.core.model

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.tools.ShapeKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * An editable geometric shape (spec 02 §5.4): open (line/arrow, two endpoints)
 * or closed (rectangle/ellipse/triangle, drawn inside the normalized AABB of
 * start/end). Closed shapes are stroked and optionally filled. Shapes are
 * deliberately-placed objects — immune to the object eraser.
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
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true

    /** Normalized AABB of the two drag points. */
    val box: Rect get() = Rect.fromPoints(start, end)

    private fun pen() = Pen(color = strokeRgba, width = strokeWidth, cosmetic = false)

    /** Triangle vertices: apex at top-edge midpoint, base along the bottom edge. */
    private fun triangleVertices(): List<Pt> {
        val b = box
        return listOf(Pt(b.centerX, b.top), Pt(b.left, b.bottom), Pt(b.right, b.bottom))
    }

    /** Filled-arrowhead triangle at [end], sized from the stroke width. */
    private fun arrowHead(): List<Pt> {
        val dir = (end - start).normalized()
        if (dir.length() < 1e-9) return emptyList()
        val headLen = max(12.0, strokeWidth * 3.5)
        val back = end - dir * headLen
        val perp = dir.perp() * (headLen * 0.5)
        return listOf(end, back + perp, back - perp)
    }

    private fun ellipsePolygon(segments: Int = 48): List<Pt> {
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

    override fun paint(r: Renderer) {
        if (neon) return paintNeon(r)
        fillRgba?.let { drawFill(r, it) }
        drawOutline(r, pen())
        if (shape == ShapeKind.ARROW) drawArrowHead(r, strokeRgba)
    }

    /** Fill the closed-shape interior (no-op for open line/arrow). */
    private fun drawFill(r: Renderer, fill: Rgba) {
        val b = box
        when (shape) {
            ShapeKind.RECTANGLE -> r.fillRect(b, fill)
            ShapeKind.ELLIPSE -> r.fillEllipse(b.center, b.w / 2.0, b.h / 2.0, fill)
            ShapeKind.TRIANGLE -> r.fillPolygon(triangleVertices(), fill)
            ShapeKind.LINE, ShapeKind.ARROW -> {}
        }
    }

    /** Stroke the shape's outline (the arrow's shaft for arrows) with [pen]. */
    private fun drawOutline(r: Renderer, pen: Pen) {
        val b = box
        when (shape) {
            ShapeKind.LINE, ShapeKind.ARROW -> r.strokePolyline(listOf(start, end), pen)
            ShapeKind.RECTANGLE -> r.strokeRect(b, pen)
            ShapeKind.ELLIPSE -> r.strokeEllipse(b.center, b.w / 2.0, b.h / 2.0, pen)
            ShapeKind.TRIANGLE -> r.strokePolygon(triangleVertices(), pen)
        }
    }

    /** Fill the arrowhead in [color], optionally with a soft glow of [blur] (0 = crisp). */
    private fun drawArrowHead(r: Renderer, color: Rgba, blur: Double = 0.0, inner: Boolean = false) {
        val head = arrowHead()
        if (head.size != 3) return
        if (blur > 0.0) r.fillPolygonGlow(head, color, FillRule.NONZERO, blur, inner) else r.fillPolygon(head, color)
    }

    /**
     * Neon: the outline as a glowing glass tube — a blurred ink-colour **halo**
     * (composited once at a glow-intensity alpha so corners don't blow out), the
     * opaque colour **body**, then a thinner **white-hot core** down the centre so
     * the colour reads at the tube's edges. [neonStrength] scales the halo only.
     */
    private fun paintNeon(r: Renderer) {
        val s = neonStrength.coerceIn(0.0, 1.0)
        val color = strokeRgba.withAlpha(255)
        val white = Rgba(255, 255, 255, 255)
        val glowR = (strokeWidth * (GLOW_FACTOR_MIN + GLOW_FACTOR_SPAN * s)).coerceAtLeast(GLOW_MIN)
        val glowAlpha = GLOW_ALPHA_MIN + GLOW_ALPHA_SPAN * s
        val coreW = (strokeWidth * CORE_WIDTH_FRAC).coerceAtLeast(CORE_WIDTH_MIN)

        fillRgba?.let { drawFill(r, it) }

        // 1) Outer halo (ink colour), bounded in its own glow-alpha layer.
        r.saveLayerAlpha(bounds().outset(glowR * 2.0 + 4.0), glowAlpha)
        drawOutline(r, Pen(color = color, width = strokeWidth, cosmetic = false, glowRadius = glowR))
        if (shape == ShapeKind.ARROW) drawArrowHead(r, color, glowR)
        r.restore()

        // 2) Tube body — saturated colour shows at the rim.
        drawOutline(r, pen())
        if (shape == ShapeKind.ARROW) drawArrowHead(r, strokeRgba)

        // 3) White-hot core — a thinner white line (and an inward-blurred white head).
        drawOutline(r, Pen(color = white, width = coreW, cosmetic = false))
        if (shape == ShapeKind.ARROW) drawArrowHead(r, white, strokeWidth * CORE_WIDTH_FRAC + 0.5, inner = true)
    }

    override fun bounds(): Rect {
        val pad = strokeWidth / 2.0 + 1.0
        return when (shape) {
            ShapeKind.LINE -> Rect.fromPoints(start, end).outset(pad)
            ShapeKind.ARROW -> Rect.bounding(listOf(start, end) + arrowHead()).outset(pad)
            else -> box.outset(pad)
        }
    }

    override fun translate(dx: Double, dy: Double) {
        start = Pt(start.x + dx, start.y + dy)
        end = Pt(end.x + dx, end.y + dy)
    }

    override fun contains(p: Pt): Boolean {
        val tol = max(strokeWidth / 2.0, HIT_TOLERANCE)
        return when (shape) {
            ShapeKind.LINE, ShapeKind.ARROW -> Geometry.distancePointToSegment(p, start, end) <= tol
            ShapeKind.RECTANGLE -> if (fillRgba != null) box.contains(p) else nearRectOutline(p, tol)
            ShapeKind.TRIANGLE -> {
                val v = triangleVertices()
                if (fillRgba != null) Geometry.pointInPolygon(v, p) else nearPolyOutline(v, p, tol)
            }
            ShapeKind.ELLIPSE -> {
                val poly = ellipsePolygon()
                if (fillRgba != null) Geometry.pointInPolygon(poly, p) else nearPolyOutline(poly, p, tol)
            }
        }
    }

    private fun nearRectOutline(p: Pt, tol: Double): Boolean {
        val b = box
        val corners = listOf(
            Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom),
        )
        return nearPolyOutline(corners, p, tol)
    }

    private fun nearPolyOutline(verts: List<Pt>, p: Pt, tol: Double): Boolean {
        for (i in verts.indices) {
            val a = verts[i]
            val b = verts[(i + 1) % verts.size]
            if (Geometry.distancePointToSegment(p, a, b) <= tol) return true
        }
        return false
    }

    override fun centroid(): Pt = bounds().center

    /** True if an eraser circle of [radius] at (cx,cy) touches the shape's geometry. */
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        val p = Pt(cx, cy)
        if (bounds().distanceTo(p) > radius) return false // cheap AABB reject
        val tol = radius + strokeWidth / 2.0
        return when (shape) {
            ShapeKind.LINE, ShapeKind.ARROW -> Geometry.distancePointToSegment(p, start, end) <= tol
            ShapeKind.RECTANGLE ->
                if (fillRgba != null && box.contains(p)) true else nearRectOutline(p, tol)
            ShapeKind.TRIANGLE -> {
                val v = triangleVertices()
                if (fillRgba != null && Geometry.pointInPolygon(v, p)) true else nearPolyOutline(v, p, tol)
            }
            ShapeKind.ELLIPSE -> {
                val poly = ellipsePolygon()
                if (fillRgba != null && Geometry.pointInPolygon(poly, p)) true else nearPolyOutline(poly, p, tol)
            }
        }
    }

    override fun geometry(): GeoHandle = ShapeHandle(start, end)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is ShapeHandle) {
            start = handle.start
            end = handle.end
        }
    }

    companion object {
        const val KIND = "shape"
        const val HIT_TOLERANCE = 6.0

        /** Halo blur = stroke_width × (MIN + SPAN × neonStrength), floored in page px. */
        private const val GLOW_FACTOR_MIN = 1.2
        private const val GLOW_FACTOR_SPAN = 2.6
        private const val GLOW_MIN = 4.0

        /** Halo opacity = MIN + SPAN × neonStrength. */
        private const val GLOW_ALPHA_MIN = 0.25
        private const val GLOW_ALPHA_SPAN = 0.55

        /** White-hot core line width as a fraction of the stroke width, with a page-px floor. */
        private const val CORE_WIDTH_FRAC = 0.4
        private const val CORE_WIDTH_MIN = 1.0
    }
}
