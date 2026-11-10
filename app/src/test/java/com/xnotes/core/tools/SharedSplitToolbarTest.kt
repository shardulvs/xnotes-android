package com.xnotes.core.tools

import com.xnotes.settings.Preferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedSplitToolbarTest {
    private val left = ToolbarPaneBounds(0.0, 50.0, 500.0, 800.0)
    private val right = ToolbarPaneBounds(500.0, 50.0, 1000.0, 800.0)

    @Test fun topBarReservesOnlyItsActualOverlapInEachPane() {
        val bar = ToolbarPaneBounds(200.0, 58.0, 800.0, 106.0)
        assertEquals(ToolbarPaneInsets(top = 56.0), sharedToolbarInsets(bar, left, "top"))
        assertEquals(ToolbarPaneInsets(top = 56.0), sharedToolbarInsets(bar, right, "top"))
    }

    @Test fun sideRailDoesNotReserveSpaceInTheOtherPane() {
        val bar = ToolbarPaneBounds(8.0, 200.0, 56.0, 600.0)
        assertEquals(ToolbarPaneInsets(left = 56.0), sharedToolbarInsets(bar, left, "left"))
        assertEquals(ToolbarPaneInsets(), sharedToolbarInsets(bar, right, "left"))
    }

    @Test fun stackedPanesReserveOnlyTheOuterEdge() {
        val top = ToolbarPaneBounds(0.0, 50.0, 1000.0, 400.0)
        val bottom = ToolbarPaneBounds(0.0, 400.0, 1000.0, 800.0)
        val bar = ToolbarPaneBounds(200.0, 744.0, 800.0, 792.0)
        assertEquals(ToolbarPaneInsets(), sharedToolbarInsets(bar, top, "bottom"))
        assertEquals(ToolbarPaneInsets(bottom = 56.0), sharedToolbarInsets(bar, bottom, "bottom"))
    }

    @Test fun rightRailRespectsUnequalPaneSizes() {
        val bar = ToolbarPaneBounds(944.0, 200.0, 992.0, 600.0)
        assertEquals(ToolbarPaneInsets(), sharedToolbarInsets(bar, left, "right"))
        assertEquals(ToolbarPaneInsets(right = 56.0), sharedToolbarInsets(bar, right, "right"))
    }

    @Test fun notYetMeasuredPaneDoesNotGetAnInset() {
        assertEquals(ToolbarPaneInsets(), sharedToolbarInsets(left, ToolbarPaneBounds(0.0, 0.0, 0.0, 0.0), "top"))
    }

    @Test fun mixedChoiceSurvivesReloadAndUnknownValuesFallBack() {
        val pref = Preferences(mixedSplitToolbar = SplitToolbarInterface.CANVAS)
        assertEquals(SplitToolbarInterface.CANVAS, Preferences.fromJson(pref.toJson()).mixedSplitToolbar)
        assertEquals(SplitToolbarInterface.NOTE, Preferences.fromJson(JSONObject()).mixedSplitToolbar)
        assertEquals(SplitToolbarInterface.NOTE,
            Preferences.fromJson(JSONObject().put("mixed_split_toolbar", "future")).mixedSplitToolbar)
    }

    @Test fun perPaneOptOutRoundTripsWithoutChangingEitherLayout() {
        val pref = Preferences(sharedSplitToolbars = false, mixedSplitToolbar = SplitToolbarInterface.CANVAS)
        assertEquals(pref, Preferences.fromJson(pref.toJson()))
        assertTrue(Preferences.fromJson(JSONObject()).sharedSplitToolbars)
    }
}
