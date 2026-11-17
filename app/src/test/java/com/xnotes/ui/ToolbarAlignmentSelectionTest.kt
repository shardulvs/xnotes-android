package com.xnotes.ui

import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarDockAlignment
import com.xnotes.core.tools.ToolbarDockEdge
import org.junit.Assert.assertEquals
import org.junit.Test

/** All inspector layouts delegate to the same alignment conversion. */
class ToolbarAlignmentSelectionTest {
    @Test fun startCenterEndMatchHorizontalAnchorSlots() {
        assertEquals(ToolbarAnchor.TOP_LEFT,
            toolbarAnchorForAlignment(ToolbarDockEdge.TOP, ToolbarDockAlignment.START))
        assertEquals(ToolbarAnchor.TOP_CENTER,
            toolbarAnchorForAlignment(ToolbarDockEdge.TOP, ToolbarDockAlignment.CENTER))
        assertEquals(ToolbarAnchor.TOP_RIGHT,
            toolbarAnchorForAlignment(ToolbarDockEdge.TOP, ToolbarDockAlignment.END))
        assertEquals(ToolbarAnchor.BOTTOM_LEFT,
            toolbarAnchorForAlignment(ToolbarDockEdge.BOTTOM, ToolbarDockAlignment.START))
        assertEquals(ToolbarAnchor.BOTTOM_CENTER,
            toolbarAnchorForAlignment(ToolbarDockEdge.BOTTOM, ToolbarDockAlignment.CENTER))
        assertEquals(ToolbarAnchor.BOTTOM_RIGHT,
            toolbarAnchorForAlignment(ToolbarDockEdge.BOTTOM, ToolbarDockAlignment.END))
    }

    @Test fun verticalRailsKeepTheirSideWhileAlignmentVaries() {
        for (alignment in ToolbarDockAlignment.entries) {
            assertEquals(ToolbarAnchor.CENTER_LEFT,
                toolbarAnchorForAlignment(ToolbarDockEdge.LEFT, alignment))
            assertEquals(ToolbarAnchor.CENTER_RIGHT,
                toolbarAnchorForAlignment(ToolbarDockEdge.RIGHT, alignment))
        }
    }
}
