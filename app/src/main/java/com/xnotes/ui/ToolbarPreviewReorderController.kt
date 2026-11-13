package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarWorkspace

/**
 * One gesture's preview and commit state. Separates hit testing/transactions from
 * Compose rendering; neither the persistent workspace nor bar identity changes while
 * the pointer is down. The last released bar bypasses slot animations until another
 * drag begins, eliminating the old-slot flash after an otherwise valid reorder.
 */
internal class ToolbarPreviewReorderController {
    var sourceId by mutableStateOf<String?>(null)
        private set
    var targetId by mutableStateOf<String?>(null)
        private set
    var lastReleasedId by mutableStateOf<String?>(null)
        private set

    /** Holds the destination through the asynchronous workspace acknowledgement. */
    private data class PendingCommit(val from: String, val to: String, val order: List<String>)
    private var pendingCommit by mutableStateOf<PendingCommit?>(null)

    val currentBounds: MutableMap<String, Rect> = mutableMapOf()
    private var initialBounds: Map<String, Rect> = emptyMap()
    private var peers: List<String> = emptyList()
    private var vertical = false
    private var gestureWorkspace: ToolbarWorkspace? = null

    private fun visualIds(workspace: ToolbarWorkspace): List<String> = workspace.bars
        .withIndex().sortedWith(compareBy({ it.value.geometry.visualOrder ?: it.index }, { it.index }))
        .map { it.value.id }

    /** Never render the pre-drop slot while the callback publishes the new workspace. */
    fun displayedWorkspace(workspace: ToolbarWorkspace): ToolbarWorkspace {
        val from = sourceId
        val target = targetId
        if (from != null && target != null) return workspace.reorderVisualBar(from, target)
        val pending = pendingCommit ?: return workspace
        if (visualIds(workspace) == pending.order) return workspace
        return workspace.reorderVisualBar(pending.from, pending.to)
    }

    /** Called from a workspace-keyed effect after the real change becomes observable. */
    fun acknowledge(workspace: ToolbarWorkspace) {
        if (pendingCommit?.order == visualIds(workspace)) pendingCommit = null
    }

    fun updateBounds(id: String, bounds: Rect) {
        if (currentBounds[id] != bounds) currentBounds[id] = bounds
    }

    fun start(id: String, candidates: List<String>, verticalAxis: Boolean, workspace: ToolbarWorkspace) {
        pendingCommit = null
        gestureWorkspace = workspace
        lastReleasedId = null
        sourceId = id
        targetId = null
        initialBounds = currentBounds.toMap()
        peers = candidates
        vertical = verticalAxis
    }

    fun hover(id: String, rootPointer: Offset) {
        if (sourceId != id) return
        val candidate = toolbarPreviewReorderHit(id, peers, rootPointer, initialBounds, vertical)
        if (candidate != null) {
            // Reaching another peer updates the prospective order.
            targetId = candidate
        } else if (initialBounds[id]?.contains(rootPointer) == true) {
            // Returning over the source deliberately cancels the preview.
            targetId = null
        }
        // Empty space does not mean "restore old order". Keep the previous target
        // stable while the pointer passes between bars or over the preview canvas.
    }

    fun drop(id: String, rootPointer: Offset, commit: (String, String) -> Unit): Boolean {
        if (sourceId != id) return false
        val destination = toolbarPreviewReorderHit(id, peers, rootPointer, initialBounds, vertical)
        val accepted = destination != null && destination in peers && destination != id
        if (accepted) {
            val expected = gestureWorkspace?.reorderVisualBar(id, destination!!)
            if (expected != null) {
                pendingCommit = PendingCommit(id, destination!!, visualIds(expected))
            }
            lastReleasedId = id
            commit(id, destination!!)
        }
        clearGesture()
        return accepted
    }

    /** An anchor move is not a lane reorder: keep its source on the direct (non-spring)
     * placement path while the new edge and rotated dimensions are published. Otherwise
     * the parent springs from the previous slot as ToolbarDragSurface clears its offset,
     * showing a brief snapback even when pendingAnchor is correctly acknowledged. */
    fun markAnchorMoveReleased(id: String) {
        lastReleasedId = id
    }

    fun cancel(id: String) {
        if (sourceId == id) clearGesture()
    }

    private fun clearGesture() {
        sourceId = null
        targetId = null
        peers = emptyList()
        initialBounds = emptyMap()
        gestureWorkspace = null
    }
}
