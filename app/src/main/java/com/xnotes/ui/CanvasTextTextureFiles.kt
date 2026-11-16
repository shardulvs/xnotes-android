package com.xnotes.ui

import com.xnotes.core.model.ImageData
import com.xnotes.core.model.TextItem
import com.xnotes.gl.CanvasScene
import java.util.IdentityHashMap

/**
 * Owns only the temporary GL sources for editable, color-emoji text.
 * The actual document continues to store native TextItems, never temporary images.
 */
internal class CanvasTextTextureFiles(private val scene: CanvasScene) {
    private val sources = IdentityHashMap<TextItem, ImageData>()

    fun track(item: TextItem, source: ImageData) {
        if (sources[item] === source) return
        forget(item)
        sources[item] = source
    }

    fun forget(item: TextItem) {
        val source = sources.remove(item) ?: return
        scene.dropTextTexture(source)
        source.file.delete()
    }

    fun clear() {
        for (item in sources.keys.toList()) forget(item)
    }
}
