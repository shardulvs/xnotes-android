package com.xnotes.platform

import com.xnotes.platform.NotePorter.Mark
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePorterTest {

    private fun marks(vararg m: Pair<String, DoubleArray>) = m.map { Mark(it.first, it.second) }

    @Test fun rewritingNoiseStillCountsAsTheSameNote() {
        val a = marks("page" to doubleArrayOf(1240.0, 1754.0), "shape function sin(x)" to doubleArrayOf(10.0, 20.0, 300.0, 0.4))
        val b = marks("page" to doubleArrayOf(1240.0, 1754.0000001), "shape function sin(x)" to doubleArrayOf(10.0, 20.0, 300.00001, 0.4000001))
        assertTrue(NotePorter.same(a, b))
    }

    @Test fun anyRealDifferenceIsCaught() {
        val a = marks("page" to doubleArrayOf(1240.0, 1754.0), "shape rectangle" to doubleArrayOf(10.0, 20.0, 300.0, 40.0))
        assertFalse("moved", NotePorter.same(a, marks("page" to doubleArrayOf(1240.0, 1754.0), "shape rectangle" to doubleArrayOf(12.0, 20.0, 300.0, 40.0))))
        assertFalse("kind", NotePorter.same(a, marks("page" to doubleArrayOf(1240.0, 1754.0), "shape polygon" to doubleArrayOf(10.0, 20.0, 300.0, 40.0))))
        assertFalse("dropped", NotePorter.same(a, a.take(1)))
        assertFalse("reordered", NotePorter.same(a, a.reversed()))
    }
}
