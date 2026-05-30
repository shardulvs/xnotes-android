package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Reads and rasterizes a PDF via the framework [PdfRenderer] (PAL §14). Pages
 * are reported in PostScript points and rendered on demand into ARGB surfaces;
 * dark mode inverts the rendered pixels.
 *
 * When dark mode is on, [renderPage]/[renderRegion] can optionally keep embedded
 * images in their original colours (`keepImages`): the whole page is inverted as
 * usual, then the original (un-inverted) pixels are re-stamped over each image's
 * bounding box. Image boxes come from parsing the PDF content stream with
 * PdfBox-Android — used *only* to read placements, never to rasterize. PdfBox is
 * loaded lazily on first use, so when the feature is off it is never touched.
 */
class PdfSource private constructor(
    private val appContext: Context,
    private val tempFile: File,
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    val pageCount: Int get() = renderer.pageCount

    /** PdfBox model, loaded lazily the first time image boxes are requested. Guarded by [pdfBoxLock]. */
    @Volatile private var pdfBoxDoc: PDDocument? = null
    private var pdfBoxLoadFailed = false

    /**
     * Monitor for all PdfBox state ([pdfBoxDoc], [pdfBoxLoadFailed], parsing). Deliberately separate
     * from the instance monitor that serializes the non-thread-safe [PdfRenderer], so a slow first
     * [PDDocument.load] on the prep worker can never block an on-screen page render.
     */
    private val pdfBoxLock = Any()

    /** Per-page image boxes as normalized page fractions (top-left origin); parsed once, cached. */
    private val imageRects = ConcurrentHashMap<Int, List<RectF>>()

    /** PDF page indices whose image-rect parse is queued on [imagesExecutor] (dedupes scheduling). */
    private val scheduledImages = Collections.synchronizedSet(HashSet<Int>())

    /** Single daemon worker for PdfBox parsing; created on first use, shut down in [close]. */
    private var imagesExecutor: ExecutorService? = null

    /** Guards [imagesExecutor]'s lifecycle. Kept off [pdfBoxLock] so scheduling a parse never waits
     *  on a load already running on the worker. */
    private val executorLock = Any()

    @Volatile private var closed = false

    /**
     * Invoked (on the prep worker thread) once a page's image rects have been parsed and are ready
     * to stamp. The canvas wires this to refresh the page's background cache and repaint; the
     * callback must hop to the main thread itself.
     */
    @Volatile var onImagesReady: ((index: Int) -> Unit)? = null

    /** Page size in points (1 pt = 1/72 inch). */
    @Synchronized
    fun pageSizePoints(index: Int): Pair<Int, Int> {
        val page = renderer.openPage(index)
        val size = page.width to page.height
        page.close()
        return size
    }

    @Synchronized
    fun renderPage(index: Int, widthPx: Int, heightPx: Int, invert: Boolean, keepImages: Boolean = false, blockingImages: Boolean = false): AndroidRasterSurface? {
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
        if (keepImages) keepImageColors(index, inverted, bmp, fullWpx = w, fullHpx = h, offsetXpx = 0, offsetYpx = 0, blocking = blockingImages)
        bmp.recycle()
        return AndroidRasterSurface(inverted)
    }

    /**
     * Render only the sub-rectangle ([regionLeftPx], [regionTopPx], [regionWpx], [regionHpx]) of
     * the page — coordinates in the page's own pixel space, where the whole page spans
     * [fullWpx] × [fullHpx] — into a [regionWpx] × [regionHpx] bitmap. The page is never allocated
     * at full size; only the region bitmap is, so a high-zoom viewport region can be rasterized at
     * full resolution without a giant whole-page bitmap.
     */
    @Synchronized
    fun renderRegion(
        index: Int,
        fullWpx: Int,
        fullHpx: Int,
        regionLeftPx: Int,
        regionTopPx: Int,
        regionWpx: Int,
        regionHpx: Int,
        invert: Boolean,
        keepImages: Boolean = false,
        blockingImages: Boolean = false,
    ): AndroidRasterSurface? {
        if (index !in 0 until renderer.pageCount) return null
        val w = regionWpx.coerceIn(1, MAX_DIM)
        val h = regionHpx.coerceIn(1, MAX_DIM)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val page = renderer.openPage(index)
        val m = Matrix().apply {
            setScale(fullWpx.toFloat() / page.width, fullHpx.toFloat() / page.height) // points → full-page px
            postTranslate(-regionLeftPx.toFloat(), -regionTopPx.toFloat()) // shift the region's origin to (0,0)
        }
        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        if (!invert) return AndroidRasterSurface(bmp)

        val inverted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(inverted).drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(INVERT) })
        if (keepImages) {
            keepImageColors(index, inverted, bmp, fullWpx, fullHpx, offsetXpx = regionLeftPx, offsetYpx = regionTopPx, blocking = blockingImages)
        }
        bmp.recycle()
        return AndroidRasterSurface(inverted)
    }

    /**
     * Keep embedded images in their real colours over the inverted page. Stamps immediately when
     * [index]'s image rects are already parsed. Otherwise: [blocking] (thumbnail/one-shot) callers
     * parse inline so the result is correct now; the on-screen canvas instead schedules an async
     * parse and leaves the images inverted for this frame — they snap to colour on the re-render
     * once [onImagesReady] fires. This is what keeps a freshly opened PDF from blanking while the
     * one-time [PDDocument.load] runs.
     */
    private fun keepImageColors(
        index: Int,
        inverted: Bitmap,
        original: Bitmap,
        fullWpx: Int,
        fullHpx: Int,
        offsetXpx: Int,
        offsetYpx: Int,
        blocking: Boolean,
    ) {
        if (blocking && !imageRects.containsKey(index)) imageRectsFor(index)
        if (imageRects.containsKey(index)) {
            stampOriginalImages(inverted, original, index, fullWpx, fullHpx, offsetXpx, offsetYpx)
        } else {
            scheduleImagePrep(index)
        }
    }

    /**
     * Copy the un-inverted [original] pixels back over each image box of [index] so embedded
     * images keep their real colours. Boxes are page fractions; they map onto the full page at
     * [fullWpx] × [fullHpx], then are shifted by ([offsetXpx], [offsetYpx]) into this bitmap's
     * space and clipped to it (so it works for both whole-page and region renders).
     */
    private fun stampOriginalImages(
        inverted: Bitmap,
        original: Bitmap,
        index: Int,
        fullWpx: Int,
        fullHpx: Int,
        offsetXpx: Int,
        offsetYpx: Int,
    ) {
        val rects = imageRects[index] ?: return // not parsed yet — caller schedules prep; skip stamping
        if (rects.isEmpty()) return
        val w = inverted.width
        val h = inverted.height
        val canvas = Canvas(inverted)
        for (f in rects) {
            val left = (Math.round(f.left * fullWpx) - offsetXpx).coerceIn(0, w)
            val top = (Math.round(f.top * fullHpx) - offsetYpx).coerceIn(0, h)
            val right = (Math.round(f.right * fullWpx) - offsetXpx).coerceIn(0, w)
            val bottom = (Math.round(f.bottom * fullHpx) - offsetYpx).coerceIn(0, h)
            if (right > left && bottom > top) {
                val rect = Rect(left, top, right, bottom) // src == dst: copy 1:1 over the inverted pixels
                canvas.drawBitmap(original, rect, rect, null)
            }
        }
    }

    /**
     * Normalized (0..1, top-left origin) bounding boxes of the embedded images on [index],
     * parsed once via PdfBox and cached. Empty list ⇒ behave like a plain full-page invert
     * (no PDF, parse failure, no images, or a rotated page we don't try to map).
     */
    private fun imageRectsFor(index: Int): List<RectF> = synchronized(pdfBoxLock) {
        imageRects[index]?.let { return it }
        val result = runCatching {
            val doc = pdfBoxDocument() ?: return@runCatching emptyList()
            if (index !in 0 until doc.numberOfPages) return@runCatching emptyList()
            val page = doc.getPage(index)
            if (page.rotation % 360 != 0) return@runCatching emptyList() // rotated page: don't risk a misplaced patch
            val box = page.cropBox
            ImageRectFinder(page, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
                .apply { processPage(page) }
                .rects
        }.getOrDefault(emptyList())
        imageRects[index] = result
        result
    }

    /** True once [index]'s image rects have been parsed (possibly to an empty list). */
    fun hasImageRects(index: Int): Boolean = imageRects.containsKey(index)

    /**
     * Parse [index]'s image rects on a dedicated worker (loading the PdfBox model once, on first
     * call), then notify [onImagesReady]. Never runs on the cache thread, so the slow first
     * [PDDocument.load] doesn't stall other pages' background builds. Deduped; a no-op when
     * already parsed or after [close].
     */
    private fun scheduleImagePrep(index: Int) {
        if (closed || imageRects.containsKey(index)) return
        if (!scheduledImages.add(index)) return
        val executor = synchronized(executorLock) {
            if (closed) {
                scheduledImages.remove(index)
                return
            }
            imagesExecutor ?: Executors.newSingleThreadExecutor { r ->
                Thread(r, "xnotes-pdfbox").apply { isDaemon = true }
            }.also { imagesExecutor = it }
        }
        runCatching {
            executor.execute {
                runCatching { imageRectsFor(index) }
                scheduledImages.remove(index)
                if (!closed) onImagesReady?.invoke(index)
            }
        }.onFailure { scheduledImages.remove(index) } // executor already shut down
    }

    /** The lazily-loaded PdfBox model. Always called under [pdfBoxLock]. */
    private fun pdfBoxDocument(): PDDocument? {
        if (closed) return null
        if (pdfBoxDoc == null && !pdfBoxLoadFailed) {
            pdfBoxDoc = runCatching {
                PDFBoxResourceLoader.init(appContext)
                PDDocument.load(tempFile)
            }.getOrElse { pdfBoxLoadFailed = true; null }
        }
        return pdfBoxDoc
    }

    fun close() {
        closed = true
        onImagesReady = null
        synchronized(executorLock) { runCatching { imagesExecutor?.shutdownNow() } }
        runCatching { pdfBoxDoc?.close() }
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        runCatching { tempFile.delete() }
    }

    /**
     * Walks a page's content stream and records each drawn image's axis-aligned bounding box,
     * normalized to page fractions with a top-left origin (matching the rendered bitmap). All
     * non-image operators are ignored.
     */
    private class ImageRectFinder(
        page: PDPage,
        private val llx: Float,
        private val lly: Float,
        private val cropW: Float,
        private val cropH: Float,
    ) : PDFGraphicsStreamEngine(page) {
        val rects = ArrayList<RectF>()

        override fun drawImage(pdImage: PDImage) {
            if (cropW <= 0f || cropH <= 0f) return
            val ctm = graphicsState.currentTransformationMatrix // maps the unit square to user space
            val c0 = ctm.transformPoint(0f, 0f)
            val c1 = ctm.transformPoint(1f, 0f)
            val c2 = ctm.transformPoint(1f, 1f)
            val c3 = ctm.transformPoint(0f, 1f)
            val minX = minOf(c0.x, c1.x, c2.x, c3.x)
            val maxX = maxOf(c0.x, c1.x, c2.x, c3.x)
            val minY = minOf(c0.y, c1.y, c2.y, c3.y)
            val maxY = maxOf(c0.y, c1.y, c2.y, c3.y)
            val ury = lly + cropH
            val fLeft = ((minX - llx) / cropW).coerceIn(0f, 1f)
            val fRight = ((maxX - llx) / cropW).coerceIn(0f, 1f)
            val fTop = ((ury - maxY) / cropH).coerceIn(0f, 1f) // user-space y is up; bitmap y is down
            val fBottom = ((ury - minY) / cropH).coerceIn(0f, 1f)
            if (fRight > fLeft && fBottom > fTop) rects.add(RectF(fLeft, fTop, fRight, fBottom))
        }

        // Path/clip/shading operators are irrelevant to image placement — ignore them.
        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {}
        override fun clip(windingRule: Path.FillType) {}
        override fun moveTo(x: Float, y: Float) {}
        override fun lineTo(x: Float, y: Float) {}
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
        override fun getCurrentPoint(): PointF = PointF(0f, 0f)
        override fun closePath() {}
        override fun endPath() {}
        override fun strokePath() {}
        override fun fillPath(windingRule: Path.FillType) {}
        override fun fillAndStrokePath(windingRule: Path.FillType) {}
        override fun shadingFill(shadingName: COSName) {}
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
                PdfSource(context.applicationContext, file, pfd, renderer)
            }
        } catch (_: Exception) {
            null
        }
    }
}
