package com.xnotes.core.text

import com.xnotes.core.model.Rgba

/** One capture over a code block's text, char offsets, capture name from the .scm query. */
class HighlightSpan(val start: Int, val end: Int, val capture: String)

/** A resolved colour span the layout paints code segments with (derived, never persisted). */
class CodeSpan(val start: Int, val end: Int, val color: Rgba)

/**
 * The platform seam for syntax highlighting: the core asks for capture spans and
 * stays JVM-pure; the Android layer answers via the vendored tree-sitter.
 */
interface CodeHighlighter {
    fun supports(language: String): Boolean

    /** Capture spans over [text] for [language], or null when it can't highlight. */
    fun highlight(text: String, language: String): List<HighlightSpan>?
}

/**
 * Maps .scm capture names to colours by longest dotted prefix ("keyword.return"
 * falls back to "keyword"), and Neovim-style roots ("conditional", "boolean") to
 * the slot they belong to. Two presets keyed to the app's dark/light paper
 * (github-dark and github-light); the dark preset and user-imported themes also
 * carry a code-block [background].
 */
class HighlightTheme(private val colors: Map<String, Rgba>, val background: Rgba? = null) {

    fun colorFor(capture: String): Rgba? {
        var name = capture
        while (true) {
            colors[name]?.let { return it }
            val dot = name.lastIndexOf('.')
            if (dot < 0) return NEOVIM_ROOTS[name]?.let { colors[it] }
            name = name.substring(0, dot)
        }
    }

    companion object {
        /** Roots that Neovim-style queries use instead of ours, onto the slot they belong to. */
        private val NEOVIM_ROOTS = mapOf(
            "conditional" to "keyword",
            "repeat" to "keyword",
            "include" to "keyword",
            "exception" to "keyword",
            "boolean" to "constant",
            "float" to "number",
            "character" to "string",
            "parameter" to "variable",
            "field" to "property",
            "method" to "function",
        )

        // github-dark (Primer palette), resolved from its Helix theme.
        val DARK = HighlightTheme(
            mapOf(
                "keyword" to Rgba(255, 123, 114, 255),
                "function" to Rgba(210, 168, 255, 255),
                "type" to Rgba(255, 166, 87, 255),
                "string" to Rgba(165, 214, 255, 255),
                "escape" to Rgba(121, 192, 255, 255),
                "number" to Rgba(121, 192, 255, 255),
                "constant" to Rgba(121, 192, 255, 255),
                "comment" to Rgba(139, 148, 158, 255),
                "operator" to Rgba(165, 214, 255, 255),
                "property" to Rgba(165, 214, 255, 255),
                "variable" to Rgba(201, 209, 217, 255),
                "punctuation" to Rgba(201, 209, 217, 255),
                "tag" to Rgba(126, 231, 135, 255),
                "diff.plus" to Rgba(63, 185, 80, 255),
                "diff.minus" to Rgba(248, 81, 73, 255),
                "diff.delta" to Rgba(210, 153, 34, 255),
            ),
            background = Rgba(13, 17, 23, 255),
        )

        // github-light (Primer palette), resolved from its Helix theme.
        val LIGHT = HighlightTheme(
            mapOf(
                "keyword" to Rgba(207, 34, 46, 255),
                "function" to Rgba(130, 80, 223, 255),
                "type" to Rgba(149, 56, 0, 255),
                "string" to Rgba(10, 48, 105, 255),
                "escape" to Rgba(5, 80, 174, 255),
                "number" to Rgba(5, 80, 174, 255),
                "constant" to Rgba(5, 80, 174, 255),
                "comment" to Rgba(87, 96, 106, 255),
                "operator" to Rgba(10, 48, 105, 255),
                "property" to Rgba(10, 48, 105, 255),
                "variable" to Rgba(36, 41, 47, 255),
                "punctuation" to Rgba(36, 41, 47, 255),
                "tag" to Rgba(17, 99, 41, 255),
                "diff.plus" to Rgba(26, 127, 55, 255),
                "diff.minus" to Rgba(207, 34, 46, 255),
                "diff.delta" to Rgba(154, 103, 0, 255),
            ),
        )
    }
}
