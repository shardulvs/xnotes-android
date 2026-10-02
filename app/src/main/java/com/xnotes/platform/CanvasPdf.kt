package com.xnotes.platform

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.xnotes.core.infinite.BackgroundPattern
import com.xnotes.core.infinite.CanvasPdfLayout
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.isFinite
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.paintPagePattern
import com.xnotes.core.pal.Mark
import com.xnotes.core.pal.Renderer
import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Flattens an infinite canvas into a one-page PDF, the canvas counterpart of [PdfExporter].
 *
 * There is nothing to paginate, so the page is cut to the drawing ([CanvasPdfLayout]) and everything
 * goes on it in one pass. What lands there is vector: the items are the same [CanvasItem]s the paged
 * note holds, so they paint themselves through [PdfBoxRenderer] and the ink stays real paths rather
 * than a screenshot of the canvas. The GL pipeline that draws them live is not involved at all — its
 * meshes are a render artifact, and reading pixels back off the GPU would give a raster of whatever
 * happened to be on screen at whatever zoom it was at.
 *
 * Text boxes become real, selectable text. The few looks that have no vector form (neon, the
 * highlighter's multiply, translucent ink) are rasterized in place by [PdfItemRaster], exactly as
 * they are on a paged export.
 */
object CanvasPdfExporter {

    /** Cap on PdfBox's in-RAM scratch during the write; the rest spills to temp files in the cache
     *  dir, so a canvas carrying many large images can't exhaust the heap. */
    private const val SCRATCH_MAIN_MEM_BYTES = 32L * 1024 * 1024

    /**
     * [paperColor] fills the page (the on-screen paper: the canvas's own colour, or the theme's).
     * [onProgress] reports `(itemsDone, totalItems)`, starting at `(0, total)`, and [isCancelled] is
     * polled per item so a dense canvas can show a dialog and abort before [out] is written. The
     * file is tagged and titled [title].
     */
    fun export(
        context: Context,
        doc: InfiniteDocument,
        out: OutputStream,
        paperColor: Rgba,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        title: String = doc.title,
    ) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val layout = CanvasPdfLayout.of(doc.contentBounds(), doc.dpi)
        // A non-finite item is outside the page by construction (contentBounds skips it too), and
        // drawn anyway it would put a NaN coordinate into the content stream and corrupt the file.
        val items = doc.items.filter { it.paintBounds().isFinite() }
        onProgress(0, items.size)
        val mem = MemoryUsageSetting.setupMixed(SCRATCH_MAIN_MEM_BYTES).setTempDir(context.cacheDir)
        val outDoc = PDDocument(mem)
        val ctx = PdfExportContext(outDoc)
        ctx.tags = PdfTags(outDoc, PdfExporter.tagSetup(context, title))
        try {
            val wPts = layout.widthPoints.toFloat()
            val hPts = layout.heightPoints.toFloat()
            val page = PDPage(PDRectangle(wPts, hPts))
            outDoc.addPage(page)
            PDPageContentStream(outDoc, page).use { cs ->
                cs.appendRawCommands("/Artifact BMC\n")
                cs.setNonStrokingColor(paperColor.r / 255f, paperColor.g / 255f, paperColor.b / 255f)
                cs.addRect(0f, 0f, wPts, hPts)
                cs.fill()
                cs.appendRawCommands("EMC\n")
                if (!paintContent(cs, ctx, page, doc, items, layout, hPts, onProgress, isCancelled)) return
            }
            ctx.finish()
            // PdfBox writes a file token by token; buffered, that is a few large writes, not many tiny ones.
            val sink = BufferedOutputStream(out, 1 shl 16)
            outDoc.save(sink)
            sink.flush()
        } finally {
            outDoc.runCatching { close() }
        }
    }

    /** Ruling then items in z-order. False when the export was cancelled part-way. */
    private fun paintContent(
        cs: PDPageContentStream,
        ctx: PdfExportContext,
        pdfPage: PDPage,
        doc: InfiniteDocument,
        items: List<CanvasItem>,
        layout: CanvasPdfLayout.Layout,
        hPts: Float,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        val cover = layout.cover
        val s = layout.scale
        // Map the cover's top-left corner onto the page's, in the (translate + axis scale) form
        // PdfBoxRenderer takes: user = (ox + x·s, oy − y·s).
        val r = PdfBoxRenderer(cs, ctx, pdfPage, -cover.left * s, hPts + cover.top * s, s)
        r.beginMark(Mark.Decoration)
        paintRuling(doc, r, layout)
        r.endMark()
        items.forEachIndexed { index, item ->
            if (isCancelled()) return false
            r.beginMark(PdfTags.markOf(item))
            if (PdfItemRaster.needsRaster(item)) {
                PdfItemRaster.item(item, cover)?.let { raster ->
                    r.drawItemBitmap(raster.bmp, raster.rect, raster.multiply)
                    raster.bmp.recycle()
                }
            } else {
                item.paint(r)
            }
            r.endMark()
            // Let the ribbon go the moment the single pass is past it, so a dense canvas's whole
            // worth of geometry is never resident at once — least of all during the write below.
            PdfItemRaster.releaseInkGeometry(item)
            onProgress(index + 1, items.size)
        }
        r.endPage()
        return !isCancelled()
    }

    /**
     * The canvas ruling as real vector lines, where the screen draws it procedurally in a fragment
     * shader. [BackgroundPattern] still chooses the level, handed the page's own scale as a zoom: a
     * canvas too wide to fit 1:1 gets the coarser grid it would show zoomed out that far, instead of
     * a mesh too fine for any reader to resolve. Line weight comes from the paged ruling, so a grid
     * looks the same whichever kind of document it was exported from.
     */
    private fun paintRuling(doc: InfiniteDocument, r: Renderer, layout: CanvasPdfLayout.Layout) {
        val bg = doc.background
        if (bg.pattern == PagePattern.NONE) return
        val spacing = bg.clampedSpacing
        val period = spacing * BackgroundPattern.levelMultiplier(spacing, layout.zoomEquivalent(doc.dpi))
        paintPagePattern(r, bg.pattern, bg.patternColor, period, layout.cover, layout.cover)
    }
}
