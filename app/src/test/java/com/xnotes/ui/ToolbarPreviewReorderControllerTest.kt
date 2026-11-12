package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ToolbarPreviewReorderControllerTest {
    private val initial = mapOf(
        "left" to Rect(0f, 0f, 100f, 45f),
        "middle" to Rect(110f, 0f, 210f, 45f),
        "right" to Rect(220f, 0f, 320f, 45f),
    )

    @Test fun hoverDoesNotPersistAndReleaseKeepsSourceInDirectPlacementMode() {
        val state = ToolbarPreviewReorderController()
        for ((id, bounds) in initial) state.updateBounds(id, bounds)
        state.start("middle", initial.keys.toList(), verticalAxis = false)
        state.hover("middle", Offset(260f, 20f))
        assertEquals("middle", state.sourceId)
        assertEquals("right", state.targetId)
        var committed: Pair<String, String>? = null
        assertTrue(state.drop("middle", Offset(270f, 20f)) { a, b -> committed = a to b })
        assertEquals("middle" to "right", committed)
        assertNull(state.sourceId)
        assertNull(state.targetId)
        assertEquals("middle", state.lastReleasedId)
        state.start("left", initial.keys.toList(), verticalAxis = false)
        assertNull(state.lastReleasedId)
    }

    @Test fun invalidDropAndCancellationDoNotCommit() {
        val state = ToolbarPreviewReorderController()
        initial.forEach { (id, rect) -> state.updateBounds(id, rect) }
        state.start("middle", listOf("middle", "right"), verticalAxis = false)
        assertFalse(state.drop("middle", Offset(10f, 20f)) { _, _ -> fail("invalid target") })
        assertNull(state.lastReleasedId)
        state.start("middle", listOf("middle", "right"), verticalAxis = false)
        state.cancel("middle")
        assertNull(state.sourceId)
        assertNull(state.targetId)
    }
}
