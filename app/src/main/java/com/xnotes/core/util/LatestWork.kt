package com.xnotes.core.util

import java.util.concurrent.Executor

/** Serial work with at most one pending request per key. Results publish monotonically; optional previews prevent starvation during continuous input.
 * [dispatch] must deliver callbacks on the owner's thread, where submit/cancel are called. */
internal class LatestWork<K>(private val executor: Executor,
    private val minimumStartIntervalMs: Long = 0L, private val dispatch: (() -> Unit) -> Unit) {
    private class Epoch(var requested: Long = 0, var published: Long = 0)
    private class Job(val epoch: Epoch, val version: Long, val previewGroup: Any?, val run: () -> Unit)
    private val pending = LinkedHashMap<K, Job>()
    private val latest = HashMap<K, Job>()
    private var draining = false

    fun <T> submit(key: K, compute: () -> T, publish: (T) -> Unit, failed: (Exception) -> Unit,
        previewGroup: Any? = null, discard: ((T) -> Unit)? = null) {
        val start = synchronized(this) {
            val epoch = latest[key]?.epoch ?: Epoch()
            val version = ++epoch.requested
            lateinit var job: Job
            job = Job(epoch, version, previewGroup) {
                if (isCurrent(key, job)) {
                    try {
                        val result = compute()
                        dispatch {
                            if (claim(key, job, preview = true)) publish(result)
                            else discard?.invoke(result)
                        }
                    } catch (e: Exception) {
                        dispatch { if (claim(key, job, preview = false)) failed(e) }
                    }
                }
            }
            latest[key] = job
            pending[key] = job
            if (draining) false else { draining = true; true }
        }
        if (start) executor.execute { drain() }
    }

    @Synchronized
    fun cancel(key: K) {
        latest.remove(key)
        pending.remove(key)
    }

    @Synchronized
    fun clear() {
        latest.clear()
        pending.clear()
    }

    @Synchronized
    private fun isCurrent(key: K, job: Job) = latest[key] === job

    @Synchronized
    private fun claim(key: K, job: Job, preview: Boolean): Boolean {
        val current = latest[key] ?: return false
        if (current.epoch !== job.epoch || job.version <= job.epoch.published) return false
        if (current !== job && !(preview && job.previewGroup != null && job.previewGroup === current.previewGroup)) return false
        job.epoch.published = job.version
        if (current === job) latest.remove(key)
        return true
    }

    private fun drain() {
        var nextStartNs = 0L
        while (true) {
            val job = synchronized(this) {
                val iterator = pending.entries.iterator()
                if (!iterator.hasNext()) {
                    draining = false
                    null
                } else iterator.next().value.also { iterator.remove() }
            } ?: return
            // Rapid resize input must not monopolize a CPU with dozens of obsolete meshes.
            // Sleep only on this dedicated background worker, never the touch/render thread.
            if (minimumStartIntervalMs > 0) {
                val remaining = nextStartNs - System.nanoTime()
                if (remaining > 0) {
                    try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining) }
                    catch (_: InterruptedException) {
                        // An interruption skips this throttle delay, not the queue. Returning here
                        // would leave draining=true and silently stall every future submission.
                        Thread.currentThread().interrupt()
                    }
                }
                nextStartNs = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(minimumStartIntervalMs)
            }
            job.run() // checks whether newer input replaced this request while waiting
        }
    }
}
