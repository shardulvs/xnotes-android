package com.xnotes.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.StaticLayout
import android.view.View
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
@SuppressLint("ViewConstructor") // Created programmatically with editor-owned viewport and selection.
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
    private val dashEffect = DashPathEffect(
        floatArrayOf(
            (OverlayTessellator.DASH_ON_DP * devicePxPerDp).toFloat(),
            (OverlayTessellator.DASH_GAP_DP * devicePxPerDp).toFloat(),
        ), 0f,
    )

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

    fun clearIf(text: TextItem) {
        if (item === text) clear()
    }

    fun clear() {
        item = null
        liftDx = 0.0
        liftDy = 0.0
        layoutKey = null
        cachedLayout = null
        visibility = GONE
    }

    fun isShowing(text: TextItem?): Boolean = text != null && item === text

    fun setLift(dx: Double, dy: Double) {
        liftDx = dx
        liftDy = dy
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val text = item ?: return
        if (text.text.isEmpty()) return
        val width = text.width.toInt().coerceAtLeast(1)
        val previous = layoutKey
        val layout = cachedLayout?.takeIf {
            previous != null && previous.text == text.text && previous.width == width &&
                previous.pointSize == text.pointSize && previous.face == text.face &&
                previous.color == text.rgba
        } ?: AndroidText.layout(
            text.text, width,
            AndroidText.textPaint(
                text.font,
                android.graphics.Color.argb(text.rgba.a, text.rgba.r, text.rgba.g, text.rgba.b),
            ),
            TextItem.FLAGS,
        ).also {
            layoutKey = LayoutKey(text.text, width, text.pointSize, text.face, text.rgba)
            cachedLayout = it
        }

        val zoom = viewport.zoom
        canvas.save()
        canvas.translate(
            ((text.pos.x + liftDx - viewport.scrollX) * zoom).toFloat(),
            ((text.pos.y + liftDy - viewport.scrollY) * zoom).toFloat(),
        )
        canvas.scale(zoom.toFloat(), zoom.toFloat())
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
        paint.pathEffect = dashEffect
        canvas.drawPath(path, paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        val half = (OverlayTessellator.HANDLE_PX / 2.0).toFloat()
        for (handle in selection.handles(margin)) {
            val pt = viewport.contentToViewport(handle.content)
            canvas.drawRect(
                pt.x.toFloat() - half, pt.y.toFloat() - half,
                pt.x.toFloat() + half, pt.y.toFloat() + half, paint,
            )
        }
    }
}
