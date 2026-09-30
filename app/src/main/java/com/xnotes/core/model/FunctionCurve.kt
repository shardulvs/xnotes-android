package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.pow

/**
 * The function a [com.xnotes.core.tools.ShapeKind.FUNCTION] shape plots: y = [expr] for x in [from, to].
 * [yMin]/[yMax] clip the plot (tan, cot): values outside are cut off and the curve breaks there.
 */
data class FunctionSpec(
    val expr: String,
    val from: Double,
    val to: Double,
    val yMin: Double? = null,
    val yMax: Double? = null,
) {

    private val clipped: Boolean get() = yMin != null && yMax != null && yMax > yMin

    /**
     * The curve sampled across the domain and fitted to the unit box (y down), or null when the
     * expression does not parse or has no real value anywhere in the domain. Where the curve leaves
     * the chart or jumps across a pole the list carries a [BREAK] marker between the two runs.
     */
    fun normalizedSamples(): List<Pt>? {
        val (pts, range) = plot() ?: return null
        val (lo, hi) = range
        return pts.map { if (it === BREAK) BREAK else Pt(it.x, (hi - it.y) / (hi - lo)) }
    }

    /** Height over width of the graph at equal x and y scale, so a drawn graph keeps its true shape. */
    fun naturalAspect(): Double? {
        val (_, range) = plot() ?: return null
        val w = to - from
        return if (w > 1e-9) (range.second - range.first) / w else null
    }

    /** Samples as (x fraction, raw y) with [BREAK]s, and the y range the box spans. */
    private fun plot(): Pair<List<Pt>, Pair<Double, Double>>? {
        val f = FunctionCurve.parse(expr) ?: return null
        if (!(to > from)) return null
        val clipLo = if (clipped) yMin!! else Double.NEGATIVE_INFINITY
        val clipHi = if (clipped) yMax!! else Double.POSITIVE_INFINITY
        val jump = if (clipped) (clipHi - clipLo) * 0.5 else Double.POSITIVE_INFINITY
        val out = ArrayList<Pt>(SAMPLES + 8)
        var prev: Pt? = null
        for (i in 0..SAMPLES) {
            val nx = i.toDouble() / SAMPLES
            val y = f(from + (to - from) * nx)
            val p = prev
            if (y.isFinite() && y >= clipLo && y <= clipHi) {
                if (p != null && abs(y - p.y) > jump) out.add(BREAK)
                else if (p == null && i > 0 && clipped) edgeCrossing(f, nx, clipLo, clipHi, entering = true)?.let { out.add(it) }
                prev = Pt(nx, y).also { out.add(it) }
            } else if (p != null) {
                if (clipped) edgeCrossing(f, nx, clipLo, clipHi, entering = false)?.let { out.add(it) }
                out.add(BREAK)
                prev = null
            }
        }
        while (out.isNotEmpty() && out.first() === BREAK) out.removeAt(0)
        while (out.isNotEmpty() && out.last() === BREAK) out.removeAt(out.size - 1)
        val real = out.filter { it !== BREAK }
        if (real.size < 2) return null
        var lo = if (clipped) clipLo else real.minOf { it.y }
        var hi = if (clipped) clipHi else real.maxOf { it.y }
        if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
        return out to (lo to hi)
    }

    /** Where the curve crosses the clip edge between the previous sample and [nx], found by bisection. */
    private fun edgeCrossing(f: (Double) -> Double, nx: Double, lo: Double, hi: Double, entering: Boolean): Pt? {
        val step = 1.0 / SAMPLES
        var a = nx - step
        var b = nx
        fun inside(t: Double) = f(from + (to - from) * t).let { it.isFinite() && it >= lo && it <= hi }
        repeat(24) {
            val m = (a + b) / 2.0
            if (inside(m) != entering) a = m else b = m
        }
        val t = if (entering) b else a
        val y = f(from + (to - from) * t)
        if (!y.isFinite()) return null
        return Pt(t, y.coerceIn(lo, hi))
    }

    companion object {
        const val SAMPLES = 200

        /** Marks a gap between two runs of a plotted curve. Compared by identity. */
        val BREAK = Pt(Double.NaN, Double.NaN)

        /** The presets on the shape tool's second page. */
        val PRESETS = listOf(
            FunctionSpec("x^2", -2.0, 2.0),
            FunctionSpec("x^3", -1.5, 1.5),
            FunctionSpec("x^(1/2)", 0.0, 4.0),
            FunctionSpec("ln(x)", 0.1, 6.0),
            FunctionSpec("e^x", -2.0, 2.0),
            FunctionSpec("sin(x)", -2 * PI, 2 * PI),
            FunctionSpec("cos(x)", -2 * PI, 2 * PI),
            FunctionSpec("tan(x)", -3 * PI / 2, 3 * PI / 2, -4.0, 4.0),
            FunctionSpec("cot(x)", -3 * PI / 2, 3 * PI / 2, -4.0, 4.0),
        )

        fun preset(expr: String): FunctionSpec = PRESETS.firstOrNull { it.expr == expr } ?: PRESETS.first()

        /** Split a point list at its [BREAK] markers (NaN points) into runs of two or more points. */
        fun runs(points: List<Pt>): List<List<Pt>> {
            if (points.none { it.x.isNaN() }) return listOf(points)
            val out = ArrayList<List<Pt>>()
            var cur = ArrayList<Pt>()
            for (p in points) {
                if (p.x.isNaN()) {
                    if (cur.size >= 2) out.add(cur)
                    cur = ArrayList()
                } else {
                    cur.add(p)
                }
            }
            if (cur.size >= 2) out.add(cur)
            return out
        }
    }
}

/**
 * A small parser for the plotted expressions: + - * / ^, parentheses, implicit multiplication
 * (2x, 3sin(x), x(x+1)), the constants pi and e, and the usual functions. A function name may
 * take its argument without parentheses ("sin2x" is sin(2x)).
 */
object FunctionCurve {

    fun parse(expr: String): ((Double) -> Double)? = runCatching {
        val p = Parser(expr.lowercase().replace(" ", "").replace("**", "^"))
        val f = p.sum()
        if (!p.done()) null else f
    }.getOrNull()

    /** A constant expression such as "2pi" or "-1/2", or null. */
    fun constant(expr: String): Double? = parse(expr)?.invoke(0.0)?.takeIf { it.isFinite() }

    private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
        "arcsin" to { x -> kotlin.math.asin(x) },
        "arccos" to { x -> kotlin.math.acos(x) },
        "arctan" to { x -> kotlin.math.atan(x) },
        "sinh" to { x -> kotlin.math.sinh(x) },
        "cosh" to { x -> kotlin.math.cosh(x) },
        "tanh" to { x -> kotlin.math.tanh(x) },
        "sqrt" to { x -> kotlin.math.sqrt(x) },
        "sin" to { x -> kotlin.math.sin(x) },
        "cos" to { x -> kotlin.math.cos(x) },
        "tan" to { x -> kotlin.math.tan(x) },
        "cot" to { x -> 1.0 / kotlin.math.tan(x) },
        "exp" to { x -> kotlin.math.exp(x) },
        "log" to { x -> kotlin.math.log10(x) },
        "abs" to { x -> abs(x) },
        "ln" to { x -> kotlin.math.ln(x) },
    )

    private class Parser(val s: String) {
        var i = 0

        fun done() = i >= s.length

        private fun peek(): Char? = s.getOrNull(i)

        fun sum(): (Double) -> Double {
            var left = product()
            while (true) {
                val op = peek()
                if (op != '+' && op != '-') return left
                i++
                val l = left
                val r = product()
                left = if (op == '+') { x -> l(x) + r(x) } else { x -> l(x) - r(x) }
            }
        }

        private fun product(): (Double) -> Double {
            var left = unary()
            while (true) {
                val op = peek() ?: return left
                val l = left
                left = when {
                    op == '*' || op == '/' -> {
                        i++
                        val r = unary()
                        if (op == '*') { x: Double -> l(x) * r(x) } else { x: Double -> l(x) / r(x) }
                    }
                    startsPrimary(op) -> power().let { r -> { x: Double -> l(x) * r(x) } }
                    else -> return left
                }
            }
        }

        private fun unary(): (Double) -> Double {
            if (peek() == '-') {
                i++
                val v = unary()
                return { x -> -v(x) }
            }
            if (peek() == '+') i++
            return power()
        }

        private fun power(): (Double) -> Double {
            val base = primary()
            if (peek() != '^') return base
            i++
            val exp = unary()
            return { x -> pow(base(x), exp(x)) }
        }

        private fun startsPrimary(c: Char) = c.isLetterOrDigit() || c == '.' || c == '('

        private fun primary(): (Double) -> Double {
            val c = peek() ?: error("end")
            if (c == '(') {
                i++
                val v = sum()
                if (peek() != ')') error("paren")
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (peek()?.let { it.isDigit() || it == '.' } == true) i++
                val v = s.substring(start, i).toDouble()
                return { v }
            }
            FUNCTIONS.entries.firstOrNull { s.startsWith(it.key, i) }?.let { (name, fn) ->
                i += name.length
                // Without parentheses the argument is one tight term, so "sin2x+1" is sin(2x) + 1.
                val arg = if (peek() == '(') primary() else tightProduct()
                return { x -> fn(arg(x)) }
            }
            if (s.startsWith("pi", i)) {
                i += 2
                return { PI }
            }
            i++
            return when (c) {
                'x' -> { x -> x }
                'e' -> { _ -> E }
                else -> error("symbol")
            }
        }

        private fun tightProduct(): (Double) -> Double {
            var left = power()
            while (peek()?.let { it.isLetterOrDigit() || it == '.' } == true) {
                val l = left
                val r = power()
                left = { x -> l(x) * r(x) }
            }
            return left
        }

        /** Real powers of negative bases with odd-denominator exponents (x^(1/3)) stay real. */
        private fun pow(b: Double, e: Double): Double {
            if (b >= 0.0 || e == kotlin.math.floor(e)) return b.pow(e)
            val inv = 1.0 / e
            val n = kotlin.math.round(inv)
            if (abs(inv - n) < 1e-9 && n.toLong() % 2L != 0L) return -((-b).pow(e))
            return Double.NaN
        }
    }
}
