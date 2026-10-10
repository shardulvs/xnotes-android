package com.xnotes.core.text

/**
 * Turns maths typed as plain text into LaTeX: "lim x->0 sin(x)/x" becomes
 * "\lim_{x \to 0} \frac{\sin(x)}{x}", "int_0^1 x^2 dx" an integral with its limits,
 * "d/dx x^2" and "dy/dx" fractions, and "x^(1/2)" a braced exponent. Text that already
 * holds a LaTeX command (a backslash) is taken to be LaTeX and handed back as it is.
 */
object PlainMath {

    fun toLatex(input: String): String {
        val text = input.trim()
        if (text.isEmpty() || '\\' in text) return text
        return Converter(tokenize(text)).run()
    }

    private enum class T { NUM, WORD, OP, OPEN, CLOSE }

    private data class Tok(val type: T, val text: String, val spaceBefore: Boolean)

    private val MULTI_OPS = listOf("<=>", "...", "->", "=>", "<=", ">=", "!=", "+-", "-+")

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        var space = false
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> { space = true; i++; continue }
                c.isDigit() || (c == '.' && s.getOrNull(i + 1)?.isDigit() == true) -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' && s.getOrNull(i + 1)?.isDigit() == true)) i++
                    out.add(Tok(T.NUM, s.substring(start, i), space))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && s[i].isLetter()) i++
                    splitWord(s.substring(start, i)).forEachIndexed { k, w -> out.add(Tok(T.WORD, w, space && k == 0)) }
                }
                c == '(' || c == '[' || c == '{' -> { out.add(Tok(T.OPEN, c.toString(), space)); i++ }
                c == ')' || c == ']' || c == '}' -> { out.add(Tok(T.CLOSE, c.toString(), space)); i++ }
                else -> {
                    val op = MULTI_OPS.firstOrNull { s.startsWith(it, i) } ?: c.toString()
                    out.add(Tok(T.OP, op, space))
                    i += op.length
                }
            }
            space = false
        }
        return out
    }

    /** "sinx" is sin x and "lnx" is ln x: a known name glued to what follows is split off it. */
    private fun splitWord(w: String): List<String> {
        if (w in SYMBOLS || w in FUNCTIONS || w in SPECIAL) return listOf(w)
        val name = (FUNCTIONS + SPECIAL).filter { w.startsWith(it) && w.length > it.length }.maxByOrNull { it.length }
            ?: return listOf(w)
        return listOf(name) + splitWord(w.substring(name.length))
    }

    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "ln", "log", "exp", "det", "max", "min",
    )

    private val SPECIAL = setOf("lim", "int", "iint", "iiint", "oint", "integral", "sum", "prod", "sqrt")

    private val SYMBOLS = mapOf(
        "alpha" to "\\alpha", "beta" to "\\beta", "gamma" to "\\gamma", "delta" to "\\delta",
        "epsilon" to "\\varepsilon", "zeta" to "\\zeta", "eta" to "\\eta", "theta" to "\\theta",
        "lambda" to "\\lambda", "mu" to "\\mu", "nu" to "\\nu", "xi" to "\\xi", "pi" to "\\pi",
        "rho" to "\\rho", "sigma" to "\\sigma", "tau" to "\\tau", "phi" to "\\varphi",
        "chi" to "\\chi", "psi" to "\\psi", "omega" to "\\omega",
        "Gamma" to "\\Gamma", "Delta" to "\\Delta", "Theta" to "\\Theta", "Lambda" to "\\Lambda",
        "Sigma" to "\\Sigma", "Phi" to "\\Phi", "Omega" to "\\Omega",
        "inf" to "\\infty", "infinity" to "\\infty", "oo" to "\\infty",
        "partial" to "\\partial", "nabla" to "\\nabla",
    )

    private val OPS = mapOf(
        "->" to "\\to", "=>" to "\\Rightarrow", "<=>" to "\\Leftrightarrow", "<=" to "\\le",
        ">=" to "\\ge", "!=" to "\\neq", "+-" to "\\pm", "-+" to "\\mp", "*" to "\\cdot",
        "..." to "\\dots",
    )

    private val BIG_OPS = mapOf(
        "int" to "\\int", "integral" to "\\int", "iint" to "\\iint", "iiint" to "\\iiint",
        "oint" to "\\oint", "sum" to "\\sum", "prod" to "\\prod",
    )

    private class Converter(val toks: List<Tok>) {
        var i = 0
        var integrals = 0

        fun run(): String = expr(stopAtClose = false)

        private fun peek(k: Int = 0): Tok? = toks.getOrNull(i + k)

        private fun isOp(t: Tok?, vararg ops: String) = t != null && t.type == T.OP && t.text in ops

        /** Items up to the end, or up to an unmatched closing bracket when [stopAtClose]. */
        private fun expr(stopAtClose: Boolean, stop: (Tok) -> Boolean = { false }): String {
            val out = StringBuilder()
            while (true) {
                val t = peek() ?: break
                if (t.type == T.CLOSE && stopAtClose) break
                if (stop(t)) break
                val piece = item()
                if (t.spaceBefore && out.isNotEmpty()) out.append(' ')
                append(out, piece)
            }
            return out.toString()
        }

        /** A tight run of terms, made a fraction when a slash joins it to the next run. */
        private fun item(): String {
            val t = peek()!!
            if (t.type == T.OP && !isOp(t, "-") || t.type == T.CLOSE) {
                i++
                return OPS[t.text] ?: t.text
            }
            val num = tight()
            if (isOp(peek(), "/") && peek(1)?.let { it.type != T.OP && it.type != T.CLOSE } == true) {
                i++
                val den = tight()
                return "\\frac{${strip(num)}}{${strip(den)}}"
            }
            return num
        }

        /** Terms glued together with no space or operator between them, e.g. "2x", "d^2y", "sin(x)". */
        private fun tight(): String {
            val out = StringBuilder()
            append(out, term())
            while (true) {
                val t = peek() ?: break
                // "partial f" reads as one piece, so "partial f/partial x" is one fraction.
                if (t.spaceBefore && out.toString() != "\\partial" || t.type == T.OP || t.type == T.CLOSE) break
                append(out, term())
            }
            return out.toString()
        }

        private fun term(): String {
            var base = primary()
            while (true) {
                val t = peek() ?: break
                when {
                    isOp(t, "^") && !t.spaceBefore -> { i++; base += "^{${script()}}" }
                    isOp(t, "_") && !t.spaceBefore -> { i++; base += "_{${script()}}" }
                    isOp(t, "'", "!") && !t.spaceBefore -> { i++; base += t.text }
                    else -> break
                }
            }
            return base
        }

        /** An exponent or index: one term, optionally signed, with its brackets dropped. */
        private fun script(bound: Boolean = false): String {
            val sign = if (isOp(peek(), "-", "+")) peek()!!.text.also { i++ } else ""
            val t = peek() ?: return sign
            if (t.type == T.OPEN) return sign + strip(group())
            // An integral's lower bound stops before the "^" of its upper one.
            return sign + if (bound) primary() else term()
        }

        private fun primary(): String {
            val t = peek()!!
            if (t.type == T.OPEN) return group()
            i++
            return when (t.type) {
                T.NUM -> t.text
                T.OP -> OPS[t.text] ?: t.text
                else -> word(t.text)
            }
        }

        /** A bracketed group converted inside, kept with its brackets; braces group invisibly, as in LaTeX. */
        private fun group(): String {
            val open = peek()!!.text
            i++
            val inner = expr(stopAtClose = true)
            val close = peek()?.takeIf { it.type == T.CLOSE }?.text?.also { i++ } ?: ""
            return "$open$inner$close"
        }

        private fun word(w: String): String {
            SYMBOLS[w]?.let { return it }
            if (w in FUNCTIONS) return "\\$w"
            BIG_OPS[w]?.let { return bigOp(it) }
            if (w == "lim") return limit()
            if (w == "sqrt") return sqrt()
            // After an integral a lone dx, dt, ... is its differential, set off by a thin space.
            if (integrals > 0 && w.length == 2 && w[0] == 'd') {
                integrals--
                return "\\,$w"
            }
            // Anything else three letters or longer is a word, not a product of variables.
            return if (w.length >= 3) "\\text{ $w }" else w
        }

        private fun sqrt(): String {
            val t = peek() ?: return "\\sqrt{}"
            val body = if (t.type == T.OPEN) strip(group()) else term()
            return "\\sqrt{$body}"
        }

        /** "lim x->0", "lim_(x->0)", "lim(x->0)" and "lim_{x->0}" all give \lim_{x \to 0}. */
        private fun limit(): String {
            if (isOp(peek(), "_")) i++
            val t = peek() ?: return "\\lim"
            if (t.type == T.OPEN) {
                val save = i
                val sub = strip(group())
                if ("\\to" in sub) return "\\lim_{$sub}"
                i = save
                return "\\lim"
            }
            // Bare: the variable, the arrow and one target term, e.g. "x->0", "n -> inf", "x->0+".
            if (!toks.drop(i).take(3).any { isOp(it, "->") }) return "\\lim"
            val sb = StringBuilder()
            while (peek() != null && !isOp(peek(), "->")) append(sb, term())
            i++
            append(sb, "\\to")
            sb.append(' ')
            val sign = if (isOp(peek(), "-", "+")) peek()!!.text.also { i++ } else ""
            sb.append(sign).append(if (peek() != null) term() else "")
            if (isOp(peek(), "+", "-") && !peek()!!.spaceBefore && (peek(1) == null || peek(1)!!.spaceBefore)) {
                sb.append("^").append(peek()!!.text)
                i++
            }
            return "\\lim_{$sb}"
        }

        /** An integral, sum or product with its bounds: "_a^b", "_(i=1)^n" or "from a to b". */
        private fun bigOp(cmd: String): String {
            if (cmd.endsWith("int")) integrals++
            var lower: String? = null
            var upper: String? = null
            while (true) {
                val t = peek() ?: break
                when {
                    isOp(t, "_") && lower == null -> { i++; lower = script(bound = true) }
                    isOp(t, "^") && upper == null -> { i++; upper = script(bound = true) }
                    t.type == T.WORD && t.text == "from" && lower == null -> {
                        i++
                        lower = expr(stopAtClose = true) { it.type == T.WORD && it.text == "to" }
                        if (peek()?.text == "to") {
                            i++
                            upper = tight()
                        }
                    }
                    else -> break
                }
            }
            val sb = StringBuilder(cmd)
            lower?.let { sb.append("_{").append(it).append('}') }
            upper?.let { sb.append("^{").append(it).append('}') }
            return sb.toString()
        }

        /** Drop one pair of outer round brackets, which a fraction or an exponent makes redundant. */
        private fun strip(s: String): String =
            if (s.startsWith("(") && s.endsWith(")") && balanced(s.substring(1, s.length - 1))) s.substring(1, s.length - 1) else s

        private fun balanced(s: String): Boolean {
            var depth = 0
            for (c in s) {
                if (c == '(') depth++
                if (c == ')' && --depth < 0) return false
            }
            return depth == 0
        }

        /** Join, keeping a command name from running into the letters after it ("\sin x", not "\sinx"). */
        private fun append(sb: StringBuilder, piece: String) {
            if (piece.isEmpty()) return
            if (sb.isNotEmpty() && piece[0].isLetter() && Regex("\\\\[A-Za-z]+$").containsMatchIn(sb)) sb.append(' ')
            sb.append(piece)
        }
    }
}
