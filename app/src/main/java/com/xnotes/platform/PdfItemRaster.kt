package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.Renderer
import com.xnotes.core.tools.Tool
import kotlin.math.ceil

/**
 * The part of a PDF export that cannot stay vector.
 *
 * [PdfBoxRenderer] turns ink, shapes, images and text into real PDF objects, but a neon glow is a
 * blur with no vector equivalent, so neon items are drawn into a bitmap cropped to their own box and
 * dropped back in at the right z-order.
 *
 * Shared by the paged [PdfExporter] and [CanvasPdfExporter] rather than written twice, so the two can
 * never disagree about which items those are: the same stroke vectorized on one surface and
 * rasterized on the other would export the same drawing two different ways.
 */
internal object PdfItemRaster {

    /** Ceiling on either side of a rasterized region, so one huge item cannot blow the heap. */
    const val MAX_DIM = 4096

    /** Supersample factor for rasterized effect items (×150 dpi content ⇒ ~300 dpi). */
    private const val ITEM_SCALE = 2.0

    class Raster(val bmp: Bitmap, val rect: Rect)

    /** Items whose look can't be reproduced as plain vector and so are rasterized in place. */
    fun needsRaster(item: CanvasItem): Boolean = when (item) {
        // Translucent ink and the highlighter are transparency groups in the PDF, so only the glow stays a picture.
        is Stroke -> item.config.neon && item.tool != Tool.HIGHLIGHTER
        // A shape's fill and outline are single paths, so PDF transparency draws them as on screen.
        is ShapeItem -> item.neon
        is ConnectorItem -> item.neon
        else -> false // text boxes are real text and images are image XObjects, both from the renderer
    }

    /** Render a single item, cropped to its [cover]-clamped paint bounds, into a transparent bitmap. */
    fun item(item: CanvasItem, cover: Rect): Raster? {
        // A shape's label stays out of its picture: the exporter writes it on top as real text.
        return region(item.paintBounds(), cover) { r, _ ->
            when (item) {
                is ShapeItem -> item.paintBody(r)
                is ConnectorItem -> item.paintBody(r)
                else -> item.paint(r)
            }
        }
    }

    /**
     * Render whatever [paint] draws, cropped to [want] clamped into [cover], into a transparent
     * bitmap. [paint] is handed the crop it actually got, which the flow-text layer needs to lay out
     * the right slice. Null when the crop is empty or falls outside the page entirely.
     */
    fun region(want: Rect, cover: Rect, paint: (Renderer, Rect) -> Unit): Raster? {
        val left = want.left.coerceAtLeast(cover.left)
        val top = want.top.coerceAtLeast(cover.top)
        val right = want.right.coerceAtMost(cover.right)
        val bottom = want.bottom.coerceAtMost(cover.bottom)
        val cw = right - left
        val ch = bottom - top
        if (cw <= 0.0 || ch <= 0.0) return null
        val w = ceil(cw * ITEM_SCALE).toInt().coerceIn(1, MAX_DIM)
        val h = ceil(ch * ITEM_SCALE).toInt().coerceIn(1, MAX_DIM)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) // starts fully transparent
        val canvas = Canvas(bmp)
        canvas.scale((w / cw).toFloat(), (h / ch).toFloat()) // content px → bitmap px
        canvas.translate(-left.toFloat(), -top.toFloat())
        val crop = Rect(left, top, cw, ch)
        paint(AndroidRenderer(canvas), crop)
        return Raster(bmp, crop)
    }

    /**
     * Drop the ribbon geometry painting [items] just built. An export is a single pass that never
     * revisits an item, so holding every one's ribbons to the end would leave the whole document's
     * worth resident (~30 bytes per sample) exactly while the encoder wants the heap. The canvas
     * rebuilds whatever it still needs on its next frame.
     */
    fun releaseInkGeometry(items: List<CanvasItem>) {
        for (item in items) releaseInkGeometry(item)
    }

    /** [releaseInkGeometry] for one item, for a pass that frees each as it goes past it. */
    fun releaseInkGeometry(item: CanvasItem) {
        if (item is Stroke) item.releaseGeometry()
    }
}
