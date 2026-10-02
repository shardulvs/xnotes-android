package com.xnotes.core.model

import com.xnotes.core.pdf.TextQuad
import java.util.UUID

/** How a markup marks its text; [id] names it in files. */
enum class MarkupType(val id: String) {
    HIGHLIGHT("highlight"),
    UNDERLINE("underline"),
    STRIKEOUT("strikeout"),
    SQUIGGLY("squiggly");

    companion object {
        fun fromId(id: String?): MarkupType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A mark on the PDF text of a page: a highlight, or a line along the text. Immutable, so other
 * threads can read it while the page is edited; an edit puts a new markup in its place, and
 * markups compare by identity, like items.
 */
class TextMarkup(
    /** Kept for life: the name files give it. */
    val id: String,
    val type: MarkupType,
    /** Opaque: the ink colour when it was made. */
    val color: Rgba,
    /** How deep a highlight's colour goes, 0..1; the line types draw at full strength. */
    val intensity: Double,
    /** One box per line run, in points as displayed, like the [com.xnotes.core.pdf.PageText] it marks. */
    val quads: List<TextQuad>,
    /** The text marked, as copied when it was made. */
    val text: String,
    /** A plain-text note on it, or null. */
    val note: String?,
    /** Epoch ms. */
    val created: Long,
    val modified: Long,
) {
    /** The same markup restyled or noted at [modified]. */
    fun copy(
        type: MarkupType = this.type,
        color: Rgba = this.color,
        note: String? = this.note,
        modified: Long = this.modified,
    ): TextMarkup = TextMarkup(id, type, color, intensity, quads, text, note, created, modified)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
