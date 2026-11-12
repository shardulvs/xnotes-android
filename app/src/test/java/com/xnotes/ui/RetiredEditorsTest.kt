package com.xnotes.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a rebuilt activity relies on before it builds its editor: the one it replaces has finished
 * every write it had going out, and can start no other.
 */
class RetiredEditorsTest {

    /** An editor's background scope, as [Editor] builds it, off the main thread for the JVM. */
    private class Old {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
    }

    @Test fun nothingRetiredIsNoWait(): Unit = runBlocking {
        withTimeout(1_000) { RetiredEditors().await() }
    }

    @Test fun waitsForTheSaveStillGoingOut(): Unit = runBlocking {
        val old = Old()
        val saving = CompletableDeferred<Unit>()
        var saved = false
        old.scope.launch { saving.await(); saved = true }
        val retired = RetiredEditors().apply { add(old.job) }

        launch { delay(50); saving.complete(Unit) }
        retired.await()

        assertTrue(saved)
    }

    @Test fun waitsForWorkThatWorkGoingOutStarts(): Unit = runBlocking {
        val old = Old()
        val saving = CompletableDeferred<Unit>()
        var written = false
        // The pause's flush: it waits its turn, then launches the write as a sibling and returns.
        old.scope.launch {
            saving.await()
            old.scope.launch { delay(50); written = true }
        }
        val retired = RetiredEditors().apply { add(old.job) }

        launch { delay(50); saving.complete(Unit) }
        retired.await()

        assertTrue(written)
    }

    @Test fun waitsForWorkStartedBetweenRetiringAndTheNextActivity(): Unit = runBlocking {
        val old = Old()
        val retired = RetiredEditors().apply { add(old.job) }
        var saved = false
        old.scope.launch { delay(50); saved = true } // the UI coming down commits something

        retired.await()

        assertTrue(saved)
    }

    @Test fun aRetiredEditorStartsNothingAfterTheWait(): Unit = runBlocking {
        val old = Old()
        val retired = RetiredEditors().apply { add(old.job) }
        retired.await()
        var ran = false

        old.scope.launch { ran = true }.join()

        assertFalse(ran)
    }

    @Test fun givesUpOnWorkThatNeverEnds(): Unit = runBlocking {
        val old = Old()
        val stuck = CompletableDeferred<Unit>()
        val hung = old.scope.launch { stuck.await() }
        val retired = RetiredEditors().apply { add(old.job) }

        withTimeout(5_000) { retired.await(capMs = 50) }

        assertTrue(hung.isActive)
        stuck.complete(Unit)
    }
}
