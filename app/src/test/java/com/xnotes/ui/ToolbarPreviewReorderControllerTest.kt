package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarBar
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarWorkspace
import org.junit.Assert.*
import org.junit.Test

class ToolbarPreviewReorderControllerTest {
    private val initial = mapOf(
        "left" to Rect(0f, 0f, 100f, 45f),
        "middle" to Rect(110f, 0f, 210f, 45f),
        "right" to Rect(220f, 0f, 320f, 45f),
    )

    private val workspace = ToolbarWorkspace(listOf(
        ToolbarBar("left", "Left", ToolbarLayout.DEFAULT),
        ToolbarBar("middle", "Middle", ToolbarLayout.DEFAULT),
        ToolbarBar("right", "Right", ToolbarLayout.DEFAULT),
    ))

    @Test fun hoverDoesNotPersistAndReleaseKeepsSourceInDirectPlacementMode() {
        val state = ToolbarPreviewReorderController()
        for ((id, bounds) in initial) state.updateBounds(id, bounds)
        state.start("middle", initial.keys.toList(), verticalAxis = false, workspace = workspace)
        state.hover("middle", Offset(260f, 20f))
        assertEquals("middle", state.sourceId)
        assertEquals("right", state.targetId)
        var committed: Pair<String, String>? = null
        assertTrue(state.drop("middle", Offset(270f, 20f)) { a, b -> committed = a to b })
        assertEquals("middle" to "right", committed)
        assertNull(state.sourceId)
        assertNull(state.targetId)
        assertEquals("middle", state.lastReleasedId)
        val projected = workspace.reorderVisualBar("middle", "right")
        assertEquals(projected, state.displayedWorkspace(workspace))
        assertEquals(projected, state.displayedWorkspace(projected))
        state.acknowledge(projected)
        assertEquals(projected, state.displayedWorkspace(projected))
        state.start("left", initial.keys.toList(), verticalAxis = false, workspace = workspace)
        assertNull(state.lastReleasedId)
    }

    @Test fun hoveringThroughGapsDoesNotUndoThePreviewReorder() {
        val state = ToolbarPreviewReorderController()
        initial.forEach { (id, bounds) -> state.updateBounds(id, bounds) }
        state.start("middle", initial.keys.toList(), verticalAxis = false, workspace = workspace)
        state.hover("middle", Offset(260f, 20f))
        assertEquals("right", state.targetId)
        // Crossing empty preview space must not temporarily restore the old order.
        state.hover("middle", Offset(350f, 110f))
        assertEquals("right", state.targetId)
        state.hover("middle", Offset(270f, 20f))
        assertEquals("right", state.targetId)
        // Returning over the original source deliberately cancels the preview.
        state.hover("middle", Offset(160f, 20f))
        assertNull(state.targetId)
    }

    @Test fun invalidDropAndCancellationDoNotCommit() {
        val state = ToolbarPreviewReorderController()
        initial.forEach { (id, rect) -> state.updateBounds(id, rect) }
        state.start("middle", listOf("middle", "right"), verticalAxis = false, workspace = workspace)
        assertFalse(state.drop("middle", Offset(10f, 20f)) { _, _ -> fail("invalid target") })
        assertNull(state.lastReleasedId)
        state.start("middle", listOf("middle", "right"), verticalAxis = false, workspace = workspace)
        state.cancel("middle")
        assertNull(state.sourceId)
        assertNull(state.targetId)
    }
}
