package com.xnotes.core.text

/**
 * Turns what another app put on the clipboard into markdown for [MarkdownParser]: HTML (web pages,
 * office apps) keeps its headings, emphasis, links, lists, quotes, tables, code and TeX maths, and
 * plain text keeps its maths. Each chunk is parsed on its own and gives one paragraph or block, so
 * no blank lines appear between them; a blank chunk is an empty line.
 */
object RichPaste {

    private val TOKEN = Regex(
        "<!--[\\s\\S]*?-->|<![^>]*>|<\\?[^>]*>|<(/?)([A-Za-z][\\w:-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>|[^<]+|<",
    )
    private val MATH_ELEMENT = Regex("<math\\b([^>]*)>([\\s\\S]*?)</math>", RegexOption.IGNORE_CASE)
    private val TEX_ANNOTATION = Regex("<annotation[^>]*application/x-tex[^>]*>([\\s\\S]*?)</annotation>", RegexOption.IGNORE_CASE)
    private val MATH_TEXT = Regex("\\$\\$[\\s\\S]+?\\$\\$|\\\\\\[[\\s\\S]+?\\\\]|\\\\\\([\\s\\S]+?\\\\\\)|(?<![\\w$])\\$[^\\s$](?:[^$\\n]*[^\\s$])?\\$(?![\\w$])")
    private val MARKDOWN_HINT = Regex("(?m)^#{1,6}[ \\t]+\\S|\\*\\*[^*\\s][^*\\n]*\\*\\*|^[ \\t]*```|\\[[^\\]\\n]+]\\((https?://|mailto:)")
    private val PLAIN_BULLET = Regex("([ \\t]*)([•◦▪▫‣●○■□·–*+-]|\\d{1,3}[.)])[ \\t]+(\\S.*)")
    private val CHAR_BULLET = Regex("^[•◦▪▫‣●○■□·][\\s\u00a0]+")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val ESCAPED = "\\*_<>~=|#`$".toSet()
    private val LINE_START = Regex("^(\\s*)([-+]|\\d+[.)])(?=\\s|$)")
    private val VOID = setOf("br", "hr", "img", "input", "meta", "link", "wbr", "col", "area", "base", "source")
    private val SKIPPED = setOf("script", "style", "head", "title", "template", "noscript")
    private val BLOCKS = setOf(
        "p", "div", "li", "ul", "ol", "blockquote", "pre", "table", "tr", "section", "article",
        "header", "footer", "dt", "dd", "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6",
    )
    private const val MATH_MARK = '\u0000'

    /**
     * Whether plain [text] is raw markdown (what AI chats and markdown editors copy), to be read
     * whole as markdown: it has a heading, bold, a code fence or a link written out.
     */
    fun looksLikeMarkdown(text: String): Boolean = MARKDOWN_HINT.containsMatchIn(text)

    /** Markdown chunks for plain [text] that holds TeX maths or bullet lines, or null when it holds neither. */
    fun fromPlain(text: String): List<String>? {
        val lines = MATH_TEXT.replace(text) { it.value.replace('\n', ' ') }.split('\n').map { it.trimEnd() }
        if (!MATH_TEXT.containsMatchIn(text) && lines.none(PLAIN_BULLET::matches)) return null
        val chunks = mutableListOf<String>()
        val items = mutableListOf<String>()
        val indents = mutableListOf<Int>()
        fun endList() {
            if (items.isNotEmpty()) chunks += items.joinToString("\n")
            items.clear()
            indents.clear()
        }
        for (line in lines) {
            val m = PLAIN_BULLET.matchEntire(line)
            if (m == null) {
                endList()
                chunks += escapeText(line)
                continue
            }
            val indent = m.groupValues[1].replace("\t", "    ").length
            while (indents.isNotEmpty() && indent < indents.last()) indents.removeAt(indents.size - 1)
            if (indents.isEmpty() || indent > indents.last()) indents += indent
            val mark = m.groupValues[2]
            val marker = if (mark[0].isDigit()) mark.dropLast(1) + ". " else "- "
            items += "    ".repeat(indents.size - 1) + marker + escapeText(m.groupValues[3])
        }
        endList()
        return chunks
    }

    /** Markdown chunks for the clipboard's [html], empty when it shows no text. */
    fun fromHtml(html: String): List<String> {
        val maths = mutableListOf<String>()
        val marked = MATH_ELEMENT.replace(html) { m ->
            val tex = TEX_ANNOTATION.find(m.groupValues[2])?.groupValues?.get(1)?.let(::decode)?.trim()
                ?: return@replace m.value
            val fence = if (Regex("display\\s*=\\s*[\"']block").containsMatchIn(m.groupValues[1])) "$$" else "$"
            maths += "$fence${tex.replace('\n', ' ')}$fence"
            "$MATH_MARK${maths.size - 1}$MATH_MARK"
        }
        return HtmlReader(maths).apply { read(marked) }.finish()
    }

    private class Frame(val tag: String, val close: String) {
        val opener get() = if (close.startsWith("]")) "[" else close.reversed()

        /** Whether [opener] is already in the text, so the mark has to be closed there. */
        var emitted = false
    }

    private class HtmlReader(private val maths: List<String>) {
        private val chunks = mutableListOf<String>()
        private val line = StringBuilder()
        private val stack = mutableListOf<Frame>()
        private val pending = StringBuilder()
        private var skipDepth = 0
        private var heading = 0
        private var pre: StringBuilder? = null
        private var codeDepth = 0

        private val container = mutableListOf<String>()
        private val lists = mutableListOf<IntArray>() // [ordered, next number]
        private var quoteDepth = 0
        private var itemMarker: String? = null
        private var itemIndent = ""

        private var rows: MutableList<MutableList<String>>? = null
        private var cell: StringBuilder? = null

        fun read(html: String) {
            for (m in TOKEN.findAll(html)) {
                val name = m.groupValues[2].lowercase()
                when {
                    name.isNotEmpty() && m.groupValues[1].isEmpty() -> open(name, m.groupValues[3])
                    name.isNotEmpty() -> close(name)
                    m.value.startsWith("<!") || m.value.startsWith("<?") -> Unit
                    skipDepth == 0 -> text(decode(m.value))
                }
            }
        }

        fun finish(): List<String> {
            flush()
            endContainer()
            return chunks.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        }

        private fun open(name: String, attrs: String) {
            val void = name in VOID || attrs.trimEnd().endsWith("/")
            if (skipDepth > 0) {
                if (!void) skipDepth++
                return
            }
            val style = attr(attrs, "style")?.lowercase()?.replace(" ", "").orEmpty()
            if (name in SKIPPED || attr(attrs, "aria-hidden") == "true" || "display:none" in style) {
                if (!void) skipDepth = 1
                return
            }
            pre?.let { p ->
                if (name == "br") p.append('\n')
                if (!void) stack += Frame(name, "")
                return
            }
            when (name) {
                "br" -> if (cell != null) cell?.append(' ') else if (line.isBlank() && container.isEmpty() && rows == null) chunks += "" else flush()
                "hr" -> { flush(); if (rows == null) chunks += "---" }
                "img" -> attr(attrs, "alt")?.takeIf { it.isNotBlank() }?.let { text(it) }
            }
            if (void) return
            if (name in BLOCKS) flush()
            var close = ""
            when (name) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> heading = name[1] - '0'
                "ul", "ol" -> lists += intArrayOf(if (name == "ol") 1 else 0, attr(attrs, "start")?.toIntOrNull() ?: 1)
                "li" -> lists.lastOrNull()?.let { l ->
                    itemIndent = "  ".repeat((lists.size - 1).coerceAtLeast(0) * 2)
                    itemMarker = if (l[0] == 1) "${l[1]++}. " else "- "
                }
                "blockquote" -> quoteDepth++
                "pre" -> pre = StringBuilder()
                "table" -> if (rows == null) rows = mutableListOf()
                "tr" -> rows?.add(mutableListOf())
                "td", "th" -> if (rows != null) cell = StringBuilder()
                "b", "strong" -> close = if (Regex("font-weight:(normal|[1-4]00)").containsMatchIn(style)) "" else "**"
                "i", "em" -> close = "*"
                "s", "del", "strike" -> close = "~~"
                "mark" -> close = "=="
                "code", "kbd", "samp", "tt" -> { close = "`"; codeDepth++ }
                "a" -> attr(attrs, "href")?.let(MarkdownParser::linkTarget)?.let { close = "]($it)" }
                !in BLOCKS -> close = buildString {
                    if (Regex("font-weight:(bold|[6-9]00)").containsMatchIn(style)) append("**")
                    if ("font-style:italic" in style) append("*")
                    if ("line-through" in style) append("~~")
                }
            }
            val frame = Frame(name, close)
            pending.append(frame.opener)
            stack += frame
        }

        private fun close(name: String) {
            if (skipDepth > 0) {
                skipDepth--
                return
            }
            val at = stack.indexOfLast { it.tag == name }
            if (at < 0) return
            while (stack.size > at) {
                val f = stack.removeAt(stack.size - 1)
                closeInline(f)
                closeBlock(f.tag)
            }
        }

        private fun closeInline(f: Frame) {
            if (f.close.isEmpty()) return
            if (f.tag in setOf("code", "kbd", "samp", "tt")) codeDepth--
            if (!f.emitted) {
                // Still waiting for text, so the mark wrapped nothing: drop it.
                if (pending.endsWith(f.opener)) pending.setLength(pending.length - f.opener.length)
                return
            }
            // A mark must hug its text, so trailing spaces go after it.
            val target = cell ?: line
            val spaces = target.length - target.trimEnd().length
            target.setLength(target.length - spaces)
            target.append(f.close).append(" ".repeat(spaces))
        }

        private fun closeBlock(name: String) {
            when (name) {
                "pre" -> pre?.let { p ->
                    pre = null
                    val code = "```\n${p.toString().trim('\n')}\n```"
                    if (container.isEmpty()) chunks += code else container += code
                }
                "td", "th" -> cell?.let { c ->
                    rows?.lastOrNull()?.add(c.toString().trim())
                    cell = null
                }
                "table" -> rows?.let { r ->
                    rows = null
                    table(r.filter { it.isNotEmpty() })
                }
                else -> if (name in BLOCKS) {
                    flush()
                    when (name) {
                        "h1", "h2", "h3", "h4", "h5", "h6" -> heading = 0
                        "li" -> itemMarker = null
                        "ul", "ol" -> { lists.removeLastOrNull(); endContainer() }
                        "blockquote" -> { quoteDepth = (quoteDepth - 1).coerceAtLeast(0); endContainer() }
                    }
                }
            }
        }

        private fun text(raw: String) {
            pre?.let { it.append(raw); return }
            var t = raw.replace(Regex("\\s+"), " ")
            val target = cell ?: line
            if (target.isEmpty() || target.last() == ' ') t = t.trimStart()
            if (t.isEmpty()) return
            if (pending.isNotEmpty()) {
                if (t.first() == ' ') { target.append(' '); t = t.trimStart() }
                if (t.isEmpty()) return
                target.append(pending)
                pending.setLength(0)
            }
            stack.forEach { it.emitted = true }
            target.append(if (codeDepth > 0) t else escapeText(t))
        }

        /** End the current line: it becomes a chunk, or a line of the list or quote being read. */
        private fun flush() {
            if (cell != null) {
                cell?.append(' ')
                return
            }
            // Marks still open close at the end of the line and open again on the next.
            val marks = stack.filter { it.close.isNotEmpty() }
            if (line.isNotBlank()) {
                line.setLength(line.trimEnd().length)
                marks.asReversed().forEach { if (it.emitted) line.append(it.close) }
            }
            pending.setLength(0)
            marks.forEach { pending.append(it.opener); it.emitted = false }
            var t = line.toString().trim()
            line.setLength(0)
            if (t.isEmpty()) return
            t = restoreMaths(t)
            // Bullets some apps write as a character instead of a list.
            if (itemMarker == null && lists.isEmpty()) t = CHAR_BULLET.replace(t, "- ")
            if (heading > 0) t = "#".repeat(heading) + " " + t
            if (rows != null) return
            val marker = itemMarker
            val quote = "> ".repeat(quoteDepth)
            when {
                marker != null -> {
                    container += quote + itemIndent + marker + t
                    itemMarker = null
                    itemIndent += " ".repeat(marker.length)
                }
                lists.isNotEmpty() -> container += quote + itemIndent + t
                quoteDepth > 0 -> {
                    if (container.isNotEmpty()) container += quote.trimEnd()
                    container += quote + t
                }
                else -> chunks += t
            }
        }

        private fun endContainer() {
            if (lists.isEmpty() && quoteDepth == 0 && container.isNotEmpty()) {
                chunks += container.joinToString("\n")
                container.clear()
            }
        }

        private fun table(rows: List<List<String>>) {
            if (rows.isEmpty()) return
            val cols = rows.maxOf { it.size }
            fun row(cells: List<String>) = (0 until cols).joinToString(" | ", "| ", " |") { restoreMaths(cells.getOrNull(it).orEmpty()) }
            chunks += (listOf(row(rows[0]), row(List(cols) { "---" })) + rows.drop(1).map(::row)).joinToString("\n")
        }

        private fun restoreMaths(s: String): String =
            Regex("$MATH_MARK(\\d+)$MATH_MARK").replace(s) { maths.getOrNull(it.groupValues[1].toInt()).orEmpty() }
    }

    private fun attr(attrs: String, name: String): String? {
        val m = Regex("(?:^|\\s)$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(attrs)
            ?: return null
        return decode(m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty())
    }

    private fun decode(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> ENTITIES[e]
        } ?: m.value
    }

    /** [text] with markdown's marks taken literally, except the TeX maths in it. */
    private fun escapeText(text: String): String {
        val out = StringBuilder()
        var last = 0
        fun plain(s: String) {
            for (c in s) {
                // A backslash before a bracket would read as \[ maths \], so brackets go as entities.
                when (c) {
                    '[' -> out.append("&#91;")
                    ']' -> out.append("&#93;")
                    in ESCAPED -> out.append('\\').append(c)
                    else -> out.append(c)
                }
            }
        }
        for (m in MATH_TEXT.findAll(text)) {
            plain(text.substring(last, m.range.first))
            out.append(m.value.replace('\n', ' '))
            last = m.range.last + 1
        }
        plain(text.substring(last))
        return out.toString().split('\n').joinToString("\n") { LINE_START.replace(it) { m -> m.groupValues[1] + m.groupValues[2].dropLast(1) + "\\" + m.groupValues[2].last() } }
    }
}
