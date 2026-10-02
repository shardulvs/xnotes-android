package com.xnotes.canvas

import android.text.StaticLayout
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pdf.PageText
import com.xnotes.core.pdf.TextQuads
import com.xnotes.core.search.SearchHit
import com.xnotes.core.search.SearchResults
import com.xnotes.core.search.SearchTarget
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.platform.AndroidText
import java.util.WeakHashMap

/**
 * The search's matches tinted on the pages shown, over ink so a match under handwriting still
 * shows: every match faint, the current one stronger and outlined. A match's boxes are worked out
 * the first time its page shows and kept: a PDF match's from its glyphs, a flow match's from its
 * laid-out lines, a text box match's from the box's layout.
 */
class SearchTints(
    private val state: CanvasState,
    private val texts: PdfTextSource,
    private val frame: () -> FlowFrame?,
    private val onViewChanged: () -> Unit,
    private val requestRender: () -> Unit,
) {
    /** A match's boxes: for PDF and flow text, page-space rects on [pages]; for a text box, rects from its corner at [width]. */
    private class Boxes(val pages: IntArray, val rects: Array<Rect>, val width: Double = 0.0)

    private var results: SearchResults? = null
    private var current: SearchHit? = null
    private val boxes = WeakHashMap<SearchHit, Boxes>()

    /** The flow layout the cached boxes were worked out against. */
    private var boxFrame: FlowFrame? = null

    /** Pages whose PDF text is being read for their matches, and those it could not be read for. */
    private val reading = HashSet<Int>()
    private val unreadable = HashSet<Int>()

    /** A match waiting on its page's text to be brought into view. */
    private var revealing: SearchHit? = null

    private var layoutOf: TextItem? = null
    private var layoutKey: Pair<String, Double>? = null
    private var layout: StaticLayout? = null

    fun show(results: SearchResults?, current: SearchHit?) {
        if (results == null || results.query != this.results?.query) {
            boxes.clear()
            reading.clear()
            unreadable.clear()
            revealing = null
        }
        this.results = results
        this.current = current
        requestRender()
    }

    fun draw(r: Renderer) {
        val res = results ?: return
        if (res.count == 0) return
        val f = frame()
        if (f !== boxFrame) {
            boxes.clear()
            boxFrame = f
        }
        val visible = state.visiblePageRange() ?: return
        val accent = state.palette.accent
        val faint = accent.withAlpha(FAINT_ALPHA)
        for (page in visible) {
            for (hit in res.hitsOn(page)) if (hit !== current) paint(r, hit, faint, null)
        }
        current?.let { paint(r, it, accent.withAlpha(CURRENT_ALPHA), Pen(accent, state.devicePxPerDp)) }
    }

    /**
     * Brings [hit] into view: its page shown, then the match on screen. A PDF match whose page text
     * is still being read goes to its page at once and onto the match when the text lands.
     */
    fun reveal(hit: SearchHit) {
        val shown = state.visiblePageRange()
        val bounds = boundsOf(hit)
        if (bounds != null) {
            revealing = null
            if (hit.page !in state.drawablePageRange()) state.goToPage(hit.page)
            scrollIntoView(bounds)
        } else {
            revealing = hit.takeIf { it.target == SearchTarget.Pdf && it.page !in unreadable }
            if (shown == null || hit.page !in shown) state.goToPage(hit.page)
        }
        onViewChanged()
        requestRender()
    }

    private fun paint(r: Renderer, hit: SearchHit, color: Rgba, outline: Pen?) {
        val b = boxesOf(hit) ?: return
        for (k in b.rects.indices) {
            val rect = contentRect(hit, b, k) ?: continue
            r.fillRect(rect, color)
            if (outline != null) r.strokeRect(rect, outline)
        }
    }

    private fun boundsOf(hit: SearchHit): Rect? {
        val b = boxesOf(hit) ?: return null
        var acc: Rect? = null
        for (k in b.rects.indices) contentRect(hit, b, k)?.let { acc = acc?.union(it) ?: it }
        return acc
    }

    private fun contentRect(hit: SearchHit, b: Boxes, k: Int): Rect? {
        val target = hit.target
        if (target is SearchTarget.Box) {
            if (state.pageRects.getOrNull(hit.page) == null) return null
            val rel = b.rects[k]
            return state.fromPageSpaceRect(hit.page, Rect(target.item.pos.x + rel.left, target.item.pos.y + rel.top, rel.w, rel.h))
        }
        val page = b.pages[k]
        if (state.pageRects.getOrNull(page) == null) return null
        return state.fromPageSpaceRect(page, b.rects[k])
    }

    private fun boxesOf(hit: SearchHit): Boxes? {
        val target = hit.target
        boxes[hit]?.let { b -> if (target !is SearchTarget.Box || b.width == target.item.width) return b }
        val b = when (target) {
            SearchTarget.Pdf -> {
                val text = texts.peek(hit.page) ?: return readPage(hit.page)
                pdfBoxes(hit, text)
            }
            is SearchTarget.Flow -> flowBoxes(hit, target.para)
            is SearchTarget.Box -> textBoxBoxes(hit, target.item)
        }
        boxes[hit] = b
        return b
    }

    /** Reads [page]'s PDF text, working out every match on it once it lands; null meanwhile. */
    private fun readPage(page: Int): Boxes? {
        if (page in unreadable || !reading.add(page)) return null
        val asked = results?.query
        texts.request(page) { text ->
            reading.remove(page)
            val res = results
            if (res == null || res.query != asked) return@request
            // Every match on the page at once, while its text is at hand: the cache keeps only a few pages.
            if (text == null) unreadable += page
            else for (hit in res.hitsOn(page)) if (hit.target == SearchTarget.Pdf) boxes[hit] = pdfBoxes(hit, text)
            revealing?.takeIf { it.page == page }?.let(::reveal)
            requestRender()
        }
        return null
    }

    private fun pdfBoxes(hit: SearchHit, text: PageText): Boxes {
        val quads = TextQuads.of(text, hit.sourceStart, hit.sourceEnd)
        val s = state.document.dpi / 72.0
        return Boxes(
            IntArray(quads.size) { hit.page },
            Array(quads.size) { i ->
                val q = quads[i]
                Rect(q.left * s, q.top * s, (q.right - q.left) * s, (q.bottom - q.top) * s)
            },
        )
    }

    private fun flowBoxes(hit: SearchHit, para: Int): Boxes {
        val rects = frame()?.selectionRects(FlowRange(FlowPos(para, hit.sourceStart), FlowPos(para, hit.sourceEnd))).orEmpty()
        return Boxes(IntArray(rects.size) { rects[it].first }, Array(rects.size) { rects[it].second })
    }

    /** The match's line pieces in the box's own layout, the one it is drawn with, from its top-left corner. */
    private fun textBoxBoxes(hit: SearchHit, item: TextItem): Boxes {
        val text = item.text
        val l = layoutFor(item)
        val a = hit.sourceStart.coerceIn(0, text.length)
        val b = hit.sourceEnd.coerceIn(a, text.length)
        val out = ArrayList<Rect>()
        if (b > a) {
            for (line in l.getLineForOffset(a)..l.getLineForOffset(b - 1)) {
                val s = maxOf(a, l.getLineStart(line))
                val e = minOf(b, l.getLineEnd(line))
                if (e <= s) continue
                val x0 = l.getPrimaryHorizontal(s).toDouble()
                // An end on a wrap belongs to the next line, so it is measured as this line's right edge.
                val x1 = if (e == l.getLineEnd(line) && line + 1 < l.lineCount) l.getLineRight(line).toDouble() else l.getPrimaryHorizontal(e).toDouble()
                val top = l.getLineTop(line).toDouble()
                out += Rect(minOf(x0, x1), top, kotlin.math.abs(x1 - x0), l.getLineBottom(line) - top)
            }
        }
        return Boxes(IntArray(0), out.toTypedArray(), item.width)
    }

    private fun layoutFor(item: TextItem): StaticLayout {
        val key = item.text to item.width
        layout?.let { if (layoutOf === item && layoutKey == key) return it }
        return AndroidText.layout(item.text, item.width.toInt(), AndroidText.textPaint(item.font)).also {
            layoutOf = item
            layoutKey = key
            layout = it
        }
    }

    /** Scrolls the least that centres [c] on whichever axis it is off screen. */
    private fun scrollIntoView(c: Rect) {
        val tl = state.contentToViewport(Pt(c.left, c.top))
        val br = state.contentToViewport(Pt(c.right, c.bottom))
        val margin = REVEAL_MARGIN_DP * state.devicePxPerDp
        val left = state.insetLeft + margin
        val right = state.viewportW - state.insetRight - margin
        val top = state.insetTop + margin
        val bottom = state.viewportH - state.insetBottom - margin
        if (right <= left || bottom <= top) return
        val dx = if (tl.x < left || br.x > right) (tl.x + br.x) / 2 - (left + right) / 2 else 0.0
        val dy = if (tl.y < top || br.y > bottom) (tl.y + br.y) / 2 - (top + bottom) / 2 else 0.0
        if (dx != 0.0 || dy != 0.0) state.scrollBy(dx, dy)
    }

    private companion object {
        /** Accent at 25% for every match, 45% for the current one. */
        const val FAINT_ALPHA = 64
        const val CURRENT_ALPHA = 115

        /** Room kept between a match brought into view and the edges. */
        const val REVEAL_MARGIN_DP = 48.0
    }
}
