package com.xnotes.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.model.TextItem
import com.xnotes.core.tools.Tool
import java.io.File
import java.io.FileOutputStream

/** Real GL/native-surface capture of rapid text moves; never edits a saved note. */
class CanvasTextVisualCaptureTest : InstrumentationTestCase() {
    private fun pointer(editor: InfiniteEditor, action: Int, x: Float, y: Float, down: Long, at: Long) {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val event = MotionEvent.obtain(down, at, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { editor.interaction.onTouch(event) } finally { event.recycle() }
    }

    private fun shot(name: String): File {
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
            ?: throw AssertionError("Device screenshot unavailable: $name")
        val outDir = File(instrumentation.targetContext.getExternalFilesDir(null), "pr428-visual")
        outDir.mkdirs()
        val target = File(outDir, "$name.png")
        FileOutputStream(target).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        android.util.Log.i("PR428_CAPTURE", target.absolutePath)
        return target
    }

    /** The rendered text offset and selected box must share the same content-space position. */
    private fun assertDragAligned(editor: InfiniteEditor) {
        val text = editor.document.items.filterIsInstance<TextItem>().single()
        val fallback = editor.surfaces.getChildAt(2)
        assertEquals(android.view.View.VISIBLE, fallback.visibility)
        val dx = fallback.javaClass.getMethod("getLiftDx").invoke(fallback) as Double
        val dy = fallback.javaClass.getMethod("getLiftDy").invoke(fallback) as Double
        val bounds = text.bounds()
        val box = editor.selection.box ?: throw AssertionError("Lost selection")
        assertEquals(bounds.left + dx, box.center.x - box.halfW, 0.01)
        assertEquals(bounds.top + dy, box.center.y - box.halfH, 0.01)
    }

    fun testRapidLongTextMoveVisualFrames() {
        val target = instrumentation.targetContext
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(target.packageName, "com.xnotes.EditorActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activity = instrumentation.startActivitySync(intent) as Activity
        val editor = InfiniteEditor(target)
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(editor.surfaces)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                editor.viewport.zoom = 0.75
                editor.viewport.scrollX = 0.0
                editor.viewport.scrollY = 0.0
                editor.armTool(Tool.TEXT_BOX)
                val down = SystemClock.uptimeMillis()
                pointer(editor, MotionEvent.ACTION_DOWN, 280f, 220f, down, down)
                pointer(editor, MotionEvent.ACTION_UP, 280f, 220f, down, down + 35)
                editor.updateEditingText(("A long paragraph with reflow and many words. ").repeat(50))
                editor.commitText()
                editor.armTool(Tool.SELECT)
                val item = editor.document.items.filterIsInstance<TextItem>().single()
                editor.selection.select(listOf(item))
                editor.view.publish()
            }
            SystemClock.sleep(1500)
            shot("00-before")
            // Reflow the long text with the actual right-edge handle, then drag while its
            // vector mesh is still being generated. This is the reported failure sequence.
            val rightHandle = editor.selection.handles(
                com.xnotes.core.infinite.OverlayTessellator.FRAME_MARGIN_PX / editor.viewport.zoom,
            ).first { it.id == com.xnotes.canvas.HandleId.R }.content
            val resizeAt = editor.viewport.contentToViewport(rightHandle)
            val resizeDown = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                pointer(editor, MotionEvent.ACTION_DOWN, resizeAt.x.toFloat(), resizeAt.y.toFloat(),
                    resizeDown, resizeDown)
            }
            for (step in 1..10) {
                val shift = if (step % 2 == 0) -220f else -340f
                instrumentation.runOnMainSync {
                    pointer(editor, MotionEvent.ACTION_MOVE, resizeAt.x.toFloat() + shift,
                        resizeAt.y.toFloat(), resizeDown, resizeDown + step * 46L)
                }
            }
            instrumentation.runOnMainSync {
                pointer(editor, MotionEvent.ACTION_UP, resizeAt.x.toFloat() - 220f,
                    resizeAt.y.toFloat(), resizeDown, resizeDown + 500L)
            }
            shot("00-after-reflow")
            val downTime = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                pointer(editor, MotionEvent.ACTION_DOWN, 380f, 280f, downTime, downTime)
            }
            for (i in 1..60) {
                val x = 380f + (i * 10f) + if (i % 2 == 0) 140f else -80f
                val y = 280f + (i * 5f) + if (i % 3 == 0) 100f else -40f
                instrumentation.runOnMainSync {
                    pointer(editor, MotionEvent.ACTION_MOVE, x, y, downTime, downTime + i * 9L)
                    assertDragAligned(editor)
                }
                if (i == 20 || i == 40 || i == 60) shot("01-drag-$i")
            }
            SystemClock.sleep(100)
            shot("02-held")
            instrumentation.runOnMainSync {
                pointer(editor, MotionEvent.ACTION_UP, 1120f, 680f, downTime, downTime + 555L)
            }
            shot("03-after-up")
            SystemClock.sleep(600)
            shot("04-after-settle")
            instrumentation.runOnMainSync {
                val text = editor.document.items.filterIsInstance<TextItem>().single()
                android.util.Log.i("PR428_CAPTURE", "text=${text.pos} box=${editor.selection.box} native=${editor.surfaces.getChildAt(2).visibility}")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
