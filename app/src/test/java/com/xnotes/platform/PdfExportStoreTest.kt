package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PdfExportStoreTest {

    private fun json(): JsonStore = JsonStore(File(Files.createTempDirectory("xnotes-exports").toFile(), "pdf_exports.json"))

    @Test fun whatWaitsIsStillWaitingAfterARelaunch() {
        val json = json()
        PdfExportStore(json).wait("auth|a.pdf", "content://auth/a.pdf", "a.pdf")

        val back = PdfExportStore(json).all().single()
        assertEquals("auth|a.pdf", back.key)
        assertEquals("content://auth/a.pdf", back.uri)
        assertEquals("a.pdf", back.name)
        assertTrue(back.waits)
        assertNull(back.staged)
    }

    @Test fun anExportedFileLeavesTheList() {
        val json = json()
        val store = PdfExportStore(json)
        store.wait("k", "u", "n.pdf")
        store.done("k")

        assertTrue(PdfExportStore(json).all().isEmpty())
    }

    @Test fun aStagedCopyOutlivesTheExportItCameFrom() {
        val json = json()
        val store = PdfExportStore(json)
        store.wait("k", "u", "n.pdf")
        store.stage("k", "u", "n.pdf", PdfExportStore.Staged("abc.pdf", 1234L, "feed"))
        store.done("k")

        val back = PdfExportStore(json).get("k")!!
        assertFalse(back.waits)
        assertEquals("abc.pdf", back.staged!!.file)
        assertEquals(1234L, back.staged!!.oldSize)
        assertEquals("feed", back.staged!!.oldEnd)

        PdfExportStore(json).also { it.unstage("k") }
        assertTrue(PdfExportStore(json).all().isEmpty())
    }

    @Test fun aFileSavedAgainWhileItsCopyIsStagedWaitsAgain() {
        val json = json()
        val store = PdfExportStore(json)
        store.stage("k", "u", "n.pdf", PdfExportStore.Staged("abc.pdf", 10L, "e"))
        store.wait("k", "u", "n.pdf")
        store.unstage("k")

        val back = PdfExportStore(json).get("k")!!
        assertTrue(back.waits)
        assertNull(back.staged)
    }

    @Test fun aRenamedFileKeepsItsPlace() {
        val json = json()
        val store = PdfExportStore(json)
        store.wait("auth|a.pdf", "content://auth/a.pdf", "a.pdf")
        store.move("auth|a.pdf", "auth|b.pdf", "content://auth/b.pdf", "b.pdf")

        val back = PdfExportStore(json).all().single()
        assertEquals("auth|b.pdf", back.key)
        assertEquals("content://auth/b.pdf", back.uri)
        assertEquals("b.pdf", back.name)
        assertTrue(back.waits)
    }

    @Test fun aDeletedFileIsForgotten() {
        val json = json()
        val store = PdfExportStore(json)
        store.wait("k", "u", "n.pdf")
        store.remove("k")
        store.remove("never there")

        assertTrue(PdfExportStore(json).all().isEmpty())
    }
}
