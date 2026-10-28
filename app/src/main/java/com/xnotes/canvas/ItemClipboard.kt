package com.xnotes.canvas

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.deepCopyAll
import com.xnotes.core.pal.TextMeasurer
import java.lang.ref.WeakReference

/**
 * The items Copy and Cut put by, one for both panes and the infinite canvas, until something
 * replaces them or the app closes. A cut's items are spent by the paste that puts them down.
 */
object ItemClipboard {

    class Clip(
        /** Copies of the items as they were, in the space of the document they came from. */
        val items: List<CanvasItem>,
        /** That document's dpi, the scale its items are measured in. */
        val dpi: Int,
        home: Any,
        val cut: Boolean,
    ) {
        private val home = WeakReference(home)

        /** True when the items came from [doc], where a paste behaves as it always has. */
        fun isFrom(doc: Any): Boolean = home.get() === doc
    }

    @Volatile
    var clip: Clip? = null

    /**
     * Fresh copies of [c]'s items for a document at [dpi]: scaled to it, and, unless they go back
     * [home], with any tie to a recording that did not come along taken off.
     */
    fun copiesFor(c: Clip, dpi: Int, home: Boolean, measurer: TextMeasurer): List<CanvasItem> {
        val copies = c.items.deepCopyAll(measurer)
        val k = dpi.toDouble() / c.dpi
        if (k != 1.0) {
            val t = Affine.scaleAbout(Pt.ZERO, k, k)
            for (item in copies) item.applyTransform(t)
        }
        if (!home) {
            val rids = copies.filterIsInstance<AudioItem>().mapTo(HashSet()) { it.rid }
            for (item in copies) if (item.sync?.rid?.let { it !in rids } == true) item.sync = null
        }
        return copies
    }
}
