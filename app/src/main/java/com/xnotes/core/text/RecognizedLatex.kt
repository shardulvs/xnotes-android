package com.xnotes.core.text

/**
 * Tidies what the handwritten-maths model returns. It emits one token per space
 * ("x ^ { 2 }"), spells operator names letter by letter ("\operatorname* { l i m }")
 * and wraps runs in font commands learned from printed formulas, which a
 * handwritten formula never meant.
 */
object RecognizedLatex {

    private val FONT_WRAPPERS = listOf("\\mathrm", "\\mathbf", "\\mathit", "\\textsc", "\\boldsymbol", "\\textrm", "\\textbf")

    private val OPERATORS = setOf(
        "lim", "sin", "cos", "tan", "cot", "sec", "csc", "ln", "log", "exp", "max", "min",
        "sup", "inf", "det", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
    )

    fun clean(raw: String): String {
        var s = join(raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "~" && it != "\\," && it != "\\;" && it != "\\quad" })
        s = Regex("\\\\operatorname\\*?\\{([A-Za-z]+)\\}").replace(s) { m ->
            val name = m.groupValues[1]
            if (name in OPERATORS) "\\$name " else "\\operatorname{$name} "
        }
        for (w in FONT_WRAPPERS) s = unwrap(s, w)
        s = s.replace(Regex("\\\\(bf|rm)(?![A-Za-z])"), " ")
        return join(tokens(s))
    }

    /** Glue tokens back, keeping a space only where a command name would run into letters. */
    private fun join(toks: List<String>): String {
        val sb = StringBuilder()
        for (t in toks) {
            if (sb.isNotEmpty() && t[0].isLetter() && Regex("\\\\[A-Za-z]+$").containsMatchIn(sb)) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    /** Split into command names, single characters and backslash-escaped symbols. */
    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == ' ' -> i++
                c == '\\' && i + 1 < s.length && s[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < s.length && s[j].isLetter()) j++
                    out.add(s.substring(i, j))
                    i = j
                }
                c == '\\' && i + 1 < s.length -> { out.add(s.substring(i, i + 2)); i += 2 }
                else -> { out.add(c.toString()); i++ }
            }
        }
        return out
    }

    /** Replace every `cmd{body}` with its body. */
    private fun unwrap(s: String, cmd: String): String {
        var out = s
        while (true) {
            val at = Regex(Regex.escape(cmd) + "(?![A-Za-z])").find(out)?.range?.first ?: return out
            var open = at + cmd.length
            while (open < out.length && out[open] == ' ') open++
            if (open >= out.length || out[open] != '{') {
                out = out.removeRange(at, at + cmd.length)
                continue
            }
            var depth = 0
            var close = -1
            for (k in open until out.length) {
                if (out[k] == '{' && (k == 0 || out[k - 1] != '\\')) depth++
                if (out[k] == '}' && (k == 0 || out[k - 1] != '\\') && --depth == 0) { close = k; break }
            }
            if (close < 0) return out
            out = out.substring(0, at) + " " + out.substring(open + 1, close) + " " + out.substring(close + 1)
        }
    }
}
