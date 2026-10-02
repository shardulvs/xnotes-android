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
 * The links of a laid-out flow. Prose, headings, lists and tables link their addresses; code
 * does not, the way a Markdown renderer leaves an address in code alone, and neither does maths.
 */
object FlowLinks {

    /** The links of each paragraph that has any, by paragraph index. */
    fun find(flow: TextFlow): Map<Int, List<TextLink>> {
        val found = HashMap<Int, List<TextLink>>()
        for ((i, para) in flow.paragraphs.withIndex()) {
            if (para.codeLang != null) continue
            val links = LinkFinder.find(para.plainText())
            if (links.isEmpty()) continue
            val kept = links.filter { link -> !touchesCodeOrMath(para.runs, link) }
            if (kept.isNotEmpty()) found[i] = kept
        }
        return found
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
