package com.xnotes.core.template

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.vector.GlyphOutliner
import com.xnotes.core.vector.GlyphRun
import com.xnotes.core.vector.GlyphStyle
import com.xnotes.core.vector.PathFlattener
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lays a [Template] out for one page: walks the node tree against the page's size and parameter
 * values and returns every shape it draws, clipped and grouped into colour layers, in page mm.
 * Pure arithmetic apart from the optional [outliner], which turns `text` into glyph outlines (text
 * is dropped without one), so it runs on the plain JVM and off the main thread. Stateless between
 * calls; safe to share across threads.
 */
class TemplateEval(private val outliner: GlyphOutliner? = null) {

    fun evaluate(t: Template, page: TemplatePage, maxPrims: Int = MAX_PRIMS): TemplateOutput {
        val run = Run(page, maxPrims)
        for (p in t.params) {
            if (p.type == ParamType.COLOR) {
                (page.values.colors[p.name] ?: p.defaultColor)?.let { run.colorParams[p.name] = it }
            } else {
                run.vars[p.name] = p.clamp(page.values.numbers[p.name] ?: p.default)
            }
        }
        run.vars["i"] = 0.0
        run.vars["j"] = 0.0
        val pageBox = Rect(0.0, 0.0, page.width, page.height)
        try {
            run.node(t.root, pageBox, page.paper, run.defaults())
        } catch (_: Full) {
            // Past the shape cap (SPEC §11): keep what was laid out so far.
        }
        return TemplateOutput(run.layers)
    }

    private object Full : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Style with every inherited value resolved (SPEC §6); colours are before [opacity]. */
    private class Style(
        val stroke: Rgba?,
        val strokeWidth: Double,
        val dashOn: Double,
        val dashOff: Double,
        val fill: Rgba?,
        val opacity: Double,
        val font: TFont,
        val size: Double,
        val bold: Boolean,
        val italic: Boolean,
        val align: TAlign,
        val baseline: TBaseline,
        val quarterTurns: Int,
    )

    private class Cells(val index: IntArray, val pos: DoubleArray, val size: Double) {
        val count get() = index.size

        companion object {
            val NONE = Cells(IntArray(0), DoubleArray(0), 0.0)
        }
    }

    private inner class Run(val page: TemplatePage, val maxPrims: Int) : ExprEnv {
        val vars = HashMap<String, Double>()
        val colorParams = HashMap<String, Rgba>()
        /** In compositing order: a colour's layer where its first shape is, an image's where it is. */
        val layers = ArrayList<TemplateLayer>()
        private val byColor = HashMap<Rgba, ArrayList<TPrim>>()
        private var count = 0
        private val glyphs = HashMap<String, GlyphRun?>()
        private val tt = DoubleArray(2)

        override var boxW = 0.0
        override var boxH = 0.0
        override fun lookup(name: String): Double? = vars[name]

        fun defaults() = Style(
            stroke = page.ink, strokeWidth = 0.25, dashOn = 0.0, dashOff = 0.0, fill = null, opacity = 1.0,
            font = TFont.SANS, size = 3.5, bold = false, italic = false,
            align = TAlign.START, baseline = TBaseline.ALPHABETIC, quarterTurns = 0,
        )

        fun ev(e: Expr, box: Rect, axis: Axis): Double {
            e.constant?.let { return it }
            boxW = box.w
            boxH = box.h
            val v = e.eval(this, axis)
            if (!v.isFinite()) throw TemplateEvalException("not a finite value")
            return v
        }

        private fun color(c: TColor): Rgba? = when (c) {
            TColor.Ink -> page.ink
            TColor.Accent -> page.accent
            TColor.None -> null
            is TColor.Literal -> c.rgba
            is TColor.Param -> colorParams[c.name] ?: page.ink
        }

        private fun resolve(s: TStyle, p: Style, box: Rect): Style {
            if (s.isEmpty) return p
            var dashOn = p.dashOn
            var dashOff = p.dashOff
            s.dash?.let { d ->
                if (d.size == 2) {
                    dashOn = ev(d[0], box, Axis.SHORT)
                    dashOff = ev(d[1], box, Axis.SHORT)
                    if (dashOn <= 0.0 || dashOff <= 0.0) { dashOn = 0.0; dashOff = 0.0 }
                } else {
                    dashOn = 0.0; dashOff = 0.0
                }
            }
            val turns = s.rotate?.let { ((kotlin.math.round(ev(it, box, Axis.PLAIN) / 90.0).toLong() % 4 + 4) % 4).toInt() }
            return Style(
                stroke = if (s.stroke != null) color(s.stroke) else p.stroke,
                strokeWidth = s.strokeWidth?.let { ev(it, box, Axis.SHORT) } ?: p.strokeWidth,
                dashOn = dashOn,
                dashOff = dashOff,
                fill = if (s.fill != null) color(s.fill) else p.fill,
                opacity = p.opacity * (s.opacity?.let { ev(it, box, Axis.PLAIN).coerceIn(0.0, 1.0) } ?: 1.0),
                font = s.font ?: p.font,
                size = s.size?.let { ev(it, box, Axis.SHORT) } ?: p.size,
                bold = s.bold ?: p.bold,
                italic = s.italic ?: p.italic,
                align = s.align ?: p.align,
                baseline = s.baseline ?: p.baseline,
                quarterTurns = turns ?: p.quarterTurns,
            )
        }

        private fun emit(color: Rgba, opacity: Double, prim: TPrim) {
            val a = (color.a * opacity).roundToInt()
            if (a <= 0) return
            if (count >= maxPrims) throw Full
            val key = if (a == color.a) color else color.copy(a = a)
            byColor.getOrPut(key) { ArrayList<TPrim>().also { layers.add(TemplateLayer(key, it)) } }.add(prim)
            count++
        }

        fun node(n: TNode, box: Rect, clip: Rect, parent: Style) {
            try {
                val st = resolve(n.style, parent, box)
                when (n) {
                    is TNode.Group -> group(n, box, clip, st)
                    is TNode.Repeat -> repeat(n, box, clip, st)
                    is TNode.Line -> line(n, box, clip, st)
                    is TNode.Hatch -> hatch(n, box, clip, st)
                    is TNode.Rectangle -> rect(n, box, clip, st)
                    is TNode.Ellipse -> ellipse(n, box, clip, st)
                    is TNode.Poly -> poly(n, box, clip, st)
                    is TNode.Path -> path(n, box, clip, st)
                    is TNode.Text -> text(n, box, clip, st)
                    is TNode.Image -> image(n, box, clip, st)
                }
            } catch (_: TemplateEvalException) {
                // An invalid node is skipped with its children (SPEC §3).
            }
        }

        private fun group(n: TNode.Group, box: Rect, clip: Rect, st: Style) {
            var x = box.x + ev(n.x, box, Axis.X)
            var y = box.y + ev(n.y, box, Axis.Y)
            var w = ev(n.w, box, Axis.X)
            var h = ev(n.h, box, Axis.Y)
            n.inset?.let { ins ->
                val t = ev(ins[0], box, Axis.Y)
                val r = ev(ins[1], box, Axis.X)
                val b = ev(ins[2], box, Axis.Y)
                val l = ev(ins[3], box, Axis.X)
                x += l; y += t; w -= l + r; h -= t + b
            }
            val gb = Rect(x, y, max(0.0, w), max(0.0, h))
            val c = if (n.clip) TemplateClip.intersect(clip, gb) ?: return else clip
            for (child in n.children) node(child, gb, c, st)
        }

        private fun repeat(n: TNode.Repeat, box: Rect, clip: Rect, st: Style) {
            if (n.axis == RepeatAxis.NONE) return repeatInPlace(n, box, clip, st)
            val onX = n.axis != RepeatAxis.Y
            val onY = n.axis != RepeatAxis.X
            val paper = page.paper
            val px = if (onX) n.periodX?.let { ev(it, box, Axis.X) } else null
            val py = if (onY) n.periodY?.let { ev(it, box, Axis.Y) } else null
            val cx = if (onX) n.countX?.let { floor(ev(it, box, Axis.PLAIN)) } else null
            val cy = if (onY) n.countY?.let { floor(ev(it, box, Axis.PLAIN)) } else null
            if (onX && px == null && cx == null) throw TemplateEvalException("repeat needs period or count")
            if (onY && py == null && cy == null) throw TemplateEvalException("repeat needs period or count")
            val extX = n.extend && onX && px != null && n.fitX == Fit.REPEAT
            val extY = n.extend && onY && py != null && n.fitY == Fit.REPEAT
            val stagger = if (n.axis == RepeatAxis.XY) n.stagger?.let { ev(it, box, Axis.X) } ?: 0.0 else 0.0

            // Across a one-axis repeat each cell spans the box, or the paper once extended (SPEC §8).
            val spanX = if (extX || (n.axis == RepeatAxis.Y && extY)) paper.left to paper.right else box.left to box.right
            val spanY = if (extY || (n.axis == RepeatAxis.X && extX)) paper.top to paper.bottom else box.top to box.bottom
            val region = Rect(spanX.first, spanY.first, spanX.second - spanX.first, spanY.second - spanY.first)
            val c = TemplateClip.intersect(clip, region) ?: return

            val colsEven = if (onX) layout(box.left, box.w, px, cx, n.fitX, extX, paper.left, paper.right, 0.0) else null
            val colsOdd = if (onX && stagger != 0.0) layout(box.left, box.w, px, cx, n.fitX, extX, paper.left, paper.right, stagger) else colsEven
            val rows = if (onY) layout(box.top, box.h, py, cy, n.fitY, extY, paper.top, paper.bottom, 0.0) else null

            val saved = saveVars(n)
            try {
                when (n.axis) {
                    RepeatAxis.Y -> {
                        val r = rows ?: return
                        for (k in 0 until r.count) {
                            setIndex(n.indexX, "i", r.index[k])
                            val cell = Rect(spanX.first, r.pos[k], spanX.second - spanX.first, r.size)
                            for (child in n.children) node(child, cell, c, st)
                        }
                    }
                    RepeatAxis.X -> {
                        val cs = colsEven ?: return
                        for (k in 0 until cs.count) {
                            setIndex(n.indexX, "i", cs.index[k])
                            val cell = Rect(cs.pos[k], spanY.first, cs.size, spanY.second - spanY.first)
                            for (child in n.children) node(child, cell, c, st)
                        }
                    }
                    RepeatAxis.NONE -> Unit
                    RepeatAxis.XY -> {
                        val r = rows ?: return
                        for (rk in 0 until r.count) {
                            val row = r.index[rk]
                            val cs = (if (Math.floorMod(row, 2) == 1) colsOdd else colsEven) ?: return
                            setIndex(n.indexY, "j", row)
                            for (k in 0 until cs.count) {
                                setIndex(n.indexX, "i", cs.index[k])
                                val cell = Rect(cs.pos[k], r.pos[rk], cs.size, r.size)
                                for (child in n.children) node(child, cell, c, st)
                            }
                        }
                    }
                }
            } finally {
                restoreVars(saved)
            }
        }

        /** `axis: "none"`: the children [count] times in the unmoved box, only `i` changing. */
        private fun repeatInPlace(n: TNode.Repeat, box: Rect, clip: Rect, st: Style) {
            val count = floor(ev(n.countX ?: throw TemplateEvalException("repeat needs a count"), box, Axis.PLAIN))
            if (count < 1 || count > MAX_COUNT) return
            val c = TemplateClip.intersect(clip, box) ?: return
            val saved = saveVars(n)
            try {
                for (k in 0 until count.toInt()) {
                    setIndex(n.indexX, "i", k)
                    for (child in n.children) node(child, box, c, st)
                }
            } finally {
                restoreVars(saved)
            }
        }

        private fun setIndex(alias: String?, builtin: String, v: Int) {
            vars[builtin] = v.toDouble()
            if (alias != null) vars[alias] = v.toDouble()
        }

        private fun saveVars(n: TNode.Repeat): List<Pair<String, Double?>> =
            listOfNotNull("i", "j", n.indexX, n.indexY).map { it to vars[it] }

        private fun restoreVars(saved: List<Pair<String, Double?>>) {
            for ((k, v) in saved) if (v == null) vars.remove(k) else vars[k] = v
        }

        /** The cells along one axis of box span [start, start + s) (SPEC §5.2). */
        private fun layout(
            start: Double,
            s: Double,
            p: Double?,
            count: Double?,
            fit: Fit,
            extended: Boolean,
            lo: Double,
            hi: Double,
            shift: Double,
        ): Cells {
            if (p == null) {
                val n = count ?: return Cells.NONE
                if (n < 1 || n > MAX_COUNT) return Cells.NONE
                val size = s / n
                if (size < MIN_CELL) return Cells.NONE
                return cells(0, n.toInt()) { k -> start + shift + k * size }.withSize(size)
            }
            if (p < MIN_CELL) return Cells.NONE
            if (count != null && (count < 1 || count > MAX_COUNT)) return Cells.NONE
            return when (fit) {
                Fit.REPEAT -> {
                    val (kmin, kmax) = when {
                        extended -> floor((lo - start - shift) / p + EPS).toLong() to ceil((hi - start - shift) / p - EPS).toLong() - 1
                        count != null -> 0L to count.toLong() - 1
                        else -> floor(-shift / p + EPS).toLong() to ceil((s - shift) / p - EPS).toLong() - 1
                    }
                    if (kmax < kmin || kmax - kmin >= MAX_CELLS) return Cells.NONE
                    cells(kmin.toInt(), (kmax - kmin + 1).toInt()) { k -> start + shift + k * p }.withSize(p)
                }
                Fit.SPACE -> {
                    var n = floor(s / p + EPS)
                    if (count != null) n = min(n, count)
                    if (n < 1) return Cells.NONE
                    if (n == 1.0) return cells(0, 1) { start + shift + (s - p) / 2.0 }.withSize(p)
                    val gap = (s - n * p) / (n - 1)
                    cells(0, n.toInt()) { k -> start + shift + k * (p + gap) }.withSize(p)
                }
                Fit.ROUND -> {
                    val n = count ?: max(1.0, kotlin.math.round(s / p))
                    val size = s / n
                    if (size < MIN_CELL || n > MAX_COUNT) return Cells.NONE
                    cells(0, n.toInt()) { k -> start + shift + k * size }.withSize(size)
                }
                Fit.CENTER -> {
                    var n = floor(s / p + EPS)
                    if (count != null) n = min(n, count)
                    if (n < 1) return Cells.NONE
                    val off = (s - n * p) / 2.0
                    cells(0, n.toInt()) { k -> start + shift + off + k * p }.withSize(p)
                }
            }
        }

        private inline fun cells(first: Int, n: Int, pos: (Int) -> Double): Cells {
            val idx = IntArray(n) { first + it }
            return Cells(idx, DoubleArray(n) { pos(idx[it]) }, 0.0)
        }

        private fun Cells.withSize(size: Double) = Cells(index, pos, size)

        // --- shapes ---

        private fun strokeOf(st: Style): Rgba? = if (st.strokeWidth > 0.0) st.stroke else null

        private fun strokeRun(pts: DoubleArray, closed: Boolean, clip: Rect, st: Style) {
            val color = strokeOf(st) ?: return
            TemplateClip.polyline(pts, closed, clip, TemplateClip.open(clip, page.paper)) { run, ring ->
                emit(color, st.opacity, TPrim.Stroke(run, ring, st.strokeWidth, st.dashOn, st.dashOff))
            }
        }

        private fun fillRings(rings: List<DoubleArray>, clip: Rect, st: Style) {
            val color = st.fill ?: return
            val joined = joinRings(rings.map { TemplateClip.polygon(it, clip) })
            if (joined.size >= 6) emit(color, st.opacity, TPrim.Fill(joined))
        }

        private fun line(n: TNode.Line, box: Rect, clip: Rect, st: Style) {
            val pts = doubleArrayOf(
                box.x + ev(n.x1, box, Axis.X), box.y + ev(n.y1, box, Axis.Y),
                box.x + ev(n.x2, box, Axis.X), box.y + ev(n.y2, box, Axis.Y),
            )
            strokeRun(pts, false, clip, st)
        }

        private fun hatch(n: TNode.Hatch, box: Rect, clip: Rect, st: Style) {
            val color = strokeOf(st) ?: return
            val theta = Math.toRadians(ev(n.angle, box, Axis.PLAIN))
            val p = ev(n.period, box, Axis.SHORT)
            val off = ev(n.offset, box, Axis.SHORT)
            if (p < MIN_CELL) return
            val c = (if (n.extend) clip else TemplateClip.intersect(clip, box)) ?: return
            val dx = cos(theta)
            val dy = -sin(theta)
            val nx = sin(theta)
            val ny = cos(theta)
            var tmin = Double.POSITIVE_INFINITY
            var tmax = Double.NEGATIVE_INFINITY
            for ((x, y) in listOf(c.left to c.top, c.right to c.top, c.left to c.bottom, c.right to c.bottom)) {
                val t = (x - box.x) * nx + (y - box.y) * ny
                tmin = min(tmin, t); tmax = max(tmax, t)
            }
            val kmin = ceil((tmin - off) / p).toLong()
            val kmax = floor((tmax - off) / p).toLong()
            if (kmax < kmin || kmax - kmin >= MAX_CELLS) return
            val open = TemplateClip.open(c, page.paper)
            val reach = hypot(c.w, c.h) + hypot(c.centerX - box.x, c.centerY - box.y) + abs(off) + abs(kmax * p) + abs(kmin * p) + 1.0
            for (k in kmin..kmax) {
                val d = off + k * p
                val bx = box.x + nx * d
                val by = box.y + ny * d
                val ax0 = bx - dx * reach
                val ay0 = by - dy * reach
                val ax1 = bx + dx * reach
                val ay1 = by + dy * reach
                if (!TemplateClip.segment(ax0, ay0, ax1, ay1, c, open, tt)) continue
                val run = doubleArrayOf(
                    ax0 + (ax1 - ax0) * tt[0], ay0 + (ay1 - ay0) * tt[0],
                    ax0 + (ax1 - ax0) * tt[1], ay0 + (ay1 - ay0) * tt[1],
                )
                emit(color, st.opacity, TPrim.Stroke(run, false, st.strokeWidth, st.dashOn, st.dashOff))
            }
        }

        private fun rect(n: TNode.Rectangle, box: Rect, clip: Rect, st: Style) {
            val x = box.x + ev(n.x, box, Axis.X)
            val y = box.y + ev(n.y, box, Axis.Y)
            val w = ev(n.w, box, Axis.X)
            val h = ev(n.h, box, Axis.Y)
            if (w <= 0.0 || h <= 0.0) return
            val rx = ev(n.rx, box, Axis.X).coerceIn(0.0, min(w, h) / 2.0)
            val ring = if (rx > 0.0) roundedRing(x, y, w, h, rx) else doubleArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
            st.fill?.let { f ->
                if (rx > 0.0) {
                    fillRings(listOf(ring), clip, st)
                } else {
                    TemplateClip.intersect(Rect(x, y, w, h), clip)?.let { emit(f, st.opacity, TPrim.FillRect(it)) }
                }
            }
            strokeRun(ring, true, clip, st)
        }

        private fun image(n: TNode.Image, box: Rect, clip: Rect, st: Style) {
            val x = box.x + ev(n.x, box, Axis.X)
            val y = box.y + ev(n.y, box, Axis.Y)
            val w = ev(n.w, box, Axis.X)
            val h = ev(n.h, box, Axis.Y)
            if (w <= 0.0 || h <= 0.0) return
            val r = Rect(x, y, w, h)
            val shown = TemplateClip.intersect(r, clip) ?: return
            val a = (255 * st.opacity).roundToInt()
            if (a <= 0) return
            if (count >= maxPrims) throw Full
            layers.add(TemplateLayer(Rgba(255, 255, 255, a), emptyList(), TImage(n.src, r, shown, n.fit, n.posX, n.posY)))
            count++
        }

        private fun ellipse(n: TNode.Ellipse, box: Rect, clip: Rect, st: Style) {
            val cx = box.x + ev(n.cx, box, Axis.X)
            val cy = box.y + ev(n.cy, box, Axis.Y)
            val round = n.kind != EllipseKind.ELLIPSE
            val rx = ev(n.rx, box, if (round) Axis.SHORT else Axis.X)
            val ry = if (round) rx else ev(n.ry, box, Axis.Y)
            if (rx <= 0.0 || ry <= 0.0) return
            if (!TemplateClip.inside(cx, cy, clip, TemplateClip.open(clip, page.paper))) return
            if (n.kind == EllipseKind.DOT) {
                val c = st.fill ?: st.stroke ?: return
                emit(c, st.opacity, TPrim.Ellipse(cx, cy, rx, ry, true, 0.0, 0.0, 0.0))
                return
            }
            st.fill?.let { emit(it, st.opacity, TPrim.Ellipse(cx, cy, rx, ry, true, 0.0, 0.0, 0.0)) }
            strokeOf(st)?.let { emit(it, st.opacity, TPrim.Ellipse(cx, cy, rx, ry, false, st.strokeWidth, st.dashOn, st.dashOff)) }
        }

        private fun poly(n: TNode.Poly, box: Rect, clip: Rect, st: Style) {
            val pts = DoubleArray(n.points.size)
            for (k in pts.indices) {
                pts[k] = if (k % 2 == 0) box.x + ev(n.points[k], box, Axis.X) else box.y + ev(n.points[k], box, Axis.Y)
            }
            if (n.closed) fillRings(listOf(pts), clip, st)
            strokeRun(pts, n.closed, clip, st)
        }

        private fun path(n: TNode.Path, box: Rect, clip: Rect, st: Style) {
            var sx = 1.0
            var sy = 1.0
            var tx = box.x
            var ty = box.y
            n.viewBox?.let { vb ->
                val x = box.x + ev(n.x, box, Axis.X)
                val y = box.y + ev(n.y, box, Axis.Y)
                val w = ev(n.w, box, Axis.X)
                val h = ev(n.h, box, Axis.Y)
                if (w <= 0.0 || h <= 0.0 || vb.w <= 0.0 || vb.h <= 0.0) return
                sx = w / vb.w
                sy = h / vb.h
                if (n.stretch) {
                    tx = x - vb.x * sx
                    ty = y - vb.y * sy
                } else {
                    val s = min(sx, sy)
                    sx = s; sy = s
                    tx = x + (w - vb.w * s) / 2.0 - vb.x * s
                    ty = y + (h - vb.h * s) / 2.0 - vb.y * s
                }
            }
            val tol = FLATTEN_MM / max(sx, sy)
            val rings = n.contours.map { contour ->
                val flat = PathFlattener.flatten(contour, tol)
                val out = DoubleArray(flat.size * 2)
                flat.forEachIndexed { k, pt -> out[2 * k] = tx + pt.x * sx; out[2 * k + 1] = ty + pt.y * sy }
                out to contour.closed
            }
            if (st.fill != null) fillRings(rings.map { it.first }.filter { it.size >= 6 }, clip, st)
            for ((pts, closed) in rings) strokeRun(pts, closed, clip, st)
        }

        private fun text(n: TNode.Text, box: Rect, clip: Rect, st: Style) {
            val outliner = outliner ?: return
            val x = box.x + ev(n.x, box, Axis.X)
            val y = box.y + ev(n.y, box, Axis.Y)
            if (!TemplateClip.inside(x, y, clip, TemplateClip.open(clip, page.paper))) return
            val color = st.fill ?: st.stroke ?: return
            if (st.size <= 0.0 || n.texts.isEmpty()) return
            val pick = Math.floorMod(floor(ev(n.select, box, Axis.PLAIN)).toLong(), n.texts.size.toLong()).toInt()
            val str = n.texts[pick].joinToString("") { part ->
                when (part) {
                    is TextPart.Literal -> part.text
                    is TextPart.Value -> formatValue(ev(part.expr, box, Axis.PLAIN), part.pad)
                }
            }
            if (str.isEmpty()) return
            val key = "${st.font}|${st.bold}|${st.italic}|$str"
            val glyph = glyphs.getOrPut(key) {
                outliner.outline(str, GlyphStyle(familyOf(st.font), GLYPH_UNITS, st.bold, st.italic, 0.0))
            } ?: return
            val scale = st.size / GLYPH_UNITS
            val dx = when (st.align) {
                TAlign.START -> 0.0
                TAlign.MIDDLE -> -glyph.advance * scale / 2.0
                TAlign.END -> -glyph.advance * scale
            }
            val dy = when (st.baseline) {
                TBaseline.ALPHABETIC -> 0.0
                TBaseline.MIDDLE -> 0.35 * st.size
                TBaseline.TOP -> 0.7 * st.size
            }
            val rings = glyph.contours.map { contour ->
                val flat = PathFlattener.flatten(contour, FLATTEN_MM / scale)
                val out = DoubleArray(flat.size * 2)
                flat.forEachIndexed { k, pt ->
                    val lx = pt.x * scale + dx
                    val ly = pt.y * scale + dy
                    val (rx, ry) = when (st.quarterTurns) {
                        1 -> -ly to lx
                        2 -> -lx to -ly
                        3 -> ly to -lx
                        else -> lx to ly
                    }
                    out[2 * k] = x + rx
                    out[2 * k + 1] = y + ry
                }
                out
            }.filter { it.size >= 6 }
            val joined = joinRings(rings)
            if (joined.size >= 6) emit(color, st.opacity, TPrim.Fill(joined))
        }
    }

    companion object {
        const val MAX_PRIMS = 200_000
        private const val MAX_COUNT = 10_000.0
        private const val MAX_CELLS = 100_000L
        private const val MIN_CELL = 0.1
        private const val EPS = 1e-9
        private const val FLATTEN_MM = 0.05
        private const val GLYPH_UNITS = 100.0

        private fun familyOf(f: TFont) = when (f) {
            TFont.SANS -> "sans-serif"
            TFont.SERIF -> "serif"
            TFont.MONO -> "monospace"
        }

        /** A text value as SPEC §5.3 prints it. */
        fun formatValue(v: Double, pad: Int?): String {
            val r = if (v < 0) -floor(-v + 0.5) else floor(v + 0.5)
            if (pad != null) {
                val n = r.toLong()
                val digits = abs(n).toString().padStart(pad, '0')
                return if (n < 0) "-$digits" else digits
            }
            if (abs(v - r) < 1e-9) return if (r == 0.0) "0" else r.toLong().toString()
            return BigDecimal(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }

        /**
         * Rings joined into one run for a single nonzero fill: each ring is followed by a step back
         * to the first ring's start. The connecting steps are walked once each way, so they cancel
         * in the winding count and every ring keeps its own sense, holes included.
         */
        internal fun joinRings(rings: List<DoubleArray>): DoubleArray {
            val live = rings.filter { it.size >= 6 }
            if (live.isEmpty()) return DoubleArray(0)
            if (live.size == 1) return live[0]
            val out = DoubleArrayBuilder()
            val ox = live[0][0]
            val oy = live[0][1]
            for ((k, ring) in live.withIndex()) {
                out.addAll(ring)
                out.add(ring[0], ring[1])
                if (k > 0) out.add(ox, oy)
            }
            return out.toArray()
        }

        /** A rounded rectangle's outline, corners flattened to within [FLATTEN_MM]. */
        private fun roundedRing(x: Double, y: Double, w: Double, h: Double, r: Double): DoubleArray {
            val steps = max(2, ceil(Math.PI / 2 / (2 * kotlin.math.acos((1 - FLATTEN_MM / r).coerceIn(-1.0, 1.0)))).toInt())
            val out = DoubleArrayBuilder()
            fun corner(cx: Double, cy: Double, from: Double) {
                for (s in 0..steps) {
                    val a = from + Math.PI / 2 * s / steps
                    out.add(cx + r * cos(a), cy + r * sin(a))
                }
            }
            corner(x + w - r, y + r, -Math.PI / 2)
            corner(x + w - r, y + h - r, 0.0)
            corner(x + r, y + h - r, Math.PI / 2)
            corner(x + r, y + r, Math.PI)
            return out.toArray()
        }
    }
}
