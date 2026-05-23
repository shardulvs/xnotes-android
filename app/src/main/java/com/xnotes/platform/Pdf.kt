package com.xnotes.platform

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageSize
import java.io.OutputStream
import kotlin.math.roundToInt

/** Turns a loaded [PdfSource] into a paged note to annotate (spec 08 §5). */
object PdfImporter {
    fun import(bytes: ByteArray, source: PdfSource, dpi: Int = PageSize.DEFAULT_DPI): Document {
        val doc = Document(dpi = dpi)
        doc.pdfBytes = bytes
        for (i in 0 until source.pageCount) {
            val (wPts, hPts) = source.pageSizePoints(i)
            val w = wPts / 72.0 * dpi
            val h = hPts / 72.0 * dpi
            doc.pages.add(Page(w, h, pdfPage = i))
        }
        if (doc.pages.isEmpty()) doc.pages.add(Page.blank(PageSize.A4, com.xnotes.core.model.Orientation.PORTRAIT, dpi))
        return doc
    }
}

/** Flattens a document — PDF backgrounds and every item — into a new PDF (spec 08 §6). */
object PdfExporter {
    fun export(doc: Document, source: PdfSource?, out: OutputStream) {
        val pdf = PdfDocument()
        val scale = (72.0 / doc.dpi).toFloat()
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        doc.pages.forEachIndexed { index, page ->
            val wPts = (page.width / doc.dpi * 72).roundToInt().coerceAtLeast(1)
            val hPts = (page.height / doc.dpi * 72).roundToInt().coerceAtLeast(1)
            val info = PdfDocument.PageInfo.Builder(wPts, hPts, index + 1).create()
            val pdfPage = pdf.startPage(info)
            val canvas = pdfPage.canvas
            // Draw in page-pixel coordinates; the canvas maps them to points.
            canvas.scale(scale, scale)

            val src = page.pdfPage
            if (src != null && source != null) {
                val bg = source.renderPage(src, page.width.toInt(), page.height.toInt(), invert = false)
                if (bg != null) {
                    canvas.drawBitmap(bg.bitmap, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), bitmapPaint)
                    bg.recycle()
                }
            }
            val renderer = AndroidRenderer(canvas)
            for (item in page.items) item.paint(renderer)
            pdf.finishPage(pdfPage)
        }
        pdf.writeTo(out)
        pdf.close()
    }
}
