package com.xnotes.ui

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDropPosition
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class ToolbarDockMovementRegressionTest {
    @get:Rule val rule = createComposeRule()

    private val initial = ToolbarWorkspace(listOf(ToolbarBar(
        "docked", "Docked", ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN))))),
        geometry = ToolbarGeometry(widthDp = 132, heightDp = 72),
    )))
    private val right = ToolbarDropPosition(ToolbarAnchor.CENTER_RIGHT, ToolbarDockAlignment.CENTER)

    @Test fun firstDockMoveDoesNotRecreateTheNativeEditor() {
        var workspace by mutableStateOf(initial)
        var look by mutableStateOf(ToolbarLook(floating = false))
        var mounts = 0
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides look) {
                    Column(Modifier.fillMaxSize()) {
                        ToolbarAround(
                            bar = { ToolbarFrame(null, workspace.bars.single().layout,
                                workspace.bars.single().geometry) {} },
                            onCover = { _, _, _, _ -> },
                            workspace = workspace, dockedContent = {},
                        ) {
                            AndroidView(
                                factory = { context -> View(context).also { mounts++ } },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        rule.runOnIdle {
            assertEquals(1, mounts)
            val moved = requireNotNull(toolbarProjectedMove(workspace, look, "docked", right))
            workspace = moved.first
            look = moved.second
        }
        rule.runOnIdle { assertEquals("The document surface must stay attached", 1, mounts) }
    }

    @Test fun previewDrawsTheNewDockBandOnlyAfterDrop() {
        var workspace by mutableStateOf(initial)
        var look by mutableStateOf(ToolbarLook(floating = false))
        rule.setContent {
            MaterialTheme {
                ToolbarWorkspacePreview(
                    workspace = workspace, look = look, selectedId = "docked",
                    colors = emptyList(), activeColorIndex = 0, pageNumber = 1,
                    pageCount = 1, zoomLabel = "100%", titleLabel = "Preview",
                    selectedEntry = null, onSelectBar = {}, onSelectEntry = { _, _, _ -> },
                    onReorderBar = { _, _ -> },
                    onMoveBar = { id, anchor, alignment ->
                        val moved = requireNotNull(toolbarProjectedMove(workspace, look, id,
                            ToolbarDropPosition(anchor, alignment)))
                        workspace = moved.first
                        look = moved.second
                    },
                )
            }
        }
        val stage = rule.onNodeWithTag("toolbar-preview-stage")
        val viewport = stage.fetchSemanticsNode().boundsInRoot
        val source = rule.onNodeWithTag("toolbar-drag-surface-docked", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - viewport.topLeft
        val destination = Offset(viewport.width - 8f, viewport.height / 2)
        val x = (viewport.width - 16f).toInt()
        val y = (viewport.height * 0.20f).toInt()
        fun backgroundPixel() = stage.captureToImage().toPixelMap()[x, y]

        val paper = backgroundPixel()
        stage.performTouchInput {
            down(source)
            advanceEventTime(850)
            moveBy(destination - source)
        }
        assertEquals("Hover exposed a dock band across the document", paper, backgroundPixel())
        stage.performTouchInput { up() }
        rule.runOnIdle { assertEquals(right.anchor, workspace.bars.single().geometry.anchor) }
        assertNotEquals("The committed dock must reserve its inset", paper, backgroundPixel())
    }
}
