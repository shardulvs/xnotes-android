package com.xnotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.StaticLayout
import android.view.View
import com.xnotes.canvas.ResizeMath
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.CanvasSelection
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontFace
import com.xnotes.platform.AndroidText

/**
 * Native XNote text layout for an XCanvas gesture or a pending GL mesh.
 * Text and selection chrome share a single frame so rapid movements cannot desynchronize them.
 */
internal class CanvasTextPreviewView(
    context: Context,
    private val viewport: CanvasViewport,
    private val selected: () -> CanvasSelection,
    private val accent: () -> Rgba,
    private val devicePxPerDp: Double,
) : View(context) {
    var item: TextItem? = null
        private set
    var liftDx = 0.0
        private set
    var liftDy = 0.0
        private set

    private data class LayoutKey(
        val text: String,
        val width: Int,
        val pointSize: Double,
        val face: FontFace,
        val color: Rgba,
    )

    private var layoutKey: LayoutKey? = null
    private var cachedLayout: StaticLayout? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        setWillNotDraw(false)
        isClickable = false
        visibility = GONE
    }

    fun show(text: TextItem) {
        translationX = 0f
        translationY = 0f
        liftDx = 0.0
        liftDy = 0.0
        item = text
        visibility = VISIBLE
        invalidate()
    }

    fun clearIf(text: TextItem): Boolean {
        if (item !== text) return false
        clear()
        return true
    }

    fun clear() {
        item = null
        liftDx = 0.0
        liftDy = 0.0
        layoutKey = null
        cachedLayout = null
        visibility = GONE
    }

    fun isShowing(text: TextItem?): Boolean = text != null && item === text && visibility == VISIBLE

    fun setLift(dx: Double, dy: Double) {
        liftDx = dx
        liftDy = dy
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val text = item ?: return
        if (text.text.isEmpty()) return
        val key = LayoutKey(text.text, text.width.toInt().coerceAtLeast(1), text.pointSize, text.face, text.rgba)
        val layout = cachedLayout?.takeIf { layoutKey == key }
            ?: AndroidText.layout(
                text.text, key.width,
                AndroidText.textPaint(
                    text.font,
                    android.graphics.Color.argb(text.rgba.a, text.rgba.r, text.rgba.g, text.rgba.b),
                ),
                TextItem.FLAGS,
            ).also {
                layoutKey = key
                cachedLayout = it
            }

        val at = viewport.contentToViewport(Pt(text.pos.x + liftDx, text.pos.y + liftDy))
        canvas.save()
        canvas.translate(at.x.toFloat(), at.y.toFloat())
        canvas.scale(viewport.zoom.toFloat(), viewport.zoom.toFloat())
        layout.draw(canvas)
        canvas.restore()
        if (selected().items.singleOrNull() === text) drawChrome(canvas)
    }

    private fun drawChrome(canvas: Canvas) {
        val zoom = viewport.zoom
        val selection = selected()
        val margin = OverlayTessellator.FRAME_MARGIN_PX / zoom
        val box = selection.chromeBox(margin) ?: return
        val color = accent()
        paint.color = android.graphics.Color.argb(color.a, color.r, color.g, color.b)
        val corners = box.corners().map(viewport::contentToViewport)
        path.reset()
        path.moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
        for (i in 1 until corners.size) path.lineTo(corners[i].x.toFloat(), corners[i].y.toFloat())
        path.close()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = OverlayTessellator.OUTLINE_PX.toFloat()
        paint.pathEffect = DashPathEffect(
            floatArrayOf(
                (OverlayTessellator.DASH_ON_DP * devicePxPerDp).toFloat(),
                (OverlayTessellator.DASH_GAP_DP * devicePxPerDp).toFloat(),
            ), 0f,
        )
        canvas.drawPath(path, paint)
        paint.pathEffect = null
        val top = viewport.contentToViewport(ResizeMath.obbTopMid(box))
        val grip = viewport.contentToViewport(
            ResizeMath.obbRotateGrip(box, OverlayTessellator.GRIP_ARM_PX / zoom),
        )
        canvas.drawLine(top.x.toFloat(), top.y.toFloat(), grip.x.toFloat(), grip.y.toFloat(), paint)
        paint.style = Paint.Style.FILL
        val half = (OverlayTessellator.HANDLE_PX / 2.0).toFloat()
        for (handle in selection.handles(margin)) {
            val pt = viewport.contentToViewport(handle.content)
            canvas.drawRect(
                pt.x.toFloat() - half, pt.y.toFloat() - half,
                pt.x.toFloat() + half, pt.y.toFloat() + half, paint,
            )
        }
        canvas.drawCircle(
            grip.x.toFloat(), grip.y.toFloat(),
            (OverlayTessellator.GRIP_PX / 2.0).toFloat(), paint,
        )
    }
}
