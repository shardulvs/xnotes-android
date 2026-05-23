package com.xnotes.platform

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.format.DocumentCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionStoreTest {

    private fun codec() = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
    private fun tempDir(): File = Files.createTempDirectory("xnotes-session").toFile()

    @Test fun roundTripsDocumentIdentityAndViewState() {
        val dir = tempDir()
        val doc = Document.blank(2).apply {
            path = "content://com.android.externalstorage.documents/document/primary%3ADocs%2FNotes.xnote"
            displayName = "Notes.xnote"
            dirty = true
        }
        SessionStore(dir, codec()).save(doc, zoom = 2.04, scrollX = 120.0, scrollY = 340.0, zoomLocked = true, writeDocument = true)

        val snap = SessionStore(dir, codec()).load()!!
        assertEquals(2, snap.document.pages.size)
        assertEquals(doc.path, snap.document.path)
        assertEquals("Notes.xnote", snap.document.displayName)
        assertTrue(snap.document.dirty)
        assertEquals(2.04, snap.zoom, 1e-9)
        assertEquals(120.0, snap.scrollX, 1e-9)
        assertEquals(340.0, snap.scrollY, 1e-9)
        assertTrue(snap.zoomLocked)
    }

    @Test fun loadReturnsNullWhenNoSession() {
        assertNull(SessionStore(tempDir(), codec()).load())
    }

    @Test fun viewStateRefreshKeepsExistingDocument() {
        val dir = tempDir()
        SessionStore(dir, codec()).save(Document.blank(3), zoom = 1.0, scrollX = 0.0, scrollY = 0.0, zoomLocked = false, writeDocument = true)
        // A metadata-only update (writeDocument = false) must not lose the document.
        SessionStore(dir, codec()).save(Document.blank(9), zoom = 1.5, scrollX = 10.0, scrollY = 20.0, zoomLocked = false, writeDocument = false)

        val snap = SessionStore(dir, codec()).load()!!
        assertEquals(3, snap.document.pages.size) // the original document, not the 9-page one
        assertEquals(1.5, snap.zoom, 1e-9) // but the refreshed view state
    }
}
