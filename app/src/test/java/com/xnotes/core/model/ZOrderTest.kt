package com.xnotes.core.model

import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class ZOrderTest {

    /** A named box; a plain class, so lists compare items by identity as the canvases do. */
    private class Box(val name: String, val rect: Rect) {
        override fun toString() = name
    }

    private fun box(name: String, x: Double) = Box(name, Rect(x, 0.0, 10.0, 10.0))

    private fun arrange(all: List<Box>, picked: Set<String>, how: Arrange): String =
        arrangeOrder(all, { it.name in picked }, how) { it.rect }.joinToString("") { it.name }

    // a overlaps c and d; b sits apart from everything.
    private val a = box("a", 0.0)
    private val b = box("b", 100.0)
    private val c = box("c", 5.0)
    private val d = box("d", 8.0)
    private val stack = listOf(a, b, c, d)

    @Test fun frontAndBackMoveTheWholeSelection() {
        assertEquals("bcda", arrange(stack, setOf("a"), Arrange.FRONT))
        assertEquals("dabc", arrange(stack, setOf("d"), Arrange.BACK))
        assertEquals("bdac", arrange(stack, setOf("a", "c"), Arrange.FRONT))
        assertEquals("acbd", arrange(stack, setOf("c", "a"), Arrange.BACK))
    }

    @Test fun forwardSkipsWhatItDoesNotTouch() {
        // b lies above a but apart from it, so a passes c, the first item it overlaps.
        assertEquals("bcad", arrange(stack, setOf("a"), Arrange.FORWARD))
        assertEquals("bcda", arrange(listOf(b, c, a, d), setOf("a"), Arrange.FORWARD))
    }

    @Test fun backwardSkipsWhatItDoesNotTouch() {
        // b lies below d but apart from it, so d passes c, the first item under it that it overlaps.
        assertEquals("adcb", arrange(listOf(a, c, b, d), setOf("d"), Arrange.BACKWARD))
        assertEquals("dacb", arrange(listOf(a, d, c, b), setOf("d"), Arrange.BACKWARD))
    }

    @Test fun nothingToPassLeavesTheOrderAlone() {
        assertEquals("abcd", arrange(stack, setOf("d"), Arrange.FORWARD))
        assertEquals("abcd", arrange(stack, setOf("a"), Arrange.BACKWARD))
        assertEquals("abcd", arrange(stack, setOf("b"), Arrange.FORWARD))
        assertEquals("abcd", arrange(stack, emptySet(), Arrange.FRONT))
    }

    @Test fun aSelectionStepsAsABlockKeepingItsOrder() {
        // a and b picked: only a overlaps c, and both land just above it, a still under b.
        assertEquals("cabd", arrange(stack, setOf("a", "b"), Arrange.FORWARD))
        // c and d picked: a under both is the one to pass going down.
        assertEquals("cdab", arrange(stack, setOf("c", "d"), Arrange.BACKWARD))
    }

    @Test fun pickedItemsAlreadyPastTheOneToPassStayPut() {
        // a and d picked; a passes c and lands just above it, d already above c stays on top.
        assertEquals("bcad", arrange(stack, setOf("a", "d"), Arrange.FORWARD))
    }
}
