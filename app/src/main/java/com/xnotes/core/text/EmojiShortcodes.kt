package com.xnotes.core.text

import com.xnotes.core.history.Command

/**
 * GitHub's emoji shortcodes, gemoji's aliases: `:name:` written in the text stands
 * for the emoji it names. The table is a classpath resource, loaded once on first
 * use, in gemoji's order, which is how names that match equally well are offered.
 * A shortcode only counts standing apart from the words around it, so a clock time
 * like "10:30:00" is never read as one.
 */
object EmojiShortcodes {

    private val table: List<Pair<String, String>> by lazy { load() }
    private val byName: Map<String, String> by lazy { table.toMap() }

    private val SHORTCODE = Regex("(?<![A-Za-z0-9]):([a-z0-9_+-]+):(?![A-Za-z0-9])")
    private val TYPED = Regex("(?<![A-Za-z0-9]):([a-z0-9_+-]+):$")
    private val NAME_CHARS = Regex("[a-z0-9_+-]+")

    /** A completion needs this many letters after the colon before it opens. */
    const val MIN_QUERY = 2

    /** The emoji [name] stands for, or null. */
    fun emoji(name: String): String? = byName[name]

    /** Up to [limit] names starting with [prefix], with their emoji: exact first, then shorter, then table order. */
    fun complete(prefix: String, limit: Int = MAX_SUGGESTIONS): List<Pair<String, String>> {
        if (prefix.isEmpty()) return emptyList()
        return table.withIndex()
            .filter { it.value.first.startsWith(prefix) }
            .sortedWith(compareBy({ it.value.first != prefix }, { it.value.first.length }, { it.index }))
            .take(limit)
            .map { it.value }
    }

    /** [text] with every shortcode in it that names an emoji turned into that emoji. */
    fun replaceAll(text: String): String =
        if (':' !in text) text else SHORTCODE.replace(text) { m -> byName[m.groupValues[1]] ?: m.value }

    /** The shortcode [before] just closed with its colon: where it starts and its emoji, or null. */
    fun closedAt(before: String): Pair<Int, String>? {
        val m = TYPED.find(before) ?: return null
        return m.range.first to (byName[m.groupValues[1]] ?: return null)
    }

    /** An open completion: the colon at [colon] in paragraph [para], and the name typed after it. */
    class Query(val para: Int, val colon: Int, val text: String) {
        val range: FlowRange get() = FlowRange(FlowPos(para, colon), FlowPos(para, colon + 1 + text.length))
    }

    /**
     * The completion the caret at [pos] is typing, or null: a colon starting a word, then at
     * least [MIN_QUERY] name characters that still match something. Never in code or maths.
     */
    fun queryAt(flow: TextFlow, pos: FlowPos): Query? {
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        if (para.codeLang != null) return null
        val text = para.plainText()
        if (pos.offset > text.length) return null
        val before = text.take(pos.offset)
        val colon = before.lastIndexOf(':')
        if (colon < 0 || (colon > 0 && !before[colon - 1].isWhitespace() && before[colon - 1] !in OPENERS)) return null
        val typed = before.substring(colon + 1)
        if (typed.length < MIN_QUERY || !NAME_CHARS.matches(typed)) return null
        val style = FlowEditor(flow).charStyleAt(FlowPos(pos.para, colon + 1))
        if (style.code || style.math) return null
        return Query(pos.para, colon, typed).takeIf { complete(typed, 1).isNotEmpty() }
    }

    /** Replace [q]'s typed shortcode with [emoji] as one edit. */
    fun replace(flow: TextFlow, q: Query, emoji: String): Pair<Command?, FlowPos> =
        FlowEditor(flow).replaceRange(q.range, emoji, null)

    private const val MAX_SUGGESTIONS = 8

    /** Absolute, so a release build that moves this class still finds it. */
    private const val TABLE = "/com/xnotes/core/text/emoji.tsv"
    private const val OPENERS = "([{\"'"

    private fun load(): List<Pair<String, String>> {
        val stream = EmojiShortcodes::class.java.getResourceAsStream(TABLE) ?: return emptyList()
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }.toList()
        }
    }
}
