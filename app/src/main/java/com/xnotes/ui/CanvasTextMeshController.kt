package com.xnotes.ui

import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.gl.CanvasScene
import com.xnotes.gl.InfiniteCanvasView
import com.xnotes.platform.AndroidTextMeasurer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * XCanvas's text-to-GL handoff. Model mutation and text layout remain shared with XNote.
 * During a gesture the native preview owns the text and chrome; one generation-checked
 * worker meshes the final snapshot, then hides the preview after a submitted GL frame.
 */
internal class CanvasTextMeshController(
    private val scene: CanvasScene,
    private val view: InfiniteCanvasView,
    private val preview: CanvasTextPreviewView,
    private val executor: Executor,
    private val onPreviewHidden: () -> Unit,
) {
    /** Only in-flight meshes are retained; a completed frame releases its token. */
    private val pending = ConcurrentHashMap<TextItem, Any>()
    private var manipulating: TextItem? = null

    fun startGesture(item: TextItem) {
        manipulating = item
        pending.remove(item)
        showWhileMeshing(item)
    }

    fun finishGesture(item: TextItem) {
        if (manipulating !== item) return
        manipulating = null
        update(item)
    }

    fun updateLift(items: List<CanvasItem>, at: LiftTransform) {
        val item = preview.item ?: return
        val moving = items.any { it === item } && at.isLinearIdentity
        preview.setLift(if (moving) at.dx else 0.0, if (moving) at.dy else 0.0)
    }

    fun showWhileMeshing(item: TextItem) {
        preview.show(item)
        // A stale GL mesh must not paint underneath newly edited native text.
        scene.remove(item)
        view.publish()
    }

    fun beginEditing(item: TextItem) {
        pending.remove(item)
        if (manipulating === item) manipulating = null
        preview.clearIf(item)
    }

    fun reset() {
        pending.clear()
        manipulating = null
        preview.clear()
    }

    fun update(item: TextItem) {
        if (manipulating === item) {
            preview.invalidate()
            return
        }
        // An editing field owns hidden text. Invalidate any worker before removing its mesh.
        if (item.canvasEditingHidden || item.text.isEmpty()) {
            pending.remove(item)
            scene.remove(item)
            return
        }

        // Snapshot on UI thread; a background job must never read mutable TextItem geometry.
        val snapshot = TextItem(
            item.pos, item.width, item.height, item.text, item.rgba,
            item.pointSize, item.face, AndroidTextMeasurer(),
        )
        val token = Any()
        pending[item] = token
        executor.execute {
            if (pending[item] !== token) return@execute
            val meshed = ItemMesher.mesh(snapshot)
            view.post {
                if (pending[item] !== token) return@post
                if (meshed == null || meshed.isEmpty) scene.remove(item)
                else scene.upsert(item, meshed.parts, meshed.bounds)

                if (preview.isShowing(item) && manipulating !== item) {
                    // Release the native text only after the new GL frame is submitted.
                    view.publishThen {
                        view.post {
                            // A newer edit or gesture invalidates this frame's token.
                            if (!pending.remove(item, token)) return@post
                            if (preview.isShowing(item) && manipulating !== item && !item.canvasEditingHidden) {
                                preview.clearIf(item)
                                onPreviewHidden()
                            }
                        }
                    }
                } else {
                    pending.remove(item, token)
                    view.publish()
                }
            }
        }
    }
}
