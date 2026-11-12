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
import com.xnotes.platform.CanvasTextRaster

/** End-to-end GL test: color glyphs survive a native-preview-to-texture handoff. */
class CanvasEmojiRenderTest : InstrumentationTestCase() {
    private fun tap(editor: InfiniteEditor, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        }
        for ((action, delay) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 35L)) {
            val event = MotionEvent.obtain(now, now + delay, action, 1,
                arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { editor.interaction.onTouch(event) } finally { event.recycle() }
        }
    }

    fun testEmojiDetection() {
        assertFalse(CanvasTextRaster.needsColorGlyphs("Plain text αβ हिन्दी مرحبا"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("😀"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("🇧🇷"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("❤️"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("1⃣"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("👩🏽‍💻"))
    }

    fun testColorEmojiSurvivesGlHandoff() {
        val target = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_MAIN).setClassName(target.packageName, "com.xnotes.EditorActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ) as Activity
        val editor = InfiniteEditor(target)
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(editor.surfaces)
            }
            instrumentation.waitForIdleSync()
            var item: TextItem? = null
            instrumentation.runOnMainSync {
                editor.viewport.zoom = 1.0
                editor.viewport.scrollX = 0.0
                editor.viewport.scrollY = 0.0
                editor.armTool(Tool.TEXT_BOX)
                tap(editor, 240f, 260f)
                editor.updateEditingText("😀 🇧🇷 ❤️ 👩🏽‍💻")
                editor.commitText()
                item = editor.document.items.filterIsInstance<TextItem>().single()
                assertTrue(CanvasTextRaster.needsColorGlyphs(item!!.text))
            }
            val preview = editor.surfaces.getChildAt(2)
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < deadline) {
                var hidden = false
                instrumentation.runOnMainSync { hidden = preview.visibility == android.view.View.GONE }
                if (hidden) break
                SystemClock.sleep(50)
            }
            assertEquals("Emoji native preview must hand off to GL",
                android.view.View.GONE, preview.visibility)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            val shot = instrumentation.uiAutomation.takeScreenshot()
                ?: throw AssertionError("Screenshot unavailable")
            try {
                val start = editor.viewport.contentToViewport(item!!.pos)
                val left = start.x.toInt().coerceIn(0, shot.width - 1)
                val top = start.y.toInt().coerceIn(0, shot.height - 1)
                val right = (left + 600).coerceAtMost(shot.width)
                val bottom = (top + 160).coerceAtMost(shot.height)
                var colored = 0
                var opaque = 0
                for (y in top until bottom step 2) for (x in left until right step 2) {
                    val pixel = shot.getPixel(x, y)
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    if (maxOf(r, g, b) - minOf(r, g, b) > 35 && minOf(r, g, b) < 220) colored++
                    if (minOf(r, g, b) < 170) opaque++
                }
                android.util.Log.i("PR428_EMOJI", "colorPixels=$colored inkPixels=$opaque crop=$left,$top..$right,$bottom")
                assertTrue("Emoji must retain visible chromatic pixels after GL handoff", colored > 25)
                assertTrue("Text image must not be blank after GL handoff", opaque > 100)
            } finally {
                shot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
