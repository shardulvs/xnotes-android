package com.xnotes.core.audio

import com.xnotes.core.model.CanvasItem
import com.xnotes.core.pal.Renderer

/**
 * What playback fades: the items of recording [rid] added after the playhead, [playheadMs] into it.
 * They draw faint until the audio reaches them, so the page shows what has been said so far.
 */
class InkFade(val rid: Int, val playheadMs: Int) {

    fun fades(item: CanvasItem): Boolean {
        val m = item.sync ?: return false
        return m.rid == rid && m.ms > playheadMs
    }

    companion object {
        /** How strongly faded ink still shows. */
        const val ALPHA = 0.25

        /**
         * Paint [items] in order through [paint], skipping any [include] leaves out. Each run of
         * faded ones goes inside one translucent layer, so where they cross each other they do not
         * darken, exactly as one stroke never darkens where it crosses itself.
         */
        inline fun paint(
            items: List<CanvasItem>,
            r: Renderer,
            fade: InkFade?,
            include: (CanvasItem) -> Boolean = { true },
            paint: (CanvasItem) -> Unit,
        ) {
            if (fade == null) {
                for (item in items) if (include(item)) paint(item)
                return
            }
            var i = 0
            while (i < items.size) {
                val item = items[i]
                if (!include(item)) {
                    i++
                    continue
                }
                if (!fade.fades(item)) {
                    paint(item)
                    i++
                    continue
                }
                var end = i + 1
                var bounds = item.paintBounds()
                while (end < items.size) {
                    val next = items[end]
                    if (include(next)) {
                        if (!fade.fades(next)) break
                        bounds = bounds.union(next.paintBounds())
                    }
                    end++
                }
                r.saveLayerAlpha(bounds, ALPHA)
                for (k in i until end) if (include(items[k])) paint(items[k])
                r.restore()
                i = end
            }
        }
    }
}
