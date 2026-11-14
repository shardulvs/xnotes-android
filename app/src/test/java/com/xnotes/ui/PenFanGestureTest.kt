package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PenFanGestureTest {
    @Test fun dragTowardEachPenChoosesMatchingItemInAllOrientations() {
        PenFanDirection.entries.forEach { direction ->
            repeat(6) { index ->
                val delta = penFanOffset(index, 6, direction, 140f)
                assertEquals(index, penFanTarget(delta, direction, 6, 18f))
            }
        }
    }

    @Test fun sideRailExtremesRemainSelectableWithSmallForwardMovement() {
        // The leftmost/rightmost pens sit near the tangent of the semicircle.
        PenFanDirection.entries.forEach { direction ->
            listOf(0, 5).forEach { index ->
                val delta = penFanOffset(index, 6, direction, 94f)
                assertEquals(index, penFanTarget(delta, direction, 6, 32f))
            }
        }
    }

    @Test fun popupOriginMatchesItsSideOfTheLauncher() {
        assertEquals(Offset(21f, 35f), penFanGestureOrigin(42f, PenFanDirection.DOWN, 35f))
        assertEquals(Offset(21f, 7f), penFanGestureOrigin(42f, PenFanDirection.UP, 35f))
        assertEquals(Offset(35f, 21f), penFanGestureOrigin(42f, PenFanDirection.RIGHT, 35f))
        assertEquals(Offset(7f, 21f), penFanGestureOrigin(42f, PenFanDirection.LEFT, 35f))
    }

    @Test fun screenSpaceHitTestTracksShiftedSidePaletteAndEdgePens() {
        // Popup alignment may be clamped at the top/bottom of a tablet screen.
        val shifted = mapOf(
            0 to Offset(310f, 70f),
            1 to Offset(358f, 95f),
            2 to Offset(382f, 140f),
            3 to Offset(382f, 190f),
            4 to Offset(358f, 235f),
            5 to Offset(310f, 260f),
        )
        assertEquals(0, penFanVisibleTarget(Offset(310f, 62f), shifted, 6, 44f))
        assertEquals(5, penFanVisibleTarget(Offset(315f, 271f), shifted, 6, 44f))
        assertEquals(3, penFanVisibleTarget(Offset(382f, 192f), shifted, 6, 44f))
        assertNull(penFanVisibleTarget(Offset(100f, 100f), shifted, 6, 44f))
        assertNull(penFanVisibleTarget(Offset(Float.NaN, 120f), shifted, 6, 44f))
    }

    @Test fun tapAndWrongDirectionDoNotCommitAccidentally() {
        assertNull(penFanTarget(Offset.Zero, PenFanDirection.DOWN, 6, 18f))
        assertNull(penFanTarget(Offset(0f, -120f), PenFanDirection.DOWN, 6, 18f))
        assertNull(penFanTarget(Offset(50f, 0f), PenFanDirection.DOWN, 6, 18f))
        assertNull(penFanTarget(Offset(Float.NaN, 120f), PenFanDirection.DOWN, 6, 18f))
        assertNull(penFanTarget(Offset(0f, 120f), PenFanDirection.DOWN, 0, 18f))
    }
}
