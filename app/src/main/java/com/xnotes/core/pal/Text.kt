package com.xnotes.core.pal

/**
 * A monospace font request. The concrete family is resolved by the host from a
 * preference list (JetBrains Mono, DejaVu Sans Mono, Liberation Mono, generic
 * monospace — spec 01 §12); the core only chooses the point size and weight.
 */
data class FontSpec(val pointSize: Double, val bold: Boolean = false)

/** Text layout flags (spec 01 §1 `draw_text`). Text boxes use the defaults. */
data class TextFlags(
    val wordWrap: Boolean = true,
    val alignLeft: Boolean = true,
    val alignTop: Boolean = true,
)
