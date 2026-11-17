package com.xnotes.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkRunsTest {

    private val black = doubleArrayOf(0.0, 0.0, 0.0)

    private fun ops(xy: FloatArray, r: FloatArray, from: Int = 0, count: Int = r.size, sy: Double = 1.0): String {
        val sb = StringBuilder()
        InkRuns.append(sb, xy, r, from, count, 0.0, 0.0, 1.0, sy, 1.0, black)
        return sb.toString()
    }

    private fun lines(s: String) = s.trim().lines()

    @Test
    fun equalWidthsShareOnePath() {
        val s = ops(floatArrayOf(0f, 0f, 10f, 0f, 20f, 5f, 30f, 5f), floatArrayOf(1f, 1f, 1f, 1f))
        assertEquals(listOf("0 0 0 RG", "1 J 1 j [] 0 d", "2 w", "0 0 m", "10 0 l", "20 5 l", "30 5 l", "S"), lines(s))
    }

    @Test
    fun eachWidthStartsAPathWhereTheLastEnded() {
        val s = ops(floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f), floatArrayOf(1f, 2f, 3f))
        assertEquals(
            listOf("0 0 0 RG", "1 J 1 j [] 0 d", "3 w", "0 0 m", "10 0 l", "S", "5 w", "10 0 m", "20 0 l", "S"),
            lines(s),
        )
    }

    @Test
    fun everyPointIsWritten() {
        val n = 50
        val xy = FloatArray(2 * n) { if (it % 2 == 0) it * 1.5f else (it % 7).toFloat() }
        val r = FloatArray(n) { 1f + (it % 3) * 0.25f }
        val s = lines(ops(xy, r))
        val written = s.filter { it.endsWith(" m") || it.endsWith(" l") }.map { it.split(' ').take(2) }.distinct()
        assertEquals(n, written.size)
    }

    @Test
    fun aSinglePointIsAFilledDisc() {
        val s = lines(ops(floatArrayOf(5f, 5f), floatArrayOf(2f)))
        assertEquals("0 0 0 rg", s.first())
        assertEquals("f", s.last())
        assertFalse(s.any { it.endsWith(" S") || it == "S" })
    }

    @Test
    fun pointsThatNeverMoveAreADisc() {
        val s = lines(ops(floatArrayOf(5f, 5f, 5f, 5f, 5f, 5f), floatArrayOf(2f, 2f, 2f)))
        assertTrue(s.contains("7 5 m"))
        assertEquals("f", s.last())
    }

    @Test
    fun segmentsWithoutWidthAreSkipped() {
        val s = lines(ops(floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f, 30f, 0f), floatArrayOf(0f, 0f, 1f, 1f)))
        assertEquals(listOf("0 0 0 RG", "1 J 1 j [] 0 d", "1 w", "10 0 m", "20 0 l", "S", "2 w", "20 0 m", "30 0 l", "S"), s)
        assertFalse(InkRuns.append(StringBuilder(), floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(0f, 0f), 0, 2, 0.0, 0.0, 1.0, 1.0, 1.0, black))
    }

    @Test
    fun aRangeReadsOnlyItsOwnPoints() {
        val s = lines(ops(floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f, 30f, 0f), floatArrayOf(1f, 1f, 1f, 1f), from = 1, count = 2))
        assertEquals(listOf("0 0 0 RG", "1 J 1 j [] 0 d", "2 w", "10 0 m", "20 0 l", "S"), s)
    }

    @Test
    fun placementMapsPointsAndScalesWidths() {
        val sb = StringBuilder()
        InkRuns.append(sb, floatArrayOf(0f, 0f, 150f, 300f), floatArrayOf(1.5f, 1.5f), 0, 2, 10.0, 800.0, 0.48, -0.48, 0.48, black)
        assertEquals(listOf("0 0 0 RG", "1 J 1 j [] 0 d", "1.44 w", "10 800 m", "82 656 l", "S"), lines(sb.toString()))
    }
}
