package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Verifies organization responds to pane width, without hiding controls or creating unequal columns. */
class ToolbarBarsBalancedComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebarWidthInspectorGivesSlidersTheFullCardWidth() {
        // With the tablet navigation rail open, ~930dp remains for preferences.
        // The inspector divides that into two cards, so dimensions must use one
        // full-width column; the old 350dp threshold incorrectly split them again.
        var availableWidth by mutableStateOf(930.dp)
        val bar = ToolbarBar("colors", "Colors", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.TOP_CENTER, floating = true))
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(availableWidth)) {
                    ToolbarBarsInspector(
                        workspace = ToolbarWorkspace(listOf(bar)),
                        selectedBar = bar,
                        look = ToolbarLook(floating = true),
                        screenWidth = 1137.dp, screenHeight = 710.dp, colorCount = 5,
                        onUpdateBar = {}, onConfigureDock = { _, _ -> },
                    )
                }
            }
        }
        rule.waitForIdle()
        fun inspect() {
            val available = rule.onNodeWithTag("toolbar-dimensions", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val controls = rule.onAllNodesWithTag("toolbar-measure", useUnmergedTree = true)
            val nodes = controls.fetchSemanticsNodes()
            assertTrue("Expected five dimension controls, got ${nodes.size}", nodes.size == 5)
            // Semantics can return unspecified layout bounds for controls fully
            // outside the Compose test viewport (the stacked inspector is scrollable
            // and taller than this device's window). Inspect unclipped rectangles
            // only for the controls that are actually laid out in the test window.
            val measured = (0 until nodes.size).map { controls[it].getUnclippedBoundsInRoot() }
                .filter { it.left.value.isFinite() && it.right.value.isFinite() }
            assertTrue("No measurable slider in the current viewport: $available", measured.isNotEmpty())
            for (control in measured) {
                assertTrue("Slider measure only partially fills the card: control=$control card=$available",
                    (control.right - control.left).value >= (available.right - available.left).value * 0.90f)
            }
        }
        inspect()
        // Also check the inspector in its single-card stacked mode.
        rule.runOnIdle { availableWidth = 420.dp }
        rule.waitForIdle()
        inspect()
    }

    @Test fun expandedInspectorGroupsEqualWidthSlidersByPurpose() {
        val bar = ToolbarBar("colors", "Colors", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.TOP_CENTER, floating = true))
        var availableWidth by mutableStateOf(1057.dp)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(availableWidth)) {
                    ToolbarBarsInspector(
                        workspace = ToolbarWorkspace(listOf(bar)), selectedBar = bar,
                        look = ToolbarLook(floating = true), screenWidth = 1137.dp,
                        screenHeight = 710.dp, colorCount = 5,
                        onUpdateBar = {}, onConfigureDock = { _, _ -> },
                    )
                }
            }
        }
        rule.waitForIdle()
        fun assertGroupsAligned() {
            val measures = rule.onAllNodesWithTag("toolbar-measure", useUnmergedTree = true)
            assertTrue("Expected five controls", measures.fetchSemanticsNodes().size == 5)
            val bounds = (0..4).map { measures[it].getUnclippedBoundsInRoot() }
            val widths = bounds.map { (it.right - it.left).value }
            assertTrue("All controls must have the same width: $widths",
                widths.all { kotlin.math.abs(it - widths[0]) <= 2f })
            assertTrue("Left group sliders should share a column: $bounds",
                (1..2).all { kotlin.math.abs((bounds[it].left - bounds[0].left).value) <= 2f })
            assertTrue("Right group sliders should share a column: $bounds",
                kotlin.math.abs((bounds[3].left - bounds[4].left).value) <= 2f)
            assertTrue("Groups must be side-by-side and not overlap: $bounds",
                bounds[0].right < bounds[3].left)
            assertTrue("Rows inside each group must descend: $bounds",
                bounds[0].bottom <= bounds[1].top && bounds[1].bottom <= bounds[2].top &&
                    bounds[3].bottom <= bounds[4].top)
        }
        assertGroupsAligned() // collapsed icon-only navigation rail
        rule.runOnIdle { availableWidth = 1137.dp }
        rule.waitForIdle()
        assertGroupsAligned() // maximum landscape editor width
    }

    @Test fun widePaneGroupsAreAdjacentAndNarrowPaneStacksThem() {
        var screenWidth by mutableStateOf(900.dp)
        val bar = ToolbarBar("secondary", "Secondary", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(anchor = ToolbarAnchor.TOP_CENTER, floating = true))
        rule.setContent {
            MaterialTheme {
                Row(Modifier.width(screenWidth)) {
                    Box(Modifier.weight(1f)) {
                        ToolbarBarSettings(currentBar = bar, barIndex = 1, barCount = 2,
                            look = ToolbarLook(floating = true), onUpdateBar = {},
                            onConfigureDock = { _, _ -> })
                    }
                    Box(Modifier.weight(1f)) {
                        ToolbarAppearanceSettings(geometry = bar.geometry, vertical = false,
                            onGeometryChange = {}, onScaleChange = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        fun box(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val behavior = box("toolbar-behavior-controls")
        val placement = box("toolbar-placement-controls")
        val sizes = box("toolbar-size-presets")
        val dimensions = box("toolbar-dimensions")
        // Physical 1400px can be under 750dp. The inspector must switch from
        // side-by-side to stacked rather than clip: both configurations are valid.
        val sharedBehaviorRow = kotlin.math.abs((behavior.top - placement.top).value) <= 2f
        assertTrue("Behavior and Placement must not overlap: $behavior / $placement",
            if (sharedBehaviorRow) behavior.right <= placement.left + 3.dp
            else behavior.bottom <= placement.top + 3.dp)
        val positionTags = rule.onAllNodesWithTag("toolbar-position-controls", useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (positionTags.isNotEmpty()) {
            val position = box("toolbar-position-controls")
            val lanes = box("toolbar-lane-controls")
            assertTrue("Position + Lane must align in wide mode: $position / $lanes",
                kotlin.math.abs((position.top - lanes.top).value) <= 2f &&
                    position.right <= lanes.left + 3.dp)
        }
        assertTrue("Size presets must precede sliders", sizes.bottom <= dimensions.top + 3.dp)
        rule.runOnIdle { screenWidth = 640.dp }
        rule.waitForIdle()
        val narrowBehavior = box("toolbar-behavior-controls")
        val narrowPlacement = box("toolbar-placement-controls")
        assertTrue("Narrow cards must stack without clipping: $narrowBehavior / $narrowPlacement",
            narrowBehavior.bottom <= narrowPlacement.top + 3.dp)
        box("toolbar-dimensions")
    }
}
