package com.xnotes.canvas

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.platform.AndroidRenderer

/**
 * The on-screen canvas. Draws the document in immediate mode each frame
 * (spec 05 §6): window background, then visible pages (paper + hairline border +
 * cached ink + page label). Selection overlay, the live stroke and the eraser
 * cursor are layered on top by later interaction code.
 */
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var state: CanvasState? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Hook for overlay drawing (selection/live-stroke/eraser), set by the interaction layer. */
    var drawOverlay: ((renderer: AndroidRenderer, canvas: Canvas) -> Unit)? = null

    init {
        isFocusableInTouchMode = true
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val st = state ?: return
        st.viewportW = w
        st.viewportH = h
        st.relayout()
        if (!st.didInitialFit && w > 0 && h > 0) {
            st.fitWidth()
            st.didInitialFit = true
        }
        st.clampScroll()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val st = state ?: return
        canvas.drawColor(st.palette.bg.toArgb())

        val r = AndroidRenderer(canvas)
        val origin = st.origin()
        r.save()
        r.translate(origin.x, origin.y)
        r.scale(st.zoom, st.zoom)

        val visible = st.visibleContentRect()
        val border = Pen(st.palette.paperBorder, 1.0, cosmetic = true)
        val visiblePages = HashSet<Page>()

        for (i in st.document.pages.indices) {
            val pr = st.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = st.document.pages[i]
            visiblePages.add(page)

            r.fillRect(pr, st.paperColor(page))
            r.strokeRect(pr, border)
            r.drawRaster(st.cacheFor(page).surface, pr)
            drawPageLabel(r, st, i, pr)
        }
        r.restore()

        drawOverlay?.invoke(r, canvas)

        st.dropCachesExcept(visiblePages)
    }

    private fun drawPageLabel(r: AndroidRenderer, st: CanvasState, index: Int, pr: Rect) {
        val label = "%02d".format(index + 1)
        r.drawText(label, Rect(pr.left, pr.top - 26.0, 140.0, 24.0), FontSpec(9.0), st.palette.textDim)
    }

    /** Request a repaint (display-paced flushing is added with the live stroke). */
    fun requestRender() = invalidate()
}
