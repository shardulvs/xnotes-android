package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Renderer
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg

/** Stretches of a page's paper in one colour of their own, in page space. */
class PaperStrips(val rects: List<Rect>, val color: Rgba)

/**
 * The paper of this page in a colour other than its own, in page space and painting order: its
 * margins, then each room through it over them, edge to edge. A room left Default shows [paper]
 * across a coloured margin. Empty when nothing has a colour of its own.
 */
fun Page.paperStrips(doc: Document, paper: Rgba): List<PaperStrips> {
    val margin = margins.color ?: doc.margins.color
    if (margin == null && rooms.none { it.color != null }) return emptyList()
    val ins = insets(doc)
    val cover = Rect(-ins.left, -ins.top, ins.left + width + ins.right, ins.top + height + ins.bottom)
    val out = ArrayList<PaperStrips>()
    if (margin != null) {
        val strips = marginStrips(cover, Rect(0.0, 0.0, width, height))
        if (strips.isNotEmpty()) out += PaperStrips(strips, margin)
    }
    for (r in rooms) {
        val color = r.color ?: paper.takeIf { margin != null } ?: continue
        val band = if (r.sideways) Rect(r.at, cover.top, r.size, cover.h) else Rect(cover.left, r.at, cover.w, r.size)
        out += PaperStrips(listOf(band), color)
    }
    return out
}

/** Paint [strips] in page space, each colour as one path so strips that meet show no seam. */
fun paintPaperStrips(r: Renderer, strips: List<PaperStrips>) {
    for (s in strips) {
        val path = s.rects.map { rect ->
            val corners = listOf(Pt(rect.right, rect.top), Pt(rect.right, rect.bottom), Pt(rect.left, rect.bottom))
            VectorContour(Pt(rect.left, rect.top), corners.map { VectorSeg.Line(it) }, closed = true)
        }
        r.fillPath(path, s.color, FillRule.NONZERO)
    }
}
