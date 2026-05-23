package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt

/** One captured stylus point, page-local; `pressure` in `[0, 1]`. */
data class Sample(val x: Double, val y: Double, val pressure: Double) {
    val pos: Pt get() = Pt(x, y)
}

/** A round end-cap (head/tail or a single-sample dot). */
data class Cap(val center: Pt, val radius: Double)

/**
 * The geometry derived from a stroke's samples (spec 03). Rendering fills
 * [outline] (nonzero winding) **and** the [caps] discs in the ink colour, with
 * no outline pen.
 */
data class StrokeGeometry(
    val outline: List<Pt>,
    val caps: List<Cap>,
    val centerline: List<Pt>,
    val halfWidths: List<Double>,
) {
    companion object {
        val EMPTY = StrokeGeometry(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
