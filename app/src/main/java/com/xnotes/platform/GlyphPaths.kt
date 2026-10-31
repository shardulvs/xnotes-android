package com.xnotes.platform

import android.graphics.Path
import android.graphics.PathIterator
import android.os.Build
import androidx.annotation.RequiresApi
import com.xnotes.core.geometry.Pt
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg

/** Read native glyph path verbs where available, or approximate older Android paths. */
internal object GlyphPaths {

    /** Preserve each contour in [source]; [size] controls approximation on older Android. */
    fun contours(source: Path, size: Double): List<VectorContour> {
        if (Build.VERSION.SDK_INT >= 34) nativeContours(source)?.let { return it }
        return approximateContours(source, size)
    }

    @RequiresApi(34)
    private fun nativeContours(source: Path): List<VectorContour>? {
        val iterator = source.pathIterator
        val points = FloatArray(8)
        val out = ArrayList<VectorContour>()
        var start: Pt? = null
        var segments = ArrayList<VectorSeg>()
        fun point(i: Int) = Pt(points[i].toDouble(), points[i + 1].toDouble())
        fun finish(closed: Boolean) {
            start?.let { if (segments.isNotEmpty()) out += VectorContour(it, segments, closed) }
            start = null
            segments = ArrayList()
        }
        fun quadratic() {
            val a = point(0)
            val control = point(2)
            val end = point(4)
            segments += VectorSeg.Cubic(a + (control - a) * (2.0 / 3.0),
                end + (control - end) * (2.0 / 3.0), end)
        }
        while (iterator.hasNext()) {
            when (iterator.next(points, 0)) {
                PathIterator.VERB_MOVE -> { finish(false); start = point(0) }
                PathIterator.VERB_LINE -> segments += VectorSeg.Line(point(2))
                PathIterator.VERB_QUAD -> quadratic()
                PathIterator.VERB_CUBIC -> segments += VectorSeg.Cubic(point(2), point(4), point(6))
                PathIterator.VERB_CONIC -> if (points[6] == 1f) quadratic() else return null
                PathIterator.VERB_CLOSE -> finish(true)
                PathIterator.VERB_DONE -> break
                else -> return null
            }
        }
        finish(false)
        return out
    }

    internal fun approximateContours(source: Path, size: Double): List<VectorContour> {
        // Native adaptive flattening avoids PathMeasure calls and Kotlin RDP per sample.
        val approximation = source.approximate((size / DECIMATE_DENOMINATOR).toFloat().coerceAtLeast(0.001f))
        val out = ArrayList<VectorContour>()
        var ring = ArrayList<Pt>()
        var previousFraction = -1f
        for (i in approximation.indices step 3) {
            val fraction = approximation[i]
            val p = Pt(approximation[i + 1].toDouble(), approximation[i + 2].toDouble())
            if (fraction == previousFraction && ring.isNotEmpty() && !near(ring.last(), p)) {
                if (ring.size >= 3) out += contourOf(ring)
                ring = ArrayList()
            }
            if (ring.isEmpty() || !near(ring.last(), p)) ring += p
            previousFraction = fraction
        }
        if (ring.size >= 3) out += contourOf(ring)
        return out
    }

    private fun contourOf(points: List<Pt>): VectorContour {
        // A closed glyph contour comes back with its last point on its first; drop the repeat.
        val last = points.size - 1
        val ring = if (near(points[0], points[last])) points.subList(0, last) else points
        return VectorContour(ring[0], ring.drop(1).map { VectorSeg.Line(it) }, closed = true)
    }

    private fun near(a: Pt, b: Pt) = kotlin.math.abs(a.x - b.x) < 1e-4 && kotlin.math.abs(a.y - b.y) < 1e-4

    /** Approximation tolerance as a fraction of the em: well under a pixel at any real zoom. */
    private const val DECIMATE_DENOMINATOR = 2000.0
}
