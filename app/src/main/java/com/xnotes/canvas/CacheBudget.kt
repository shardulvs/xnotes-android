package com.xnotes.canvas

/** How much memory the page caches may take when the user has not said. */
object CacheBudget {
    /** Screens of pixels an automatic budget holds: a PDF note's two layers about two screens either side of the view. */
    const val SCREENS = 12

    /** The least a budget comes to, however small the view. */
    const val FLOOR: Long = 64L shl 20

    /** What a user may set it to instead, in MB: from [MIN_MB] in steps of [STEP_MB], up to [maxMb]. */
    const val MIN_MB = 64
    const val STEP_MB = 32
    const val MAX_MB = 1024

    /** The most an automatic budget takes of a device with [ram] bytes of memory. */
    fun ceiling(ram: Long): Long = if (ram > 0) ram / 16 else Long.MAX_VALUE

    /** The most a user may set on a device with [ram] bytes of memory: a quarter of it, a whole step, within [MIN_MB]..[MAX_MB]. */
    fun maxMb(ram: Long): Int {
        if (ram <= 0) return MAX_MB
        val quarter = (ram / 4 shr 20).toInt().coerceIn(MIN_MB, MAX_MB)
        return MIN_MB + (quarter - MIN_MB) / STEP_MB * STEP_MB
    }

    /** The budget for a view [widthPx] by [heightPx]: at most [ceiling], a share of the device's memory, and never under [FLOOR]. */
    fun automatic(widthPx: Int, heightPx: Int, ceiling: Long = Long.MAX_VALUE): Long =
        (SCREENS * 4L * widthPx * heightPx).coerceAtMost(ceiling).coerceAtLeast(FLOOR)
}
