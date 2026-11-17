package com.xnotes.ui

import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class TabBarPlacementTest {
    @Test fun topDockedUsesFullWidthTabs() {
        assertEquals(TabBarPlacement.DOCKED_TOP, tabBarPlacement(ToolbarLook()))
    }

    @Test fun topFloatingSharesToolbarSurface() {
        assertEquals(TabBarPlacement.ATTACHED_TOP,
            tabBarPlacement(ToolbarLook(position = ToolbarPosition.TOP, floating = true)))
    }

    @Test fun bottomAndSideToolbarsUseCompactOverlay() {
        for (position in listOf(ToolbarPosition.BOTTOM, ToolbarPosition.LEFT, ToolbarPosition.RIGHT)) {
            for (floating in listOf(false, true)) {
                assertEquals(TabBarPlacement.FLOATING_SELECTOR,
                    tabBarPlacement(ToolbarLook(position = position, floating = floating)))
            }
        }
    }
}
