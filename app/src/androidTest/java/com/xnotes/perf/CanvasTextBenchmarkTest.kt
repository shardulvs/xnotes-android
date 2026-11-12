package com.xnotes.perf

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.PowerManager
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.tools.Tool
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.InfiniteEditor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Diagnostic microbenchmarks, not pass/fail speed limits. Run alone on a device; read PR428_BENCH
 * from logcat. Outlined shapes already contain glyph geometry, unlike native editable text.
 */
class CanvasTextBenchmarkTest : InstrumentationTestCase() {
    private val measurer = AndroidTextMeasurer()
    private val style = TextStyle(Rgba(42, 60, 90, 255), 18.0, FontFace.MONO)
    private val codec = CanvasCodec(AndroidImageCodec(), measurer)
    private var checksum = 0L

    private fun items(native: Boolean, count: Int): List<CanvasItem> = (0 until count).map { i ->
        val label = "Editable text on an infinite canvas, item " + i + "."
        val pos = Pt((i % 3) * 350.0, (i / 3) * 180.0)
        if (native) TextItem(pos, 320.0, 0.0, label, style.rgba, style.pointSize, style.face, measurer)
        else ShapeLabel(label, style, measurer)
            .asGlyphs(Rect(pos.x, pos.y, 320.0, 160.0), { it }, 0.0) ?: error("Missing glyphs")
    }

    private fun bench(name: String, runs: Int = 7, warmup: Int = 1, block: () -> Long) {
        repeat(warmup) { checksum += block() }
        val samples = LongArray(runs) {
            val start = SystemClock.elapsedRealtimeNanos()
            checksum += block()
            SystemClock.elapsedRealtimeNanos() - start
        }.sorted()
        val p95 = samples[((runs * .95).toInt()).coerceIn(0, runs - 1)]
        Log.i("PR428_BENCH", name + " n=" + runs + " median_ms=" + (samples[runs / 2] / 1e6) +
            " p95_ms=" + (p95 / 1e6) + " checksum=" + checksum)
    }

    fun testMeshingReflowAndPersistence() {
        for (count in listOf(1, 3)) for (native in listOf(true, false)) {
            val kind = if (native) "editable" else "preoutlined"
            val list = items(native, count)
            // No glyph-mesh cache: each call does the work again.
            bench(kind + "/" + count + " mesh") {
                list.sumOf { item -> ItemMesher.mesh(item)?.parts?.sumOf { it.mesh.indices.size.toLong() } ?: 0L }
            }
            val doc = InfiniteDocument().apply { list.forEach(::add) }
            bench(kind + "/" + count + " save", runs = 5) {
                ByteArrayOutputStream().also { codec.write(doc, it) }.size().toLong()
            }
            val bytes = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()
            Log.i("PR428_BENCH", kind + "/" + count + " compressed_bytes=" + bytes.size)
            if (count == 1) bench(kind + "/1 load", runs = 3, warmup = 0) {
                codec.read(ByteArrayInputStream(bytes)).itemCount.toLong()
            }
        }
        val item = items(true, 1).single() as TextItem
        bench("editable/1 width-change-plus-mesh", runs = 11) {
            item.width = if (item.width == 320.0) 230.0 else 320.0
            ItemMesher.mesh(item)?.parts?.sumOf { it.mesh.indices.size.toLong() } ?: 0L
        }
    }

    fun testLongTextGeometryBreakdown() {
        val content = ("Updated paragraph 0 with word wrapping. ").repeat(25)
        val text = TextItem(Pt(0.0, 0.0), 320.0, 0.0, content,
            style.rgba, style.pointSize, style.face, measurer)
        val contours = text.glyphContours()
        bench("long/outline", runs = 5) {
            text.glyphContours().size.toLong()
        }
        bench("long/outline-legacy", runs = 5) {
            measurer.outline(text.text, text.font, text.width, TextItem.FLAGS).size.toLong()
        }
        bench("long/contours-to-mesh", runs = 5) {
            com.xnotes.core.infinite.ShapeTessellator.textPart(contours, text.rgba, fastLongText = true) { it }
                ?.mesh?.indices?.size?.toLong() ?: 0L
        }
        bench("long/complete-mesh", runs = 5) {
            ItemMesher.mesh(text)?.parts?.sumOf { it.mesh.indices.size.toLong() } ?: 0L
        }
        Log.i("PR428_BENCH", "long/contours n=" + contours.size)
    }

    private fun tap(editor: InfiniteEditor, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 30 else 0,
                action, 1, arrayOf(properties), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { editor.interaction.onTouch(event) } finally { event.recycle() }
        }
    }

    fun testNativeToGlHandoffLatency() {
        val context = instrumentation.targetContext
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertTrue("Unlock and wake the display before the GL benchmark",
            power.isInteractive && !keyguard.isKeyguardLocked)
        val activity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_MAIN).setClassName(context.packageName, "com.xnotes.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ) as Activity
        val editor = InfiniteEditor(context)
        val elapsed = LongArray(5)
        val commit = LongArray(5)
        try {
            instrumentation.runOnMainSync {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                editor.newCanvas()
                activity.setContentView(editor.surfaces)
                editor.armTool(Tool.TEXT_BOX)
            }
            instrumentation.waitForIdleSync()
            repeat(elapsed.size) { i ->
                var start = 0L
                instrumentation.runOnMainSync {
                    tap(editor, 210f, 215f)
                    assertNotNull("Text editor was not opened", editor.editingField)
                    editor.updateEditingText(("Updated paragraph " + i + " with word wrapping. ").repeat(25))
                    start = SystemClock.elapsedRealtimeNanos()
                    editor.commitText()
                    commit[i] = SystemClock.elapsedRealtimeNanos() - start
                    val boxes = editor.document.items.filterIsInstance<TextItem>()
                    assertEquals("The benchmark should edit one textbox", 1, boxes.size)
                    assertTrue(boxes.single().text.startsWith("Updated paragraph " + i))
                }
                val preview = editor.surfaces.getChildAt(2)
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (true) {
                    var hidden = false
                    instrumentation.runOnMainSync { hidden = preview.visibility == android.view.View.GONE }
                    if (hidden) break
                    assertTrue("GL handoff never finished for sample " + i, SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(10)
                }
                elapsed[i] = SystemClock.elapsedRealtimeNanos() - start
                // Separate CPU meshing time from GL scheduling and first-frame warmup.
                val cpuMs = editor.view.stats.lastTessellateMs
                Log.i("PR428_BENCH", "handoff/sample=" + i +
                    " elapsed_ms=" + elapsed[i] / 1e6 +
                    " commit_ms=" + commit[i] / 1e6 + " recent_mesh_ms=" + cpuMs)
            }
            fun report(name: String, values: LongArray) {
                val sorted = values.sorted()
                Log.i("PR428_BENCH", name + " n=" + values.size +
                    " median_ms=" + (sorted[values.size / 2] / 1e6) +
                    " p95_ms=" + (sorted.last() / 1e6))
            }
            report("commit-main-thread", commit)
            report("commit-to-gl-handoff", elapsed)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
