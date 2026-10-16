package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.FunctionSpec
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads a freehand open stroke as the graph of a well-known function (a parabola, a cubic, an
 * exponential, a logarithm or a sine wave) fitted to the stroke's bounding box, or null when none
 * follows it closely. Pure and deterministic, like [ShapeRecognizer].
 */
internal object FunctionFit {

    /** RMS vertical error, as a fraction of the box height, at or under which a fit is accepted. */
    private const val MAX_RMS = 0.06

    /** Share of the horizontal travel that may run backwards before the stroke is not a graph. */
    private const val MAX_BACKTRACK = 0.04

    /** A graph must be at least this wide and tall, as a fraction of the box diagonal. */
    private const val MIN_SIDE_FRAC = 0.12

    private const val GRID = 48

    private class Candidate(val expr: String, val from: Double, val to: Double, val f: (Double) -> Double)

    fun fit(path: List<Pt>, bbox: Rect, diag: Double): FunctionSpec? {
        if (bbox.w < MIN_SIDE_FRAC * diag || bbox.h < MIN_SIDE_FRAC * diag) return null
        var forward = 0.0
        var backward = 0.0
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            if (dx > 0) forward += dx else backward -= dx
        }
        val total = forward + backward
        if (total < 1e-9 || minOf(forward, backward) > MAX_BACKTRACK * total) return null
        val ltr = forward >= backward
        // The stroke as v(u): u across the box left to right, v down from the top, both in [0, 1].
        val us = DoubleArray(path.size) { (path[it].x - bbox.left) / bbox.w }
        val vs = DoubleArray(path.size) { (path[it].y - bbox.top) / bbox.h }
        if (!ltr) { us.reverse(); vs.reverse() }
        var best: Candidate? = null
        var bestRms = MAX_RMS
        for (c in candidates) {
            val rms = rms(c, us, vs) ?: continue
            if (rms < bestRms) {
                bestRms = rms
                best = c
            }
        }
        return best?.let { FunctionSpec(it.expr, it.from, it.to) }
    }

    /** RMS of the stroke's v against the candidate plotted in the same unit box, or null if it can't plot. */
    private fun rms(c: Candidate, us: DoubleArray, vs: DoubleArray): Double? {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0..GRID) {
            val y = c.f(c.from + (c.to - c.from) * i / GRID)
            if (!y.isFinite()) return null
            if (y < lo) lo = y
            if (y > hi) hi = y
        }
        if (hi - lo < 1e-9) return null
        var sum = 0.0
        for (i in us.indices) {
            val y = c.f(c.from + (c.to - c.from) * us[i].coerceIn(0.0, 1.0))
            val v = (hi - y) / (hi - lo)
            val d = v - vs[i]
            sum += d * d
        }
        return sqrt(sum / us.size)
    }

    private val candidates: List<Candidate> by lazy { buildCandidates() }

    private fun buildCandidates(): List<Candidate> {
        val out = ArrayList<Candidate>()
        // Power laws only depend on where zero sits in the domain, so one scale covers them all.
        for (k in -10..8) {
            val a = k / 10.0
            val sq = { x: Double -> x * x }
            out.add(Candidate("x^2", r(2 * a), 2.0, sq))
            out.add(Candidate("-x^2", r(2 * a), 2.0) { x -> -x * x })
            out.add(Candidate("x^2", -2.0, r(-2 * a), sq))
            out.add(Candidate("-x^2", -2.0, r(-2 * a)) { x -> -x * x })
            out.add(Candidate("x^3", r(1.5 * a), 1.5) { x -> x * x * x })
            out.add(Candidate("-x^3", r(1.5 * a), 1.5) { x -> -x * x * x })
            out.add(Candidate("x^3", -1.5, r(-1.5 * a)) { x -> x * x * x })
            out.add(Candidate("-x^3", -1.5, r(-1.5 * a)) { x -> -x * x * x })
        }
        // An exponential's shape only depends on the domain's width.
        for (k in 2..14) {
            val h = k / 4.0
            out.add(Candidate("e^x", -h, h) { x -> exp(x) })
            out.add(Candidate("-e^x", -h, h) { x -> -exp(x) })
            out.add(Candidate("e^(-x)", -h, h) { x -> exp(-x) })
            out.add(Candidate("-e^(-x)", -h, h) { x -> -exp(-x) })
        }
        // A logarithm's shape only depends on the ratio of the domain's ends.
        for (ratio in listOf(2.0, 3.0, 5.0, 8.0, 12.0, 20.0, 35.0, 60.0)) {
            val from = r(6.0 / ratio)
            out.add(Candidate("ln(x)", from, 6.0) { x -> ln(x) })
            out.add(Candidate("-ln(x)", from, 6.0) { x -> -ln(x) })
            out.add(Candidate("ln(-x)", -6.0, -from) { x -> ln(-x) })
            out.add(Candidate("-ln(-x)", -6.0, -from) { x -> -ln(-x) })
        }
        // Sine waves: half a period up to four, at sixteen phases.
        for (halves in 1..8) {
            val span = halves * PI
            for (ph in 0 until 16) {
                val phase = ph * PI / 8
                out.add(sine(phase, span))
            }
        }
        return out
    }

    /** sin over [phase, phase + span], written as cos when the phase lines up with one. */
    private fun sine(phase: Double, span: Double): Candidate {
        val eighths = Math.round(phase / (PI / 8)).toInt()
        val (expr, from) = when (eighths) {
            4 -> "cos(x)" to 0.0
            12 -> "-cos(x)" to 0.0
            8 -> "-sin(x)" to 0.0
            else -> "sin(x)" to phase
        }
        val f: (Double) -> Double = when (expr) {
            "cos(x)" -> { x -> kotlin.math.cos(x) }
            "-cos(x)" -> { x -> -kotlin.math.cos(x) }
            "-sin(x)" -> { x -> -sin(x) }
            else -> { x -> sin(x) }
        }
        return Candidate(expr, r(from), r(from + span), f)
    }

    /** Round a domain end so the Edit function dialog shows a tidy number. */
    private fun r(v: Double): Double = Math.round(v * 1e6) / 1e6
}
