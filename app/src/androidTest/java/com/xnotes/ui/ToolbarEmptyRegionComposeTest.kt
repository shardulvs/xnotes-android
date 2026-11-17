package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarDistribution
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarRegion
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Empty or hidden regions must never claim space in XNote, XCanvas or their preview. */
class ToolbarEmptyRegionComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun section(region: ToolbarRegion, count: Int, visible: Boolean = true) =
        ToolbarSection((0 until count).map { ToolbarEntry(ToolbarItem.PEN, visible = visible) }, region = region)

    @Composable
    private fun Surface(layout: ToolbarLayout, vertical: Boolean = false) {
        val look = ToolbarLook(position = if (vertical) ToolbarPosition.RIGHT else ToolbarPosition.TOP)
        val geometry = if (vertical) ToolbarGeometry(widthDp = 55, heightDp = 300)
            else ToolbarGeometry(widthDp = 300, heightDp = 55)
        CompositionLocalProvider(LocalToolbarLook provides look) {
            ToolbarFrame(armed = null, layout = layout, geometry = geometry) {
                ToolbarSections(layout) { _, section ->
                    if (LocalBar.current.vertical) {
                        Column {
                            section.visibleEntries.forEach { Box(Modifier.size(40.dp)) }
                        }
                    } else {
                        Row {
                            section.visibleEntries.forEach { Box(Modifier.size(40.dp)) }
                        }
                    }
                }
            }
        }
    }

    @Test fun emptyEndDoesNotStealScrollingAreaEvenIfPinEndPreferenceIsEnabled() {
        val layout = ToolbarLayout(listOf(
            section(ToolbarRegion.START, 1),
            section(ToolbarRegion.CENTER, 8),
            section(ToolbarRegion.END, 1, visible = false),
        ), distribution = ToolbarDistribution.REGIONS, pinEnd = true)
        rule.setContent { MaterialTheme { Surface(layout) } }
        rule.onNodeWithTag("toolbar-pin-end", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("toolbar-region-content-end", useUnmergedTree = true).assertDoesNotExist()
        val center = rule.onNodeWithTag("toolbar-region-content-center", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("center should receive the free space, got $center", (center.right - center.left).value >= 200f)
    }

    @Test fun unpinnedStartUsesNaturalSizeWhileEmptyEndGivesCenterTheRest() {
        // Four tools fit alongside Center in this bar. Neither a ghost End slot nor
        // an arbitrary Start fixed-width slot may make Start scroll prematurely.
        val layout = ToolbarLayout(listOf(
            section(ToolbarRegion.START, 4),
            section(ToolbarRegion.CENTER, 1),
            section(ToolbarRegion.END, 1, visible = false),
        ), distribution = ToolbarDistribution.REGIONS, pinEnd = true)
        rule.setContent { MaterialTheme { Surface(layout) } }
        val start = rule.onNodeWithTag("toolbar-region-content-start", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val center = rule.onNodeWithTag("toolbar-region-content-center", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("Start should fit all 4 tools without a width cap: $start",
            (start.right - start.left).value >= 155f)
        assertTrue("Center must remain visible: $center", (center.right - center.left).value >= 40f)
        rule.onNodeWithTag("toolbar-region-content-end", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun pinnedStartWidthFollowsItsVisibleToolsAndEmptyEndIsAbsent() {
        var startCount by mutableStateOf(1)
        val end = section(ToolbarRegion.END, 2, visible = false)
        rule.setContent {
            MaterialTheme {
                Surface(ToolbarLayout(listOf(
                    section(ToolbarRegion.START, startCount),
                    section(ToolbarRegion.CENTER, 6),
                    end,
                ), distribution = ToolbarDistribution.REGIONS, pinStart = true, pinEnd = true))
            }
        }
        rule.onNodeWithTag("toolbar-pin-end", useUnmergedTree = true).assertDoesNotExist()
        val oldWidth = rule.onNodeWithTag("toolbar-pin-start", useUnmergedTree = true).getUnclippedBoundsInRoot()
            .let { (it.right - it.left).value }
        rule.runOnIdle { startCount = 3 }
        rule.waitForIdle()
        val newWidth = rule.onNodeWithTag("toolbar-pin-start", useUnmergedTree = true).getUnclippedBoundsInRoot()
            .let { (it.right - it.left).value }
        assertTrue("pinned Start should grow with tools: $oldWidth -> $newWidth", newWidth >= oldWidth + 65f)
        rule.onNodeWithTag("toolbar-pin-end", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("toolbar-pin-scrolling", useUnmergedTree = true).getUnclippedBoundsInRoot()
    }

    @Test fun emptyStartIsAbsentAndPinnedEndResizesInVerticalToolbar() {
        var endCount by mutableStateOf(1)
        rule.setContent {
            MaterialTheme {
                Surface(ToolbarLayout(listOf(
                    section(ToolbarRegion.START, 1, visible = false),
                    section(ToolbarRegion.CENTER, 4),
                    section(ToolbarRegion.END, endCount),
                ), distribution = ToolbarDistribution.REGIONS, pinStart = true, pinEnd = true), vertical = true)
            }
        }
        rule.onNodeWithTag("toolbar-pin-start", useUnmergedTree = true).assertDoesNotExist()
        val oldHeight = rule.onNodeWithTag("toolbar-pin-end", useUnmergedTree = true).getUnclippedBoundsInRoot()
            .let { (it.bottom - it.top).value }
        rule.runOnIdle { endCount = 3 }
        rule.waitForIdle()
        val newHeight = rule.onNodeWithTag("toolbar-pin-end", useUnmergedTree = true).getUnclippedBoundsInRoot()
            .let { (it.bottom - it.top).value }
        assertTrue("pinned End should grow with tools: $oldHeight -> $newHeight", newHeight >= oldHeight + 65f)
        rule.onNodeWithTag("toolbar-pin-start", useUnmergedTree = true).assertDoesNotExist()
    }
}
