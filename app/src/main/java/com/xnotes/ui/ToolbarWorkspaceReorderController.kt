package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.core.tools.ToolbarDropPosition
import com.xnotes.core.tools.ToolbarAnchor
import com.xnotes.core.tools.ToolbarDockEdge
import com.xnotes.core.tools.ToolbarDockAlignment

/**
 * Shared preview/live-toolbar gesture and commit state. Separates hit testing
 * and transactions from Compose rendering; neither the persistent workspace nor bar identity changes while
 * the pointer is down. The last released bar bypasses slot animations until another
 * drag begins, eliminating the old-slot flash after an otherwise valid reorder.
 */
internal class ToolbarWorkspaceReorderController {
    var sourceId by mutableStateOf<String?>(null)
        private set
    var targetId by mutableStateOf<String?>(null)
        private set
    var lastReleasedId by mutableStateOf<String?>(null)
        private set
    var hoverPlacement by mutableStateOf<ToolbarDropPosition?>(null)
        private set

    /** Holds the destination through the asynchronous workspace acknowledgement. */
    private data class PendingCommit(val from: String, val to: String, val order: List<String>)
    private var pendingCommit by mutableStateOf<PendingCommit?>(null)

    val currentBounds: MutableMap<String, Rect> = mutableMapOf()
    private var initialBounds: Map<String, Rect> = emptyMap()
    private var peers: List<String> = emptyList()
    private var vertical = false
    private var sourcePlacement: ToolbarDropPosition? = null
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
        val target = hoverPlacement
        val source = sourceId
        if (source != null && target != null && lastReleasedId == source &&
            workspace.bars.any { it.id == source && it.geometry.anchor == target.anchor &&
                it.geometry.dockAlignment == target.alignment }) {
            clearGesture()
        }
    }

    fun updateBounds(id: String, bounds: Rect) {
        if (currentBounds[id] != bounds) currentBounds[id] = bounds
    }

    fun start(id: String, candidates: List<String>, verticalAxis: Boolean,
        workspace: ToolbarWorkspace, originalAnchor: ToolbarAnchor? = null,
        originalAlignment: ToolbarDockAlignment = ToolbarDockAlignment.CENTER) {
        pendingCommit = null
        gestureWorkspace = workspace
        lastReleasedId = null
        sourceId = id
        targetId = null
        hoverPlacement = null
        initialBounds = currentBounds.toMap()
        peers = candidates
        vertical = verticalAxis
        sourcePlacement = originalAnchor?.let { ToolbarDropPosition(it, originalAlignment) }
    }

    fun hover(id: String, rootPointer: Offset) {
        if (sourceId != id) return
        val candidate = toolbarPreviewReorderHit(id, peers, rootPointer, initialBounds, vertical)
        if (candidate != null && !hasChangedPlacement()) {
            // Reaching another peer updates the prospective order.
            targetId = candidate
        } else if (initialBounds[id]?.contains(rootPointer) == true) {
            // Returning over the source deliberately cancels the preview.
            targetId = null
        }
        // Empty space does not mean "restore old order". Keep the previous target
        // stable while the pointer passes between bars or over the preview canvas.
    }

    private fun hasChangedPlacement(): Boolean {
        val origin = sourcePlacement ?: return false
        val hover = hoverPlacement ?: return false
        return hover.anchor.floatingEdge() != origin.anchor.floatingEdge() ||
            hover.alignment != origin.alignment
    }

    fun previewPlacement(id: String, placement: ToolbarDropPosition?) {
        if (sourceId != id) return
        if (hoverPlacement != placement) hoverPlacement = placement
        // The original peer target is intentionally sticky over gaps on the SAME
        // edge. It must NOT stay sticky after crossing to a different edge: doing
        // so hid the placement indicator and prevented the side transfer.
        if (hasChangedPlacement()) targetId = null
    }

    fun drop(id: String, rootPointer: Offset, commit: (String, String) -> Unit): Boolean {
        if (sourceId != id) return false
        val destination = toolbarPreviewReorderHit(id, peers, rootPointer, initialBounds, vertical)
        val accepted = !hasChangedPlacement() && destination != null &&
            destination in peers && destination != id
        if (accepted) {
            val expected = gestureWorkspace?.reorderVisualBar(id, destination!!)
            if (expected != null) {
                pendingCommit = PendingCommit(id, destination!!, visualIds(expected))
            }
            lastReleasedId = id
            commit(id, destination!!)
        }
        // Keep the projected peer positions and ghost visible while the source
        // eases into its new slot. Clearing here makes every neighbour flash back
        // for ~220 ms before the actual workspace commit repositions it again.
        if (accepted || !hasChangedPlacement()) clearGesture()
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
        hoverPlacement = null
        peers = emptyList()
        sourcePlacement = null
        initialBounds = emptyMap()
        gestureWorkspace = null
    }
}
