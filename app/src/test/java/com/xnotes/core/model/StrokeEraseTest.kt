package com.xnotes.core.model

import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Area-erase split ([Stroke.erasedBy]): partitioning a stroke's samples around an eraser circle. */
class StrokeEraseTest {

    private fun stroke(vararg pts: Pair<Double, Double>): Stroke =
        Stroke(Tool.PEN, ToolConfig(), pts.map { Sample(it.first, it.second, 1.0) }.toMutableList())

    @Test fun untouchedReturnsNull() {
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0)
        assertNull(s.erasedBy(100.0, 100.0, 5.0)) // far away -> bbox reject -> null
    }

    @Test fun emptyStrokeReturnsNull() {
        assertNull(Stroke(Tool.PEN, ToolConfig()).erasedBy(0.0, 0.0, 5.0))
    }

    @Test fun fullCoverReturnsEmpty() {
        val s = stroke(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        val frags = s.erasedBy(1.0, 0.0, 10.0) // every sample inside -> whole-removal signal
        assertNotNull(frags)
        assertTrue(frags!!.isEmpty())
    }

    @Test fun endTouchTrimsToOneFragment() {
        // x = 0,10,20,30,40,50; circle at (5,0) r=8 covers samples 0 and 1, the rest survive.
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0, 30.0 to 0.0, 40.0 to 0.0, 50.0 to 0.0)
        val frags = s.erasedBy(5.0, 0.0, 8.0)!!
        assertEquals(1, frags.size)
        assertEquals(listOf(20.0, 30.0, 40.0, 50.0), frags[0].samples.map { it.x })
    }

    @Test fun midHoleSplitsInTwo() {
        // circle at (25,0) r=8 covers samples at x=20 and x=30 (indices 2,3) -> two fragments.
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0, 30.0 to 0.0, 40.0 to 0.0, 50.0 to 0.0)
        val frags = s.erasedBy(25.0, 0.0, 8.0)!!
        assertEquals(2, frags.size)
        assertEquals(listOf(0.0, 10.0), frags[0].samples.map { it.x }) // order preserved
        assertEquals(listOf(40.0, 50.0), frags[1].samples.map { it.x })
    }

    @Test fun multipleHolesProduceThreeFragments() {
        // A path that crosses the eraser circle (origin, r=5) twice: samples 1 and 4 are inside.
        val s = stroke(
            10.0 to 0.0,   // out
            0.0 to 0.0,    // in  (erased)
            -10.0 to 0.0,  // out
            -10.0 to 1.0,  // out
            0.0 to 1.0,    // in  (erased)
            10.0 to 1.0,   // out
        )
        val frags = s.erasedBy(0.0, 0.0, 5.0)!!
        assertEquals(3, frags.size)
        assertEquals(listOf(1, 2, 1), frags.map { it.samples.size })
    }

    @Test fun singleSurvivingSamplesAreKept() {
        // Erase the middle of three collinear samples -> two single-sample dots survive (not dropped).
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0)
        val frags = s.erasedBy(10.0, 0.0, 5.0)!!
        assertEquals(2, frags.size)
        assertTrue(frags.all { it.samples.size == 1 })
    }

    @Test fun fragmentsShareToolConfigAndSpeedScale() {
        val cfg = ToolConfig(baseWidth = 7.0)
        val orig = Stroke(
            Tool.HIGHLIGHTER, cfg,
            mutableListOf(Sample(0.0, 0.0, 1.0), Sample(10.0, 0.0, 1.0), Sample(20.0, 0.0, 1.0)),
            speedScale = 2.5,
        )
        for (frag in orig.erasedBy(0.0, 0.0, 5.0)!!) {
            assertEquals(Tool.HIGHLIGHTER, frag.tool)
            assertSame(cfg, frag.config) // same config reference, not a copy
            assertEquals(2.5, frag.speedScale, 1e-12)
        }
    }

    @Test fun fragmentsAreIndependentCopies() {
        val orig = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0)
        val frag = orig.erasedBy(0.0, 0.0, 5.0)!!.first() // survivors: x=10,20
        val sizeBefore = frag.samples.size
        orig.samples.clear() // mutating the original must not disturb the fragment's copy
        assertEquals(sizeBefore, frag.samples.size)
        assertEquals(10.0, frag.samples.first().x, 1e-12)
    }

    @Test fun agreesWithIntersectsCircle() {
        // The load-bearing invariant: erasedBy(..) != null  <=>  intersectsCircle(..) == true.
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 10.0, 30.0 to 10.0)
        val cases = listOf(
            Triple(5.0, 0.0, 6.0),     // hits sample 0
            Triple(100.0, 100.0, 5.0), // far miss (bbox reject)
            Triple(20.0, 10.0, 4.0),   // hits sample 2
            Triple(15.0, 5.0, 2.0),    // inside bbox but reaches no sample
            Triple(25.0, 10.0, 50.0),  // covers everything
        )
        for ((cx, cy, r) in cases) {
            assertEquals(
                "center=($cx,$cy) r=$r",
                s.intersectsCircle(cx, cy, r),
                s.erasedBy(cx, cy, r) != null,
            )
        }
    }
}
