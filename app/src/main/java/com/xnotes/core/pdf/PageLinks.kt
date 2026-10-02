package com.xnotes.core.pdf

import java.util.Collections
import java.util.WeakHashMap

/**
 * The addresses written out in a PDF page's text, found the way the export finds them in typed text
 * ([LinkFinder]): http and https addresses, www. hosts and email addresses. A line break ends one,
 * except after a line-end hyphen, which stays in the address with the next line joined on (as
 * PDFium's own finder does).
 */
object PageLinks {

    /** Each page text's links once looked for: a tap runs [at], and a dense page takes a few ms to search. */
    private val found: MutableMap<PageText, List<TextLink>> = Collections.synchronizedMap(WeakHashMap())

    /** Each link with its range of [text]'s characters, [TextLink.start] to [TextLink.end] exclusive. */
    fun find(text: PageText): List<TextLink> {
        val sb = StringBuilder(text.length)
        val source = IntArray(2 * text.length)
        for (i in 0 until text.length) {
            val cp = text.codepoint(i)
            if (cp == 0) continue
            val from = sb.length
            if (text.isLineBreak(i)) sb.append(' ') else sb.appendCodePoint(cp)
            for (k in from until sb.length) source[k] = i
        }
        return LinkFinder.find(sb.toString()).map { TextLink(source[it.start], source[it.end - 1] + 1, it.uri) }
    }

    /** The link whose line boxes, grown by [slop] on every side, hold ([x], [y]) in points as displayed; null when none does. */
    fun at(text: PageText, x: Float, y: Float, slop: Float = 0f): TextLink? {
        // Most taps land off the text, where no address can be.
        if (TextHit.charAt(text, x, y, slop, reach = 0f) < 0) return null
        for (link in found.getOrPut(text) { find(text) }) {
            for (q in TextQuads.of(text, link.start, link.end)) {
                if (x >= q.left - slop && x <= q.right + slop && y >= q.top - slop && y <= q.bottom + slop) return link
            }
        }
        return null
    }
}
