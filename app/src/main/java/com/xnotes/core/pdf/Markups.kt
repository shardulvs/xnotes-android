package com.xnotes.core.pdf

import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup

/** Turning selected PDF text into markups. */
object Markups {

    /**
     * The markups [sel] makes as [type] in [color] at [intensity]: one on each note page it covers,
     * over its lines there and holding the text it marks, made at [now]. A page whose share has no
     * glyphs, or whose text [textOf] does not have, makes none.
     */
    fun of(
        sel: TextSelection,
        textOf: (Int) -> PageText?,
        type: MarkupType,
        color: Rgba,
        intensity: Double,
        now: Long,
    ): List<Pair<Int, TextMarkup>> {
        val out = ArrayList<Pair<Int, TextMarkup>>()
        for (page in sel.start.page..sel.end.page) {
            val text = textOf(page) ?: continue
            val range = sel.rangeOn(page, text)
            if (range.isEmpty()) continue
            val quads = TextQuads.of(text, range.first, range.last + 1)
            if (quads.isEmpty()) continue
            val marked = text.text(range.first, range.last + 1)
            out += page to TextMarkup(TextMarkup.newId(), type, color.withAlpha(255), intensity, quads, marked, null, now, now)
        }
        return out
    }
}
