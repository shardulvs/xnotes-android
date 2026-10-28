package com.xnotes.core.text

import com.xnotes.core.history.Command
import com.xnotes.core.model.Rgba
import com.xnotes.core.pdf.LinkFinder

/**
 * Markdown shortcuts applied while typing: the markers convert the paragraph and
 * are then deleted, so the flow itself never holds markdown. Detection mirrors
 * [MarkdownParser], so a typed marker lands the same paragraph that pasting it
 * would, and is pure so the caller can flush its typing burst before [apply]
 * mutates anything: the conversion is then its own undo step and one undo puts
 * the markers back as plain text.
 */
object InputRules {

    /** A rule that matched. Opaque to callers, who only pass it back to [apply]. */
    sealed interface Rule

    /** An applied rule: its command, the caret after it, and the next typing style. */
    class Result(val command: Command?, val caret: FlowPos, val pending: CharStyle? = null)

    // Block markers match the whole text before the caret, so they only fire on a
    // prefix the user just completed with the trigger space.
    private val HEADING = Regex("^(#{1,4}) $")
    private val TASK = Regex("^(\\s*)\\[([ xX]?)] $")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)] $")
    private val BULLET = Regex("^(\\s*)[-*+] $")
    private val QUOTE = Regex("^>+ $")
    private val CALLOUT = Regex("^\\[!([A-Za-z]+)] $")
    private val FENCE = Regex("^\\s{0,3}```\\s*([A-Za-z0-9+#-]*)\\s*$")
    private val RULE = Regex("^ {0,3}([-*_])(?: *\\1){2,} *$")
    private val LINK = Regex("\\[([^\\[\\]]+)]\\(([^()\\s]+)\\)$")

    /** What a marker does to a style; a highlight takes the colour the caller hands over. */
    private enum class Emph(val apply: (CharStyle, Rgba) -> CharStyle) {
        BOLD({ s, _ -> s.copy(bold = true) }),
        ITALIC({ s, _ -> s.copy(italic = true) }),
        STRIKE({ s, _ -> s.copy(strike = true) }),
        HIGHLIGHT({ s, c -> s.copy(highlight = c) }),
        CODE({ s, _ -> s.copy(code = true) }),
    }

    /** Emphasis markers longest first, so ** is tried before *. */
    private val MARKERS = listOf(
        "**" to Emph.BOLD,
        "__" to Emph.BOLD,
        "~~" to Emph.STRIKE,
        "==" to Emph.HIGHLIGHT,
        "*" to Emph.ITALIC,
        "_" to Emph.ITALIC,
    )

    private class Block(
        val prefix: Int,
        val heading: Int = 0,
        val list: ListKind? = null,
        val checked: Boolean = false,
        val indent: Int? = null,
        val start: Int? = null,
        val quote: Boolean = false,
    ) : Rule

    private class Inline(val open: Int, val marker: String, val emph: Emph) : Rule

    /** A formula opening at [open]; each of its two markers is [marker] characters long. */
    private class Math(val open: Int, val display: Boolean = false, val marker: Int = if (display) 2 else 1) : Rule

    private class Fence(val lang: String) : Rule

    /** `[text](url)` just closed at [open]: [text] links to [uri]. */
    private class LinkUp(val open: Int, val text: String, val uri: String) : Rule

    /** An address typed out over [start, end) gets the link it names. */
    private class AutoLink(val start: Int, val end: Int, val uri: String) : Rule

    /** The shortcode from [start] to the caret just closed: it becomes [emoji]. */
    private class Emoji(val start: Int, val emoji: String) : Rule

    private object HRule : Rule

    /** `[!kind] ` typed at the start of a plain quote: the whole quote becomes that callout. */
    private class Callout(val prefix: Int, val kind: CalloutKind) : Rule

    private enum class Strip : Rule { BREAK, RULE, CODE, LIST, LIST_EXIT, QUOTE_EXIT, HEADING, QUOTE, INDENT }

    // --- detection ---

    /**
     * The rule typing [typed] with the caret now at [pos] triggers, or null. Only
     * plain typing reaches here: a paste builds its paragraphs through
     * [MarkdownParser], and a code line takes markers literally.
     */
    fun forTyped(flow: TextFlow, pos: FlowPos, typed: String): Rule? {
        if (typed.isEmpty() || '\n' in typed) return null
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        if (para.codeLang != null) return null
        val text = para.plainText()
        if (pos.offset > text.length) return null
        val before = text.take(pos.offset)
        if (!typed.endsWith(" ")) return emojiRule(para, before) ?: inlineRule(before)
        return (if (para.table != null) null else blockRule(para, before)) ?: autoLinkRule(para, before)
    }

    /**
     * The rule pressing Enter at [pos] triggers, or null. [markdown] off drops the
     * fence, which is syntax; stepping out of a list is plain list editing and stays.
     */
    fun forEnter(flow: TextFlow, pos: FlowPos, markdown: Boolean = true): Rule? {
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        if (para.table != null) return null
        if (markdown && para.codeLang == null) {
            FENCE.matchEntire(para.plainText())?.let {
                return Fence(MarkdownParser.normalizeLang(it.groupValues[1].lowercase()))
            }
            if (RULE.matches(para.plainText())) return HRule
        }
        // Enter on an empty list item steps out of it rather than making another, and
        // on an empty line of a quote steps out of the quote.
        if (para.length != 0) return null
        return when {
            para.list != ListKind.NONE -> Strip.LIST_EXIT
            para.quote != null -> Strip.QUOTE_EXIT
            else -> null
        }
    }

    /**
     * The rule a backspace at the very start of [pos]'s paragraph triggers: strip
     * one block property (page break, rule, code line, list, heading, quote, then indent) instead of
     * merging into the line above. Null when the key should just delete.
     */
    fun forBackspace(flow: TextFlow, pos: FlowPos): Rule? {
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        if (para.table != null) return null
        return when {
            para.breakBefore -> Strip.BREAK
            para.rule -> Strip.RULE
            para.codeLang != null -> if (para.length == 0) Strip.CODE else null
            para.list != ListKind.NONE -> Strip.LIST
            para.headingLevel > 0 -> Strip.HEADING
            para.quote != null -> Strip.QUOTE
            para.indent > 0 -> Strip.INDENT
            else -> null
        }
    }

    private fun blockRule(para: Paragraph, before: String): Rule? {
        val n = before.length
        if (para.quote?.kind == null && para.quote != null) {
            CALLOUT.matchEntire(before)?.let { m -> CalloutKind.fromId(m.groupValues[1])?.let { return Callout(n, it) } }
        }
        HEADING.matchEntire(before)?.let { return Block(n, heading = it.groupValues[1].length) }
        TASK.matchEntire(before)?.let {
            return Block(
                n,
                list = ListKind.CHECK,
                checked = it.groupValues[2].isNotBlank(),
                indent = indentOf(it.groupValues[1]),
            )
        }
        ORDERED.matchEntire(before)?.let {
            val start = it.groupValues[2].toInt().takeIf { s -> s != 1 }
            return Block(n, list = ListKind.ORDERED, indent = indentOf(it.groupValues[1]), start = start)
        }
        BULLET.matchEntire(before)?.let { return Block(n, list = ListKind.BULLET, indent = indentOf(it.groupValues[1])) }
        if (QUOTE.matches(before)) return Block(n, quote = true)
        return null
    }

    /** Leading whitespace as an indent level (two spaces a step), or null to keep the current one. */
    private fun indentOf(leading: String): Int? =
        if (leading.isEmpty()) null else (leading.replace("\t", "  ").length / 2).coerceAtMost(Paragraph.MAX_INDENT)

    private fun inlineRule(before: String): Rule? {
        mathRule(before)?.let { return it }
        linkRule(before)?.let { return it }
        if (before.endsWith("`")) {
            val close = before.length - 1
            val open = before.lastIndexOf('`', close - 1)
            return if (open >= 0 && close - open > 1) Inline(open, "`", Emph.CODE) else null
        }
        for ((marker, emph) in MARKERS) {
            if (!before.endsWith(marker)) continue
            val open = findOpen(before, before.length - marker.length, marker) ?: continue
            return Inline(open, marker, emph)
        }
        return null
    }

    /** The `:name:` that just closed, outside code and maths, when it names an emoji. */
    private fun emojiRule(para: Paragraph, before: String): Rule? {
        if (!before.endsWith(":")) return null
        val (start, emoji) = EmojiShortcodes.closedAt(before) ?: return null
        var at = 0
        for (run in para.runs) {
            val end = at + run.text.length
            if (end > start && at < before.length && (run.style.code || run.style.math)) return null
            at = end
        }
        return Emoji(start, emoji)
    }

    /** The `[text](url)` that just closed, when the url is an address a link can open. */
    private fun linkRule(before: String): Rule? {
        if (!before.endsWith(")")) return null
        val m = LINK.find(before) ?: return null
        val uri = MarkdownParser.linkTarget(m.groupValues[2]) ?: return null
        return LinkUp(m.range.first, m.groupValues[1], uri)
    }

    /**
     * The address the space just typed ends, as a link, when it is not one already. Trailing
     * punctuation stays out of it, so "see www.a.org. " links just the host.
     */
    private fun autoLinkRule(para: Paragraph, before: String): Rule? {
        val text = before.dropLast(1)
        val l = LinkFinder.find(text).lastOrNull() ?: return null
        if (text.substring(l.end).any { it.isWhitespace() }) return null
        var at = 0
        for (run in para.runs) {
            val end = at + run.text.length
            if (end > l.start && at < l.end && (run.style.link != null || run.style.code || run.style.math)) return null
            at = end
        }
        return AutoLink(l.start, l.end, l.uri)
    }

    /**
     * The "$...$" that just closed, or null. Doubled markers are tried first, so
     * "$$x$$" is one display equation and never the inline one hiding inside it.
     */
    private fun mathRule(before: String): Rule? {
        latexRule(before)?.let { return it }
        if (!before.endsWith("$")) return null
        if (before.endsWith("$$")) {
            val close = before.length - 2
            val open = before.lastIndexOf("$$", close - 1)
            if (open < 0 || close - open < 3) return null
            return if (pads(before, open + 2, close)) Math(open, display = true) else null
        }
        val close = before.length - 1
        val open = before.lastIndexOf('$', close - 1)
        if (open < 0 || close - open < 2) return null
        if (open > 0 && before[open - 1] == '$') return null
        return if (pads(before, open + 1, close)) Math(open) else null
    }

    /**
     * The "\(...\)" or "\[...\]" that just closed, or null. LaTeX's own
     * delimiters say maths outright, so unlike a dollar they need no padding rule.
     */
    private fun latexRule(before: String): Rule? {
        val display = when {
            before.endsWith("\\)") -> false
            before.endsWith("\\]") -> true
            else -> return null
        }
        val close = before.length - 2
        val open = before.lastIndexOf(if (display) "\\[" else "\\(", close - 1)
        if (open < 0 || before.substring(open + 2, close).isBlank()) return null
        return Math(open, display, marker = 2)
    }

    /**
     * Whether the markers around [from], [close) are padded the same on both
     * sides, which is what tells a formula from a price. "$x$" and "$ x $" are
     * both somebody writing maths; "$5 and $10" pads only where it closes, which
     * is somebody writing about money, and the asymmetry is the whole signal.
     * A span of nothing but spaces is neither.
     */
    private fun pads(s: String, from: Int, close: Int): Boolean {
        if (from >= close) return false
        if (s[from].isWhitespace() != s[close - 1].isWhitespace()) return false
        return s.substring(from, close).isNotBlank()
    }

    /**
     * The opening [marker] for a run closing at [close], or null. Emphasis never
     * opens or closes on whitespace, so arithmetic like "a * b *" is left alone,
     * and a one-character marker beside its own twin is really half of a
     * two-character one, so "**bold*" does not fire italic mid-word.
     */
    private fun findOpen(s: String, close: Int, marker: String): Int? {
        if (close <= 0 || s[close - 1].isWhitespace()) return null
        var i = s.lastIndexOf(marker, close - 1)
        while (i >= 0) {
            val content = i + marker.length
            if (content < close && opens(s, i, content, marker)) return i
            i = s.lastIndexOf(marker, i - 1)
        }
        return null
    }

    private fun opens(s: String, open: Int, content: Int, marker: String): Boolean {
        if (s[content].isWhitespace()) return false
        if (marker.length == 1 && (s[content] == marker[0] || (open > 0 && s[open - 1] == marker[0]))) return false
        // Intraword underscores (snake_case) are not emphasis.
        return !(marker == "_" && open > 0 && s[open - 1].isLetterOrDigit())
    }

    // --- application ---

    /** Apply [rule], detected at [pos], and return its command, caret and typing style; ==text== takes [highlight]. */
    fun apply(flow: TextFlow, pos: FlowPos, rule: Rule, highlight: Rgba = CharStyle.DEFAULT_HIGHLIGHT): Result = when (rule) {
        is Block -> applyBlock(flow, pos, rule)
        is Inline -> applyInline(flow, pos, rule, highlight)
        is Math -> applyMath(flow, pos, rule)
        is Fence -> applyFence(flow, pos, rule)
        is LinkUp -> applyLink(flow, pos, rule)
        is Emoji -> FlowEditor(flow).replaceRange(FlowRange(FlowPos(pos.para, rule.start), pos), rule.emoji)
            .let { (cmd, caret) -> Result(cmd, caret) }
        is Callout -> applyCallout(flow, pos, rule)
        is AutoLink -> Result(
            FlowEditor(flow).setCharStyle(FlowRange(FlowPos(pos.para, rule.start), FlowPos(pos.para, rule.end))) {
                it.copy(link = rule.uri)
            },
            pos,
        )
        HRule -> applyRule(flow, pos)
        is Strip -> applyStrip(flow, pos, rule)
    }

    private fun applyBlock(flow: TextFlow, pos: FlowPos, b: Block): Result {
        val ed = FlowEditor(flow)
        val caret = FlowPos(pos.para, 0)
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(caret, FlowPos(pos.para, b.prefix))).first?.let { cmds += it }
        ed.setParaStyle(FlowRange.caret(caret)) { p ->
            if (b.heading > 0) p.headingLevel = b.heading
            if (b.list != null) {
                p.list = b.list
                p.checked = b.checked
                p.listStart = b.start
            }
            if (b.indent != null) p.indent = b.indent
            // A quote typed next to one joins it, the way consecutive ">" lines are one quote.
            if (b.quote && p.quote == null) {
                p.quote = flow.paragraphs.getOrNull(pos.para - 1)?.quote
                    ?: flow.paragraphs.getOrNull(pos.para + 1)?.quote
                    ?: FlowQuote()
            }
        }?.let { cmds += it }
        var pending: CharStyle? = null
        if (b.heading > 0) {
            val len = flow.paragraphs[pos.para].length
            if (len > 0) {
                ed.setCharStyle(FlowRange(caret, FlowPos(pos.para, len))) {
                    it.copy(bold = true, sizePt = null)
                }?.let { cmds += it }
            }
            pending = Paragraph.HEADING_STYLE
        }
        return Result(ed.combined(cmds), caret, pending)
    }

    private fun applyInline(flow: TextFlow, pos: FlowPos, r: Inline, highlight: Rgba): Result {
        val p = pos.para
        val ed = FlowEditor(flow)
        // The style outside the markers, so typing on past the run is not emphasized.
        val outside = ed.charStyleAt(FlowPos(p, r.open))
        val close = pos.offset - r.marker.length
        val content = r.open + r.marker.length
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(FlowPos(p, close), FlowPos(p, pos.offset))).first?.let { cmds += it }
        ed.deleteRange(FlowRange(FlowPos(p, r.open), FlowPos(p, content))).first?.let { cmds += it }
        val end = r.open + (close - content)
        ed.setCharStyle(FlowRange(FlowPos(p, r.open), FlowPos(p, end))) { r.emph.apply(it, highlight) }?.let { cmds += it }
        return Result(ed.combined(cmds), FlowPos(p, end), outside)
    }

    /**
     * Drop both markers and set what was between them. A display equation alone
     * on its line draws centred, but the paragraph's own alignment is left as it
     * was: turning it on here would outlive the equation.
     */
    private fun applyMath(flow: TextFlow, pos: FlowPos, r: Math): Result {
        val p = pos.para
        val marker = r.marker
        val ed = FlowEditor(flow)
        val outside = ed.charStyleAt(FlowPos(p, r.open))
        val text = flow.paragraphs[p].plainText()
        // The padding goes with the markers. A run may not begin with a space:
        // the breaker reads one as somewhere to wrap, and the formula carries its
        // whole width on its first character, so the line would hang on nothing.
        var close = pos.offset - marker
        var content = r.open + marker
        while (content < close && text[content].isWhitespace()) content++
        while (close > content && text[close - 1].isWhitespace()) close--
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(FlowPos(p, close), FlowPos(p, pos.offset))).first?.let { cmds += it }
        ed.deleteRange(FlowRange(FlowPos(p, r.open), FlowPos(p, content))).first?.let { cmds += it }
        val end = r.open + (close - content)
        ed.setCharStyle(FlowRange(FlowPos(p, r.open), FlowPos(p, end))) {
            it.copy(math = true, mathDisplay = r.display)
        }?.let { cmds += it }
        return Result(ed.combined(cmds), FlowPos(p, end), outside.copy(math = false, mathDisplay = false))
    }

    private fun applyFence(flow: TextFlow, pos: FlowPos, r: Fence): Result {
        val ed = FlowEditor(flow)
        val caret = FlowPos(pos.para, 0)
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(caret, FlowPos(pos.para, flow.paragraphs[pos.para].length))).first?.let { cmds += it }
        ed.setParaStyle(FlowRange.caret(caret)) { it.codeLang = r.lang }?.let { cmds += it }
        return Result(ed.combined(cmds), caret, CharStyle.DEFAULT)
    }

    /** The marker goes and every paragraph of the quote takes one new callout of [r]'s kind. */
    private fun applyCallout(flow: TextFlow, pos: FlowPos, r: Callout): Result {
        val ed = FlowEditor(flow)
        val caret = FlowPos(pos.para, 0)
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(caret, FlowPos(pos.para, r.prefix))).first?.let { cmds += it }
        val old = flow.paragraphs[pos.para].quote
        var first = pos.para
        var last = pos.para
        while (first > 0 && flow.paragraphs[first - 1].quote === old) first--
        while (last + 1 < flow.paragraphs.size && flow.paragraphs[last + 1].quote === old) last++
        val callout = FlowQuote(r.kind)
        ed.setParaStyle(FlowRange(FlowPos(first, 0), FlowPos(last, 0))) { it.quote = callout }?.let { cmds += it }
        return Result(ed.combined(cmds), caret)
    }

    /** The brackets and address go, and the text left between them links to it. */
    private fun applyLink(flow: TextFlow, pos: FlowPos, r: LinkUp): Result {
        val p = pos.para
        val ed = FlowEditor(flow)
        val outside = ed.charStyleAt(FlowPos(p, r.open))
        val textEnd = r.open + 1 + r.text.length
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(FlowPos(p, textEnd), pos)).first?.let { cmds += it }
        ed.deleteRange(FlowRange(FlowPos(p, r.open), FlowPos(p, r.open + 1))).first?.let { cmds += it }
        val end = r.open + r.text.length
        ed.setCharStyle(FlowRange(FlowPos(p, r.open), FlowPos(p, end))) { it.copy(link = r.uri) }?.let { cmds += it }
        return Result(ed.combined(cmds), FlowPos(p, end), outside.copy(link = null))
    }

    /** The line becomes a rule and Enter goes on to the line after it, as it would have anyway. */
    private fun applyRule(flow: TextFlow, pos: FlowPos): Result {
        val ed = FlowEditor(flow)
        val start = FlowPos(pos.para, 0)
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(start, FlowPos(pos.para, flow.paragraphs[pos.para].length))).first?.let { cmds += it }
        ed.setParaStyle(FlowRange.caret(start)) {
            it.rule = true
            it.list = ListKind.NONE
            it.checked = false
            it.headingLevel = 0
        }?.let { cmds += it }
        val (split, caret) = ed.insertText(start, "\n")
        split?.let { cmds += it }
        return Result(ed.combined(cmds), caret, CharStyle.DEFAULT)
    }

    private fun applyStrip(flow: TextFlow, pos: FlowPos, rule: Strip): Result {
        val ed = FlowEditor(flow)
        val caret = FlowPos(pos.para, 0)
        val len = flow.paragraphs[pos.para].length
        return when (rule) {
            Strip.BREAK -> Result(ed.setParaStyle(FlowRange.caret(caret)) { it.breakBefore = false }, caret)
            Strip.RULE -> Result(ed.setParaStyle(FlowRange.caret(caret)) { it.rule = false }, caret, CharStyle.DEFAULT)
            Strip.CODE -> Result(ed.setParaStyle(FlowRange.caret(caret)) { it.codeLang = null }, caret, CharStyle.DEFAULT)
            Strip.LIST -> Result(
                ed.setParaStyle(FlowRange.caret(caret)) {
                    it.list = ListKind.NONE
                    it.checked = false
                },
                caret,
            )
            Strip.LIST_EXIT -> Result(
                ed.setParaStyle(FlowRange.caret(caret)) {
                    if (it.indent > 0) {
                        it.indent--
                    } else {
                        it.list = ListKind.NONE
                        it.checked = false
                    }
                },
                caret,
            )
            Strip.HEADING -> {
                val cmds = mutableListOf<Command>()
                ed.setParaStyle(FlowRange.caret(caret)) { it.headingLevel = 0 }?.let { cmds += it }
                if (len > 0) {
                    ed.setCharStyle(FlowRange(caret, FlowPos(pos.para, len))) {
                        it.copy(bold = false, sizePt = null)
                    }?.let { cmds += it }
                }
                Result(ed.combined(cmds), caret, CharStyle.DEFAULT)
            }
            Strip.QUOTE, Strip.QUOTE_EXIT -> Result(ed.setParaStyle(FlowRange.caret(caret)) { it.quote = null }, caret)
            Strip.INDENT -> Result(ed.setParaStyle(FlowRange.caret(caret)) { it.indent-- }, caret)
        }
    }
}
