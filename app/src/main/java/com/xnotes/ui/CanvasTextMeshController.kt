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
import java.util.concurrent.atomic.AtomicLong

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
    private val generations = ConcurrentHashMap<TextItem, Long>()
    private val nextGeneration = AtomicLong()
    private var manipulating: TextItem? = null

    fun startGesture(item: TextItem) {
        manipulating = item
        generations.remove(item)
        preview.show(item)
        scene.remove(item)
        view.publish()
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

    fun showWhileMeshing(item: TextItem) = preview.show(item)

    fun beginEditing(item: TextItem) {
        generations.remove(item)
        if (manipulating === item) manipulating = null
        preview.clearIf(item)
    }

    fun removed(item: TextItem) = beginEditing(item)

    fun reset() {
        generations.clear()
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
            generations.remove(item)
            scene.remove(item)
            return
        }

        // Snapshot on UI thread; a background job must never read mutable TextItem geometry.
        val snapshot = TextItem(
            item.pos, item.width, item.height, item.text, item.rgba,
            item.pointSize, item.face, AndroidTextMeasurer(),
        )
        val token = nextGeneration.incrementAndGet()
        generations[item] = token
        // A previous GL record may remain until the new one arrives, but gestures always
        // use the native preview. Never copy/shift obsolete glyph vertices as a second path.
        executor.execute {
            if (generations[item] != token) return@execute
            val started = System.nanoTime()
            val meshed = ItemMesher.mesh(snapshot)
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            view.post {
                if (!generations.remove(item, token)) return@post
                scene.lastTessellateMs = elapsed
                if (meshed == null || meshed.isEmpty) scene.remove(item)
                else scene.upsert(item, meshed.parts, meshed.bounds)

                if (preview.isShowing(item) && manipulating !== item) {
                    view.publishThen {
                        view.post {
                            if (preview.isShowing(item) && manipulating !== item && !item.canvasEditingHidden) {
                                preview.clearIf(item)
                                onPreviewHidden()
                            }
                        }
                    }
                } else {
                    view.publish()
                }
            }
        }
    }
}
