package com.xnotes.core.infinite

import com.xnotes.core.model.Arrange
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.arrangeOrder
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BringToFrontTest {

    /** Identical geometry on purpose: order must follow identity, never equality. */
    private fun stroke() = Stroke(
        Tool.PEN, ToolConfig(), mutableListOf(Sample(0.0, 0.0, 1.0), Sample(10.0, 10.0, 1.0)),
    )

    /** Items never override equals, so a hash set of them already compares by identity. */
    private fun bringToFrontOrder(all: List<CanvasItem>, selected: List<CanvasItem>): List<CanvasItem> {
        val picked = HashSet(selected)
        return arrangeOrder(all, { it in picked }, Arrange.FRONT) { it.bounds() }
    }

    private fun assertOrder(expected: List<CanvasItem>, actual: List<CanvasItem>) {
        assertTrue("size ${actual.size}, expected ${expected.size}", expected.size == actual.size)
        for (i in expected.indices) assertSame("at $i", expected[i], actual[i])
    }

    @Test
    fun `the selection moves to the end`() {
        val a = stroke(); val b = stroke(); val c = stroke(); val d = stroke()
        assertOrder(listOf(a, c, b, d), bringToFrontOrder(listOf(a, b, c, d), listOf(b, d)))
    }

    @Test
    fun `everything else keeps its order`() {
        val a = stroke(); val b = stroke(); val c = stroke(); val d = stroke(); val e = stroke()
        assertOrder(listOf(b, d, e, a, c), bringToFrontOrder(listOf(a, b, c, d, e), listOf(a, c)))
    }

    /** The list order wins, not the order the user happened to select in. */
    @Test
    fun `the moved items keep the list order rather than the selection order`() {
        val a = stroke(); val b = stroke(); val c = stroke()
        assertOrder(listOf(b, a, c), bringToFrontOrder(listOf(a, b, c), listOf(c, a)))
    }

    @Test
    fun `a selection already on top is left alone`() {
        val a = stroke(); val b = stroke(); val c = stroke()
        val all = listOf(a, b, c)
        assertTrue(sameOrder(all, bringToFrontOrder(all, listOf(b, c))))
        assertTrue(sameOrder(all, bringToFrontOrder(all, listOf(a, b, c))))
        assertTrue(sameOrder(all, bringToFrontOrder(all, emptyList())))
    }

    @Test
    fun `a selection that is not in the list changes nothing`() {
        val a = stroke(); val b = stroke()
        val all = listOf(a, b)
        assertTrue(sameOrder(all, bringToFrontOrder(all, listOf(stroke()))))
    }

    @Test
    fun `sameOrder compares by identity, not by contents`() {
        val a = stroke(); val b = stroke()
        assertTrue(sameOrder(listOf(a, b), listOf(a, b)))
        assertFalse(sameOrder(listOf(a, b), listOf(b, a)))
        // Same samples, same config, different objects: still a different list.
        assertFalse(sameOrder(listOf(a), listOf(stroke())))
        assertFalse(sameOrder(listOf(a), listOf(a, b)))
    }
}
