package com.xnotes.core.audio

import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.SyncMark

/** Keeps every recording's id unique within its document as items are pasted into it. */
object RecordingIds {

    /** The lowest id above every recording [items] hold or are tied to, a recording in progress included. */
    fun next(items: Iterable<CanvasItem>): Int = (items.maxOfOrNull { idOf(it) } ?: 0) + 1

    private fun idOf(item: CanvasItem): Int = maxOf((item as? AudioItem)?.rid ?: 0, item.sync?.rid ?: 0)

    /**
     * Ready pasted [items] to join a document already holding [existing]. A pill whose id is taken
     * gets a fresh one. A copy is new ink, so it drops its moments, unless it [moves]: a cut put
     * down again, or whole pages pasted, which bring their recordings along and keep what is tied
     * to them tied to the copy.
     */
    fun adopt(items: List<CanvasItem>, existing: Iterable<CanvasItem>, moves: Boolean) {
        // A copied pill must not take over ink already tied to its number; a moved one comes back to its own.
        val taken = HashSet<Int>()
        for (item in existing) {
            if (item is AudioItem) taken.add(item.rid)
            if (!moves) item.sync?.let { taken.add(it.rid) }
        }
        var fresh = maxOf(taken.maxOrNull() ?: 0, items.maxOfOrNull { (it as? AudioItem)?.rid ?: 0 } ?: 0) + 1
        val remap = HashMap<Int, Int>()
        for (item in items) {
            if (item !is AudioItem) continue
            if (item.rid in taken) {
                remap[item.rid] = fresh
                item.rid = fresh++
            }
            taken.add(item.rid)
        }
        for (item in items) {
            val mark = item.sync ?: continue
            item.sync = if (moves) remap[mark.rid]?.let { SyncMark(it, mark.ms) } ?: mark else null
        }
    }
}
