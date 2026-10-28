package com.xnotes.core.pdf

import com.xnotes.core.geometry.Rect
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.TextFlow

/**
 * One line's share of [link] in the flow: its box on page [page] (page-local content px) and the
 * paragraph characters [start, end) it covers there. A link wrapped over two lines is two of these.
 */
class PlacedLink(val page: Int, val rect: Rect, val link: TextLink, val para: Int, val start: Int, val end: Int) {
    val uri: String get() = link.uri
}

/**
 * The links of a laid-out flow: the text the user linked, and the addresses written out in
 * prose, headings, lists and tables. Code does not link its addresses, the way a Markdown
 * renderer leaves an address in code alone, and neither does maths.
 */
object FlowLinks {

    /** The links of each paragraph that has any, by paragraph index. */
    fun find(flow: TextFlow): Map<Int, List<TextLink>> {
        val found = HashMap<Int, List<TextLink>>()
        for ((i, para) in flow.paragraphs.withIndex()) {
            val linked = linkedRuns(para.runs)
            val written = if (para.codeLang != null) {
                emptyList()
            } else {
                LinkFinder.find(para.plainText()).filter { link ->
                    !touchesCodeOrMath(para.runs, link) && linked.none { it.start < link.end && link.start < it.end }
                }
            }
            val kept = (linked + written).sortedBy { it.start }
            if (kept.isNotEmpty()) found[i] = kept
        }
        return found
    }

    /** The stretches of [runs] linked to an address, neighbouring runs with the same one joined. */
    private fun linkedRuns(runs: List<com.xnotes.core.text.Run>): List<TextLink> {
        val out = mutableListOf<TextLink>()
        var at = 0
        for (run in runs) {
            val end = at + run.text.length
            val uri = run.style.link
            val last = out.lastOrNull()
            when {
                uri == null -> Unit
                last != null && last.end == at && last.uri == uri -> out[out.lastIndex] = last.copy(end = end)
                else -> out += TextLink(at, end, uri)
            }
            at = end
        }
        return out
    }

    fun place(flow: TextFlow, frame: FlowFrame, found: Map<Int, List<TextLink>> = find(flow)): List<PlacedLink> {
        if (found.isEmpty()) return emptyList()
        val out = mutableListOf<PlacedLink>()
        for ((pageIndex, line) in frame.lines) {
            val links = found[line.paraIndex] ?: continue
            for (link in links) {
                val a = maxOf(link.start, line.startChar)
                val b = minOf(link.end, line.endChar)
                if (b <= a) continue
                val x0 = line.caretX(a)
                val x1 = line.caretX(b)
                out += PlacedLink(pageIndex, Rect(x0, line.top, x1 - x0, line.height), link, line.paraIndex, a, b)
            }
        }
        return out
    }

    private fun touchesCodeOrMath(runs: List<com.xnotes.core.text.Run>, link: TextLink): Boolean {
        var at = 0
        for (run in runs) {
            val end = at + run.text.length
            if (end > link.start && at < link.end && (run.style.code || run.style.math)) return true
            at = end
        }
        return false
    }
}
