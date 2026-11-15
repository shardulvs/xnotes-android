package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarEntry
import com.xnotes.core.tools.ToolbarGeometry
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.settings.CornerStyle
import com.xnotes.settings.ToolbarSize
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.XnotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolbarRoundnessComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun rows(count: Int) = ToolbarLayout(listOf(ToolbarSection(
        (0 until count).map { ToolbarEntry(ToolbarItem.PEN, lane = it) },
        lanes = count,
    )))

    @Test fun manualSliderAndAutomaticAreIndependentForSelectedBar() {
        var geometry by mutableStateOf(ToolbarGeometry(floating = true))
        rule.setContent {
            MaterialTheme {
                ToolbarRoundnessSettings(
                    geometry = geometry, layout = rows(3), size = ToolbarSize.REGULAR,
                    vertical = false, floating = true,
                    onChange = { geometry = geometry.copy(cornerRadiusDp = it) },
                )
            }
        }
        rule.waitForIdle()
        assertNull(geometry.cornerRadiusDp)
        rule.onNodeWithTag("toolbar-roundness-settings").assertExists()
        rule.onNodeWithTag("toolbar-roundness-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
        rule.waitForIdle()
        assertEquals(12, geometry.cornerRadiusDp)
        rule.onNodeWithTag("toolbar-measure-auto").performClick()
        rule.waitForIdle()
        assertNull(geometry.cornerRadiusDp)
    }

    @Test fun oneAndThreeRowPreviewCornersUseDifferentSilhouettes() {
        var count by mutableStateOf(1)
        var measuredRadius = 0f
        rule.setContent {
            XnotesTheme(Palette.DEFAULT, CornerStyle.ROUNDED) {
                val layout = rows(count)
                val shape = toolbarSurfaceShape(
                    ToolbarGeometry(floating = true), layout, ToolbarSize.REGULAR,
                    vertical = false, floating = true,
                )
                val density = LocalDensity.current
                SideEffect {
                    measuredRadius = (shape.createOutline(Size(800f, 800f),
                        androidx.compose.ui.unit.LayoutDirection.Ltr, density) as Outline.Rounded)
                        .roundRect.topLeftCornerRadius.x
                }
                Box(Modifier.size(190.dp, 150.dp)) {
                    Box(Modifier.size(170.dp, (count * 42 + 8 + (count - 1) * 2).dp)
                        .clip(shape).background(Color.Blue)
                        .testTag("rounded-bar"))
                }
            }
        }
        rule.waitForIdle()
        val single = measuredRadius
        rule.runOnIdle { count = 3 }
        rule.waitForIdle()
        val triple = measuredRadius
        assertTrue("3-row auto bars should use a smaller actual radius: $single vs $triple",
            single > triple * 1.5f)
    }
}
