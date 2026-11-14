package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
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
import com.xnotes.core.tools.ToolbarDropPosition
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real toolbar runtime regression for new bars inserted at TOP/CENTER beside TOP/END.
 * Preview-only screenshots or a final-position assertion cannot detect the
 * single-frame blink that occurs when projected and committed bounds diverge. */
class ToolbarOccupiedTopCenterComposeTest {
    @get:Rule val rule = createComposeRule()

    @Composable private fun Body(bar: ToolbarBar) {
        Box(Modifier.testTag("actual-body-${bar.id}")) {
            ToolbarFrame(armed = null, layout = bar.layout, geometry = bar.geometry) {
                ToolbarSections(bar.layout) { _, section ->
                    ToolbarSectionGrid(section) { _ -> Box(Modifier.size(42.dp)) }
                }
            }
        }
    }

    private fun matrix(rows: Int, floating: Boolean, initial: ToolbarAnchor,
                       width: Int, height: Int, extraPeer: Boolean = false,
                       peerOnRightStart: Boolean = false,
                       viaRightPeer: Boolean = false,
                       dropToRightCenter: Boolean = false) {
        fun layout(count: Int): ToolbarLayout = ToolbarLayout(listOf(ToolbarSection(
            (0 until count).flatMap { lane -> listOf(
                ToolbarEntry(ToolbarItem.COLORS, lane = lane),
                ToolbarEntry(ToolbarItem.PEN, lane = lane),
            ) }, lanes = count,
        )))
        val peer = ToolbarBar("peer", "Upper right", layout(1), geometry = ToolbarGeometry(
            floating = floating,
            anchor = if (peerOnRightStart) ToolbarAnchor.CENTER_RIGHT else ToolbarAnchor.TOP_RIGHT,
            dockEdge = if (floating) null else if (peerOnRightStart) ToolbarDockEdge.RIGHT else ToolbarDockEdge.TOP,
            dockAlignment = if (peerOnRightStart) ToolbarDockAlignment.START else ToolbarDockAlignment.END,
            widthDp = if (peerOnRightStart) 72 else 168,
            heightDp = if (peerOnRightStart) 210 else 55,
        ))
        val moving = ToolbarBar("moving", "Target", layout(rows), geometry = ToolbarGeometry(
            floating = floating, anchor = initial,
            dockEdge = if (floating) null else initial.floatingEdge(),
            dockAlignment = when (initial) {
                ToolbarAnchor.TOP_LEFT, ToolbarAnchor.BOTTOM_LEFT -> ToolbarDockAlignment.START
                ToolbarAnchor.TOP_RIGHT, ToolbarAnchor.BOTTOM_RIGHT -> ToolbarDockAlignment.END
                else -> ToolbarDockAlignment.CENTER
            }, widthDp = width, heightDp = height,
        ))
        val extra = ToolbarBar("extra", "Third", layout(2), geometry = ToolbarGeometry(
            floating = floating, anchor = ToolbarAnchor.CENTER_LEFT,
            dockEdge = if (floating) null else ToolbarDockEdge.LEFT,
            dockAlignment = ToolbarDockAlignment.CENTER, widthDp = 112, heightDp = 180,
        ))
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(peer, moving) + if (extraPeer) listOf(extra) else emptyList()))
        var look by mutableStateOf(ToolbarLook(position = ToolbarPosition.TOP, floating = floating))
        var commits = 0
        val moveBar: (String, ToolbarAnchor, ToolbarDockAlignment) -> Unit = { id, a, align ->
            val moved = toolbarProjectedMove(workspace, look, id,
                ToolbarDropPosition(a, align))!!
            workspace = moved.first
            look = moved.second
            commits++
        }
        val reorder: (String, String) -> Unit = { from, to ->
            workspace = workspace.reorderVisualBar(from, to)
        }
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides look) {
                    if (floating) {
                        Box(Modifier.size(840.dp, if (dropToRightCenter) 525.dp else 430.dp).testTag("runtime-matrix-stage")) {
                            WorkspaceFloatingBars(workspace,
                                onMoveBar = moveBar,
                                onReorderBar = reorder,
                                primaryContent = { Body(workspace.bars[0]) },
                                content = { Body(it) },
                                colorCount = 6,
                            )
                        }
                    } else {
                        Column(Modifier.size(840.dp, if (dropToRightCenter) 525.dp else 430.dp).testTag("runtime-matrix-stage")) {
                            ToolbarAround(
                                bar = { Body(workspace.bars[0]) },
                                onCover = { _, _, _, _ -> },
                                workspace = workspace,
                                dockedContent = { Body(it) },
                                onMoveBar = moveBar,
                                onReorderBar = reorder,
                            ) { floatingSlot ->
                                Box(Modifier.fillMaxSize()) { floatingSlot() }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val stage = rule.onNodeWithTag("runtime-matrix-stage")
        val pane = stage.fetchSemanticsNode().boundsInRoot
        val moved = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val first = moved.fetchSemanticsNode().boundsInRoot
        val end = if (dropToRightCenter) Offset(pane.right - 10f, pane.center.y)
            else Offset(pane.center.x, pane.top + 10f)
        rule.mainClock.autoAdvance = false
        stage.performTouchInput {
            down(first.center - pane.topLeft)
            advanceEventTime(820)
            if (viaRightPeer) {
                val peerHover = Offset(pane.right - 24f, pane.top + 95f)
                // Follow the finger through an occupied edge, so the interim
                // predicted layout is recomputed before TOP/CENTER is selected.
                repeat(8) { moveBy((peerHover - first.center) / 8f); advanceEventTime(16) }
                advanceEventTime(100)
                repeat(12) { moveBy((end - peerHover) / 12f); advanceEventTime(16) }
            } else {
                moveBy(end - first.center)
            }
            advanceEventTime(48)
            up()
        }
        val peerNode = rule.onNodeWithTag("live-toolbar-slot-peer", useUnmergedTree = true)
        val bodyNode = rule.onNodeWithTag("actual-body-moving", useUnmergedTree = true)
        val parentNode = rule.onNodeWithTag("live-toolbar-slot-moving", useUnmergedTree = true)
        var postCommitX: Float? = null
        var postCommitY: Float? = null
        var postPeerX: Float? = null
        repeat(28) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val source = moved.fetchSemanticsNode().boundsInRoot
            val peerBox = peerNode.fetchSemanticsNode().boundsInRoot
            assertTrue("Lost source at frame=$frame rows=$rows floating=$floating initial=$initial: $source",
                source.width > 1f && source.height > 1f)
            assertTrue("Lost peer at frame=$frame rows=$rows floating=$floating initial=$initial: $peerBox",
                peerBox.width > 1f && peerBox.height > 1f)
            if (dropToRightCenter) {
                val body = bodyNode.fetchSemanticsNode().boundsInRoot
                val wrapper = parentNode.fetchSemanticsNode().boundsInRoot
                println("RIGHT_CENTER_TRACE rows=$rows floating=$floating initial=$initial frame=$frame commits=$commits source=$source body=$body wrapper=$wrapper peer=$peerBox pane=$pane")
                assertTrue("Right-edge toolbar escaped viewport in frame $frame, rows=$rows floating=$floating: $source / $pane",
                    source.right <= pane.right + 3f && source.left >= pane.left - 3f)
                assertTrue("Rendered Colors content escaped viewport at frame $frame rows=$rows floating=$floating: body=$body stage=$pane",
                    body.right <= pane.right + 3f && body.left >= pane.left - 3f)
            }
            if (commits > 0) {
                postCommitX?.let { old -> assertTrue("Runtime post-commit x jumped frame=$frame rows=$rows floating=$floating initial=$initial: $old -> ${source.left}",
                    kotlin.math.abs(old - source.left) < 18f) }
                postCommitY?.let { old -> assertTrue("Runtime post-commit y jumped frame=$frame rows=$rows floating=$floating initial=$initial: $old -> ${source.top}",
                    kotlin.math.abs(old - source.top) < 18f) }
                postPeerX?.let { old -> assertTrue("TOP_RIGHT peer jumped after commit frame=$frame rows=$rows floating=$floating initial=$initial: $old -> ${peerBox.right}",
                    kotlin.math.abs(old - peerBox.right) < 18f) }
                postCommitX = source.left
                postCommitY = source.top
                postPeerX = peerBox.right
            }
        }
        rule.runOnIdle {
            assertEquals("Expected one saved move", 1, commits)
            val saved = workspace.bars.first { it.id == "moving" }
            assertEquals(if (dropToRightCenter) ToolbarAnchor.CENTER_RIGHT else ToolbarAnchor.TOP_CENTER,
                saved.geometry.anchor)
            assertEquals(ToolbarDockAlignment.CENTER, saved.geometry.dockAlignment)
            assertEquals(if (peerOnRightStart) ToolbarAnchor.CENTER_RIGHT else ToolbarAnchor.TOP_RIGHT,
                workspace.bars.first().geometry.anchor)
            assertEquals(if (peerOnRightStart) ToolbarDockAlignment.START else ToolbarDockAlignment.END,
                workspace.bars.first().geometry.dockAlignment)
        }
        rule.mainClock.autoAdvance = true
    }

    @Test fun oneRowDockedRightToOccupiedTopCenter() = matrix(1, false, ToolbarAnchor.CENTER_RIGHT, 56, 148)
    @Test fun oneRowFloatingRightToOccupiedTopCenter() = matrix(1, true, ToolbarAnchor.CENTER_RIGHT, 56, 148)
    @Test fun oneRowDockedSameTopRightToCenter() = matrix(1, false, ToolbarAnchor.TOP_RIGHT, 148, 55)
    @Test fun oneRowFloatingSameTopRightToCenter() = matrix(1, true, ToolbarAnchor.TOP_RIGHT, 148, 55)
    @Test fun twoRowsDockedLeftToOccupiedTopCenter() = matrix(2, false, ToolbarAnchor.CENTER_LEFT, 112, 196)
    @Test fun twoRowsFloatingBottomToOccupiedTopCenter() = matrix(2, true, ToolbarAnchor.BOTTOM_LEFT, 112, 196)
    @Test fun threeRowsDockedRightToOccupiedTopCenter() = matrix(3, false, ToolbarAnchor.CENTER_RIGHT, 172, 255)
    @Test fun threeRowsFloatingLeftToOccupiedTopCenter() = matrix(3, true, ToolbarAnchor.CENTER_LEFT, 172, 255)
    @Test fun oneRowDockedThreeBarsTopCenter() = matrix(1, false, ToolbarAnchor.CENTER_RIGHT, 56, 148, true)
    @Test fun threeRowsFloatingThreeBarsTopCenter() = matrix(3, true, ToolbarAnchor.TOP_RIGHT, 172, 255, true)

    @Test fun rightStartOneRowFloatingTopCenter() = matrix(1, true, ToolbarAnchor.TOP_LEFT, 150, 55, peerOnRightStart = true)
    @Test fun rightStartOneRowDockedTopCenter() = matrix(1, false, ToolbarAnchor.CENTER_LEFT, 56, 148, peerOnRightStart = true)
    @Test fun rightStartTwoRowsFloatingTopCenter() = matrix(2, true, ToolbarAnchor.BOTTOM_CENTER, 160, 112, peerOnRightStart = true)
    @Test fun rightStartTwoRowsDockedTopCenter() = matrix(2, false, ToolbarAnchor.TOP_LEFT, 160, 112, peerOnRightStart = true)
    @Test fun rightStartThreeRowsFloatingTopCenter() = matrix(3, true, ToolbarAnchor.CENTER_LEFT, 172, 255, peerOnRightStart = true)
    @Test fun rightStartThreeRowsDockedTopCenter() = matrix(3, false, ToolbarAnchor.BOTTOM_LEFT, 172, 255, peerOnRightStart = true)
    @Test fun rightStartExtraPeerFloatingTopCenter() = matrix(1, true, ToolbarAnchor.TOP_LEFT, 150, 55, true, true)
    @Test fun rightStartExtraPeerDockedTopCenter() = matrix(3, false, ToolbarAnchor.CENTER_LEFT, 172, 255, true, true)

    @Test fun hoverThroughRightStartOneRowFloatingTopCenter() =
        matrix(1, true, ToolbarAnchor.CENTER_RIGHT, 56, 148,
            peerOnRightStart = true, viaRightPeer = true)
    @Test fun hoverThroughRightStartOneRowDockedTopCenter() =
        matrix(1, false, ToolbarAnchor.CENTER_RIGHT, 56, 148,
            peerOnRightStart = true, viaRightPeer = true)
    @Test fun hoverThroughRightStartTwoRowsFloatingTopCenter() =
        matrix(2, true, ToolbarAnchor.CENTER_LEFT, 112, 196,
            peerOnRightStart = true, viaRightPeer = true)
    @Test fun hoverThroughRightStartThreeRowsDockedTopCenter() =
        matrix(3, false, ToolbarAnchor.CENTER_RIGHT, 172, 255, true, true, true)
    /** Redmi 16:10 video: TOP/CENTER one-row Colors to RIGHT/CENTER while
     * an independent toolbar occupies RIGHT/START. Watch all post-release frames. */
    @Test fun video1610OneRowFloatingTopToRightWithRightStartPeer() =
        matrix(1, true, ToolbarAnchor.TOP_CENTER, 220, 55,
            peerOnRightStart = true, dropToRightCenter = true)

    @Test fun video1610OneRowDockedTopToRightWithRightStartPeer() =
        matrix(1, false, ToolbarAnchor.TOP_CENTER, 220, 55,
            peerOnRightStart = true, dropToRightCenter = true)

    @Test fun video1610TwoRowsFloatingTopToRightWithRightStartPeer() =
        matrix(2, true, ToolbarAnchor.TOP_CENTER, 220, 112,
            peerOnRightStart = true, dropToRightCenter = true)

    @Test fun video1610ThreeRowsFloatingTopToRightWithRightStartPeer() =
        matrix(3, true, ToolbarAnchor.TOP_CENTER, 250, 168,
            peerOnRightStart = true, dropToRightCenter = true)

}
