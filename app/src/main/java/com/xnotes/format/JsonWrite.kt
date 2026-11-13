package com.xnotes.format

import java.io.Writer

/**
 * Minimal streaming JSON writer, the write-side twin of [JsonPull]. Emits the same
 * compact form org.json's DOM produced on Android (its number formatting, escape
 * rules and separator placement), so a note re-saved through this writer is
 * byte-identical to one the old path wrote, without ever materializing the DOM
 * or the whole manifest string.
 */
internal class JsonWrite(private val out: Writer) {

    private var first = BooleanArray(32)
    private var depth = 0
    private var afterName = false

    /** Scratch for [samplePoint]; big enough for five numbers, their separators and the brackets. */
    private val buf = CharArray(128)

    fun beginObject(): JsonWrite {
        element()
        out.write("{")
        push()
        return this
    }

    fun endObject(): JsonWrite {
        out.write("}")
        depth--
        return this
    }

    fun beginArray(): JsonWrite {
        element()
        out.write("[")
        push()
        return this
    }

    fun endArray(): JsonWrite {
        out.write("]")
        depth--
        return this
    }

    fun name(key: String): JsonWrite {
        if (first[depth - 1]) first[depth - 1] = false else out.write(",")
        string(key)
        out.write(":")
        afterName = true
        return this
    }

    fun value(v: String): JsonWrite {
        element()
        string(v)
        return this
    }

    fun value(v: Double): JsonWrite {
        element()
        out.write(formatDouble(v))
        return this
    }

    fun value(v: Int): JsonWrite {
        element()
        out.write(v.toString())
        return this
    }

    fun value(v: Boolean): JsonWrite {
        element()
        out.write(if (v) "true" else "false")
        return this
    }

    fun nullValue(): JsonWrite {
        element()
        out.write("null")
        return this
    }

    /**
     * One stroke sample as `[x,y,p]`, `[x,y,p,t]` when [time] is given, or `[x,y,p,t,w]` with the speed pen's [width]
     * factor too, rounded on the way out: 0.01 content px, 0.001 pressure and width, all far below anything visible.
     *
     * Its own method because samples are ~97% of a dense manifest and the general path spends ~8
     * [Writer.write] calls and three [Double.toString]s on each one. A rounded coordinate is an
     * exact scaled integer, so it formats from a long into a reusable buffer that goes out in a
     * single write. The bytes are identical to the general path, which still handles anything the
     * fixed-point form cannot reproduce: a magnitude where [Double.toString] turns to scientific
     * notation, or a fractional time.
     */
    fun samplePoint(x: Double, y: Double, pressure: Double, time: Double?, width: Double? = null): JsonWrite {
        val w = if (time != null) width else null
        var n = 0
        n = fixed(n, x, 100L)
        if (n >= 0) n = fixed(n, y, 100L)
        if (n >= 0) n = fixed(n, pressure, 1000L)
        if (n >= 0 && time != null) n = whole(n, time)
        if (n >= 0 && w != null) n = fixed(n, w, 1000L)
        if (n < 0) return samplePointSlow(x, y, pressure, time, w)
        element()
        buf[n++] = ']'
        out.write(buf, 0, n)
        return this
    }

    private fun samplePointSlow(x: Double, y: Double, pressure: Double, time: Double?, width: Double?): JsonWrite {
        beginArray().value(round(x, 100.0)).value(round(y, 100.0)).value(round(pressure, 1000.0))
        if (time != null) value(time)
        if (width != null) value(round(width, 1000.0))
        return endArray()
    }

    /**
     * Append [v] rounded to 1/[scale] as fixed point, prefixed by the separator its position needs.
     * Returns the new length, or -1 when [formatDouble] would not agree: it defers to
     * [Double.toString], which turns to scientific notation at 1e7. The small end needs no guard,
     * since a rounded value is either zero or at least 0.001, and the switch happens below that.
     */
    private fun fixed(at: Int, v: Double, scale: Long): Int {
        if (!v.isFinite() || v <= -1e7 || v >= 1e7) return -1
        var n = at
        buf[n++] = if (at == 0) '[' else ','
        var k = Math.round(v * scale)
        if (k < 0) {
            buf[n++] = '-'
            k = -k
        }
        n = digits(n, k / scale)
        val frac = k % scale
        if (frac == 0L) return n // an integral value prints as a long, as formatDouble does
        buf[n++] = '.'
        var place = scale / 10L
        var rest = frac
        while (place > 0L) {
            val d = rest / place
            rest %= place
            n = digits(n, d)
            if (rest == 0L) break // trailing zeros are not part of the shortest form
            place /= 10L
        }
        return n
    }

    /** Append an integral [v] (a sample time), or -1 when it is not one the fast path can print. */
    private fun whole(at: Int, v: Double): Int {
        if (!v.isFinite() || v <= -1e7 || v >= 1e7) return -1
        val l = v.toLong()
        if (l.toDouble() != v) return -1
        var n = at
        buf[n++] = ','
        if (l < 0) {
            buf[n++] = '-'
            return digits(n, -l)
        }
        return digits(n, l)
    }

    private fun digits(at: Int, v: Long): Int {
        if (v < 10L) {
            buf[at] = ('0' + v.toInt())
            return at + 1
        }
        var len = 1
        var t = v
        while (t >= 10L) {
            t /= 10L
            len++
        }
        var i = at + len
        var rest = v
        while (rest > 0L) {
            buf[--i] = ('0' + (rest % 10L).toInt())
            rest /= 10L
        }
        return at + len
    }

    private fun round(v: Double, scale: Double): Double =
        if (v.isFinite()) Math.round(v * scale) / scale else v

    private fun push() {
        if (depth == first.size) first = first.copyOf(depth * 2)
        first[depth] = true
        depth++
    }

    /** Emits the ',' before an array element; a named value's ',' came with its name. */
    private fun element() {
        if (afterName) {
            afterName = false
            return
        }
        if (depth == 0) return
        if (first[depth - 1]) first[depth - 1] = false else out.write(",")
    }

    /** org.json's number form: integral doubles print as longs ("30", not "30.0"). */
    private fun formatDouble(v: Double): String {
        require(!v.isNaN() && !v.isInfinite()) { "Forbidden numeric value: $v" }
        if (v == 0.0 && 1.0 / v < 0) return "-0"
        val l = v.toLong()
        return if (l.toDouble() == v) l.toString() else v.toString()
    }

    private fun string(s: String) {
        out.write("\"")
        for (c in s) {
            when (c) {
                '"', '\\', '/' -> {
                    out.write('\\'.code)
                    out.write(c.code)
                }
                '\t' -> out.write("\\t")
                '\b' -> out.write("\\b")
                '\n' -> out.write("\\n")
                '\r' -> out.write("\\r")
                '\u000C' -> out.write("\\f")
                else -> if (c < ' ') {
                    out.write(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                } else {
                    out.write(c.code)
                }
            }
        }
        out.write("\"")
    }
}
