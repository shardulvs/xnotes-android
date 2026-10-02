package com.xnotes.core.pdf

import com.xnotes.core.text.CellIndex
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.TableBlock
import com.xnotes.core.text.TextFlow

/**
 * One element of a tagged PDF's logical structure: a standard type ("P", "H1", "L", "TD", ...)
 * and the elements under it in reading order. What an element shows on the page is recorded by
 * the writer as it paints; this is only the shape a screen reader walks.
 */
class StructNode(val type: String) {
    val kids = mutableListOf<StructNode>()

    /** Replacement text for a reader, as a figure or formula has. */
    var alt: String? = null

    /** A list's ListNumbering attribute: Disc, Decimal or None. */
    var numbering: String? = null

    /** A header cell's Scope attribute. */
    var scope: String? = null

    /** Stays while its parent does even with nothing in it, so a table keeps a full grid. */
    var keep = false

    fun add(type: String): StructNode = StructNode(type).also { kids += it }

    /**
     * Drop every part of this subtree that shows nothing, by [shows] (whether a node itself holds
     * content). True when something below or at this node shows.
     */
    fun prune(shows: (StructNode) -> Boolean): Boolean {
        var any = shows(this)
        val it = kids.iterator()
        while (it.hasNext()) {
            val kid = it.next()
            if (kid.prune(shows)) any = true else if (!kid.keep) it.remove()
        }
        return any
    }
}

/**
 * The logical structure of a flow: headings, paragraphs, lists nested by indent, code blocks and
 * tables, the way a tagged PDF presents them to a reader.
 */
object FlowStructure {

    /** The flow's top-level [blocks] in reading order, and where each paragraph's parts belong. */
    class Tree(val blocks: List<StructNode>, private val text: Array<StructNode?>, private val label: Array<StructNode?>) {
        /** The element paragraph [para]'s characters belong to. */
        fun textOf(para: Int): StructNode? = text.getOrNull(para)

        /** The element paragraph [para]'s list marker belongs to: its Lbl, else its own text's. */
        fun labelOf(para: Int): StructNode? = label.getOrNull(para) ?: textOf(para)
    }

    fun build(flow: TextFlow): Tree {
        val paras = flow.paragraphs
        val cells = CellIndex(paras)
        val text = arrayOfNulls<StructNode>(paras.size)
        val label = arrayOfNulls<StructNode>(paras.size)
        val blocks = mutableListOf<StructNode>()
        val lists = ArrayList<OpenList>()
        var code: StructNode? = null
        var i = 0
        while (i < paras.size) {
            val block = cells.blockAt(i)
            if (block != null) {
                lists.clear()
                code = null
                blocks += table(block, text)
                i = block.last + 1
                continue
            }
            val p = paras[i]
            if (p.codeLang != null) {
                lists.clear()
                text[i] = code ?: StructNode("Code").also {
                    code = it
                    blocks += StructNode("P").apply { kids += it }
                }
                i++
                continue
            }
            code = null
            when {
                p.headingLevel > 0 -> {
                    lists.clear()
                    blocks += StructNode("H${p.headingLevel.coerceIn(1, Paragraph.MAX_HEADING)}").also { text[i] = it }
                }
                p.list != ListKind.NONE -> item(p, i, lists, blocks, text, label)
                else -> {
                    lists.clear()
                    blocks += StructNode("P").also { text[i] = it }
                }
            }
            i++
        }
        return Tree(blocks, text, label)
    }

    /** A list still taking items: its [indent], [kind], element, and its latest item's body. */
    private class OpenList(val indent: Int, val kind: ListKind, val node: StructNode) {
        var body: StructNode? = null
    }

    /**
     * List paragraph [p] as an item: of the open list at its indent when the kind matches, else of
     * a new list, nested in the body of the item above when it is indented deeper than that.
     */
    private fun item(
        p: Paragraph,
        i: Int,
        lists: ArrayList<OpenList>,
        blocks: MutableList<StructNode>,
        text: Array<StructNode?>,
        label: Array<StructNode?>,
    ) {
        while (lists.isNotEmpty() && lists.last().indent > p.indent) lists.removeAt(lists.size - 1)
        var list = lists.lastOrNull()?.takeIf { it.indent == p.indent && it.kind == p.list }
        if (list == null) {
            if (lists.lastOrNull()?.indent == p.indent) lists.removeAt(lists.size - 1)
            val node = StructNode("L").apply { numbering = numberingOf(p.list) }
            val parent = lists.lastOrNull()?.body
            if (parent != null) parent.kids += node else blocks += node
            list = OpenList(p.indent, p.list, node).also { lists += it }
        }
        val li = list.node.add("LI")
        label[i] = li.add("Lbl")
        list.body = li.add("LBody").also { text[i] = it }
    }

    private fun numberingOf(kind: ListKind): String = when (kind) {
        ListKind.BULLET -> "Disc"
        ListKind.ORDERED -> "Decimal"
        else -> "None"
    }

    /** A table as rows of cells, every row full width; with a header row its cells head their columns. */
    private fun table(block: TableBlock, text: Array<StructNode?>): StructNode {
        val table = StructNode("Table")
        val header = block.table.style.headerRow
        for (r in 0 until block.rows) {
            val tr = table.add("TR").apply { keep = true }
            for (c in 0 until block.cols) {
                val head = header && r == 0
                val cell = tr.add(if (head) "TH" else "TD").apply {
                    keep = true
                    if (head) scope = "Column"
                }
                if (!block.hasCell(r, c)) continue
                for (k in block.cellFirstPara(r, c)..block.cellLastPara(r, c)) text[k] = cell.add("P")
            }
        }
        return table
    }
}
