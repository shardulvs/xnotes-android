package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pdf.PageText
import com.xnotes.core.pdf.TextHit
import com.xnotes.core.pdf.TextPos
import com.xnotes.core.pdf.TextQuad
import com.xnotes.core.pdf.TextSelection
import com.xnotes.core.pdf.TextWords

/** Where a [PdfTextController] reads the PDF text of note pages, by note page index. */
interface PdfTextSource {
    /** The text when held, else null (also for a page without a PDF); never waits. */
    fun peek(page: Int): PageText?

    /** Starts reading the text in the background. */
    fun prefetch(page: Int)

    /** Reads the text off the main thread, then hands it to [onReady] on the main thread, null when there is none. */
    fun request(page: Int, onReady: (PageText?) -> Unit)
}

/**
 * Selecting the text of a note's PDF pages. A free pointer's long press selects a word and drags on
 * to extend it; the teardrop handles move either end, across pages, autoscrolling near the edges
 * while the view scrolls (a paginated view keeps a handle to the pages shown). The text markup tool
 * selects by dragging from where it presses, a character at a time. Drawn in the interaction
 * overlay, over ink, like the flow text selection.
 */
class PdfTextController(
    private val state: CanvasState,
    private val texts: PdfTextSource,
    private val onViewChanged: () -> Unit,
    private val requestRender: () -> Unit,
) {
    var selection: TextSelection? = null
        private set

    /** A selecting gesture ended with text selected: time to show its menu. */
    var onSettled: () -> Unit = {}

    /** The selection ended. */
    var onCleared: () -> Unit = {}

    /** A long press selected a word: a short haptic tick. */
    var onHaptic: () -> Unit = {}

    /** The markup tool's marking drag lifted over [TextSelection]: time to make the marks. */
    var onMarked: (TextSelection) -> Unit = {}

    /** Draws the lines a marking drag covers on note page [Int] as the mark it will make. */
    var drawMark: ((Renderer, Int, List<TextQuad>) -> Unit)? = null

    private val handles = TextHandles(state)
    private var dragging: TextHandles.Handle? = null
    private var fixed: TextPos? = null
    private var grabOffset = Pt(0.0, 0.0)

    /** The long-pressed word, while the press that selected it drags on to extend it. */
    private var word: TextSelection? = null

    /** The markup tool's drag: under way, the boundary it started from once its text is read, and whether it marks. */
    private var toolDrag = false
    private var anchor: TextPos? = null
    var marking = false
        private set
    private var pressing = false
    private var dragAt: Pt? = null

    /** Bumped by each long press and by [clear], so a press still waiting on its text knows it was overtaken. */
    private var pressGen = 0

    /** Pages a drag is waiting to read; pages the overlay read for the current selection. */
    private val reading = HashSet<Int>()
    private val readForSelection = HashSet<Int>()

    /** Points per page-space px: the PDF renders at dpi / 72 px per point. */
    private val ptPerPx: Double get() = 72.0 / state.document.dpi

    fun prefetch(page: Int) = texts.prefetch(page)

    /**
     * A free pointer's long press at [pageLocal] (page space) on page [page]: selects the word there,
     * which the press may then drag on to extend, or runs [noText] when there is no text there. Text
     * still being read selects its word once it lands.
     */
    fun longPress(page: Int, pageLocal: Pt, noText: () -> Unit) {
        pressing = true
        val gen = ++pressGen
        texts.peek(page)?.let { return selectWord(page, it, pageLocal, noText) }
        texts.request(page) {
            if (gen != pressGen) return@request
            val text = texts.peek(page)
            if (text == null) noText() else selectWord(page, text, pageLocal, noText)
            if (!pressing && selection != null) onSettled()
        }
    }

    /**
     * The markup tool's press at [pageLocal] (page space) on page [page]: from the character boundary
     * there the selection follows the pointer a character at a time. A press away from the text
     * selects nothing; text still being read starts once it lands. With [mark] the selection draws
     * as the mark it will become, and lifting hands it to [onMarked] instead of showing its menu.
     */
    fun beginToolDrag(page: Int, pageLocal: Pt, mark: Boolean) {
        pressing = true
        toolDrag = true
        marking = mark
        val gen = ++pressGen
        texts.peek(page)?.let { return anchorAt(page, it, pageLocal) }
        texts.request(page) { text ->
            if (gen != pressGen || text == null) return@request
            anchorAt(page, text, pageLocal)
            dragAt?.let { follow(it) }
            requestRender()
        }
    }

    private fun anchorAt(page: Int, text: PageText, pageLocal: Pt) {
        val x = (pageLocal.x * ptPerPx).toFloat()
        val y = (pageLocal.y * ptPerPx).toFloat()
        val slop = (TOUCH_SLOP_DP * state.devicePxPerDp / state.zoom * ptPerPx).toFloat()
        val offset = TextHit.offsetAt(text, x, y, slop)
        if (offset < 0) return
        val at = TextPos(page, offset)
        readForSelection.clear()
        anchor = at
        selection = TextSelection(at, at)
    }

    private fun selectWord(page: Int, text: PageText, pageLocal: Pt, noText: () -> Unit) {
        val x = (pageLocal.x * ptPerPx).toFloat()
        val y = (pageLocal.y * ptPerPx).toFloat()
        val slop = (TOUCH_SLOP_DP * state.devicePxPerDp / state.zoom * ptPerPx).toFloat()
        var i = TextHit.charAt(text, x, y, slop)
        if (i < 0) return noText()
        if (isBlank(text, i)) i = besideBlank(text, i, x, y)
        val range = TextWords.wordAt(text, i)
        val sel = TextSelection(TextPos(page, range.first), TextPos(page, range.last + 1))
        readForSelection.clear()
        word = sel.takeIf { pressing }
        selection = sel
        onHaptic()
        requestRender()
    }

    /** A press at [viewport]: grabs a handle of the selection to drag. False when it misses both. */
    fun press(viewport: Pt): Boolean {
        val sel = selection ?: return false
        val start = caret(sel, atEnd = false)
        val end = caret(sel, atEnd = true)
        val h = handles.grab(
            viewport, start?.let { handles.center(it.tip, isStart = true) }, end?.let { handles.center(it.tip, isStart = false) },
        ) ?: return false
        dragging = h
        fixed = if (h == TextHandles.Handle.START) sel.end else sel.start
        // Aim the drag at the caret the handle hangs from, not at the finger below it, so the end
        // follows the line the handle marks.
        val held = if (h == TextHandles.Handle.START) start else end
        grabOffset = held?.let { state.contentToViewport(it.middle) }?.let { Pt(it.x - viewport.x, it.y - viewport.y) } ?: Pt(0.0, 0.0)
        pressing = true
        return true
    }

    /** The pointer that selected, or grabbed a handle, moved to [viewport]. */
    fun dragTo(viewport: Pt) {
        if (dragging == null && word == null && !toolDrag) return
        dragAt = viewport
        follow(viewport)
        if (state.verticalScroll) {
            handles.autoscroll(viewport) { at ->
                onViewChanged()
                follow(at)
                requestRender()
            }
        }
        requestRender()
    }

    /** The pointer that selected, or grabbed a handle, lifted. */
    fun release() {
        handles.stopAutoscroll()
        val selecting = dragging != null || word != null
        val tool = toolDrag
        val marked = marking
        dragging = null
        fixed = null
        word = null
        dragAt = null
        pressing = false
        toolDrag = false
        anchor = null
        val sel = selection
        when {
            tool && (sel == null || sel.isEmpty) -> clear()
            tool && marked -> onMarked(sel!!)
            (selecting || tool) && sel != null -> onSettled()
        }
    }

    /** A second pointer came down mid-drag: a mark in the making is dropped, a selection stays. */
    fun interrupt() {
        if (toolDrag && marking) clear() else release()
    }

    fun clear() {
        pressGen++
        pressing = false
        if (selection == null && dragging == null && word == null && !toolDrag) return
        handles.stopAutoscroll()
        selection = null
        dragging = null
        fixed = null
        word = null
        dragAt = null
        toolDrag = false
        anchor = null
        marking = false
        reading.clear()
        readForSelection.clear()
        onCleared()
        requestRender()
    }

    private fun follow(viewport: Pt) {
        val h = dragging
        val target = if (h != null) Pt(viewport.x + grabOffset.x, viewport.y + grabOffset.y) else viewport
        val pos = posAt(state.viewportToContent(target)) ?: return
        val a = anchor
        selection = if (h != null) {
            val f = fixed ?: return
            if (pos == f) return
            TextSelection.between(f, pos)
        } else if (toolDrag) {
            TextSelection.between(a ?: return, pos)
        } else {
            val w = word ?: return
            if (pos < w.start) TextSelection(pos, w.end) else TextSelection(w.start, maxOf(pos, w.end))
        }
    }

    /** The boundary nearest a content point on the page under it, or the nearest page shown; null while its text is read. */
    private fun posAt(content: Pt): TextPos? {
        val (page, local) = state.pagePointAt(content) ?: return null
        val text = texts.peek(page)
        if (text == null) {
            if (state.document.pages[page].pdfPage != null && reading.add(page)) {
                texts.request(page) {
                    reading.remove(page)
                    dragAt?.let { follow(it) }
                    requestRender()
                }
            }
            return null
        }
        val x = (local.x * ptPerPx).toFloat()
        val y = (local.y * ptPerPx).toFloat()
        val offset = TextHit.offsetAt(text, x, y, reach = Float.POSITIVE_INFINITY)
        return if (offset < 0) null else TextPos(page, offset)
    }

    /** The selection's tint and handles, in content space; a marking drag's mark instead. */
    fun drawOverlay(r: Renderer) {
        val sel = selection ?: return
        val mark = drawMark
        if (marking && mark != null) {
            val visible = state.visiblePageRange() ?: return
            for (page in maxOf(sel.start.page, visible.first)..minOf(sel.end.page, visible.last)) {
                val text = texts.peek(page) ?: continue
                val quads = sel.quads(page, text)
                if (quads.isNotEmpty()) mark(r, page, quads)
            }
            return
        }
        val accent = state.palette.accent
        val tint = accent.withAlpha(70)
        forEachQuad(sel) { page, q -> r.fillRect(contentRect(page, q), tint) }
        caret(sel, atEnd = false)?.let { handles.draw(r, handles.center(it.tip, isStart = true), isStart = true, accent) }
        caret(sel, atEnd = true)?.let { handles.draw(r, handles.center(it.tip, isStart = false), isStart = false, accent) }
    }

    /** The viewport bounds of the selection's lines on screen, for its menu; null when none shows. */
    fun menuAnchor(): Rect? {
        val sel = selection ?: return null
        var l = Double.MAX_VALUE
        var t = Double.MAX_VALUE
        var r = -Double.MAX_VALUE
        var b = -Double.MAX_VALUE
        forEachQuad(sel) { page, q ->
            val c = contentRect(page, q)
            l = minOf(l, c.left)
            t = minOf(t, c.top)
            r = maxOf(r, c.right)
            b = maxOf(b, c.bottom)
        }
        if (l > r) return null
        val tl = state.contentToViewport(Pt(l, t))
        val br = state.contentToViewport(Pt(r, b))
        return Rect(tl.x, tl.y, br.x - tl.x, br.y - tl.y)
    }

    /** Runs [each] over the selection's line boxes on the pages shown, reading (once) a page whose text was let go. */
    private inline fun forEachQuad(sel: TextSelection, each: (Int, TextQuad) -> Unit) {
        val visible = state.visiblePageRange() ?: return
        for (page in maxOf(sel.start.page, visible.first)..minOf(sel.end.page, visible.last)) {
            val text = texts.peek(page)
            if (text == null) {
                if (readForSelection.add(page)) texts.request(page) { requestRender() }
                continue
            }
            for (q in sel.quads(page, text)) each(page, q)
        }
    }

    private fun contentRect(page: Int, q: TextQuad): Rect {
        val s = 1 / ptPerPx
        return state.fromPageSpaceRect(page, Rect(q.left * s, q.top * s, (q.right - q.left) * s, (q.bottom - q.top) * s))
    }

    /** A selection end's caret in content space: its screen-lower end, which a handle hangs from, and its middle. */
    private class Caret(val tip: Pt, val middle: Pt)

    private fun caret(sel: TextSelection, atEnd: Boolean): Caret? {
        val pos = if (atEnd) sel.end else sel.start
        if (pos.page !in state.drawablePageRange()) return null
        val text = texts.peek(pos.page) ?: return null
        val c = (if (atEnd) sel.endCaret(text) else sel.startCaret(text)) ?: return null
        val s = 1 / ptPerPx
        val top = state.fromPageSpace(pos.page, Pt(c.topX * s, c.topY * s))
        val bottom = state.fromPageSpace(pos.page, Pt(c.bottomX * s, c.bottomY * s))
        return Caret(if (top.y > bottom.y) top else bottom, Pt((top.x + bottom.x) / 2, (top.y + bottom.y) / 2))
    }

    private fun isBlank(text: PageText, i: Int): Boolean = Character.isSpaceChar(text.codepoint(i)) || Character.isWhitespace(text.codepoint(i))

    /** For a press on a space, the nearer of the characters either side; the space itself when neither is text. */
    private fun besideBlank(text: PageText, i: Int, x: Float, y: Float): Int {
        var best = i
        var bestDist = Float.MAX_VALUE
        for (j in intArrayOf(i - 1, i + 1)) {
            if (j !in 0 until text.length || !text.hasBox(j) || isBlank(text, j)) continue
            val dx = maxOf(text.left(j) - x, 0f, x - text.right(j))
            val dy = maxOf(text.top(j) - y, 0f, y - text.bottom(j))
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                best = j
                bestDist = dist
            }
        }
        return best
    }

    companion object {
        /** How far off a character a finger's long press may land and still take it. */
        const val TOUCH_SLOP_DP = 12.0
    }
}
