package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.*
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarSize
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ToolbarTitleAndPreviewReorderComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun singleRowTopFloatingBarSharesTheTopEdgeWithTwoRowPeer() {
        val tall = ToolbarLayout(listOf(ToolbarSection(listOf(
            ToolbarEntry(ToolbarItem.PEN, lane = 0),
            ToolbarEntry(ToolbarItem.ERASER, lane = 1),
        ), lanes = 2)))
        val short = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        val bars = ToolbarWorkspace(listOf(
            ToolbarBar("two", "Two rows", tall, geometry = ToolbarGeometry(
                floating = true, anchor = ToolbarAnchor.TOP_LEFT, widthDp = 150)),
            ToolbarBar("one", "One row", short, geometry = ToolbarGeometry(
                floating = true, anchor = ToolbarAnchor.TOP_RIGHT, widthDp = 150)),
        ))
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    ToolbarWorkspacePreview(workspace = bars, look = ToolbarLook(floating = true),
                        selectedId = "two", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%", titleLabel = "Document",
                        selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, _ -> })
                }
            }
        }
        rule.waitForIdle()
        val tallTop = rule.onNodeWithTag("preview-content-two", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().top.value
        val shortTop = rule.onNodeWithTag("preview-content-one", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().top.value
        assertTrue("One-row floating bar drifts below its top-aligned peer: $tallTop vs $shortTop",
            kotlin.math.abs(tallTop - shortTop) <= 2f)
    }

    @Test fun crossEdgePreviewDragTracksPointerWhileStillPressedOnRealScreen() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        val first = ToolbarBar("moving", "Move", layout, geometry = ToolbarGeometry(
            floating = true, anchor = ToolbarAnchor.TOP_LEFT, widthDp = 160, heightDp = 56))
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(first)))
        var movedTo: ToolbarAnchor? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().testTag("cross-edge-preview-stage")) {
                    ToolbarWorkspacePreview(workspace = workspace, look = ToolbarLook(floating = true),
                        selectedId = "moving", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%", titleLabel = "Document",
                        selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, anchor ->
                            movedTo = anchor
                            workspace = workspace.replaceBar(first.copy(geometry = first.geometry.movedTo(anchor, true)))
                        }, onReorderBar = { _, _ -> })
                }
            }
        }
        rule.waitForIdle()
        val source = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val stage = rule.onNodeWithTag("cross-edge-preview-stage", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val start = source.fetchSemanticsNode().boundsInRoot.center
        val delta = Offset(stage.right - stage.width * 0.06f - start.x, stage.center.y - start.y)
        val step = delta / 12f
        source.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(step)
            advanceEventTime(32)
        }
        rule.waitForIdle()
        var previous = source.fetchSemanticsNode().boundsInRoot.center
        repeat(11) { index ->
            source.performTouchInput { moveBy(step); advanceEventTime(32) }
            rule.waitForIdle()
            val now = source.fetchSemanticsNode().boundsInRoot.center
            assertTrue("Cross-edge preview x went backward on step $index: $previous -> $now",
                now.x >= previous.x - 8f)
            assertTrue("Cross-edge preview y went backward on step $index: $previous -> $now",
                now.y >= previous.y - 8f)
            assertTrue("Cross-edge preview lost pointer during step $index: $now",
                kotlin.math.abs(now.x - start.x - step.x * (index + 2)) < 36f &&
                kotlin.math.abs(now.y - start.y - step.y * (index + 2)) < 36f)
            previous = now
        }
        source.performTouchInput { up() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(ToolbarAnchor.CENTER_RIGHT, movedTo) }
        val settled = source.fetchSemanticsNode().boundsInRoot.center
        assertTrue("Dropped bar did not settle on the right edge: $settled vs $stage",
            settled.x > stage.center.x)
    }

    @Test fun movingFloatingBarFromTopToRightSnapsWithoutReturningToPreviousSlot() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        val initial = ToolbarBar("moving", "Move", layout, geometry = ToolbarGeometry(
            floating = true, anchor = ToolbarAnchor.TOP_LEFT, widthDp = 160, heightDp = 56))
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(initial)))
        var droppedAnchor: ToolbarAnchor? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().testTag("move-preview-stage")) {
                    ToolbarWorkspacePreview(workspace = workspace, look = ToolbarLook(floating = true),
                        selectedId = "moving", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%", titleLabel = "Document",
                        selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, anchor -> droppedAnchor = anchor },
                        onReorderBar = { _, _ -> })
                }
            }
        }
        rule.waitForIdle()
        val source = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val stage = rule.onNodeWithTag("move-preview-stage", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val start = source.fetchSemanticsNode().boundsInRoot.center
        val delta = Offset(stage.right - stage.width * 0.06f - start.x, stage.center.y - start.y)
        source.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(delta)
            advanceEventTime(48)
            up()
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(ToolbarAnchor.CENTER_RIGHT, droppedAnchor) }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            workspace = workspace.replaceBar(initial.copy(geometry =
                initial.geometry.movedTo(droppedAnchor!!, floating = true)))
        }
        repeat(4) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val x = source.fetchSemanticsNode().boundsInRoot.center.x
            assertTrue("Cross-edge preview move flickered toward the old top slot at frame $frame: x=$x, center=${stage.center.x}",
                x > stage.center.x + stage.width * 0.1f)
        }
        rule.mainClock.autoAdvance = true
    }

    @Test fun horizontalTitleIsBoundedAndPreservesNoteVersusCanvasClickBehavior() {
        val name = "Long document name which must never consume a whole toolbar row"
        var editable by mutableStateOf(true)
        var clicks = 0
        rule.setContent {
            MaterialTheme {
                HorizontalToolbarTitle(name, onClick = if (editable) ({ clicks++ }) else null)
            }
        }
        val node = rule.onNodeWithTag("toolbar-horizontal-title", useUnmergedTree = true)
        node.assertIsDisplayed()
        fun assertBounded() {
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("horizontal title overflows: $bounds", (bounds.right - bounds.left).value <= 161f)
        }
        assertBounded()
        node.performClick()
        rule.runOnIdle { assertEquals(1, clicks); editable = false }
        rule.waitForIdle()
        assertBounded()
        val semantics = node.fetchSemanticsNode().config
        assertFalse("read-only canvas title should not rename",
            semantics.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
    }

    @Test fun verticalTitleIsVisibleReadableToAccessibilityAndClickable() {
        var clicked = 0
        val name = "Documento com nome longo — Pesquisa de geometria"
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalBar provides barMetrics(ToolbarSize.REGULAR).copy(vertical = true)) {
                    VerticalToolbarTitle(name, onClick = { clicked++ })
                }
            }
        }
        rule.onNodeWithContentDescription(name).assertExists().assertIsDisplayed()
        rule.onNodeWithContentDescription(name).performClick()
        rule.runOnIdle { assertEquals(1, clicked) }
    }

    @Test fun verticalCanvasTitleIsReadableButHasNoUnexpectedNavigationAction() {
        val title = "My infinite drawing"
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalBar provides barMetrics(ToolbarSize.REGULAR).copy(vertical = true)) {
                    VerticalToolbarTitle(title)
                }
            }
        }
        rule.onNodeWithContentDescription(title).assertExists().assertIsDisplayed()
        val semantics = rule.onNodeWithContentDescription(title).fetchSemanticsNode().config
        assertFalse(semantics.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
    }

    @Test fun titleEntryRendersWithinVerticalPreviewForNoteAndCanvas() {
        var canvas by mutableStateOf(false)
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.TITLE)))))
        val bar = ToolbarBar("main", "Title test", layout, geometry = ToolbarGeometry(
            anchor = ToolbarAnchor.CENTER_RIGHT, dockEdge = ToolbarDockEdge.RIGHT,
            widthDp = 60, heightDp = 360,
        ))
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    ToolbarWorkspacePreview(
                        workspace = ToolbarWorkspace(listOf(bar)), look = ToolbarLook(),
                        selectedId = "main", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%",
                        titleLabel = if (canvas) "XCanvas title" else "XNote title",
                        selectedEntry = null,
                        onSelectBar = {}, onSelectEntry = { _, _, _ -> }, onMoveBar = { _, _ -> },
                    )
                }
            }
        }
        rule.waitForIdle()
        for (name in listOf("XNote title", "XCanvas title")) {
            if (name == "XCanvas title") rule.runOnIdle { canvas = true }
            rule.waitForIdle()
            val titleNode = rule.onNodeWithContentDescription(name)
            titleNode.assertExists().assertIsDisplayed()
            val title = titleNode.getUnclippedBoundsInRoot()
            val barBounds = rule.onNodeWithTag("preview-bar-main", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue("Vertical title exceeds toolbar thickness: $title vs $barBounds",
                (title.right - title.left).value <= (barBounds.right - barBounds.left).value + 2f)
        }
    }

    @Test fun movingAcrossEmptyPreviewSpaceKeepsLastReorderCandidateStable() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        fun bar(id: String) = ToolbarBar(id, id, layout,
            geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP,
                dockAlignment = ToolbarDockAlignment.CENTER, widthDp = 150, heightDp = 60))
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(bar("main"), bar("moving"), bar("target"))))
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    ToolbarWorkspacePreview(workspace = workspace, look = ToolbarLook(),
                        selectedId = "moving", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%", titleLabel = "Document",
                        selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, _ -> }, onReorderBar = { id, other ->
                            workspace = workspace.reorderVisualBar(id, other)
                        })
                }
            }
        }
        rule.waitForIdle()
        val source = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val target = rule.onNodeWithTag("preview-bar-target", useUnmergedTree = true)
        val initialSource = source.fetchSemanticsNode().boundsInRoot.center
        val dragToTarget = target.fetchSemanticsNode().boundsInRoot.center - initialSource
        source.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(dragToTarget)
            advanceEventTime(48)
        }
        rule.waitForIdle()
        val highlighted = rule.onNodeWithTag("preview-reorder-target-target", useUnmergedTree = true)
        highlighted.assertExists()
        val targetX = target.fetchSemanticsNode().boundsInRoot.center.x
        // Cross a region which contains no candidate; only peers should move and
        // their prospective slot must not spring back to the original order.
        source.performTouchInput { moveBy(Offset(0f, 160f)); advanceEventTime(48) }
        rule.waitForIdle()
        highlighted.assertExists()
        val afterGapX = target.fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("Peer returned toward old slot while pointer crossed an empty region: $targetX -> $afterGapX",
            kotlin.math.abs(afterGapX - targetX) < 5f)
        val sourceGap = source.fetchSemanticsNode().boundsInRoot.center
        assertTrue("Dragged source changed its horizontal position while moving vertically",
            kotlin.math.abs(sourceGap.x - initialSource.x - dragToTarget.x) < 10f)
        // Return to the target and commit using the original frozen hit-box.
        source.performTouchInput { moveBy(Offset(0f, -160f)); advanceEventTime(48); up() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("main", "target", "moving"),
                workspace.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
    }

    @Test fun incrementalDragEachFrameNeverReturnsToPriorPosition() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        fun bar(id: String) = ToolbarBar(id, id, layout,
            geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP,
                dockAlignment = ToolbarDockAlignment.CENTER, widthDp = 150, heightDp = 60))
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(bar("main"), bar("moving"), bar("target"))))
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    ToolbarWorkspacePreview(workspace = workspace, look = ToolbarLook(),
                        selectedId = "moving", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%", titleLabel = "Example",
                        selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, _ -> }, onReorderBar = { from, to ->
                            workspace = workspace.reorderVisualBar(from, to)
                        })
                }
            }
        }
        rule.waitForIdle()
        val surface = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        // Inject on a STATIONARY preview root, never the moving source. A moving
        // injection target can change the local coordinate origin between events,
        // creating an artificial reverse pointer delta on real hardware.
        val stage = rule.onNodeWithTag("toolbar-preview-stage", useUnmergedTree = true)
        val stageOrigin = stage.fetchSemanticsNode().boundsInRoot.topLeft
        val before = surface.fetchSemanticsNode().boundsInRoot
        val target = rule.onNodeWithTag("preview-bar-target", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val total = target.center.x - before.center.x
        val step = total / 10f
        stage.performTouchInput {
            down(before.center - stageOrigin)
            advanceEventTime(780)
            moveBy(Offset(step, 0f))
            advanceEventTime(32)
        }
        rule.waitForIdle()
        var last = surface.fetchSemanticsNode().boundsInRoot.center.x
        repeat(9) { index ->
            stage.performTouchInput { moveBy(Offset(step, 0f)); advanceEventTime(32) }
            rule.waitForIdle()
            val current = surface.fetchSemanticsNode().boundsInRoot.center.x
            assertTrue("Bar reversed between frames $index: $last -> $current", current >= last - 5f)
            assertTrue("Bar fell behind pointer on step ${index+1}: $current vs ${before.center.x + step*(index+2)}",
                kotlin.math.abs(current - (before.center.x + step*(index+2))) <= 30f)
            last = current
        }
        stage.performTouchInput { up() }
    }

    @Test fun dragMultipleSmallStepsShouldTrackPointerWithoutOscillations() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        fun bar(id: String) = ToolbarBar(id, id, layout,
            geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP,
                dockAlignment = ToolbarDockAlignment.CENTER, widthDp = 150, heightDp = 60))
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(bar("main"), bar("moving"), bar("target"))))
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    ToolbarWorkspacePreview(workspace = workspace, look = ToolbarLook(),
                        selectedId = "moving", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%", titleLabel = "Example",
                        selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, _ -> }, onReorderBar = { from, to ->
                            workspace = workspace.reorderVisualBar(from, to)
                        })
                }
            }
        }
        rule.waitForIdle()
        val surface = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val before = surface.fetchSemanticsNode().boundsInRoot
        val target = rule.onNodeWithTag("preview-bar-target", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val total = target.center - before.center
        surface.performTouchInput {
            down(center)
            advanceEventTime(780)
            repeat(16) {
                moveBy(total / 16f)
                advanceEventTime(24)
            }
        }
        rule.waitForIdle()
        val after = surface.fetchSemanticsNode().boundsInRoot
        assertTrue("Repeated drag segments lost cursor: start=$before end=$after expected=$total",
            kotlin.math.abs(after.center.x - before.center.x - total.x) <= 24f)
        surface.performTouchInput { up() }
    }

    @Test fun deferredWorkspaceCommitDoesNotFlashBarBackToOldSlot() {
        // A persistence callback may only publish the updated workspace on the next
        // recomposition or after an async preference write. The preview must render
        // the proposed order *before* that acknowledgement arrives.
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        fun bar(id: String) = ToolbarBar(id, id, layout,
            geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP,
                dockAlignment = ToolbarDockAlignment.CENTER, widthDp = 150, heightDp = 60))
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(bar("main"), bar("moving"), bar("target"))))
        var pending: Pair<String, String>? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    ToolbarWorkspacePreview(workspace = workspace, look = ToolbarLook(),
                        selectedId = "moving", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%", titleLabel = "Document",
                        selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, _ -> }, onReorderBar = { from, to -> pending = from to to })
                }
            }
        }
        rule.waitForIdle()
        val source = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val initialX = source.fetchSemanticsNode().boundsInRoot.center.x
        val targetBounds = rule.onNodeWithTag("preview-bar-target", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val delta = targetBounds.center - source.fetchSemanticsNode().boundsInRoot.center
        source.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(delta)
            advanceEventTime(48)
            up()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("moving" to "target", pending)
            // Simulate an external persistence layer not yet acknowledging the reorder.
            assertNull(workspace.bars[1].geometry.visualOrder)
        }
        repeat(4) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val currentX = source.fetchSemanticsNode().boundsInRoot.center.x
            assertTrue("frame $frame flashed to origin; old=$initialX new=$currentX",
                currentX > initialX + delta.x * 0.4f)
        }
        rule.runOnIdle { workspace = workspace.reorderVisualBar("moving", "target") }
        rule.waitForIdle()
        val acknowledgedX = source.fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("commit acknowledgement must not flash to old slot",
            acknowledgedX > initialX + delta.x * 0.4f)
    }

    @Test fun draggedBarTracksPointerAcrossLiveReorderPreviewWithoutJump() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        fun bar(id: String) = ToolbarBar(id, id, layout,
            geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP,
                dockAlignment = ToolbarDockAlignment.CENTER, widthDp = 145, heightDp = 60))
        var workspace by mutableStateOf(ToolbarWorkspace(listOf(bar("main"), bar("moving"), bar("target"))))
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    ToolbarWorkspacePreview(workspace = workspace, look = ToolbarLook(),
                        selectedId = "moving", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%", titleLabel = "Document",
                        selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, _ -> }, onReorderBar = { id, other ->
                            workspace = workspace.reorderVisualBar(id, other)
                        })
                }
            }
        }
        rule.waitForIdle()
        val source = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val before = source.fetchSemanticsNode().boundsInRoot
        val target = rule.onNodeWithTag("preview-bar-target", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val displacement = target.center - before.center
        source.performTouchInput {
            down(center)
            advanceEventTime(780)
            // One continuous dispatcher stream; split calls can inject an unrelated
            // previousPosition when the view itself has moved between calls.
            moveBy(displacement)
            advanceEventTime(48)
        }
        rule.waitForIdle()
        val hovered = source.fetchSemanticsNode().boundsInRoot
        assertTrue("Source jumped while other bars reordered: before=$before hovered=$hovered",
            kotlin.math.abs(hovered.center.x - before.center.x - displacement.x) <= 24f)
        source.performTouchInput { up() }
        // Check successive Compose frames, not only the final saved order. A single
        // flash to the original slot was invisible to the old end-state tests.
        repeat(4) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val bounds = source.fetchSemanticsNode().boundsInRoot
            assertTrue("Source flashed at original slot on frame $frame: before=$before now=$bounds",
                bounds.center.x > before.center.x + displacement.x * 0.40f)
        }
        rule.waitForIdle()
        val released = source.fetchSemanticsNode().boundsInRoot
        assertTrue("Source snapped back toward original slot: before=$before released=$released",
            released.center.x > before.center.x + displacement.x * 0.40f)
        rule.runOnIdle {
            assertEquals("main", workspace.bars.first().id)
            assertEquals(listOf("main", "target", "moving"),
                workspace.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
    }

    @Test fun sameLaneFloatingBarsCanBeReorderedByPreviewGesture() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        fun bar(id: String) = ToolbarBar(id, id, layout,
            geometry = ToolbarGeometry(floating = true, anchor = ToolbarAnchor.TOP_CENTER,
                dockAlignment = ToolbarDockAlignment.CENTER, widthDp = 140, heightDp = 56))
        var current by mutableStateOf(ToolbarWorkspace(listOf(bar("primary"), bar("second"), bar("third"))))
        var completed: Pair<String, String>? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    ToolbarWorkspacePreview(workspace = current, look = ToolbarLook(floating = true),
                        selectedId = "second", colors = emptyList(), activeColorIndex = 0,
                        pageNumber = 1, pageCount = 1, zoomLabel = "100%", titleLabel = "Example",
                        selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, _ -> },
                        onReorderBar = { id, target ->
                            completed = id to target
                            current = current.reorderVisualBar(id, target)
                        })
                }
            }
        }
        rule.waitForIdle()
        val barSurface = rule.onNodeWithTag("preview-bar-second", useUnmergedTree = true)
        barSurface.assertIsDisplayed()
        rule.onNodeWithTag("preview-reorder-handle-second", useUnmergedTree = true).assertDoesNotExist()
        val a = barSurface.fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag("preview-bar-third", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        barSurface.performTouchInput {
            down(Offset(center.x, 4f))
            advanceEventTime(750)
            val delta = b.center - Offset(a.center.x, a.top + 4f)
            repeat(12) { moveBy(delta / 12f); advanceEventTime(16) }
        }
        rule.waitForIdle()
        val candidate = rule.onNodeWithTag("preview-reorder-target-third", useUnmergedTree = true)
        candidate.assertExists()
        // The previous implementation reset the temporary order in empty space.
        // That made the floating peers bounce back and forward during a gesture.
        val peer = rule.onNodeWithTag("preview-bar-third", useUnmergedTree = true)
        val previewX = peer.fetchSemanticsNode().boundsInRoot.center.x
        repeat(2) {
            barSurface.performTouchInput { moveBy(Offset(0f, 145f)); advanceEventTime(48) }
            rule.waitForIdle()
            candidate.assertExists()
            assertTrue("Floating peer jumped back to its initial position",
                kotlin.math.abs(peer.fetchSemanticsNode().boundsInRoot.center.x - previewX) < 5f)
            barSurface.performTouchInput { moveBy(Offset(0f, -145f)); advanceEventTime(48) }
            rule.waitForIdle()
            candidate.assertExists()
        }
        barSurface.performTouchInput { up() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("second" to "third", completed)
            assertEquals("primary", current.bars.first().id)
            assertEquals(listOf("primary", "third", "second"),
                current.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
    }

    @Test fun reorderHandlePreviewsThenPersistsOrderWithoutReplacingMainBar() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        fun bar(id: String) = ToolbarBar(id, id, layout,
            geometry = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP,
                dockAlignment = ToolbarDockAlignment.CENTER, widthDp = 160, heightDp = 58))
        var current by mutableStateOf(ToolbarWorkspace(listOf(bar("primary"), bar("second"), bar("third"))))
        var callback: Pair<String, String>? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    ToolbarWorkspacePreview(
                        workspace = current,
                        look = ToolbarLook(),
                        selectedId = "second",
                        colors = emptyList(),
                        activeColorIndex = 0,
                        pageNumber = 1,
                        pageCount = 1,
                        zoomLabel = "100%",
                        titleLabel = "Test note",
                        selectedEntry = null,
                        onSelectBar = {},
                        onSelectEntry = { _, _, _ -> },
                        onMoveBar = { _, _ -> },
                        onReorderBar = { source, target ->
                            callback = source to target
                            current = current.reorderVisualBar(source, target)
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        val barSurface = rule.onNodeWithTag("preview-bar-second", useUnmergedTree = true)
        barSurface.assertIsDisplayed()
        rule.onNodeWithTag("preview-reorder-handle-second", useUnmergedTree = true).assertDoesNotExist()
        val gripRect = barSurface.fetchSemanticsNode().boundsInRoot
        val targetRect = rule.onNodeWithTag("preview-bar-third", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val start = Offset(gripRect.center.x, gripRect.top + 4f)
        val delta = targetRect.center - start
        barSurface.performTouchInput {
            down(Offset(center.x, 4f))
            advanceEventTime(750)
            repeat(12) { moveBy(delta / 12f); advanceEventTime(16) }
            up()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("second" to "third", callback)
            assertEquals("primary", current.bars.first().id)
            assertEquals(listOf("primary", "third", "second"),
                current.bars.sortedBy { it.geometry.visualOrder }.map { it.id })
        }
    }
}
