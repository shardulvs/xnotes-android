package com.xnotes.canvas

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page layer is cached in bands; these pin how a footprint is cut into them. */
class BandGridTest {

    private fun grid(h: Double = 1000.0, res: Double = 2.0, sideways: Boolean = false, gutter: Int = 8) =
        BandGrid(Rect(-10.0, -20.0, 300.0, h), res, sideways, 512, gutter)

    @Test fun theBandsTileTheRasterWithNothingOverOrMissing() {
        val g = grid()
        assertEquals(600, g.across)
        assertEquals(2000, g.length)
        assertEquals(4, g.count)
        var row = 0
        for (k in 0 until g.count) {
            assertEquals(row, g.from(k))
            row = g.to(k)
        }
        assertEquals(g.length, row)
        assertEquals("the last band is what is left", 2000 - 3 * 512, g.to(3) - g.from(3))
    }

    @Test fun aBandShowsItsRowsInPageSpace() {
        val g = grid()
        assertEquals(Rect(-10.0, -20.0, 300.0, 256.0), g.shown(0))
        assertEquals(Rect(-10.0, -20.0 + 256.0, 300.0, 256.0), g.shown(1))
        for (k in 1 until g.count) assertEquals(g.shown(k - 1).bottom, g.shown(k).top, 1e-9)
    }

    @Test fun aSurfaceHoldsAGutterOfEachNeighbourButNoneOffThePage() {
        val g = grid()
        assertEquals("the first band has a neighbour below only", 512 + 8, g.surfaceH(0))
        assertEquals(512 + 16, g.surfaceH(1))
        assertEquals("the last has one above only", 2000 - 3 * 512 + 8, g.surfaceH(3))
        assertEquals(600, g.surfaceW(1))
        assertEquals(4L * 600 * (512 + 16), g.bytes(1))
        assertEquals(Rect(0.0, 0.0, 600.0, 512.0), g.source(0))
        assertEquals(Rect(0.0, 8.0, 600.0, 512.0), g.source(1))
        assertEquals(g.shown(1).top - 4.0, g.held(1).top, 1e-9)
        assertEquals(g.shown(1).bottom + 4.0, g.held(1).bottom, 1e-9)
    }

    @Test fun enteringABandPutsItsFirstHeldRowAtTheSurfaceTop() {
        val g = grid()
        val r = FakeRenderer()
        g.enter(r, 2)
        assertEquals(listOf("translate", "scale", "translate"), r.ops)
    }

    @Test fun aRectMeetsTheBandsItCrosses() {
        val g = grid()
        assertEquals(0..0, g.meeting(Rect(0.0, -20.0, 10.0, 100.0)))
        assertEquals(0..1, g.meeting(Rect(0.0, 200.0, 10.0, 100.0)))
        assertEquals(1..3, g.meeting(Rect(0.0, 300.0, 10.0, 600.0)))
        assertTrue("past the page's end", g.meeting(Rect(0.0, 2000.0, 10.0, 50.0)).isEmpty())
        assertTrue("before its start", g.meeting(Rect(0.0, -90.0, 10.0, 50.0)).isEmpty())
    }

    @Test fun aRectJustPastASeamStillMeetsTheBandWhoseGutterHoldsIt() {
        val g = grid()
        val below = Rect(0.0, g.shown(1).top + 1.0, 10.0, 2.0) // 2 to 6 rows into band 1
        assertEquals(1..1, g.meeting(below))
        assertEquals("band 0 holds those rows in its gutter", 0..1, g.meeting(below, held = true))
        val deep = Rect(0.0, g.shown(1).top + 20.0, 10.0, 2.0)
        assertEquals(1..1, g.meeting(deep, held = true))
    }

    @Test fun aQuarterTurnedPageIsCutAcrossItsWidth() {
        val g = BandGrid(Rect(0.0, 0.0, 1000.0, 300.0), 2.0, true, 512, 8)
        assertEquals(600, g.across)
        assertEquals(2000, g.length)
        assertEquals(Rect(256.0, 0.0, 256.0, 300.0), g.shown(1))
        assertEquals(512 + 16, g.surfaceW(1))
        assertEquals(600, g.surfaceH(1))
        assertEquals(Rect(8.0, 0.0, 512.0, 600.0), g.source(1))
        assertEquals(1..2, g.meeting(Rect(300.0, 0.0, 300.0, 10.0)))
    }

    @Test fun aShortPageIsOneBandWithNoGutter() {
        val g = grid(h = 200.0, res = 1.0)
        assertEquals(1, g.count)
        assertEquals(200, g.surfaceH(0))
        assertEquals(g.shown(0), g.held(0))
    }

    @Test fun aGridFitsOnlyItsOwnFootprintResolutionAndCut() {
        val g = grid()
        assertTrue(g.fits(Rect(-10.0, -20.0, 300.0, 1000.0), 2.0, false))
        assertFalse(g.fits(Rect(-10.0, -20.0, 300.0, 1001.0), 2.0, false))
        assertFalse(g.fits(Rect(-10.0, -20.0, 300.0, 1000.0), 2.5, false))
        assertFalse(g.fits(Rect(-10.0, -20.0, 300.0, 1000.0), 2.0, true))
    }
}
