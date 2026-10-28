package com.xnotes.format

import com.xnotes.core.model.Rgba
import com.xnotes.core.text.HighlightTheme

/**
 * Reads a Helix editor theme (the TOML files every popular scheme ships: Tokyo
 * Night, GitHub, Gruvbox, ...) into a [HighlightTheme]. Helix speaks tree-sitter
 * capture names, so its scope keys map almost one-to-one onto ours; the code
 * block background comes from `ui.background`. The reader is a hand-rolled TOML
 * subset (quoted/bare keys, string values, inline tables, a `[palette]` section
 * of named colours) and is forgiving: unknown keys, named ANSI colours and
 * modifiers are skipped. Themes using `inherits` may resolve too few colours;
 * [parse] then fails so the user can import the self-contained base theme.
 */
object HelixTheme {

    /** Our theme slots and the Helix scopes that feed them, in priority order. */
    private val SLOTS: Map<String, List<String>> = mapOf(
        "keyword" to listOf("keyword"),
        "function" to listOf("function"),
        "type" to listOf("type"),
        "string" to listOf("string"),
        "escape" to listOf("constant.character.escape", "escape", "string.special"),
        "number" to listOf("constant.numeric", "number"),
        "constant" to listOf("constant"),
        "comment" to listOf("comment"),
        "operator" to listOf("operator"),
        "property" to listOf("variable.other.member", "property"),
        "variable" to listOf("variable"),
        "punctuation" to listOf("punctuation"),
        "tag" to listOf("tag"),
        "diff.plus" to listOf("diff.plus"),
        "diff.minus" to listOf("diff.minus"),
        "diff.delta" to listOf("diff.delta"),
    )

    /** How many slots must resolve before the file counts as a usable theme. */
    private const val MIN_SLOTS = 4

    /** Parse [text]; null when it isn't a usable Helix theme. */
    fun parse(text: String): HighlightTheme? {
        val palette = HashMap<String, String>()
        val fg = HashMap<String, String>()
        val bg = HashMap<String, String>()
        var section = ""

        for (raw in text.lines()) {
            val line = stripComment(raw).trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                section = line.trim('[', ']').trim().trim('"')
                continue
            }
            val eq = indexOfTopLevel(line, '=') ?: continue
            val key = line.substring(0, eq).trim().trim('"')
            val value = line.substring(eq + 1).trim()
            if (key.isEmpty() || value.isEmpty()) continue
            if (section == "palette") {
                unquote(value)?.let { palette[key] = it }
            } else if (section.isEmpty()) {
                when {
                    value.startsWith("{") -> {
                        tableEntry(value, "fg")?.let { fg[key] = it }
                        tableEntry(value, "bg")?.let { bg[key] = it }
                    }
                    else -> unquote(value)?.let { fg[key] = it }
                }
            }
        }

        fun resolve(raw: String?): Rgba? = hex(raw?.let { palette[it] ?: it })
        fun lookup(map: Map<String, String>, scope: String): Rgba? =
            resolve(map[scope])
                ?: map.keys.filter { it.startsWith("$scope.") }.sorted().firstNotNullOfOrNull { resolve(map[it]) }

        val colors = HashMap<String, Rgba>()
        for ((slot, candidates) in SLOTS) {
            val color = candidates.firstNotNullOfOrNull { lookup(fg, it) } ?: continue
            colors[slot] = color
        }
        if (colors.size < MIN_SLOTS) return null
        return HighlightTheme(colors, background = lookup(bg, "ui.background"))
    }

    /** Cut a trailing comment, respecting '#' inside quoted strings (hex colours). */
    private fun stripComment(line: String): String {
        var inString = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inString = !inString
                '#' -> if (!inString) return line.substring(0, i)
            }
        }
        return line
    }

    /** The first [ch] outside quotes, or null. */
    private fun indexOfTopLevel(line: String, ch: Char): Int? {
        var inString = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inString = !inString
                ch -> if (!inString) return i
            }
        }
        return null
    }

    private fun unquote(value: String): String? {
        val v = value.trim()
        return if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v.substring(1, v.length - 1) else null
    }

    /** `key = "value"` inside an inline table `{ ... }`. */
    private fun tableEntry(table: String, key: String): String? =
        Regex("""(^|[{,\s])$key\s*=\s*"([^"]*)"""").find(table)?.groupValues?.get(2)

    private fun hex(s: String?): Rgba? {
        if (s == null || !s.startsWith("#")) return null
        val body = s.substring(1)
        return when (body.length) {
            6 -> body.toIntOrNull(16)?.let { Rgba((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF, 255) }
            3 -> body.toIntOrNull(16)?.let {
                val r = (it shr 8) and 0xF
                val g = (it shr 4) and 0xF
                val b = it and 0xF
                Rgba(r * 17, g * 17, b * 17, 255)
            }
            else -> null
        }
    }
}
