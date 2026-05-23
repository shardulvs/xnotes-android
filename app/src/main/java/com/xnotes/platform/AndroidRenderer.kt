package com.xnotes.platform

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags

/**
 * A [Renderer] backed by an [android.graphics.Canvas]. The same implementation
 * draws to the on-screen view, into page-cache/thumbnail bitmaps, to PDF export
 * and to presentation frames.
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

    override fun translate(dx: Double, dy: Double) {
        canvas.translate(dx.toFloat(), dy.toFloat())
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

    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {
        val bmp = (raster as? AndroidRasterSurface)?.bitmap ?: return
        if (bmp.isRecycled) return
        val srcRect = src?.let {
            android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        val destRect = RectF(dest.left.toFloat(), dest.top.toFloat(), dest.right.toFloat(), dest.bottom.toFloat())
        canvas.drawBitmap(bmp, srcRect, destRect, bitmapPaint)
    }

    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) {
        if (text.isEmpty()) return
        val paint = AndroidText.textPaint(font, color.toArgb())
        val layout = AndroidText.layout(text, rect.w.toInt(), paint)
        canvas.save()
        canvas.translate(rect.left.toFloat(), rect.top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun applyPen(pen: Pen) {
        strokePaint.color = pen.color.toArgb()
        val width = if (pen.cosmetic) (pen.width / avgScale) else pen.width
        strokePaint.strokeWidth = width.toFloat()
        strokePaint.pathEffect = if (pen.dashed) {
            val on = (6.0 * (if (pen.cosmetic) 1.0 / avgScale else 1.0)).toFloat()
            val off = (4.0 * (if (pen.cosmetic) 1.0 / avgScale else 1.0)).toFloat()
            DashPathEffect(floatArrayOf(on, off), 0f)
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
}
