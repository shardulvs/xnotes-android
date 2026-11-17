package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolbarContainerMorphComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun toolIconsReflowContinuouslyInTwoAxesRatherThanSwappingLayouts() {
        val section = ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN),
            ToolbarEntry(ToolbarItem.ERASER), ToolbarEntry(ToolbarItem.COLORS)))
        var fraction by mutableFloatStateOf(0f)
        rule.setContent {
            CompositionLocalProvider(LocalToolbarContainerMorph provides
                ToolbarContainerMorph(fromVertical = false, toVertical = true, fraction = fraction)) {
                ToolbarMorphSectionGrid(section) { entry ->
                    Box(Modifier.size(40.dp).testTag("cell-${entry.item.id}"))
                }
            }
        }
        rule.waitForIdle()
        val pen = rule.onNodeWithTag("cell-pen", useUnmergedTree = true)
        val eraser = rule.onNodeWithTag("cell-eraser", useUnmergedTree = true)
        val first = eraser.getUnclippedBoundsInRoot()
        val base = pen.getUnclippedBoundsInRoot()
        assertTrue(first.left > base.left)
        rule.runOnIdle { fraction = 0.5f }
        rule.waitForIdle()
        val middle = eraser.getUnclippedBoundsInRoot()
        rule.runOnIdle { fraction = 1f }
        rule.waitForIdle()
        val last = eraser.getUnclippedBoundsInRoot()
        assertTrue("X coordinate should reflow over intermediate frames: $first $middle $last",
            first.left > middle.left && middle.left > last.left)
        assertTrue("Y coordinate should reflow over intermediate frames: $first $middle $last",
            first.top < middle.top && middle.top < last.top)
        assertEquals("No icon stretching", first.right - first.left, middle.right - middle.left)
        assertEquals("No icon stretching", middle.bottom - middle.top, last.bottom - last.top)
    }

    @Test fun compoundColorControlsSpreadApartInsteadOfOverlapping() {
        val section = ToolbarSection(listOf(ToolbarEntry(ToolbarItem.COLORS)))
        var fraction by mutableFloatStateOf(0f)
        rule.setContent {
            CompositionLocalProvider(LocalToolbarContainerMorph provides
                ToolbarContainerMorph(fromVertical = false, toVertical = true, fraction = fraction)) {
                ToolbarMorphSectionGrid(section) {
                    repeat(3) { index ->
                        Box(Modifier.size(28.dp).testTag("color-$index"))
                    }
                }
            }
        }
        rule.waitForIdle()
        val first = rule.onNodeWithTag("color-0", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val third = rule.onNodeWithTag("color-2", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("Swatches must start side-by-side", third.left > first.right)
        rule.runOnIdle { fraction = .5f }
        rule.waitForIdle()
        val middleThird = rule.onNodeWithTag("color-2", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("Swatches must move diagonally", middleThird.left < third.left &&
            middleThird.top > third.top)
        rule.runOnIdle { fraction = 1f }
        rule.waitForIdle()
        val endFirst = rule.onNodeWithTag("color-0", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val endThird = rule.onNodeWithTag("color-2", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("Swatches must end stacked vertically", endThird.top > endFirst.bottom)
    }

    @Test fun realToolbarFrameReflowsSectionsAndToolsInsideOneContainer() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(
            ToolbarEntry(ToolbarItem.PEN),
            ToolbarEntry(ToolbarItem.ERASER),
            ToolbarEntry(ToolbarItem.COLORS),
        ))))
        var fraction by mutableFloatStateOf(0f)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalToolbarLook provides ToolbarLook(floating = true),
                    LocalToolbarContainerMorph provides ToolbarContainerMorph(
                        fromVertical = false, toVertical = true, fraction = fraction),
                ) {
                    Box(Modifier.size(260.dp)) {
                        ToolbarFrame(armed = null, layout = layout) {
                            ToolbarSections(layout) { _, section ->
                                ToolbarSectionGrid(section) { entry ->
                                    Box(Modifier.size(40.dp).testTag("frame-${entry.item.id}"))
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        // The centre item stays centred by design; the edge tool must travel.
        val edgeTool = rule.onNodeWithTag("frame-pen", useUnmergedTree = true)
        val start = edgeTool.getUnclippedBoundsInRoot()
        rule.runOnIdle { fraction = .5f }
        rule.waitForIdle()
        val mid = edgeTool.getUnclippedBoundsInRoot()
        rule.runOnIdle { fraction = 1f }
        rule.waitForIdle()
        val end = edgeTool.getUnclippedBoundsInRoot()
        assertTrue("Actual ToolbarFrame must move its tools along both axes: $start $mid $end",
            start.left < mid.left && mid.left < end.left &&
                start.top > mid.top && mid.top > end.top)
        rule.onNodeWithTag("toolbar-frame", useUnmergedTree = true).assertExists()
    }

    @Test fun twoLineSectionReflowsColumnsWithoutLosingAnItem() {
        val section = ToolbarSection(listOf(
            ToolbarEntry(ToolbarItem.PEN, lane = 0),
            ToolbarEntry(ToolbarItem.ERASER, lane = 0),
            ToolbarEntry(ToolbarItem.COLORS, lane = 1),
        ), lanes = 2)
        var fraction by mutableFloatStateOf(0f)
        rule.setContent {
            CompositionLocalProvider(LocalToolbarContainerMorph provides
                ToolbarContainerMorph(fromVertical = false, toVertical = true, fraction = fraction)) {
                ToolbarMorphSectionGrid(section) { entry ->
                    Box(Modifier.size(40.dp).testTag("cell-${entry.item.id}"))
                }
            }
        }
        rule.waitForIdle()
        val colors = rule.onNodeWithTag("cell-colors", useUnmergedTree = true)
        val horizontal = colors.getUnclippedBoundsInRoot()
        rule.runOnIdle { fraction = 1f }
        rule.waitForIdle()
        val vertical = colors.getUnclippedBoundsInRoot()
        assertTrue("Second lane should move from lower row into side column",
            vertical.left > horizontal.left && vertical.top < horizontal.top)
        rule.onNodeWithTag("cell-pen", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cell-eraser", useUnmergedTree = true).assertExists()
    }
}
