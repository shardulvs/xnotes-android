package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.tools.Tool
import java.io.OutputStream
import kotlin.math.ceil
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

/**
 * Flattens a document into a new PDF (spec 08 §6).
 *
 * The imported PDF background stays **vector** — its pages are copied straight into the output via
 * PdfBox, never rasterized — so a 4 MB source no longer balloons into a 100 MB export. Annotations
 * are drawn on top: plain ink, shapes and inserted images become real vector/image objects through
 * [PdfBoxRenderer]; only effect-heavy items (neon glow, the highlighter's multiply blend,
 * translucent ink) and text boxes are rasterized in place — cropped to their own box, drawn at the
 * right z-order, and (for the highlighter) composited with Multiply so they still tint what's below.
 *
 * Fallbacks keep it correct everywhere: a source page with a non-zero `/Rotate` is written as one
 * upright full-page raster (overlay coordinates for rotated pages aren't handled yet), and if PdfBox
 * cannot parse the source PDF at all we fall back to the framework rasterizer ([exportRasterized]).
 */
object PdfExporter {

    private const val MAX_RASTER_DIM = 4096

    /** Supersample factor for rasterized effect/text items (×150 dpi content ⇒ ~300 dpi). */
    private const val RASTER_ITEM_SCALE = 2.0

    /**
     * [paperColor] gives each page's background fill (the on-screen paper colour, e.g. dark-theme
     * `#161616`) — used for plain note pages; an imported PDF page keeps its own background instead.
     * [onProgress] reports `(pagesDone, totalPages)` (once as `(0, total)` first) and [isCancelled]
     * is polled per page so a long export can show a dialog and abort before [out] is written.
     */
    fun export(
        context: Context,
        doc: Document,
        source: PdfSource?,
        out: OutputStream,
        paperColor: (Page) -> Rgba,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val bytes = doc.pdfBytes
        val srcDoc = if (bytes != null) loadSource(context, bytes) else null
        // A PDF background we can't parse with PdfBox: keep the working framework rasterizer.
        if (bytes != null && srcDoc == null) {
            exportRasterized(doc, source, out, paperColor, onProgress, isCancelled)
            return
        }
        try {
            exportVector(doc, srcDoc, source, out, paperColor, onProgress, isCancelled)
        } finally {
            srcDoc?.runCatching { close() }
        }
    }

    private fun loadSource(context: Context, bytes: ByteArray): PDDocument? = try {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(bytes)
    } catch (_: Throwable) {
        null
    }

    private fun exportVector(
        doc: Document,
        srcDoc: PDDocument?,
        source: PdfSource?,
        out: OutputStream,
        paperColor: (Page) -> Rgba,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val outDoc = PDDocument()
        try {
            val s = 72.0 / doc.dpi
            val total = doc.pages.size
            onProgress(0, total)
            doc.pages.forEachIndexed { index, page ->
                if (isCancelled()) return
                val srcIdx = page.pdfPage
                val hasSource = srcIdx != null && srcDoc != null && srcIdx in 0 until srcDoc.numberOfPages
                when {
                    hasSource && srcDoc!!.getPage(srcIdx!!).rotation % 360 == 0 ->
                        vectorImportedPage(outDoc, srcDoc, srcIdx, page, s)
                    hasSource ->
                        rasterFullPage(outDoc, page, source, paperColor, s) // rotated source page
                    else ->
                        vectorBlankPage(outDoc, page, s, paperColor)
                }
                onProgress(index + 1, total)
            }
            if (isCancelled()) return
            outDoc.save(out)
        } finally {
            outDoc.runCatching { close() }
        }
    }

    /** Copy a (rotation-0) source page in as vector, then overlay its annotations. */
    private fun vectorImportedPage(outDoc: PDDocument, srcDoc: PDDocument, srcIdx: Int, page: Page, s: Double) {
        val imported = outDoc.importPage(srcDoc.getPage(srcIdx))
        val crop = imported.cropBox
        val ox = crop.lowerLeftX.toDouble()
        val oy = (crop.lowerLeftY + crop.height).toDouble()
        PDPageContentStream(outDoc, imported, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            paintItems(cs, outDoc, page, ox, oy, s)
        }
    }

    /** A note page with no PDF background: blank page filled with the paper colour, then annotations. */
    private fun vectorBlankPage(outDoc: PDDocument, page: Page, s: Double, paperColor: (Page) -> Rgba) {
        val wPts = (page.width * s).toFloat().coerceAtLeast(1f)
        val hPts = (page.height * s).toFloat().coerceAtLeast(1f)
        val pdfPage = PDPage(PDRectangle(wPts, hPts))
        outDoc.addPage(pdfPage)
        PDPageContentStream(outDoc, pdfPage).use { cs ->
            val paper = paperColor(page)
            cs.setNonStrokingColor(paper.r / 255f, paper.g / 255f, paper.b / 255f)
            cs.addRect(0f, 0f, wPts, hPts)
            cs.fill()
            paintItems(cs, outDoc, page, ox = 0.0, oy = hPts.toDouble(), s)
        }
    }

    /** Draw a page's items in z-order: vectorizable ones as paths/images, effect/text items as bitmaps. */
    private fun paintItems(cs: PDPageContentStream, outDoc: PDDocument, page: Page, ox: Double, oy: Double, s: Double) {
        val renderer = PdfBoxRenderer(cs, outDoc, ox, oy, s)
        for (item in page.items) {
            if (needsRaster(item)) {
                val raster = rasterizeItem(item, page) ?: continue
                renderer.drawItemBitmap(raster.bmp, raster.rect, raster.multiply)
                raster.bmp.recycle()
            } else {
                item.paint(renderer)
            }
        }
    }

    /**
     * A source page with a non-zero rotation: render it (rotation already applied by [PdfSource])
     * plus its items into one upright bitmap and embed that as a full-page JPEG on a rotation-0 page.
     * Still vector for everything else; only these rare pages stay rasterized.
     */
    private fun rasterFullPage(outDoc: PDDocument, page: Page, source: PdfSource?, paperColor: (Page) -> Rgba, s: Double) {
        val wPx = page.width.toInt().coerceIn(1, MAX_RASTER_DIM)
        val hPx = page.height.toInt().coerceIn(1, MAX_RASTER_DIM)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(paperColor(page).toArgb())
        val srcIdx = page.pdfPage
        if (srcIdx != null && source != null) {
            val bg = source.renderPage(srcIdx, wPx, hPx, invert = false)
            if (bg != null) {
                canvas.drawBitmap(bg.bitmap, null, RectF(0f, 0f, wPx.toFloat(), hPx.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                bg.recycle()
            }
        }
        val r = AndroidRenderer(canvas)
        for (item in page.items) item.paint(r)

        val wPts = (page.width * s).toFloat().coerceAtLeast(1f)
        val hPts = (page.height * s).toFloat().coerceAtLeast(1f)
        val pdfPage = PDPage(PDRectangle(wPts, hPts))
        outDoc.addPage(pdfPage)
        PDPageContentStream(outDoc, pdfPage).use { cs ->
            val img = JPEGFactory.createFromImage(outDoc, bmp, 0.8f)
            cs.drawImage(img, 0f, 0f, wPts, hPts)
        }
        bmp.recycle()
    }

    /** Items whose look can't be reproduced as plain vector and so are rasterized in place. */
    private fun needsRaster(item: CanvasItem): Boolean = when (item) {
        is Stroke -> item.tool == Tool.HIGHLIGHTER ||
            (item.config.neon && item.tool != Tool.HIGHLIGHTER) ||
            item.renderColor.a < 255
        is ShapeItem -> item.neon ||
            item.strokeRgba.a < 255 ||
            (item.fillRgba?.let { it.a < 255 } ?: false)
        is TextItem -> true
        else -> false // ImageItem is embedded as an image XObject by the renderer's drawRaster
    }

    private class RasterItem(val bmp: Bitmap, val rect: Rect, val multiply: Boolean)

    /** Render a single item, cropped to its (page-clamped) paint bounds, into a transparent bitmap. */
    private fun rasterizeItem(item: CanvasItem, page: Page): RasterItem? {
        val pb = item.paintBounds()
        val left = pb.left.coerceAtLeast(0.0)
        val top = pb.top.coerceAtLeast(0.0)
        val right = pb.right.coerceAtMost(page.width)
        val bottom = pb.bottom.coerceAtMost(page.height)
        val cw = right - left
        val ch = bottom - top
        if (cw <= 0.0 || ch <= 0.0) return null
        val w = ceil(cw * RASTER_ITEM_SCALE).toInt().coerceIn(1, MAX_RASTER_DIM)
        val h = ceil(ch * RASTER_ITEM_SCALE).toInt().coerceIn(1, MAX_RASTER_DIM)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) // starts fully transparent
        val canvas = Canvas(bmp)
        canvas.scale((w / cw).toFloat(), (h / ch).toFloat()) // content px → bitmap px
        canvas.translate(-left.toFloat(), -top.toFloat())
        item.paint(AndroidRenderer(canvas))
        val multiply = item is Stroke && item.tool == Tool.HIGHLIGHTER
        return RasterItem(bmp, Rect(left, top, cw, ch), multiply)
    }

    /**
     * Original framework-[PdfDocument] path: rasterizes each page (background + items) to a bitmap.
     * Kept only as a fallback for source PDFs PdfBox can't parse.
     */
    private fun exportRasterized(
        doc: Document,
        source: PdfSource?,
        out: OutputStream,
        paperColor: (Page) -> Rgba,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val pdf = PdfDocument()
        val scale = (72.0 / doc.dpi).toFloat()
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val total = doc.pages.size
        try {
            onProgress(0, total)
            doc.pages.forEachIndexed { index, page ->
                if (isCancelled()) return
                val wPts = (page.width / doc.dpi * 72).roundToInt().coerceAtLeast(1)
                val hPts = (page.height / doc.dpi * 72).roundToInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(wPts, hPts, index + 1).create()
                val pdfPage = pdf.startPage(info)
                val canvas = pdfPage.canvas
                canvas.drawColor(paperColor(page).toArgb())
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
                onProgress(index + 1, total)
            }
            if (isCancelled()) return
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
