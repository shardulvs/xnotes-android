package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import com.caverock.androidsvg.SVG
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.util.Svg
import java.io.File
import java.io.FileInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Decodes an encoded image file downsampled to a target box, so a large photo is never fully decoded
 * into memory and its bytes are never slurped into the heap. Shared by [AndroidImageCodec], the
 * on-screen [AndroidRenderer] and PDF export: each asks only for the pixels its destination needs.
 * SVG files take the same route (probed and rasterized on demand via AndroidSVG), so a vector image
 * also lives on disk only and re-renders sharp at whatever resolution each draw asks for. Because a
 * complex SVG is expensive to parse and paint — and lifted (selected) items repaint every frame —
 * SVGs are served from two bounded LRUs: the parsed document, and rasterizations at power-of-two
 * size buckets (so a drag, a repeated sharpen, or a small zoom step is a plain bitmap blit).
 * On-screen draws go through [renderVectorSlice], which rasterizes only the region the caller can
 * actually show, so a deep zoom paints a viewport-sized bitmap instead of the whole document.
 * Safe to call from any thread; both caches are thread-safe and memory-capped.
 */
object ImageDecoder {

    /** Native pixel size of the image at [path] (bounds-only decode, or the SVG's intrinsic
     *  size / viewBox), or null if unreadable. */
    fun probeFile(path: String): ImageSize? =
        if (isVector(path)) cachedSvg(path)?.let { svgSize(it) } else probeRaster(path)

    /** Decode the image at [path] for a [maxWidth]×[maxHeight] box (aspect kept), or null.
     *  Rasters stay within the box and never upscale past their native pixels; an SVG returns the
     *  cached power-of-two bucket covering the box (possibly larger — callers scale into place). */
    fun decodeSampledFile(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val mw = maxWidth.coerceAtLeast(1)
        val mh = maxHeight.coerceAtLeast(1)
        if (isVector(path)) return renderSvg(path, mw, mh)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sw, sh, mw, mh)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        if (bmp.width <= mw && bmp.height <= mh) return bmp
        // inSampleSize only halves, so a result can still exceed the box: scale the rest of the way.
        val scale = minOf(mw.toDouble() / bmp.width, mh.toDouble() / bmp.height)
        val tw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val th = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /**
     * Like [decodeSampledFile] but returns a **shared, cached** bitmap the caller must never recycle
     * or mutate. Decoded at the power-of-two sample-size bucket for the box (the renderer scales it
     * into place), so a selected image being dragged or resized reuses one decode across frames
     * instead of re-reading the file every frame. Raster only; vectors fall back to the plain decode.
     */
    fun decodeSharedForDraw(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val mw = maxWidth.coerceAtLeast(1)
        val mh = maxHeight.coerceAtLeast(1)
        if (isVector(path)) return decodeSampledFile(path, mw, mh)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        val ss = sampleSize(sw, sh, mw, mh)
        val key = "$path|${runCatching { File(path).lastModified() }.getOrDefault(0L)}|$ss"
        rasterCache.get(key)?.let { if (!it.isRecycled) return it }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ss
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        rasterCache.put(key, bmp)
        return bmp
    }

    /** True when [path] holds a vector (SVG) source with no native pixel resolution.
     *  Memoized: the sniff opens the file, and a lifted image is re-drawn every frame. */
    fun isVector(path: String): Boolean =
        vectorFlags.get(path) ?: Svg.isSvgFile(File(path)).also { vectorFlags.put(path, it) }

    /**
     * A rasterized sub-region of a vector document. [bitmap] covers the fractional rect
     * ([u0], [v0])–([u1], [v1]) of the document's intrinsic box, so the caller maps that
     * fraction of its destination and blits.
     */
    class VectorSlice(
        val bitmap: Bitmap,
        val u0: Double,
        val v0: Double,
        val u1: Double,
        val v1: Double,
    )

    /**
     * Rasterize only the requested fraction of the vector at [path], sized for a [devW]×[devH]
     * on-screen box for the *whole* document. At deep zoom the caller passes the visible sliver,
     * so the render is bounded by the viewport instead of by the document: no whole-document paint
     * whose pixels are then thrown away, and no bitmap too big for its own cache.
     *
     * The returned slice may cover more than was asked for, and deliberately so. Slicing at all is
     * only worth it once the whole document no longer fits the raster caps; below that the whole
     * thing is rendered, because one rasterization then serves every position and a *lifted* image
     * repaints on the UI thread every single frame. Past the caps the wanted region is padded, and
     * the last slice is re-used for as long as it still covers what is asked for, so dragging or
     * pinching a deeply zoomed image re-renders occasionally rather than continuously.
     */
    fun renderVectorSlice(
        path: String,
        u0: Double,
        v0: Double,
        u1: Double,
        v1: Double,
        devW: Int,
        devH: Int,
    ): VectorSlice? {
        val svg = cachedSvg(path) ?: return null
        val size = svgSize(svg)
        val cu0 = u0.coerceIn(0.0, 1.0)
        val cv0 = v0.coerceIn(0.0, 1.0)
        val cu1 = u1.coerceIn(0.0, 1.0)
        val cv1 = v1.coerceIn(0.0, 1.0)
        if (cu1 <= cu0 || cv1 <= cv0) return null
        // Cover (not fit): neither axis is under-sampled when the item's box was stretched.
        val cover = max(devW.toDouble() / size.width, devH.toDouble() / size.height)
        val longSide = (max(size.width, size.height) * cover).toInt().coerceAtLeast(1)
        var bucket = pow2AtLeast(longSide)
        while (bucket >= MIN_BUCKET_PX) {
            val s = bucket.toDouble() / max(size.width, size.height)
            val docW = (size.width * s).toInt().coerceAtLeast(1)
            val docH = (size.height * s).toInt().coerceAtLeast(1)
            if (fits(docW, docH)) {
                // The whole document, under the same key [renderSvg] uses, so a draw and an export
                // at the same bucket share one bitmap.
                val bmp = sliceBitmap(svg, path, bucket, 0, 0, docW, docH, docW, docH) ?: return null
                return VectorSlice(bmp, 0.0, 0.0, 1.0, 1.0)
            }
            reusable(path, bucket, cu0 * docW, cv0 * docH, cu1 * docW, cv1 * docH)?.let { return it }
            val wantW = (cu1 - cu0) * docW
            val wantH = (cv1 - cv0) * docH
            // As much padding as the caps will take, which is what buys the drag its room. A small
            // region gets the generous share; one already the size of the screen gets what is left.
            for (pad in SLICE_PADS) {
                val x0 = floorGrid(cu0 * docW - wantW * pad).coerceIn(0, docW)
                val y0 = floorGrid(cv0 * docH - wantH * pad).coerceIn(0, docH)
                val x1 = ceilGrid(cu1 * docW + wantW * pad).coerceIn(0, docW)
                val y1 = ceilGrid(cv1 * docH + wantH * pad).coerceIn(0, docH)
                val w = x1 - x0
                val h = y1 - y0
                if (w <= 0 || h <= 0) return null
                if (!fits(w, h)) continue
                val bmp = sliceBitmap(svg, path, bucket, x0, y0, w, h, docW, docH) ?: return null
                synchronized(lastSlices) { lastSlices.put(path, Slice(bucket, x0, y0, x1, y1, docW, docH)) }
                return VectorSlice(
                    bmp,
                    x0.toDouble() / docW, y0.toDouble() / docH,
                    x1.toDouble() / docW, y1.toDouble() / docH,
                )
            }
            bucket = bucket shr 1
        }
        return null
    }

    private fun fits(w: Int, h: Int): Boolean =
        w <= VECTOR_RENDER_CAP_PX && h <= VECTOR_RENDER_CAP_PX && w.toLong() * h.toLong() <= MAX_SLICE_PX

    /** Where the last slice of a document landed, so a small move can re-use it whole. */
    private class Slice(
        val bucket: Int,
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val docW: Int,
        val docH: Int,
    )

    private val lastSlices = LruCache<String, Slice>(8)

    /**
     * The last slice of [path] when it still covers the wanted region at the same bucket and its
     * bitmap is still cached. This is what makes a drag cheap: the region asked for slides with
     * every frame, but the padded slice under it holds for a good while.
     */
    private fun reusable(path: String, bucket: Int, wx0: Double, wy0: Double, wx1: Double, wy1: Double): VectorSlice? {
        val last = synchronized(lastSlices) { lastSlices.get(path) } ?: return null
        if (last.bucket != bucket) return null
        if (wx0 < last.x0 || wy0 < last.y0 || wx1 > last.x1 || wy1 > last.y1) return null
        val key = sliceKey(path, bucket, last.x0, last.y0, last.x1 - last.x0, last.y1 - last.y0, last.docW, last.docH)
        val bmp = svgBitmapCache.get(key) ?: return null
        return VectorSlice(
            bmp,
            last.x0.toDouble() / last.docW, last.y0.toDouble() / last.docH,
            last.x1.toDouble() / last.docW, last.y1.toDouble() / last.docH,
        )
    }

    /** A whole-document slice keys as [renderSvg]'s own entry, so the two share one bitmap. */
    private fun sliceKey(path: String, bucket: Int, x0: Int, y0: Int, w: Int, h: Int, docW: Int, docH: Int): String =
        if (x0 == 0 && y0 == 0 && w == docW && h == docH) {
            "$path#$bucket"
        } else {
            "$path#$bucket#$x0,$y0+$w,$h"
        }

    private fun sliceBitmap(
        svg: SVG,
        path: String,
        bucket: Int,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
        docW: Int,
        docH: Int,
    ): Bitmap? {
        val key = sliceKey(path, bucket, x0, y0, w, h, docW, docH)
        svgBitmapCache.get(key)?.let { return it }
        val mpx = (w.toDouble() * h) / 1e6
        val cost = renderCost(path)
        var bmp: Bitmap? = null
        if (preferHardware(cost)) {
            val t = System.nanoTime()
            bmp = HardwareSvgRasterizer.render(w, h) { paintSlice(it, svg, x0, y0, docW, docH) }
            cost.hardware = if (bmp == null) Double.MAX_VALUE else elapsedPerMpx(t, mpx, cost.hardware)
        }
        if (bmp == null) {
            val t = System.nanoTime()
            bmp = runCatching {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { paintSlice(Canvas(it), svg, x0, y0, docW, docH) }
            }.getOrNull() ?: return null
            cost.software = elapsedPerMpx(t, mpx, cost.software)
        }
        svgBitmapCache.put(key, bmp)
        return bmp
    }

    // The viewport is the whole document at this bucket; the target's bounds clip to the slice.
    private fun paintSlice(c: Canvas, svg: SVG, x0: Int, y0: Int, docW: Int, docH: Int) {
        c.translate(-x0.toFloat(), -y0.toFloat())
        // Concurrent renders of one parsed document are serialized (its render state is shared).
        synchronized(svg) { svg.renderToCanvas(c, RectF(0f, 0f, docW.toFloat(), docH.toFloat())) }
    }

    /** Measured raster cost of one document, in milliseconds per megapixel, per backend. */
    private class RenderCost {
        @Volatile var software = -1.0
        @Volatile var hardware = -1.0
    }

    private val renderCosts = LruCache<String, RenderCost>(16)

    private fun renderCost(path: String): RenderCost =
        synchronized(renderCosts) { renderCosts.get(path) ?: RenderCost().also { renderCosts.put(path, it) } }

    /**
     * Whether the GPU raster is worth its readback for this document. Software is timed first; a
     * cheap paint stays on the CPU forever because the readback alone would cost more than it
     * saves. Anything dearer than that tries the GPU once, and the two measurements pick the
     * winner from then on. So the decision is measured per device and per file, never guessed.
     */
    private fun preferHardware(cost: RenderCost): Boolean {
        if (!HardwareSvgRasterizer.supported) return false
        val sw = cost.software
        if (sw < 0.0 || sw < HW_WORTH_MS_PER_MPX) return false
        val hw = cost.hardware
        return hw < 0.0 || hw < sw
    }

    // Exponentially smoothed, so one scheduling hiccup cannot pin a document to the wrong backend.
    private fun elapsedPerMpx(startNanos: Long, mpx: Double, prev: Double): Double {
        val ms = (System.nanoTime() - startNanos) / 1e6
        val now = if (mpx > 0.0) ms / mpx else ms
        return if (prev < 0.0 || prev == Double.MAX_VALUE) now else prev * 0.7 + now * 0.3
    }

    private fun floorGrid(v: Double): Int = (floor(v / SLICE_GRID) * SLICE_GRID).toInt()

    private fun ceilGrid(v: Double): Int = (ceil(v / SLICE_GRID) * SLICE_GRID).toInt()

    private fun probeRaster(path: String): ImageSize? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) ImageSize(o.outWidth, o.outHeight) else null
    }

    // Parsed SVG documents by path: parsing a big flowchart's XML can dwarf painting it, and image
    // files are immutable temp files, so the path alone is a sound key. Count-bounded (a parsed DOM
    // has no cheap byte size); stale entries for deleted temp files just age out.
    private val svgCache = LruCache<String, SVG>(4)

    // Vector-ness by path: the sniff opens the file, and image files are immutable temp files.
    private val vectorFlags = LruCache<String, Boolean>(64)

    // Rasterized SVGs by path + bucket. Byte-bounded against the heap; entries are never recycled
    // (callers may still be drawing them), eviction leaves them to the GC.
    private val svgBitmapCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L shl 20) shr 10).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // Decoded raster bitmaps by path + mtime + sample-size bucket. A lifted (selected) image is
    // redrawn every frame while it's moved or resized; without this each frame re-decoded the whole
    // JPEG from disk, so a drag lagged and the image trailed its transform box. Same byte-bounded,
    // never-recycled discipline as [svgBitmapCache] (a cached bitmap may still be mid-draw).
    private val rasterCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L shl 20) shr 10).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private fun cachedSvg(path: String): SVG? {
        svgCache.get(path)?.let { return it }
        val svg = runCatching { FileInputStream(path).use { SVG.getFromInputStream(it) } }.getOrNull() ?: return null
        // Without a viewBox the content wouldn't scale to the render viewport, so synthesize one.
        if (svg.documentViewBox == null) {
            val s = svgSize(svg)
            runCatching { svg.setDocumentViewBox(0f, 0f, s.width.toFloat(), s.height.toFloat()) }
        }
        svgCache.put(path, svg)
        return svg
    }

    /** An SVG's intrinsic pixel size: width/height attributes, else the viewBox, else a square. */
    private fun svgSize(svg: SVG): ImageSize {
        var w = runCatching { svg.documentWidth }.getOrDefault(-1f)
        var h = runCatching { svg.documentHeight }.getOrDefault(-1f)
        if (w <= 0f || h <= 0f) {
            val vb = svg.documentViewBox
            if (vb != null && vb.width() > 0f && vb.height() > 0f) {
                w = vb.width()
                h = vb.height()
            } else {
                w = DEFAULT_SVG_SIZE
                h = DEFAULT_SVG_SIZE
            }
        }
        return ImageSize(ceil(w).toInt().coerceAtLeast(1), ceil(h).toInt().coerceAtLeast(1))
    }

    // Renders at the power-of-two bucket whose long side covers the requested fit (capped), so
    // nearby request sizes share one cached bitmap and get scaled down at draw time. A complex SVG
    // is painted at most once per bucket it ever crosses; every later draw is a cache hit.
    private fun renderSvg(path: String, maxW: Int, maxH: Int): Bitmap? {
        val svg = cachedSvg(path) ?: return null
        val size = svgSize(svg)
        val fit = minOf(maxW.toDouble() / size.width, maxH.toDouble() / size.height)
        val longSide = (max(size.width, size.height) * fit).toInt().coerceAtLeast(1)
        val bucket = pow2AtLeast(longSide).coerceAtMost(VECTOR_RENDER_CAP_PX)
        val key = "$path#$bucket"
        svgBitmapCache.get(key)?.let { return it }
        val s = bucket.toDouble() / max(size.width, size.height)
        val bw = (size.width * s).toInt().coerceAtLeast(1)
        val bh = (size.height * s).toInt().coerceAtLeast(1)
        val bmp = runCatching {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { paintSlice(Canvas(it), svg, 0, 0, bw, bh) }
        }.getOrNull() ?: return null
        svgBitmapCache.put(key, bmp)
        return bmp
    }

    private fun pow2AtLeast(v: Int): Int {
        var p = 16
        while (p < v) p = p shl 1
        return p
    }

    /** Largest power-of-two sample step that keeps the decoded size at or above the requested box. */
    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var ss = 1
        while (w / (ss * 2) >= reqW && h / (ss * 2) >= reqH) ss *= 2
        return ss
    }

    private const val DEFAULT_SVG_SIZE = 512f

    // Matches the raster decode caps used by the screen renderer and PDF export. On screen the
    // slice render keeps every bitmap viewport-bounded, so this only governs export/thumbnails.
    private const val VECTOR_RENDER_CAP_PX = 4096

    // Slices snap outward to this grid so panning re-uses one rasterization for a while.
    private const val SLICE_GRID = 128

    // How far past the wanted region a slice may reach, as a fraction of that region on each side,
    // widest first. This is the room a drag or a pinch has before the render has to be repeated.
    private val SLICE_PADS = doubleArrayOf(1.0, 0.5, 0.25, 0.125, 0.0)

    // 40 MiB at ARGB_8888. A slice is bounded by the viewport, so the headroom over a screenful is
    // what the padding spends; it never scales with the document the way a whole-page render did.
    private const val MAX_SLICE_PX = 10L shl 20

    private const val MIN_BUCKET_PX = 16

    // Below this the CPU already paints faster than the GPU can hand the pixels back (a megapixel
    // of ARGB is 4 MiB across the bus), so the GPU path is never even measured.
    private const val HW_WORTH_MS_PER_MPX = 6.0
}
