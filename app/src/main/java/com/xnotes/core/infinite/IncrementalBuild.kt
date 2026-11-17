package com.xnotes.core.infinite

/**
 * Runs model-to-render work in bounded batches, yielding to the host between them.
 * A later start (or cancel) invalidates callbacks queued for a previous document.
 * The host owns thread confinement: onBatch and schedule must both run on its UI thread.
 */
class IncrementalBuild<T> {
    private var generation = 0L

    fun cancel() { generation++ }

    fun start(
        items: List<T>,
        batchSize: Int,
        onBatch: (List<T>) -> Unit,
        schedule: (() -> Unit) -> Unit,
        onComplete: () -> Unit = {},
    ) {
        require(batchSize > 0)
        val token = ++generation
        var position = 0

        fun step() {
            if (generation != token) return
            val end = (position + batchSize).coerceAtMost(items.size)
            if (end > position) onBatch(items.subList(position, end))
            if (generation != token) return
            position = end
            if (position < items.size) schedule(::step)
            else onComplete()
        }
        step()
    }
}
