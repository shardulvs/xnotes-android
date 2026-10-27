package com.xnotes.core.fill

import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.MeshData
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Where a point would drop its colour, as [FillMap.probe] finds it. */
sealed interface Probe {
    /** Paper that reaches the window's edge, or no paper at all: nothing closed to fill. */
    data object Open : Probe

    /** Lines all round the point and no paper near it. */
    data object Blocked : Probe

    /** A closed area, before it reaches under its lines; [FillMap.finish] shapes it. */
    class Enclosed internal constructor(
        internal val x0: Int,
        internal val y0: Int,
        internal val w: Int,
        internal val h: Int,
        internal val cells: ByteArray,
        /** Cells of paper in the area. */
        val count: Int,
    ) : Probe {
        /** True when grid cell ([gx], [gy]) is paper of this area. */
        fun holds(gx: Int, gy: Int): Boolean {
            val x = gx - x0
            val y = gy - y0
            return x in 0 until w && y in 0 until h && cells[y * w + x].toInt() and FillMap.MARK != 0
        }
    }
}

/**
 * What closes areas in one window, cell by cell: the lines, the paper that can reach the window's
 * edge (open, whatever is drawn round it), and where older fills already paint. Built once for a
 * drag over a window, then asked about every point the pointer crosses; one thread owns it.
 *
 * Every cell the window's edge can reach is marked once, when the map is sealed, so a point on open
 * paper answers at once and only an enclosed area is ever flooded.
 */
class FillMap(val grid: FillGrid) {
    val width = grid.width
    val height = grid.height
    internal val cells = ByteArray(width * height)
    private val raster = CellRaster(width, height)

    /** How far an area may reach under its lines, in cells: past the widest one's half, with slack. */
    var reach = MIN_REACH
        private set

    private var stack = IntArray(1024)
    private var top = 0
    private var minX = 0
    private var minY = 0
    private var maxX = 0
    private var maxY = 0
    private var count = 0

    /** Add a line that closes areas: [mesh] in content space, [halfWidth] its widest half in content px. */
    fun addLine(mesh: MeshData, halfWidth: Double) {
        val p = mesh.positions
        val idx = mesh.indices
        val sink = CellRaster.Sink { row, from, to -> or(row, from, to, LINE) }
        var t = 0
        while (t + 2 < idx.size) {
            val a = idx[t] * 2
            val b = idx[t + 1] * 2
            val c = idx[t + 2] * 2
            raster.triangle(
                grid.gx(p[a]), grid.gy(p[a + 1]), grid.gx(p[b]), grid.gy(p[b + 1]), grid.gx(p[c]), grid.gy(p[c + 1]), sink,
            )
            t += 3
        }
        reach = max(reach, ceil(halfWidth * grid.scale).toInt() + MIN_REACH)
    }

    /** Add where an older fill paints: [rings] in content space, filled by parity. */
    fun addPaint(rings: List<List<Pt>>) {
        raster.rings(rings.map(::toGrid)) { row, from, to -> or(row, from, to, PAINT) }
    }

    /** Finish building: whatever paper touches the window's edge is open. */
    fun seal() {
        for (x in 0 until width) {
            openFrom(x)
            openFrom((height - 1) * width + x)
        }
        for (y in 0 until height) {
            openFrom(y * width)
            openFrom(y * width + width - 1)
        }
    }

    private fun openFrom(cell: Int) {
        if (cells[cell].toInt() and (LINE or OUTSIDE) == 0) flood(cell, LINE, OUTSIDE)
    }

    /** The area content point [at] would fill: open paper, a closed area, or nothing but lines near it. */
    fun probe(at: Pt): Probe {
        val gx = grid.gx(at.x)
        val gy = grid.gy(at.y)
        if (gx < 0.0 || gy < 0.0 || gx >= width || gy >= height) return Probe.Open
        val seed = paperNear(gx, gy) ?: return Probe.Blocked
        if (cells[seed].toInt() and OUTSIDE != 0) return Probe.Open
        flood(seed, LINE or OUTSIDE, MARK)
        // A line's whole width and the paper past it, so its far side can claim its own half.
        val margin = 2 * reach + 2
        val x0 = max(0, minX - margin)
        val y0 = max(0, minY - margin)
        val x1 = min(width - 1, maxX + margin)
        val y1 = min(height - 1, maxY + margin)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val local = ByteArray(w * h)
        for (y in y0..y1) System.arraycopy(cells, y * width + x0, local, (y - y0) * w, w)
        val clear = MARK.inv()
        for (y in minY..maxY) {
            val row = y * width
            for (x in minX..maxX) cells[row + x] = (cells[row + x].toInt() and clear).toByte()
        }
        return Probe.Enclosed(x0, y0, w, h, local, count)
    }

    /** Grid cell of content point [at], for [Probe.Enclosed.holds]. */
    fun cellX(at: Pt): Int = floor(grid.gx(at.x)).toInt()
    fun cellY(at: Pt): Int = floor(grid.gy(at.y)).toInt()

    /**
     * Shape an enclosed area into the fill it makes. It reaches under its lines to their middles,
     * except under [keepOut] (content-space meshes of lines the fill will sit above); closed bits
     * inside it no more than [smallHole] cells across fill too, unless an older fill paints them.
     */
    fun finish(area: Probe.Enclosed, keepOut: List<MeshData> = emptyList(), smallHole: Int = 0): FillArea {
        val w = area.w
        val h = area.h
        val src = area.cells
        val own = ByteArray(w * h)
        for (i in own.indices) {
            val c = src[i].toInt()
            own[i] = when {
                c and MARK != 0 -> IN
                c and LINE != 0 -> WALL
                else -> REST
            }
        }
        if (keepOut.isNotEmpty()) keep(own, area, keepOut)
        grow(own, w, h)
        fillHoles(own, src, w, h, smallHole)
        val inside = ByteArray(w * h)
        var n = 0
        var edge = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!isIn(own[i])) continue
                inside[i] = 1
                n++
                val boundary = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                    !isIn(own[i - 1]) || !isIn(own[i + 1]) || !isIn(own[i - w]) || !isIn(own[i + w])
                if (boundary) edge++
            }
        }
        val rings = CellContours.trace(inside, w, h).mapNotNull { ring ->
            val pts = CellContours.simplify(ring, SIMPLIFY_CELLS)
            if (pts.size < 6) null else List(pts.size / 2) { k -> grid.toContent(area.x0 + pts[2 * k], area.y0 + pts[2 * k + 1]) }
        }
        return FillArea(grid, area.x0, area.y0, w, h, inside, n, edge, rings)
    }

    /** Mark the lines under [keepOut] as ones the area may not reach under. */
    private fun keep(own: ByteArray, area: Probe.Enclosed, keepOut: List<MeshData>) {
        val r = CellRaster(area.w, area.h)
        val sink = CellRaster.Sink { row, from, to ->
            val base = row * area.w
            for (x in from..to) if (own[base + x] == WALL) own[base + x] = KEPT
        }
        val ox = area.x0.toDouble()
        val oy = area.y0.toDouble()
        for (mesh in keepOut) {
            val p = mesh.positions
            val idx = mesh.indices
            var t = 0
            while (t + 2 < idx.size) {
                val a = idx[t] * 2
                val b = idx[t + 1] * 2
                val c = idx[t + 2] * 2
                r.triangle(
                    grid.gx(p[a]) - ox, grid.gy(p[a + 1]) - oy, grid.gx(p[b]) - ox, grid.gy(p[b + 1]) - oy,
                    grid.gx(p[c]) - ox, grid.gy(p[c + 1]) - oy, sink,
                )
                t += 3
            }
        }
    }

    /**
     * Split the lines between the area and the paper beyond them, a layer at a time from both
     * sides, so each line cell goes to whichever side is nearer and the area stops at the middle.
     */
    private fun grow(own: ByteArray, w: Int, h: Int) {
        var front = IntArray(256)
        var n = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val o = own[i]
                if (o != IN && o != REST) continue
                val nearWall = (x > 0 && own[i - 1] == WALL) || (x < w - 1 && own[i + 1] == WALL) ||
                    (y > 0 && own[i - w] == WALL) || (y < h - 1 && own[i + w] == WALL)
                if (!nearWall) continue
                if (n == front.size) front = front.copyOf(n * 2)
                front[n++] = i
            }
        }
        var next = IntArray(front.size)
        repeat(reach) {
            if (n == 0) return
            var m = 0
            for (k in 0 until n) {
                val i = front[k]
                val side = if (isIn(own[i])) GROWN else SHARED
                val x = i % w
                val y = i / w
                for (d in 0 until 4) {
                    val j = when (d) {
                        0 -> if (x > 0) i - 1 else -1
                        1 -> if (x < w - 1) i + 1 else -1
                        2 -> if (y > 0) i - w else -1
                        else -> if (y < h - 1) i + w else -1
                    }
                    if (j < 0 || own[j] != WALL) continue
                    own[j] = side
                    if (m == next.size) next = next.copyOf(m * 2)
                    next[m++] = j
                }
            }
            val t = front
            front = next
            next = if (t.size >= front.size) t else IntArray(front.size)
            n = m
        }
    }

    private var holeStack = IntArray(256)
    private var holeCells = IntArray(256)
    private var holeCount = 0
    private var holeW = 0
    private var holeH = 0
    private var holePaper = 0
    private var holePainted = 0

    /** Let small closed bits inside the area fill too, unless an older fill already paints them. */
    private fun fillHoles(own: ByteArray, src: ByteArray, w: Int, h: Int, small: Int) {
        val seen = ByteArray(w * h)
        // What the window's edge reaches is outside the area, never a hole in it.
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x != 0 && y != 0 && x != w - 1 && y != h - 1) continue
                val i = y * w + x
                if (seen[i].toInt() == 0 && !isIn(own[i])) spread(i, own, src, seen, w, h)
            }
        }
        if (small <= 0) return
        for (i in own.indices) {
            if (seen[i].toInt() != 0 || isIn(own[i])) continue
            spread(i, own, src, seen, w, h)
            val paintedOver = holePaper > 0 && holePainted * 2 >= holePaper
            if (max(holeW, holeH) <= small && !paintedOver) for (k in 0 until holeCount) own[holeCells[k]] = IN
        }
    }

    /** Visit the run of cells outside the area joined to [start], noting its cells, extent and paper. */
    private fun spread(start: Int, own: ByteArray, src: ByteArray, seen: ByteArray, w: Int, h: Int) {
        var sp = 0
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        holeCount = 0
        holePaper = 0
        holePainted = 0
        holeStack[sp++] = start
        seen[start] = 1
        while (sp > 0) {
            val i = holeStack[--sp]
            val x = i % w
            val y = i / w
            if (holeCount == holeCells.size) holeCells = holeCells.copyOf(holeCount * 2)
            holeCells[holeCount++] = i
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
            if (own[i] == REST) {
                holePaper++
                if (src[i].toInt() and PAINT != 0) holePainted++
            }
            for (d in 0 until 4) {
                val j = when (d) {
                    0 -> if (x > 0) i - 1 else -1
                    1 -> if (x < w - 1) i + 1 else -1
                    2 -> if (y > 0) i - w else -1
                    else -> if (y < h - 1) i + w else -1
                }
                if (j < 0 || seen[j].toInt() != 0 || isIn(own[j])) continue
                seen[j] = 1
                if (sp == holeStack.size) holeStack = holeStack.copyOf(sp * 2)
                holeStack[sp++] = j
            }
        }
        holeW = x1 - x0 + 1
        holeH = y1 - y0 + 1
    }

    /** The nearest cell to ([gx], [gy]) that is not a line, within [NEAR] cells; null when there is none. */
    private fun paperNear(gx: Double, gy: Double): Int? {
        val cx = floor(gx).toInt().coerceIn(0, width - 1)
        val cy = floor(gy).toInt().coerceIn(0, height - 1)
        if (cells[cy * width + cx].toInt() and LINE == 0) return cy * width + cx
        var best = -1
        var bestD = Double.MAX_VALUE
        for (y in max(0, cy - NEAR)..min(height - 1, cy + NEAR)) {
            for (x in max(0, cx - NEAR)..min(width - 1, cx + NEAR)) {
                if (cells[y * width + x].toInt() and LINE != 0) continue
                val dx = x + 0.5 - gx
                val dy = y + 0.5 - gy
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    best = y * width + x
                }
            }
        }
        return if (best >= 0 && bestD <= (NEAR + 0.5) * (NEAR + 0.5)) best else null
    }

    /** Scanline flood from [seed] through cells with none of [stop], setting [set]; leaves the bounds and count. */
    private fun flood(seed: Int, stop: Int, set: Int) {
        val block = stop or set
        minX = width
        minY = height
        maxX = -1
        maxY = -1
        count = 0
        top = 0
        push(seed)
        while (top > 0) {
            val c = stack[--top]
            if (cells[c].toInt() and block != 0) continue
            val y = c / width
            val row = y * width
            var l = c - row
            var r = l
            while (l > 0 && cells[row + l - 1].toInt() and block == 0) l--
            while (r < width - 1 && cells[row + r + 1].toInt() and block == 0) r++
            for (x in l..r) cells[row + x] = (cells[row + x].toInt() or set).toByte()
            count += r - l + 1
            if (l < minX) minX = l
            if (r > maxX) maxX = r
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (y > 0) seedRow(y - 1, l, r, block)
            if (y < height - 1) seedRow(y + 1, l, r, block)
        }
    }

    /** Push one seed per run of open cells in row [y] between [l] and [r]. */
    private fun seedRow(y: Int, l: Int, r: Int, block: Int) {
        val row = y * width
        var x = l
        while (x <= r) {
            if (cells[row + x].toInt() and block == 0) {
                push(row + x)
                while (x <= r && cells[row + x].toInt() and block == 0) x++
            } else {
                x++
            }
        }
    }

    private fun push(cell: Int) {
        if (top == stack.size) stack = stack.copyOf(top * 2)
        stack[top++] = cell
    }

    private fun or(row: Int, from: Int, to: Int, bit: Int) {
        val base = row * width
        for (x in from..to) cells[base + x] = (cells[base + x].toInt() or bit).toByte()
    }

    private fun toGrid(ring: List<Pt>): DoubleArray {
        val out = DoubleArray(ring.size * 2)
        for ((k, p) in ring.withIndex()) {
            out[2 * k] = grid.gx(p.x)
            out[2 * k + 1] = grid.gy(p.y)
        }
        return out
    }

    companion object {
        internal const val LINE = 1
        internal const val OUTSIDE = 2
        internal const val PAINT = 4
        internal const val MARK = 8

        private const val IN: Byte = 1
        private const val WALL: Byte = 2
        private const val REST: Byte = 3
        private const val KEPT: Byte = 4
        private const val GROWN: Byte = 5
        private const val SHARED: Byte = 6

        private fun isIn(o: Byte) = o == IN || o == GROWN

        /** Cells an area reaches past any line's half width, for the rasterizer's own spread. */
        private const val MIN_REACH = 2

        /** How far a point on a line looks for paper beside it, in cells. */
        private const val NEAR = 3

        /** How far a traced edge may stray to lose its stair steps, in cells. */
        private const val SIMPLIFY_CELLS = 0.9
    }
}
