package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pdf.TextQuad

/** Turning a page like a sheet of paper: a quarter turn swaps its sides, and what is on it turns with it. */
object PageTurn {

    /** [turns] as clockwise quarter turns, 0 to 3. */
    fun normalize(turns: Int): Int = ((turns % 4) + 4) % 4

    /** The map from the space of a [w] by [h] page to the space of that page turned [turns] quarter turns clockwise. */
    fun affine(w: Double, h: Double, turns: Int): Affine = when (normalize(turns)) {
        1 -> Affine(0.0, 1.0, -1.0, 0.0, h, 0.0)
        2 -> Affine(-1.0, 0.0, 0.0, -1.0, w, h)
        3 -> Affine(0.0, -1.0, 1.0, 0.0, 0.0, w)
        else -> Affine.IDENTITY
    }

    /** [m] turned with its page: each edge's margin goes where that edge goes. */
    fun margins(m: PageMargins, turns: Int): PageMargins = when (normalize(turns)) {
        1 -> PageMargins(left = m.bottom, top = m.left, right = m.top, bottom = m.right)
        2 -> PageMargins(left = m.right, top = m.bottom, right = m.left, bottom = m.top)
        3 -> PageMargins(left = m.top, top = m.right, right = m.bottom, bottom = m.left)
        else -> m
    }

    /** [m], a markup on a page of [wPts] by [hPts] points as displayed, turned with that page. */
    fun markup(m: TextMarkup, wPts: Double, hPts: Double, turns: Int): TextMarkup {
        val q = normalize(turns)
        if (q == 0) return m
        val t = affine(wPts, hPts, q)
        val quads = m.quads.map { quad ->
            val a = t.apply(Pt(quad.left.toDouble(), quad.top.toDouble()))
            val b = t.apply(Pt(quad.right.toDouble(), quad.bottom.toDouble()))
            TextQuad(
                minOf(a.x, b.x).toFloat(), minOf(a.y, b.y).toFloat(), maxOf(a.x, b.x).toFloat(), maxOf(a.y, b.y).toFloat(),
                (quad.quarter + q) % 4,
            )
        }
        return TextMarkup(m.id, m.type, m.color, m.intensity, quads, m.text, m.note, m.created, m.modified)
    }

    /**
     * Turn [page] of [doc] [turns] quarter turns clockwise, in place. Ink, shapes and pictures turn
     * with it; text boxes and recordings, which always stand upright, keep their place on it. Its PDF
     * page turns too, and the markups on its text with it. Margins it inherits are written onto the
     * page when turning would change them.
     */
    fun turn(page: Page, doc: Document, turns: Int) {
        val q = normalize(turns)
        if (q == 0) return
        val t = affine(page.width, page.height, q)
        if (page.pdfPage != null) {
            val toPts = 72.0 / doc.dpi
            page.markups = page.markups.map { markup(it, page.width * toPts, page.height * toPts, q) }
            page.pdfTurn = normalize(page.pdfTurn + q)
        }
        for (item in page.items) {
            if (item is TextItem || item is AudioItem) {
                val c = item.bounds().center
                val to = t.apply(c)
                item.translate(to.x - c.x, to.y - c.y)
            } else {
                item.applyTransform(t)
            }
        }
        val resolved = page.resolvedMargins(doc)
        val turned = margins(resolved, q)
        if (turned != resolved) page.margins = turned
        if (q % 2 == 1) {
            val w = page.width
            page.width = page.height
            page.height = w
        }
    }
}

/** Everything turning or resizing a page changes, held so it can be put back exactly. */
class PageShape private constructor(
    private val width: Double,
    private val height: Double,
    private val margins: PageMargins,
    private val markups: List<TextMarkup>,
    private val pdfTurn: Int,
    private val geometry: List<Pair<CanvasItem, GeometrySnapshot>>,
) {
    fun restore(page: Page) {
        page.width = width
        page.height = height
        page.margins = margins
        page.markups = markups
        page.pdfTurn = pdfTurn
        for ((item, snap) in geometry) item.restoreGeometry(snap)
    }

    companion object {
        fun of(page: Page) = PageShape(
            page.width, page.height, page.margins, page.markups, page.pdfTurn, page.items.map { it to it.snapshotGeometry() },
        )
    }
}
