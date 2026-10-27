package com.xnotes.core.text

import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.FlowSplice
import com.xnotes.core.history.FlowTableEdit

/**
 * Structural table edits on a [TextFlow], push-after-apply like [FlowEditor]:
 * each op applies itself and returns the [Command] (plus where the caret should
 * go). Row and column changes rebuild the table's paragraph block in one
 * [FlowSplice], reusing the cell paragraphs, alongside a [FlowTableEdit] for the
 * widths and row heights. Every op keeps the invariants [normalizeTables]
 * repairs: whole rows, and body paragraphs on both sides of a table.
 */
class TableEditor(private val flow: TextFlow) {

    private val paras: MutableList<Paragraph> get() = flow.paragraphs

    /**
     * Insert an empty [rows] x [cols] table at [pos] (splitting its paragraph; an
     * empty plain line is replaced). Null when [pos] is inside a table.
     */
    fun insertTable(pos: FlowPos, rows: Int, cols: Int, style: TableStyle): Pair<Command, FlowPos>? {
        val table = FlowTable(FlowTable.even(cols), List(rows) { 0.0 }, style)
        val cells = List(rows * cols) { Paragraph(table = table, cellStart = true) }
        if (paras.isEmpty()) {
            val list = listOf(Paragraph()) + cells + Paragraph()
            return FlowSplice(flow, 0, emptyList(), list).also { it.redo() } to FlowPos(1, 0)
        }
        if (CellIndex(paras).inTable(pos.para)) return null
        val cmds = mutableListOf<Command>()
        var at = pos.para.coerceIn(0, paras.size - 1)
        val para = paras[at]
        val offset = pos.offset.coerceIn(0, para.length)
        var remove = 0
        when {
            para.length == 0 && para.isDefaultStyle() -> remove = 1
            offset == 0 -> Unit
            offset == para.length -> at++
            else -> {
                FlowEditor(flow).replaceRange(FlowRange.caret(FlowPos(at, offset)), "\n").first?.let(cmds::add)
                at++
            }
        }
        val before = paras.getOrNull(at - 1)
        val after = paras.getOrNull(at + remove)
        val list = mutableListOf<Paragraph>()
        if (before == null || before.table != null) list.add(Paragraph())
        list.addAll(cells)
        if (after == null || after.table != null) list.add(Paragraph())
        cmds += FlowSplice(flow, at, paras.subList(at, at + remove).toList(), list).also { it.redo() }
        val firstCell = at + if (list.first().table == null) 1 else 0
        return (if (cmds.size == 1) cmds[0] else CompositeCommand(cmds)) to FlowPos(firstCell, 0)
    }

    /**
     * Replace a selection that crosses cells or tables with [text]: a cell
     * rectangle has its cells emptied (the grid stays) and the text lands in its
     * top-left cell; a mixed selection deletes the covered body text and whole
     * table rows (a fully covered table goes), merging the body ends when no
     * table is left between them.
     */
    fun replaceSelection(
        range: FlowRange,
        text: String,
        style: CharStyle?,
        cells: CellIndex = CellIndex(paras),
    ): Pair<Command?, FlowPos> {
        val r = range.normalized()
        val (cmd, caret) = when (val shape = cells.shapeOf(r)) {
            is SelShape.Cells -> clearCells(shape)
            SelShape.Mixed -> deleteMixed(r, cells)
            SelShape.Text -> FlowEditor(flow).replaceRange(r, "")
        }
        if (text.isEmpty()) return cmd to caret
        val (ins, end) = FlowEditor(flow).replaceRange(FlowRange.caret(caret), text, style)
        return combined(listOfNotNull(cmd, ins)) to end
    }

    /** Empty every cell of [shape]'s rectangle; the caret goes to its top-left cell. */
    fun clearCells(shape: SelShape.Cells): Pair<Command?, FlowPos> {
        val b = shape.block
        val cmds = mutableListOf<Command>()
        val ed = FlowEditor(flow)
        for (row in shape.rows.reversed()) {
            for (col in shape.cols.reversed()) {
                if (!b.hasCell(row, col)) continue
                val f = b.cellFirstPara(row, col)
                val l = b.cellLastPara(row, col)
                ed.replaceRange(FlowRange(FlowPos(f, 0), FlowPos(l, paras[l].length)), "").first?.let(cmds::add)
            }
        }
        return combined(cmds) to FlowPos(b.cellFirstPara(shape.rows.first, shape.cols.first), 0)
    }

    private fun deleteMixed(r: FlowRange, cells: CellIndex): Pair<Command?, FlowPos> {
        val s = r.start
        val e = r.end
        val firstPara = cells.blockAt(s.para)?.first ?: s.para
        val lastPara = cells.blockAt(e.para)?.last ?: e.para
        val ed = FlowEditor(flow)
        val out = mutableListOf<Paragraph>()
        val tableEdits = mutableListOf<Command>()
        var head: Paragraph? = null
        var caretPara: Paragraph? = null
        var caretOffset = 0
        var p = firstPara
        while (p <= lastPara) {
            val b = cells.blockAt(p)
            if (b == null) {
                val para = paras[p]
                if (p == s.para) {
                    head = Paragraph(
                        ed.copyRunsBefore(para, s.offset), para.align, para.indent, para.list, para.checked, para.codeLang,
                        headingLevel = para.headingLevel, quote = para.quote,
                    )
                    out.add(head)
                    caretPara = head
                    caretOffset = head.length
                } else if (p == e.para) {
                    val tail = ed.copyRunsAfter(para, e.offset)
                    if (head != null && out.last() === head) {
                        head.runs.addAll(tail)
                        ed.normalize(head)
                    } else {
                        out.add(
                            Paragraph(
                                tail, para.align, para.indent, para.list, para.checked, para.codeLang,
                                headingLevel = para.headingLevel, quote = para.quote,
                            ),
                        )
                        if (caretPara == null) caretPara = out.last()
                    }
                }
                p++
                continue
            }
            val covered = cells.mixedRows(b, r)
            val kept = (0 until b.rows).filter { it !in covered }
            if (kept.isNotEmpty()) {
                for (row in kept) for (q in b.rowFirstPara(row)..b.rowLastPara(row)) out.add(paras[q])
                val before = b.table.snapshot()
                tableEdits += FlowTableEdit(flow, b.table, before, before.copy(minHeights = kept.map { b.table.minHeightPt(it) }))
            }
            p = b.last + 1
        }
        if (out.isEmpty()) out.add(Paragraph())
        val padded = withTableSeparators(out, paras.getOrNull(firstPara - 1), paras.getOrNull(lastPara + 1))
        out.clear()
        out.addAll(padded)
        val splice = FlowSplice(flow, firstPara, paras.subList(firstPara, lastPara + 1).toList(), out).also { it.redo() }
        tableEdits.forEach { it.redo() }
        val target = caretPara ?: out.firstOrNull { it.table == null } ?: out.first()
        val caret = FlowPos(firstPara + out.indexOfFirst { it === target }, caretOffset.coerceAtMost(target.length))
        return combined(listOf(splice) + tableEdits) to caret
    }

    // --- structure (edit mode) ---

    /** Insert an empty row before row [at] (rows = append); cells copy the alignment of their neighbour. */
    fun insertRow(table: FlowTable, at: Int): Pair<Command, FlowPos>? {
        val b = CellIndex(paras).blockOf(table) ?: return null
        val grid = b.grid(paras)
        val row = at.coerceIn(0, grid.size)
        val like = grid.getOrNull(if (row < grid.size) row else row - 1)
        grid.add(row, MutableList(b.cols) { c -> mutableListOf(emptyCell(table, like?.getOrNull(c)?.firstOrNull())) })
        val heights = table.minHeights.toMutableList().apply { add(row.coerceAtMost(size), 0.0) }
        val cmd = rebuild(b, grid, table.snapshot().copy(minHeights = heights))
        return cmd to cellPos(table, row, 0)
    }

    /** Insert an empty column before column [at] (cols = append); it takes an even share of the width. */
    fun insertCol(table: FlowTable, at: Int): Pair<Command, FlowPos>? {
        val b = CellIndex(paras).blockOf(table) ?: return null
        val grid = b.grid(paras)
        val col = at.coerceIn(0, b.cols)
        for (row in grid) {
            val like = row.getOrNull(if (col < row.size) col else col - 1)?.firstOrNull()
            row.add(col, mutableListOf(emptyCell(table, like)))
        }
        val n = b.cols + 1
        val widths = table.widths.map { it * (n - 1) / n }.toMutableList().apply { add(col, 1.0 / n) }
        val cmd = rebuild(b, grid, table.snapshot().copy(widths = FlowTable.normalized(widths)))
        return cmd to cellPos(table, 0, col)
    }

    /** Delete row [row]; deleting the last one deletes the table. */
    fun deleteRow(table: FlowTable, row: Int): Pair<Command, FlowPos>? {
        val b = CellIndex(paras).blockOf(table) ?: return null
        if (b.rows <= 1) return deleteTable(table)
        val grid = b.grid(paras)
        if (row !in grid.indices) return null
        grid.removeAt(row)
        val heights = table.minHeights.toMutableList().apply { if (row < size) removeAt(row) }
        val cmd = rebuild(b, grid, table.snapshot().copy(minHeights = heights))
        return cmd to cellPos(table, (row - 1).coerceAtLeast(0), 0)
    }

    /** Delete column [col]; deleting the last one deletes the table. */
    fun deleteCol(table: FlowTable, col: Int): Pair<Command, FlowPos>? {
        val b = CellIndex(paras).blockOf(table) ?: return null
        if (b.cols <= 1) return deleteTable(table)
        if (col !in 0 until b.cols) return null
        val grid = b.grid(paras)
        for (row in grid) row.removeAt(col)
        val widths = table.widths.toMutableList().apply { removeAt(col) }
        val cmd = rebuild(b, grid, table.snapshot().copy(widths = FlowTable.normalized(widths)))
        return cmd to cellPos(table, 0, (col - 1).coerceAtLeast(0))
    }

    /** Move row [from] so it ends up at index [to]. */
    fun moveRow(table: FlowTable, from: Int, to: Int): Command? {
        val b = CellIndex(paras).blockOf(table) ?: return null
        if (from !in 0 until b.rows || to !in 0 until b.rows || from == to) return null
        val grid = b.grid(paras)
        grid.add(to, grid.removeAt(from))
        val heights = List(b.rows) { table.minHeightPt(it) }.toMutableList()
        heights.add(to, heights.removeAt(from))
        return rebuild(b, grid, table.snapshot().copy(minHeights = heights))
    }

    /** Move column [from] so it ends up at index [to]; its width travels with it. */
    fun moveCol(table: FlowTable, from: Int, to: Int): Command? {
        val b = CellIndex(paras).blockOf(table) ?: return null
        if (from !in 0 until b.cols || to !in 0 until b.cols || from == to) return null
        val grid = b.grid(paras)
        for (row in grid) row.add(to, row.removeAt(from))
        val widths = table.widths.toMutableList()
        widths.add(to, widths.removeAt(from))
        return rebuild(b, grid, table.snapshot().copy(widths = widths))
    }

    /** Remove the whole table; the caret lands on the paragraph that followed it. */
    fun deleteTable(table: FlowTable): Pair<Command, FlowPos>? {
        val b = CellIndex(paras).blockOf(table) ?: return null
        val removed = paras.subList(b.first, b.last + 1).toList()
        val cmd = FlowSplice(flow, b.first, removed, emptyList()).also { it.redo() }
        return cmd to FlowPos(b.first.coerceAtMost(paras.size - 1).coerceAtLeast(0), 0)
    }

    /** Replace the table's geometry/look wholesale (width drags, row heights, restyle, the magic wand). */
    fun setSnapshot(table: FlowTable, after: TableSnapshot): Command? {
        val before = table.snapshot()
        if (before == after) return null
        return FlowTableEdit(flow, table, before, after).also { it.redo() }
    }

    /** The caret position at the start of cell ([row], [col]) of [table], clamped into the grid. */
    fun cellPos(table: FlowTable, row: Int, col: Int): FlowPos {
        val b = CellIndex(paras).blockOf(table) ?: return FlowPos.START
        val r = row.coerceIn(0, b.rows - 1)
        val c = col.coerceIn(0, b.cols - 1)
        return FlowPos(if (b.hasCell(r, c)) b.cellFirstPara(r, c) else b.first, 0)
    }

    private fun emptyCell(table: FlowTable, like: Paragraph?): Paragraph =
        Paragraph(align = like?.align ?: ParaAlign.LEFT, table = table, cellStart = true)

    private fun rebuild(b: TableBlock, grid: List<List<List<Paragraph>>>, after: TableSnapshot): Command {
        val old = paras.subList(b.first, b.last + 1).toList()
        val new = grid.flatMap { row -> row.flatten() }
        val splice = FlowSplice(flow, b.first, old, new).also { it.redo() }
        val edit = FlowTableEdit(flow, b.table, b.table.snapshot(), after).also { it.redo() }
        return CompositeCommand(listOf(splice, edit))
    }

    private fun combined(cmds: List<Command>): Command? = when {
        cmds.isEmpty() -> null
        cmds.size == 1 -> cmds[0]
        else -> CompositeCommand(cmds)
    }
}
