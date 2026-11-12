package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                Box(Modifier.width(800.dp)) {
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

    @Test fun sameLaneFloatingBarsCanBeReorderedByPreviewGesture() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))
        fun bar(id: String) = ToolbarBar(id, id, layout,
            geometry = ToolbarGeometry(floating = true, anchor = ToolbarAnchor.TOP_CENTER,
                dockAlignment = ToolbarDockAlignment.CENTER, widthDp = 140, heightDp = 56))
        var current by mutableStateOf(ToolbarWorkspace(listOf(bar("primary"), bar("second"), bar("third"))))
        var completed: Pair<String, String>? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(800.dp)) {
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
            up()
        }
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
                Box(Modifier.width(850.dp)) {
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
