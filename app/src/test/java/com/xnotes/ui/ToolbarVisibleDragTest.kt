package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarVisibleDragTest {
    @Test fun tallBarGrabbedByMiddleCannotEscapeOccupiedTopEdge() {
        val viewport = Rect(0f, 0f, 900f, 550f)
        val vertical = Rect(0f, 180f, 68f, 440f)
        val pointer = Offset(550f, -320f)
        val shown = toolbarVisibleDrag(pointer, vertical, viewport)
        assertEquals(0f, vertical.top + shown.y, 0.001f)
        assertEquals(pointer.x, shown.x, 0.001f)
    }

    @Test fun rightAndBottomEdgesConstrainTheVisibleSurfaceNotThePointer() {
        val viewport = Rect(0f, 0f, 900f, 550f)
        val source = Rect(210f, 70f, 410f, 130f)
        val shown = toolbarVisibleDrag(Offset(810f, 570f), source, viewport)
        assertEquals(viewport.right, source.right + shown.x, 0.001f)
        assertEquals(viewport.bottom, source.bottom + shown.y, 0.001f)
    }

    @Test fun oversizedToolbarKeepsValidOffsetInsteadOfJumpingToCenter() {
        val viewport = Rect(0f, 0f, 150f, 200f)
        val wide = Rect(-35f, 20f, 235f, 85f)
        assertEquals(Offset.Zero, toolbarVisibleDrag(Offset.Zero, wide, viewport))
        assertEquals(-85f, toolbarVisibleDrag(Offset(-1000f, 0f), wide, viewport).x, 0.001f)
    }

    @Test fun settleTargetUsesParentSlotNotOldCenteredChild() {
        // RIGHT/CENTER color bar: child starts 24 px in from its parent and
        // morphs into a TOP/CENTER row whose inset disappears.
        val target = Offset(519f, 12f)
        val parent = Offset(1140f, 339f)
        val oldChild = Rect(1164f, 339f, 1272f, 561f)
        assertEquals(Offset(-621f, -327f),
            toolbarSettleDestinationOffset(target, parent, oldChild))
        // A legacy standalone bar without a parent-plan coordinate still
        // uses the measured slot as an explicit, documented fallback.
        assertEquals(Offset(-645f, -327f),
            toolbarSettleDestinationOffset(target, null, oldChild))
    }

    @Test fun missingGeometryDoesNotChangePointerMotion() {
        val offset = Offset(170f, -260f)
        assertEquals(offset, toolbarVisibleDrag(offset, Rect.Zero, Rect.Zero))
    }
}
