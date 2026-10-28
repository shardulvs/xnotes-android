package com.xnotes.format

import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.text.CalloutKind
import com.xnotes.core.text.CellIndex
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.FlowQuote
import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TableBlock
import com.xnotes.core.text.TableBorders
import com.xnotes.core.text.TableStyle
import com.xnotes.core.text.TextFlow
import com.xnotes.core.text.normalizeTables
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Reads/writes the flow as `flow.xml` inside the `.xnote` bundle, speaking the
 * ODF text vocabulary (text:p / text:span / text:list, fo:* style properties in
 * automatic styles) so the dialect is interchange-friendly; xnotes-specific
 * facts an ODF reader has no slot for (list kind, checkbox state, code language,
 * indent level, flow margins/defaults) ride `xnotes:*` foreign attributes, which
 * conformant readers ignore. Writing is a hand-rolled deterministic serializer;
 * reading is DOM-based and forgiving (unknown elements/attributes are skipped,
 * malformed XML loads as an empty flow). Colours serialize as #rrggbb (the flow
 * only holds opaque colours). Spaces and tabs are ODF-encoded (text:s /
 * text:tab) so code indentation survives whitespace-collapsing readers.
 * Tables are ODF tables (table:table-column widths as rel-column-width
 * styles, a table:table-header-rows row when the header is on, text:p cells);
 * their look rides xnotes:* attributes on table:table.
 */
object FlowXml {
    const val ENTRY_NAME = "flow.xml"

    // --- write ---

    fun write(flow: TextFlow): ByteArray = buildString {
        val charStyles = LinkedHashMap<CharStyle, String>()
        val paraStyles = LinkedHashMap<ParaProps, String>()
        for (para in flow.paragraphs) {
            val props = ParaProps.of(para)
            if (props != ParaProps.PLAIN) paraStyles.getOrPut(props) { "P${paraStyles.size + 1}" }
            for (run in para.runs) {
                val style = writtenStyle(flow, para, run.style)
                if (style != CharStyle.DEFAULT) {
                    charStyles.getOrPut(style) { "T${charStyles.size + 1}" }
                }
            }
        }

        val tables = CellIndex(flow.paragraphs).tables
        val quotes = quoteIds(flow.paragraphs)

        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<office:document-content")
        append(" xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"")
        append(" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\"")
        if (tables.isNotEmpty()) append(" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\"")
        if (flow.paragraphs.any { p -> p.runs.any { it.style.link != null } }) append(" xmlns:xlink=\"http://www.w3.org/1999/xlink\"")
        append(" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\"")
        append(" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\"")
        append(" xmlns:xnotes=\"urn:xnotes:flow:1.0\"")
        append(" office:version=\"1.2\"")
        append(" xnotes:margin-left-mm=\"${flow.margins.leftMm}\"")
        append(" xnotes:margin-top-mm=\"${flow.margins.topMm}\"")
        append(" xnotes:margin-right-mm=\"${flow.margins.rightMm}\"")
        append(" xnotes:margin-bottom-mm=\"${flow.margins.bottomMm}\"")
        append(" xnotes:default-face=\"${escapeAttr(flow.defaultFace.id)}\"")
        append(" xnotes:default-size-pt=\"${flow.defaultSizePt}\"")
        flow.defaultColor?.let { append(" xnotes:default-color=\"${hex(it)}\"") }
        if (flow.monoFace != FontFace.MONO) append(" xnotes:mono-face=\"${escapeAttr(flow.monoFace.id)}\"")
        append(">\n")

        append(" <office:automatic-styles>\n")
        for ((style, name) in charStyles) appendTextStyle(name, style)
        for ((props, name) in paraStyles) appendParaStyle(name, props)
        for (b in tables) appendTableStyles(b)
        append(" </office:automatic-styles>\n")

        append(" <office:body>\n  <office:text>\n")
        var i = 0
        val paras = flow.paragraphs
        var nextTable = 0
        while (i < paras.size) {
            val block = tables.getOrNull(nextTable)?.takeIf { it.first == i }
            if (block != null) {
                appendTable(flow, block, paras, paraStyles, charStyles, quotes)
                nextTable++
                i = block.last + 1
                continue
            }
            val kind = paras[i].list
            if (kind == ListKind.NONE) {
                appendParagraph(flow, paras[i], paraStyles, charStyles, "   ", quotes)
                i++
            } else {
                append("   <text:list xnotes:list=\"${kind.id}\">\n")
                while (i < paras.size && paras[i].list == kind) {
                    val start = paras[i].listStart
                    append(if (start == null) "    <text:list-item>\n" else "    <text:list-item text:start-value=\"$start\">\n")
                    appendParagraph(flow, paras[i], paraStyles, charStyles, "     ", quotes)
                    append("    </text:list-item>\n")
                    i++
                }
                append("   </text:list>\n")
            }
        }
        append("  </office:text>\n </office:body>\n</office:document-content>\n")
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendTextStyle(name: String, s: CharStyle) {
        append("  <style:style style:name=\"$name\" style:family=\"text\">\n   <style:text-properties")
        if (s.bold) append(" fo:font-weight=\"bold\"")
        if (s.italic) append(" fo:font-style=\"italic\"")
        if (s.underline) append(" style:text-underline-style=\"solid\"")
        if (s.strike) append(" style:text-line-through-style=\"solid\"")
        if (s.code) append(" xnotes:code=\"true\"")
        // The run's text is the LaTeX itself, so a reader that ignores this still
        // shows the source rather than losing the equation.
        if (s.math) append(" xnotes:math=\"true\"")
        if (s.mathDisplay) append(" xnotes:math-display=\"true\"")
        s.face?.let { append(" style:font-name=\"${escapeAttr(it.id)}\"") }
        s.color?.let { append(" fo:color=\"${hex(it)}\"") }
        s.highlight?.let { append(" fo:background-color=\"${hex(it)}\"") }
        s.sizePt?.let { append(" fo:font-size=\"${it}pt\"") }
        append("/>\n  </style:style>\n")
    }

    private fun StringBuilder.appendParaStyle(name: String, p: ParaProps) {
        append("  <style:style style:name=\"$name\" style:family=\"paragraph\">\n   <style:paragraph-properties")
        if (p.align != ParaAlign.LEFT) append(" fo:text-align=\"${p.align.id}\"")
        if (p.indent != 0) append(" fo:margin-left=\"${p.indent * INDENT_MM_PER_LEVEL}mm\" xnotes:indent=\"${p.indent}\"")
        if (p.breakBefore) append(" fo:break-before=\"page\"")
        append("/>\n  </style:style>\n")
    }

    /** Each quoted paragraph's container, numbered from 1 in order, a container being a run sharing one. */
    private fun quoteIds(paras: List<Paragraph>): Map<Paragraph, Int> {
        val ids = java.util.IdentityHashMap<Paragraph, Int>()
        var n = 0
        for ((i, p) in paras.withIndex()) {
            val q = p.quote ?: continue
            if (paras.getOrNull(i - 1)?.quote !== q) n++
            ids[p] = n
        }
        return ids
    }

    /** What a paragraph's automatic style holds. */
    private data class ParaProps(val align: ParaAlign, val indent: Int, val breakBefore: Boolean) {
        companion object {
            val PLAIN = ParaProps(ParaAlign.LEFT, 0, false)

            fun of(p: Paragraph) = ParaProps(p.align, p.indent, p.breakBefore)
        }
    }

    private fun tableName(b: TableBlock) = "Table${b.ordinal + 1}"

    private fun StringBuilder.appendTableStyles(b: TableBlock) {
        val name = tableName(b)
        if (b.table.width < FlowTable.FULL_WIDTH) {
            append("  <style:style style:name=\"$name\" style:family=\"table\">\n")
            append("   <style:table-properties style:rel-width=\"${relWidth(b.table.width)}%\" table:align=\"left\"/>\n")
            append("  </style:style>\n")
        }
        for ((c, w) in b.table.widths.withIndex()) {
            append("  <style:style style:name=\"$name.C${c + 1}\" style:family=\"table-column\">\n")
            append("   <style:table-column-properties style:rel-column-width=\"${(w * REL_WIDTH_SCALE).roundToInt()}*\"/>\n")
            append("  </style:style>\n")
        }
        for (r in 0 until b.rows) {
            val h = b.table.minHeightPt(r)
            if (h <= 0.0) continue
            append("  <style:style style:name=\"$name.R${r + 1}\" style:family=\"table-row\">\n")
            append("   <style:table-row-properties style:min-row-height=\"${h}pt\"/>\n")
            append("  </style:style>\n")
        }
    }

    private fun StringBuilder.appendTable(
        flow: TextFlow,
        b: TableBlock,
        paras: List<Paragraph>,
        paraStyles: Map<ParaProps, String>,
        charStyles: Map<CharStyle, String>,
        quotes: Map<Paragraph, Int>,
    ) {
        val name = tableName(b)
        val st = b.table.style
        append("   <table:table table:name=\"$name\"")
        if (b.table.width < FlowTable.FULL_WIDTH) append(" table:style-name=\"$name\"")
        append(" xnotes:padding-pt=\"${st.paddingPt}\"")
        append(" xnotes:line-width-pt=\"${st.lineWidthPt}\"")
        append(" xnotes:borders=\"${st.borders.id}\"")
        st.lineColor?.let { append(" xnotes:line-color=\"${hex(it)}\"") }
        if (st.headerRow) append(" xnotes:header=\"true\"")
        if (st.banded) append(" xnotes:banded=\"true\"")
        st.tint?.let { append(" xnotes:tint=\"${hex(it)}\"") }
        append(">\n")
        for (c in 0 until b.cols) append("    <table:table-column table:style-name=\"$name.C${c + 1}\"/>\n")
        for (r in 0 until b.rows) {
            val header = st.headerRow && r == 0
            if (header) append("    <table:table-header-rows>\n")
            append("    <table:table-row")
            if (b.table.minHeightPt(r) > 0.0) append(" table:style-name=\"$name.R${r + 1}\"")
            append(">\n")
            for (c in 0 until b.cols) {
                append("     <table:table-cell office:value-type=\"string\">\n")
                if (b.hasCell(r, c)) {
                    for (p in b.cellFirstPara(r, c)..b.cellLastPara(r, c)) {
                        appendParagraph(flow, paras[p], paraStyles, charStyles, "      ", quotes)
                    }
                } else {
                    append("      <text:p></text:p>\n")
                }
                append("     </table:table-cell>\n")
            }
            append("    </table:table-row>\n")
            if (header) append("    </table:table-header-rows>\n")
        }
        append("   </table:table>\n")
    }

    private fun StringBuilder.appendParagraph(
        flow: TextFlow,
        para: Paragraph,
        paraStyles: Map<ParaProps, String>,
        charStyles: Map<CharStyle, String>,
        pad: String,
        quotes: Map<Paragraph, Int>,
    ) {
        append(pad).append("<text:p")
        paraStyles[ParaProps.of(para)]?.let { append(" text:style-name=\"$it\"") }
        if (para.list != ListKind.NONE) append(" xnotes:list=\"${para.list.id}\"")
        if (para.checked) append(" xnotes:checked=\"true\"")
        if (para.headingLevel > 0) append(" xnotes:heading=\"${para.headingLevel}\"")
        para.codeLang?.let { append(" xnotes:code-lang=\"${escapeAttr(it)}\"") }
        if (para.rule) append(" xnotes:rule=\"true\"")
        if (para.breakBefore) para.breakToPage?.let { append(" xnotes:break-page=\"$it\"") }
        // A quote's paragraphs share its number; a callout's say its kind as well.
        quotes[para]?.let { append(" xnotes:quote=\"$it\"") }
        para.quote?.kind?.let { append(" xnotes:callout=\"${it.id}\"") }
        append(">")
        // A link is ODF's own text:a around the spans it covers, however they are styled.
        var link: String? = null
        for (run in para.runs) {
            if (run.style.link != link) {
                if (link != null) append("</text:a>")
                link = run.style.link
                link?.let { append("<text:a xlink:type=\"simple\" xlink:href=\"${escapeAttr(it)}\">") }
            }
            val styleName = charStyles[writtenStyle(flow, para, run.style)]
            if (styleName == null) {
                appendEncodedText(run.text)
            } else {
                append("<text:span text:style-name=\"$styleName\">")
                appendEncodedText(run.text)
                append("</text:span>")
            }
        }
        if (link != null) append("</text:a>")
        append("</text:p>\n")
    }

    /**
     * The text style written for [style]: a heading's text sizes from its level, spelled out so
     * older readers still size it, and a link is no style at all but the text:a around it.
     */
    private fun writtenStyle(flow: TextFlow, para: Paragraph, style: CharStyle): CharStyle {
        val s = if (style.link != null) style.copy(link = null) else style
        return if (para.headingLevel > 0 && s.sizePt == null) s.copy(sizePt = para.textSizePt(flow)) else s
    }

    /** Escape markup and ODF-encode whitespace (lone interior spaces stay literal). */
    private fun StringBuilder.appendEncodedText(text: String) {
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '\t' -> { append("<text:tab/>"); i++ }
                ' ' -> {
                    var j = i
                    while (j < text.length && text[j] == ' ') j++
                    val n = j - i
                    when {
                        i == 0 || j == text.length -> append("<text:s text:c=\"$n\"/>")
                        n == 1 -> append(' ')
                        else -> append(' ').append("<text:s text:c=\"${n - 1}\"/>")
                    }
                    i = j
                }
                '&' -> { append("&amp;"); i++ }
                '<' -> { append("&lt;"); i++ }
                '>' -> { append("&gt;"); i++ }
                else -> { append(c); i++ }
            }
        }
    }

    private fun escapeAttr(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun hex(c: Rgba): String = "#%02x%02x%02x".format(c.r, c.g, c.b)

    // --- read ---

    /** Populate [flow] from [bytes]; malformed XML leaves it untouched (loads empty). */
    fun readInto(flow: TextFlow, bytes: ByteArray) {
        val root = try {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
                .documentElement
        } catch (_: Exception) {
            return
        }

        flow.paragraphs.clear()
        flow.margins = FlowMargins(
            leftMm = attrDouble(root, "margin-left-mm", FlowMargins.DEFAULT_MM),
            topMm = attrDouble(root, "margin-top-mm", FlowMargins.DEFAULT_MM),
            rightMm = attrDouble(root, "margin-right-mm", FlowMargins.DEFAULT_MM),
            bottomMm = attrDouble(root, "margin-bottom-mm", FlowMargins.DEFAULT_MM),
        )
        attr(root, "default-face")?.let { flow.defaultFace = FontFace.fromId(it) }
        flow.defaultSizePt = attrDouble(root, "default-size-pt", TextFlow.DEFAULT_SIZE_PT)
        // Legacy files always wrote the (then ignored) built-in near-white; read it back as auto.
        flow.defaultColor = parseHex(attr(root, "default-color"))?.takeUnless { it == TextFlow.DEFAULT_COLOR }
        attr(root, "mono-face")?.let { flow.monoFace = FontFace.fromId(it) }

        val styles = Styles()
        for (styleEl in descendants(root, "style")) {
            val name = attr(styleEl, "name") ?: continue
            when (attr(styleEl, "family")) {
                "text" -> descendants(styleEl, "text-properties").firstOrNull()
                    ?.let { styles.chars[name] = parseCharStyle(it) }
                "paragraph" -> descendants(styleEl, "paragraph-properties").firstOrNull()
                    ?.let { styles.paras[name] = parseParaProps(it) }
                "table" -> descendants(styleEl, "table-properties").firstOrNull()
                    ?.let { parsePercent(attr(it, "rel-width")) }
                    ?.let { styles.tableWidths[name] = it }
                "table-column" -> descendants(styleEl, "table-column-properties").firstOrNull()
                    ?.let { attr(it, "rel-column-width")?.removeSuffix("*")?.toDoubleOrNull() }
                    ?.let { styles.colWidths[name] = it }
                "table-row" -> descendants(styleEl, "table-row-properties").firstOrNull()
                    ?.let { parseLengthPt(attr(it, "min-row-height")) }
                    ?.let { styles.rowHeights[name] = it }
            }
        }

        val body = descendants(root, "body").firstOrNull() ?: root
        readBlocks(body, styles, flow.paragraphs)
        joinQuotes(flow.paragraphs, styles.quotes)
        normalizeTables(flow)
        flow.touch()
    }

    /** Neighbouring paragraphs with one quote number share one container, of the kind the first names. */
    private fun joinQuotes(paras: List<Paragraph>, refs: Map<Paragraph, Pair<String, CalloutKind?>>) {
        var id: String? = null
        var quote: FlowQuote? = null
        for (p in paras) {
            val ref = refs[p]
            if (ref == null || p.codeLang != null || p.rule || p.table != null) {
                id = null
                quote = null
                continue
            }
            if (ref.first != id) {
                id = ref.first
                quote = FlowQuote(ref.second)
            }
            p.quote = quote
        }
    }

    private class Styles {
        val quotes = java.util.IdentityHashMap<Paragraph, Pair<String, CalloutKind?>>()
        val chars = HashMap<String, CharStyle>()
        val paras = HashMap<String, ParaProps>()
        val tableWidths = HashMap<String, Double>()
        val colWidths = HashMap<String, Double>()
        val rowHeights = HashMap<String, Double>()
    }

    /** Paragraphs in document order (nested ones after their parent, as ever); tables become grids. */
    private fun readBlocks(parent: Element, styles: Styles, out: MutableList<Paragraph>) {
        var child = parent.firstChild
        while (child != null) {
            if (child is Element) {
                when (child.localName ?: child.nodeName) {
                    "p" -> {
                        val para = parseParagraph(child, styles.chars, styles.paras)
                        attr(child, "quote")?.let { styles.quotes[para] = it to CalloutKind.fromId(attr(child, "callout")) }
                        out.add(para)
                        readBlocks(child, styles, out)
                    }
                    "table" -> readTable(child, styles, out)
                    else -> readBlocks(child, styles, out)
                }
            }
            child = child.nextSibling
        }
    }

    private fun readTable(el: Element, styles: Styles, out: MutableList<Paragraph>) {
        val widths = mutableListOf<Double>()
        val rows = mutableListOf<List<List<Paragraph>>>()
        val heights = mutableListOf<Double>()
        fun repeats(e: Element, name: String, cap: Int) = (attr(e, name)?.toIntOrNull() ?: 1).coerceIn(1, cap)
        fun collect(parent: Element) {
            var node = parent.firstChild
            while (node != null) {
                val child = node
                if (child is Element) {
                    when (child.localName ?: child.nodeName) {
                        "table-column" -> {
                            val w = styles.colWidths[attr(child, "style-name")] ?: 0.0
                            repeat(repeats(child, "number-columns-repeated", MAX_REPEAT)) { widths.add(w) }
                        }
                        "table-row" -> {
                            val cells = mutableListOf<List<Paragraph>>()
                            var cellNode = child.firstChild
                            while (cellNode != null) {
                                val cell = cellNode
                                val kind = if (cell is Element) cell.localName ?: cell.nodeName else null
                                if (cell is Element && (kind == "table-cell" || kind == "covered-table-cell")) {
                                    val paras = cellParagraphs(cell, styles)
                                    repeat(repeats(cell, "number-columns-repeated", MAX_REPEAT)) { cells.add(paras) }
                                }
                                cellNode = cell.nextSibling
                            }
                            val h = styles.rowHeights[attr(child, "style-name")] ?: 0.0
                            repeat(repeats(child, "number-rows-repeated", MAX_REPEAT)) {
                                rows.add(cells.map { c -> c.map { it.deepCopy() } })
                                heights.add(h)
                            }
                        }
                        else -> collect(child)
                    }
                }
                node = child.nextSibling
            }
        }
        collect(el)
        if (rows.isEmpty()) return
        val cols = maxOf(widths.size, rows.maxOf { it.size }, 1)
        val header = attr(el, "header")?.let { it == "true" } ?: descendants(el, "table-header-rows").isNotEmpty()
        val d = TableStyle()
        val table = FlowTable(
            widths = if (widths.size == cols && widths.all { it > 0.0 }) FlowTable.normalized(widths) else FlowTable.even(cols),
            minHeights = heights,
            style = TableStyle(
                paddingPt = attr(el, "padding-pt")?.toDoubleOrNull() ?: d.paddingPt,
                lineColor = parseHex(attr(el, "line-color")),
                lineWidthPt = attr(el, "line-width-pt")?.toDoubleOrNull() ?: d.lineWidthPt,
                borders = TableBorders.fromId(attr(el, "borders")),
                headerRow = header,
                banded = attr(el, "banded") == "true",
                tint = parseHex(attr(el, "tint")),
            ).clamped(),
            width = FlowTable.clampWidth(styles.tableWidths[attr(el, "style-name")] ?: FlowTable.FULL_WIDTH),
        )
        for (row in rows) {
            for (c in 0 until cols) {
                val paras = row.getOrNull(c) ?: listOf(Paragraph())
                paras.forEachIndexed { i, p ->
                    p.table = table
                    p.cellStart = i == 0
                    out.add(p)
                }
            }
        }
    }

    /** A cell's paragraphs, a nested table's text flattened in; never empty. */
    private fun cellParagraphs(cell: Element, styles: Styles): List<Paragraph> {
        val out = descendants(cell, "p").map { parseParagraph(it, styles.chars, styles.paras) }
        return out.ifEmpty { listOf(Paragraph()) }
    }

    /** An ODF length ("12pt", "4.2mm", "0.5in", "1cm") in points, or null. */
    private fun parseLengthPt(s: String?): Double? {
        if (s == null) return null
        val unit = s.takeLastWhile { it.isLetter() }
        val v = s.dropLast(unit.length).toDoubleOrNull() ?: return null
        return when (unit) {
            "pt", "" -> v
            "mm" -> v * 72.0 / 25.4
            "cm" -> v * 72.0 / 2.54
            "in" -> v * 72.0
            "px" -> v * 0.75
            else -> null
        }
    }

    private fun parseCharStyle(props: Element): CharStyle = CharStyle(
        bold = attr(props, "font-weight") == "bold",
        italic = attr(props, "font-style") == "italic",
        underline = attr(props, "text-underline-style").let { it != null && it != "none" },
        strike = attr(props, "text-line-through-style").let { it != null && it != "none" },
        code = attr(props, "code") == "true",
        math = attr(props, "math") == "true",
        mathDisplay = attr(props, "math-display") == "true",
        color = parseHex(attr(props, "color")),
        highlight = parseHex(attr(props, "background-color")),
        sizePt = attr(props, "font-size")?.removeSuffix("pt")?.toDoubleOrNull(),
        face = attr(props, "font-name")?.takeIf { it.isNotEmpty() }?.let { FontFace(it) },
        link = attr(props, "link")?.takeIf { it.isNotEmpty() },
    )

    private fun parseParaProps(props: Element): ParaProps {
        val align = when (attr(props, "text-align")) {
            "center" -> ParaAlign.CENTER
            "right", "end" -> ParaAlign.RIGHT
            "justify" -> ParaAlign.JUSTIFY
            else -> ParaAlign.LEFT
        }
        val indent = attr(props, "indent")?.toIntOrNull()
            ?: attr(props, "margin-left")?.removeSuffix("mm")?.toDoubleOrNull()
                ?.let { (it / INDENT_MM_PER_LEVEL).toInt() }
            ?: 0
        return ParaProps(align, indent.coerceIn(0, Paragraph.MAX_INDENT), attr(props, "break-before") == "page")
    }

    private fun parseParagraph(
        p: Element,
        charStyles: Map<String, CharStyle>,
        paraStyles: Map<String, ParaProps>,
    ): Paragraph {
        val props = paraStyles[attr(p, "style-name")] ?: ParaProps.PLAIN
        val para = Paragraph(
            align = props.align,
            indent = props.indent,
            list = ListKind.fromId(attr(p, "list")),
            checked = attr(p, "checked") == "true",
            codeLang = attr(p, "code-lang"),
            headingLevel = attr(p, "heading")?.toIntOrNull()?.coerceIn(0, Paragraph.MAX_HEADING) ?: 0,
            rule = attr(p, "rule") == "true",
            breakBefore = props.breakBefore,
            breakToPage = if (props.breakBefore) attr(p, "break-page")?.toIntOrNull()?.takeIf { it >= 0 } else null,
            listStart = (p.parentNode as? Element)
                ?.takeIf { (it.localName ?: it.nodeName) == "list-item" }
                ?.let { attr(it, "start-value")?.toIntOrNull() },
        )
        if (para.list != ListKind.ORDERED) para.listStart = null
        collectRuns(p, CharStyle.DEFAULT, charStyles, para.runs)
        // The size written on a heading's text is for older readers; the level sizes it here.
        if (para.headingLevel > 0) for (run in para.runs) run.style = run.style.copy(sizePt = null)
        mergeAdjacent(para.runs)
        return para
    }

    private fun collectRuns(
        node: Element,
        style: CharStyle,
        charStyles: Map<String, CharStyle>,
        out: MutableList<Run>,
    ) {
        var child = node.firstChild
        while (child != null) {
            when {
                child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE ->
                    appendText(out, child.nodeValue.orEmpty(), style)
                child is Element -> when (child.localName ?: child.nodeName) {
                    "span" -> collectRuns(
                        child,
                        // n-notes before 0.9.6 kept a link in the text style as xnotes:link.
                        charStyles[attr(child, "style-name")]?.let { it.copy(link = style.link ?: it.link) } ?: style,
                        charStyles,
                        out,
                    )
                    "a" -> collectRuns(
                        child,
                        style.copy(link = attr(child, "href")?.trim()?.takeIf { it.isNotEmpty() }),
                        charStyles,
                        out,
                    )
                    "s" -> appendText(out, " ".repeat(attr(child, "c")?.toIntOrNull() ?: 1), style)
                    "tab" -> appendText(out, "\t", style)
                    else -> collectRuns(child, style, charStyles, out)
                }
            }
            child = child.nextSibling
        }
    }

    private fun appendText(out: MutableList<Run>, text: String, style: CharStyle) {
        if (text.isEmpty()) return
        val last = out.lastOrNull()
        if (last != null && last.style == style) last.text += text else out.add(Run(text, style))
    }

    private fun mergeAdjacent(runs: MutableList<Run>) {
        runs.removeAll { it.text.isEmpty() }
        var i = 0
        while (i < runs.size - 1) {
            if (runs[i].style == runs[i + 1].style) {
                runs[i].text += runs[i + 1].text
                runs.removeAt(i + 1)
            } else {
                i++
            }
        }
    }

    // --- forgiving DOM helpers (match by local name, any namespace/prefix) ---

    private fun descendants(parent: Element, local: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(n: Node) {
            var child = n.firstChild
            while (child != null) {
                if (child is Element) {
                    if ((child.localName ?: child.nodeName) == local) out.add(child)
                    walk(child)
                }
                child = child.nextSibling
            }
        }
        walk(parent)
        return out
    }

    private fun attr(el: Element, local: String): String? {
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if ((a.localName ?: a.nodeName) == local) return a.nodeValue
        }
        return null
    }

    private fun attrDouble(el: Element, local: String, fallback: Double): Double =
        attr(el, local)?.toDoubleOrNull() ?: fallback

    private fun parseHex(s: String?): Rgba? {
        if (s == null || !s.startsWith("#") || s.length != 7) return null
        val v = s.substring(1).toIntOrNull(16) ?: return null
        return Rgba((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF, 255)
    }

    /** Indent level to fo:margin-left millimetres, for ODF readers only. */
    private const val INDENT_MM_PER_LEVEL = 10.0

    /** A table's own width as an ODF percentage of the text column, to two decimals. */
    private fun relWidth(width: Double): Double = (width * 100.0 * 100.0).roundToInt() / 100.0

    /** An ODF percentage ("80%") as a fraction, or null. */
    private fun parsePercent(s: String?): Double? =
        s?.trim()?.removeSuffix("%")?.toDoubleOrNull()?.let { it / 100.0 }

    /** Relative column widths are written as integer shares of this. */
    private const val REL_WIDTH_SCALE = 10000.0

    /** Repeated rows/columns in a foreign file are expanded at most this many times. */
    private const val MAX_REPEAT = 64
}
