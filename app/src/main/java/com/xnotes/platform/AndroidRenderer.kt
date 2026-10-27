package com.xnotes.platform

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Build
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg

/**
 * A [Renderer] backed by an [android.graphics.Canvas]. The same implementation
 * draws to the on-screen view, into page-cache/thumbnail bitmaps, to PDF export
 *
 * Only translation and uniform scaling are used; the cumulative scale is tracked
 * so a cosmetic pen's width stays constant in device pixels regardless of zoom.
 */
class AndroidRenderer(private val canvas: Canvas) : Renderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
    private val rasterBlendPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val layerPaint = Paint()
    private val mathInk by lazy { MathRendering.Ink() }

    /** The platform blend for [blend], or null when it has none (SRC_OVER, or below API 29). */
    private fun nativeBlend(blend: BlendMode): android.graphics.BlendMode? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return when (blend) {
            BlendMode.MULTIPLY -> android.graphics.BlendMode.MULTIPLY
            BlendMode.SCREEN -> android.graphics.BlendMode.SCREEN
            BlendMode.SRC_OVER -> null
        }
    }

    private val clipBounds = android.graphics.Rect()
    private val scaleStack = ArrayDeque<Float>()
    private var scaleX = 1f
    private var scaleY = 1f
    private val avgScale get() = ((scaleX + scaleY) / 2f).coerceAtLeast(1e-4f)

    override fun save() {
        canvas.save()
        scaleStack.addLast(scaleX)
        scaleStack.addLast(scaleY)
    }

    override fun restore() {
        canvas.restore()
        if (scaleStack.isNotEmpty()) {
            scaleY = scaleStack.removeLast()
            scaleX = scaleStack.removeLast()
        }
    }

    override fun saveLayerAlpha(bounds: Rect, alpha: Double) {
        canvas.saveLayerAlpha(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            (alpha.coerceIn(0.0, 1.0) * 255).toInt(),
        )
        scaleStack.addLast(scaleX)
        scaleStack.addLast(scaleY)
    }

    // MULTIPLY and SCREEN use the W3C separable blends (BlendMode, API 29+), which over a
    // transparent backdrop *deposit* the source and over ink blend — so they
    // work the same in the transparent ink cache and on the composed screen. Below
    // API 29 (and for SRC_OVER) we just use plain alpha compositing.
    override fun saveLayerBlended(bounds: Rect, alpha: Double, blend: BlendMode) {
        val native = nativeBlend(blend) ?: return saveLayerAlpha(bounds, alpha)
        layerPaint.reset()
        layerPaint.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
        layerPaint.blendMode = native
        canvas.saveLayer(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            layerPaint,
        )
        scaleStack.addLast(scaleX)
        scaleStack.addLast(scaleY)
    }

    override fun translate(dx: Double, dy: Double) {
        canvas.translate(dx.toFloat(), dy.toFloat())
    }

    override fun rotate(degrees: Double) {
        canvas.rotate(degrees.toFloat())
        // A quarter turn swaps which axis each scale factor acts on (cosmetic pen math).
        val d = ((degrees % 360.0) + 360.0) % 360.0
        if (d == 90.0 || d == 270.0) {
            val t = scaleX
            scaleX = scaleY
            scaleY = t
        }
    }

    override fun scale(sx: Double, sy: Double) {
        canvas.scale(sx.toFloat(), sy.toFloat())
        scaleX *= sx.toFloat()
        scaleY *= sy.toFloat()
    }

    override fun clipRect(rect: Rect) {
        canvas.clipRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    }

    override fun clear() {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
    }

    /** Take each of [polygons] out of the clip, so what is drawn next leaves them alone. */
    fun clipOutPolygons(polygons: List<List<Pt>>) {
        for (poly in polygons) if (poly.size >= 3) canvas.clipOutPath(buildPath(poly, close = true, FillRule.NONZERO))
    }

    override fun fillBackground(rect: Rect, color: Rgba) = fillRect(rect, color)

    override fun fillRect(rect: Rect, color: Rgba) {
        fillPaint.color = color.toArgb()
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), fillPaint)
    }

    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) {
        if (points.size < 3) return
        fillPaint.color = color.toArgb()
        canvas.drawPath(buildPath(points, close = true, rule), fillPaint)
    }

    override fun fillCircle(center: Pt, radius: Double, color: Rgba) {
        fillPaint.color = color.toArgb()
        canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), radius.toFloat(), fillPaint)
    }

    // The whole swept-disc ribbon as one path: every disc and bridging quad is wound the same way
    // under WINDING, so overlaps union into solid ink (anti-aliased only along the true outer
    // silhouette, no interior seams, no winding-cancelled gap at a sharp turn). One draw call keeps
    // the repainted-every-frame live stroke cheap. Built straight from the packed float geometry
    // (the [Geometry.ribbonQuad] math inlined) so a dense page allocates nothing per point.
    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, color: Rgba) =
        fillDiskRibbon(centers, radii, 0, minOf(centers.size / 2, radii.size), color)

    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
        if (count <= 0) return
        val end = from + count
        fillPaint.color = color.toArgb()
        val path = Path().apply { fillType = Path.FillType.WINDING }
        for (i in from until end - 1) {
            val x0 = centers[2 * i]
            val y0 = centers[2 * i + 1]
            val x1 = centers[2 * i + 2]
            val y1 = centers[2 * i + 3]
            val dx = x1 - x0
            val dy = y1 - y0
            val len = kotlin.math.hypot(dx, dy)
            if (len < 1e-9f) continue
            val nx = -dy / len
            val ny = dx / len
            val r0 = radii[i]
            val r1 = radii[i + 1]
            val ax = x0 + nx * r0
            val ay = y0 + ny * r0
            val bx = x1 + nx * r1
            val by = y1 + ny * r1
            val cx = x1 - nx * r1
            val cy = y1 - ny * r1
            val ex = x0 - nx * r0
            val ey = y0 - ny * r0
            // Consistent (positive-area) winding so overlapping quads union rather than cancel.
            if ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax) >= 0f) {
                path.moveTo(ax, ay)
                path.lineTo(bx, by)
                path.lineTo(cx, cy)
                path.lineTo(ex, ey)
            } else {
                path.moveTo(ex, ey)
                path.lineTo(cx, cy)
                path.lineTo(bx, by)
                path.lineTo(ax, ay)
            }
            path.close()
        }
        for (i in from until end) {
            val r = radii[i]
            if (r > 0f) path.addCircle(centers[2 * i], centers[2 * i + 1], r, Path.Direction.CW)
        }
        canvas.drawPath(path, fillPaint)
    }

    // The blur radius is a page-space length: the canvas scale grows it with zoom,
    // so the halo tracks the ink (exactly like a non-cosmetic pen width). INNER blur
    // keeps the soft fill inside the shape (the white core); NORMAL spreads it both
    // ways (the outer halo).
    override fun fillPolygonGlow(pts: FloatArray, color: Rgba, rule: FillRule, blurRadius: Double, inner: Boolean) {
        if (pts.size < 6) return
        val path = buildPath(pts, close = true, rule)
        if (blurRadius <= 0.0) {
            fillPaint.color = color.toArgb()
            canvas.drawPath(path, fillPaint)
            return
        }
        glowPaint.color = color.toArgb()
        glowPaint.maskFilter = BlurMaskFilter(blurRadius.toFloat().coerceAtLeast(0.1f), blurStyle(inner))
        canvas.drawPath(path, glowPaint)
        glowPaint.maskFilter = null
    }

    override fun fillCircleGlow(center: Pt, radius: Double, color: Rgba, blurRadius: Double, inner: Boolean) {
        if (blurRadius <= 0.0) return fillCircle(center, radius, color)
        glowPaint.color = color.toArgb()
        glowPaint.maskFilter = BlurMaskFilter(blurRadius.toFloat().coerceAtLeast(0.1f), blurStyle(inner))
        canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), radius.toFloat(), glowPaint)
        glowPaint.maskFilter = null
    }

    private fun blurStyle(inner: Boolean) =
        if (inner) BlurMaskFilter.Blur.INNER else BlurMaskFilter.Blur.NORMAL

    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) {
        fillPaint.color = color.toArgb()
        canvas.drawOval(
            (center.x - rx).toFloat(), (center.y - ry).toFloat(),
            (center.x + rx).toFloat(), (center.y + ry).toFloat(),
            fillPaint,
        )
    }

    override fun strokeRect(rect: Rect, pen: Pen) {
        applyPen(pen)
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), strokePaint)
    }

    override fun strokePolyline(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        applyPen(pen)
        canvas.drawPath(buildPath(points, close = false, FillRule.NONZERO), strokePaint)
    }

    override fun strokePolyline(pts: FloatArray, pen: Pen) = strokePolyline(pts, 0, pts.size / 2, pen)

    override fun strokePolyline(pts: FloatArray, from: Int, count: Int, pen: Pen) {
        if (count < 2) return
        applyPen(pen)
        val path = Path()
        path.moveTo(pts[2 * from], pts[2 * from + 1])
        for (i in 1 until count) path.lineTo(pts[2 * (from + i)], pts[2 * (from + i) + 1])
        canvas.drawPath(path, strokePaint)
    }

    override fun strokePolygon(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        applyPen(pen)
        canvas.drawPath(buildPath(points, close = true, FillRule.NONZERO), strokePaint)
    }

    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) {
        applyPen(pen)
        canvas.drawOval(
            (center.x - rx).toFloat(), (center.y - ry).toFloat(),
            (center.x + rx).toFloat(), (center.y + ry).toFloat(),
            strokePaint,
        )
    }

    override fun fillPath(contours: List<VectorContour>, color: Rgba, rule: FillRule) {
        if (contours.isEmpty()) return
        fillPaint.color = color.toArgb()
        canvas.drawPath(buildPath(contours, rule), fillPaint)
    }

    override fun strokePath(contours: List<VectorContour>, pen: Pen) {
        if (contours.isEmpty()) return
        applyPen(pen)
        canvas.drawPath(buildPath(contours, FillRule.NONZERO), strokePaint)
    }

    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {
        val bmp = (raster as? AndroidRasterSurface)?.bitmap ?: return
        if (bmp.isRecycled) return
        val srcRect = src?.let {
            android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        val destRect = RectF(dest.left.toFloat(), dest.top.toFloat(), dest.right.toFloat(), dest.bottom.toFloat())
        canvas.drawBitmap(bmp, srcRect, destRect, bitmapPaint)
    }

    // MULTIPLY uses the W3C separable blend (API 29+); below that (and for SRC_OVER) it falls back
    // to plain alpha compositing, matching saveLayerBlended. Composites a pre-rendered highlighter
    // ribbon onto the live page each frame in one blit, no per-frame ribbon tessellation.
    override fun drawRasterBlended(raster: RasterSurface, dest: Rect, alpha: Double, blend: BlendMode, src: Rect?) {
        val bmp = (raster as? AndroidRasterSurface)?.bitmap ?: return
        if (bmp.isRecycled) return
        rasterBlendPaint.reset()
        rasterBlendPaint.isFilterBitmap = true
        rasterBlendPaint.isDither = true
        rasterBlendPaint.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
        nativeBlend(blend)?.let { rasterBlendPaint.blendMode = it }
        val srcRect = src?.let {
            android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        val destRect = RectF(dest.left.toFloat(), dest.top.toFloat(), dest.right.toFloat(), dest.bottom.toFloat())
        canvas.drawBitmap(bmp, srcRect, destRect, rasterBlendPaint)
    }

    // Decode the source only to the destination's device-pixel size (capped, never upscaled past the
    // source) so a huge photo never fully decodes; the free angle, the mirror and the quarter turn
    // all apply about the destination centre, the rect already carrying the quarter-turned
    // (w/h-swapped) box.
    override fun drawImage(image: ImageData, dest: Rect, orientation: Int, angle: Double, flip: Flip) {
        if (dest.w <= 0.0 || dest.h <= 0.0) return
        val devW = (dest.w * scaleX).toInt()
        val devH = (dest.h * scaleY).toInt()
        val o = ((orientation % 360) + 360) % 360
        if (o == 0 && angle == 0.0 && drawVectorSlice(image, dest, devW, devH, flip)) return
        val turned = o == 90 || o == 270
        val reqW = (if (turned) devH else devW).coerceIn(1, DECODE_CAP_PX)
        val reqH = (if (turned) devW else devH).coerceIn(1, DECODE_CAP_PX)
        val bmp = ImageDecoder.decodeSampledFile(image.file.path, reqW, reqH) ?: return
        val uw = (if (turned) dest.h else dest.w).toFloat()
        val uh = (if (turned) dest.w else dest.h).toFloat()
        canvas.save()
        canvas.translate(((dest.left + dest.right) / 2.0).toFloat(), ((dest.top + dest.bottom) / 2.0).toFloat())
        if (angle != 0.0) canvas.rotate(Math.toDegrees(angle).toFloat())
        if (flip != Flip.NONE) canvas.scale(if (flip.x) -1f else 1f, if (flip.y) -1f else 1f)
        if (o != 0) canvas.rotate(o.toFloat())
        canvas.drawBitmap(bmp, null, RectF(-uw / 2f, -uh / 2f, uw / 2f, uh / 2f), bitmapPaint)
        canvas.restore()
    }

    /**
     * Draw an unrotated vector image by rasterizing only the part the clip can show. The clip is
     * this canvas's own, so the destination surface bounds it: the sharp viewport asks for the
     * visible sliver instead of the whole document at a 4096 px bucket. Returns false when the
     * image isn't a vector (or the slice failed) so the caller falls back to the raster path.
     */
    private fun drawVectorSlice(image: ImageData, dest: Rect, devW: Int, devH: Int, flip: Flip): Boolean {
        val path = image.file.path
        if (!ImageDecoder.isVector(path)) return false
        if (!canvas.getClipBounds(clipBounds)) return true // clipped away entirely
        val l = maxOf(dest.left, clipBounds.left.toDouble())
        val t = maxOf(dest.top, clipBounds.top.toDouble())
        val r = minOf(dest.right, clipBounds.right.toDouble())
        val b = minOf(dest.bottom, clipBounds.bottom.toDouble())
        if (r <= l || b <= t) return true
        // What shows there, in the picture's own fractions: a mirrored picture has its far side here.
        val ul = (l - dest.left) / dest.w
        val ur = (r - dest.left) / dest.w
        val vt = (t - dest.top) / dest.h
        val vb = (b - dest.top) / dest.h
        val slice = ImageDecoder.renderVectorSlice(
            path,
            if (flip.x) 1.0 - ur else ul, if (flip.y) 1.0 - vb else vt,
            if (flip.x) 1.0 - ul else ur, if (flip.y) 1.0 - vt else vb,
            devW, devH,
        ) ?: return false
        val x0 = if (flip.x) 1.0 - slice.u1 else slice.u0
        val y0 = if (flip.y) 1.0 - slice.v1 else slice.v0
        val x1 = if (flip.x) 1.0 - slice.u0 else slice.u1
        val y1 = if (flip.y) 1.0 - slice.v0 else slice.v1
        val target = RectF(
            (dest.left + x0 * dest.w).toFloat(),
            (dest.top + y0 * dest.h).toFloat(),
            (dest.left + x1 * dest.w).toFloat(),
            (dest.top + y1 * dest.h).toFloat(),
        )
        canvas.save()
        if (flip != Flip.NONE) canvas.scale(if (flip.x) -1f else 1f, if (flip.y) -1f else 1f, target.centerX(), target.centerY())
        canvas.drawBitmap(slice.bitmap, null, target, bitmapPaint)
        canvas.restore()
        return true
    }

    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) {
        if (text.isEmpty()) return
        val paint = AndroidText.textPaint(font, color.toArgb())
        val layout = AndroidText.layout(text, rect.w.toInt(), paint, flags)
        canvas.save()
        canvas.translate(rect.left.toFloat(), rect.top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    override fun drawTextTurned(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags, angle: Double) {
        if (angle == 0.0) return drawText(text, rect, font, color, flags)
        canvas.save()
        canvas.rotate(Math.toDegrees(angle).toFloat(), rect.centerX.toFloat(), rect.centerY.toFloat())
        drawText(text, rect, font, color, flags)
        canvas.restore()
    }

    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) {
        if (text.isEmpty()) return
        val paint = AndroidText.textPaint(font, color.toArgb())
        canvas.drawText(text, x.toFloat(), baseline.toFloat(), paint)
    }

    /** What drawing a formula that is not set yet does; the live canvas queues it rather than waits. */
    var mathPace = MathRendering.Pace.WAIT

    /** How many stand-ins were drawn for formulas still being set. */
    var standIns = 0
        private set

    override fun drawMath(
        latex: String,
        x: Double,
        baseline: Double,
        sizePt: Double,
        color: Rgba,
        display: Boolean,
    ): Boolean = MathRendering.draw(canvas, mathInk, latex, x, baseline, sizePt, color, display, avgScale, mathPace)

    override fun drawMathPending(latex: String, sizePt: Double, display: Boolean, box: Rect, color: Rgba) {
        if (mathPace != MathRendering.Pace.WAIT) MathRendering.want(latex, sizePt, display, color, avgScale)
        val still = mathPace != MathRendering.Pace.LIVE
        MathRendering.drawStandIn(canvas, mathInk, box.left, box.top, box.right, box.bottom, color, still)
        standIns++
    }

    private fun applyPen(pen: Pen) {
        strokePaint.color = pen.color.toArgb()
        val width = if (pen.cosmetic) (pen.width / avgScale) else pen.width
        strokePaint.strokeWidth = width.toFloat()
        // A page-space outward blur for the neon halo on shape outlines (NORMAL = both sides).
        strokePaint.maskFilter = if (pen.glowRadius > 0.0) {
            BlurMaskFilter(pen.glowRadius.toFloat().coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
        strokePaint.pathEffect = if (pen.dashed) {
            // Cosmetic dashes (chrome) keep a fixed device-pixel rhythm; content dashes (the
            // dashed pen) are specified in content px and so scale with zoom like the ink.
            val s = if (pen.cosmetic) 1.0 / avgScale else 1.0
            val on = (pen.dashOn * s).toFloat().coerceAtLeast(0.1f)
            val off = (pen.dashGap * s).toFloat().coerceAtLeast(0.1f)
            DashPathEffect(floatArrayOf(on, off), (pen.dashPhase * s).toFloat())
        } else {
            null
        }
    }

    private fun buildPath(points: List<Pt>, close: Boolean, rule: FillRule): Path {
        val path = Path()
        path.fillType = if (rule == FillRule.EVEN_ODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        path.moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size) path.lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        if (close) path.close()
        return path
    }

    private fun buildPath(contours: List<VectorContour>, rule: FillRule): Path {
        val path = Path()
        path.fillType = if (rule == FillRule.EVEN_ODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        for (c in contours) {
            path.moveTo(c.start.x.toFloat(), c.start.y.toFloat())
            for (seg in c.segments) when (seg) {
                is VectorSeg.Line -> path.lineTo(seg.end.x.toFloat(), seg.end.y.toFloat())
                is VectorSeg.Cubic -> path.cubicTo(
                    seg.c1.x.toFloat(), seg.c1.y.toFloat(), seg.c2.x.toFloat(), seg.c2.y.toFloat(),
                    seg.end.x.toFloat(), seg.end.y.toFloat(),
                )
            }
            if (c.closed) path.close()
        }
        return path
    }

    private fun buildPath(pts: FloatArray, close: Boolean, rule: FillRule): Path {
        val path = Path()
        path.fillType = if (rule == FillRule.EVEN_ODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        path.moveTo(pts[0], pts[1])
        for (i in 1 until pts.size / 2) path.lineTo(pts[2 * i], pts[2 * i + 1])
        if (close) path.close()
        return path
    }

    companion object {
        /** Long-edge cap (device px) for an on-screen image decode, so extreme zoom can't OOM. */
        private const val DECODE_CAP_PX = 4096
    }
}
