package com.xnotes.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ForkLedgerTest {

    private val original = "content://notes/document/Embedded%2Fweek-02.xnote"
    private val fork = "content://notes/document/Embedded%2Fweek-02_1.xnote"
    private val forkOfFork = "content://notes/document/Embedded%2Fweek-02_2.xnote"
    private val note = Any()

    private fun ForkLedger.forkOriginal() = record(original, fork, note, "week-02_1", "week-02")

    private fun ForkLedger.forkTheFork() = record(fork, forkOfFork, note, "week-02_2", "week-02")

    @Test
    fun `a late save aimed at the forked file lands in the fork`() {
        val ledger = ForkLedger().apply { forkOriginal() }
        assertEquals(fork, ledger.target(original, note))
        assertEquals(fork, ledger.target(fork, note))
    }

    @Test
    fun `a chain of forks is followed to the newest`() {
        val ledger = ForkLedger().apply { forkOriginal(); forkTheFork() }
        assertEquals(forkOfFork, ledger.target(original, note))
        assertEquals(forkOfFork, ledger.target(fork, note))
    }

    @Test
    fun `another document opened on the original saves to the original`() {
        val ledger = ForkLedger().apply { forkOriginal() }
        assertEquals(original, ledger.target(original, Any()))
    }

    @Test
    fun `a file that was never forked is its own target`() {
        assertEquals(original, ForkLedger().target(original, note))
    }

    @Test
    fun `a fork created at a reused uri does not loop`() {
        val ledger = ForkLedger().apply { forkOriginal() }
        ledger.record(fork, original, note, "week-02", "week-02")
        assertEquals(original, ledger.target(original, note))
        assertEquals(original, ledger.target(fork, note))
    }

    @Test
    fun `a fork of a fork is numbered from the first name`() {
        val ledger = ForkLedger().apply { forkOriginal() }
        assertEquals("week-02", ledger.base(fork, "week-02_1"))
        ledger.forkTheFork()
        assertEquals("week-02", ledger.base(forkOfFork, "week-02_2"))
    }

    @Test
    fun `a renamed fork is numbered from its new name`() {
        val ledger = ForkLedger().apply { forkOriginal() }
        assertEquals("lecture notes", ledger.base(fork, "lecture notes"))
    }

    @Test
    fun `a note whose own name ends in a number keeps it`() {
        assertEquals("chapter_3", ForkLedger().base(original, "chapter_3"))
    }
}
