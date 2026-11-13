package com.xnotes.core.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class CopyOverTest {

    private fun file(text: String): File =
        File.createTempFile("copy", null).apply { writeText(text); deleteOnExit() }

    @Test
    fun `a whole temp replaces what the destination held`() {
        val temp = file("the note as it is now")
        val note = file("the note as it was, and longer than the new one")

        assertTrue(copyOver(temp, 8) { FileOutputStream(note) })

        assertArrayEquals(temp.readBytes(), note.readBytes())
    }

    @Test
    fun `a temp that has gone leaves the destination whole`() {
        val temp = file("the note as it is now")
        val note = file("the note as it was")
        var opened = false
        temp.delete() // what a temp dir purged under a save in flight does

        assertFalse(copyOver(temp, 8) { opened = true; FileOutputStream(note) })

        assertFalse("opening the note is what truncates it", opened)
        assertEquals("the note as it was", note.readText())
    }

    @Test
    fun `a destination that will not open is a failed copy`() {
        assertFalse(copyOver(file("the note as it is now"), 8) { null })
    }
}
