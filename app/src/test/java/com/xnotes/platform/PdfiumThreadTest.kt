package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PdfiumThreadTest {

    /** A worker held by its first job until the returned gate opens. */
    private fun heldWorker(): Pair<PdfiumThread, CountDownLatch> {
        val worker = PdfiumThread("test-pdfium")
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        worker.submit(PdfPriority.INTERACTIVE) {
            started.countDown()
            gate.await()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        return worker to gate
    }

    @Test fun runsTheMostUrgentJobFirstAndInOrderWithinAPriority() {
        val (worker, gate) = heldWorker()
        val order = Collections.synchronizedList(ArrayList<String>())
        val tasks = listOf(
            worker.submit(PdfPriority.WARM) { order += "warm" },
            worker.submit(PdfPriority.THUMBNAIL) { order += "thumb 1" },
            worker.submit(PdfPriority.INTERACTIVE) { order += "interactive" },
            worker.submit(PdfPriority.THUMBNAIL) { order += "thumb 2" },
            worker.submit(PdfPriority.VISIBLE_RENDER) { order += "visible" },
        )
        gate.countDown()
        tasks.forEach { it.await() }
        assertEquals(listOf("interactive", "visible", "thumb 1", "thumb 2", "warm"), order)
    }

    @Test fun cancellingDropsAQueuedJobAndFreesItsWaiterWhileTheThreadIsBusy() {
        val (worker, gate) = heldWorker()
        val token = CancelToken()
        var ran = false
        val task = worker.submit(PdfPriority.THUMBNAIL, token) { ran = true; 1 }
        var result: Int? = 0
        val returned = CountDownLatch(1)
        Thread {
            result = task.await()
            returned.countDown()
        }.start()
        token.cancel()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        assertNull(result)
        gate.countDown()
        worker.call(PdfPriority.WARM) {}
        assertFalse(ran)
    }

    @Test fun aJobWithACancelledTokenNeverRuns() {
        val worker = PdfiumThread("test-pdfium")
        val live = CancelToken()
        val dead = CancelToken().apply { cancel() }
        assertNull(worker.call(PdfPriority.INTERACTIVE, dead) { 1 })
        assertNull(worker.call(PdfPriority.INTERACTIVE, live, dead) { 1 })
        assertEquals(1, worker.call(PdfPriority.INTERACTIVE, live) { 1 })
    }

    @Test fun aJobCancelledWhileRunningStillFinishes() {
        val worker = PdfiumThread("test-pdfium")
        val token = CancelToken()
        assertEquals(3, worker.call(PdfPriority.INTERACTIVE, token) { token.cancel(); 3 })
    }

    @Test fun anInterruptedWaiterGivesUpItsJob() {
        val (worker, gate) = heldWorker()
        var ran = false
        val task = worker.submit(PdfPriority.WARM) { ran = true }
        var interrupted = false
        val returned = CountDownLatch(1)
        val waiter = Thread {
            task.await()
            interrupted = Thread.currentThread().isInterrupted
            returned.countDown()
        }
        waiter.start()
        waiter.interrupt()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        assertTrue(interrupted)
        gate.countDown()
        worker.call(PdfPriority.WARM) {}
        assertFalse(ran)
    }

    @Test fun aJobsErrorReachesItsCallerAndTheThreadLivesOn() {
        val worker = PdfiumThread("test-pdfium")
        val e = assertThrows(IllegalStateException::class.java) {
            worker.call(PdfPriority.INTERACTIVE) { error("boom") }
        }
        assertEquals("boom", e.message)
        assertEquals(2, worker.call(PdfPriority.INTERACTIVE) { 2 })
    }

    @Test fun aCallFromAJobRunsInline() {
        val worker = PdfiumThread("test-pdfium")
        assertEquals(7, worker.call(PdfPriority.WARM) { worker.call(PdfPriority.INTERACTIVE) { 7 } })
    }

    @Test fun everyJobRunsOnTheOneThreadAfterItsStartHook() {
        val seen = Collections.synchronizedList(ArrayList<String>())
        val worker = PdfiumThread("test-pdfium") { seen += "start on " + Thread.currentThread().name }
        repeat(2) { worker.call(PdfPriority.SEARCH) { seen += Thread.currentThread().name } }
        assertEquals(listOf("start on test-pdfium", "test-pdfium", "test-pdfium"), seen)
    }
}
