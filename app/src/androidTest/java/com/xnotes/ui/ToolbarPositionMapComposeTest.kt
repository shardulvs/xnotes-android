package com.xnotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.settings.ToolbarLook
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ToolbarPositionMapComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun positionControlsFollowSideAlignmentWithoutStaleSelection() {
        var edge by mutableStateOf(ToolbarDockEdge.LEFT)
        var alignment by mutableStateOf(ToolbarDockAlignment.START)
        rule.setContent {
            MaterialTheme {
                ToolbarPositionMap(edge, alignment) { nextEdge, nextAlignment ->
                    edge = nextEdge
                    alignment = nextAlignment
                }
            }
        }
        fun select(e: ToolbarDockEdge, a: ToolbarDockAlignment) =
            rule.onNodeWithTag("toolbar-position-${e.name}-${a.name}", useUnmergedTree = true)
        select(ToolbarDockEdge.LEFT, ToolbarDockAlignment.START).assertIsSelected()
        select(ToolbarDockEdge.LEFT, ToolbarDockAlignment.CENTER).performClick().assertIsSelected()
        rule.runOnIdle {
            assertEquals(ToolbarDockEdge.LEFT, edge)
            assertEquals(ToolbarDockAlignment.CENTER, alignment)
        }
        select(ToolbarDockEdge.RIGHT, ToolbarDockAlignment.END).performClick().assertIsSelected()
        select(ToolbarDockEdge.TOP, ToolbarDockAlignment.CENTER).performClick().assertIsSelected()
    }

    @Test fun barEditorAppliesFloatingPositionsWithOneConsistentGeometry() {
        var bar by mutableStateOf(ToolbarBar("moving", "Move", ToolbarLayout.DEFAULT,
            geometry = ToolbarGeometry(floating = true, anchor = ToolbarAnchor.CENTER_LEFT,
                dockAlignment = ToolbarDockAlignment.START)))
        rule.setContent {
            MaterialTheme {
                ToolbarBarSettings(
                    currentBar = bar, barIndex = 1, barCount = 2,
                    look = ToolbarLook(floating = true),
                    onUpdateBar = { transform -> bar = transform(bar) },
                    onConfigureDock = { geometry, _ -> bar = bar.copy(geometry = geometry) },
                )
            }
        }
        rule.onNodeWithTag("toolbar-position-LEFT-START", useUnmergedTree = true).assertIsSelected()
        rule.onNodeWithTag("toolbar-position-LEFT-CENTER", useUnmergedTree = true)
            .performClick().assertIsSelected()
        rule.runOnIdle {
            assertEquals(ToolbarAnchor.CENTER_LEFT, bar.geometry.anchor)
            assertEquals(ToolbarDockAlignment.CENTER, bar.geometry.dockAlignment)
        }
        rule.onNodeWithTag("toolbar-position-TOP-END", useUnmergedTree = true)
            .performClick().assertIsSelected()
        rule.runOnIdle {
            assertEquals(ToolbarAnchor.TOP_RIGHT, bar.geometry.anchor)
            assertEquals(ToolbarDockAlignment.END, bar.geometry.dockAlignment)
        }
    }
}
