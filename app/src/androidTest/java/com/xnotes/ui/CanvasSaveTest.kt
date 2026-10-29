package com.xnotes.ui

import android.net.Uri
import android.test.InstrumentationTestCase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CanvasSaveTest : InstrumentationTestCase() {
    fun testUnsavedCanvasIsOnlyClearedAfterSuccessfulSave() {
        val context = instrumentation.targetContext
        val target = File(context.cacheDir, "pr359-canvas-save.xcanvas")
        val latch = CountDownLatch(1)
        var saved = false
        lateinit var editor: Editor
        instrumentation.runOnMainSync {
            editor = Editor(context)
            editor.newCanvas()
            editor.infinite.document.dirty = true
            editor.saveCanvasToThen(Uri.fromFile(target).toString(), "test.xcanvas") {
                saved = it
                latch.countDown()
            }
        }
        assertTrue("save timed out", latch.await(30, TimeUnit.SECONDS))
        assertTrue("save failed", saved)
        assertTrue("canvas file is empty", target.length() > 0)
        instrumentation.runOnMainSync {
            assertFalse(editor.infinite.document.dirty)
            assertEquals(Uri.fromFile(target).toString(), editor.infinite.document.path)
        }
    }

    fun testFailedSaveKeepsCanvasDirty() {
        val latch = CountDownLatch(1)
        var saved = true
        lateinit var editor: Editor
        instrumentation.runOnMainSync {
            editor = Editor(instrumentation.targetContext)
            editor.newCanvas()
            editor.infinite.document.dirty = true
            editor.saveCanvasToThen("content://invalid.pr359.provider/missing", "test.xcanvas") {
                saved = it
                latch.countDown()
            }
        }
        assertTrue("save timed out", latch.await(30, TimeUnit.SECONDS))
        assertFalse(saved)
        instrumentation.runOnMainSync { assertTrue(editor.infinite.document.dirty) }
    }
    fun testNewCanvasAutosavesIntoConfiguredFolder() {
        val latch = CountDownLatch(1)
        var saved = false
        lateinit var editor: Editor
        instrumentation.runOnMainSync {
            editor = Editor(instrumentation.targetContext)
            editor.useInternalStorage()
            editor.newCanvas()
            editor.infinite.document.dirty = true
            editor.saveNewCanvasToThen { saved = it; latch.countDown() }
        }
        assertTrue("save timed out", latch.await(30, TimeUnit.SECONDS))
        assertTrue("new canvas was not saved", saved)
        instrumentation.runOnMainSync {
            val doc = editor.infinite.document
            assertFalse(doc.dirty)
            assertNotNull(doc.path)
            assertTrue(doc.displayName!!.endsWith(".xcanvas"))
            assertNotNull(instrumentation.targetContext.contentResolver.openInputStream(Uri.parse(doc.path)).also { it?.close() })
        }
    }

    fun testNewCanvasRemainsDirtyWhenFolderIsUnavailable() {
        val latch = CountDownLatch(1)
        var saved = true
        lateinit var editor: Editor
        instrumentation.runOnMainSync {
            editor = Editor(instrumentation.targetContext)
            editor.updateBrowseRoot("content://invalid.pr359.provider/tree/root")
            editor.newCanvas()
            editor.infinite.document.dirty = true
            editor.saveNewCanvasToThen { saved = it; latch.countDown() }
        }
        assertTrue("save timed out", latch.await(30, TimeUnit.SECONDS))
        assertFalse(saved)
        instrumentation.runOnMainSync {
            assertTrue(editor.infinite.document.dirty)
            assertNull(editor.infinite.document.path)
        }
    }
}
