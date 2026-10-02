package com.xnotes.core.search

import com.xnotes.core.model.Page
import com.xnotes.core.model.TextItem
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.TextFlow

/** A note's typed text to search: the flow's paragraphs as [frame] lays them out, then each page's text boxes. */
object TypedTexts {
    fun of(pages: List<Page>, flow: TextFlow, frame: FlowFrame?): List<TypedText> {
        val out = ArrayList<TypedText>()
        if (frame != null) {
            for ((i, para) in flow.paragraphs.withIndex()) {
                val text = para.plainText()
                if (text.isBlank()) continue
                out += TypedText(SearchTarget.Flow(i), text) { at -> frame.placedLineFor(FlowPos(i, at))?.first ?: -1 }
            }
        }
        for ((p, page) in pages.withIndex()) {
            for (item in page.items) {
                if (item is TextItem && item.text.isNotBlank()) out += TypedText(SearchTarget.Box(item), item.text) { p }
            }
        }
        return out
    }
}
