package com.xnotes.perf

import junit.framework.TestCase
import android.util.Log
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
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/** On-device benchmark. Does not measure framebuffer time. */
class NativeTextBenchmarkTest : TestCase() {
    private val measurer = AndroidTextMeasurer()
    private val codec = CanvasCodec(AndroidImageCodec(), measurer)
    private val message = "XNotes editable canvas text and notes. "
    private val style = TextStyle(Rgba(42, 60, 90, 255), 18.0, FontFace.MONO)
    private var sink = 0L

    private fun items(native: Boolean, count: Int): List<CanvasItem> =
        (0 until count).map { n ->
            val text = message + "item $n"
            val pos = Pt((n % 5) * 350.0, (n / 5) * 180.0)
            if (native) TextItem(pos, 320.0, 0.0, text, style.rgba, style.pointSize, style.face, measurer)
            else ShapeLabel(text, style, measurer)
                .asGlyphs(Rect(pos.x, pos.y, 320.0, 160.0), { it }, 0.0) ?: error("No glyphs")
        }

    private fun sample(name: String, runs: Int = 9, block: () -> Long) {
        repeat(2) { sink += block() }
        val times = LongArray(runs) {
            val before = System.nanoTime()
            sink += block()
            System.nanoTime() - before
        }.sorted()
        Log.i("XNOTES_BENCH", "$name median_ms=${times[times.size / 2] / 1e6} p95_ms=${times[ceil(times.size * .95).toInt() - 1] / 1e6} runs=$runs checksum=$sink")
    }

    fun testTextGeometryAndPersistence() {
        for (count in listOf(1, 5)) for (native in listOf(false, true)) {
            val kind = if (native) "native" else "outlined"
            val collection = items(native, count)
            // Prepare distinct items outside the timed region: every cold run actually builds
            // geometry, whereas hot runs can hit the per-item mesh cache.
            val coldBatches = List(9) { items(native, count) }
            var coldIndex = 0
            sample("$kind/$count cold-mesh", 7) {
                val batch = coldBatches[coldIndex++]
                batch.sumOf { item -> ItemMesher.mesh(item)?.parts?.sumOf { it.mesh.indices.size.toLong() } ?: 0L }
            }
            sample("$kind/$count hot-mesh") {
                collection.sumOf { item -> ItemMesher.mesh(item)?.parts?.sumOf { it.mesh.indices.size.toLong() } ?: 0L }
            }
            val doc = InfiniteDocument()
            collection.forEach(doc::add)
            sample("$kind/$count serialize") {
                ByteArrayOutputStream().also { codec.write(doc, it) }.size().toLong()
            }
            val bytes = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()
            Log.i("XNOTES_BENCH", "$kind/$count size_bytes=${bytes.size}")
            sample("$kind/$count deserialize", 3) {
                codec.read(ByteArrayInputStream(bytes)).itemCount.toLong()
            }
        }
        val text = items(true, 1).single() as TextItem
        sample("native/1 width-reflow-and-mesh", 35) {
            text.width = if (text.width == 320.0) 240.0 else 320.0
            ItemMesher.mesh(text)?.parts?.sumOf { it.mesh.indices.size.toLong() } ?: 0L
        }
        val glyph = items(false, 1).single()
        sample("outlined/1 mesh-existing-geometry", 35) {
            ItemMesher.mesh(glyph)?.parts?.sumOf { it.mesh.indices.size.toLong() } ?: 0L
        }
    }
}
