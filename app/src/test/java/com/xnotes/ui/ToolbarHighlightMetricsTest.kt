package com.xnotes.ui

import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.settings.ToolbarSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarHighlightMetricsTest {
    @Test fun perElementHighlightDiameterScalesWithButton() {
        val base = barMetrics(ToolbarSize.REGULAR)
        val small = entryBarMetrics(base, ToolbarEntry(ToolbarItem.PEN, sizeDp = 28))
        val large = entryBarMetrics(base, ToolbarEntry(ToolbarItem.PEN, sizeDp = 70))

        assertEquals(base.circle.value * 28f / base.button.value, small.circle.value, 0.01f)
        assertEquals(base.circle.value * 70f / base.button.value, large.circle.value, 0.01f)
        assertTrue(large.circle > small.circle)
        // The actual widget reports these per-item circle bounds to the shared glide.
    }
}
