package com.xnotes.ui

import androidx.compose.ui.unit.dp

import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspaces
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.settings.ToolbarWorkspacePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarPresetApplicationTest {
    private val profiles = ToolbarWorkspaces.fromLegacy(ToolbarLayout.DEFAULT, ToolbarLayout.CANVAS_DEFAULT)

    @Test fun copyingAnAppliedDockedPrimaryDoesNotInheritUnrelatedFloatingLook() {
        val preset = ToolbarWorkspacePreset("dock", "Dock", profiles)
        val currentLook = ToolbarLook(floating = true)
        val applied = applyToolbarPresetToContext(profiles, preset, ToolbarContext.NOTE)
        val draft = planToolbarCopy(applied.note.bars.first(), true, profiles.canvas,
            ToolbarContext.CANVAS, currentLook, 1600.dp, 1200.dp, 5)!!
        assertFalse(draft.bar.geometry.floating)
        assertEquals(ToolbarDockEdge.TOP, draft.bar.geometry.dockEdge)
    }

    @Test fun dockedPresetOverridesCurrentFloatingAppearanceWithoutChangingOtherProfile() {
        for (target in ToolbarContext.entries) {
            val preset = ToolbarWorkspacePreset("dock", "Dock", profiles,
                ToolbarLook(position = ToolbarPosition.RIGHT))
            val currentLook = ToolbarLook(position = ToolbarPosition.BOTTOM, floating = true)
            val applied = applyToolbarPresetToContext(profiles.copy(shared = true), preset, target)
            val primary = applied.forContext(target).bars.first()
            assertEquals(ToolbarDockEdge.RIGHT, toolbarDockEdge(primary, 0, currentLook))
            assertFalse(primary.geometry.floating)
            assertFalse(applied.shared)
            if (target == ToolbarContext.NOTE) assertEquals(profiles.canvas, applied.canvas)
            else assertEquals(profiles.note, applied.note)
        }
    }

    @Test fun inheritedFloatingPresetOverridesCurrentDockAndSurvivesSaveReload() {
        for (target in ToolbarContext.entries) {
            val preset = ToolbarWorkspacePreset("float", "Float", profiles.copy(shared = true),
                ToolbarLook(position = ToolbarPosition.LEFT, floating = true))
            val applied = applyToolbarPresetToContext(profiles, preset, target)
            val restored = com.xnotes.settings.Settings.fromJson(
                com.xnotes.settings.Settings().withToolbarWorkspaces(applied).toJson())
            val primary = restored.effectiveToolbarWorkspaces().forContext(target).bars.first()
            assertTrue(primary.geometry.floating)
            assertNull(toolbarDockEdge(primary, 0, ToolbarLook()))
            assertEquals(ToolbarAnchor.CENTER_LEFT, primary.geometry.anchor)
            assertFalse(restored.effectiveToolbarWorkspaces().shared)
        }
    }
}
