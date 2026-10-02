package com.xnotes.canvas

import com.xnotes.core.model.Page
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pdf.MarkupPainter

/**
 * Text markups drawn over the page while its layers don't show them: those an edit just made, until
 * the background patch holding them lands ([CanvasState.unbakedMarkups]). Coloured as the page's
 * colour filter will show them once baked, so nothing shifts when they are.
 */
class MarkupOverlay(private val state: CanvasState, private val filter: () -> PdfPageFilter) {

    fun draw(r: Renderer) {
        val visible = state.visibleContentRect()
        for (i in state.drawablePageRange()) {
            val pr = state.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = state.document.pages.getOrNull(i) ?: continue
            val marks = state.unbakedMarkups(page)
            if (marks.isNotEmpty()) paintOn(r, i, page, marks)
        }
    }

    /** Paints [marks] on note page [index] as its background shows them. */
    fun paintOn(r: Renderer, index: Int, page: Page, marks: List<TextMarkup>) {
        val pr = state.pageRects.getOrNull(index) ?: return
        val m = if (page.pdfPage != null) filter().pageMatrix else null
        val blend = if (m != null && PdfColorFilter.lightensBlack(m)) BlendMode.SCREEN else BlendMode.MULTIPLY
        val scale = state.document.dpi / 72.0
        r.withSave {
            r.clipRect(pr)
            r.translate(pr.left, pr.top)
            state.applyPageTransform(r, page)
            r.scale(scale, scale)
            for (mark in marks) {
                MarkupPainter.paint(r, mark, if (m != null) PdfColorFilter.apply(m, mark.color) else mark.color, blend)
            }
        }
    }
}
