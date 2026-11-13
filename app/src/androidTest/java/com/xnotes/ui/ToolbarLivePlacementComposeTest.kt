package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Tests the *live* XNote/XCanvas containers, not the already-correct editor preview. */
class ToolbarLivePlacementComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun bar(id: String, tall: Boolean, floating: Boolean) = ToolbarBar(
        id, id, ToolbarLayout.DEFAULT,
        geometry = ToolbarGeometry(
            dockEdge = if (floating) null else ToolbarDockEdge.BOTTOM,
            anchor = ToolbarAnchor.BOTTOM_CENTER,
            dockAlignment = ToolbarDockAlignment.CENTER,
            floating = floating,
            dockLane = 0,
            widthDp = 148,
            heightDp = if (tall) 112 else 52,
        ),
    )

    /** Actual ToolbarFrame + ToolbarSectionGrid measurements: one row vs two rows. */
    private fun naturalBar(id: String, rows: Int, floating: Boolean): ToolbarBar {
        val entries = if (rows == 2) listOf(
            ToolbarEntry(ToolbarItem.PEN, lane = 0),
            ToolbarEntry(ToolbarItem.ERASER, lane = 1),
        ) else listOf(ToolbarEntry(ToolbarItem.COLORS, lane = 0))
        return ToolbarBar(id, id,
            ToolbarLayout(listOf(ToolbarSection(entries, lanes = rows))),
            geometry = ToolbarGeometry(
                floating = floating, anchor = ToolbarAnchor.BOTTOM_CENTER,
                dockEdge = if (floating) null else ToolbarDockEdge.BOTTOM,
                dockAlignment = ToolbarDockAlignment.CENTER,
                dockLane = 0, widthDp = 220, heightDp = null,
            ))
    }

    @Composable
    private fun RealToolbar(bar: ToolbarBar) {
        Box(Modifier.testTag("natural-${bar.id}")) {
            ToolbarFrame(armed = null, layout = bar.layout, geometry = bar.geometry) {
                ToolbarSections(bar.layout) { _, section ->
                    ToolbarSectionGrid(section) { _ -> Box(Modifier.size(42.dp)) }
                }
            }
        }
    }

    @Test fun naturalOneRowBarTouchesBottomEdgeBesideTwoRowBarInDockedMode() {
        val bars = ToolbarWorkspace(listOf(
            naturalBar("two", 2, false), naturalBar("one", 1, false)))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides ToolbarLook()) {
                    Column(Modifier.size(840.dp, 380.dp)) {
                        ToolbarAround(bar = { RealToolbar(bars.bars.first()) },
                            onCover = { _, _, _, _ -> },
                            workspace = bars,
                            dockedContent = { RealToolbar(it) }) {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        val tall = rule.onNodeWithTag("natural-two", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val short = rule.onNodeWithTag("natural-one", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Natural 1-row dock bar does not meet 2-row bottom edge: $short / $tall",
            kotlin.math.abs((tall.bottom - short.bottom).value) <= 2f)
        assertTrue("Expected real two-row toolbar to be taller than one-row toolbar", tall.top < short.top)
    }

    @Test fun naturalOneRowBarTouchesBottomEdgeBesideTwoRowBarInFloatingMode() {
        val bars = ToolbarWorkspace(listOf(
            naturalBar("two", 2, true), naturalBar("one", 1, true)))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides ToolbarLook(floating = true)) {
                    Box(Modifier.size(840.dp, 380.dp)) {
                        WorkspaceFloatingBars(bars, onMoveBar = { _, _, _ -> },
                            primaryContent = { RealToolbar(bars.bars.first()) },
                            content = { RealToolbar(it) })
                    }
                }
            }
        }
        val tall = rule.onNodeWithTag("natural-two", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val short = rule.onNodeWithTag("natural-one", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Natural 1-row float bar does not meet 2-row bottom edge: $short / $tall",
            kotlin.math.abs((tall.bottom - short.bottom).value) <= 2f)
        assertTrue("Expected real two-row toolbar to be taller than one-row toolbar", tall.top < short.top)
    }

    @Test fun crossEdgeAfterReorderHoverShowsIndicatorAndPrepositionsPeers() {
        val initial = ToolbarWorkspace(listOf(
            bar("moving", false, true), bar("peer", false, true)))
        var workspace by mutableStateOf(initial)
        var moveCommits = 0
        val look = ToolbarLook(floating = true)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides look) {
                    Box(Modifier.size(840.dp, 380.dp).testTag("cross-edge-stage")) {
                        WorkspaceFloatingBars(workspace, onMoveBar = { id, anchor, alignment ->
                            moveCommits++
                            workspace = toolbarProjectedMove(workspace, look, id,
                                com.xnotes.core.tools.ToolbarDropPosition(anchor, alignment))!!.first
                        }, onReorderBar = { from, to ->
                            workspace = workspace.reorderVisualBar(from, to)
                        }, primaryContent = { Box(Modifier.size(148.dp, 52.dp)) },
                            content = { Box(Modifier.size(148.dp, 52.dp)) })
                    }
                }
            }
        }
        rule.waitForIdle()
        val stage = rule.onNodeWithTag("cross-edge-stage", useUnmergedTree = true)
        val stageTopLeft = stage.fetchSemanticsNode().boundsInRoot.topLeft
        val first = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center
        val neighbor = rule.onNodeWithTag("live-toolbar-slot-peer", useUnmergedTree = true)
        val peerBefore = neighbor.fetchSemanticsNode().boundsInRoot.center
        stage.performTouchInput {
            down(first - stageTopLeft)
            advanceEventTime(790)
            moveBy(peerBefore - first)
            advanceEventTime(48)
        }
        rule.waitForIdle()
        // The same long-press gesture now crosses from reorder into edge placement.
        // The peer's original hitbox remains frozen, but it must stop blocking the ghost.
        stage.performTouchInput {
            moveBy(Offset(0f, stageTopLeft.y + 25f - peerBefore.y))
            advanceEventTime(48)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("toolbar-drop-indicator-moving", useUnmergedTree = true).assertExists()
        rule.runOnIdle { assertEquals("Pre-layout must not persist a move", 0, moveCommits) }
        val peerDuring = neighbor.fetchSemanticsNode().boundsInRoot.center
        assertTrue("Neighbour must prepare its new position before the drop",
            kotlin.math.abs(peerBefore.x - peerDuring.x) > 5f ||
                kotlin.math.abs(peerBefore.y - peerDuring.y) > 5f)
        stage.performTouchInput { up() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(1, moveCommits)
            assertTrue(workspace.bars.first().geometry.anchor in listOf(
                ToolbarAnchor.TOP_LEFT, ToolbarAnchor.TOP_CENTER, ToolbarAnchor.TOP_RIGHT))
        }
    }

    @Test fun floatingMoveShowsProjectedTargetAndSettlesAfterRelease() {
        val moving = bar("moving", false, true)
        val peer = bar("peer", false, true).let { it.copy(geometry =
            it.geometry.copy(anchor = ToolbarAnchor.TOP_CENTER,
                dockAlignment = ToolbarDockAlignment.CENTER)) }
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(moving, peer)))
        var commits = 0
        val look = ToolbarLook(floating = true)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides look) {
                    Box(Modifier.size(840.dp, 380.dp).testTag("drop-stage")) {
                        WorkspaceFloatingBars(workspace = workspace,
                            onMoveBar = { id, anchor, alignment ->
                                commits++
                                workspace = toolbarProjectedMove(workspace, look, id,
                                    com.xnotes.core.tools.ToolbarDropPosition(anchor, alignment))!!.first
                            },
                            onReorderBar = { source, destination ->
                                workspace = workspace.reorderVisualBar(source, destination)
                            },
                            primaryContent = { Box(Modifier.size(148.dp, 52.dp)) },
                            content = { Box(Modifier.size(148.dp, 52.dp)) },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val stage = rule.onNodeWithTag("drop-stage", useUnmergedTree = true)
        val origin = stage.fetchSemanticsNode().boundsInRoot.topLeft
        val barCenter = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center
        // Drag to the top, but away from the other bar so reorder won't consume it.
        stage.performTouchInput {
            down(barCenter - origin)
            advanceEventTime(780)
            moveBy(Offset(-145f, origin.y + 25f - barCenter.y))
            advanceEventTime(48)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("toolbar-drop-indicator-moving", useUnmergedTree = true).assertExists()
        rule.runOnIdle { assertEquals("The bar must not persist while the finger is down", 0, commits) }
        stage.performTouchInput { up() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("A single completed move must be persisted", 1, commits)
            assertTrue("The released bar must move to the top edge",
                workspace.bars.first().geometry.anchor in listOf(
                    ToolbarAnchor.TOP_LEFT, ToolbarAnchor.TOP_CENTER, ToolbarAnchor.TOP_RIGHT))
        }
        val slot = rule.onNodeWithTag("live-toolbar-slot-moving", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val stageBox = stage.getUnclippedBoundsInRoot()
        assertTrue("Bar should settle near the target top edge: $slot vs $stageBox",
            slot.top < stageBox.top + 60.dp)
    }

    @Test fun legacyPrimaryAndColorsUseSameFloatingLaneAndCanReorder() {
        // The primary uses legacy ToolbarLook.floating rather than geometry.floating.
        // The old runtime drew it separately from colors; unlike the preview, its
        // lower edge could drift and it was excluded from same-lane gesture reorder.
        val primary = naturalBar("main", 2, true).let { bar ->
            bar.copy(geometry = bar.geometry.copy(floating = false))
        }
        val colors = naturalBar("colors", 1, true)
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(primary, colors)))
        val look = ToolbarLook(floating = true, position = ToolbarPosition.BOTTOM)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides look) {
                    Column(Modifier.size(840.dp, 380.dp)) {
                        ToolbarAround(
                            bar = { RealToolbar(workspace.bars.first()) },
                            onCover = { _, _, _, _ -> },
                            workspace = workspace,
                            dockedContent = { RealToolbar(it) },
                            onMoveBar = { _, _, _ -> },
                            onReorderBar = { from, to ->
                                workspace = workspace.reorderVisualBar(from, to)
                            },
                        ) { oldFloating ->
                            Box(Modifier.fillMaxSize()) {
                                oldFloating()
                                WorkspaceFloatingBars(workspace, onMoveBar = { _, _, _ -> },
                                    onReorderBar = { from, to ->
                                        workspace = workspace.reorderVisualBar(from, to)
                                    },
                                    primaryContent = { RealToolbar(workspace.bars.first()) },
                                    content = { RealToolbar(it) },
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals("Legacy primary must not be rendered twice", 1,
            rule.onAllNodesWithTag("natural-main", useUnmergedTree = true)
                .fetchSemanticsNodes().size)
        val main = rule.onNodeWithTag("natural-main", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val colorBar = rule.onNodeWithTag("natural-colors", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("Legacy floating main and colors do not share bottom edge: $main / $colorBar",
            kotlin.math.abs((main.bottom - colorBar.bottom).value) <= 2f)
        val source = rule.onNodeWithTag("toolbar-drag-surface-main", useUnmergedTree = true)
        val start = source.fetchSemanticsNode().boundsInRoot.center
        val end = rule.onNodeWithTag("live-toolbar-slot-colors", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center
        source.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(Offset(end.x - start.x, end.y - start.y))
            advanceEventTime(40)
            up()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("colors", "main"),
                workspace.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
    }

    @Test fun shortDockedBottomBarSharesScreenEdgeWithTwoRowPeer() {
        val workspace = ToolbarWorkspace(listOf(bar("two", true, false), bar("one", false, false)))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides ToolbarLook()) {
                    Column(Modifier.size(840.dp, 380.dp)) {
                        ToolbarAround(
                            bar = { Box(Modifier.size(148.dp, 112.dp).testTag("two-content")) },
                            onCover = { _, _, _, _ -> },
                            workspace = workspace,
                            dockedContent = { Box(Modifier.width(148.dp).height(52.dp).testTag("one-content")) },
                        ) { _ -> Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        val tall = rule.onNodeWithTag("two-content", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val short = rule.onNodeWithTag("one-content", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Docked bottom shorter bar is floating inside the taller lane: $short vs $tall",
            kotlin.math.abs((tall.bottom - short.bottom).value) <= 2f)
        assertTrue("Regression scenario must use different row heights", tall.top < short.top)
    }

    @Test fun shortFloatingBottomBarSharesScreenEdgeWithTwoRowPeer() {
        val workspace = ToolbarWorkspace(listOf(bar("two", true, true), bar("one", false, true)))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides ToolbarLook(floating = true)) {
                    Box(Modifier.size(840.dp, 380.dp)) {
                        WorkspaceFloatingBars(workspace, onMoveBar = { _, _, _ -> },
                            primaryContent = { Box(Modifier.size(148.dp, 112.dp).testTag("two-content")) },
                            content = { Box(Modifier.width(148.dp).height(52.dp).testTag("one-content")) })
                    }
                }
            }
        }
        val tall = rule.onNodeWithTag("two-content", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val short = rule.onNodeWithTag("one-content", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Floating bottom shorter bar is floating inside the taller lane: $short vs $tall",
            kotlin.math.abs((tall.bottom - short.bottom).value) <= 2f)
        assertTrue("Regression scenario must use different row heights", tall.top < short.top)
    }

    @Test fun dockedBarOrientationChangesByResizingTheSlot() {
        val horizontal = bar("resizing", false, false).copy(geometry =
            bar("resizing", false, false).geometry.copy(
                anchor = ToolbarAnchor.BOTTOM_CENTER, dockEdge = ToolbarDockEdge.BOTTOM,
                widthDp = 240, heightDp = 55))
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(horizontal)))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides ToolbarLook()) {
                    Column(Modifier.size(840.dp, 380.dp)) {
                        ToolbarAround(bar = { Box(Modifier.size(55.dp)) },
                            onCover = { _, _, _, _ -> }, workspace = workspace,
                            dockedContent = { Box(Modifier.size(55.dp)) },
                        ) { _ -> Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag("live-toolbar-slot-resizing", useUnmergedTree = true)
        val original = node.getUnclippedBoundsInRoot()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            val bar = workspace.bars.first()
            workspace = workspace.replaceBar(bar.copy(geometry = bar.geometry.copy(
                anchor = ToolbarAnchor.CENTER_RIGHT, dockEdge = ToolbarDockEdge.RIGHT,
                widthDp = 55, heightDp = 240)))
        }
        rule.mainClock.advanceTimeByFrame()
        val first = node.getUnclippedBoundsInRoot()
        rule.mainClock.advanceTimeBy(96)
        val middle = node.getUnclippedBoundsInRoot()
        rule.mainClock.advanceTimeBy(360)
        val completed = node.getUnclippedBoundsInRoot()
        assertTrue("Initial horizontal slot must be wide", (original.right - original.left).value > 200f)
        assertTrue("Final vertical slot must be narrow", (completed.right - completed.left).value < 90f)
        assertTrue("Width should interpolate, not jump to final size: $first / $middle / $completed",
            (middle.right - middle.left).value < (first.right - first.left).value - 8f &&
                (middle.right - middle.left).value > (completed.right - completed.left).value + 8f)
        assertTrue("Height should interpolate towards vertical: $first / $middle / $completed",
            (middle.bottom - middle.top).value > (first.bottom - first.top).value + 8f &&
                (middle.bottom - middle.top).value < (completed.bottom - completed.top).value - 8f)
        rule.mainClock.autoAdvance = true
    }

    @Test fun liveFloatingBarsCanReorderWithSameLongPressGestureAsPreview() {
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(
            bar("moving", false, true), bar("target", false, true))))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides ToolbarLook(floating = true)) {
                    Box(Modifier.size(840.dp, 380.dp)) {
                        WorkspaceFloatingBars(workspace = workspace,
                            onMoveBar = { _, _, _ -> },
                            onReorderBar = { from, to -> workspace = workspace.reorderVisualBar(from, to) },
                            primaryContent = { Box(Modifier.size(148.dp, 52.dp)) },
                            content = { Box(Modifier.size(148.dp, 52.dp)) })
                    }
                }
            }
        }
        rule.waitForIdle()
        val from = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val to = rule.onNodeWithTag("live-toolbar-slot-target", useUnmergedTree = true)
        val start = from.fetchSemanticsNode().boundsInRoot.center
        val end = to.fetchSemanticsNode().boundsInRoot.center
        from.performTouchInput {
            down(center)
            advanceEventTime(790)
            moveBy(Offset(end.x - start.x, end.y - start.y))
            advanceEventTime(48)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("toolbar-drop-indicator-moving", useUnmergedTree = true)
            .assertExists()
        rule.runOnIdle {
            assertEquals("The reorder preview must not persist until release",
                listOf("moving", "target"),
                workspace.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
        from.performTouchInput { up() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("target", "moving"),
                workspace.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
    }

    @Test fun liveDockedBarsCanReorderWithSameLongPressGestureAsPreview() {
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(
            bar("moving", false, false), bar("target", false, false))))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides ToolbarLook()) {
                    Column(Modifier.size(840.dp, 380.dp)) {
                        ToolbarAround(bar = { Box(Modifier.size(148.dp, 52.dp)) },
                            onCover = { _, _, _, _ -> },
                            workspace = workspace,
                            dockedContent = { Box(Modifier.size(148.dp, 52.dp)) },
                            onMoveBar = { _, _, _ -> },
                            onReorderBar = { from, to -> workspace = workspace.reorderVisualBar(from, to) },
                        ) { _ -> Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        rule.waitForIdle()
        val from = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val to = rule.onNodeWithTag("live-toolbar-slot-target", useUnmergedTree = true)
        val start = from.fetchSemanticsNode().boundsInRoot.center
        val end = to.fetchSemanticsNode().boundsInRoot.center
        from.performTouchInput {
            down(center)
            advanceEventTime(790)
            moveBy(Offset(end.x - start.x, end.y - start.y))
            advanceEventTime(48)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("toolbar-drop-indicator-moving", useUnmergedTree = true)
            .assertExists()
        rule.runOnIdle {
            assertEquals("The reorder preview must not persist until release",
                listOf("moving", "target"),
                workspace.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
        from.performTouchInput { up() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("target", "moving"),
                workspace.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
    }
}
