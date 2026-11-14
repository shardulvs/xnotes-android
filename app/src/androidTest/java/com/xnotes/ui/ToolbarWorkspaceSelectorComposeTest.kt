package com.xnotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.R
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Command logic lives in ToolbarPreferencesPane, not in this presentational menu. */
class ToolbarWorkspaceSelectorComposeTest {
    @get:Rule val rule = createComposeRule()
    private val primary = ToolbarBar("main", "Main", ToolbarLayout.DEFAULT)
    private val secondary = ToolbarBar("colors", "Colors", ToolbarLayout.DEFAULT)

    @Test fun switchesContextAndSelectsBarWithoutModifyingWorkspace() {
        val workspace = ToolbarWorkspace(listOf(primary, secondary))
        var canvas by mutableStateOf(false)
        var selected by mutableStateOf("main")
        rule.setContent {
            MaterialTheme {
                ToolbarWorkspaceSelector(
                    canvasTab = canvas, workspace = workspace, selectedBarId = selected,
                    onContextSelected = { canvas = it }, onBarSelected = { selected = it },
                    onResetSelectedBar = {}, onOpenLibrary = {}, onCreate = {},
                    onDuplicate = {}, onRemove = {}, onCopy = {},
                )
            }
        }
        rule.onNodeWithText("xcanvas").performClick()
        rule.runOnIdle { assertTrue(canvas) }
        rule.onNodeWithText("Main ▾").performClick()
        rule.onNodeWithText("2. Colors").performClick()
        rule.runOnIdle { assertEquals("colors", selected) }
        assertEquals(listOf(primary, secondary), workspace.bars)
    }

    @Test fun actionMenuDoesNotOfferRemovalForPrimaryButDoesForSecondary() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val workspace = ToolbarWorkspace(listOf(primary, secondary))
        var selected by mutableStateOf("main")
        var action by mutableStateOf("")
        rule.setContent {
            MaterialTheme {
                ToolbarWorkspaceSelector(
                    canvasTab = false, workspace = workspace, selectedBarId = selected,
                    onContextSelected = {}, onBarSelected = { selected = it },
                    onResetSelectedBar = { action = "reset" },
                    onOpenLibrary = { action = "library" },
                    onCreate = { action = "create" },
                    onDuplicate = { action = "duplicate" },
                    onRemove = { action = "remove" },
                    onCopy = { action = "copy" },
                )
            }
        }
        val actionIcon = resources.getString(R.string.toolbar_config_create_duplicate_or_copy_a_bar)
        rule.onNodeWithContentDescription(actionIcon).performClick()
        rule.onNodeWithText(resources.getString(R.string.remove)).assertDoesNotExist()
        rule.onNodeWithText(resources.getString(R.string.duplicate)).performClick()
        rule.runOnIdle { assertEquals("duplicate", action) }

        rule.onNodeWithText("Main ▾").performClick()
        rule.onNodeWithText("2. Colors").performClick()
        rule.onNodeWithContentDescription(actionIcon).performClick()
        rule.onNodeWithText(resources.getString(R.string.remove)).assertExists().performClick()
        rule.runOnIdle { assertEquals("remove", action) }
    }
}
