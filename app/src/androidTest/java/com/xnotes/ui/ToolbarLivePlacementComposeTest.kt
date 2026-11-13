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
            up()
        }
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
            up()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("target", "moving"),
                workspace.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
    }
}
