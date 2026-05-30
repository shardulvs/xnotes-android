package com.xnotes.platform

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags
import kotlin.math.abs
import com.xnotes.core.pal.BlendMode as PalBlend

/**
 * A [Renderer] that writes **vector** PDF operators into a PdfBox content stream, so exported ink
 * and shapes stay as real paths and the imported PDF page underneath is never rasterized. One
 * instance draws the annotation layer of a single page; [PdfExporter] sets up the per-page mapping.
 *
 * Coordinates: the model paints in *content pixels* (top-left origin, y-down, page sized at the
 * document dpi). PDF user space is points (bottom-left origin, y-up), offset to the page's crop box.
 * Rather than push a flipped CTM — which would also mirror placed images and text — this maps every
 * point in software: `user = (ox + x·sx, oy + y·sy)` with `sx = +s, sy = −s, s = 72/dpi`.
 *
 * Only the primitives the *vectorizable* items use are meaningful here (fills, strokes, images).
 * Effect-heavy items (neon glow, highlighter multiply, translucent ink) and text are rasterized by
 * the exporter and handed back as bitmaps via [drawItemBitmap]; the glow/layer/text methods here are
 * inert, so they never silently flatten anything to a crisp-but-wrong vector shape.
 */
class PdfBoxRenderer(
    private val cs: PDPageContentStream,
    private val doc: PDDocument,
    ox: Double,
    oy: Double,
    s: Double,
) : Renderer {

    // Affine content→user mapping (translation + axis scale only; never rotation/shear — PAL §1).
    private var ox = ox
    private var oy = oy
    private var sx = s
    private var sy = -s
    private val stack = ArrayDeque<DoubleArray>()
    private val scaleAbs get() = (abs(sx) + abs(sy)) / 2.0

    private fun ux(x: Double): Float = (ox + x * sx).toFloat()
    private fun uy(y: Double): Float = (oy + y * sy).toFloat()

    // --- transform stack ---
    override fun save() {
        stack.addLast(doubleArrayOf(ox, oy, sx, sy))
        cs.saveGraphicsState()
    }

    override fun restore() {
        cs.restoreGraphicsState()
        stack.removeLastOrNull()?.let { ox = it[0]; oy = it[1]; sx = it[2]; sy = it[3] }
    }

    override fun saveLayerAlpha(bounds: Rect, alpha: Double) {
        save()
        cs.setGraphicsStateParameters(alphaState(alpha, null))
    }

    override fun saveLayerBlended(bounds: Rect, alpha: Double, blend: PalBlend) {
        save()
        cs.setGraphicsStateParameters(alphaState(alpha, if (blend == PalBlend.MULTIPLY) BlendMode.MULTIPLY else null))
    }

    override fun translate(dx: Double, dy: Double) {
        ox += dx * sx
        oy += dy * sy
    }

    override fun scale(sxFactor: Double, syFactor: Double) {
        sx *= sxFactor
        sy *= syFactor
    }

    override fun clipRect(rect: Rect) {
        // Export draws unclipped: the PDF page already bounds its content and no vectorized item clips.
    }

    override fun clear() {
        // No meaningful "clear to transparent" on an append stream; unused by export.
    }

    // --- fills ---
    override fun fillBackground(rect: Rect, color: Rgba) = fillRect(rect, color)

    override fun fillRect(rect: Rect, color: Rgba) {
        setFill(color)
        rectPath(rect)
        cs.fill()
    }

    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) {
        if (points.size < 3) return
        setFill(color)
        polyPath(points, close = true)
        if (rule == FillRule.EVEN_ODD) cs.fillEvenOdd() else cs.fill()
    }

    override fun fillCircle(center: Pt, radius: Double, color: Rgba) =
        fillEllipse(center, radius, radius, color)

    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) {
        if (rx <= 0.0 || ry <= 0.0) return
        setFill(color)
        ellipsePath(center, rx, ry)
        cs.fill()
    }

    // --- outlines ---
    override fun strokeRect(rect: Rect, pen: Pen) {
        applyPen(pen)
        rectPath(rect)
        cs.stroke()
    }

    override fun strokePolyline(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        applyPen(pen)
        polyPath(points, close = false)
        cs.stroke()
    }

    override fun strokePolygon(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        applyPen(pen)
        polyPath(points, close = true)
        cs.stroke()
    }

    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) {
        if (rx <= 0.0 || ry <= 0.0) return
        applyPen(pen)
        ellipsePath(center, rx, ry)
        cs.stroke()
    }

    // --- raster ---
    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {
        val bmp = (raster as? AndroidRasterSurface)?.bitmap ?: return
        if (bmp.isRecycled) return
        placeBitmap(bmp, dest, multiply = false)
    }

    /**
     * Place a pre-rendered item bitmap (an effect/text item the exporter rasterized) at its content
     * rect. [multiply] composites it with the page beneath via the Multiply blend mode — used for the
     * highlighter, so it tints the PDF/ink underneath instead of painting a flat translucent block.
     */
    fun drawItemBitmap(bmp: Bitmap, dest: Rect, multiply: Boolean) = placeBitmap(bmp, dest, multiply)

    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) {
        // Text boxes are rasterized by the exporter for now. TODO: embed selectable PDF text instead.
    }

    private fun placeBitmap(bmp: Bitmap, dest: Rect, multiply: Boolean) {
        if (bmp.isRecycled || dest.w <= 0.0 || dest.h <= 0.0) return
        val img = LosslessFactory.createFromImage(doc, bmp)
        val llx = ux(dest.left)
        val lly = uy(dest.bottom) // largest content-y maps to the smallest user-y → lower-left corner
        val w = (dest.w * abs(sx)).toFloat()
        val h = (dest.h * abs(sy)).toFloat()
        if (multiply) {
            cs.saveGraphicsState()
            cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { blendMode = BlendMode.MULTIPLY })
            cs.drawImage(img, llx, lly, w, h)
            cs.restoreGraphicsState()
        } else {
            cs.drawImage(img, llx, lly, w, h)
        }
    }

    // --- helpers ---
    private fun setFill(c: Rgba) {
        cs.setNonStrokingColor(c.r / 255f, c.g / 255f, c.b / 255f)
    }

    private fun applyPen(pen: Pen) {
        cs.setStrokingColor(pen.color.r / 255f, pen.color.g / 255f, pen.color.b / 255f)
        // Widths/dashes are content-px lengths; no CTM scale is applied, so convert to points here.
        cs.setLineWidth((pen.width * scaleAbs).toFloat().coerceAtLeast(0.05f))
        cs.setLineCapStyle(1) // round
        cs.setLineJoinStyle(1) // round
        if (pen.dashed) {
            val on = (pen.dashOn * scaleAbs).toFloat().coerceAtLeast(0.1f)
            val off = (pen.dashGap * scaleAbs).toFloat().coerceAtLeast(0.1f)
            cs.setLineDashPattern(floatArrayOf(on, off), 0f)
        } else {
            cs.setLineDashPattern(floatArrayOf(), 0f)
        }
    }

    private fun rectPath(rect: Rect) {
        cs.moveTo(ux(rect.left), uy(rect.top))
        cs.lineTo(ux(rect.right), uy(rect.top))
        cs.lineTo(ux(rect.right), uy(rect.bottom))
        cs.lineTo(ux(rect.left), uy(rect.bottom))
        cs.closePath()
    }

    private fun polyPath(points: List<Pt>, close: Boolean) {
        cs.moveTo(ux(points[0].x), uy(points[0].y))
        for (i in 1 until points.size) cs.lineTo(ux(points[i].x), uy(points[i].y))
        if (close) cs.closePath()
    }

    private fun ellipsePath(center: Pt, rx: Double, ry: Double) {
        val k = 0.5522847498307936 // cubic-Bézier circle constant
        val cx = center.x
        val cy = center.y
        fun curve(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) =
            cs.curveTo(ux(x1), uy(y1), ux(x2), uy(y2), ux(x3), uy(y3))
        cs.moveTo(ux(cx + rx), uy(cy))
        curve(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        curve(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        curve(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        curve(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        cs.closePath()
    }

    private fun alphaState(alpha: Double, blend: BlendMode?): PDExtendedGraphicsState {
        val a = alpha.coerceIn(0.0, 1.0).toFloat()
        return PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = a
            strokingAlphaConstant = a
            if (blend != null) blendMode = blend
        }
    }
}
