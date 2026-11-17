package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Renders concurrent live toolbar frames in a real Android Compose layout. */
class ToolbarMultiBarComposeTest {
    @get:Rule val rule = createComposeRule()
    private val compact = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN)))))

    private fun bar(id: String, edge: ToolbarDockEdge, length: Int = 1400, depth: Int = 800): ToolbarBar {
        val vertical = edge == ToolbarDockEdge.LEFT || edge == ToolbarDockEdge.RIGHT
        return ToolbarBar(id, id, compact, geometry = ToolbarGeometry(
            dockEdge = edge,
            anchor = when (edge) {
                ToolbarDockEdge.TOP -> ToolbarAnchor.TOP_CENTER
                ToolbarDockEdge.BOTTOM -> ToolbarAnchor.BOTTOM_CENTER
                ToolbarDockEdge.LEFT -> ToolbarAnchor.CENTER_LEFT
                ToolbarDockEdge.RIGHT -> ToolbarAnchor.CENTER_RIGHT
            },
            widthDp = if (vertical) depth else length,
            heightDp = if (vertical) length else depth,
        ))
    }
    private fun floating(id: String, anchor: ToolbarAnchor) =
        ToolbarBar(id, id, compact, geometry = ToolbarGeometry(
            anchor = anchor, floating = true, widthDp = 1400, heightDp = 800,
        ))

    @Test fun dockedOnlyFloatingOnlyMixedAndMovedBarsAreRenderedWithinStage() {
        val fourDocks = ToolbarWorkspace(listOf(
            bar("top", ToolbarDockEdge.TOP), bar("bottom", ToolbarDockEdge.BOTTOM),
            bar("left", ToolbarDockEdge.LEFT), bar("right", ToolbarDockEdge.RIGHT),
        ))
        val onlyFloats = ToolbarWorkspace(listOf(
            floating("a", ToolbarAnchor.TOP_LEFT), floating("b", ToolbarAnchor.BOTTOM_RIGHT),
            floating("c", ToolbarAnchor.CENTER_LEFT),
        ))
        val mixed = ToolbarWorkspace(listOf(
            bar("top", ToolbarDockEdge.TOP), bar("bottom", ToolbarDockEdge.BOTTOM),
            floating("left", ToolbarAnchor.CENTER_LEFT),
            floating("right", ToolbarAnchor.CENTER_RIGHT),
        ))
        var workspace by mutableStateOf(fourDocks)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides ToolbarLook()) {
                    Column(Modifier.size(600.dp, 320.dp).testTag("multibar-stage")) {
                        val primary = workspace.bars.first()
                        ToolbarAround(
                            bar = { TaggedToolbar(primary) },
                            onCover = { _, _, _, _ -> },
                            suppressBar = primary.geometry.floating,
                            barLayout = primary.layout,
                            barGeometry = primary.geometry,
                            workspace = workspace,
                            dockedContent = { TaggedToolbar(it) },
                            content = { legacyFloating ->
                                Box(Modifier.fillMaxSize()) {
                                    Text("Document")
                                    legacyFloating()
                                    WorkspaceFloatingBars(
                                        workspace = workspace,
                                        onMoveBar = { _, _, _ -> },
                                        primaryContent = { TaggedToolbar(primary) },
                                        content = { TaggedToolbar(it) },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        assertBars(fourDocks)
        rule.runOnIdle { workspace = onlyFloats }
        rule.waitForIdle()
        assertBars(onlyFloats)
        rule.runOnIdle { workspace = mixed }
        rule.waitForIdle()
        assertBars(mixed)
        rule.runOnIdle {
            val floatingLeft = workspace.bars.first { it.id == "left" }
            workspace = workspace.replaceBar(floatingLeft.copy(geometry =
                toolbarGeometryForDockSelection(floatingLeft, 2, ToolbarLook(), ToolbarDockEdge.RIGHT)))
        }
        rule.waitForIdle()
        assertBars(workspace)
    }

    @androidx.compose.runtime.Composable
    private fun TaggedToolbar(bar: ToolbarBar) {
        Box(Modifier.testTag("live-bar-${bar.id}")) {
            ToolbarFrame(armed = null, layout = bar.layout, geometry = bar.geometry) {
                Text("Tools ${bar.id}")
            }
        }
    }

    private fun assertBars(workspace: ToolbarWorkspace) {
        val stage = rule.onNodeWithTag("multibar-stage").getUnclippedBoundsInRoot()
        val frames = workspace.bars.map { bar ->
            rule.onNodeWithTag("live-bar-${bar.id}", useUnmergedTree = true).getUnclippedBoundsInRoot()
        }
        assertEquals(workspace.bars.size, frames.size)
        frames.forEach { r ->
            assertTrue("invisible toolbar: $r", (r.right - r.left).value > 1f && (r.bottom - r.top).value > 1f)
            assertTrue("off-screen toolbar: $r vs $stage", r.left >= stage.left - 2.dp &&
                r.top >= stage.top - 2.dp && r.right <= stage.right + 2.dp &&
                r.bottom <= stage.bottom + 2.dp)
        }
        for (i in frames.indices) for (j in i + 1 until frames.size) {
            val a = frames[i]; val b = frames[j]
            val intersects = a.left < b.right - 1.dp && b.left < a.right - 1.dp &&
                a.top < b.bottom - 1.dp && b.top < a.bottom - 1.dp
            assertTrue("overlapping live bars: $a and $b", !intersects)
        }
    }
}
