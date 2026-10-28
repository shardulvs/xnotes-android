package com.xnotes.core.text

import com.xnotes.core.model.Rgba
import com.xnotes.core.pdf.LinkFinder
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.gfm.alerts.Alert
import org.commonmark.ext.gfm.alerts.AlertsExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Block
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Nodes
import org.commonmark.node.OrderedList
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.parser.SourceLine
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun
import org.commonmark.node.Paragraph as MdParagraph

/**
 * The "Paste as Markdown" reader: CommonMark with GitHub's tables, strikethrough
 * and task lists (commonmark-java), plus `==highlight==` and maths written as
 * `$`, `$$`, `\(...\)` and `\[...\]`. Its tree flattens into flow paragraphs: wrapped lines join, a
 * gap of blank lines leaves one empty paragraph, nested lists indent a level
 * each, quotes and GitHub's alerts become quotes and callouts, code blocks become code paragraphs (one per line, language from
 * the fence info), links and written-out addresses open what they name, images
 * keep their alt text and `:shortcodes:` become their emoji. Raw HTML and
 * footnotes stay as the text they were written as; front matter is dropped.
 */
object MarkdownParser {

    private val LANG_ALIASES = mapOf(
        "js" to "javascript", "jsx" to "javascript",
        "py" to "python", "kt" to "kotlin", "kts" to "kotlin",
        "sh" to "bash", "shell" to "bash", "zsh" to "bash",
        "c++" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp",
        "ts" to "typescript", "mts" to "typescript", "cts" to "typescript",
        "golang" to "go",
        "rs" to "rust",
        "htm" to "html", "xhtml" to "html",
        "assembly" to "asm", "nasm" to "asm", "gas" to "asm", "x86asm" to "asm", "armasm" to "asm", "mipsasm" to "asm",
        "yml" to "yaml",
        "patch" to "diff",
        "docker" to "dockerfile", "containerfile" to "dockerfile",
        "svg" to "xml", "xsd" to "xml", "xsl" to "xml", "xslt" to "xml", "plist" to "xml",
    )

    /** Common fence-info aliases to the bundled grammar ids. */
    fun normalizeLang(id: String): String = LANG_ALIASES[id] ?: id

    /**
     * The address a link written to [dest] opens, or null when it is not one this app can
     * open: a web or mail address. A "www." host is taken as the web address it names.
     */
    fun linkTarget(dest: String): String? {
        val d = dest.trim()
        return when {
            d.startsWith("http://", ignoreCase = true) || d.startsWith("https://", ignoreCase = true) ->
                d.takeIf { it.substringAfter("://").isNotEmpty() }
            d.startsWith("mailto:", ignoreCase = true) -> d.takeIf { it.length > "mailto:".length }
            d.startsWith("www.", ignoreCase = true) -> "https://$d"
            else -> null
        }
    }

    private val FENCE_LANG = Regex("^[A-Za-z0-9+#-]*")

    /** LaTeX's page breaks, which is how markdown headed for print writes one. */
    private val PAGE_BREAK = Regex("\\s*\\\\(pagebreak|newpage)\\s*")

    private val parser: Parser by lazy {
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    // A lone tilde is prose ("~5 min"), not strikethrough.
                    StrikethroughExtension.builder().requireTwoTildes(true).build(),
                    TaskListItemsExtension.create(),
                    FootnotesExtension.create(),
                    YamlFrontMatterExtension.create(),
                    AlertsExtension.create(),
                ),
            )
            .customDelimiterProcessor(MarkDelimiters())
            .customBlockParserFactory(MathBlockFactory())
            .customInlineContentParserFactory(DollarMathFactory())
            .customInlineContentParserFactory(BracketMathFactory())
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build()
    }

    /**
     * Parse [text] into flow paragraphs; pipe tables take [tableStyle] (with its header row on),
     * and ==marked== text [highlight].
     */
    fun parse(
        text: String,
        tableStyle: TableStyle = TableStyle(),
        highlight: Rgba = CharStyle.DEFAULT_HIGHLIGHT,
    ): List<Paragraph> =
        Emitter(tableStyle, highlight).apply { blocks(parser.parse(text), indent = 0, gaps = true) }.finish()

    private class Emitter(private val tableStyle: TableStyle, private val highlight: Rgba) {
        private val paras = mutableListOf<Paragraph>()

        /** Set by a `\pagebreak` or `\newpage` line: the next paragraph starts a page. */
        private var pendingBreak = false

        /** The quote or callout being read, which what is emitted goes into where it may. */
        private var quote: FlowQuote? = null

        /** Everything emitted; a break with nothing after it still breaks, on an empty line. */
        fun finish(): List<Paragraph> {
            if (pendingBreak) emit(Paragraph())
            return paras
        }

        private fun emit(p: Paragraph) {
            if (pendingBreak && p.table == null) {
                p.breakBefore = true
                pendingBreak = false
            }
            // Code, rules, tables and display equations cannot sit in a quote: one cuts it in
            // two, and what follows goes into a fresh quote of the same kind.
            quote?.let { q ->
                val alone = p.codeLang != null || p.rule || p.table != null || (p.runs.size == 1 && p.runs[0].style.mathDisplay)
                if (alone) quote = FlowQuote(q.kind) else p.quote = q
            }
            paras += p
        }

        /** [node]'s blocks inside one new quote, or callout of [kind]; one inside another joins it. */
        private fun quoted(node: Node, kind: CalloutKind?, indent: Int) {
            val outer = quote
            if (outer == null || kind != null) quote = FlowQuote(kind ?: outer?.kind)
            blocks(node, indent, gaps = true)
            quote = outer?.let { if (kind != null) FlowQuote(it.kind) else quote }
        }

        /** Every block child of [parent]; with [gaps], blank source lines between two leave an empty paragraph. */
        fun blocks(parent: Node, indent: Int, gaps: Boolean) {
            var prev: Node? = null
            var child = parent.firstChild
            while (child != null) {
                if (child is Block) {
                    val before = paras.size
                    // A break already starts the page, so the gap before what follows it goes.
                    if (gaps && prev != null && !pendingBreak && gapBetween(prev, child)) emit(Paragraph(indent = indent))
                    val mark = paras.size
                    val broke = pendingBreak
                    block(child, indent)
                    if (paras.size > mark) {
                        prev = child
                    } else {
                        // Nothing came of it, so neither does the gap before it.
                        while (paras.size > before) paras.removeAt(paras.size - 1)
                        if (pendingBreak != broke) prev = child
                    }
                }
                child = child.next
            }
        }

        private fun gapBetween(a: Node, b: Node): Boolean {
            val end = a.sourceSpans.lastOrNull()?.lineIndex ?: return false
            val start = b.sourceSpans.firstOrNull()?.lineIndex ?: return false
            return start > end + 1
        }

        private fun block(node: Node, indent: Int) {
            when (node) {
                is MdParagraph -> if (PAGE_BREAK.matches(plain(node))) {
                    pendingBreak = true
                } else {
                    lines(node, CharStyle.DEFAULT) { runs, _ -> prose(runs, indent) }
                }
                is Heading -> lines(node, Paragraph.HEADING_STYLE) { runs, _ ->
                    Paragraph(runs, indent = indent, headingLevel = node.level.coerceIn(1, Paragraph.MAX_HEADING))
                }
                is FencedCodeBlock -> code(node.literal, lang(node.info), indent)
                is IndentedCodeBlock -> code(node.literal, "", indent)
                is MathBlock -> emit(displayParagraph(node.latex, indent))
                is BulletList, is OrderedList -> list(node as ListBlock, indent)
                is BlockQuote -> quoted(node, null, indent)
                is Alert -> quoted(node, CalloutKind.fromId(node.type), indent)
                is ThematicBreak -> emit(Paragraph(indent = indent, rule = true))
                is HtmlBlock -> for (line in node.literal.trimEnd('\n').split('\n')) {
                    emit(Paragraph(runsOf(line, CharStyle.DEFAULT), indent = indent))
                }
                is TableBlock -> table(node)
                is FootnoteDefinition -> footnote(node, indent)
                is YamlFrontMatterBlock, is LinkReferenceDefinition -> Unit
                else -> blocks(node, indent, gaps = false)
            }
        }

        /** [node]'s text with no styling, for recognising a line by what it says. */
        private fun plain(node: Node): String {
            val lines = mutableListOf(mutableListOf<Run>())
            inlines(node, CharStyle.DEFAULT, lines)
            return lines.joinToString("\n") { l -> l.joinToString("") { it.text } }
        }

        /** A paragraph of prose; a display equation alone in it is set as one. */
        private fun prose(runs: MutableList<Run>, indent: Int): Paragraph =
            if (runs.size == 1 && runs[0].style.mathDisplay) {
                displayParagraph(runs[0].text, indent)
            } else {
                Paragraph(runs, indent = indent)
            }

        /** [node]'s inline content, one paragraph per hard-broken line, each made by [make]. */
        private fun lines(node: Node, base: CharStyle, make: (MutableList<Run>, Int) -> Paragraph) {
            val lines = mutableListOf(mutableListOf<Run>())
            inlines(node, base, lines)
            for ((i, runs) in lines.withIndex()) emit(make(asTyped(runs), i))
        }

        private fun code(literal: String, lang: String, indent: Int) {
            if (literal.isEmpty()) return
            for (line in literal.removeSuffix("\n").split('\n')) emit(Paragraph(runsOf(line, CharStyle.DEFAULT), indent = indent, codeLang = lang))
        }

        private fun lang(info: String?): String =
            normalizeLang(FENCE_LANG.find(info.orEmpty().trim())?.value.orEmpty().lowercase())

        /**
         * Each item's first paragraph takes the marker; the item's later blocks
         * continue it a level in, and a list nested in it sits a level deeper.
         */
        private fun list(node: ListBlock, indent: Int) {
            val kind = if (node is OrderedList) ListKind.ORDERED else ListKind.BULLET
            var start = (node as? OrderedList)?.markerStartNumber?.takeIf { it != 1 }
            var item = node.firstChild
            while (item != null) {
                if (item is ListItem) {
                    listItem(item, kind, indent, start)
                    start = null
                }
                item = item.next
            }
        }

        private fun listItem(item: ListItem, kind: ListKind, indent: Int, start: Int?) {
            val task = taskMarker(item)
            val inner = (indent + 1).coerceAtMost(Paragraph.MAX_INDENT)
            var first = true
            var child = item.firstChild
            while (child != null) {
                when {
                    child !is Block -> Unit
                    first && (child is MdParagraph || child is Heading) -> {
                        first = false
                        val base = if (child is Heading) Paragraph.HEADING_STYLE else CharStyle.DEFAULT
                        val level = (child as? Heading)?.level?.coerceIn(1, Paragraph.MAX_HEADING) ?: 0
                        lines(child, base) { runs, line ->
                            if (line == 0) {
                                Paragraph(
                                    runs, indent = indent,
                                    list = if (task != null) ListKind.CHECK else kind,
                                    checked = task?.isChecked == true,
                                    headingLevel = level,
                                    listStart = start.takeIf { task == null },
                                )
                            } else {
                                Paragraph(runs, indent = inner, headingLevel = level)
                            }
                        }
                    }
                    child is ListBlock -> {
                        if (first) emit(markerOnly(kind, task, indent, start))
                        first = false
                        list(child, inner)
                    }
                    else -> {
                        if (first) emit(markerOnly(kind, task, indent, start))
                        first = false
                        block(child, inner)
                    }
                }
                child = child.next
            }
            if (first) emit(markerOnly(kind, task, indent, start))
        }

        private fun markerOnly(kind: ListKind, task: TaskListItemMarker?, indent: Int, start: Int?) = Paragraph(
            indent = indent,
            list = if (task != null) ListKind.CHECK else kind,
            checked = task?.isChecked == true,
            listStart = start.takeIf { task == null },
        )

        private fun taskMarker(item: ListItem): TaskListItemMarker? =
            item.firstChild as? TaskListItemMarker ?: item.firstChild?.firstChild as? TaskListItemMarker

        /** A footnote's definition, kept as the text it was written as. */
        private fun footnote(node: FootnoteDefinition, indent: Int) {
            val at = paras.size
            blocks(node, indent, gaps = false)
            val label = "[^${node.label}]:"
            if (paras.size == at) {
                emit(Paragraph(runsOf(label, CharStyle.DEFAULT), indent = indent))
            } else {
                paras[at].runs.add(0, Run("$label ", CharStyle.DEFAULT))
                mergeRuns(paras[at].runs)
            }
        }

        private fun table(node: TableBlock) {
            val rows = mutableListOf<List<TableCell>>()
            fun collect(n: Node) {
                var c = n.firstChild
                while (c != null) {
                    if (c is TableRow) rows += generateSequence(c.firstChild) { it.next }.filterIsInstance<TableCell>().toList()
                    else collect(c)
                    c = c.next
                }
            }
            collect(node)
            val cols = rows.firstOrNull()?.size ?: return
            if (cols == 0) return
            val aligns = rows[0].map {
                when (it.alignment) {
                    TableCell.Alignment.CENTER -> ParaAlign.CENTER
                    TableCell.Alignment.RIGHT -> ParaAlign.RIGHT
                    else -> ParaAlign.LEFT
                }
            }
            // A table cannot carry a break, so one waiting for it stands on an empty line before it.
            if (pendingBreak) emit(Paragraph())
            val table = FlowTable(FlowTable.even(cols), List(rows.size) { 0.0 }, tableStyle.copy(headerRow = true))
            for (row in rows) {
                for (c in 0 until cols) {
                    val runs = mutableListOf(mutableListOf<Run>())
                    row.getOrNull(c)?.let { inlines(it, CharStyle.DEFAULT, runs) }
                    val flat = mutableListOf<Run>()
                    for ((i, line) in runs.withIndex()) {
                        if (i > 0) appendRun(flat, " ", CharStyle.DEFAULT)
                        line.forEach { appendRun(flat, it.text, it.style) }
                    }
                    emit(Paragraph(asTyped(flat), align = aligns[c], table = table, cellStart = true))
                }
            }
        }

        /** Inline content of [parent] in [style], appended to the last of [lines]; a hard break opens another. */
        private fun inlines(parent: Node, style: CharStyle, lines: MutableList<MutableList<Run>>) {
            var node = parent.firstChild
            while (node != null) {
                val runs = lines.last()
                when (node) {
                    is Text -> appendRun(runs, node.literal, style)
                    is Code -> appendRun(runs, node.literal, style.copy(code = true))
                    is MathInline -> appendRun(runs, node.latex, style.copy(math = true, mathDisplay = node.display))
                    is Emphasis -> inlines(node, style.copy(italic = true), lines)
                    is StrongEmphasis -> inlines(node, style.copy(bold = true), lines)
                    is Strikethrough -> inlines(node, style.copy(strike = true), lines)
                    is Marked -> inlines(node, style.copy(highlight = highlight), lines)
                    is Link -> inlines(node, linkTarget(node.destination.orEmpty())?.let { style.copy(link = it) } ?: style, lines)
                    is Image -> inlines(node, style, lines)
                    is SoftLineBreak -> appendRun(runs, " ", style)
                    is HardLineBreak -> lines += mutableListOf<Run>()
                    is HtmlInline -> appendRun(runs, node.literal, style)
                    is FootnoteReference -> appendRun(runs, "[^${node.label}]", style)
                    is TaskListItemMarker -> Unit
                    else -> inlines(node, style, lines)
                }
                node = node.next
            }
        }
    }

    /**
     * One display equation on a line of its own. The layout centres such a line
     * itself, so the paragraph keeps the default alignment rather than carrying
     * one the reader would have to undo.
     */
    private fun displayParagraph(latex: String, indent: Int): Paragraph =
        if (latex.isEmpty()) {
            Paragraph(indent = indent)
        } else {
            Paragraph(mutableListOf(Run(latex, CharStyle(math = true, mathDisplay = true))), indent = indent)
        }

    private fun runsOf(text: String, style: CharStyle): MutableList<Run> =
        if (text.isEmpty()) mutableListOf() else mutableListOf(Run(text, style))

    private fun appendRun(out: MutableList<Run>, text: String, style: CharStyle) {
        if (text.isEmpty()) return
        val last = out.lastOrNull()
        if (last != null && last.style == style) last.text += text else out.add(Run(text, style))
    }

    /** [runs] as typing would have left them: shortcodes turned into emoji, written-out addresses linked. */
    private fun asTyped(runs: MutableList<Run>): MutableList<Run> {
        for (run in runs) if (!run.style.code && !run.style.math) run.text = EmojiShortcodes.replaceAll(run.text)
        return linkBareUrls(runs)
    }

    /** [runs] with the addresses written out in their prose made links, as typing would. */
    private fun linkBareUrls(runs: MutableList<Run>): MutableList<Run> {
        val text = runs.joinToString("") { it.text }
        val found = LinkFinder.find(text)
        if (found.isEmpty()) return runs
        val out = mutableListOf<Run>()
        var at = 0
        for (run in runs) {
            val end = at + run.text.length
            var cut = at
            if (run.style.link == null && !run.style.code && !run.style.math) {
                for (l in found) {
                    val a = maxOf(l.start, at)
                    val b = minOf(l.end, end)
                    if (b <= a) continue
                    if (a > cut) appendRun(out, text.substring(cut, a), run.style)
                    appendRun(out, text.substring(a, b), run.style.copy(link = l.uri))
                    cut = b
                }
            }
            if (end > cut) appendRun(out, text.substring(cut, end), run.style)
            at = end
        }
        return out
    }

    private fun mergeRuns(runs: MutableList<Run>) {
        val merged = mutableListOf<Run>()
        for (r in runs) appendRun(merged, r.text, r.style)
        runs.clear()
        runs.addAll(merged)
    }

    // --- ==highlight== ---

    private class Marked : CustomNode()

    /** `==text==`, Obsidian's and many editors' highlight, as a delimiter run like `~~`. */
    private class MarkDelimiters : DelimiterProcessor {
        override fun getOpeningCharacter(): Char = '='

        override fun getClosingCharacter(): Char = '='

        override fun getMinLength(): Int = 2

        override fun process(opening: DelimiterRun, closing: DelimiterRun): Int {
            if (opening.length() < 2 || closing.length() < 2) return 0
            val mark = Marked()
            val opener = opening.opener
            for (node in Nodes.between(opener, closing.closer)) mark.appendChild(node)
            opener.insertAfter(mark)
            return 2
        }
    }

    // --- maths ---

    /** A formula met inline: its LaTeX, and whether it was written in display form. */
    private class MathInline(val latex: String, val display: Boolean) : CustomNode()

    /** A display equation written as a block: `$$` or `\[` on a line of its own, closed the same way. */
    private class MathBlock : CustomBlock() {
        var latex = ""
    }

    private class MathBlockParser(private val close: Regex) : AbstractBlockParser() {
        private val block = MathBlock()
        private val body = mutableListOf<String>()
        private var opened = false

        override fun getBlock(): Block = block

        override fun tryContinue(state: ParserState): BlockContinue {
            val content = state.line.content
            return if (close.matches(content.subSequence(state.index, content.length))) {
                BlockContinue.finished()
            } else {
                BlockContinue.atIndex(state.index)
            }
        }

        // The first line handed over is the rest of the opening one, which is empty.
        override fun addLine(line: SourceLine) {
            if (opened) body += line.content.toString() else opened = true
        }

        // Everything between is one formula, so its lines join rather than standing apart.
        override fun closeBlock() {
            block.latex = body.joinToString(" ") { it.trim() }.trim()
        }
    }

    private class MathBlockFactory : AbstractBlockParserFactory() {
        override fun tryStart(state: ParserState, matched: MatchedBlockParser): BlockStart? {
            if (state.indent >= 4) return BlockStart.none()
            val content = state.line.content
            val rest = content.subSequence(state.nextNonSpaceIndex, content.length)
            val close = when {
                DOLLAR_FENCE.matches(rest) -> DOLLAR_FENCE
                BRACKET_OPEN.matches(rest) -> BRACKET_CLOSE
                else -> return BlockStart.none()
            }
            return BlockStart.of(MathBlockParser(close)).atIndex(content.length)
        }

        private companion object {
            val DOLLAR_FENCE = Regex("\\s*\\$\\$\\s*")
            val BRACKET_OPEN = Regex("\\\\\\[\\s*")
            val BRACKET_CLOSE = Regex("\\s*\\\\]\\s*")
        }
    }

    /**
     * `$...$` and `$$...$$`, read the way typing converts them, so pasting and
     * typing land the same paragraph. The span closes at the first marker after
     * it opens, and only counts when padded the same on both sides, or not padded
     * at all: "$x$" and "$ x $" are maths, while "$5 and $10" pads only where it
     * closes and is money.
     */
    private class DollarMathFactory : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf('$')

        override fun create(): InlineContentParser = InlineContentParser { state -> tryParse(state) }

        private fun tryParse(state: InlineParserState): ParsedInline? {
            val sc = state.scanner()
            val display = sc.next("$$")
            if (!display) sc.next()
            val body = StringBuilder()
            while (true) {
                val c = sc.peek()
                if (c == Scanner.END) return ParsedInline.none()
                if (c == '$') {
                    if (display) {
                        if (sc.next("$$")) break
                    } else {
                        sc.next()
                        // A single "$" must not close on the first half of a doubled one.
                        if (sc.peek() == '$') return ParsedInline.none()
                        break
                    }
                }
                body.append(c)
                sc.next()
            }
            val s = body.toString()
            if (s.isBlank() || s.first().isWhitespace() != s.last().isWhitespace()) return ParsedInline.none()
            return ParsedInline.of(MathInline(s.replace('\n', ' ').trim(), display), sc.position())
        }
    }

    /** `\(...\)` inline and `\[...\]` display, LaTeX's own delimiters. */
    private class BracketMathFactory : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf('\\')

        override fun create(): InlineContentParser = InlineContentParser { state -> tryParse(state) }

        private fun tryParse(state: InlineParserState): ParsedInline? {
            val sc = state.scanner()
            sc.next()
            val display = when (sc.peek()) {
                '(' -> false
                '[' -> true
                else -> return ParsedInline.none()
            }
            sc.next()
            val close = if (display) "\\]" else "\\)"
            val body = StringBuilder()
            while (true) {
                val c = sc.peek()
                if (c == Scanner.END) return ParsedInline.none()
                if (c == '\\' && sc.next(close)) break
                body.append(c)
                sc.next()
            }
            val s = body.toString().replace('\n', ' ').trim()
            if (s.isEmpty()) return ParsedInline.none()
            return ParsedInline.of(MathInline(s, display), sc.position())
        }
    }
}
