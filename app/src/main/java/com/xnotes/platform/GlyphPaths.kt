package com.xnotes.platform

import android.graphics.Path
import android.graphics.PathMeasure
import com.xnotes.core.geometry.Pt
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg

/**
 * Glyph outlines read from an Android Path. XCanvas uses native approximation for speed;
 * other labels retain the original PathMeasure sampler. Both preserve contour boundaries.
 */
internal object GlyphPaths {

    /** Every contour of [source], text [size] px tall, as a closed polyline. */
    fun contours(source: Path, size: Double): List<VectorContour> {
        val step = size / SAMPLES_PER_EM
        val tolerance = size / DECIMATE_DENOMINATOR
        val measure = PathMeasure(source, false)
        val out = ArrayList<VectorContour>()
        val at = FloatArray(2)
        var guard = MAX_CONTOURS
        do {
            val length = measure.length
            if (length > 1e-4) {
                val count = kotlin.math.ceil(length / step).toInt().coerceIn(2, MAX_SAMPLES)
                val pts = ArrayList<Pt>(count + 1)
                for (i in 0..count) {
                    val d = length * i / count
                    if (measure.getPosTan(d, at, null)) pts.add(Pt(at[0].toDouble(), at[1].toDouble()))
                }
                val thinned = PathFlattener.decimate(pts, tolerance)
                if (thinned.size >= 3) out.add(contourOf(thinned))
            }
        } while (measure.nextContour() && guard-- > 0)
        return out
    }

    /**
     * Fast native contour approximation, available on minSdk 26.
     * Path.approximate emits (distance fraction, x, y) triples and repeats a fraction
     * across moveTo boundaries. Keep each contour separate so glyph counters survive.
     */
    fun approximateContours(source: Path, size: Double, errorEm: Double = 1.0 / 500.0): List<VectorContour> {
        val triples = source.approximate((size * errorEm).toFloat().coerceAtLeast(0.0001f))
        val out = ArrayList<VectorContour>()
        val points = ArrayList<Pt>()
        var previousFraction = Float.NaN
        fun flush() {
            if (points.size >= 3) {
                val thinned = PathFlattener.decimate(points, size / DECIMATE_DENOMINATOR)
                if (thinned.size >= 3) out.add(contourOf(thinned))
            }
            points.clear()
        }
        var i = 0
        while (i + 2 < triples.size) {
            val fraction = triples[i]
            val point = Pt(triples[i + 1].toDouble(), triples[i + 2].toDouble())
            // MoveTo produces equal fractions even when two contours share a vertex.
            // Comparing coordinates here would merge them and destroy glyph counters.
            if (points.isNotEmpty() && fraction == previousFraction) {
                flush()
                if (out.size >= MAX_CONTOURS) return out
            }
            if (points.isEmpty() || !near(points.last(), point)) points.add(point)
            previousFraction = fraction
            i += 3
        }
        flush()
        return out
    }

    private fun contourOf(points: List<Pt>): VectorContour {
        // A closed glyph contour comes back with its last point on its first; drop the repeat.
        val last = points.size - 1
        val ring = if (near(points[0], points[last])) points.subList(0, last) else points
        return VectorContour(ring[0], ring.drop(1).map { VectorSeg.Line(it) }, closed = true)
    }

    private fun near(a: Pt, b: Pt) = kotlin.math.abs(a.x - b.x) < 1e-4 && kotlin.math.abs(a.y - b.y) < 1e-4

    /** How finely a contour is walked, in samples per em of arc length. */
    private const val SAMPLES_PER_EM = 64.0

    /** Decimation tolerance as a fraction of the em: well under a pixel at any real zoom. */
    private const val DECIMATE_DENOMINATOR = 2000.0

    private const val MAX_SAMPLES = 4096
    private const val MAX_CONTOURS = 4096
}
