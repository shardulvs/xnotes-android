package com.xnotes.core.pdf

import com.xnotes.core.text.CellIndex
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.TextFlow

/**
 * A heading of the flow as a bookmark sees it: paragraph [para] of [level], read as [title], whose
 * first line is on layout page [page] with its top at page-local [top].
 */
class Heading(val para: Int, val level: Int, val title: String, val page: Int, val top: Double)

object FlowHeadings {

    /** The flow's headings in reading order; one in a table cell or with no text is not a bookmark. */
    fun find(flow: TextFlow, frame: FlowFrame): List<Heading> {
        val cells = CellIndex(flow.paragraphs)
        val first = HashMap<Int, Pair<Int, Double>>()
        for ((page, line) in frame.lines) if (line.paraIndex !in first) first[line.paraIndex] = page to line.top
        val out = mutableListOf<Heading>()
        for ((i, para) in flow.paragraphs.withIndex()) {
            if (para.headingLevel <= 0 || cells.inTable(i)) continue
            val (page, top) = first[i] ?: continue
            // A formula reads as the LaTeX a copy of it gives, so a bookmark names it the same way.
            val title = para.runs.joinToString("") {
                when {
                    !it.style.math -> it.text
                    it.style.mathDisplay -> "\$\$${it.text}\$\$"
                    else -> "\$${it.text}\$"
                }
            }.trim().replace(WHITESPACE, " ")
            if (title.isNotEmpty()) out += Heading(i, para.headingLevel, title, page, top)
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")
}
