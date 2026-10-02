package com.xnotes.core.pdf

/** A bookmark and the bookmarks nested under it. */
class OutlineNode<T>(val value: T, val kids: List<OutlineNode<T>> = emptyList())

/** The tree arithmetic behind a PDF's bookmarks. */
object Outline {

    /** [items] in document order, each nested under the closest item above it of a lower [level]. */
    fun <T> nest(items: List<T>, level: (T) -> Int): List<OutlineNode<T>> {
        class Open(val value: T, val level: Int) {
            val kids = mutableListOf<Open>()
        }
        fun done(o: Open): OutlineNode<T> = OutlineNode(o.value, o.kids.map { done(it) })
        val roots = mutableListOf<Open>()
        val stack = ArrayList<Open>()
        for (item in items) {
            val o = Open(item, level(item))
            while (stack.isNotEmpty() && stack.last().level >= o.level) stack.removeAt(stack.size - 1)
            (stack.lastOrNull()?.kids ?: roots) += o
            stack += o
        }
        return roots.map { done(it) }
    }

    /** [nodes] with every value passed through [keep]; a node it drops (null) leaves its children in its place. */
    fun <T, R> remap(nodes: List<OutlineNode<T>>, keep: (T) -> R?): List<OutlineNode<R>> = nodes.flatMap { n ->
        val kids = remap(n.kids, keep)
        val value = keep(n.value)
        if (value == null) kids else listOf(OutlineNode(value, kids))
    }

    /** Where each source page went: its first place in [sources], the source page each export page shows (null for none). */
    fun pageMap(sources: List<Int?>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for ((i, s) in sources.withIndex()) if (s != null && s !in out) out[s] = i
        return out
    }
}
