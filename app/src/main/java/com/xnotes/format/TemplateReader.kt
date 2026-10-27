package com.xnotes.format

import com.xnotes.core.geometry.Rect
import com.xnotes.core.template.EllipseKind
import com.xnotes.core.template.Expr
import com.xnotes.core.template.Fit
import com.xnotes.core.template.ImageFit
import com.xnotes.core.template.ParamType
import com.xnotes.core.template.RepeatAxis
import com.xnotes.core.template.TAlign
import com.xnotes.core.template.TBaseline
import com.xnotes.core.template.TColor
import com.xnotes.core.template.TFont
import com.xnotes.core.template.TNode
import com.xnotes.core.template.TStyle
import com.xnotes.core.template.Template
import com.xnotes.core.template.TemplateKind
import com.xnotes.core.template.TemplateParam
import com.xnotes.core.template.TextPart
import java.io.StringReader

/** The file is not an xtemplate at all (as opposed to one with parts this reader skips). */
class TemplateFormatException(message: String) : Exception(message)

/**
 * Reads `.xtemplate` JSON (the xtemplate repo's SPEC.md) into a [Template]. Forgiving like the
 * document codecs: unknown properties are ignored, unknown node types and nodes with unusable
 * values are dropped, and a missing optional value takes its default. Only a file that is not a
 * template at all (not JSON, no `xtemplate` version, no `items`) or that breaks the size limits
 * is refused.
 */
object TemplateReader {

    const val MAX_BYTES = 1 shl 20
    private const val MAX_DEPTH = 32
    private const val MAX_NODES = 10_000
    private const val MAX_PATH = 100_000

    fun read(text: String): Template {
        if (text.length > MAX_BYTES) throw TemplateFormatException("template is larger than 1 MiB")
        val root = try {
            val p = JsonPull(StringReader(text))
            val v = value(p, 0)
            if (p.peek() != JsonPull.Token.END_DOCUMENT) throw TemplateFormatException("trailing data after the template")
            v
        } catch (e: JsonPullException) {
            throw TemplateFormatException(e.message ?: "not valid JSON")
        }
        val o = root as? Map<*, *> ?: throw TemplateFormatException("not a JSON object")
        if (o["xtemplate"] !is Double) throw TemplateFormatException("missing the xtemplate version")
        val items = o["items"] as? List<*> ?: throw TemplateFormatException("missing items")
        return Builder(o).build(items)
    }

    // --- JSON to a plain tree: Map, List, String, Double, Boolean, null ---

    private fun value(p: JsonPull, depth: Int): Any? {
        if (depth > 4 * MAX_DEPTH) throw TemplateFormatException("template nested too deeply")
        return when (p.peek()) {
            JsonPull.Token.BEGIN_OBJECT -> {
                val m = LinkedHashMap<String, Any?>()
                p.beginObject()
                while (p.hasNext()) {
                    val k = p.nextName()
                    m[k] = value(p, depth + 1)
                }
                p.endObject()
                m
            }
            JsonPull.Token.BEGIN_ARRAY -> {
                val l = ArrayList<Any?>()
                p.beginArray()
                while (p.hasNext()) l.add(value(p, depth + 1))
                p.endArray()
                l
            }
            JsonPull.Token.STRING -> p.nextString()
            JsonPull.Token.NUMBER -> p.nextDouble()
            JsonPull.Token.BOOLEAN -> p.nextBoolean()
            JsonPull.Token.NULL -> { p.nextNull(); null }
            else -> throw TemplateFormatException("unexpected JSON")
        }
    }

    /** A node whose values cannot be used; dropped from the tree. */
    private class Unusable : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Builder(val top: Map<*, *>) {
        private var nodes = 0
        private val colorParams = HashSet<String>()

        fun build(items: List<*>): Template {
            val params = params(top["params"] as? Map<*, *>)
            colorParams += params.filter { it.type == ParamType.COLOR }.map { it.name }
            val root = TNode.Group(
                style(top), Expr.ZERO, Expr.ZERO, Expr.FULL, Expr.FULL, null, false, children(items, 1),
            )
            return Template(
                name = (top["name"] as? String)?.takeIf { it.isNotBlank() } ?: "Untitled",
                id = top["id"] as? String,
                description = top["description"] as? String,
                author = top["author"] as? String,
                license = top["license"] as? String,
                version = top["version"] as? String,
                tags = (top["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                params = params,
                root = root,
                kind = if (top["kind"] == "cover") TemplateKind.COVER else TemplateKind.PAGE,
            )
        }

        private fun params(m: Map<*, *>?): List<TemplateParam> {
            if (m == null) return emptyList()
            val out = ArrayList<TemplateParam>()
            var spacingTaken = false
            for ((k, v) in m) {
                val name = k as? String ?: continue
                val d = v as? Map<*, *> ?: continue
                if (!NAME.matches(name) || name in Expr.BUILTIN_NAMES || name in Expr.FUNCTIONS) continue
                val type = when (d["type"]) {
                    "length" -> ParamType.LENGTH
                    "number" -> ParamType.NUMBER
                    "integer" -> ParamType.INTEGER
                    "color" -> ParamType.COLOR
                    else -> continue
                }
                var role = d["role"] as? String
                if (role == TemplateParam.ROLE_SPACING && (type != ParamType.LENGTH || spacingTaken)) role = null
                if (role == TemplateParam.ROLE_SPACING) spacingTaken = true
                if (type == ParamType.COLOR) {
                    val c = SvgColors.parse(d["default"] as? String) ?: continue
                    out += TemplateParam(name, type, 0.0, c, null, null, null, d["label"] as? String, role)
                    continue
                }
                fun lit(x: Any?): Double? = when (x) {
                    is Double -> x
                    is String -> if (type == ParamType.LENGTH) Expr.literalLength(x) else x.trim().toDoubleOrNull()
                    else -> null
                }
                val def = lit(d["default"]) ?: continue
                out += TemplateParam(
                    name, type, def, null, lit(d["min"]), lit(d["max"]), lit(d["step"]), d["label"] as? String, role,
                )
            }
            return out
        }

        private fun children(v: Any?, depth: Int): List<TNode> {
            val list = v as? List<*> ?: return emptyList()
            if (depth > MAX_DEPTH) return emptyList()
            val out = ArrayList<TNode>(list.size)
            for (item in list) {
                if (nodes >= MAX_NODES) break
                val m = item as? Map<*, *> ?: continue
                val n = try { node(m, depth) } catch (_: Unusable) { null } ?: continue
                nodes++
                out += n
            }
            return out
        }

        private fun node(m: Map<*, *>, depth: Int): TNode? {
            val s = style(m)
            return when (m["type"]) {
                "group" -> TNode.Group(
                    s, len(m, "x", Expr.ZERO), len(m, "y", Expr.ZERO), len(m, "w", Expr.FULL), len(m, "h", Expr.FULL),
                    inset(m["inset"]), m["clip"] == true, children(m["children"], depth + 1),
                )
                "repeat" -> repeat(m, s, depth)
                "line" -> TNode.Line(s, len(m, "x1", Expr.ZERO), len(m, "y1", Expr.ZERO), len(m, "x2", Expr.ZERO), len(m, "y2", Expr.ZERO))
                "hline" -> {
                    val y = len(m, "y", Expr.ZERO)
                    TNode.Line(s, len(m, "x1", Expr.ZERO), y, len(m, "x2", Expr.FULL), y)
                }
                "vline" -> {
                    val x = len(m, "x", Expr.ZERO)
                    TNode.Line(s, x, len(m, "y1", Expr.ZERO), x, len(m, "y2", Expr.FULL))
                }
                "hatch" -> TNode.Hatch(
                    s, len(m, "angle", Expr.ZERO), required(m, "period"), len(m, "offset", Expr.ZERO), m["extend"] == true,
                )
                "rect" -> TNode.Rectangle(
                    s, len(m, "x", Expr.ZERO), len(m, "y", Expr.ZERO), len(m, "w", Expr.FULL), len(m, "h", Expr.FULL), len(m, "rx", Expr.ZERO),
                )
                "circle" -> {
                    val r = required(m, "r")
                    TNode.Ellipse(s, EllipseKind.CIRCLE, len(m, "cx", Expr.ZERO), len(m, "cy", Expr.ZERO), r, r)
                }
                "ellipse" -> TNode.Ellipse(
                    s, EllipseKind.ELLIPSE, len(m, "cx", Expr.ZERO), len(m, "cy", Expr.ZERO), required(m, "rx"), required(m, "ry"),
                )
                "dot" -> {
                    val r = len(m, "r", DOT_RADIUS)
                    TNode.Ellipse(s, EllipseKind.DOT, len(m, "cx", Expr.ZERO), len(m, "cy", Expr.ZERO), r, r)
                }
                "polyline", "polygon" -> TNode.Poly(s, points(m["points"]), m["type"] == "polygon")
                "path" -> path(m, s)
                "text" -> text(m, s)
                "image" -> image(m, s)
                else -> null
            }
        }

        private fun image(m: Map<*, *>, s: TStyle): TNode.Image {
            val src = (m["src"] as? String)?.takeIf { TemplatePackage.validSrc(it) } ?: throw Unusable()
            val fit = when (m["fit"]) {
                "cover" -> ImageFit.COVER
                "stretch" -> ImageFit.STRETCH
                else -> ImageFit.CONTAIN
            }
            val (px, py) = when (m["position"]) {
                "top" -> 0.5 to 0.0
                "bottom" -> 0.5 to 1.0
                "left" -> 0.0 to 0.5
                "right" -> 1.0 to 0.5
                "top left" -> 0.0 to 0.0
                "top right" -> 1.0 to 0.0
                "bottom left" -> 0.0 to 1.0
                "bottom right" -> 1.0 to 1.0
                else -> 0.5 to 0.5
            }
            return TNode.Image(s, src, len(m, "x", Expr.ZERO), len(m, "y", Expr.ZERO), len(m, "w", Expr.FULL), len(m, "h", Expr.FULL), fit, px, py)
        }

        private fun repeat(m: Map<*, *>, s: TStyle, depth: Int): TNode.Repeat {
            val axis = when (m["axis"]) {
                "x" -> RepeatAxis.X
                "xy" -> RepeatAxis.XY
                "none" -> RepeatAxis.NONE
                else -> RepeatAxis.Y
            }
            val (px, py) = pair(m["period"])
            val (cx, cy) = pair(m["count"])
            val fits = m["fit"]
            val (fx, fy) = if (fits is List<*> && fits.size == 2) fit(fits[0]) to fit(fits[1]) else fit(fits) to fit(fits)
            val index = m["index"]
            val (ix, iy) = when {
                index is String -> index to null
                index is List<*> && index.size == 2 -> (index[0] as? String) to (index[1] as? String)
                else -> null to null
            }
            fun ok(n: String?) = n?.takeIf { NAME.matches(it) && it !in Expr.BUILTIN_NAMES && it !in Expr.FUNCTIONS }
            // A one-axis repeat reads a single period or count for its own axis.
            val single = axis != RepeatAxis.XY
            return TNode.Repeat(
                s, axis,
                periodX = px, periodY = if (single) px else py,
                countX = cx, countY = if (single) cx else cy,
                fitX = fx, fitY = if (single) fx else fy,
                stagger = opt(m["stagger"]), extend = m["extend"] == true,
                indexX = ok(ix), indexY = ok(iy),
                children = children(m["children"], depth + 1),
            )
        }

        private fun path(m: Map<*, *>, s: TStyle): TNode.Path {
            val d = m["d"] as? String ?: throw Unusable()
            if (d.length > MAX_PATH) throw Unusable()
            val contours = runCatching { SvgPathData.parse(d) }.getOrNull() ?: throw Unusable()
            val vb = when (val v = m["viewBox"]) {
                is String -> v.trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
                is List<*> -> v.map { it as? Double ?: throw Unusable() }
                else -> null
            }?.let { if (it.size == 4) Rect(it[0], it[1], it[2], it[3]) else throw Unusable() }
            return TNode.Path(
                s, contours, vb, len(m, "x", Expr.ZERO), len(m, "y", Expr.ZERO), len(m, "w", Expr.FULL), len(m, "h", Expr.FULL),
                m["aspect"] == "stretch",
            )
        }

        private fun text(m: Map<*, *>, s: TStyle): TNode.Text {
            val texts = when (val t = m["text"]) {
                is String -> listOf(textParts(t))
                is List<*> -> t.map { textParts(it as? String ?: throw Unusable()) }
                else -> throw Unusable()
            }
            if (texts.isEmpty()) throw Unusable()
            return TNode.Text(s, texts, len(m, "x", Expr.ZERO), len(m, "y", Expr.ZERO), len(m, "select", I))
        }

        /** `{expr}` and `{expr:0N}` interpolations with `{{`/`}}` escapes (SPEC §5.3). */
        private fun textParts(t: String): List<TextPart> {
            val out = ArrayList<TextPart>()
            val lit = StringBuilder()
            var k = 0
            while (k < t.length) {
                val c = t[k]
                when {
                    c == '{' && t.getOrNull(k + 1) == '{' -> { lit.append('{'); k += 2 }
                    c == '}' && t.getOrNull(k + 1) == '}' -> { lit.append('}'); k += 2 }
                    c == '{' -> {
                        val end = t.indexOf('}', k + 1)
                        if (end < 0) throw Unusable()
                        if (lit.isNotEmpty()) { out += TextPart.Literal(lit.toString()); lit.clear() }
                        val body = t.substring(k + 1, end)
                        val colon = body.lastIndexOf(':')
                        val pad = if (colon >= 0) PAD.matchEntire(body.substring(colon + 1).trim())?.groupValues?.get(1)?.toInt() else null
                        if (colon >= 0 && pad == null) throw Unusable()
                        val expr = Expr.parse(if (colon >= 0) body.substring(0, colon) else body)
                        if (expr is Expr.Invalid) throw Unusable()
                        out += TextPart.Value(expr, pad)
                        k = end + 1
                    }
                    else -> { lit.append(c); k++ }
                }
            }
            if (lit.isNotEmpty()) out += TextPart.Literal(lit.toString())
            return out
        }

        private fun style(m: Map<*, *>): TStyle {
            val dash = when (val d = m["dash"]) {
                null -> null
                "none" -> emptyList()
                is List<*> -> if (d.size == 2) d.map { expr(it) ?: throw Unusable() } else emptyList()
                else -> emptyList()
            }
            val s = TStyle(
                stroke = (m["stroke"] as? String)?.let(::color),
                strokeWidth = opt(m["strokeWidth"]),
                dash = dash,
                fill = (m["fill"] as? String)?.let(::color),
                opacity = opt(m["opacity"]),
                font = when (m["font"]) {
                    null -> null
                    "serif" -> TFont.SERIF
                    "mono" -> TFont.MONO
                    else -> TFont.SANS
                },
                size = opt(m["size"]),
                bold = when (m["weight"]) {
                    null -> null
                    "bold" -> true
                    else -> false
                },
                italic = m["italic"] as? Boolean,
                align = when (m["align"]) {
                    null -> null
                    "middle" -> TAlign.MIDDLE
                    "end" -> TAlign.END
                    else -> TAlign.START
                },
                baseline = when (m["baseline"]) {
                    null -> null
                    "middle" -> TBaseline.MIDDLE
                    "top" -> TBaseline.TOP
                    else -> TBaseline.ALPHABETIC
                },
                rotate = opt(m["rotate"]),
            )
            return if (s.isEmpty) TStyle.EMPTY else s
        }

        private fun color(v: String): TColor {
            val t = v.trim()
            return when (t.lowercase()) {
                "ink" -> TColor.Ink
                "accent" -> TColor.Accent
                "none" -> TColor.None
                else -> when {
                    t in colorParams -> TColor.Param(t)
                    else -> SvgColors.parse(t)?.let { TColor.Literal(it) } ?: TColor.Ink
                }
            }
        }

        private fun fit(v: Any?) = when (v) {
            "space" -> Fit.SPACE
            "round" -> Fit.ROUND
            "center" -> Fit.CENTER
            else -> Fit.REPEAT
        }

        private fun inset(v: Any?): List<Expr>? {
            val l = when (v) {
                null -> return null
                is List<*> -> v.map { expr(it) ?: throw Unusable() }
                else -> listOf(expr(v) ?: throw Unusable())
            }
            return when (l.size) {
                1 -> listOf(l[0], l[0], l[0], l[0])
                2 -> listOf(l[0], l[1], l[0], l[1])
                3 -> listOf(l[0], l[1], l[2], l[1])
                4 -> l
                else -> throw Unusable()
            }
        }

        private fun pair(v: Any?): Pair<Expr?, Expr?> = when (v) {
            null -> null to null
            is List<*> -> if (v.size == 2) (expr(v[0]) ?: throw Unusable()) to (expr(v[1]) ?: throw Unusable()) else throw Unusable()
            else -> (expr(v) ?: throw Unusable()).let { it to it }
        }

        private fun points(v: Any?): List<Expr> {
            val out = ArrayList<Expr>()
            when (v) {
                is String -> v.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.forEach {
                    out += Expr.Num(it.toDoubleOrNull() ?: throw Unusable())
                }
                is List<*> -> v.forEach { pt ->
                    val xy = pt as? List<*> ?: throw Unusable()
                    if (xy.size != 2) throw Unusable()
                    out += expr(xy[0]) ?: throw Unusable()
                    out += expr(xy[1]) ?: throw Unusable()
                }
                else -> throw Unusable()
            }
            if (out.size < 4 || out.size % 2 != 0) throw Unusable()
            return out
        }

        private fun len(m: Map<*, *>, key: String, default: Expr): Expr =
            if (m.containsKey(key)) expr(m[key]) ?: throw Unusable() else default

        private fun required(m: Map<*, *>, key: String): Expr = expr(m[key]) ?: throw Unusable()

        private fun opt(v: Any?): Expr? = if (v == null) null else expr(v) ?: throw Unusable()

        /** A number or an expression string; null for anything else. Unparseable text is unusable. */
        private fun expr(v: Any?): Expr? = when (v) {
            is Double -> Expr.Num(v)
            is String -> Expr.parse(v).also { if (it is Expr.Invalid) throw Unusable() }
            else -> null
        }
    }

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val PAD = Regex("0(\\d+)")
    private val I = Expr.Name("i")
    private val DOT_RADIUS = Expr.Num(0.35)
}
