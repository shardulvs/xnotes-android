package com.xnotes.ui

import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarFloatingPlanTest {
    private val colors = ToolbarLayout(listOf(
        ToolbarSection(listOf(ToolbarEntry(ToolbarItem.COLORS))),
    ))
    private fun floating(id: String, anchor: ToolbarAnchor = ToolbarAnchor.TOP_LEFT, width: Int? = 300) =
        ToolbarBar(id, id, colors, geometry = ToolbarGeometry(
            anchor = anchor, floating = true, widthDp = width,
        ))

    @Test fun overflowUsesSecondAndThirdLaneAutomaticallyThenRejects() {
        val workspace = ToolbarWorkspace(listOf(
            floating("one"), floating("two"), floating("three"), floating("four"),
        ))
        val plan = toolbarFloatingPlan(workspace, ToolbarLook(), 500.dp, 400.dp, 5)
        assertEquals(listOf(0, 1, 2), listOf("one", "two", "three").map { plan.placement(it)!!.lane })
        assertTrue("four" in plan.rejected)
        assertEquals(3, plan.placements.size)
    }

    @Test fun preexistingRightAnchorRetainsEndAlignment() {
        val plan = toolbarFloatingPlan(
            ToolbarWorkspace(listOf(floating("end", ToolbarAnchor.BOTTOM_RIGHT, width = 180))),
            ToolbarLook(), 500.dp, 400.dp, 5,
        )
        assertTrue(plan.rejected.isEmpty())
        assertEquals(312f, plan.placement("end")!!.x, 0.01f)
    }

    @Test fun dockedBarsAutomaticallyAdvanceToNextFreeLane() {
        val primary = ToolbarBar("primary", "Main", colors)
        val secondary = floating("second", width = 180).copy(
            geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP, floating = false, widthDp = 180),
        )
        val plan = toolbarDockPlan(
            ToolbarWorkspace(listOf(primary, secondary)), ToolbarLook(),
            500.dp, 400.dp,
        )
        assertTrue(plan.rejected.isEmpty())
        assertEquals(0, plan.placement("primary")!!.lane)
        assertEquals(1, plan.placement("second")!!.lane)
    }

    @Test fun floatingAutoRegionsUseCompactLengthEvenWithUnequalEdgeGroups() {
        val sections = listOf(
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN),
                ToolbarEntry(ToolbarItem.ERASER), ToolbarEntry(ToolbarItem.COLORS)),
                region = ToolbarRegion.START),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.SIDEBAR)), region = ToolbarRegion.CENTER),
            ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)), region = ToolbarRegion.END),
        )
        for (anchor in listOf(ToolbarAnchor.TOP_CENTER, ToolbarAnchor.CENTER_LEFT)) {
            val geometry = ToolbarGeometry(anchor = anchor, floating = true)
            fun length(mode: ToolbarDistribution): Float {
                val bar = ToolbarBar("regions", "Regions", ToolbarLayout(sections, mode),
                    geometry = geometry)
                val plan = toolbarFloatingPlan(ToolbarWorkspace(listOf(bar)), ToolbarLook(),
                    1600.dp, 1600.dp, 6)
                val placed = plan.placement("regions")!!
                return if (anchor == ToolbarAnchor.CENTER_LEFT) placed.height else placed.width
            }
            assertEquals(length(ToolbarDistribution.CONTINUOUS), length(ToolbarDistribution.REGIONS), 0.01f)
        }
    }

    @Test fun floatingNaturalLengthIncludesSpacersOnBothAxes() {
        val base = colors.addItem(0, 0, ToolbarItem.SPACER)
        val expanded = base.setEntrySize(0, 1, 180)
        for (anchor in listOf(ToolbarAnchor.TOP_CENTER, ToolbarAnchor.CENTER_LEFT)) {
            fun length(layout: ToolbarLayout): Float {
                val bar = ToolbarBar("spaced", "Spaced", layout,
                    geometry = ToolbarGeometry(anchor = anchor, floating = true))
                val placement = toolbarFloatingPlan(ToolbarWorkspace(listOf(bar)), ToolbarLook(),
                    1000.dp, 1000.dp, 4).placement("spaced")!!
                return if (anchor == ToolbarAnchor.CENTER_LEFT) placement.height else placement.width
            }
            assertTrue(length(expanded) >= length(base) + 150f)
        }
    }

    @Test fun naturalColourPaletteLengthAdaptsToSelectedCount() {
        val workspace = ToolbarWorkspace(listOf(floating("colors", width = null)))
        val short = toolbarFloatingPlan(workspace, ToolbarLook(), 500.dp, 400.dp, 1)
        val long = toolbarFloatingPlan(workspace, ToolbarLook(), 500.dp, 400.dp, 8)
        assertTrue(long.placement("colors")!!.width > short.placement("colors")!!.width)
    }
}
