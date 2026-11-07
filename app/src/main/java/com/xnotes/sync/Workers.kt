package com.xnotes.sync

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Runs sync work on up to [threads] threads. With one thread everything runs inline, exactly as a plain loop would. */
internal class Workers(private val threads: Int) : AutoCloseable {
    private val pool = if (threads > 1) Executors.newFixedThreadPool(threads) else null
    private val slots = Semaphore(threads)

    /** Starts [task] once a thread is free. [task] must catch its own failures. */
    fun submit(task: () -> Unit) {
        if (pool == null) return task()
        slots.acquire()
        try {
            pool.execute { try { task() } finally { slots.release() } }
        } catch (e: Throwable) {
            slots.release()
            throw e
        }
    }

    /** Waits until every task given to [submit] has finished. */
    fun awaitAll() {
        if (pool == null) return
        slots.acquire(threads)
        slots.release(threads)
    }

    /** [fn] over [items], results in the same order. All are let finish, then the first failure is thrown. */
    fun <T, R> map(items: List<T>, fn: (T) -> R): List<R> {
        if (pool == null) return items.map(fn)
        val futures = items.map { pool.submit(Callable { fn(it) }) }
        var error: Throwable? = null
        val out = futures.map { f ->
            try { f.get() } catch (e: ExecutionException) { if (error == null) error = e.cause ?: e; null }
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out as List<R>
    }

    override fun close() {
        pool?.shutdown()
    }
}
