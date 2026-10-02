package com.xnotes.core.pdf

import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageGeometryTest {

    // A page box off the origin, as a crop box often is.
    private val left = 10.0
    private val bottom = 20.0
    private val right = 622.0
    private val top = 812.0
    private val w = right - left
    private val h = top - bottom

    private fun near(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }

    /** Section 5.6 of the plan: a display fraction (top-left origin) to user space, per rotation. */
    private fun userOf(fx: Double, fy: Double, rotation: Int): Pt = when (rotation) {
        90 -> Pt(left + fy * w, bottom + fx * h)
        180 -> Pt(right - fx * w, bottom + fy * h)
        270 -> Pt(right - fy * w, top - fx * h)
        else -> Pt(left + fx * w, top - fy * h)
    }

    @Test
    fun everyRotationMapsBothWays() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val g = PdfPageGeometry.of(left, bottom, right, top, rotation)
            // Displayed size: the box turned a quarter swaps its sides.
            val dw = if (rotation % 180 == 0) w else h
            val dh = if (rotation % 180 == 0) h else w
            for ((fx, fy) in listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 0.25 to 0.75, 0.6 to 0.1)) {
                val user = userOf(fx, fy, rotation)
                near(Pt(fx * dw, fy * dh), g.toDisplay(user.x, user.y))
                near(user, g.toUser(fx * dw, fy * dh))
            }
        }
    }

    @Test
    fun aPageOfNoPdfTurnsItsOwnPointsUp() {
        val g = PdfPageGeometry.flipped(842.0)
        near(Pt(100.0, 742.0), g.toUser(100.0, 100.0))
        near(Pt(100.0, 100.0), g.toDisplay(100.0, 742.0))
    }

    @Test
    fun quadPointsRunAlongTheTopsThenTheFeet() {
        // Upright: UL, UR, LL, LR.
        assertEquals(
            listOf(Pt(100.0, 200.0), Pt(240.0, 200.0), Pt(100.0, 214.0), Pt(240.0, 214.0)),
            MarkupPainter.corners(TextQuad(100f, 200f, 240f, 214f, 0)),
        )
        // Reading down the page, the tops face right: the first edge is the right one, top to bottom.
        assertEquals(
            listOf(Pt(314.0, 100.0), Pt(314.0, 240.0), Pt(300.0, 100.0), Pt(300.0, 240.0)),
            MarkupPainter.corners(TextQuad(300f, 100f, 314f, 240f, 1)),
        )
    }
}
