package com.xnotes.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter
import kotlin.random.Random

/**
 * [JsonWrite.samplePoint] is a fixed-point fast path for the numbers that make up almost all of a
 * dense manifest. Its whole licence to exist is that it prints exactly what the general path
 * prints, so a re-saved note stays byte-stable: these compare the two over the ranges real ink
 * covers, plus the magnitudes where the fast path must hand back to Double.toString.
 */
class JsonWriteSampleTest {

    private fun round(v: Double, scale: Double) = Math.round(v * scale) / scale

    /** What the writer emitted before [JsonWrite.samplePoint] existed. */
    private fun reference(x: Double, y: Double, p: Double, t: Double?, width: Double? = null): String {
        val w = StringWriter()
        val j = JsonWrite(w)
        j.beginArray()
        j.beginArray().value(round(x, 100.0)).value(round(y, 100.0)).value(round(p, 1000.0))
        if (t != null) j.value(t)
        if (t != null && width != null) j.value(round(width, 1000.0))
        j.endArray()
        j.endArray()
        return w.toString()
    }

    private fun fast(x: Double, y: Double, p: Double, t: Double?, width: Double? = null): String {
        val w = StringWriter()
        val j = JsonWrite(w)
        j.beginArray()
        j.samplePoint(x, y, p, t, width)
        j.endArray()
        return w.toString()
    }

    private fun same(x: Double, y: Double, p: Double, t: Double? = null) =
        assertEquals("x=$x y=$y p=$p t=$t", reference(x, y, p, t), fast(x, y, p, t))

    @Test fun itPrintsWhatTheGeneralPathPrints() {
        val r = Random(20260902)
        repeat(20000) {
            val mag = listOf(1.0, 10.0, 1000.0, 100000.0, 9.9e6, 2e7).random(r)
            same(
                (r.nextDouble() - 0.5) * 2 * mag,
                (r.nextDouble() - 0.5) * 2 * mag,
                r.nextDouble(),
            )
        }
    }

    @Test fun itPrintsWhatTheGeneralPathPrintsWithTimes() {
        val r = Random(7)
        repeat(5000) {
            same(
                (r.nextDouble() - 0.5) * 2000.0,
                (r.nextDouble() - 0.5) * 2000.0,
                r.nextDouble(),
                r.nextInt(0, 600000).toDouble(),
            )
        }
        // A fractional time is not something the fixed-point form can print; it must fall back.
        same(1.0, 2.0, 0.5, 12.75)
        same(1.0, 2.0, 0.5, 1.0e8)
    }

    @Test fun itPrintsWhatTheGeneralPathPrintsWithKeptWidths() {
        val r = Random(11)
        repeat(5000) {
            val x = (r.nextDouble() - 0.5) * 2000.0
            val y = (r.nextDouble() - 0.5) * 2000.0
            val t = r.nextInt(0, 600000).toDouble()
            val width = 0.08 + r.nextDouble() * 0.92
            assertEquals(reference(x, y, 0.5, t, width), fast(x, y, 0.5, t, width))
        }
        assertEquals("[[1,2,0.5,12,0.457]]", fast(1.0, 2.0, 0.5, 12.0, 0.4567))
        assertEquals(reference(1.0, 2.0, 0.5, 12.75, 0.4567), fast(1.0, 2.0, 0.5, 12.75, 0.4567))
        // A width only has a slot after a time, so without one it is left out rather than misplaced.
        assertEquals("[[1,2,0.5]]", fast(1.0, 2.0, 0.5, null, 0.4567))
    }

    @Test fun theEdgesRoundTheSameWay() {
        val edges = listOf(
            0.0, -0.0, 0.004, -0.004, 0.005, -0.005, 0.01, -0.01, 0.099, 0.1, 0.5, 0.999,
            1.0, -1.0, 1.005, 9.995, 10.0, 99.99, 100.0, 1e6, -1e6,
            9999999.99, -9999999.99, 1e7, -1e7, 1.5e7, 1e300,
        )
        for (a in edges) for (b in edges) same(a, b, 0.5)
        for (p in listOf(0.0, 0.0004, 0.0005, 0.001, 0.0015, 0.01, 0.5, 0.999, 0.9995, 1.0)) {
            same(1.0, 2.0, p)
        }
    }

    @Test fun itLaysOutOneSampleAsAnArrayOfThree() {
        assertEquals("[[1.5,2,0.001]]", fast(1.5, 2.0, 0.001, null))
        assertEquals("[[-0.25,0,1,17]]", fast(-0.25, 0.0, 1.0, 17.0))
    }

    @Test fun consecutiveSamplesAreComma() {
        val w = StringWriter()
        val j = JsonWrite(w)
        j.beginArray()
        j.samplePoint(1.0, 2.0, 0.5, null)
        j.samplePoint(3.0, 4.0, 0.25, null)
        j.endArray()
        assertEquals("[[1,2,0.5],[3,4,0.25]]", w.toString())
    }

    /** A named array of samples: the shape both codecs actually write. */
    @Test fun itNestsUnderANameLikeTheCodecsUseIt() {
        val w = StringWriter()
        val j = JsonWrite(w)
        j.beginObject()
        j.name("samples").beginArray()
        j.samplePoint(1.0, 2.0, 0.5, null)
        j.endArray()
        j.endObject()
        assertEquals("{\"samples\":[[1,2,0.5]]}", w.toString())
    }
}
