package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cutting a picture down to part of what it shows. The part is [Rect] fractions (left, top,
 * right, bottom, from 0 to 1) of the picture's box as drawn before its free turn, so a turned or
 * mirrored picture is cropped in its own frame.
 */
object ImageCrop {

    /** The pixels of [item]'s picture, [w] by [h], that show in the part [f] of its box. */
    fun sourceRect(item: ImageItem, w: Int, h: Int, f: Rect): Rect {
        val p = partOf(item, f)
        val l = (p.left * w).roundToInt().coerceIn(0, w)
        val t = (p.top * h).roundToInt().coerceIn(0, h)
        val r = (p.right * w).roundToInt().coerceIn(0, w)
        val btm = (p.bottom * h).roundToInt().coerceIn(0, h)
        return Rect.ltrb(l.toDouble(), t.toDouble(), r.toDouble(), btm.toDouble())
    }

    /** The share of [item]'s image, as fractions of all of it, that shows in the part [f] of its box: what [ImageItem.part] becomes when cut to [f]. */
    fun partOf(item: ImageItem, f: Rect): Rect {
        val a = toPicture(item, f.left - 0.5, f.top - 0.5)
        val b = toPicture(item, f.right - 0.5, f.bottom - 0.5)
        val p = item.part ?: WHOLE
        fun x(u: Double) = p.left + (u + 0.5) * p.w
        fun y(v: Double) = p.top + (v + 0.5) * p.h
        return Rect.ltrb(x(minOf(a.x, b.x)), y(minOf(a.y, b.y)), x(maxOf(a.x, b.x)), y(maxOf(a.y, b.y)))
    }

    /** All of a picture, as a part. */
    val WHOLE = Rect(0.0, 0.0, 1.0, 1.0)

    /**
     * Where the line through [at] across the direction [n] (a unit vector) cuts [item]'s box: the
     * part of the box on the side [n] points away from, then the part on the side it points to, as
     * fractions of the box. Null when the line misses the box, or the picture stands at a free angle
     * a straight cut cannot follow.
     */
    fun split(item: ImageItem, at: Pt, n: Pt): Pair<Rect, Rect>? {
        val q = Math.round(item.angle / (PI / 2.0))
        if (abs(item.angle - q * PI / 2.0) > ANGLE_EPS) return null
        val r = item.rect
        // The line in the box's own frame, from its centre: turning the box back turns the line with it.
        val cs = cos(-item.angle)
        val sn = sin(-item.angle)
        val dx = at.x - r.center.x
        val dy = at.y - r.center.y
        val px = dx * cs - dy * sn
        val py = dx * sn + dy * cs
        val bx = n.x * cs - n.y * sn
        val by = n.x * sn + n.y * cs
        return if (abs(bx) > abs(by)) {
            val t = px / r.w + 0.5
            if (t <= EDGE_EPS || t >= 1.0 - EDGE_EPS) return null
            val first = Rect.ltrb(0.0, 0.0, t, 1.0)
            val second = Rect.ltrb(t, 0.0, 1.0, 1.0)
            if (bx > 0) first to second else second to first
        } else {
            val t = py / r.h + 0.5
            if (t <= EDGE_EPS || t >= 1.0 - EDGE_EPS) return null
            val first = Rect.ltrb(0.0, 0.0, 1.0, t)
            val second = Rect.ltrb(0.0, t, 1.0, 1.0)
            if (by > 0) first to second else second to first
        }
    }

    /** A turn this close to a quarter is one, as far as a straight cut is concerned. */
    private const val ANGLE_EPS = 1e-6

    /** A cut this close to a box's edge, as a fraction of it, leaves nothing to show on one side. */
    private const val EDGE_EPS = 1e-6

    /** A point of the box, as fractions from its centre, as fractions of the picture from its centre. */
    private fun toPicture(item: ImageItem, dx: Double, dy: Double): Pt {
        val x = if (item.flip.x) -dx else dx
        val y = if (item.flip.y) -dy else dy
        return when (((item.orientation % 360) + 360) % 360) {
            90 -> Pt(y, -x)
            180 -> Pt(-x, -y)
            270 -> Pt(-y, x)
            else -> Pt(x, y)
        }
    }

    /** [item]'s box cut down to the part [f], keeping its free turn about where it stood. */
    fun croppedRect(item: ImageItem, f: Rect): Rect {
        val r = item.rect
        val ox = ((f.left + f.right) / 2.0 - 0.5) * r.w
        val oy = ((f.top + f.bottom) / 2.0 - 0.5) * r.h
        val c = Pt(r.center.x + ox * cos(item.angle) - oy * sin(item.angle), r.center.y + ox * sin(item.angle) + oy * cos(item.angle))
        val w = (f.right - f.left) * r.w
        val h = (f.bottom - f.top) * r.h
        return Rect(c.x - w / 2.0, c.y - h / 2.0, w, h)
    }

    /** True when [f] leaves the whole picture showing, which makes a crop nothing to do. */
    fun isWhole(f: Rect): Boolean = f.left <= 1e-4 && f.top <= 1e-4 && f.right >= 1 - 1e-4 && f.bottom >= 1 - 1e-4
}
