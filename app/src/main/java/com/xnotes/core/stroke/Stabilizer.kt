package com.xnotes.core.stroke

import kotlin.math.PI
import kotlin.math.hypot

/**
 * Pen stabilization: a 1-euro filter (Casiez, Roussel and Vogel, CHI 2012) on the nib's position.
 *
 * A one-pole low-pass whose cutoff climbs with the nib's speed: at a steady speed `v` the steadied
 * point runs `trail * v / (crossover + v)` behind the nib, so slow, shaky movement is held back hard
 * and a quick line is left almost alone. Both settings are dp of hand travel ([trail] the most it
 * lags, [crossover] the speed in dp/s below which movement counts as slow), converted with the
 * stroke's speed scale so zoom and screen density do not change the feel. Positions are content px,
 * times ms.
 */
class Stabilizer(val trail: Double, val crossover: Double, speedScale: Double) {

    /** Hz of cutoff per content px/s of speed. */
    private val beta = speedScale / (2.0 * PI * trail)

    /** The cutoff with the nib at rest, in Hz. */
    private val minCutoff = crossover / (2.0 * PI * trail)

    /** The steadied position, content px. */
    var x = 0.0
        private set
    var y = 0.0
        private set

    /** The nib's velocity, low-passed, in content px per second. */
    private var vx = 0.0
    private var vy = 0.0

    /** Where the nib was at the last sample: its speed is read from its own movement, never against the trail. */
    private var nibX = 0.0
    private var nibY = 0.0

    /** When the last sample came in, ms. */
    private var time = 0.0

    /** The cutoff the last sample was filtered at, in Hz. */
    private var cutoff = minCutoff

    /** Whether a sample has been filtered since [start]; until then the steadied point is the nib. */
    var moved = false
        private set

    /** Begin a stroke at the nib's first position. */
    fun start(x: Double, y: Double, t: Double) {
        this.x = x
        this.y = y
        vx = 0.0
        vy = 0.0
        nibX = x
        nibY = y
        time = t
        cutoff = minCutoff
        moved = false
    }

    /** Take the nib at ([px], [py]) at [t] ms, moving the steadied point ([x], [y]) toward it. */
    fun step(px: Double, py: Double, t: Double) {
        var dt = (t - time) / 1000.0
        if (!(dt > MIN_DT)) dt = FALLBACK_DT
        time = t
        val ad = alpha(dt, SPEED_CUTOFF)
        vx += ad * ((px - nibX) / dt - vx)
        vy += ad * ((py - nibY) / dt - vy)
        nibX = px
        nibY = py
        cutoff = minCutoff + beta * hypot(vx, vy)
        val a = alpha(dt, cutoff)
        x += a * (px - x)
        y += a * (py - y)
        moved = true
    }

    /** How far the steadied point runs behind the nib in time at the speed just measured, ms. */
    val lagMs: Double get() = 1000.0 / (2.0 * PI * cutoff)

    /** The time a tip drawn out to the nib is given: the last sample's, plus the lag. */
    val tipTime: Double get() = time + lagMs

    companion object {
        /** The trail a pen starts at, in dp: 30 on the Strength slider. */
        const val DEFAULT_TRAIL = 3.6

        /** The crossover a pen starts at, in dp/s: about 9 mm/s of hand travel. */
        const val DEFAULT_CROSSOVER = 2.0 * PI * 9.0

        /** The cutoff the nib's speed is low-passed at, in Hz; the paper's 1 Hz notices a pen setting off far too late. */
        const val SPEED_CUTOFF = 8.0

        /** Samples closer together than this (seconds) are taken as [FALLBACK_DT] apart. */
        private const val MIN_DT = 1e-4
        private const val FALLBACK_DT = 1e-3

        /** The steadying a stroke draws through, or null when it is off or its settings would steady nothing. */
        fun forStroke(on: Boolean, trail: Double, crossover: Double, speedScale: Double): Stabilizer? =
            if (on && trail > 0.0 && crossover > 0.0 && speedScale > 0.0) Stabilizer(trail, crossover, speedScale) else null

        /** The one-pole smoothing factor for a step of [dt] seconds at cutoff [fc] Hz. */
        private fun alpha(dt: Double, fc: Double): Double {
            val r = 2.0 * PI * fc * dt
            return r / (r + 1.0)
        }
    }
}
