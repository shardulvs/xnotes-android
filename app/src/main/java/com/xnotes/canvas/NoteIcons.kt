package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pdf.NoteIcon

/**
 * The icons over text markups that have a note: chrome drawn over the pages in view, one size on
 * screen at any zoom, each just above where its markup starts, in colours taken from the markup.
 */
class NoteIcons(private val state: CanvasState) {

    /** A markup's icon: the markup on note page [page], with the icon's tail tip at [tip] in viewport px. */
    class Icon(val page: Int, val markup: TextMarkup, val tip: Pt)

    /** Draws the icons, in viewport px. */
    fun draw(r: Renderer) {
        val dp = state.devicePxPerDp
        for (icon in shown()) {
            val colors = NoteIcon.colorsOf(icon.markup.color)
            val outline = NoteIcon.outline(icon.tip, dp)
            r.fillPolygon(outline, colors.fill)
            r.strokePolygon(outline, Pen(colors.edge, NoteIcon.EDGE_DP * dp))
            val pen = Pen(colors.lines, NoteIcon.LINE_DP * dp)
            for ((a, b) in NoteIcon.lines(icon.tip, dp)) r.strokePolyline(listOf(a, b), pen)
        }
    }

    /** The topmost icon a tap at viewport [p] lands on, or null. */
    fun at(p: Pt): Icon? = shown().lastOrNull { NoteIcon.target(it.tip, state.devicePxPerDp).contains(p) }

    /** What [icon] covers, in viewport px. */
    fun bounds(icon: Icon): Rect = NoteIcon.bounds(icon.tip, state.devicePxPerDp)

    /** [m]'s icon on note page [page], noted or not; null when it has no quads or the page is not laid out. */
    fun iconOf(page: Int, m: TextMarkup): Icon? {
        val q = m.quads.firstOrNull() ?: return null
        if (page !in state.pageRects.indices) return null
        val s = state.document.dpi / 72.0
        val c = state.fromPageSpaceRect(page, Rect.ltrb(q.left * s, q.top * s, q.right * s, q.bottom * s))
        return Icon(page, m, NoteIcon.tipFor(state.contentToViewport(Pt(c.left, c.top)), state.devicePxPerDp))
    }

    /** The icons of the noted markups on the pages in view, bottom first. */
    private fun shown(): List<Icon> {
        val view = state.visibleContentRect()
        // An icon stands above its markup, so a page starting just below the view can show one.
        val reach = NoteIcon.REACH_DP * state.devicePxPerDp / state.zoom
        val near = Rect(view.x, view.y, view.w, view.h + reach)
        val out = ArrayList<Icon>()
        for (i in state.drawablePageRange()) {
            val pr = state.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(near)) continue
            val page = state.document.pages.getOrNull(i) ?: continue
            for (m in page.markups) if (m.note != null) iconOf(i, m)?.let { out += it }
        }
        return out
    }
}
