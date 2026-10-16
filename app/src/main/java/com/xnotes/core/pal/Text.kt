package com.xnotes.core.pal

/**
 * A font family reference. [id] is the stable serialized token: one of the four
 * generic tokens ([SANS]/[SERIF]/[MONO]/[HAND], resolved to system typefaces) or
 * a family name such as "Inter" naming a bundled or user-imported font. Hosts
 * resolve ids they do not know to the sans face, so a note referencing a missing
 * font still renders. [MONO] is the historical default (and what files without a
 * face stored fall back to), so older notes are unchanged.
 */
data class FontFace(val id: String) {
    companion object {
        val SANS = FontFace("sans")
        val SERIF = FontFace("serif")
        val MONO = FontFace("mono")
        val HAND = FontFace("hand")

        fun fromId(id: String?): FontFace = if (id.isNullOrEmpty()) MONO else FontFace(id)
    }
}

/**
 * A font request: a point size, an abstract [face] (resolved to a concrete family
 * by the host — spec 01 §12), and a weight/slant. Defaults to monospace so existing
 * call sites and stored notes render exactly as before.
 */
data class FontSpec(
    val pointSize: Double,
    val face: FontFace = FontFace.MONO,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/** Horizontal alignment of a wrapped text block. */
enum class HAlign { LEFT, CENTER, RIGHT }

/** Text layout flags (spec 01 §1 `draw_text`). Text boxes use the defaults. */
data class TextFlags(
    val wordWrap: Boolean = true,
    val alignLeft: Boolean = true,
    val alignTop: Boolean = true,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val hAlign: HAlign = HAlign.LEFT,
)
