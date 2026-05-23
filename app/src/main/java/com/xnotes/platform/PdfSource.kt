package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Reads and rasterizes a PDF via the framework [PdfRenderer] (PAL §14). Pages
 * are reported in PostScript points and rendered on demand into ARGB surfaces;
 * dark mode inverts the rendered pixels.
 */
class PdfSource private constructor(
    private val tempFile: File,
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    val pageCount: Int get() = renderer.pageCount

    /** Page size in points (1 pt = 1/72 inch). */
    @Synchronized
    fun pageSizePoints(index: Int): Pair<Int, Int> {
        val page = renderer.openPage(index)
        val size = page.width to page.height
        page.close()
        return size
    }

    @Synchronized
    fun renderPage(index: Int, widthPx: Int, heightPx: Int, invert: Boolean): AndroidRasterSurface? {
        if (index !in 0 until renderer.pageCount) return null
        val w = widthPx.coerceIn(1, MAX_DIM)
        val h = heightPx.coerceIn(1, MAX_DIM)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val page = renderer.openPage(index)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        if (!invert) return AndroidRasterSurface(bmp)

        val inverted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(inverted).drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(INVERT) })
        bmp.recycle()
        return AndroidRasterSurface(inverted)
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        runCatching { tempFile.delete() }
    }

    companion object {
        private const val MAX_DIM = 4096

        private val INVERT = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

        /** Opens a PDF from raw bytes, or null if it cannot be opened. */
        fun create(context: Context, bytes: ByteArray): PdfSource? = try {
            val file = File.createTempFile("xnote_src", ".pdf", context.cacheDir)
            file.writeBytes(bytes)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) {
                renderer.close(); pfd.close(); file.delete(); null
            } else {
                PdfSource(file, pfd, renderer)
            }
        } catch (_: Exception) {
            null
        }
    }
}
