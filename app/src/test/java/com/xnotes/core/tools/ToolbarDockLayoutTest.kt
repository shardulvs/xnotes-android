package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarDockLayoutTest {
    private fun bar(id: String, edge: ToolbarDockEdge, lane: Int = 0,
        length: Float = 100f, depth: Float = 40f,
        alignment: ToolbarDockAlignment = ToolbarDockAlignment.START
    ) = ToolbarDockRequest(id, edge, lane, alignment, length, depth)

    @Test fun multipleBarsInOneLaneFitExactlyAndOverflowIsRejected() {
        val plan = ToolbarDockLayout.arrange(304f, 500f, listOf(
            bar("first", ToolbarDockEdge.TOP, length = 150f),
            bar("second", ToolbarDockEdge.TOP, length = 150f),
            bar("overflow", ToolbarDockEdge.TOP, length = 1f),
        ))
        assertEquals(setOf("overflow"), plan.rejected)
        assertEquals(0f, plan.placement("first")!!.x, 0.01f)
        assertEquals(154f, plan.placement("second")!!.x, 0.01f)
    }

    @Test fun threeLanesAllowedAndFourthRejected() {
        val plan = ToolbarDockLayout.arrange(600f, 500f, (0..3).map {
            bar("$it", ToolbarDockEdge.RIGHT, lane = it, length = 200f, depth = 35f)
        })
        assertEquals(3, plan.placements.size)
        assertEquals(setOf("3"), plan.rejected)
        assertEquals(105f, plan.rightInset, 0.01f)
    }

    @Test fun dockedOppositeEdgesReserveCornersAndDocumentSpace() {
        val plan = ToolbarDockLayout.arrange(600f, 450f, listOf(
            bar("top", ToolbarDockEdge.TOP, depth = 40f),
            bar("bottom", ToolbarDockEdge.BOTTOM, depth = 50f),
            bar("left", ToolbarDockEdge.LEFT, depth = 25f, length = 200f),
            bar("right", ToolbarDockEdge.RIGHT, depth = 30f, length = 200f),
        ))
        assertTrue(plan.rejected.isEmpty())
        assertEquals(40f, plan.topInset, 0.01f)
        assertEquals(50f, plan.bottomInset, 0.01f)
        assertEquals(25f, plan.leftInset, 0.01f)
        assertEquals(30f, plan.rightInset, 0.01f)
        assertEquals(40f, plan.placement("left")!!.y, 0.01f)
        assertEquals(400f, plan.placement("bottom")!!.y, 0.01f)
    }

    @Test fun startCenterEndClustersNeverOverlap() {
        val plan = ToolbarDockLayout.arrange(600f, 400f, listOf(
            bar("end", ToolbarDockEdge.TOP, length = 140f, alignment = ToolbarDockAlignment.END),
            bar("middle", ToolbarDockEdge.TOP, length = 120f, alignment = ToolbarDockAlignment.CENTER),
            bar("start", ToolbarDockEdge.TOP, length = 100f, alignment = ToolbarDockAlignment.START),
        ))
        assertTrue(plan.rejected.isEmpty())
        val positions = plan.placements.sortedBy { it.x }
        assertEquals(listOf("start", "middle", "end"), positions.map { it.id })
        assertTrue(positions.zipWithNext().all { (a, b) -> a.x + a.width <= b.x })
        assertEquals(460f, plan.placement("end")!!.x, 0.01f)
    }

    @Test fun autoLengthSideBarUsesSpaceBetweenTopAndBottom() {
        val plan = ToolbarDockLayout.arrange(600f, 450f, listOf(
            bar("top", ToolbarDockEdge.TOP, depth = 35f),
            bar("bottom", ToolbarDockEdge.BOTTOM, depth = 45f),
            bar("side", ToolbarDockEdge.LEFT, length = 450f)
                .copy(autoLength = true),
        ))
        assertTrue(plan.rejected.isEmpty())
        assertEquals(370f, plan.placement("side")!!.height, 0.01f)
        assertEquals(35f, plan.placement("side")!!.y, 0.01f)
    }

    @Test fun opposingLaneDepthCannotExceedWindow() {
        val plan = ToolbarDockLayout.arrange(600f, 100f, listOf(
            bar("top", ToolbarDockEdge.TOP, depth = 60f),
            bar("bottom", ToolbarDockEdge.BOTTOM, depth = 50f),
        ))
        assertEquals(setOf("bottom"), plan.rejected)
        assertEquals(60f, plan.topInset, 0.01f)
        assertEquals(0f, plan.bottomInset, 0.01f)
    }

    @Test fun invalidViewportDoesNotAcceptBars() {
        val plan = ToolbarDockLayout.arrange(0f, 400f, listOf(bar("a", ToolbarDockEdge.TOP)))
        assertTrue(plan.placements.isEmpty())
        assertTrue("a" in plan.rejected)
    }
}
