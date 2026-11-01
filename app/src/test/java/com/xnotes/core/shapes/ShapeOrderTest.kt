package com.xnotes.core.shapes

import org.junit.Assert.assertEquals
import org.junit.Test

class ShapeOrderTest {
    @Test
    fun movedKeyLandsInTheSlot() {
        val keys = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), reorderedKeys(emptyList(), keys, "a", 2))
        assertEquals(listOf("b", "c", "d", "a"), reorderedKeys(emptyList(), keys, "a", 4))
        assertEquals(listOf("d", "a", "b", "c"), reorderedKeys(emptyList(), keys, "d", 0))
        assertEquals(keys, reorderedKeys(emptyList(), keys, "b", 2))
    }

    @Test
    fun reorderingOneListKeepsTheOthers() {
        val order = reorderedKeys(listOf("x", "y"), listOf("a", "b"), "b", 0)
        assertEquals(listOf("b", "a", "x", "y"), order)
        assertEquals(listOf("y", "x", "z"), listOf("x", "y", "z").inOrder(listOf("y", "x")) { it })
    }

    @Test
    fun orderedPagesFollowTheOrderAndKeepEveryChip() {
        val last = ShapePresets.pages.last()
        val pages = ShapePresets.ordered(listOf(last.key, last.presets.last().key))
        assertEquals(last.key, pages.first().key)
        assertEquals(last.presets.last().key, pages.first().presets.first().key)
        assertEquals(ShapePresets.all.size, pages.sumOf { it.presets.size })
    }

    @Test
    fun keysAreDistinctAcrossShapesAndConnectors() {
        val keys = ShapePresets.pages.map { it.key } + ShapePresets.all.map { it.key } +
            ConnectorPresets.pages.map { it.key } + ConnectorPresets.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
