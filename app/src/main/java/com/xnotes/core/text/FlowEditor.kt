package com.xnotes.core.text

import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.FlowEditParagraph
import com.xnotes.core.history.FlowSplice
import com.xnotes.core.history.ParaSnapshot

/**
 * The single mutation gateway for a [TextFlow] (spec: push-after-apply). Every
 * op applies its edit and returns the [Command] describing it (null when the
 * edit was a no-op), which the caller pushes onto the history. Runs are kept
 * normalized (no empty runs, adjacent equal styles merged, no hand-set size in a
 * heading) after every op.
 */
class FlowEditor(private val flow: TextFlow) {

    /** Insert [text] at [pos]; '\n' splits paragraphs. Returns the command and the caret after it. */
    fun insertText(pos: FlowPos, text: String, style: CharStyle? = null): Pair<Command?, FlowPos> =
        replaceRange(FlowRange.caret(pos), text, style)

    /** Delete [range]; a cross-paragraph range merges the remnants into one paragraph. */
    fun deleteRange(range: FlowRange): Pair<Command?, FlowPos> = replaceRange(range, "")

    /**
     * Replace [range] with [text] in one undo step. New text takes [style] when
     * given, else the style at the range start (what a caret there would type).
     * New paragraphs from '\n' splits inherit the first paragraph's properties,
     * with [Paragraph.checked] cleared and [Paragraph.headingLevel] dropped on
     * continuations that start empty (Enter at the end of a heading leaves it). [adoptEndProps] makes a
     * cross-paragraph merge keep the END paragraph's properties instead (the
     * forward-delete-into-a-block rule).
     */
    fun replaceRange(
        range: FlowRange,
        text: String,
        style: CharStyle? = null,
        adoptEndProps: Boolean = false,
    ): Pair<Command?, FlowPos> {
        val r = range.normalized()
        if (flow.paragraphs.isEmpty()) {
            if (text.isEmpty()) return null to FlowPos.START
            val lines = text.split('\n')
            val paras = lines.mapTo(mutableListOf()) { line ->
                Paragraph(runsOf(line, style ?: CharStyle.DEFAULT))
            }
            val cmd = FlowSplice(flow, 0, emptyList(), paras).also { it.redo() }
            return cmd to FlowPos(paras.size - 1, lines.last().length)
        }
        val start = clamp(r.start)
        val end = clamp(r.end)
        if (start == end && text.isEmpty()) return null to start

        if (start.para == end.para && '\n' !in text) {
            val para = flow.paragraphs[start.para]
            val before = ParaSnapshot.of(para)
            val insStyle = style ?: styleForInsert(para, start.offset)
            deleteWithin(para, start.offset, end.offset)
            insertWithin(para, start.offset, text, insStyle)
            val caret = FlowPos(start.para, start.offset + text.length)
            if (before.matches(para)) return null to caret
            para.touch()
            return FlowEditParagraph(para, before, ParaSnapshot.of(para)) to caret
        }

        val cells = CellIndex(flow.paragraphs)
        if (!cells.isEmpty && cells.shapeOf(FlowRange(start, end)) != SelShape.Text) {
            return TableEditor(flow).replaceSelection(FlowRange(start, end), text, style, cells)
        }
        val first = flow.paragraphs[start.para]
        val props = if (adoptEndProps) flow.paragraphs[end.para] else first
        val insStyle = style ?: styleForInsert(first, start.offset)
        val head = copyRunsBefore(first, start.offset)
        val tail = copyRunsAfter(flow.paragraphs[end.para], end.offset)
        val lines = text.split('\n')
        val newParas = lines.mapIndexedTo(mutableListOf()) { i, line ->
            val runs = mutableListOf<Run>()
            if (i == 0) runs += head
            if (line.isNotEmpty()) runs += Run(line, insStyle)
            if (i == lines.lastIndex) runs += tail
            Paragraph(
                runs, props.align, props.indent, props.list,
                checked = if (i == 0) props.checked else false,
                codeLang = props.codeLang,
                table = props.table,
                cellStart = i == 0 && props.cellStart,
                // A heading continues only into the remainder it was split into.
                headingLevel = if (i == 0 || (i == lines.lastIndex && tail.isNotEmpty())) props.headingLevel else 0,
            ).also { normalize(it) }
        }
        val removed = flow.paragraphs.subList(start.para, end.para + 1).toList()
        val cmd = FlowSplice(flow, start.para, removed, newParas).also { it.redo() }
        val caretOff = (if (lines.size == 1) start.offset else 0) + lines.last().length
        return cmd to FlowPos(start.para + lines.lastIndex, caretOff)
    }

    /** Rewrite character styles over [range] via [mutate]; null when nothing changed. */
    fun setCharStyle(range: FlowRange, mutate: (CharStyle) -> CharStyle): Command? {
        val r = range.normalized()
        if (r.collapsed || flow.paragraphs.isEmpty()) return null
        val cmds = mutableListOf<Command>()
        for ((pi, from, to) in CellIndex(flow.paragraphs).spans(flow.paragraphs, FlowRange(clamp(r.start), clamp(r.end)))) {
            val para = flow.paragraphs[pi]
            if (to <= from) continue
            val before = ParaSnapshot.of(para)
            applyCharStyleWithin(para, from, to, mutate)
            if (!before.matches(para)) {
                para.touch()
                cmds += FlowEditParagraph(para, before, ParaSnapshot.of(para))
            }
        }
        return combined(cmds)
    }

    /** Rewrite paragraph properties over [range] via [mutate]; null when nothing changed. */
    fun setParaStyle(range: FlowRange, mutate: (Paragraph) -> Unit): Command? {
        val r = range.normalized()
        if (flow.paragraphs.isEmpty()) return null
        val cmds = mutableListOf<Command>()
        val spans = CellIndex(flow.paragraphs).spans(flow.paragraphs, FlowRange(clamp(r.start), clamp(r.end)))
        for (pi in spans.map { it.para }.distinct()) {
            val para = flow.paragraphs[pi]
            val before = ParaSnapshot.of(para)
            mutate(para)
            para.indent = para.indent.coerceIn(0, Paragraph.MAX_INDENT)
            para.headingLevel = para.headingLevel.coerceIn(0, Paragraph.MAX_HEADING)
            if (para.table != null) {
                // Cells hold rich text only: no lists, code lines, headings or indents.
                para.list = ListKind.NONE
                para.checked = false
                para.codeLang = null
                para.indent = 0
                para.headingLevel = 0
            }
            if (!before.matches(para)) {
                para.touch()
                cmds += FlowEditParagraph(para, before, ParaSnapshot.of(para))
            }
        }
        return combined(cmds)
    }

    /** Flip a checkbox item's checked state; null for non-check paragraphs. */
    fun toggleChecked(paraIndex: Int): Command? {
        val para = flow.paragraphs.getOrNull(paraIndex) ?: return null
        if (para.list != ListKind.CHECK) return null
        val before = ParaSnapshot.of(para)
        para.checked = !para.checked
        para.touch()
        return FlowEditParagraph(para, before, ParaSnapshot.of(para))
    }

    /** Splice ready-made paragraphs in at [at] (markdown paste). */
    fun insertParagraphs(at: Int, paras: List<Paragraph>): Command =
        FlowSplice(flow, at.coerceIn(0, flow.paragraphs.size), emptyList(), paras.toList())
            .also { it.redo() }

    /** Replace [removeCount] paragraphs at [at] with [paras] (paste over an empty line). */
    fun replaceParagraphs(at: Int, removeCount: Int, paras: List<Paragraph>): Command {
        val start = at.coerceIn(0, flow.paragraphs.size)
        val n = removeCount.coerceIn(0, flow.paragraphs.size - start)
        val removed = flow.paragraphs.subList(start, start + n).toList()
        return FlowSplice(flow, start, removed, paras.toList()).also { it.redo() }
    }

    /** Append [count] empty default paragraphs (the tap-below-the-text fill). */
    fun appendEmptyLines(count: Int): Pair<Command, FlowPos> {
        val paras = List(count.coerceAtLeast(1)) { Paragraph() }
        val cmd = FlowSplice(flow, flow.paragraphs.size, emptyList(), paras).also { it.redo() }
        return cmd to FlowPos(flow.paragraphs.size - 1, 0)
    }

    /** The style a caret at [pos] would type with: the char before it, else at it. */
    fun charStyleAt(pos: FlowPos): CharStyle {
        val para = flow.paragraphs.getOrNull(pos.para) ?: return CharStyle.DEFAULT
        return styleForInsert(para, pos.offset.coerceIn(0, para.length))
    }

    /**
     * The contiguous single-paragraph range around [pos] whose characters all carry the same
     * hyperlink, or null when the caret is not on a link. Lets an "Edit link" action target the
     * whole link even from a collapsed caret. The link probed is the one a caret there would type.
     */
    fun linkRangeAt(pos: FlowPos): FlowRange? {
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        val len = para.length
        if (len == 0) return null
        val probe = (pos.offset.coerceIn(0, len).let { if (it > 0) it - 1 else 0 })
        val link = styleOfCharAt(para, probe)?.link ?: return null
        var start = probe
        while (start > 0 && styleOfCharAt(para, start - 1)?.link == link) start--
        var end = probe + 1
        while (end < len && styleOfCharAt(para, end)?.link == link) end++
        return FlowRange(FlowPos(pos.para, start), FlowPos(pos.para, end))
    }

    /** The style of the first character inside [range] (what a format bar reports for selections). */
    fun styleAtRangeStart(range: FlowRange): CharStyle {
        val r = range.normalized()
        val para = flow.paragraphs.getOrNull(r.start.para) ?: return CharStyle.DEFAULT
        if (para.length == 0) return styleForInsert(para, 0)
        return styleOfCharAt(para, r.start.offset.coerceIn(0, para.length - 1)) ?: CharStyle.DEFAULT
    }

    // --- internals ---

    internal fun combined(cmds: List<Command>): Command? = when {
        cmds.isEmpty() -> null
        cmds.size == 1 -> cmds[0]
        else -> CompositeCommand(cmds)
    }

    internal fun clamp(pos: FlowPos): FlowPos {
        val pi = pos.para.coerceIn(0, flow.paragraphs.size - 1)
        return FlowPos(pi, pos.offset.coerceIn(0, flow.paragraphs[pi].length))
    }

    private fun runsOf(text: String, style: CharStyle): MutableList<Run> =
        if (text.isEmpty()) mutableListOf() else mutableListOf(Run(text, style))

    /**
     * The style new text takes at [offset]: whatever the character before it has,
     * except that a formula carries over only from strictly within it, which is
     * the same reach over which the caret shows its source. Its closing edge
     * belongs to whatever comes next, so typing on past an equation writes beside
     * it; that boundary has to match the one reveal uses, or text would land
     * inside a formula that is not showing what it is doing with it. An empty
     * heading has nothing to copy, so it types as a heading's own text.
     */
    private fun styleForInsert(para: Paragraph, offset: Int): CharStyle {
        val base = styleOfCharAt(para, if (offset > 0) offset - 1 else 0)
            ?: if (para.headingLevel > 0) Paragraph.HEADING_STYLE else CharStyle.DEFAULT
        if (!base.math) return base
        var seen = 0
        for (run in para.runs) {
            val end = seen + run.text.length
            if (run.style.math && offset > seen && offset < end) return base
            seen = end
        }
        return base.copy(math = false)
    }

    private fun styleOfCharAt(para: Paragraph, index: Int): CharStyle? {
        var seen = 0
        for (run in para.runs) {
            val end = seen + run.text.length
            if (index < end) return run.style
            seen = end
        }
        return para.runs.lastOrNull()?.style
    }

    private fun deleteWithin(para: Paragraph, from: Int, to: Int) {
        if (to <= from) return
        var seen = 0
        for (run in para.runs) {
            val start = seen
            val end = seen + run.text.length
            seen = end
            val cutFrom = maxOf(from, start)
            val cutTo = minOf(to, end)
            if (cutTo > cutFrom) run.text = run.text.removeRange(cutFrom - start, cutTo - start)
        }
        normalize(para)
    }

    private fun insertWithin(para: Paragraph, offset: Int, text: String, style: CharStyle) {
        if (text.isEmpty()) return
        var seen = 0
        var i = 0
        while (i < para.runs.size) {
            val run = para.runs[i]
            val end = seen + run.text.length
            if (offset <= end) {
                val local = offset - seen
                when {
                    run.style == style ->
                        run.text = StringBuilder(run.text).insert(local, text).toString()
                    local == 0 -> para.runs.add(i, Run(text, style))
                    local == run.text.length -> para.runs.add(i + 1, Run(text, style))
                    else -> {
                        val tailText = run.text.substring(local)
                        run.text = run.text.substring(0, local)
                        para.runs.add(i + 1, Run(text, style))
                        para.runs.add(i + 2, Run(tailText, run.style))
                    }
                }
                normalize(para)
                return
            }
            seen = end
            i++
        }
        para.runs.add(Run(text, style))
        normalize(para)
    }

    /**
     * Merge adjacent equal-style runs and drop empties (an empty paragraph keeps zero runs).
     * A heading's level sizes its text, so text sized by hand makes it body text, the
     * rest of it keeping the size it showed.
     */
    internal fun normalize(para: Paragraph) {
        para.runs.removeAll { it.text.isEmpty() }
        if (para.headingLevel > 0 && para.runs.any { it.style.sizePt != null }) {
            val size = para.textSizePt(flow)
            for (run in para.runs) if (run.style.sizePt == null) run.style = run.style.copy(sizePt = size)
            para.headingLevel = 0
        }
        var i = 0
        while (i < para.runs.size - 1) {
            val a = para.runs[i]
            val b = para.runs[i + 1]
            if (a.style == b.style) {
                a.text += b.text
                para.runs.removeAt(i + 1)
            } else {
                i++
            }
        }
    }

    /** Ensure a run boundary exists at [offset] (no-op at run edges / paragraph ends). */
    private fun splitAt(para: Paragraph, offset: Int) {
        var seen = 0
        for ((i, run) in para.runs.withIndex()) {
            val end = seen + run.text.length
            if (offset in (seen + 1) until end) {
                val local = offset - seen
                val tailText = run.text.substring(local)
                run.text = run.text.substring(0, local)
                para.runs.add(i + 1, Run(tailText, run.style))
                return
            }
            seen = end
        }
    }

    private fun applyCharStyleWithin(para: Paragraph, from: Int, to: Int, mutate: (CharStyle) -> CharStyle) {
        splitAt(para, to)
        splitAt(para, from)
        var seen = 0
        for (run in para.runs) {
            val end = seen + run.text.length
            if (seen >= from && end <= to) run.style = mutate(run.style)
            seen = end
        }
        normalize(para)
    }

    internal fun copyRunsBefore(para: Paragraph, offset: Int): MutableList<Run> {
        val out = mutableListOf<Run>()
        var seen = 0
        for (run in para.runs) {
            val end = seen + run.text.length
            when {
                end <= offset -> out.add(run.deepCopy())
                seen < offset -> out.add(Run(run.text.substring(0, offset - seen), run.style))
            }
            seen = end
        }
        return out
    }

    internal fun copyRunsAfter(para: Paragraph, offset: Int): MutableList<Run> {
        val out = mutableListOf<Run>()
        var seen = 0
        for (run in para.runs) {
            val end = seen + run.text.length
            when {
                seen >= offset -> out.add(run.deepCopy())
                end > offset -> out.add(Run(run.text.substring(offset - seen), run.style))
            }
            seen = end
        }
        return out
    }
}
