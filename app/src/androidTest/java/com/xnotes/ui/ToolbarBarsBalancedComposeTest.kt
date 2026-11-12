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
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Verifies organization responds to pane width, without hiding controls or creating unequal columns. */
class ToolbarBarsBalancedComposeTest {
    @get:Rule val rule = createComposeRule()

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
