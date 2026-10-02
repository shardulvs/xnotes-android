package com.xnotes.core.pdf

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteIconTest {

    private val tip = Pt(200.0, 300.0)

    @Test
    fun itSitsAboveWhereTheMarkupStarts() {
        val corner = Pt(120.0, 400.0)
        val at = NoteIcon.tipFor(corner, 2.0)
        assertTrue(at.y < corner.y && at.x > corner.x)
        val b = NoteIcon.bounds(at, 2.0)
        assertTrue(b.bottom < corner.y)
        assertEquals(NoteIcon.REACH_DP * 2.0, corner.y - b.top, 1e-9)
    }

    @Test
    fun theTailEndsAtTheTip() {
        val outline = NoteIcon.outline(tip, 2.0)
        assertTrue(tip in outline)
        // Everything else is the bubble, a tail's length above.
        assertTrue(outline.filter { it != tip }.all { it.y <= tip.y - 5.0 * 2.0 + 1e-9 })
        val b = NoteIcon.bounds(tip, 2.0)
        assertTrue(outline.all { b.outset(1e-9).contains(it) })
        assertEquals(tip.y, b.bottom, 1e-9)
        assertEquals(b.top, outline.minOf { it.y }, 1e-9)
        assertEquals(b.left, outline.minOf { it.x }, 1e-9)
        assertEquals(b.right, outline.maxOf { it.x }, 1e-9)
    }

    @Test
    fun itIsTheSameSizeInDpAtAnyDensity() {
        val one = NoteIcon.bounds(tip, 1.0)
        val three = NoteIcon.bounds(tip, 3.0)
        assertEquals(3 * one.w, three.w, 1e-9)
        assertEquals(3 * one.h, three.h, 1e-9)
        assertEquals(3 * (one.left - tip.x), three.left - tip.x, 1e-9)
    }

    @Test
    fun theLinesLieInTheBubble() {
        val b = NoteIcon.bounds(tip, 2.0)
        val lines = NoteIcon.lines(tip, 2.0)
        assertEquals(2, lines.size)
        for ((a, e) in lines) {
            assertEquals(a.y, e.y, 1e-9)
            assertTrue(a.x < e.x && a.x > b.left && e.x < b.right)
            assertTrue(a.y > b.top && a.y < tip.y - 5.0 * 2.0)
        }
    }

    @Test
    fun aTapAroundTheIconTakesIt() {
        val b = NoteIcon.bounds(tip, 2.0)
        val t = NoteIcon.target(tip, 2.0)
        assertTrue(t.w >= NoteIcon.TARGET_DP * 2.0 - 1e-9 && t.h >= NoteIcon.TARGET_DP * 2.0 - 1e-9)
        assertEquals(b.centerX, t.centerX, 1e-9)
        assertEquals(b.centerY, t.centerY, 1e-9)
    }

    @Test
    fun aLightColourIsOutlinedAndLinedInItsShade() {
        val c = NoteIcon.colorsOf(Rgba(61, 220, 132, 90))
        assertEquals(Rgba(61, 220, 132), c.fill)
        assertEquals(Rgba(27, 99, 59), c.edge)
        assertEquals(c.edge, c.lines)
    }

    @Test
    fun aDarkColourGetsPaleLines() {
        val c = NoteIcon.colorsOf(Rgba(0, 0, 0))
        assertEquals(Rgba(0, 0, 0), c.edge)
        assertEquals(Rgba(204, 204, 204), c.lines)
        val blue = NoteIcon.colorsOf(Rgba(33, 150, 243))
        assertTrue(blue.lines.r > blue.fill.r && blue.lines.g > blue.fill.g)
    }
}
