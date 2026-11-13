package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarAnchor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Verifies the move path independently of the reorder controller.
 * A delayed parent preference acknowledgement must not expose the old position. */
class ToolbarDragSurfaceSettlementTest {
    @get:Rule val rule = createComposeRule()

    @Test fun projectedDropAnimatesFromReleaseBeforeCommittingNewPosition() {
        var anchor by mutableStateOf(ToolbarAnchor.TOP_LEFT)
        var alignment by mutableStateOf(ToolbarDockAlignment.START)
        var parentX by mutableStateOf(100.dp)
        var root by mutableStateOf(Rect.Zero)
        var commits = 0
        val destination = 1100f
        rule.setContent {
            val density = LocalDensity.current
            MaterialTheme {
                Box(Modifier.width(1200.dp).height(600.dp)
                    .onGloballyPositioned { root = it.boundsInRoot() }) {
                    Box(Modifier.offset(x = parentX, y = 120.dp).size(150.dp, 65.dp)) {
                        ToolbarDragSurface(
                            id = "animated", anchor = anchor, movable = true,
                            currentAlignment = alignment,
                            paneBounds = Rect(root.left, root.top, root.left + 1800f,
                                root.top + 900f),
                            onMoveBar = { _, target, targetAlignment ->
                                commits++
                                anchor = target
                                alignment = targetAlignment
                                parentX = with(density) { destination.toDp() }
                            },
                            snapTargetInRoot = { Offset(root.left + destination,
                                root.top + with(density) { 120.dp.toPx() }) },
                        ) { Box(Modifier.size(150.dp, 65.dp)) }
                    }
                }
            }
        }
        rule.waitForIdle()
        val stage = rule.onNodeWithTag("toolbar-drag-surface-animated", useUnmergedTree = true)
        val original = stage.getUnclippedBoundsInRoot().left.value
        rule.mainClock.autoAdvance = false
        stage.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(Offset(700f, 0f))
            advanceEventTime(48)
            up()
        }
        rule.mainClock.advanceTimeByFrame()
        var previous = stage.getUnclippedBoundsInRoot().left.value
        assertTrue("Released bar returned to old slot", previous > original + 250f)
        rule.runOnIdle { assertEquals("Move committed before animation", 0, commits) }
        repeat(4) { frame ->
            rule.mainClock.advanceTimeBy(36)
            rule.waitForIdle()
            val current = stage.getUnclippedBoundsInRoot().left.value
            assertTrue("Frame $frame snapped back: $previous -> $current", current >= previous - 4f)
            previous = current
        }
        rule.mainClock.advanceTimeBy(130)
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("Expected one commit after landing", 1, commits) }
        val settled = stage.getUnclippedBoundsInRoot().left.value
        assertTrue("Landed at incorrect destination: $settled", settled > original + 550f)
        rule.mainClock.autoAdvance = true
    }

    @Test fun delayedAnchorCommitDoesNotShowTheOldLocation() {
        var anchor by mutableStateOf(ToolbarAnchor.TOP_LEFT)
        var parentX by mutableStateOf(100.dp)
        var pendingAnchor: ToolbarAnchor? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(1000.dp).height(600.dp)) {
                    Box(Modifier.offset(x = parentX, y = 200.dp).size(150.dp, 65.dp)) {
                        ToolbarDragSurface(
                            id = "moving", anchor = anchor, movable = true,
                            paneBounds = Rect(0f, 0f, 1800f, 900f),
                            onMoveBar = { _, target, _ -> pendingAnchor = target },
                        ) { Box(Modifier.size(150.dp, 65.dp)) }
                    }
                }
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val before = node.getUnclippedBoundsInRoot().left.value
        val distance = 300f
        node.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(Offset(distance, 0f))
            advanceEventTime(48)
            up()
        }
        rule.waitForIdle()
        assertNotNull("The move callback must be called", pendingAnchor)
        // The editor has not yet published its new geometry. The bar must stay
        // where it was released instead of flashing to the old anchor.
        repeat(4) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val x = node.getUnclippedBoundsInRoot().left.value
            assertTrue("frame=$frame returned to old slot x=$x before=$before", x > before + 100f)
        }
        rule.runOnIdle {
            anchor = pendingAnchor!!
            parentX = 450.dp
        }
        rule.waitForIdle()
        assertTrue("settled bar must follow the new parent slot",
            node.getUnclippedBoundsInRoot().left.value > before + 200f)
    }
}
