package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Mesh
import android.graphics.NinePatch
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RenderNode
import android.graphics.fonts.Font
import android.graphics.text.MeasuredText
import androidx.annotation.RequiresApi

/**
 * A canvas that paints nothing and records what is drawn on it instead, every call as a filled
 * outline in the canvas's own coordinates: how a formula the LaTeX library draws becomes vector
 * glyph content. Text turns into its glyph outlines and strokes into the outlines they cover, so
 * all of it comes out as fills. A call it cannot turn into outlines (a bitmap, a mesh) marks the
 * capture [incomplete], and the caller falls back to the formula's bitmap rather than exporting
 * part of it.
 */
internal class OutlineCapture : Canvas() {

    class Shape(val path: Path, val argb: Int)

    val shapes = mutableListOf<Shape>()

    var incomplete = false
        private set

    private val ctm = Matrix()

    private fun record(local: Path, paint: Paint, stroke: Boolean = false) {
        if (Color.alpha(paint.color) == 0) return
        val p = if (stroke && paint.style == Paint.Style.FILL) Paint(paint).apply { style = Paint.Style.STROKE } else paint
        val out = Path()
        p.getFillPath(local, out)
        @Suppress("DEPRECATION")
        getMatrix(ctm)
        out.transform(ctm)
        shapes += Shape(out, paint.color)
    }

    private fun text(t: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
        if (end <= start) return
        val s = t.subSequence(start, end).toString()
        val path = Path()
        paint.getTextPath(s, 0, s.length, x, y, path)
        record(path, paint)
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float, paint: Paint) =
        record(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, paint)

    private fun unsupported() {
        incomplete = true
    }

    // With no bitmap behind it the canvas has no size, and text layout asks for the clip before
    // drawing a line and skips all of it when that is empty. Every query answers "everywhere".
    override fun getClipBounds(bounds: Rect): Boolean {
        bounds.set(-HUGE, -HUGE, HUGE, HUGE)
        return true
    }

    override fun quickReject(rect: RectF): Boolean = false

    override fun quickReject(path: Path): Boolean = false

    override fun quickReject(left: Float, top: Float, right: Float, bottom: Float): Boolean = false

    @Deprecated("Deprecated in Java")
    override fun quickReject(rect: RectF, type: EdgeType): Boolean = false

    @Deprecated("Deprecated in Java")
    override fun quickReject(path: Path, type: EdgeType): Boolean = false

    @Deprecated("Deprecated in Java")
    override fun quickReject(left: Float, top: Float, right: Float, bottom: Float, type: EdgeType): Boolean = false

    override fun getWidth(): Int = HUGE

    override fun getHeight(): Int = HUGE

    // --- text ---
    override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) =
        text(String(text, index, count), 0, count, x, y, paint)

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) = text(text, 0, text.length, x, y, paint)

    override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) =
        text(text, start, end, x, y, paint)

    override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) =
        text(text, start, end, x, y, paint)

    override fun drawTextRun(
        text: CharArray, index: Int, count: Int, contextIndex: Int, contextCount: Int,
        x: Float, y: Float, isRtl: Boolean, paint: Paint,
    ) = text(String(text, index, count), 0, count, x, y, paint)

    override fun drawTextRun(
        text: CharSequence, start: Int, end: Int, contextStart: Int, contextEnd: Int,
        x: Float, y: Float, isRtl: Boolean, paint: Paint,
    ) = text(text, start, end, x, y, paint)

    @RequiresApi(29)
    override fun drawTextRun(
        text: MeasuredText, start: Int, end: Int, contextStart: Int, contextEnd: Int,
        x: Float, y: Float, isRtl: Boolean, paint: Paint,
    ) = unsupported()

    @RequiresApi(31)
    override fun drawGlyphs(
        glyphIds: IntArray, glyphIdOffset: Int, positions: FloatArray, positionOffset: Int,
        glyphCount: Int, font: Font, paint: Paint,
    ) = unsupported()

    @Deprecated("Deprecated in Java")
    override fun drawPosText(text: CharArray, index: Int, count: Int, pos: FloatArray, paint: Paint) = unsupported()

    @Deprecated("Deprecated in Java")
    override fun drawPosText(text: String, pos: FloatArray, paint: Paint) = unsupported()

    override fun drawTextOnPath(text: CharArray, index: Int, count: Int, path: Path, hOffset: Float, vOffset: Float, paint: Paint) =
        unsupported()

    override fun drawTextOnPath(text: String, path: Path, hOffset: Float, vOffset: Float, paint: Paint) = unsupported()

    // --- shapes ---
    override fun drawPath(path: Path, paint: Paint) = record(Path(path), paint)

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        rect(left, top, right, bottom, paint)

    override fun drawRect(rect: RectF, paint: Paint) = rect(rect.left, rect.top, rect.right, rect.bottom, paint)

    override fun drawRect(r: Rect, paint: Paint) =
        rect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), paint)

    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) =
        record(Path().apply { addRoundRect(rect, rx, ry, Path.Direction.CW) }, paint)

    override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) =
        record(Path().apply { addRoundRect(left, top, right, bottom, rx, ry, Path.Direction.CW) }, paint)

    override fun drawOval(oval: RectF, paint: Paint) = record(Path().apply { addOval(oval, Path.Direction.CW) }, paint)

    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        record(Path().apply { addOval(left, top, right, bottom, Path.Direction.CW) }, paint)

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) =
        record(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }, paint)

    override fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) =
        record(arc(oval, startAngle, sweepAngle, useCenter), paint)

    override fun drawArc(
        left: Float, top: Float, right: Float, bottom: Float,
        startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint,
    ) = record(arc(RectF(left, top, right, bottom), startAngle, sweepAngle, useCenter), paint)

    private fun arc(oval: RectF, start: Float, sweep: Float, useCenter: Boolean): Path = Path().apply {
        if (useCenter) moveTo(oval.centerX(), oval.centerY())
        arcTo(oval, start, sweep, !useCenter)
        if (useCenter) close()
    }

    // A line is always stroked, whatever the paint's style says.
    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) =
        record(Path().apply { moveTo(startX, startY); lineTo(stopX, stopY) }, paint, stroke = true)

    override fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        val path = Path()
        var i = offset
        while (i + 3 < offset + count) {
            path.moveTo(pts[i], pts[i + 1])
            path.lineTo(pts[i + 2], pts[i + 3])
            i += 4
        }
        record(path, paint, stroke = true)
    }

    override fun drawLines(pts: FloatArray, paint: Paint) = drawLines(pts, 0, pts.size, paint)

    override fun drawPoint(x: Float, y: Float, paint: Paint) = drawPoints(floatArrayOf(x, y), 0, 2, paint)

    override fun drawPoints(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        val r = paint.strokeWidth / 2f
        val path = Path()
        var i = offset
        while (i + 1 < offset + count) {
            if (paint.strokeCap == Paint.Cap.ROUND) {
                path.addCircle(pts[i], pts[i + 1], r, Path.Direction.CW)
            } else {
                path.addRect(pts[i] - r, pts[i + 1] - r, pts[i] + r, pts[i + 1] + r, Path.Direction.CW)
            }
            i += 2
        }
        record(path, Paint(paint).apply { style = Paint.Style.FILL })
    }

    override fun drawPoints(pts: FloatArray, paint: Paint) = drawPoints(pts, 0, pts.size, paint)

    override fun drawRegion(region: Region, paint: Paint) = record(region.boundaryPath, paint)

    // --- what has no outline ---
    override fun drawPaint(paint: Paint) {
        if (Color.alpha(paint.color) != 0) unsupported()
    }

    override fun drawColor(color: Int) {
        if (Color.alpha(color) != 0) unsupported()
    }

    override fun drawColor(color: Int, mode: PorterDuff.Mode) {
        if (Color.alpha(color) != 0) unsupported()
    }

    @RequiresApi(29)
    override fun drawColor(color: Int, mode: BlendMode) {
        if (Color.alpha(color) != 0) unsupported()
    }

    @RequiresApi(29)
    override fun drawColor(color: Long) {
        if (Color.alpha(color) != 0f) unsupported()
    }

    @RequiresApi(29)
    override fun drawColor(color: Long, mode: BlendMode) {
        if (Color.alpha(color) != 0f) unsupported()
    }

    override fun drawARGB(a: Int, r: Int, g: Int, b: Int) {
        if (a != 0) unsupported()
    }

    override fun drawRGB(r: Int, g: Int, b: Int) = unsupported()

    override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) = unsupported()

    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) = unsupported()

    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) = unsupported()

    override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) = unsupported()

    @Deprecated("Deprecated in Java")
    override fun drawBitmap(
        colors: IntArray, offset: Int, stride: Int, x: Float, y: Float,
        width: Int, height: Int, hasAlpha: Boolean, paint: Paint?,
    ) = unsupported()

    @Deprecated("Deprecated in Java")
    override fun drawBitmap(
        colors: IntArray, offset: Int, stride: Int, x: Int, y: Int,
        width: Int, height: Int, hasAlpha: Boolean, paint: Paint?,
    ) = unsupported()

    override fun drawBitmapMesh(
        bitmap: Bitmap, meshWidth: Int, meshHeight: Int, verts: FloatArray, vertOffset: Int,
        colors: IntArray?, colorOffset: Int, paint: Paint?,
    ) = unsupported()

    override fun drawPicture(picture: Picture) = unsupported()

    override fun drawPicture(picture: Picture, dst: RectF) = unsupported()

    override fun drawPicture(picture: Picture, dst: Rect) = unsupported()

    override fun drawVertices(
        mode: VertexMode, vertexCount: Int, verts: FloatArray, vertOffset: Int, texs: FloatArray?,
        texOffset: Int, colors: IntArray?, colorOffset: Int, indices: ShortArray?, indexOffset: Int,
        indexCount: Int, paint: Paint,
    ) = unsupported()

    @RequiresApi(29)
    override fun drawRenderNode(renderNode: RenderNode) = unsupported()

    @RequiresApi(34)
    override fun drawMesh(mesh: Mesh, blendMode: BlendMode?, paint: Paint) = unsupported()

    @RequiresApi(29)
    override fun drawPatch(patch: NinePatch, dst: Rect, paint: Paint?) = unsupported()

    @RequiresApi(29)
    override fun drawPatch(patch: NinePatch, dst: RectF, paint: Paint?) = unsupported()

    @RequiresApi(29)
    override fun drawDoubleRoundRect(outer: RectF, outerRx: Float, outerRy: Float, inner: RectF, innerRx: Float, innerRy: Float, paint: Paint) =
        unsupported()

    @RequiresApi(29)
    override fun drawDoubleRoundRect(outer: RectF, outerRadii: FloatArray, inner: RectF, innerRadii: FloatArray, paint: Paint) =
        unsupported()

    private companion object {
        const val HUGE = 1 shl 20
    }
}
