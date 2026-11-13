package com.xnotes.ui

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.tools.Tool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Simulates a slow image decode occupying the canvas's image worker while text is edited. */
class CanvasTextQueueContentionTest : InstrumentationTestCase() {
    private fun tap(editor: InfiniteEditor) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = 230f; y = 230f; pressure = 1f; size = 1f
        }
        for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 30L)) {
            val event = MotionEvent.obtain(now, now + offset, action, 1,
                arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { editor.interaction.onTouch(event) } finally { event.recycle() }
        }
    }

    fun testTextHandoffNotQueuedBehindSlowImageDecoding() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_MAIN).setClassName(context.packageName, "com.xnotes.EditorActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ) as Activity
        val editor = InfiniteEditor(context)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(editor.surfaces)
                editor.armTool(Tool.TEXT_BOX)
            }
            instrumentation.waitForIdleSync()
            // Diagnostic hook into the existing image queue: models a long raster/SVG decode.
            val field = InfiniteEditor::class.java.getDeclaredField("decodeExecutor").apply {
                isAccessible = true
            }
            val imageWorker = field.get(editor) as ExecutorService
            imageWorker.execute {
                started.countDown()
                release.await(4, TimeUnit.SECONDS)
            }
            assertTrue("Decode task did not start", started.await(2, TimeUnit.SECONDS))
            val begin = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                tap(editor)
                editor.updateEditingText("A text box must remain independent of a stalled image queue.")
                editor.commitText()
            }
            val preview = editor.surfaces.getChildAt(2)
            var handoff = false
            while (SystemClock.elapsedRealtime() - begin < 1800) {
                instrumentation.runOnMainSync {
                    handoff = preview.visibility == android.view.View.GONE
                }
                if (handoff) break
                SystemClock.sleep(20)
            }
            val elapsed = SystemClock.elapsedRealtime() - begin
            Log.i("PR428_QUEUE", "handoff_while_image_worker_blocked=$handoff elapsed_ms=$elapsed")
            assertTrue("Text mesh should not wait for the image decode worker", handoff)
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
