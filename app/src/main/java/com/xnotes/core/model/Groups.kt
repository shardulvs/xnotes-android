package com.xnotes.core.model

/**
 * Items grouped by an id each member carries, unique in its document. Groups are flat: grouping
 * groups merges them into one. A group holds together on one page, or on a canvas, and one left
 * with a single member is no group at all.
 */
object Groups {

    /** An id past every group id among [items]. */
    fun nextId(items: Iterable<CanvasItem>): Int = (items.maxOfOrNull { it.group ?: 0 } ?: 0) + 1

    /** Give [copies] fresh group ids past those among [existing], fellow members keeping one between them. */
    fun refresh(copies: List<CanvasItem>, existing: Iterable<CanvasItem>) {
        var next = nextId(existing)
        val ids = HashMap<Int, Int>()
        for (c in copies) c.group?.let { c.group = ids.getOrPut(it) { next++ } }
    }

    /** True when [items] hold a group, two or more of them sharing an id. */
    fun hasGroup(items: List<CanvasItem>): Boolean =
        items.mapNotNull { it.group }.groupingBy { it }.eachCount().values.any { it >= 2 }

    /** True when [items] are two or more and not already one group. */
    fun canGroup(items: List<CanvasItem>): Boolean {
        if (items.size < 2) return false
        val id = items.first().group ?: return true
        return items.any { it.group != id }
    }

    /** [picked] with every unlocked member of the groups they are in, from among [all]. */
    fun <T> withMembers(picked: List<T>, all: Iterable<T>, item: (T) -> CanvasItem): List<T> {
        val ids = picked.mapNotNullTo(HashSet()) { item(it).group }
        if (ids.isEmpty()) return picked
        val have = picked.mapTo(HashSet()) { item(it) }
        val out = ArrayList(picked)
        for (t in all) {
            val i = item(t)
            if (i.group in ids && !i.locked && have.add(i)) out.add(t)
        }
        return out
    }
}
