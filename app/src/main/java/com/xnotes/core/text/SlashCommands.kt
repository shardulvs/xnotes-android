package com.xnotes.core.text

import com.xnotes.core.history.Command

/**
 * The "/" command menu: the query is typed into the paragraph itself, filtered as
 * it grows and deleted when it commits. Keeping it in the document rather than in
 * a focused field means the IME never changes target mid-word, and dismissing just
 * leaves behind the literal text that was typed. Everything here is pure: the host
 * flushes its typing burst before calling anything that mutates, and executes the
 * chosen [Kind] itself, since half of them need font, colour or locale services the
 * core has no business knowing about. Keywords are ASCII; the visible labels belong
 * to the UI, which can localize them.
 */
object SlashCommands {

    /** What a committed entry does. The host dispatches on it. */
    enum class Kind { HEADING, BODY, BULLET, ORDERED, TODO, QUOTE, CALLOUT, CODE, TABLE, PAGE_BREAK, SIZE, COLOR, MATH, DATE, TIME }

    /**
     * One menu entry. [keys] are matched by prefix, so "h" offers all six headings
     * and "h2" only one. [markdown] names the shortcut this duplicates, shown as a
     * hint so the menu teaches the input rules it overlaps, and [param] names the
     * argument the entry wants, as a placeholder rather than a value so no one
     * mistakes it for something to type verbatim.
     */
    class Entry(
        val kind: Kind,
        val id: String,
        val keys: List<String>,
        val level: Int = 0,
        val needsArg: Boolean = false,
        val markdown: String? = null,
        val param: String? = null,
        /** Lets this entry's argument run past [MAX_QUERY]; a formula is still one argument. */
        val longArg: Boolean = false,
    )

    /** An open query: where the "/" sits in its paragraph, and everything typed after it. */
    class Query(val para: Int, val slash: Int, val text: String) {
        /** The keyword, before any argument. */
        val word: String get() = text.substringBefore(' ')

        /** The argument, once a space has closed the keyword. */
        val arg: String get() = text.substringAfter(' ', "").trim()

        /** True once the keyword is closed, which freezes the candidate list. */
        val hasArg: Boolean get() = ' ' in text

        /** The range the query occupies, slash included. */
        val range: FlowRange get() = FlowRange(FlowPos(para, slash), FlowPos(para, slash + 1 + text.length))
    }

    /** Past this the keyword is plainly prose, not a command. */
    const val MAX_QUERY = 24

    /**
     * How far past [MAX_QUERY] an [Entry.longArg] command may run. Only the keyword
     * is really a guess at intent; once it has closed on an entry that wants a long
     * argument, the rest of the line belongs to that entry.
     */
    const val MAX_ARG = 160

    private val ENTRIES: List<Entry> = buildList {
        for (n in 1..Paragraph.MAX_HEADING) {
            add(Entry(Kind.HEADING, "h$n", listOf("h$n", "heading$n"), level = n, markdown = "#".repeat(n)))
        }
        add(Entry(Kind.BODY, "body", listOf("body", "text", "paragraph")))
        add(Entry(Kind.BULLET, "bullet", listOf("bullet", "list", "ul"), markdown = "-"))
        add(Entry(Kind.ORDERED, "number", listOf("number", "ordered", "ol"), markdown = "1."))
        add(Entry(Kind.TODO, "todo", listOf("todo", "task", "check"), markdown = "[]"))
        add(Entry(Kind.QUOTE, "quote", listOf("quote", "blockquote"), markdown = ">"))
        // A callout's level is its kind's ordinal.
        for (kind in CalloutKind.entries) {
            add(Entry(Kind.CALLOUT, kind.id, listOf(kind.id, "callout${kind.id}"), level = kind.ordinal, markdown = "[!${kind.id}]"))
        }
        add(Entry(Kind.CODE, "code", listOf("code", "pre"), markdown = "```", param = "LANG"))
        add(Entry(Kind.TABLE, "table", listOf("table", "grid"), param = "ROWSxCOLS"))
        add(Entry(Kind.PAGE_BREAK, "pagebreak", listOf("pagebreak", "newpage", "break")))
        add(Entry(Kind.SIZE, "size", listOf("size", "fontsize", "pt"), needsArg = true, param = "NUMBER"))
        add(Entry(Kind.COLOR, "color", listOf("color", "colour"), needsArg = true, param = "NAME"))
        add(Entry(
            Kind.MATH, "equation", listOf("equation", "math", "latex", "eq"),
            needsArg = true, markdown = "$", param = "LATEX", longArg = true,
        ))
        add(Entry(Kind.DATE, "date", listOf("date", "today")))
        add(Entry(Kind.TIME, "time", listOf("time", "now")))
    }

    /** Every entry, in menu order. */
    fun entries(): List<Entry> = ENTRIES

    /**
     * The query the caret sits in, or null. The slash has to start a word, so
     * "and/or", "1/2" and URLs never open the menu, and what follows has to still
     * match something, so the menu closes itself the moment the word goes nowhere.
     */
    fun queryAt(flow: TextFlow, pos: FlowPos): Query? {
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        if (para.codeLang != null) return null
        val text = para.plainText()
        if (pos.offset > text.length) return null
        val before = text.take(pos.offset)
        val slash = before.lastIndexOf('/')
        if (slash < 0) return null
        if (slash > 0 && !before[slash - 1].isWhitespace()) return null
        val typed = before.substring(slash + 1)
        if (typed.substringBefore(' ').length > MAX_QUERY) return null
        val q = Query(pos.para, slash, typed)
        val cands = candidates(q)
        if (cands.isEmpty()) return null
        val limit = if (cands.any { it.longArg }) MAX_QUERY + MAX_ARG else MAX_QUERY
        return if (typed.length > limit) null else q
    }

    /** The entries [q] still matches; a closed keyword matches only itself. */
    fun candidates(q: Query): List<Entry> {
        val word = q.word.lowercase()
        if (q.hasArg) return ENTRIES.filter { e -> e.keys.any { it == word } }
        if (word.isEmpty()) return ENTRIES
        return ENTRIES.filter { e -> e.keys.any { it.startsWith(word) } }
    }

    /** True when [entry] can commit with what has been typed so far. */
    fun ready(q: Query, entry: Entry): Boolean = !entry.needsArg || q.arg.isNotEmpty()

    /**
     * The offset of the slash to drop because "//" was just typed: the escape hatch
     * for a literal slash where the menu would otherwise open. Pure, so the caller
     * can flush its burst before deleting. Null when the pair is not a menu trigger
     * anyway, which is what leaves "http://" alone.
     */
    fun escapeAt(flow: TextFlow, pos: FlowPos): Int? {
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        if (para.codeLang != null) return null
        val text = para.plainText()
        if (pos.offset < 2 || pos.offset > text.length) return null
        if (text[pos.offset - 1] != '/' || text[pos.offset - 2] != '/') return null
        if (pos.offset >= 3 && !text[pos.offset - 3].isWhitespace()) return null
        return pos.offset - 1
    }

    /** Delete the typed query, leaving the caret where the slash was. */
    fun strip(flow: TextFlow, q: Query): Pair<Command?, FlowPos> =
        FlowEditor(flow).deleteRange(q.range).first to FlowPos(q.para, q.slash)

    /** Replace the query with [text] (the date and time stamps) as one edit. */
    fun replace(flow: TextFlow, q: Query, text: String): Pair<Command?, FlowPos> =
        FlowEditor(flow).replaceRange(q.range, text, null)
}
