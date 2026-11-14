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
import androidx.compose.ui.platform.testTag
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
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarDropPosition
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

    @Test fun projectedDropMustNotPersistIfThePlannerRejectedTheTarget() {
        var bounds by mutableStateOf(Rect.Zero)
        var commits = 0
        var canceled = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(650.dp).height(380.dp)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }) {
                    ToolbarDragSurface(
                        id = "rejected", anchor = ToolbarAnchor.CENTER_LEFT,
                        movable = true, paneBounds = bounds,
                        onMoveBar = { _, _, _ -> commits++ },
                        snapTargetInRoot = { null },
                        onDragCancel = { canceled++ },
                    ) { Box(Modifier.size(70.dp, 280.dp)) }
                }
            }
        }
        rule.waitForIdle()
        val surface = rule.onNodeWithTag("toolbar-drag-surface-rejected", useUnmergedTree = true)
        val before = surface.getUnclippedBoundsInRoot()
        surface.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(Offset(900f, 200f))
            advanceEventTime(48)
            up()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("An unplaceable destination may not be saved", 0, commits)
            assertEquals("Cancel should restore the original toolbar", 1, canceled)
        }
        val after = surface.getUnclippedBoundsInRoot()
        assertTrue("Bar should return to its existing valid position",
            kotlin.math.abs(before.left.value - after.left.value) < 2f)
    }

    @Test fun rejectedCrossEdgeDropClearsGhostAndRestoresPeerAllocation() {
        val workspace = ToolbarWorkspace(listOf(
            ToolbarBar("moving", "Moving", ToolbarLayout.DEFAULT),
            ToolbarBar("peer", "Peer", ToolbarLayout.DEFAULT),
        ))
        val reorder = ToolbarWorkspaceReorderController()
        var pane by mutableStateOf(Rect.Zero)
        val target = ToolbarDropPosition(ToolbarAnchor.CENTER_RIGHT, ToolbarDockAlignment.CENTER)
        var commits = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(720.dp).height(380.dp)
                    .onGloballyPositioned { pane = it.boundsInRoot() }) {
                    if (reorder.sourceId != null && reorder.hoverPlacement == target) {
                        Box(Modifier.size(60.dp).testTag("stale-ghost"))
                    }
                    Box(Modifier.offset(x = if (reorder.sourceId != null) 130.dp else 80.dp)
                        .size(52.dp).testTag("projected-peer"))
                    ToolbarDragSurface(
                        id = "moving", anchor = ToolbarAnchor.CENTER_LEFT,
                        movable = true, paneBounds = pane,
                        onMoveBar = { _, _, _ -> commits++ },
                        onDragStart = {
                            reorder.start("moving", emptyList(), verticalAxis = true,
                                workspace = workspace, originalAnchor = ToolbarAnchor.CENTER_LEFT)
                        },
                        onDrop = { false },
                        onPlacementPreview = { reorder.previewPlacement("moving", it) },
                        onDragCancel = { reorder.cancel("moving") },
                        snapTargetInRoot = { null },
                    ) { Box(Modifier.size(65.dp, 280.dp)) }
                }
            }
        }
        rule.waitForIdle()
        val peer = rule.onNodeWithTag("projected-peer", useUnmergedTree = true)
        val originalX = peer.getUnclippedBoundsInRoot().left.value
        val source = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        source.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(Offset(900f, 150f))
            advanceEventTime(48)
        }
        rule.waitForIdle()
        assertNotNull("The projected destination must be tracked", reorder.hoverPlacement)
        assertTrue("The old peer allocation must be shown before drop",
            peer.getUnclippedBoundsInRoot().left.value > originalX + 25f)
        source.performTouchInput { up() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("Rejected move must not save", 0, commits)
            assertNull("A failed drop cannot leave a source in the controller", reorder.sourceId)
            assertNull("A failed drop cannot leave the destination reserved", reorder.hoverPlacement)
        }
        rule.onNodeWithTag("stale-ghost", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("The peer must move back after a failed drop",
            originalX, peer.getUnclippedBoundsInRoot().left.value, 2f)
    }

    @Test fun abandonedMoveWatchdogClearsProjectionAfterMissingAcknowledgement() {
        val workspace = ToolbarWorkspace(listOf(
            ToolbarBar("moving", "Moving", ToolbarLayout.DEFAULT),
        ))
        val reorder = ToolbarWorkspaceReorderController()
        rule.setContent { ToolbarReorderRecoveryEffect(reorder) }
        rule.runOnIdle {
            reorder.start("moving", emptyList(), verticalAxis = true, workspace = workspace,
                originalAnchor = ToolbarAnchor.CENTER_LEFT)
            reorder.previewPlacement("moving",
                ToolbarDropPosition(ToolbarAnchor.CENTER_RIGHT, ToolbarDockAlignment.CENTER))
            reorder.markAnchorMoveReleased("moving")
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.runOnIdle {
            assertNull("A detached source must not hold the ghost forever", reorder.sourceId)
            assertNull("A detached source must release projected peers", reorder.hoverPlacement)
        }
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
