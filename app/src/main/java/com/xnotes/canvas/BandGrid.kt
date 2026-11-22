package com.xnotes.canvas

import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Renderer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How one page layer is cut into bands: the raster its footprint [cover] takes at [res] px per page
 * px, sliced along the axis the page scrolls on, which runs across the page ([sideways]) when the
 * view shows it a quarter turned. A band is [bandPx] rows of that raster. Its surface also holds
 * [gutter] rows of each neighbour, so a blit that scales or lands between pixels samples real ones
 * at a seam, and so what PDFium draws differently at the edge of a region stays out of sight.
 */
class BandGrid(val cover: Rect, val res: Double, val sideways: Boolean, val bandPx: Int, val gutter: Int) {
    /** The raster's size in px across the bands. */
    val across: Int = ceil((if (sideways) cover.h else cover.w) * res).toInt().coerceAtLeast(1)

    /** The raster's size in px along the bands. */
    val length: Int = ceil((if (sideways) cover.w else cover.h) * res).toInt().coerceAtLeast(1)

    val count: Int = (length + bandPx - 1) / bandPx

    /** The first row band [k] shows. */
    fun from(k: Int): Int = k * bandPx

    /** The row after the last one band [k] shows. */
    fun to(k: Int): Int = min(length, (k + 1) * bandPx)

    private fun heldFrom(k: Int): Int = max(0, from(k) - gutter)

    private fun heldTo(k: Int): Int = min(length, to(k) + gutter)

    private fun span(a: Int, b: Int): Rect =
        if (sideways) Rect(cover.left + a / res, cover.top, (b - a) / res, across / res)
        else Rect(cover.left, cover.top + a / res, across / res, (b - a) / res)

    /** The page-space rect band [k] shows. */
    fun shown(k: Int): Rect = span(from(k), to(k))

    /** What of the footprint band [k] shows: [shown] without the part of a pixel the raster runs past the paper by. */
    fun onPage(k: Int): Rect {
        val s = shown(k)
        return Rect.ltrb(s.left, s.top, min(s.right, cover.right), min(s.bottom, cover.bottom))
    }

    /** The page-space rect band [k]'s surface holds: what it shows, and its gutters. */
    fun held(k: Int): Rect = span(heldFrom(k), heldTo(k))

    fun surfaceW(k: Int): Int = if (sideways) heldTo(k) - heldFrom(k) else across

    fun surfaceH(k: Int): Int = if (sideways) across else heldTo(k) - heldFrom(k)

    fun bytes(k: Int): Long = 4L * surfaceW(k) * surfaceH(k)

    /** The part of band [k]'s surface that it shows, in the surface's pixels. */
    fun source(k: Int): Rect {
        val skip = (from(k) - heldFrom(k)).toDouble()
        val rows = (to(k) - from(k)).toDouble()
        return if (sideways) Rect(skip, 0.0, rows, across.toDouble()) else Rect(0.0, skip, across.toDouble(), rows)
    }

    /** Put [r], which draws into band [k]'s surface, in page space. */
    fun enter(r: Renderer, k: Int) {
        if (sideways) r.translate(-heldFrom(k).toDouble(), 0.0) else r.translate(0.0, -heldFrom(k).toDouble())
        r.scale(res, res)
        r.translate(-cover.left, -cover.top)
    }

    /** The bands that show any of page-space [rect], or whose surfaces hold any of it when [held]. Empty when it misses the page. */
    fun meeting(rect: Rect, held: Boolean = false): IntRange {
        val a = (if (sideways) rect.left - cover.left else rect.top - cover.top) * res
        val b = (if (sideways) rect.right - cover.left else rect.bottom - cover.top) * res
        val pad = if (held) gutter else 0
        val first = floor((a - pad) / bandPx).toInt().coerceAtLeast(0)
        val last = (ceil((b + pad) / bandPx).toInt() - 1).coerceAtMost(count - 1)
        return first..last
    }

    /** True when this is the grid a layer over [cover] at [res] takes, sliced the same way. */
    fun fits(cover: Rect, res: Double, sideways: Boolean): Boolean =
        this.cover == cover && this.sideways == sideways && abs(this.res - res) < 1e-6
}
