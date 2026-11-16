package com.xnotes.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import com.xnotes.core.model.TextItem
import com.xnotes.core.tools.Tool
import com.xnotes.platform.CanvasTextRaster

/** End-to-end GL test: color glyphs survive a native-preview-to-texture handoff. */
class CanvasEmojiRenderTest : InstrumentationTestCase() {
    private fun tap(editor: InfiniteEditor, x: Float, y: Float) =
        CanvasTextTestInput.tap(editor, x, y, duration = 35L)

    fun testEmojiDetection() {
        assertFalse(CanvasTextRaster.needsColorGlyphs("Plain text αβ हिन्दी مرحبا"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("😀"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("🇧🇷"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("❤️"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("1⃣"))
        assertTrue(CanvasTextRaster.needsColorGlyphs("👩🏽‍💻"))
    }

    fun testSwitchEmojiToPlainAndBackDoesNotAccumulatePngs() {
        val target = instrumentation.targetContext
        val folder = java.io.File(target.cacheDir, "canvas-text-raster")
        fun currentFiles() = folder.listFiles()?.map { it.name }?.toSet().orEmpty()
        val before = currentFiles()
        val activity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_MAIN).setClassName(target.packageName, "com.xnotes.EditorActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ) as Activity
        val editor = InfiniteEditor(target)
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(editor.surfaces)
                editor.viewport.zoom = 1.0
                editor.armTool(Tool.TEXT_BOX)
            }
            instrumentation.waitForIdleSync()
            fun edit(value: String, x: Float = 240f, y: Float = 260f) {
                instrumentation.runOnMainSync {
                    tap(editor, x, y)
                    assertNotNull(editor.editingField)
                    editor.updateEditingText(value)
                    editor.commitText()
                }
                val preview = editor.surfaces.getChildAt(2)
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    if (preview.visibility == android.view.View.GONE) break
                    SystemClock.sleep(40)
                }
                assertEquals("GL handoff failed for " + value, android.view.View.GONE, preview.visibility)
            }

            edit("First 😀 emoji")
            assertEquals(1, editor.document.items.filterIsInstance<TextItem>().size)
            assertEquals(1, (currentFiles() - before).size)
            edit("Changed 🇧🇷 emoji", x = 250f, y = 270f)
            assertEquals("Editing the same item should replace its PNG, not leak another",
                1, (currentFiles() - before).size)
            edit("Now plain text", x = 250f, y = 270f)
            assertEquals("Switching back to vectors must delete emoji PNGs",
                emptySet<String>(), currentFiles() - before)
            assertEquals("Now plain text", editor.document.items.filterIsInstance<TextItem>().single().text)
            edit("Emoji returned 😀", x = 250f, y = 270f)
            assertEquals(1, (currentFiles() - before).size)
            instrumentation.runOnMainSync {
                editor.document.remove(editor.document.items.filterIsInstance<TextItem>().single())
            }
            instrumentation.waitForIdleSync()
            assertTrue(editor.document.items.filterIsInstance<TextItem>().isEmpty())
            assertEquals("Deleting the textbox must release its cached PNG",
                before, currentFiles())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun testColorEmojiSurvivesGlHandoff() = verifyEmojiHandoff(1.0)

    fun testColorEmojiSurvivesGlHandoffAtHighZoom() = verifyEmojiHandoff(2.0)

    private fun verifyEmojiHandoff(zoom: Double) {
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
                editor.viewport.zoom = zoom
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
