package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTabBarSizingTest {
    @Test fun dividesAvailableSpaceUntilMinimumWidth() {
        assertEquals(1, visibleTabSlots(800f, 1))
        assertEquals(2, visibleTabSlots(800f, 2))
        assertEquals(3, visibleTabSlots(800f, 3))
        assertEquals(5, visibleTabSlots(800f, 6))
    }

    @Test fun narrowScreenPreservesTapTargetsInsteadOfCompressingTabs() {
        assertEquals(2, visibleTabSlots(360f, 5))
        assertEquals(1, visibleTabSlots(280f, 1))
        assertEquals(1, visibleTabSlots(120f, 4))
    }

    @Test fun closingThirdOfFourMovesIndicatorToTheSurvivingTab() {
        val fourTabs = tabHighlightBounds(200f, 2)
        val afterClose = tabHighlightBounds(800f / 3f, 1)
        assertEquals(402f, fourTabs.first, 0.01f)
        assertEquals(800f / 3f + 2f, afterClose.first, 0.01f)
        assertEquals(800f / 3f - 4f, afterClose.second, 0.01f)
    }
}
