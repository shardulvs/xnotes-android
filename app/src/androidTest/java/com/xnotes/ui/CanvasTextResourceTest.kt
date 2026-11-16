package com.xnotes.ui

import android.graphics.Color
import android.test.InstrumentationTestCase
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.TextItem
import com.xnotes.gl.CanvasScene
import com.xnotes.gl.TextureCache
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.CanvasTextRaster
import java.io.File

/** Deterministic checks for resources owned by the native-text GL fallback. */
class CanvasTextResourceTest : InstrumentationTestCase() {
    private fun text(value: String) = TextItem(Pt(0.0, 0.0), width = 320.0, text = value,
        measurer = AndroidTextMeasurer())

    fun testEmojiRasterIsTransparentBoundedAndDisposable() {
        val directory = File(instrumentation.targetContext.cacheDir, "pr428-raster-test-" + System.nanoTime())
        val result = CanvasTextRaster.render(text("😀 ❤️ 🇧🇷"), directory)
            ?: throw AssertionError("Emoji raster returned null")
        try {
            assertTrue(result.image.image.file.isFile)
            assertTrue(result.bitmap.width in 1..4096)
            assertTrue(result.bitmap.height in 1..4096)
            assertTrue(result.bitmap.width.toLong() * result.bitmap.height <= 4_200_000L)
            assertEquals(0, Color.alpha(result.bitmap.getPixel(0, 0)))
            assertTrue(result.image.rect.w > 0.0)
            assertTrue(result.image.rect.h > 0.0)
        } finally {
            val png = result.image.image.file
            result.discard()
            assertTrue(result.bitmap.isRecycled)
            assertFalse("Temporary PNG survived disposal", png.exists())
            directory.delete()
        }
    }

    fun testSmallEmojiRasterSupportsFourTimesZoomWithoutGrowingItsBounds() {
        val directory = File(instrumentation.targetContext.cacheDir, "pr428-sharp-" + System.nanoTime())
        val box = text("Emoji 😀 text")
        val result = CanvasTextRaster.render(box, directory)
            ?: throw AssertionError("Emoji raster failed")
        try {
            // Bitmap resolution increases; the model bounds/position must remain unchanged.
            assertEquals(box.width, result.image.rect.w, 0.0001)
            assertEquals(box.pos.x, result.image.rect.x, 0.0001)
            assertEquals(box.pos.y, result.image.rect.y, 0.0001)
            assertEquals("Expected 4x density for a compact textbox",
                (box.width * 4).toInt(), result.bitmap.width)
            assertTrue(result.bitmap.width <= 4096)
            assertTrue(result.bitmap.width.toLong() * result.bitmap.height <= 4_200_000L)
        } finally {
            result.discard()
            directory.delete()
        }
    }

    fun testRasterWriteFailureDoesNotLeaveTempFiles() {
        val fileNotDirectory = File.createTempFile(
            "pr428-not-a-directory-", ".tmp", instrumentation.targetContext.cacheDir,
        )
        try {
            val failed = runCatching { CanvasTextRaster.render(text("😀"), fileNotDirectory) }
            assertTrue("Writing into a regular file must fail", failed.isFailure)
            assertTrue("Do not modify the invalid cache directory", fileNotDirectory.isFile)
        } finally {
            fileNotDirectory.delete()
        }
    }

    fun testReplacingAndRemovingEmojiDeletesOldSources() {
        val directory = File(instrumentation.targetContext.cacheDir, "pr428-ownership-" + System.nanoTime())
        assertTrue(directory.mkdirs())
        val first = File(directory, "first.png").apply { writeText("first") }
        val second = File(directory, "second.png").apply { writeText("second") }
        val third = File(directory, "third.png").apply { writeText("third") }
        val owner = CanvasTextTextureFiles(CanvasScene())
        val box1 = text("😀")
        val box2 = text("🇧🇷")
        try {
            owner.track(box1, ImageData(first, 1, 1))
            owner.track(box1, ImageData(second, 1, 1))
            assertFalse("Replacing a texture must delete the previous source", first.exists())
            assertTrue(second.exists())
            owner.track(box2, ImageData(third, 1, 1))
            owner.forget(box1)
            assertFalse(second.exists())
            assertTrue("Unrelated text must keep its image", third.exists())
            owner.clear()
            assertFalse("Reset must remove the last PNG", third.exists())
            owner.clear()
        } finally {
            owner.clear()
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
        }
    }

    fun testSupersededAndResetJobsDoNotResurrectEmojiSources() {
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "canvas-text-raster")
        val before = folder.listFiles()?.map { it.name }?.toSet().orEmpty()
        val jobs = ArrayList<Runnable>()
        lateinit var controller: CanvasTextMeshController
        lateinit var item: TextItem
        instrumentation.runOnMainSync {
            val view = com.xnotes.gl.InfiniteCanvasView(context)
            val selection = com.xnotes.core.infinite.CanvasSelection(
                com.xnotes.core.infinite.InfiniteDocument(),
            )
            val preview = CanvasTextPreviewView(
                context, view.viewport, selected = { selection },
                accent = { com.xnotes.core.model.Rgba(255, 255, 255) },
                devicePxPerDp = 1.0,
            )
            controller = CanvasTextMeshController(
                CanvasScene(), view, preview, executor = java.util.concurrent.Executor { jobs.add(it) },
                onPreviewHidden = {}, context = context,
            )
            item = text("First 😀")
            controller.update(item)
            item.text = "Second 🇧🇷"
            controller.update(item)
            controller.reset()
        }
        assertEquals(2, jobs.size)
        jobs.forEach(Runnable::run)
        assertEquals("Reset must cancel old raster jobs without creating PNGs",
            before, folder.listFiles()?.map { it.name }?.toSet().orEmpty())

        jobs.clear()
        instrumentation.runOnMainSync {
            item.canvasEditingHidden = false
            controller.update(item)
            controller.beginEditing(item)
        }
        assertEquals(1, jobs.size)
        jobs.forEach(Runnable::run)
        assertEquals("Entering edit mode must cancel the previous geometry job",
            before, folder.listFiles()?.map { it.name }?.toSet().orEmpty())
    }

    /** Diagnostic, not a timing assertion: compare PNG encode cost at 2x vs 4x on device. */
    fun testEmojiTextureScaleBenchmark() {
        val directory = File(instrumentation.targetContext.cacheDir, "pr428-scale-bench-" + System.nanoTime())
        directory.mkdirs()
        try {
            for (value in listOf("Short 😀 text", ("Wrapped 😀 text. ").repeat(40))) {
                for (maxScale in listOf(2.0, 4.0)) {
                    val times = ArrayList<Long>()
                    var pixels = 0L
                    var compressed = 0L
                    repeat(5) {
                        val started = android.os.SystemClock.elapsedRealtimeNanos()
                        val result = CanvasTextRaster.render(text(value), directory, maxScale)
                            ?: throw AssertionError("Raster failed")
                        val elapsed = android.os.SystemClock.elapsedRealtimeNanos() - started
                        times.add(elapsed)
                        pixels = result.bitmap.width.toLong() * result.bitmap.height
                        compressed = result.image.image.file.length()
                        result.discard()
                    }
                    android.util.Log.i("PR428_RASTER", "chars=" + value.length +
                        " requested_scale=" + maxScale +
                        " median_ms=" + times.sorted()[times.size / 2] / 1e6 +
                        " pixels=" + pixels + " png_bytes=" + compressed)
                }
            }
        } finally {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
        }
    }

    fun testLongEmojiTextRetainsFastTwoTimesRasterPath() {
        val directory = File(instrumentation.targetContext.cacheDir, "pr428-long-scale-" + System.nanoTime())
        val box = text(("Wrapped 😀 text. ").repeat(40))
        val result = CanvasTextRaster.render(box, directory)
            ?: throw AssertionError("Long text raster failed")
        try {
            assertEquals("Long emoji text should remain 2x to keep PNG handoff affordable",
                (box.width * 2).toInt(), result.bitmap.width)
            assertEquals(box.width, result.image.rect.w, 0.0001)
            assertTrue(result.bitmap.width.toLong() * result.bitmap.height <= 4_200_000L)
        } finally {
            result.discard()
            directory.delete()
        }
    }

    fun testHugeEmojiRasterRespectsTextureBudget() {
        val directory = File(instrumentation.targetContext.cacheDir, "pr428-huge-" + System.nanoTime())
        val result = CanvasTextRaster.render(text(("Long 😀 line with wrapping. ").repeat(2500)), directory)
            ?: throw AssertionError("Large text should still rasterize")
        try {
            assertTrue(result.bitmap.width <= 4096)
            assertTrue(result.bitmap.height <= 4096)
            assertTrue(result.bitmap.width.toLong() * result.bitmap.height <= 4_200_000L)
            assertTrue(result.image.image.file.length() > 0L)
        } finally {
            result.discard()
            directory.delete()
        }
    }

    fun testDroppingPendingTextureRecyclesItsBitmap() {
        val cache = TextureCache()
        val png = File.createTempFile("pr428-image-", ".png", instrumentation.targetContext.cacheDir)
        val bitmap = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val source = ImageData(png, bitmap.width, bitmap.height)
            cache.offerBitmap(source, bitmap)
            cache.discard(source)
            assertTrue("Discarded texture must not retain its pending bitmap", bitmap.isRecycled)
            cache.discard(source)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            png.delete()
        }
    }
}
