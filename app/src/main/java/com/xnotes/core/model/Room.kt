package com.xnotes.core.model

import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer

/**
 * Room the Insert space tool opened on a page: a band [size] content px tall from [at] px below the
 * page's top edge, across its whole width. On a page turned a quarter since, the band stands
 * [sideways]: [size] wide from [at] px right of its left edge, down its whole height.
 */
data class Room(val at: Double, val size: Double, val sideways: Boolean = false) {
    val end: Double get() = at + size
}

/**
 * The arithmetic of a page's rooms ([Page.rooms]). Display space is the page as it stands, room
 * and all; source space is the page as it was before any room opened, which is where its PDF page
 * and the marks on its text lie. Rooms on one axis never touch: a cut inside a room or on its edge
 * grows that room.
 */
object Rooms {

    /** Shorter than this, in content px, a room or a band is nothing. */
    const val EPS = 1e-6

    /** The rooms on one axis, in order. */
    fun on(rooms: List<Room>, sideways: Boolean): List<Room> = rooms.filter { it.sideways == sideways }

    /** All the room on one axis. */
    fun total(rooms: List<Room>, sideways: Boolean): Double = rooms.sumOf { if (it.sideways == sideways) it.size else 0.0 }

    /** The room a position [along] its axis lies in, counting [slop] past either edge, or null. */
    fun at(rooms: List<Room>, along: Double, sideways: Boolean, slop: Double = 0.0): Room? =
        rooms.firstOrNull { it.sideways == sideways && along >= it.at - slop && along <= it.end + slop }

    /** [rooms] with [size] more room at [at]: a cut inside a room or on its edge grows that room, and every room after moves on. */
    fun opened(rooms: List<Room>, at: Double, size: Double, sideways: Boolean = false): List<Room> {
        if (size <= EPS) return rooms
        val grown = at(rooms, at, sideways)
        val out = ArrayList<Room>(rooms.size + 1)
        for (r in rooms) {
            out += when {
                r.sideways != sideways -> r
                r === grown -> r.copy(size = r.size + size)
                r.at > at -> r.copy(at = r.at + size)
                else -> r
            }
        }
        if (grown == null) out += Room(at, size, sideways)
        return sorted(out)
    }

    /** [rooms] with the strip [from] to [to] of [room] closed, clamped to it; a room closed to nothing is gone, and every room after moves back. */
    fun closed(rooms: List<Room>, room: Room, from: Double, to: Double): List<Room> {
        val a = maxOf(from, room.at)
        val b = minOf(to, room.end)
        val shut = b - a
        if (shut <= EPS) return rooms
        val out = ArrayList<Room>(rooms.size)
        for (r in rooms) {
            when {
                r == room -> if (r.size - shut > EPS) out += r.copy(size = r.size - shut)
                r.sideways == room.sideways && r.at > room.at -> out += r.copy(at = r.at - shut)
                else -> out += r
            }
        }
        return out
    }

    /** A display position along an axis as the source position it shows; a position in a room maps to where that room opened. */
    fun toSource(rooms: List<Room>, along: Double, sideways: Boolean): Double {
        var shift = 0.0
        for (r in rooms) {
            if (r.sideways != sideways) continue
            if (along >= r.end) shift += r.size else if (along > r.at) return r.at - shift else break
        }
        return along - shift
    }

    /** A source position along an axis as the display position it shows at; a position exactly where a room opened stays above it. */
    fun toDisplay(rooms: List<Room>, along: Double, sideways: Boolean): Double {
        var shift = 0.0
        for (r in rooms) {
            if (r.sideways != sideways) continue
            if (along > r.at - shift) shift += r.size else break
        }
        return along + shift
    }

    /** One stretch of source between two cuts, shown [shift] further along. */
    class Band(val from: Double, val to: Double, val shift: Double)

    /** The source's [length] along an axis cut into the bands its rooms open between, empty ones left out. */
    fun bands(rooms: List<Room>, length: Double, sideways: Boolean): List<Band> {
        val out = ArrayList<Band>()
        var from = 0.0
        var shift = 0.0
        for (r in rooms) {
            if (r.sideways != sideways) continue
            val cut = r.at - shift
            if (cut - from > EPS && cut > 0.0) out += Band(maxOf(from, 0.0), minOf(cut, length), shift)
            from = maxOf(from, cut)
            shift += r.size
        }
        if (length - from > EPS) out += Band(maxOf(from, 0.0), length, shift)
        return out.filter { it.to - it.from > EPS }
    }

    /** One rectangle of source, shown moved by [dx], [dy]. */
    class Tile(val source: Rect, val dx: Double, val dy: Double) {
        val display: Rect get() = source.translate(dx, dy)
    }

    /** A [sourceW] by [sourceH] source cut into the tiles its rooms open between. One tile without rooms. */
    fun tiles(rooms: List<Room>, sourceW: Double, sourceH: Double): List<Tile> {
        val xs = bands(rooms, sourceW, true)
        val ys = bands(rooms, sourceH, false)
        val out = ArrayList<Tile>(xs.size * ys.size)
        for (y in ys) for (x in xs) out += Tile(Rect(x.from, y.from, x.to - x.from, y.to - y.from), x.shift, y.shift)
        return out
    }

    /**
     * The paper of [cover] a [sourceW] by [sourceH] source does not show on once its rooms open:
     * the margins round it and the rooms through it. Just the margins without rooms.
     */
    fun paper(cover: Rect, rooms: List<Room>, sourceW: Double, sourceH: Double): List<Rect> {
        val xs = shown(bands(rooms, sourceW, true))
        val ys = shown(bands(rooms, sourceH, false))
        val out = ArrayList<Rect>()
        for ((a, b) in gaps(cover.top, cover.bottom, ys)) out += Rect(cover.left, a, cover.w, b - a)
        for ((a, b) in gaps(cover.left, cover.right, xs)) {
            for ((t, u) in ys) {
                val top = maxOf(t, cover.top)
                val bottom = minOf(u, cover.bottom)
                if (bottom - top > EPS) out += Rect(a, top, b - a, bottom - top)
            }
        }
        return out
    }

    /** Where [bands] show, display intervals in order. */
    private fun shown(bands: List<Band>): List<Pair<Double, Double>> = bands.map { it.from + it.shift to it.to + it.shift }

    /** The stretches of [from] to [to] that [taken], intervals in order, leave. */
    private fun gaps(from: Double, to: Double, taken: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var at = from
        for ((a, b) in taken) {
            if (a - at > EPS) out += at to minOf(a, to)
            at = maxOf(at, b)
        }
        if (to - at > EPS) out += at to to
        return out.filter { it.second - it.first > EPS }
    }

    /** The source the tiles of a [sourceW] by [sourceH] source show within [region] come from, or null when none shows there. */
    fun sourceOf(region: Rect, rooms: List<Room>, sourceW: Double, sourceH: Double): Rect? {
        var out: Rect? = null
        for (t in tiles(rooms, sourceW, sourceH)) {
            val d = t.display
            val l = maxOf(d.left, region.left)
            val top = maxOf(d.top, region.top)
            val r = minOf(d.right, region.right)
            val b = minOf(d.bottom, region.bottom)
            if (r <= l || b <= top) continue
            val s = Rect(l - t.dx, top - t.dy, r - l, b - top)
            out = out?.union(s) ?: s
        }
        return out
    }

    /**
     * Draw [raster], a render of the source over [at] (source space), through [rooms]: each tile
     * where it shows, the room between them left bare. One render serves every tile, so the bands
     * match the page unbanded to the pixel.
     */
    fun drawBanded(r: Renderer, raster: RasterSurface, at: Rect, rooms: List<Room>, sourceW: Double, sourceH: Double) {
        for (t in tiles(rooms, sourceW, sourceH)) {
            if (!t.source.intersects(at)) continue
            r.save()
            r.clipRect(t.display)
            r.translate(t.dx, t.dy)
            r.drawRaster(raster, at)
            r.restore()
        }
    }

    /** [rooms] of a [w] by [h] page turned [turns] quarter turns clockwise with it. */
    fun turned(rooms: List<Room>, w: Double, h: Double, turns: Int): List<Room> {
        val q = PageTurn.normalize(turns)
        if (q == 0 || rooms.isEmpty()) return rooms
        return sorted(
            rooms.map { r ->
                when (q) {
                    1 -> if (r.sideways) Room(r.at, r.size, false) else Room(h - r.end, r.size, true)
                    2 -> if (r.sideways) Room(w - r.end, r.size, true) else Room(h - r.end, r.size, false)
                    else -> if (r.sideways) Room(w - r.end, r.size, false) else Room(r.at, r.size, true)
                }
            },
        )
    }

    /** [rooms] scaled by [k] with their page. */
    fun scaled(rooms: List<Room>, k: Double): List<Room> = if (k == 1.0) rooms else rooms.map { Room(it.at * k, it.size * k, it.sideways) }

    private fun sorted(rooms: List<Room>): List<Room> = rooms.sortedWith(compareBy<Room>({ it.sideways }, { it.at }))
}

/** The page's width without the room inserted into it: what its PDF page and margins measure against. */
val Page.sourceWidth: Double get() = width - Rooms.total(rooms, true)

/** The page's height without the room inserted into it. */
val Page.sourceHeight: Double get() = height - Rooms.total(rooms, false)
