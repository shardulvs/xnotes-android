package com.xnotes.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class StagedCopyTest {

    private fun file(bytes: ByteArray): File = File.createTempFile("staged", ".bin").apply { writeBytes(bytes); deleteOnExit() }

    private fun bytes(seed: Int, size: Int): ByteArray = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun endHash(f: File): String = RandomAccessFile(f, "r").use { StagedCopy.endHash(it.channel) }

    private fun state(target: File, staged: File, old: File): StagedCopy =
        RandomAccessFile(target, "r").use { t ->
            RandomAccessFile(staged, "r").use { s -> StagedCopy.of(t.channel, s.channel, old.length(), endHash(old)) }
        }

    private val old = file(bytes(1, 150_000))
    private val staged = file(bytes(2, 300_000))

    @Test fun aFileThatIsTheCopyIsDone() {
        assertEquals(StagedCopy.DONE, state(file(staged.readBytes()), staged, old))
    }

    @Test fun aFileStillAsItWasIsCopiedAgain() {
        assertEquals(StagedCopy.REDO, state(file(old.readBytes()), staged, old))
    }

    @Test fun aCopyCutShortAnywhereIsCopiedAgain() {
        val whole = staged.readBytes()
        for (cut in listOf(0, 1, 3000, 70_000, whole.size - 1)) {
            assertEquals("cut at $cut", StagedCopy.REDO, state(file(whole.copyOfRange(0, cut)), staged, old))
        }
    }

    @Test fun aFileSomethingElseWroteIsLeft() {
        // Another length, and the copy's length with other bytes at either end.
        assertEquals(StagedCopy.LEAVE, state(file(bytes(3, 200_000)), staged, old))
        val head = staged.readBytes().also { it[10] = (it[10] + 1).toByte() }
        assertEquals(StagedCopy.LEAVE, state(file(head), staged, old))
        val end = staged.readBytes().also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
        assertEquals(StagedCopy.LEAVE, state(file(end), staged, old))
        // The old length with other bytes is not the old file.
        assertEquals(StagedCopy.LEAVE, state(file(bytes(4, 150_000)), staged, old))
    }

    @Test fun aSmallFileIsToldTheSameWay() {
        val smallOld = file(bytes(5, 100))
        val smallStaged = file(bytes(6, 900))
        assertEquals(StagedCopy.DONE, state(file(smallStaged.readBytes()), smallStaged, smallOld))
        assertEquals(StagedCopy.REDO, state(file(smallOld.readBytes()), smallStaged, smallOld))
        assertEquals(StagedCopy.REDO, state(file(smallStaged.readBytes().copyOfRange(0, 400)), smallStaged, smallOld))
    }
}
