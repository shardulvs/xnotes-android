package com.xnotes.core.pdf

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/**
 * Paints text markups in points as displayed, the space their quads are in. A quad is measured in
 * its reading frame: u runs along the line from where it starts, v from the glyphs' tops down to
 * their feet, which [TextQuad.quarter] turns onto the page.
 */
object MarkupPainter {

    /** Paints [markups] bottom first, at [scale] px a point from the page's top-left corner. */
    fun paint(r: Renderer, markups: List<TextMarkup>, scale: Double) {
        if (markups.isEmpty()) return
        r.withSave {
            r.scale(scale, scale)
            for (m in markups) paint(r, m)
        }
    }

    /** Paints [m] in points, in [color] with a highlight laid on by [blend] (a page's filter changes both). */
    fun paint(r: Renderer, m: TextMarkup, color: Rgba = m.color, blend: BlendMode = BlendMode.MULTIPLY) {
        if (m.type == MarkupType.HIGHLIGHT) {
            // One layer per markup: its own overlapping lines never darken twice, stacked markups do.
            quadBounds(m.quads)?.let { bounds ->
                r.saveLayerBlended(bounds, m.intensity, blend)
                for (q in m.quads) r.fillRect(Rect.ltrb(q.left.toDouble(), q.top.toDouble(), q.right.toDouble(), q.bottom.toDouble()), color)
                r.restore()
            }
        } else {
            for (q in m.quads) {
                val line = lineOf(m.type, q)
                if (line.size >= 2) r.strokePolyline(line, Pen(color, thickness(q), cosmetic = false))
            }
        }
    }

    /** Where [m] paints, in points; null when it has no quads. A note's icon is chrome ([NoteIcon]), not page. */
    fun bounds(m: TextMarkup): Rect? = quadBounds(m.quads)

    /** The topmost of [marks] at ([x], [y]), in points: on one of its lines grown by [slop]. */
    fun markupAt(marks: List<TextMarkup>, x: Double, y: Double, slop: Double): TextMarkup? {
        for (m in marks.asReversed()) {
            for (q in m.quads) {
                if (x >= q.left - slop && x <= q.right + slop && y >= q.top - slop && y <= q.bottom + slop) return m
            }
        }
        return null
    }

    /** Whether a circle at ([x], [y]) of [radius], in points, touches what [m] paints: the eraser's test. */
    fun touches(m: TextMarkup, x: Double, y: Double, radius: Double): Boolean {
        val c = Pt(x, y)
        for (q in m.quads) {
            // Every mark lies within its quad, so a circle clear of the quad misses it.
            if (Rect.ltrb(q.left.toDouble(), q.top.toDouble(), q.right.toDouble(), q.bottom.toDouble()).distanceTo(c) > radius) continue
            if (m.type == MarkupType.HIGHLIGHT) return true
            val reach = radius + thickness(q) / 2
            if (lineOf(m.type, q).zipWithNext().any { (a, b) -> Geometry.distancePointToSegment(c, a, b) <= reach }) return true
        }
        return false
    }

    /** The centre line of a line type along [q]: a straight line, or the squiggle's wave. */
    internal fun lineOf(type: MarkupType, q: TextQuad): List<Pt> {
        val len = along(q)
        val h = across(q)
        val w = thickness(q)
        return when (type) {
            MarkupType.UNDERLINE -> listOf(frame(q, 0.0, h - w), frame(q, len, h - w))
            MarkupType.STRIKEOUT -> listOf(frame(q, 0.0, h / 2), frame(q, len, h / 2))
            MarkupType.SQUIGGLY -> {
                val period = h / WAVE_PERIODS
                val amp = h * WAVE_AMPLITUDE
                val mid = h - w / 2 - amp
                val steps = max(2, ceil(len / period * WAVE_STEPS).toInt())
                List(steps + 1) { i ->
                    val u = len * i / steps
                    frame(q, u, mid + amp * sin(2 * PI * u / period))
                }
            }
            MarkupType.HIGHLIGHT -> emptyList()
        }
    }

    /**
     * [q]'s corners in the order PDF QuadPoints take them: the line's start then end along the
     * glyphs' tops, then the same along their feet.
     */
    fun corners(q: TextQuad): List<Pt> {
        val len = along(q)
        val h = across(q)
        return listOf(frame(q, 0.0, 0.0), frame(q, len, 0.0), frame(q, 0.0, h), frame(q, len, h))
    }

    /** How thick a line type is drawn on [q]. */
    internal fun thickness(q: TextQuad): Double = max(across(q) / THICKNESS_DIVISOR, MIN_THICKNESS)

    private fun along(q: TextQuad): Double = (if (q.quarter % 2 == 0) q.right - q.left else q.bottom - q.top).toDouble()

    private fun across(q: TextQuad): Double = (if (q.quarter % 2 == 0) q.bottom - q.top else q.right - q.left).toDouble()

    /** The page point at [u] along [q]'s line from its start and [v] down from its glyphs' tops. */
    internal fun frame(q: TextQuad, u: Double, v: Double): Pt {
        val l = q.left.toDouble()
        val t = q.top.toDouble()
        val r = q.right.toDouble()
        val b = q.bottom.toDouble()
        return when (q.quarter) {
            1 -> Pt(r - v, t + u)
            2 -> Pt(r - u, b - v)
            3 -> Pt(l + v, b - u)
            else -> Pt(l + u, t + v)
        }
    }

    private fun quadBounds(quads: List<TextQuad>): Rect? {
        if (quads.isEmpty()) return null
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (q in quads) {
            l = minOf(l, q.left)
            t = minOf(t, q.top)
            r = maxOf(r, q.right)
            b = maxOf(b, q.bottom)
        }
        return Rect.ltrb(l.toDouble(), t.toDouble(), r.toDouble(), b.toDouble())
    }

    private const val THICKNESS_DIVISOR = 14.0
    private const val MIN_THICKNESS = 0.5
    private const val WAVE_PERIODS = 3.5
    private const val WAVE_AMPLITUDE = 1.0 / 12
    private const val WAVE_STEPS = 8
}
