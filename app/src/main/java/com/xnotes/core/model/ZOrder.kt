package com.xnotes.core.model

import com.xnotes.core.geometry.Rect

/** Where Arrange puts a selection in a stack of items, whose z order is list order. */
enum class Arrange { FRONT, FORWARD, BACKWARD, BACK }

/**
 * [all] with the [selected] items moved as [how] says, keeping their own order. Forward and
 * backward step the selection past the nearest item it overlaps, since passing one it doesn't
 * touch would change nothing to see; with no such item the order comes back unchanged.
 */
fun <T> arrangeOrder(all: List<T>, selected: (T) -> Boolean, how: Arrange, bounds: (T) -> Rect): List<T> {
    val moving = all.filter(selected)
    if (moving.isEmpty()) return all
    val rest = all.filterNot(selected)
    return when (how) {
        Arrange.FRONT -> rest + moving
        Arrange.BACK -> moving + rest
        Arrange.FORWARD -> {
            val pass = nearestOverlap(all, selected, bounds, all.indices) ?: return all
            all.subList(0, pass).filterNot(selected) + all[pass] + all.subList(0, pass).filter(selected) +
                all.subList(pass + 1, all.size)
        }
        Arrange.BACKWARD -> {
            val pass = nearestOverlap(all, selected, bounds, all.indices.reversed()) ?: return all
            all.subList(0, pass) + all.subList(pass + 1, all.size).filter(selected) + all[pass] +
                all.subList(pass + 1, all.size).filterNot(selected)
        }
    }
}

/** The first index in [order] holding an item that is not selected and overlaps a selected item met before it. */
private fun <T> nearestOverlap(all: List<T>, selected: (T) -> Boolean, bounds: (T) -> Rect, order: IntProgression): Int? {
    val seen = ArrayList<Rect>()
    var reach: Rect? = null
    for (i in order) {
        val item = all[i]
        val box = bounds(item)
        if (selected(item)) {
            seen.add(box)
            reach = reach?.union(box) ?: box
            continue
        }
        if (reach?.intersects(box) != true) continue
        if (seen.any { it.intersects(box) }) return i
    }
    return null
}
