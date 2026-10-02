package com.xnotes.core.pdf

/** A link found in text: characters [start, end) and the address it opens. */
data class TextLink(val start: Int, val end: Int, val uri: String)

/**
 * Finds what a reader would take for a link in plain text: `http://` and `https://` addresses,
 * `www.` hosts and email addresses. Punctuation that ends a sentence is left out of the link, and
 * so is a closing bracket the link did not open, so "(see www.a.org)." links just the host.
 */
object LinkFinder {

    private val PATTERN = Regex(
        "(?:https?://[^\\s<>\"]+)" +
            "|(?:www\\.[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+[^\\s<>\"]*)" +
            "|(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})",
        RegexOption.IGNORE_CASE,
    )

    private const val TRAILING = ".,;:!?'\"*_~>"

    fun find(text: String): List<TextLink> {
        val out = mutableListOf<TextLink>()
        for (m in PATTERN.findAll(text)) {
            val start = m.range.first
            // Mid-word matches are not links: "foo.www.bar" or the tail of an address already taken.
            if (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] in "@.-_/")) continue
            val end = trimmedEnd(text, start, m.range.last + 1)
            if (end <= start) continue
            val body = text.substring(start, end)
            val uri = when {
                body.startsWith("http://", ignoreCase = true) || body.startsWith("https://", ignoreCase = true) -> body
                body.startsWith("www.", ignoreCase = true) -> "https://$body"
                '@' in body -> "mailto:$body"
                else -> continue
            }
            // A scheme with nothing after it is not an address.
            if (uri.substringAfter("://", uri).isEmpty()) continue
            out += TextLink(start, end, uri)
        }
        return out
    }

    /** The end of a match once trailing punctuation and unopened closing brackets are dropped. */
    private fun trimmedEnd(text: String, start: Int, end: Int): Int {
        var e = end
        while (e > start) {
            val c = text[e - 1]
            val open = when (c) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                else -> null
            }
            if (open != null) {
                val body = text.substring(start, e)
                if (body.count { it == open } < body.count { it == c }) {
                    e--
                    continue
                }
                break
            }
            if (c in TRAILING) {
                e--
                continue
            }
            break
        }
        return e
    }
}
