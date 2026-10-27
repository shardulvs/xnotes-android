package com.xnotes.core.history

import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMarks
import com.xnotes.core.text.FlowQuote
import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TableSnapshot
import com.xnotes.core.text.TextFlow

/**
 * A full value snapshot of one flow paragraph (runs deep-copied). Applying it
 * restores content and paragraph properties on the identity-stable [Paragraph],
 * so a snapshot pair makes an idempotent undo/redo swap.
 */
class ParaSnapshot private constructor(
    private val runs: List<Run>,
    private val align: ParaAlign,
    private val indent: Int,
    private val list: ListKind,
    private val checked: Boolean,
    private val codeLang: String?,
    private val table: FlowTable?,
    private val cellStart: Boolean,
    private val headingLevel: Int,
    private val rule: Boolean,
    private val breakBefore: Boolean,
    private val breakToPage: Int?,
    private val listStart: Int?,
    private val quote: FlowQuote?,
) {
    fun applyTo(para: Paragraph) {
        para.runs.clear()
        // Install copies so this snapshot survives later mutation of the live runs.
        runs.mapTo(para.runs) { it.deepCopy() }
        para.align = align
        para.indent = indent
        para.list = list
        para.checked = checked
        para.codeLang = codeLang
        para.table = table
        para.cellStart = cellStart
        para.headingLevel = headingLevel
        para.rule = rule
        para.breakBefore = breakBefore
        para.breakToPage = breakToPage
        para.listStart = listStart
        para.quote = quote
        para.touch()
    }

    /** True when applying this snapshot to [para] would change nothing. */
    fun matches(para: Paragraph): Boolean =
        align == para.align && indent == para.indent && list == para.list &&
            checked == para.checked && codeLang == para.codeLang &&
            table === para.table && cellStart == para.cellStart &&
            headingLevel == para.headingLevel && rule == para.rule && breakBefore == para.breakBefore &&
            breakToPage == para.breakToPage &&
            listStart == para.listStart && quote === para.quote &&
            runs.size == para.runs.size &&
            runs.indices.all { runs[it].text == para.runs[it].text && runs[it].style == para.runs[it].style }

    companion object {
        fun of(para: Paragraph): ParaSnapshot = ParaSnapshot(
            para.runs.map { it.deepCopy() },
            para.align, para.indent, para.list, para.checked, para.codeLang, para.table, para.cellStart,
            para.headingLevel, para.rule, para.breakBefore, para.breakToPage, para.listStart, para.quote,
        )
    }
}

/**
 * An in-place edit of one identity-stable paragraph (a typing burst, a restyle,
 * a checkbox toggle), recorded as before/after snapshots.
 */
class FlowEditParagraph(
    private val para: Paragraph,
    private val before: ParaSnapshot,
    private val after: ParaSnapshot,
) : Command {
    override fun redo() = after.applyTo(para)
    override fun undo() = before.applyTo(para)
}

/**
 * Replace the [count] = `removed.size` paragraphs at [index] with [inserted]
 * (split, merge, paste, multi-paragraph delete). Undo restores the original
 * paragraph objects, so other commands' references to them stay valid. Built
 * un-applied; the flow editor applies it with the first [redo].
 */
class FlowSplice(
    private val flow: TextFlow,
    private val index: Int,
    private val removed: List<Paragraph>,
    private val inserted: List<Paragraph>,
) : Command {
    private var applied = false

    override fun redo() {
        if (applied) return
        replace(removed.size, inserted)
        applied = true
    }

    override fun undo() {
        if (!applied) return
        replace(inserted.size, removed)
        applied = false
    }

    private fun replace(count: Int, with: List<Paragraph>) {
        repeat(count.coerceAtMost(flow.paragraphs.size - index)) { flow.paragraphs.removeAt(index) }
        flow.paragraphs.addAll(index.coerceIn(0, flow.paragraphs.size), with)
        flow.touch()
    }
}

/** A table's geometry or look changed (widths, row heights, style), as before/after snapshots. */
class FlowTableEdit(
    private val flow: TextFlow,
    private val table: FlowTable,
    private val before: TableSnapshot,
    private val after: TableSnapshot,
) : Command {
    override fun redo() {
        after.applyTo(table)
        flow.touch()
    }

    override fun undo() {
        before.applyTo(table)
        flow.touch()
    }
}

/**
 * A page appended automatically because the flow overflowed while typing. Undo
 * removes the page only while it is still empty — ink drawn on it afterwards
 * must never be silently destroyed by undoing the text edit that created it.
 */
class AddPageAuto(
    private val document: Document,
    private val page: Page,
    private val index: Int,
) : Command {
    private var marks = PageMarks.NONE

    override fun redo() {
        if (document.pages.none { it === page }) document.insertPage(page, index, marks)
    }

    override fun undo() {
        if (page.items.isEmpty()) marks = document.removePage(page)
    }
}
