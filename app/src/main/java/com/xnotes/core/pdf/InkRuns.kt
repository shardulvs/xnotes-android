package com.xnotes.core.pdf

import kotlin.math.roundToLong

/**
 * A stroke's ribbon as PDF strokes. On screen the ribbon is a brush disc swept down the centreline,
 * and a PDF stroke with round caps and joins is exactly that sweep at one width. So each segment is
 * stroked at the mean of its two discs, and neighbours whose widths print alike share one path.
 * Every sample is kept: this changes how the ink is written, never where it goes.
 *
 * Drawn the old way, a disc and a quad per sample, the same ink took about seven times the bytes.
 */
object InkRuns {

    /** Decimals of every number written: a ten-thousandth of a point. */
    const val DECIMALS = 4

    private const val UNIT = 10_000.0

    // Cubic Bezier quarter-circle handle length.
    private const val K = 0.5522847498307936

    /**
     * Append the operators that paint the [count] points from [from] of [centers] (interleaved x, y)
     * at [radii]. A point lands at (ox + x·sx, oy + y·sy) and a width is scaled by [scale]; [rgb] is
     * the colour, 0..1 a channel. Returns false, having written nothing, when no point has width.
     */
    fun append(
        sb: StringBuilder,
        centers: FloatArray,
        radii: FloatArray,
        from: Int,
        count: Int,
        ox: Double,
        oy: Double,
        sx: Double,
        sy: Double,
        scale: Double,
        rgb: DoubleArray,
    ): Boolean {
        if (count <= 0) return false
        val w = Writer(sb, centers, ox, oy, sx, sy, rgb)
        if (count == 1) {
            if (radii[from] > 0f) w.dot(from, radii[from] * scale)
            return w.wrote
        }
        val last = from + count - 1
        var i = from
        while (i < last) {
            val width = width(radii, i, scale)
            if (width == 0L) {
                i++
                continue
            }
            var j = i + 1
            while (j < last && width(radii, j, scale) == width) j++
            w.run(i, j, width / UNIT)
            i = j
        }
        return w.wrote
    }

    /** Segment [i]'s width, the sum of its two radii, in ten-thousandths of a point as printed. */
    private fun width(radii: FloatArray, i: Int, scale: Double): Long =
        ((radii[i] + radii[i + 1]).toDouble() * scale * UNIT).roundToLong()

    private class Writer(
        val sb: StringBuilder,
        val centers: FloatArray,
        val ox: Double,
        val oy: Double,
        val sx: Double,
        val sy: Double,
        val rgb: DoubleArray,
    ) {
        private val start = sb.length
        private var stroking = false
        private var filling = false
        private var lineWidth = -1.0

        val wrote get() = sb.length > start

        fun x(i: Int) = ox + centers[2 * i] * sx
        fun y(i: Int) = oy + centers[2 * i + 1] * sy

        /** Points [a]..[b] as one path [width] wide; a dot when they all print at one place. */
        fun run(a: Int, b: Int, width: Double) {
            val x0 = (x(a) * UNIT).roundToLong()
            val y0 = (y(a) * UNIT).roundToLong()
            var moved = false
            for (i in a + 1..b) {
                if ((x(i) * UNIT).roundToLong() != x0 || (y(i) * UNIT).roundToLong() != y0) {
                    moved = true
                    break
                }
            }
            // A path that never moves is drawn by some viewers and not others, so it is filled instead.
            if (!moved) return dot(a, width / 2.0)
            if (!stroking) {
                colour("RG")
                sb.append("1 J 1 j [] 0 d\n")
                stroking = true
            }
            if (width != lineWidth) {
                num(width)
                sb.append("w\n")
                lineWidth = width
            }
            point(x(a), y(a))
            sb.append("m\n")
            for (i in a + 1..b) {
                point(x(i), y(i))
                sb.append("l\n")
            }
            sb.append("S\n")
        }

        /** A filled disc of radius [r] (points) at point [i]. */
        fun dot(i: Int, r: Double) {
            if ((r * UNIT).roundToLong() == 0L) return
            if (!filling) {
                colour("rg")
                filling = true
            }
            val cx = x(i)
            val cy = y(i)
            point(cx + r, cy); sb.append("m\n")
            point(cx + r, cy + r * K); point(cx + r * K, cy + r); point(cx, cy + r); sb.append("c\n")
            point(cx - r * K, cy + r); point(cx - r, cy + r * K); point(cx - r, cy); sb.append("c\n")
            point(cx - r, cy - r * K); point(cx - r * K, cy - r); point(cx, cy - r); sb.append("c\n")
            point(cx + r * K, cy - r); point(cx + r, cy - r * K); point(cx + r, cy); sb.append("c\nh\nf\n")
        }

        private fun colour(op: String) {
            for (c in rgb) num(c)
            sb.append(op).append('\n')
        }

        private fun point(x: Double, y: Double) {
            num(x)
            num(y)
        }

        private fun num(v: Double) {
            PdfNumbers.append(sb, v, DECIMALS)
            sb.append(' ')
        }
    }
}
