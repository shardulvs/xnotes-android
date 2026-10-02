package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.xnotes.R
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageInsets
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.insets
import com.xnotes.core.pal.Mark
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pdf.FlowHeadings
import com.xnotes.core.pdf.FlowLinks
import com.xnotes.core.pdf.FlowStructure
import com.xnotes.core.pdf.Heading
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.pdf.Outline
import com.xnotes.core.pdf.PageFrame
import com.xnotes.core.pdf.PlacedLink
import com.xnotes.core.pdf.TextLink
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowPainter
import com.xnotes.core.text.TextFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.roundToInt

/** Turns a loaded [PdfSource] into a paged note to annotate (spec 08 §5). */
object PdfImporter {
    fun import(source: PdfSource, dpi: Int = PageSize.DEFAULT_DPI): Document {
        val doc = Document(dpi = dpi)
        doc.pdfFile = source.file
        val sizes = source.allPageSizePoints() ?: FloatArray(0)
        for (i in 0 until sizes.size / 2) {
            val wPts = sizes[2 * i]
            val hPts = sizes[2 * i + 1]
            if (wPts < 1f || hPts < 1f) {
                // A page that doesn't load (0x0), or one too small to see, takes the previous page's size
                val (w, h) = doc.pages.lastOrNull()?.let { it.width to it.height }
                    ?: PageSize.A4.pixels(com.xnotes.core.model.Orientation.PORTRAIT, dpi)
                doc.pages.add(Page(w, h))
                continue
            }
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
 * [PdfBoxRenderer], and the typed flow and text boxes become real, selectable text. Only
 * effect-heavy items (neon glow, the highlighter's multiply blend, translucent ink) are rasterized
 * in place: cropped to their own box, drawn at the right z-order, and (for the highlighter)
 * composited with Multiply so they still tint what's below.
 *
 * A source page turned by `/Rotate` goes in as it is, with the overlay drawn turned to match
 * ([PageFrame]). If PdfBox cannot parse the source PDF at all we fall back to the framework
 * rasterizer ([exportRasterized]).
 */
object PdfExporter {

    /**
     * The flow-text layer of an export: the flow, its layout over the document's pages, and which
     * of those pages a page of the export is ([indexOf] is null for a page the flow never reached,
     * or one foreign to the layout). A vector page paints it straight into the PDF renderer, so
     * it lands as real text; the raster fallbacks paint it into a bitmap canvas.
     */
    class FlowExport(
        val flow: TextFlow,
        val frame: FlowFrame,
        private val indexOf: (Page) -> Int?,
    ) {
        /** Paint [page]'s share of the flow, page-local, where it meets [region]; [tagged] splits runs at links. */
        fun paint(page: Page, r: Renderer, region: Rect, tagged: Boolean = false) {
            val i = indexOf(page) ?: return
            FlowPainter.paintPage(r, frame, i, region, if (tagged) breaks else emptyMap())
        }

        /** [page]'s flow extent, or null when there is no flow on it. */
        fun bounds(page: Page): Rect? = indexOf(page)?.let { frame.pageFlowBounds(it) }

        /** The links on [page], line by line. */
        fun links(page: Page): List<PlacedLink> = indexOf(page)?.let { byPage[it] }.orEmpty()

        /** The links in each paragraph. */
        val textLinks: Map<Int, List<TextLink>> by lazy { FlowLinks.find(flow) }

        /** The flow's logical structure, for a tagged export. */
        val structure: FlowStructure.Tree by lazy { FlowStructure.build(flow) }

        /** The headings whose first line is on [page]. */
        fun headings(page: Page): List<Heading> = indexOf(page)?.let { headingsByPage[it] }.orEmpty()

        private val headingsByPage: Map<Int, List<Heading>> by lazy { FlowHeadings.find(flow, frame).groupBy { it.page } }

        private val byPage: Map<Int, List<PlacedLink>> by lazy { FlowLinks.place(flow, frame, textLinks).groupBy { it.page } }

        private val breaks: Map<Int, IntArray> by lazy {
            textLinks.mapValues { (_, links) -> links.flatMap { listOf(it.start, it.end) }.toIntArray() }
        }

        companion object {
            val NONE = FlowExport(TextFlow(), FlowFrame.EMPTY) { null }
        }
    }

    /** Write buffer between PdfBox and the output file. */
    private const val WRITE_BUFFER = 1 shl 16

    /** Cap on PdfBox's in-RAM scratch buffers during export; the rest spills to temp files so a large
     *  source PDF can't exhaust the heap. Small/medium exports stay fully in memory (fast). */
    private const val SCRATCH_MAIN_MEM_BYTES = 32L * 1024 * 1024

    /**
     * [paperColor] gives each page's background fill (the on-screen paper colour, e.g. dark-theme
     * `#161616`) — used for plain note pages; an imported PDF page keeps its own background instead.
     * [onProgress] reports `(pagesDone, totalPages)` (once as `(0, total)` first) and [isCancelled]
     * is polled per page so a long export can show a dialog and abort before [out] is written. A
     * plain note is written tagged, titled [title]; a PDF-backed one keeps its source's own make-up.
     * The source's bookmarks are kept, and [headingBookmarks] adds one per heading of the flow.
     */
    fun export(
        context: Context,
        doc: Document,
        source: PdfSource?,
        out: OutputStream,
        paperColor: (Page) -> Rgba,
        paintRuling: (Page, Renderer) -> Unit = { _, _ -> },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        flow: FlowExport = FlowExport.NONE,
        title: String = doc.title,
        headingBookmarks: Boolean = true,
    ) {
        // Text fonts read PdfBox's glyph list, which only loads once the resource loader is set up.
        PDFBoxResourceLoader.init(context.applicationContext)
        // PdfBox writes a file token by token; buffered, that is a few large writes, not many tiny ones.
        val sink = BufferedOutputStream(out, WRITE_BUFFER)
        val file = doc.pdfFile
        // Cap PdfBox's in-RAM scratch to a few tens of MB and spill the rest to temp files in the
        // cache dir, so a large source PDF can't exhaust the heap while it's parsed/written.
        val mem = MemoryUsageSetting.setupMixed(SCRATCH_MAIN_MEM_BYTES).setTempDir(context.cacheDir)
        val srcDoc = if (file != null) loadSource(file, mem) else null
        // A PDF background we can't parse with PdfBox: keep the working framework rasterizer.
        if (file != null && srcDoc == null) {
            exportRasterized(doc, source, sink, paperColor, paintRuling, onProgress, isCancelled, flow)
            sink.flush()
            return
        }
        val tags = if (file == null) tagSetup(context, title) else null
        try {
            exportVector(doc, srcDoc, sink, paperColor, paintRuling, onProgress, isCancelled, mem, flow, tags, headingBookmarks)
        } finally {
            srcDoc?.runCatching { close() }
        }
        sink.flush()
    }

    /** How a tagged export of [title] reads to a screen reader, in the device's language. */
    internal fun tagSetup(context: Context, title: String): PdfTags.Setup = PdfTags.Setup(
        title = title,
        lang = Locale.getDefault().toLanguageTag().takeIf { it != "und" }.orEmpty(),
        altDrawing = context.getString(R.string.pdf_alt_drawing),
        altImage = context.getString(R.string.pdf_alt_image),
    )

    private fun loadSource(file: File, mem: MemoryUsageSetting): PDDocument? = try {
        PDDocument.load(file, mem) // file-backed + scratch-capped: never reads the whole PDF into RAM
    } catch (_: Throwable) {
        null
    }

    private fun exportVector(
        doc: Document,
        srcDoc: PDDocument?,
        out: OutputStream,
        paperColor: (Page) -> Rgba,
        paintRuling: (Page, Renderer) -> Unit,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
        mem: MemoryUsageSetting,
        flow: FlowExport,
        tags: PdfTags.Setup?,
        headingBookmarks: Boolean,
    ) {
        val s = 72.0 / doc.dpi
        val total = doc.pages.size
        // Where each page was drawn, for its heading bookmarks.
        val frames = arrayOfNulls<PageFrame>(total)
        onProgress(0, total)

        // Fast path: the note is exactly the imported PDF's pages in their original order (optionally
        // with blank pages appended). Annotate the source document in place and save *it* — its
        // already-compressed page/image streams are copied straight through, never decoded and
        // re-encoded, and no second copy of the document is built. This is the case for "import a PDF
        // and draw on it", so the common big-PDF export is both fast and low-memory.
        if (srcDoc != null && canAnnotateSourceInPlace(doc, srcDoc)) {
            val ctx = PdfExportContext(srcDoc)
            val n = srcDoc.numberOfPages
            doc.pages.forEachIndexed { index, page ->
                if (isCancelled()) return
                val ins = page.insets(doc)
                if (index < n) {
                    // Flow text counts as content too: a typed-only page must still annotate, and so
                    // must a margined one — its extra paper is written by growing the page's boxes.
                    if (page.items.isNotEmpty() || flow.bounds(page) != null || !ins.isZero) {
                        frames[index] = annotatePage(ctx, srcDoc.getPage(index), page, ins, s, paintRuling, flow)
                    } else if (page.markups.isNotEmpty()) {
                        srcDoc.getPage(index).let { pd -> PdfMarkups.write(ctx, pd, page.markups, sourceFrame(pd, s)) }
                    }
                } else {
                    frames[index] = vectorBlankPage(ctx, page, ins, s, paperColor, paintRuling, flow) // a blank note page appended after the PDF
                }
                PdfItemRaster.releaseInkGeometry(page.items)
                onProgress(index + 1, total)
            }
            if (isCancelled()) return
            ctx.finish()
            PdfBookmarks.write(srcDoc, emptyList(), headingAnchors(doc, flow, ctx, frames, headingBookmarks))
            // Page work is near-instant on this path, so all the time is in writing the PDF — one
            // opaque PdfBox call. Report it as byte progress (output ≈ the source PDF's size) so the
            // dialog keeps moving instead of freezing at "done". total = -1 marks the writing phase.
            val est = doc.pdfFile?.length() ?: 0L
            if (est > 0) {
                onProgress(0, -1) // enter the writing phase at 0% right away (no spinner flash)
                srcDoc.save(ProgressOutputStream(out, est) { p -> onProgress(p, -1) })
            } else {
                srcDoc.save(out)
            }
            return
        }

        // Fallback: pages were reordered or deleted, or it's a pure note: rebuild a fresh document.
        // Scratch is capped (see [mem]) so this can't exhaust the heap either.
        val outDoc = PDDocument(mem)
        val ctx = PdfExportContext(outDoc)
        if (tags != null) ctx.tags = PdfTags(outDoc, tags).also { it.useFlow(flow.structure, flow.textLinks) }
        try {
            doc.pages.forEachIndexed { index, page ->
                if (isCancelled()) return
                val srcIdx = page.pdfPage
                val ins = page.insets(doc)
                frames[index] = if (srcIdx != null && srcDoc != null && srcIdx in 0 until srcDoc.numberOfPages) {
                    vectorImportedPage(ctx, srcDoc, srcIdx, page, ins, s, paintRuling, flow)
                } else {
                    vectorBlankPage(ctx, page, ins, s, paperColor, paintRuling, flow)
                }
                PdfItemRaster.releaseInkGeometry(page.items)
                onProgress(index + 1, total)
            }
            if (isCancelled()) return
            ctx.finish()
            val sources = if (srcDoc != null) {
                val at = Outline.pageMap(doc.pages.map { it.pdfPage })
                PdfBookmarks.remapped(srcDoc) { i -> at[i]?.let { outDoc.getPage(it) } }
            } else {
                emptyList()
            }
            PdfBookmarks.write(outDoc, sources, headingAnchors(doc, flow, ctx, frames, headingBookmarks))
            outDoc.save(out)
        } finally {
            outDoc.runCatching { close() }
        }
    }

    /**
     * Where the bookmark of each heading on the exported pages opens, in [ctx]'s document, whose
     * pages are [doc]'s in order and were drawn by [frames]. None unless [on].
     */
    private fun headingAnchors(doc: Document, flow: FlowExport, ctx: PdfExportContext, frames: Array<PageFrame?>, on: Boolean): List<PdfBookmarks.Anchor> {
        if (!on) return emptyList()
        val out = mutableListOf<PdfBookmarks.Anchor>()
        doc.pages.forEachIndexed { i, page ->
            val headings = flow.headings(page)
            val frame = frames[i]
            if (headings.isEmpty() || frame == null) return@forEachIndexed
            val pd = ctx.doc.getPage(i)
            for (h in headings) {
                val element = ctx.tags?.let { tags -> flow.structure.textOf(h.para)?.let { tags.elementOf(it) } }
                // On a turned page the displayed top is a side of the box, so the left edge is pinned too.
                val at = frame.toUser(-page.insets(doc).left, h.top)
                out += PdfBookmarks.Anchor(h, pd, if (frame.turned) at.x.toFloat() else null, at.y.toFloat(), element)
            }
        }
        return out
    }

    /** True when the note's pages are the source's pages 0..N-1 in order, optionally followed by
     *  blank note pages: the shape that lets us annotate the source in place. */
    private fun canAnnotateSourceInPlace(doc: Document, srcDoc: PDDocument): Boolean {
        val n = srcDoc.numberOfPages
        if (doc.pages.size < n) return false // a source page was deleted -> rebuild
        for (i in 0 until n) {
            if (doc.pages[i].pdfPage != i) return false // reordered/duplicated/remapped -> rebuild
        }
        for (i in n until doc.pages.size) {
            if (doc.pages[i].pdfPage != null) return false // a source page where a blank is expected -> rebuild
        }
        return true
    }

    /** Copy a source page in as vector, then overlay its ruling + annotations; returns where it drew. */
    private fun vectorImportedPage(ctx: PdfExportContext, srcDoc: PDDocument, srcIdx: Int, page: Page, ins: PageInsets, s: Double, paintRuling: (Page, Renderer) -> Unit, flow: FlowExport): PageFrame {
        val pdfPage = ctx.doc.importPage(srcDoc.getPage(srcIdx))
        ownAnnotations(pdfPage)
        return annotatePage(ctx, pdfPage, page, ins, s, paintRuling, flow)
    }

    /**
     * Give an imported page an /Annots array of its own. [PDDocument.importPage] copies the page
     * dictionary shallowly, so two copies of one source page would share it, and an annotation
     * added to one copy would show on both.
     */
    private fun ownAnnotations(pdfPage: PDPage) {
        val annots = pdfPage.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return
        pdfPage.cosObject.setItem(COSName.ANNOTS, COSArray().also { it.addAll(annots) })
    }

    /**
     * Append [page]'s ruling + annotations as a new content stream over an existing [pdfPage] of the
     * export, then its markups; returns where it drew.
     */
    private fun annotatePage(ctx: PdfExportContext, pdfPage: PDPage, page: Page, ins: PageInsets, s: Double, paintRuling: (Page, Renderer) -> Unit, flow: FlowExport): PageFrame {
        val frame = sourceFrame(pdfPage, s)
        val cover = footprintOf(page, ins)
        growPageBox(pdfPage, frame, cover, ins)
        PDPageContentStream(ctx.doc, pdfPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            paintItems(cs, ctx, pdfPage, page, frame, cover, paintRuling, flow)
        }
        PdfMarkups.write(ctx, pdfPage, page.markups, frame)
        return frame
    }

    /**
     * Where page space lands on [pdfPage], a source page: its origin is the page's top-left as
     * displayed, read before the box grows, since the source content keeps its coordinates and the
     * margins extend the paper around it.
     */
    private fun sourceFrame(pdfPage: PDPage, s: Double): PageFrame {
        val crop = pdfPage.cropBox
        return PageFrame.of(
            crop.lowerLeftX.toDouble(), crop.lowerLeftY.toDouble(),
            (crop.lowerLeftX + crop.width).toDouble(), (crop.lowerLeftY + crop.height).toDouble(),
            pdfPage.rotation, s,
        )
    }

    /** Grow an imported page's boxes out to [cover], its paper with the note's margins, on the sides they show on. */
    private fun growPageBox(pdfPage: PDPage, frame: PageFrame, cover: Rect, ins: PageInsets) {
        if (ins.isZero) return
        val box = frame.toUser(cover)
        val grown = PDRectangle(box.left.toFloat(), box.top.toFloat(), box.w.toFloat(), box.h.toFloat())
        pdfPage.mediaBox = grown
        pdfPage.cropBox = grown
    }

    /** A note page with no PDF background: blank page filled with the paper colour, then ruling + annotations and markups; returns where it drew. */
    private fun vectorBlankPage(ctx: PdfExportContext, page: Page, ins: PageInsets, s: Double, paperColor: (Page) -> Rgba, paintRuling: (Page, Renderer) -> Unit, flow: FlowExport): PageFrame {
        val wPts = ((ins.left + page.width + ins.right) * s).toFloat().coerceAtLeast(1f)
        val hPts = ((ins.top + page.height + ins.bottom) * s).toFloat().coerceAtLeast(1f)
        val pdfPage = PDPage(PDRectangle(wPts, hPts))
        ctx.doc.addPage(pdfPage)
        // Page space starts inside the paper by the left/top margins.
        val frame = PageFrame(Affine.IDENTITY, ins.left * s, hPts - ins.top * s, s)
        PDPageContentStream(ctx.doc, pdfPage).use { cs ->
            val paper = paperColor(page)
            if (ctx.tags != null) cs.appendRawCommands("/Artifact BMC\n")
            cs.setNonStrokingColor(paper.r / 255f, paper.g / 255f, paper.b / 255f)
            cs.addRect(0f, 0f, wPts, hPts)
            cs.fill()
            if (ctx.tags != null) cs.appendRawCommands("EMC\n")
            paintItems(cs, ctx, pdfPage, page, frame, footprintOf(page, ins), paintRuling, flow)
        }
        PdfMarkups.write(ctx, pdfPage, page.markups, frame)
        return frame
    }

    /** Draw a page's ruling (behind ink), the flow text, then its items in z-order, where [frame] puts them; [cover] is its paper. */
    private fun paintItems(cs: PDPageContentStream, ctx: PdfExportContext, pdfPage: PDPage, page: Page, frame: PageFrame, cover: Rect, paintRuling: (Page, Renderer) -> Unit, flow: FlowExport) {
        val renderer = PdfBoxRenderer(cs, ctx, pdfPage, frame.ox, frame.oy, frame.s, frame.turn)
        renderer.beginMark(Mark.Decoration)
        paintRuling(page, renderer) // page ruling sits behind the ink
        renderer.endMark()
        flow.paint(page, renderer, cover, tagged = ctx.tags != null)
        for (link in flow.links(page)) renderer.addFlowLink(link)
        for (item in page.items) {
            renderer.beginMark(PdfTags.markOf(item))
            if (PdfItemRaster.needsRaster(item)) {
                PdfItemRaster.item(item, cover)?.let { raster ->
                    renderer.drawItemBitmap(raster.bmp, raster.rect, raster.multiply)
                    raster.bmp.recycle()
                }
            } else {
                item.paint(renderer)
            }
            renderer.endMark()
        }
        renderer.endPage()
    }

    /** [page]'s whole paper in page space, margins included (negative on a margined edge). */
    private fun footprintOf(page: Page, ins: PageInsets): Rect =
        Rect(-ins.left, -ins.top, ins.left + page.width + ins.right, ins.top + page.height + ins.bottom)

    /**
     * Wraps [out] and reports write progress as a 0..999 permille of [estTotal] bytes (throttled to
     * one call per permille step), so a long PdfBox `save` can drive a moving progress bar. Capped at
     * 999 so it never reads "100%" before the save actually returns. Does not own [out].
     */
    private class ProgressOutputStream(
        private val out: OutputStream,
        private val estTotal: Long,
        private val onPermille: (Int) -> Unit,
    ) : OutputStream() {
        private var written = 0L
        private var last = -1
        override fun write(b: Int) { out.write(b); written++; tick() }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len; tick() }
        override fun flush() { out.flush() }
        override fun close() { out.close() }
        private fun tick() {
            val p = ((written * 1000) / estTotal).toInt().coerceIn(0, 999)
            if (p != last) { last = p; onPermille(p) }
        }
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
        paintRuling: (Page, Renderer) -> Unit,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
        flow: FlowExport,
    ) {
        val pdf = PdfDocument()
        val scale = (72.0 / doc.dpi).toFloat()
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val total = doc.pages.size
        try {
            onProgress(0, total)
            doc.pages.forEachIndexed { index, page ->
                if (isCancelled()) return
                val cover = footprintOf(page, page.insets(doc))
                val wPts = (cover.w / doc.dpi * 72).roundToInt().coerceAtLeast(1)
                val hPts = (cover.h / doc.dpi * 72).roundToInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(wPts, hPts, index + 1).create()
                val pdfPage = pdf.startPage(info)
                val canvas = pdfPage.canvas
                canvas.drawColor(paperColor(page).toArgb())
                canvas.scale(scale, scale)
                canvas.translate(-cover.left.toFloat(), -cover.top.toFloat())

                val renderer = AndroidRenderer(canvas)
                val src = page.pdfPage
                val bg = if (src != null && source != null) source.renderPage(src, page.width.toInt(), page.height.toInt(), markups = page.markups) else null
                if (bg != null) {
                    canvas.drawBitmap(bg.bitmap, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), bitmapPaint)
                    bg.recycle()
                } else {
                    MarkupPainter.paint(renderer, page.markups, doc.dpi / 72.0) // on bare paper, beneath the ruling
                }
                paintRuling(page, renderer) // ruling behind ink
                flow.paint(page, renderer, cover)
                for (item in page.items) item.paint(renderer)
                pdf.finishPage(pdfPage)
                PdfItemRaster.releaseInkGeometry(page.items)
                onProgress(index + 1, total)
            }
            if (isCancelled()) return
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
