package com.xnotes.ui

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The background work of editors whose activity is gone. Android can rebuild the activity inside a
 * living process, and the editor it leaves behind may still be writing the note and the session it
 * saved on pause. The next editor purges the temp dirs those writes read from and restores the
 * session they produce, so it is built only once they are done.
 */
internal class RetiredEditors {

    private val jobs = CopyOnWriteArrayList<CompletableJob>()

    /** Take [job], the parent of a retired editor's background work, to wait for. */
    fun add(job: CompletableJob) {
        jobs.removeAll { it.isCompleted }
        jobs += job
    }

    /**
     * Lets every retired editor finish what it has going out, and closes it to anything new: work
     * launched under a completed job is cancelled before it runs. Returns without suspending when
     * none is busy. Gives up after [capMs]: past that something is stuck, and a launch must not be.
     */
    suspend fun await(capMs: Long = CAP_MS) {
        jobs.removeAll { it.isCompleted }
        if (jobs.isEmpty()) return
        jobs.forEach { it.complete() }
        withTimeoutOrNull(capMs) { jobs.forEach { it.join() } }
        jobs.removeAll { it.isCompleted }
    }

    companion object {
        /** The editors retired in this process. */
        val inProcess = RetiredEditors()

        /** Well past the longest save. */
        private const val CAP_MS = 30_000L
    }
}
