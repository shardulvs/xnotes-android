package com.xnotes.core.pdf

/**
 * Finds the character under a point of a page, in points as displayed. A plain scan: a dense page
 * holds a few thousand characters, which take well under a millisecond to look through.
 */
object TextHit {

    /**
     * The character whose box holds ([x], [y]), the one whose middle is nearest when boxes overlap;
     * failing that, the nearest within [reach] of its line heights or [slop] points, whichever is
     * more. -1 for none. Characters PDFium inferred have no box and are never hit.
     */
    fun charAt(text: PageText, x: Float, y: Float, slop: Float = 0f, reach: Float = 1.5f): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        var bestMid = Float.MAX_VALUE
        for (i in 0 until text.length) {
            if (!text.hasBox(i)) continue
            val l = text.left(i)
            val t = text.top(i)
            val r = text.right(i)
            val b = text.bottom(i)
            val dx = maxOf(l - x, 0f, x - r)
            val dy = maxOf(t - y, 0f, y - b)
            val dist = dx * dx + dy * dy
            val limit = maxOf(slop, reach * text.lineHeight(i))
            if (dist > limit * limit || dist > bestDist) continue
            val mx = (l + r) / 2 - x
            val my = (t + b) / 2 - y
            val mid = mx * mx + my * my
            if (dist < bestDist || mid < bestMid) {
                best = i
                bestDist = dist
                bestMid = mid
            }
        }
        return best
    }

    /**
     * The boundary between characters nearest ([x], [y]): before the character [charAt] finds, or
     * after it when the point lies past its middle along its line. Characters sharing one glyph,
     * like the letters of a ligature, are never split. -1 for none.
     */
    fun offsetAt(text: PageText, x: Float, y: Float, slop: Float = 0f, reach: Float = 1.5f): Int {
        val i = charAt(text, x, y, slop, reach)
        if (i < 0) return -1
        var first = i
        while (first > 0 && sameBox(text, first - 1, i)) first--
        var last = i
        while (last + 1 < text.length && sameBox(text, last + 1, i)) last++
        val past = when (text.quarter(i)) {
            1 -> y > (text.top(i) + text.bottom(i)) / 2
            2 -> x < (text.left(i) + text.right(i)) / 2
            3 -> y < (text.top(i) + text.bottom(i)) / 2
            else -> x > (text.left(i) + text.right(i)) / 2
        }
        return if (past) last + 1 else first
    }

    private fun sameBox(text: PageText, a: Int, b: Int): Boolean =
        text.left(a) == text.left(b) && text.top(a) == text.top(b) &&
            text.right(a) == text.right(b) && text.bottom(a) == text.bottom(b)
}
