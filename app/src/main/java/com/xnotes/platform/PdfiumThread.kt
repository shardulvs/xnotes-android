package com.xnotes.platform

import java.util.concurrent.CountDownLatch
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** How urgent a PDFium job is, most urgent first. */
enum class PdfPriority { INTERACTIVE, VISIBLE_RENDER, BG_REPAIR, SHARP, THUMBNAIL, SEARCH, WARM }

/** Cancels the PDFium jobs it is given: a queued one never runs and its waiter returns at once. */
class CancelToken {
    @Volatile var isCancelled = false
        private set

    private var hooks: ArrayList<Runnable>? = null // guarded by this

    fun cancel() {
        val run = synchronized(this) {
            if (isCancelled) return
            isCancelled = true
            hooks.also { hooks = null }
        }
        run?.forEach { it.run() }
    }

    /** Adds [hook] to run on [cancel]; false, adding nothing, when already cancelled. */
    internal fun addHook(hook: Runnable): Boolean = synchronized(this) {
        if (!isCancelled) (hooks ?: ArrayList<Runnable>(2).also { hooks = it }).add(hook)
        !isCancelled
    }

    internal fun removeHook(hook: Runnable) {
        synchronized(this) { hooks?.remove(hook) }
    }
}

/** A job queued on a [PdfiumThread]. */
class PdfiumTask<T> internal constructor(
    private val priority: PdfPriority,
    private val seq: Long,
    private val tokens: Array<out CancelToken>,
    private val body: () -> T,
    private val queue: PriorityBlockingQueue<PdfiumTask<*>>,
) : Comparable<PdfiumTask<*>> {
    private val state = AtomicInteger(QUEUED)
    private val done = CountDownLatch(1)
    private var result: T? = null // published by [done]
    private var error: Throwable? = null

    private val cancelHook = Runnable {
        if (state.compareAndSet(QUEUED, CANCELLED)) {
            queue.remove(this)
            finish()
        }
    }

    override fun compareTo(other: PdfiumTask<*>): Int =
        if (priority != other.priority) priority.compareTo(other.priority) else seq.compareTo(other.seq)

    internal fun enqueue() {
        var cancelled = false
        for (t in tokens) if (!t.addHook(cancelHook)) cancelled = true
        if (cancelled) cancelHook.run() else queue.add(this)
        // A cancel that raced the hooks above may have finished us before a later hook was added.
        if (done.count == 0L) for (t in tokens) t.removeHook(cancelHook)
    }

    internal fun run() {
        if (tokens.any { it.isCancelled }) cancelHook.run()
        if (!state.compareAndSet(QUEUED, RUNNING)) return
        try {
            result = body()
        } catch (t: Throwable) {
            error = t
        } finally {
            state.set(DONE)
            finish()
        }
    }

    private fun finish() {
        done.countDown()
        for (t in tokens) t.removeHook(cancelHook)
    }

    /** Waits for the job; null when it was cancelled first. Rethrows what the job threw. */
    fun await(): T? {
        try {
            done.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelHook.run()
            return null
        }
        error?.let { throw it }
        return result
    }

    private companion object {
        const val QUEUED = 0
        const val RUNNING = 1
        const val DONE = 2
        const val CANCELLED = 3
    }
}

/**
 * Runs PDFium jobs one at a time on one thread, the most urgent first. PDFium is not
 * thread-safe, not even across documents, so the whole process shares [shared].
 */
class PdfiumThread internal constructor(name: String, onStart: () -> Unit = {}) {
    private val queue = PriorityBlockingQueue<PdfiumTask<*>>()
    private val seq = AtomicLong()

    private val thread = Thread({
        onStart()
        while (true) queue.take().run()
    }, name).apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY // a new thread inherits its creator's, maybe a background one
        start()
    }

    /** Queues [body], which is skipped if any of [tokens] is cancelled before it starts. */
    fun <T> submit(priority: PdfPriority, vararg tokens: CancelToken, body: () -> T): PdfiumTask<T> =
        PdfiumTask(priority, seq.getAndIncrement(), tokens, body, queue).also { it.enqueue() }

    /** Runs [body] on this thread and waits for it; null when cancelled. Runs inline when already here. */
    fun <T> call(priority: PdfPriority, vararg tokens: CancelToken, body: () -> T): T? {
        if (Thread.currentThread() !== thread) return submit(priority, *tokens, body = body).await()
        return if (tokens.any { it.isCancelled }) null else body()
    }

    companion object {
        /** The process's one PDFium thread; it initializes the library before its first job. */
        val shared: PdfiumThread by lazy {
            PdfiumThread("xnotes-pdfium") { if (PdfiumNative.loaded) PdfiumNative.nativeInit() }
        }
    }
}
