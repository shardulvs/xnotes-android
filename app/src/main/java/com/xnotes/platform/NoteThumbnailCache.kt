package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xnotes.core.util.ThumbnailCacheFiles
import java.io.File
import java.io.FileOutputStream

// Notes and canvases share a bounded disk cache, invalidated by content or the rendered theme.
class NoteThumbnailCache(dir: File, maxFiles: Int = 256) {
    private val files = ThumbnailCacheFiles(dir, FORMAT, maxFiles)

    fun useTheme(theme: String): Long = files.useTheme(theme)

    fun isCurrent(generation: Long): Boolean = files.isCurrent(generation)

    fun load(uri: String, generation: Long): Bitmap? = files.load(uri, generation) { png ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.path, bounds)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sampleSize(bounds.outWidth, DECODE_PX)
        }
        BitmapFactory.decodeFile(png.path, opts)
    }

    private fun sampleSize(srcPx: Int, reqPx: Int): Int {
        if (srcPx <= 0 || reqPx <= 0) return 1
        var s = 1
        while (srcPx / (s * 2) >= reqPx) s *= 2
        return s
    }

    fun store(uri: String, bitmap: Bitmap, generation: Long) = files.store(uri, generation) { png ->
        FileOutputStream(png).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun remove(uri: String) = files.remove(uri)

    fun prune(keep: Set<String>) = files.prune(keep)

    private companion object {
        const val DECODE_PX = 300
        const val FORMAT = 4
    }
}
