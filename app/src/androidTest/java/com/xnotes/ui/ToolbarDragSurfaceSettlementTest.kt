package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.ToolbarAnchor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Verifies the move path independently of the reorder controller.
 * A delayed parent preference acknowledgement must not expose the old position. */
class ToolbarDragSurfaceSettlementTest {
    @get:Rule val rule = createComposeRule()

    @Test fun delayedAnchorCommitDoesNotShowTheOldLocation() {
        var anchor by mutableStateOf(ToolbarAnchor.TOP_LEFT)
        var parentX by mutableStateOf(100.dp)
        var pendingAnchor: ToolbarAnchor? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(1000.dp).height(600.dp)) {
                    Box(Modifier.offset(x = parentX, y = 200.dp).size(150.dp, 65.dp)) {
                        ToolbarDragSurface(
                            id = "moving", anchor = anchor, movable = true,
                            paneBounds = Rect(0f, 0f, 1800f, 900f),
                            onMoveBar = { _, target -> pendingAnchor = target },
                        ) { Box(Modifier.size(150.dp, 65.dp)) }
                    }
                }
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag("toolbar-drag-surface-moving", useUnmergedTree = true)
        val before = node.getUnclippedBoundsInRoot().left.value
        val distance = 300f
        node.performTouchInput {
            down(center)
            advanceEventTime(780)
            moveBy(Offset(distance, 0f))
            advanceEventTime(48)
            up()
        }
        rule.waitForIdle()
        assertNotNull("The move callback must be called", pendingAnchor)
        // The editor has not yet published its new geometry. The bar must stay
        // where it was released instead of flashing to the old anchor.
        repeat(4) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val x = node.getUnclippedBoundsInRoot().left.value
            assertTrue("frame=$frame returned to old slot x=$x before=$before", x > before + 100f)
        }
        rule.runOnIdle {
            anchor = pendingAnchor!!
            parentX = 450.dp
        }
        rule.waitForIdle()
        assertTrue("settled bar must follow the new parent slot",
            node.getUnclippedBoundsInRoot().left.value > before + 200f)
    }
}
