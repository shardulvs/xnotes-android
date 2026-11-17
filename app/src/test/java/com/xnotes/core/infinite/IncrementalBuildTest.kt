package com.xnotes.core.infinite

import org.junit.Assert.assertEquals
import org.junit.Test

class IncrementalBuildTest {
    @Test fun largeDocumentYieldsBetweenBatchesWithoutLosingItems() {
        val queue = ArrayDeque<() -> Unit>()
        val built = mutableListOf<Int>()
        var completed = 0
        IncrementalBuild<Int>().start(
            items = (1..55).toList(),
            batchSize = 16,
            onBatch = { built.addAll(it) },
            schedule = { queue.addLast(it) },
            onComplete = { completed++ },
        )
        assertEquals((1..16).toList(), built)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals((1..55).toList(), built)
        assertEquals(1, completed)
    }

    @Test fun queuedOldDocumentCannotRenderAfterSwitch() {
        val queue = ArrayDeque<() -> Unit>()
        val built = mutableListOf<String>()
        val loader = IncrementalBuild<String>()
        val schedule: (() -> Unit) -> Unit = { queue.addLast(it) }
        loader.start(listOf("old-1", "old-2", "old-3"), 1, { built.addAll(it) }, schedule)
        loader.start(listOf("new-1", "new-2"), 1, { built.addAll(it) }, schedule)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf("old-1", "new-1", "new-2"), built)
    }

    @Test fun cancellationDropsPendingWork() {
        val queue = ArrayDeque<() -> Unit>()
        val built = mutableListOf<Int>()
        val loader = IncrementalBuild<Int>()
        loader.start((1..3).toList(), 1, { built.addAll(it) }, { queue.addLast(it) })
        loader.cancel()
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf(1), built)
    }
}
