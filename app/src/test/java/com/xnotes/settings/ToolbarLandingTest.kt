package com.xnotes.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarLandingTest {
    private val w = 1000f
    private val h = 600f

    private fun land(x: Float, y: Float, from: ToolbarLanding) = toolbarLanding(x, y, w, h, from, pull = 70f, push = 12f, other = 30f)

    private val dockedTop = ToolbarLanding(ToolbarPosition.TOP, floating = false)
    private val floatingTop = ToolbarLanding(ToolbarPosition.TOP, floating = true)

    @Test
    fun aDockedBarStaysDockedUntilPulledClear() {
        assertEquals(dockedTop, land(500f, 25f, dockedTop))
        assertEquals(dockedTop, land(100f, 60f, dockedTop))
        assertEquals(floatingTop, land(500f, 90f, dockedTop))
    }

    @Test
    fun aFloatingBarDocksOnlyWhenPushedIntoItsEdge() {
        assertEquals(floatingTop, land(500f, 30f, floatingTop))
        assertEquals(floatingTop, land(900f, 20f, floatingTop))
        assertEquals(dockedTop, land(500f, 8f, floatingTop))
    }

    @Test
    fun theNearestEdgeTakesTheBar() {
        assertEquals(ToolbarLanding(ToolbarPosition.BOTTOM, floating = true), land(500f, 450f, dockedTop))
        assertEquals(ToolbarLanding(ToolbarPosition.BOTTOM, floating = false), land(500f, 590f, dockedTop))
        assertEquals(ToolbarLanding(ToolbarPosition.LEFT, floating = true), land(100f, 300f, floatingTop))
        assertEquals(ToolbarLanding(ToolbarPosition.RIGHT, floating = false), land(985f, 300f, floatingTop))
    }
}
