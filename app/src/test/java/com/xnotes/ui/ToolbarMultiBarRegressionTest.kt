package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression cases for interacting toolbars, rather than a single isolated bar. */
class ToolbarMultiBarRegressionTest {
    private val compact = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
    private fun dock(id: String, edge: ToolbarDockEdge, length: Int = 1600, thickness: Int = 900) =
        ToolbarBar(id, id, compact, geometry = ToolbarGeometry(
            dockEdge = edge, anchor = when (edge) {
                ToolbarDockEdge.TOP -> ToolbarAnchor.TOP_CENTER
                ToolbarDockEdge.BOTTOM -> ToolbarAnchor.BOTTOM_CENTER
                ToolbarDockEdge.LEFT -> ToolbarAnchor.CENTER_LEFT
                ToolbarDockEdge.RIGHT -> ToolbarAnchor.CENTER_RIGHT
            },
            widthDp = if (edge in listOf(ToolbarDockEdge.TOP, ToolbarDockEdge.BOTTOM)) length else thickness,
            heightDp = if (edge in listOf(ToolbarDockEdge.LEFT, ToolbarDockEdge.RIGHT)) length else thickness,
        ))
    private fun floatBar(id: String, anchor: ToolbarAnchor, width: Int = 1600, height: Int = 900) =
        ToolbarBar(id, id, compact, geometry = ToolbarGeometry(
            floating = true, anchor = anchor, widthDp = width, heightDp = height,
        ))

    private fun assertInside(placement: com.xnotes.core.tools.ToolbarDockPlacement,
        width: Float, height: Float) {
        assertTrue("negative position $placement", placement.x >= -0.01f && placement.y >= -0.01f)
        assertTrue("off-screen $placement in ${width}x$height",
            placement.x + placement.width <= width + 0.01f &&
                placement.y + placement.height <= height + 0.01f)
        assertTrue("zero size $placement", placement.width > 0f && placement.height > 0f)
    }
    private fun assertNonOverlapping(plan: com.xnotes.core.tools.ToolbarDockPlan) {
        val placements = plan.placements
        for (i in placements.indices) for (j in i + 1 until placements.size) {
            val a = placements[i]; val b = placements[j]
            val intersects = a.x < b.x + b.width - 0.01f && b.x < a.x + a.width - 0.01f &&
                a.y < b.y + b.height - 0.01f && b.y < a.y + a.height - 0.01f
            assertTrue("overlapping bars $a and $b", !intersects)
        }
    }

    @Test fun fourOversizedDockedBarsRemainVisibleAndSeparate() {
        for ((width, height) in listOf(980 to 700, 720 to 420)) {
            val bars = listOf(dock("top", ToolbarDockEdge.TOP),
                dock("bottom", ToolbarDockEdge.BOTTOM), dock("left", ToolbarDockEdge.LEFT),
                dock("right", ToolbarDockEdge.RIGHT))
            val plan = toolbarDockPlan(ToolbarWorkspace(bars), ToolbarLook(), width.dp, height.dp)
            assertTrue("rejected docked bars at ${width}x$height: ${plan.rejected}", plan.rejected.isEmpty())
            assertEquals(4, plan.placements.size)
            assertNonOverlapping(plan)
            for (placement in plan.placements) assertInside(placement, width.toFloat(), height.toFloat())
        }
    }

    @Test fun threeOversizedFloatingBarsRemainVisibleAndSeparate() {
        for ((width, height) in listOf(980 to 700, 720 to 420)) {
            val bars = listOf(floatBar("top", ToolbarAnchor.TOP_LEFT),
                floatBar("bottom", ToolbarAnchor.BOTTOM_RIGHT),
                floatBar("left", ToolbarAnchor.CENTER_LEFT))
            val plan = toolbarFloatingPlan(ToolbarWorkspace(bars), ToolbarLook(), width.dp, height.dp, 5)
            assertTrue("rejected floating bars at ${width}x$height: ${plan.rejected}", plan.rejected.isEmpty())
            assertEquals(3, plan.placements.size)
            assertNonOverlapping(plan)
            for (placement in plan.placements) assertInside(placement, width.toFloat(), height.toFloat())
        }
    }

    @Test fun twoDockedAndTwoFloatingBarsKeepEachOtherVisible() {
        for ((width, height) in listOf(980 to 700, 720 to 420)) {
            val workspace = ToolbarWorkspace(listOf(
                dock("top", ToolbarDockEdge.TOP), dock("bottom", ToolbarDockEdge.BOTTOM),
                floatBar("left", ToolbarAnchor.CENTER_LEFT), floatBar("right", ToolbarAnchor.CENTER_RIGHT)))
            val dockPlan = toolbarDockPlan(workspace, ToolbarLook(), width.dp, height.dp)
            assertTrue("docks rejected: ${dockPlan.rejected}", dockPlan.rejected.isEmpty())
            assertEquals(2, dockPlan.placements.size)
            val innerWidth = width - dockPlan.leftInset - dockPlan.rightInset
            val innerHeight = height - dockPlan.topInset - dockPlan.bottomInset
            val floating = toolbarFloatingPlan(workspace, ToolbarLook(), innerWidth.dp, innerHeight.dp, 5)
            assertTrue("floating rejected at ${width}x$height in ${innerWidth}x$innerHeight: ${floating.rejected}",
                floating.rejected.isEmpty())
            assertEquals(2, floating.placements.size)
            assertNonOverlapping(floating)
            floating.placements.forEach { assertInside(it, innerWidth, innerHeight) }
            assertTrue(toolbarPlacementFits(workspace, ToolbarLook(), width.dp, height.dp, 5))
        }
    }

    @Test fun moveAndResizeOneOfSeveralBarsDoesNotChangeOtherBars() {
        for ((width, height) in listOf(980 to 700, 720 to 420)) {
            val top = dock("top", ToolbarDockEdge.TOP, length = 220, thickness = 65)
            val second = dock("right", ToolbarDockEdge.RIGHT, length = 200, thickness = 65)
            val floating = floatBar("floating", ToolbarAnchor.BOTTOM_RIGHT, width = 180, height = 65)
            val before = ToolbarWorkspace(listOf(top, second, floating))
            val original = before.bars.associateBy { it.id }
            for (edge in ToolbarDockEdge.entries) {
                val moved = toolbarGeometryForDockSelection(second, 1, ToolbarLook(), edge)
                    .withLengthDp(300, edge in listOf(ToolbarDockEdge.LEFT, ToolbarDockEdge.RIGHT))
                val changed = before.replaceBar(second.copy(geometry = moved))
                assertEquals(original["top"], changed.bars.first { it.id == "top" })
                assertEquals(original["floating"], changed.bars.first { it.id == "floating" })
                val plan = toolbarDockPlan(changed, ToolbarLook(), width.dp, height.dp)
                assertNotNull("moved bar missing on $edge: ${plan.rejected}", plan.placement("right"))
                assertNonOverlapping(plan)
            }
        }
    }
}
