package com.xnotes.core.fill

/**
 * The outlines of a set of cells, traced along the cells' own edges, and those outlines rid of
 * their stair steps. An area's outer edge runs clockwise on screen and a hole's the other way, so a
 * parity fill draws either. Where two cells meet only at a corner, the trace keeps them apart.
 */
internal object CellContours {

    private const val RIGHT = 0
    private const val DOWN = 1
    private const val LEFT = 2
    private const val UP = 3

    /** Every closed outline of the cells set in [inside] ([w] x [h]): corner coordinates x, y at each turn. */
    fun trace(inside: ByteArray, w: Int, h: Int): List<IntArray> {
        val cw = w + 1
        val edges = ByteArray(cw * (h + 1))
        fun isIn(x: Int, y: Int) = x in 0 until w && y in 0 until h && inside[y * w + x].toInt() != 0
        fun add(corner: Int, dir: Int) {
            edges[corner] = (edges[corner].toInt() or (1 shl dir)).toByte()
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (inside[y * w + x].toInt() == 0) continue
                if (!isIn(x, y - 1)) add(y * cw + x, RIGHT)
                if (!isIn(x + 1, y)) add(y * cw + x + 1, DOWN)
                if (!isIn(x, y + 1)) add((y + 1) * cw + x + 1, LEFT)
                if (!isIn(x - 1, y)) add((y + 1) * cw + x, UP)
            }
        }
        val all = edges.copyOf()
        val rings = ArrayList<IntArray>()
        var buf = IntArray(64)
        for (start in edges.indices) {
            if (edges[start].toInt() == 0) continue
            var n = 0
            var corner = start
            var dir = Integer.numberOfTrailingZeros(edges[start].toInt())
            var prev = -1
            while (true) {
                edges[corner] = (edges[corner].toInt() and (1 shl dir).inv()).toByte()
                if (dir != prev) {
                    if (n + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
                    buf[n++] = corner % cw
                    buf[n++] = corner / cw
                }
                prev = dir
                corner = when (dir) {
                    RIGHT -> corner + 1
                    DOWN -> corner + cw
                    LEFT -> corner - 1
                    else -> corner - cw
                }
                val out = all[corner].toInt()
                // At a corner two cells only touch diagonally, turn right: the trace keeps to one cell.
                dir = if (Integer.bitCount(out) == 1) Integer.numberOfTrailingZeros(out) else (prev + 1) % 4
                if (edges[corner].toInt() and (1 shl dir) == 0) break
            }
            if (n >= 6) rings.add(buf.copyOf(n))
        }
        return rings
    }

    /** [ring] (x, y pairs, closed) with every point within [tolerance] of its neighbours' chord dropped. */
    fun simplify(ring: IntArray, tolerance: Double): DoubleArray {
        val n = ring.size / 2
        if (n < 4) return DoubleArray(ring.size) { ring[it].toDouble() }
        var far = 0
        var farD = -1.0
        for (k in 1 until n) {
            val dx = (ring[2 * k] - ring[0]).toDouble()
            val dy = (ring[2 * k + 1] - ring[1]).toDouble()
            val d = dx * dx + dy * dy
            if (d > farD) {
                farD = d
                far = k
            }
        }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[far] = true
        reduce(ring, n, 0, far, tolerance, keep)
        reduce(ring, n, far, n, tolerance, keep)
        val out = DoubleArray(2 * keep.count { it })
        var m = 0
        for (k in 0 until n) {
            if (!keep[k]) continue
            out[m++] = ring[2 * k].toDouble()
            out[m++] = ring[2 * k + 1].toDouble()
        }
        return out
    }

    /** Douglas-Peucker between points [from] and [to] (exclusive span, [to] == [n] meaning point 0 again). */
    private fun reduce(ring: IntArray, n: Int, from: Int, to: Int, tolerance: Double, keep: BooleanArray) {
        val stack = ArrayDeque<Long>()
        stack.addLast(from.toLong() shl 32 or to.toLong())
        val tol2 = tolerance * tolerance
        while (stack.isNotEmpty()) {
            val span = stack.removeLast()
            val a = (span ushr 32).toInt()
            val b = (span and 0xffffffffL).toInt()
            if (b - a < 2) continue
            val ax = ring[2 * a].toDouble()
            val ay = ring[2 * a + 1].toDouble()
            val bi = b % n
            val bx = ring[2 * bi].toDouble()
            val by = ring[2 * bi + 1].toDouble()
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            var worst = -1
            var worstD = tol2
            for (k in a + 1 until b) {
                val px = ring[2 * k] - ax
                val py = ring[2 * k + 1] - ay
                val d = if (len2 < 1e-12) {
                    px * px + py * py
                } else {
                    val c = px * dy - py * dx
                    c * c / len2
                }
                if (d > worstD) {
                    worstD = d
                    worst = k
                }
            }
            if (worst < 0) continue
            keep[worst] = true
            stack.addLast(a.toLong() shl 32 or worst.toLong())
            stack.addLast(worst.toLong() shl 32 or b.toLong())
        }
    }
}
