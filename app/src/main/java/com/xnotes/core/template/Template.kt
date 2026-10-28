package com.xnotes.core.template

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.vector.VectorContour

/**
 * A parsed `.xtemplate`: a page ruling described against the page's size rather than drawn for one
 * (see the xtemplate repo's SPEC.md, whose section numbers the comments here cite). Immutable and
 * page-independent; [TemplateEval] lays it out for a given page. Expressions are parsed once, at
 * load, so evaluating a page never touches text.
 */
class Template(
    val name: String,
    val id: String?,
    val description: String?,
    val author: String?,
    val license: String?,
    val version: String?,
    val tags: List<String>,
    val params: List<TemplateParam>,
    val root: TNode.Group,
    /** Where hosts offer it (SPEC §1 `kind`); never changes how it draws. */
    val kind: TemplateKind = TemplateKind.PAGE,
) {
    /** The parameter the host's spacing control drives (SPEC §4 `role`), if any. */
    val spacingParam: TemplateParam? = params.firstOrNull { it.role == TemplateParam.ROLE_SPACING }

    fun param(name: String): TemplateParam? = params.firstOrNull { it.name == name }

    /** Whether anything draws in the host's `accent` colour, so a host shows that control. */
    val usesAccent: Boolean by lazy { uses(root) }

    /** Whether anything draws in the host's `ink`, every stroke's default, so a host shows the pattern colour. */
    val usesInk: Boolean by lazy { inks(root, TColor.Ink, null) }

    private fun uses(n: TNode): Boolean =
        n.style.stroke == TColor.Accent || n.style.fill == TColor.Accent || when (n) {
            is TNode.Group -> n.children.any(::uses)
            is TNode.Repeat -> n.children.any(::uses)
            else -> false
        }

    private fun inks(n: TNode, stroke: TColor, fill: TColor?): Boolean {
        val s = n.style.stroke ?: stroke
        val f = n.style.fill ?: fill
        // A dot and text are painted in their fill when it is set, and otherwise in their stroke.
        val painted = f?.takeIf { it != TColor.None } ?: s
        return when (n) {
            is TNode.Group -> n.children.any { inks(it, s, f) }
            is TNode.Repeat -> n.children.any { inks(it, s, f) }
            is TNode.Image -> false
            is TNode.Line, is TNode.Hatch -> s == TColor.Ink
            is TNode.Text -> painted == TColor.Ink
            is TNode.Ellipse -> if (n.kind == EllipseKind.DOT) painted == TColor.Ink else s == TColor.Ink || f == TColor.Ink
            else -> s == TColor.Ink || f == TColor.Ink
        }
    }
}

/** SPEC §1 `kind`: paper for every page, or a notebook's cover. An unknown value reads as [PAGE]. */
enum class TemplateKind { PAGE, COVER }

enum class ParamType { LENGTH, NUMBER, INTEGER, COLOR }

/** One user-adjustable value (SPEC §4). Lengths are millimetres. */
class TemplateParam(
    val name: String,
    val type: ParamType,
    val default: Double,
    val defaultColor: Rgba?,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val label: String?,
    val role: String?,
) {
    /** [v] clamped into range, and rounded for an integer parameter. */
    fun clamp(v: Double): Double {
        var x = v
        if (min != null && x < min) x = min
        if (max != null && x > max) x = max
        return if (type == ParamType.INTEGER) kotlin.math.round(x) else x
    }

    companion object {
        const val ROLE_SPACING = "spacing"
    }
}

/** A colour as written in a template, resolved per page against the host's colours (SPEC §6). */
sealed class TColor {
    data object Ink : TColor()
    data object Accent : TColor()
    data object None : TColor()
    class Literal(val rgba: Rgba) : TColor()
    class Param(val name: String) : TColor()
}

enum class TAlign { START, MIDDLE, END }
enum class TBaseline { ALPHABETIC, MIDDLE, TOP }
enum class TFont { SANS, SERIF, MONO }

/** Style as written on one node; a null field inherits (SPEC §6). An empty [dash] means none. */
class TStyle(
    val stroke: TColor? = null,
    val strokeWidth: Expr? = null,
    val dash: List<Expr>? = null,
    val fill: TColor? = null,
    val opacity: Expr? = null,
    val font: TFont? = null,
    val size: Expr? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val align: TAlign? = null,
    val baseline: TBaseline? = null,
    val rotate: Expr? = null,
) {
    val isEmpty: Boolean = stroke == null && strokeWidth == null && dash == null && fill == null && opacity == null &&
            font == null && size == null && bold == null && italic == null && align == null &&
            baseline == null && rotate == null

    companion object {
        val EMPTY = TStyle()
    }
}

enum class RepeatAxis { X, Y, XY, NONE }
enum class EllipseKind { CIRCLE, ELLIPSE, DOT }
enum class Fit { REPEAT, SPACE, ROUND, CENTER }

/** One piece of a text string: literal characters, or an interpolated `{expr}` (SPEC §5.3). */
sealed class TextPart {
    class Literal(val text: String) : TextPart()
    class Value(val expr: Expr, val pad: Int?) : TextPart()
}

/** The node tree (SPEC §5). Absent properties already hold their defaults. */
sealed class TNode(val style: TStyle) {

    class Group(
        style: TStyle,
        val x: Expr,
        val y: Expr,
        val w: Expr,
        val h: Expr,
        /** Top, right, bottom, left, already expanded from the CSS shorthand; null when absent. */
        val inset: List<Expr>?,
        val clip: Boolean,
        val children: List<TNode>,
    ) : TNode(style)

    class Repeat(
        style: TStyle,
        val axis: RepeatAxis,
        val periodX: Expr?,
        val periodY: Expr?,
        val countX: Expr?,
        val countY: Expr?,
        val fitX: Fit,
        val fitY: Fit,
        val stagger: Expr?,
        val extend: Boolean,
        /** An alias for `i` (the only index of a one-axis repeat), and for `j`. */
        val indexX: String?,
        val indexY: String?,
        val children: List<TNode>,
    ) : TNode(style)

    class Line(style: TStyle, val x1: Expr, val y1: Expr, val x2: Expr, val y2: Expr) : TNode(style)

    class Hatch(style: TStyle, val angle: Expr, val period: Expr, val offset: Expr, val extend: Boolean) : TNode(style)

    class Rectangle(style: TStyle, val x: Expr, val y: Expr, val w: Expr, val h: Expr, val rx: Expr) : TNode(style)

    /** `circle` and `dot` use [rx] as their radius and ignore [ry]; a dot is filled, with no outline. */
    class Ellipse(style: TStyle, val kind: EllipseKind, val cx: Expr, val cy: Expr, val rx: Expr, val ry: Expr) : TNode(style)

    /** `polyline` / `polygon`: [points] alternates x and y. */
    class Poly(style: TStyle, val points: List<Expr>, val closed: Boolean) : TNode(style)

    class Path(
        style: TStyle,
        val contours: List<VectorContour>,
        val viewBox: Rect?,
        val x: Expr,
        val y: Expr,
        val w: Expr,
        val h: Expr,
        val stretch: Boolean,
    ) : TNode(style)

    /** [texts] has one entry for a plain string, several for an array picked by [select]. */
    class Text(style: TStyle, val texts: List<List<TextPart>>, val x: Expr, val y: Expr, val select: Expr) : TNode(style)

    /** A picture from the package (SPEC §5.3); [posX] and [posY] are 0, 0.5 or 1 across and down. */
    class Image(
        style: TStyle,
        val src: String,
        val x: Expr,
        val y: Expr,
        val w: Expr,
        val h: Expr,
        val fit: ImageFit,
        val posX: Double,
        val posY: Double,
    ) : TNode(style)
}

enum class ImageFit { CONTAIN, COVER, STRETCH }

/** Parameter values a host passes in; unset names take the template's defaults. Lengths in mm. */
class TemplateValues(
    val numbers: Map<String, Double> = emptyMap(),
    val colors: Map<String, Rgba> = emptyMap(),
) {
    companion object {
        val DEFAULTS = TemplateValues()
    }
}

/** What a page gives a template: its size and paper in mm, colours and parameter values. */
class TemplatePage(
    val width: Double,
    val height: Double,
    /** The paper in page mm: the page itself, or more when the host adds margins (SPEC §8). */
    val paper: Rect = Rect(0.0, 0.0, width, height),
    val ink: Rgba,
    val accent: Rgba,
    val values: TemplateValues = TemplateValues.DEFAULTS,
)
