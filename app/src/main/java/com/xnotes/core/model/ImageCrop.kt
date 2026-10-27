package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
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
        val a = toPicture(item, f.left - 0.5, f.top - 0.5)
        val b = toPicture(item, f.right - 0.5, f.bottom - 0.5)
        val l = ((minOf(a.x, b.x) + 0.5) * w).roundToInt().coerceIn(0, w)
        val t = ((minOf(a.y, b.y) + 0.5) * h).roundToInt().coerceIn(0, h)
        val r = ((maxOf(a.x, b.x) + 0.5) * w).roundToInt().coerceIn(0, w)
        val btm = ((maxOf(a.y, b.y) + 0.5) * h).roundToInt().coerceIn(0, h)
        return Rect.ltrb(l.toDouble(), t.toDouble(), r.toDouble(), btm.toDouble())
    }

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
