package com.xnotes.canvas

/** How much memory the page caches may take when the user has not said. */
object CacheBudget {
    /** Screens of pixels an automatic budget holds: a PDF note's two layers about two screens either side of the view. */
    const val SCREENS = 12

    /** The least a budget comes to, however small the view. */
    const val FLOOR: Long = 64L shl 20

    /** The budget for a view [widthPx] by [heightPx]: at most [ceiling], a share of the device's memory, and never under [FLOOR]. */
    fun automatic(widthPx: Int, heightPx: Int, ceiling: Long = Long.MAX_VALUE): Long =
        (SCREENS * 4L * widthPx * heightPx).coerceAtMost(ceiling).coerceAtLeast(FLOOR)
}
