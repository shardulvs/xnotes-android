package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** Read-only PDF pages for the reader, through the platform [PdfRenderer]. Not thread-safe: serialise calls per instance. */
class ReaderPdf private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val temp: File?,
) : Closeable {
    val pageCount: Int get() = renderer.pageCount

    /** Width over height of page [index]. */
    fun ratio(index: Int): Float = renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }

    /** Page [index] rendered [widthPx] wide on white (PDFs assume a paper background). */
    fun render(index: Int, widthPx: Int): Bitmap = renderer.openPage(index).use { page ->
        val w = widthPx.coerceIn(1, MAX_PX)
        val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, MAX_PX)
        renderInto(page, w, 0, 0, w, h)
    }

    /** The [w] x [h] part at ([left], [top]) of page [index] laid out [pageWidthPx] wide, for sharp zoomed-in views. */
    fun renderRegion(index: Int, pageWidthPx: Int, left: Int, top: Int, w: Int, h: Int): Bitmap = renderer.openPage(index).use { page ->
        renderInto(page, pageWidthPx, left, top, w.coerceIn(1, MAX_PX), h.coerceIn(1, MAX_PX))
    }

    private fun renderInto(page: PdfRenderer.Page, pageWidthPx: Int, left: Int, top: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val s = pageWidthPx.toFloat() / page.width.coerceAtLeast(1)
        val m = Matrix().apply { setScale(s, s); postTranslate(-left.toFloat(), -top.toFloat()) }
        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        temp?.delete()
    }

    companion object {
        private const val MAX_PX = 4096

        /** Opens [uri]; a provider that hands out an unseekable stream is copied to a temp file first. */
        fun open(context: Context, uri: Uri): ReaderPdf? {
            runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
                try {
                    ReaderPdf(pfd, PdfRenderer(pfd), null)
                } catch (e: Exception) {
                    pfd.close()
                    throw e
                }
            }.getOrNull()?.let { return it }
            return runCatching {
                val temp = File.createTempFile("reader", ".pdf", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                ReaderPdf(pfd, PdfRenderer(pfd), temp)
            }.getOrNull()
        }

        /** The first page [heightPx] tall, for an explorer tile; null when the file will not open. */
        fun renderFirstPage(context: Context, uri: Uri, heightPx: Int): Bitmap? = open(context, uri)?.use { pdf ->
            if (pdf.pageCount == 0) return null
            runCatching { pdf.render(0, (heightPx * pdf.ratio(0)).toInt()) }.getOrNull()
        }
    }
}
