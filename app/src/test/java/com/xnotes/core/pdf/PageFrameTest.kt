package com.xnotes.core.pdf

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFrameTest {

    // A crop box off the origin, and a 300 dpi note.
    private val left = 10.0
    private val bottom = 20.0
    private val right = 622.0
    private val top = 812.0
    private val s = 72.0 / 300

    private fun near(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }

    @Test
    fun drawingLandsWhereTheScreenShowsIt() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val frame = PageFrame.of(left, bottom, right, top, rotation, s)
            val shown = PdfPageGeometry.of(left, bottom, right, top, rotation)
            // The note page is the page as displayed, so its width is the turned box's.
            val w = (if (rotation % 180 == 0) right - left else top - bottom) / s
            val h = (if (rotation % 180 == 0) top - bottom else right - left) / s
            for ((x, y) in listOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h, 123.0 to 2001.5)) {
                val user = frame.toUser(x, y)
                near(Pt(x * s, y * s), shown.toDisplay(user.x, user.y))
            }
        }
    }

    @Test
    fun aTurnNeverMirrors() {
        for (rotation in listOf(90, 180, 270, -90, 450)) {
            val frame = PageFrame.of(left, bottom, right, top, rotation, s)
            assertTrue(frame.turned)
            assertEquals(1.0, frame.turn.determinant, 0.0)
        }
    }

    @Test
    fun anUnturnedPageDrawsAsBefore() {
        val frame = PageFrame.of(left, bottom, right, top, 0, s)
        assertFalse(frame.turned)
        assertEquals(Affine.IDENTITY, frame.turn)
        assertEquals(left, frame.ox, 0.0)
        assertEquals(top, frame.oy, 0.0)
    }

    @Test
    fun marginsGrowTheSidesTheyAreShownOn() {
        // Margins in page px: 10 left, 20 top, 30 right, 40 bottom, on the page as displayed.
        fun grown(rotation: Int): Rect {
            val frame = PageFrame.of(left, bottom, right, top, rotation, s)
            val w = (if (rotation % 180 == 0) right - left else top - bottom) / s
            val h = (if (rotation % 180 == 0) top - bottom else right - left) / s
            return frame.toUser(Rect.ltrb(-10.0, -20.0, w + 30.0, h + 40.0))
        }
        fun box(l: Double, b: Double, r: Double, t: Double) = Rect.ltrb(left - l * s, bottom - b * s, right + r * s, top + t * s)
        fun same(expected: Rect, actual: Rect) {
            near(Pt(expected.left, expected.top), Pt(actual.left, actual.top))
            near(Pt(expected.right, expected.bottom), Pt(actual.right, actual.bottom))
        }
        same(box(l = 10.0, b = 40.0, r = 30.0, t = 20.0), grown(0))
        // Turned a quarter clockwise: the box's left edge shows on top, its top edge on the right.
        same(box(l = 20.0, b = 10.0, r = 40.0, t = 30.0), grown(90))
        same(box(l = 30.0, b = 20.0, r = 10.0, t = 40.0), grown(180))
        same(box(l = 40.0, b = 30.0, r = 20.0, t = 10.0), grown(270))
    }
}
