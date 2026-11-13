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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** Device-level tests: exercise actual Compose controls, not only the model's edge mapping. */
class ToolbarDockComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun geometryControlsKeepLengthAndThicknessLabelsWhenRotating() {
        var geometry by mutableStateOf(ToolbarGeometry(
            dockEdge = ToolbarDockEdge.RIGHT, anchor = ToolbarAnchor.CENTER_RIGHT,
            widthDp = 70, heightDp = 430,
        ))
        var vertical by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                ToolbarGeometryControls(geometry = geometry, vertical = vertical,
                    onChange = { geometry = it })
            }
        }
        // The Redmi Pad 2 reports pt-BR labels; never assert hard-coded English.
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        rule.onNodeWithText(resources.getString(R.string.toolbar_config_length)).assertExists()
        rule.onNodeWithText("430 dp").assertExists()
        rule.onNodeWithText(resources.getString(R.string.toolbar_config_thickness)).assertExists()
        rule.onNodeWithText("70 dp").assertExists()
        rule.runOnIdle {
            geometry = geometry.movedTo(ToolbarAnchor.TOP_CENTER, floating = false)
            vertical = false
        }
        rule.waitForIdle()
        // The localized labels must remain stable after rotation.
        rule.onNodeWithText(resources.getString(R.string.toolbar_config_length)).assertExists()
        rule.onNodeWithText("430 dp").assertExists()
        rule.onNodeWithText(resources.getString(R.string.toolbar_config_thickness)).assertExists()
        rule.onNodeWithText("70 dp").assertExists()
    }

    @Test fun oversizedRightToTopDockRendersInsideStageInsteadOfDisappearing() {
        var geometry by mutableStateOf(ToolbarGeometry(
            anchor = ToolbarAnchor.CENTER_RIGHT, dockEdge = ToolbarDockEdge.RIGHT,
            widthDp = 980, heightDp = 260,
        ))
        var look by mutableStateOf(ToolbarLook(position = ToolbarPosition.RIGHT))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides look) {
                    Column(Modifier.size(600.dp, 400.dp).testTag("oversize-stage")) {
                        val main = ToolbarBar("main", "Main", ToolbarLayout.DEFAULT, geometry = geometry)
                        ToolbarAround(
                            bar = { ToolbarFrame(armed = null, layout = main.layout,
                                geometry = main.geometry) { Text("Toolbar tools") } },
                            onCover = { _, _, _, _ -> },
                            barLayout = main.layout, barGeometry = main.geometry,
                            workspace = ToolbarWorkspace(listOf(main)),
                            dockedContent = { bar -> ToolbarFrame(armed = null,
                                layout = bar.layout, geometry = bar.geometry) { Text("Tools") } },
                            content = { floating -> Box(Modifier.fillMaxSize()) {
                                Text("Canvas")
                                floating()
                            } },
                        )
                    }
                }
            }
        }
        rule.runOnIdle {
            val main = ToolbarBar("main", "Main", ToolbarLayout.DEFAULT, geometry = geometry)
            geometry = toolbarGeometryForDockSelection(main, 0, look, ToolbarDockEdge.TOP)
            look = look.copy(position = ToolbarPosition.TOP)
        }
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag("toolbar-frame").getUnclippedBoundsInRoot()
        val stage = rule.onNodeWithTag("oversize-stage").getUnclippedBoundsInRoot()
        assertTrue("clipped toolbar must remain visible: $bounds", (bounds.bottom - bounds.top).value > 0f)
        assertTrue("bar moved away from top: $bounds vs $stage", (bounds.top - stage.top).value < 2f)
        assertTrue("toolbar exceeds screen after rotation: $bounds vs $stage", bounds.bottom <= stage.bottom + 1.dp)
    }

    @Test fun primaryDockDirectionButtonsUpdateActualEdgeAndRotateBothDimensions() {
        var geometry by mutableStateOf(ToolbarGeometry(
            anchor = ToolbarAnchor.TOP_CENTER, dockEdge = ToolbarDockEdge.TOP,
            widthDp = 430, heightDp = 70, paddingDp = 12,
        ))
        var look by mutableStateOf(ToolbarLook(position = ToolbarPosition.TOP))
        rule.setContent {
            MaterialTheme {
                ToolbarBarSettings(
                    currentBar = ToolbarBar("main", "Main", ToolbarLayout.DEFAULT, geometry = geometry),
                    barIndex = 0, barCount = 1, look = look,
                    onUpdateBar = { geometry = it(ToolbarBar("main", "Main", ToolbarLayout.DEFAULT, geometry = geometry)).geometry },
                    onConfigureDock = { next, nextLook -> geometry = next; look = nextLook },

                )
            }
        }
        rule.onNodeWithContentDescription("center left").performClick()
        rule.runOnIdle {
            assertEquals(ToolbarDockEdge.LEFT, geometry.dockEdge)
            assertEquals(70, geometry.widthDp)
            assertEquals(430, geometry.heightDp)
            assertEquals(ToolbarPosition.LEFT, look.position)
        }
        rule.onNodeWithContentDescription("bottom center").performClick()
        rule.runOnIdle {
            assertEquals(ToolbarDockEdge.BOTTOM, geometry.dockEdge)
            assertEquals(430, geometry.widthDp)
            assertEquals(70, geometry.heightDp)
            assertEquals(12, geometry.paddingDp)
        }
    }

    @Test fun secondaryDockGridDoesNotCreateLegacy200DpGaps() {
        var geometry by mutableStateOf(ToolbarGeometry(
            anchor = ToolbarAnchor.TOP_CENTER, dockEdge = ToolbarDockEdge.TOP,
            widthDp = 330, heightDp = 62,
        ))
        rule.setContent {
            MaterialTheme {
                ToolbarBarSettings(
                    currentBar = ToolbarBar("nav", "Navigation", ToolbarLayout.DEFAULT, geometry = geometry),
                    barIndex = 1, barCount = 2, look = ToolbarLook(),
                    onUpdateBar = { geometry = it(ToolbarBar("nav", "Navigation", ToolbarLayout.DEFAULT, geometry = geometry)).geometry },
                    onConfigureDock = { next, _ -> geometry = next },

                )
            }
        }
        rule.onNodeWithContentDescription("center left").performClick()
        rule.runOnIdle {
            assertEquals(ToolbarDockEdge.LEFT, geometry.dockEdge)
            assertEquals(62, geometry.widthDp)
            assertEquals(330, geometry.heightDp)
        }
    }

    @Test fun bothEditorWorkspacePathsKeepAllFourDocksAtTheirScreenEdges() {
        val initial = ToolbarGeometry(dockEdge = ToolbarDockEdge.TOP,
            anchor = ToolbarAnchor.TOP_CENTER, widthDp = 230, heightDp = 64, paddingDp = 8)
        var layout by mutableStateOf(ToolbarLayout.DEFAULT)
        var geometry by mutableStateOf(initial)
        var look by mutableStateOf(ToolbarLook(position = ToolbarPosition.TOP))
        var stageWidth by mutableStateOf(560)
        var stageHeight by mutableStateOf(400)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides look) {
                    Column(Modifier.size(stageWidth.dp, stageHeight.dp).testTag("toolbar-stage")) {
                        val main = ToolbarBar("main", "Main", layout, geometry = geometry)
                        ToolbarAround(
                            bar = { ToolbarFrame(armed = null, layout = main.layout,
                                geometry = main.geometry) { Text("Main tools") } },
                            onCover = { _, _, _, _ -> },
                            barLayout = main.layout, barGeometry = main.geometry,
                            workspace = ToolbarWorkspace(listOf(main)),
                            dockedContent = { bar -> ToolbarFrame(armed = null,
                                layout = bar.layout, geometry = bar.geometry) { Text("Other tools") } },
                            content = { floating -> Box(Modifier.fillMaxSize()) {
                                Text("Document canvas")
                                floating()
                            } },
                        )
                    }
                }
            }
        }
        val sizes = listOf(560 to 400, 600 to 270) // 7:5 and 20:9
        val directions = listOf(
            ToolbarDockEdge.TOP to ToolbarAnchor.TOP_CENTER,
            ToolbarDockEdge.LEFT to ToolbarAnchor.CENTER_LEFT,
            ToolbarDockEdge.RIGHT to ToolbarAnchor.CENTER_RIGHT,
            ToolbarDockEdge.BOTTOM to ToolbarAnchor.BOTTOM_CENTER,
        )
        for (editor in listOf(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)) {
            for ((width, height) in sizes) for ((edge, anchor) in directions) {
                rule.runOnIdle {
                    layout = editor
                    stageWidth = width
                    stageHeight = height
                    geometry = initial.movedTo(anchor, floating = false)
                    look = ToolbarLook(position = edge.toolbarPosition())
                }
                rule.waitForIdle()
                val stage = rule.onNodeWithTag("toolbar-stage").getUnclippedBoundsInRoot()
                val bar = rule.onNodeWithTag("toolbar-frame").getUnclippedBoundsInRoot()
                val tolerance = 1.1f
                when (edge) {
                    ToolbarDockEdge.TOP -> assertTrue("Top dock: $bar versus $stage",
                        kotlin.math.abs((bar.top - stage.top).value) <= tolerance)
                    ToolbarDockEdge.BOTTOM -> assertTrue("Bottom dock: $bar versus $stage",
                        kotlin.math.abs((bar.bottom - stage.bottom).value) <= tolerance)
                    ToolbarDockEdge.LEFT -> assertTrue("Left dock: $bar versus $stage",
                        kotlin.math.abs((bar.left - stage.left).value) <= tolerance)
                    ToolbarDockEdge.RIGHT -> assertTrue("Right dock: $bar versus $stage",
                        kotlin.math.abs((bar.right - stage.right).value) <= tolerance)
                }
                val thickness = if (edge in listOf(ToolbarDockEdge.LEFT, ToolbarDockEdge.RIGHT))
                    (bar.right - bar.left).value else (bar.bottom - bar.top).value
                assertTrue("Unwanted gap/thickness: $thickness at $edge", thickness in 60f..175f)
            }
        }
    }

    @Test fun giantFixedSideDockFitsBetweenTopAndBottomBarsOnShortScreens() {
        val tools = ToolbarLayout.DEFAULT.copy(sections = ToolbarLayout.DEFAULT.sections.take(1))
        val main = ToolbarBar("main", "Main", tools, geometry = ToolbarGeometry(
            dockEdge = ToolbarDockEdge.LEFT, anchor = ToolbarAnchor.CENTER_LEFT,
            widthDp = 72, heightDp = 1200))
        val top = ToolbarBar("top", "Top", tools, geometry = ToolbarGeometry(
            dockEdge = ToolbarDockEdge.TOP, heightDp = 64))
        val bottom = ToolbarBar("bottom", "Bottom", tools, geometry = ToolbarGeometry(
            dockEdge = ToolbarDockEdge.BOTTOM, heightDp = 64))
        val workspace = ToolbarWorkspace(listOf(main, top, bottom))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides ToolbarLook(position = ToolbarPosition.LEFT)) {
                    Column(Modifier.size(600.dp, 270.dp).testTag("toolbar-stage")) {
                        ToolbarAround(
                            bar = { ToolbarFrame(armed = null, layout = main.layout,
                                geometry = main.geometry) { Text("Main tools") } },
                            onCover = { _, _, _, _ -> }, workspace = workspace,
                            barLayout = main.layout, barGeometry = main.geometry,
                            dockedContent = { bar -> ToolbarFrame(armed = null, layout = bar.layout,
                                geometry = bar.geometry) { Text("Other tools") } },
                            content = { floating -> Box(Modifier.fillMaxSize()) {
                                Text("Document canvas")
                                floating()
                            } },
                        )
                    }
                }
            }
        }
        val stage = rule.onNodeWithTag("toolbar-stage").getUnclippedBoundsInRoot()
        val frames = rule.onAllNodesWithTag("toolbar-frame")
        val rects = (0 until 3).map { frames[it].getUnclippedBoundsInRoot() }
        val left = rects.single { kotlin.math.abs((it.left - stage.left).value) < 2f &&
            (it.right - it.left).value < 120f }
        val horizontal = rects.filter { (it.right - it.left).value >= 500f }.sortedBy { it.top.value }
        assertEquals(2, horizontal.size)
        assertTrue("left dock starts too high: $left vs $horizontal", left.top >= horizontal[0].bottom)
        assertTrue("left dock overruns bottom: $left vs $horizontal", left.bottom <= horizontal[1].top)
        assertTrue("left dock left empty space", kotlin.math.abs(
            (left.bottom - left.top - (horizontal[1].top - horizontal[0].bottom)).value) <= 2f)
    }

    @Test fun liveToolbarFrameUsesTheSelectedAxisForItsMeasuredBounds() {
        var look by mutableStateOf(ToolbarLook(position = ToolbarPosition.TOP))
        var geometry by mutableStateOf(ToolbarGeometry(widthDp = 430, heightDp = 70))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalToolbarLook provides look) {
                    Box(Modifier.size(680.dp, 500.dp).testTag("sizing-stage")) {
                        ToolbarFrame(armed = null, layout = ToolbarLayout.DEFAULT,
                            geometry = geometry) { Text("Actual toolbar") }
                    }
                }
            }
        }
        val horizontal = rule.onNodeWithTag("toolbar-frame").getUnclippedBoundsInRoot()
        assertTrue("horizontal width: ${horizontal.right - horizontal.left}", (horizontal.right - horizontal.left).value in 420f..440f)
        assertTrue("horizontal depth: ${horizontal.bottom - horizontal.top}", (horizontal.bottom - horizontal.top).value >= 65f)
        rule.runOnIdle {
            geometry = geometry.movedTo(ToolbarAnchor.CENTER_LEFT, floating = false)
            look = look.copy(position = ToolbarPosition.LEFT)
        }
        rule.waitForIdle()
        val vertical = rule.onNodeWithTag("toolbar-frame").getUnclippedBoundsInRoot()
        assertTrue("vertical depth: ${vertical.right - vertical.left}", (vertical.right - vertical.left).value in 65f..85f)
        // On a short device (e.g. 1600x720 at 320 dpi = 360dp high), Compose
        // correctly clamps the requested 430dp length to the stage's measured height.
        // Comparing against an unconditional 430dp would flag correct clipping as a bug.
        val stage = rule.onNodeWithTag("sizing-stage").getUnclippedBoundsInRoot()
        val availableHeight = (stage.bottom - stage.top).value
        val expectedLength = minOf(430f, availableHeight)
        assertTrue("vertical length: ${vertical.bottom - vertical.top} expected $expectedLength",
            kotlin.math.abs((vertical.bottom - vertical.top).value - expectedLength) < 2f)
        assertTrue("vertical exceeds stage: $vertical vs $stage", vertical.bottom <= stage.bottom + 1.dp)
    }
}
