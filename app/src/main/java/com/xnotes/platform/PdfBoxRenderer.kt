package com.xnotes.platform

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Mark
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pdf.GlyphPlacement
import com.xnotes.core.pdf.LinkFinder
import com.xnotes.core.pdf.PdfNumbers
import com.xnotes.core.pdf.PlacedLink
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.xnotes.core.pal.BlendMode as PalBlend

/**
 * A [Renderer] that writes **vector** PDF operators into a PdfBox content stream, so exported ink
 * and shapes stay as real paths and the imported PDF page underneath is never rasterized. One
 * instance draws the annotation layer of a single page; [PdfExporter] sets up the per-page mapping.
 *
 * Coordinates: the model paints in *content pixels* (top-left origin, y-down, page sized at the
 * document dpi). PDF user space is points (bottom-left origin, y-up), offset to the page's crop box.
 * Rather than push a flipped CTM — which would also mirror placed images and text — this maps every
 * point in software: `user = (ox + x·sx, oy + y·sy)` with `sx = +s, sy = −s, s = 72/dpi`. On a
 * source page turned by /Rotate that is an upright frame, which the stream's one `cm` ([turn])
 * takes into user space, so ink, text and images sit upright on the page as displayed.
 *
 * Only the primitives the *vectorizable* items use are meaningful here (fills, strokes, images,
 * text). Effect-heavy items (neon glow, highlighter multiply, translucent ink) are rasterized by the
 * exporter and handed back as bitmaps via [drawItemBitmap]; the glow methods here are inert, so they
 * never silently flatten anything to a crisp-but-wrong vector shape. When the export is tagged,
 * every primitive first tells the page's [PageTagger] what it is about to draw.
 */
internal class PdfBoxRenderer(
    private val cs: PDPageContentStream,
    private val ctx: PdfExportContext,
    private val page: PDPage,
    ox: Double,
    oy: Double,
    s: Double,
    private val turn: Affine = Affine.IDENTITY,
) : Renderer {

    private val doc: PDDocument get() = ctx.doc

    private val tagger: PageTagger? = ctx.tags?.page(page, cs)

    init {
        if (turn != Affine.IDENTITY) cs.transform(Matrix(turn.a.toFloat(), turn.b.toFloat(), turn.c.toFloat(), turn.d.toFloat(), turn.e.toFloat(), turn.f.toFloat()))
    }

    // Affine content→user mapping (translation + axis scale only; never rotation/shear — PAL §1).
    private var ox = ox
    private var oy = oy
    private var sx = s
    private var sy = -s

    // The opacity a saveLayerAlpha put in force; a translucent colour inside it multiplies with it.
    private var layerAlpha = 1.0
    private val stack = ArrayDeque<DoubleArray>()
    private val scaleAbs get() = (abs(sx) + abs(sy)) / 2.0

    private fun ux(x: Double): Float = (ox + x * sx).toFloat()
    private fun uy(y: Double): Float = (oy + y * sy).toFloat()

    // --- transform stack ---
    override fun save() {
        stack.addLast(doubleArrayOf(ox, oy, sx, sy, layerAlpha))
        cs.saveGraphicsState()
    }

    override fun restore() {
        tagger?.restoring(stack.size - 1)
        cs.restoreGraphicsState()
        stack.removeLastOrNull()?.let { ox = it[0]; oy = it[1]; sx = it[2]; sy = it[3]; layerAlpha = it[4] }
    }

    override fun saveLayerAlpha(bounds: Rect, alpha: Double) {
        save()
        layerAlpha *= alpha.coerceIn(0.0, 1.0)
        cs.setGraphicsStateParameters(ctx.graphicsState(layerAlpha))
    }

    override fun saveLayerBlended(bounds: Rect, alpha: Double, blend: PalBlend) {
        save()
        val pdfBlend = when (blend) {
            PalBlend.MULTIPLY -> BlendMode.MULTIPLY
            PalBlend.SCREEN -> BlendMode.SCREEN
            PalBlend.SRC_OVER -> null
        }
        layerAlpha *= alpha.coerceIn(0.0, 1.0)
        cs.setGraphicsStateParameters(ctx.graphicsState(layerAlpha, pdfBlend))
    }

    /**
     * Run [paint] at [color]'s own opacity. PDF keeps opacity in the graphics state rather than
     * the colour, and a state set later replaces it instead of multiplying, so a translucent
     * colour gets a state of its own, scoped by q/Q so the layer's opacity is back afterwards.
     */
    private inline fun translucent(color: Rgba, paint: () -> Unit) {
        if (color.a >= 255) return paint()
        cs.saveGraphicsState()
        cs.setGraphicsStateParameters(ctx.graphicsState(layerAlpha * color.a / 255.0))
        paint()
        cs.restoreGraphicsState()
    }

    override fun translate(dx: Double, dy: Double) {
        ox += dx * sx
        oy += dy * sy
    }

    override fun scale(sxFactor: Double, syFactor: Double) {
        sx *= sxFactor
        sy *= syFactor
    }

    override fun clipRect(rect: Rect) {
        // Export draws unclipped: the PDF page already bounds its content and no vectorized item clips.
    }

    override fun clear() {
        // No meaningful "clear to transparent" on an append stream; unused by export.
    }

    // --- fills ---
    override fun fillBackground(rect: Rect, color: Rgba) = fillRect(rect, color)

    override fun fillRect(rect: Rect, color: Rgba) {
        tag()
        translucent(color) {
            setFill(color)
            rectPath(rect)
            cs.fill()
        }
    }

    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) {
        if (points.size < 3) return
        tag()
        translucent(color) {
            setFill(color)
            polyPath(points, close = true)
            if (rule == FillRule.EVEN_ODD) cs.fillEvenOdd() else cs.fill()
        }
    }

    override fun fillCircle(center: Pt, radius: Double, color: Rgba) =
        fillEllipse(center, radius, radius, color)

    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) {
        if (rx <= 0.0 || ry <= 0.0) return
        tag()
        translucent(color) {
            setFill(color)
            ellipsePath(center, rx, ry)
            cs.fill()
        }
    }

    // --- outlines ---
    override fun strokeRect(rect: Rect, pen: Pen) {
        tag()
        translucent(pen.color) {
            applyPen(pen)
            rectPath(rect)
            cs.stroke()
        }
    }

    override fun strokePolyline(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        tag()
        translucent(pen.color) {
            applyPen(pen)
            polyPath(points, close = false)
            cs.stroke()
        }
    }

    override fun strokePolygon(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        tag()
        translucent(pen.color) {
            applyPen(pen)
            polyPath(points, close = true)
            cs.stroke()
        }
    }

    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) {
        if (rx <= 0.0 || ry <= 0.0) return
        tag()
        translucent(pen.color) {
            applyPen(pen)
            ellipsePath(center, rx, ry)
            cs.stroke()
        }
    }

    // --- raster ---
    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {
        val bmp = (raster as? AndroidRasterSurface)?.bitmap ?: return
        if (bmp.isRecycled) return
        tag()
        placeBitmap(bmp, dest, multiply = false)
    }

    // Inserted images embed as XObjects: decode the source (capped for file size/memory), apply the
    // stored quarter turn to the pixels, then place it like any other bitmap. A vector (SVG) source
    // has no native pixels, so it rasterizes at the placed size supersampled for print instead of at
    // the cap; the decode box is pre-swapped for quarter turns (dest already carries the turned box).
    override fun drawImage(image: ImageData, dest: Rect, orientation: Int, angle: Double) {
        if (dest.w <= 0.0 || dest.h <= 0.0) return
        val turned = orientation % 180 != 0
        val (reqW, reqH) = if (ImageDecoder.isVector(image.file.path)) {
            val w = (dest.w * abs(sx) * VECTOR_EXPORT_SCALE).toInt().coerceIn(1, EXPORT_CAP_PX)
            val h = (dest.h * abs(sy) * VECTOR_EXPORT_SCALE).toInt().coerceIn(1, EXPORT_CAP_PX)
            if (turned) h to w else w to h
        } else {
            EXPORT_CAP_PX to EXPORT_CAP_PX
        }
        var bmp = ImageDecoder.decodeSampledFile(image.file.path, reqW, reqH) ?: return
        val o = ((orientation % 360) + 360) % 360
        if (o != 0) {
            val m = android.graphics.Matrix().apply { postRotate(o.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        tag()
        if (angle == 0.0) placeBitmap(bmp, dest, multiply = false) else placeTurnedBitmap(bmp, dest, angle)
    }

    /**
     * Place a bitmap turned [angle] radians clockwise about [dest]'s centre, as a placement matrix
     * rather than a resampled bitmap, so the exported pixels are the source's own. The matrix maps
     * the unit square: content space is y-down and user space is y-up, so the page sees the turn
     * mirrored, which is the sign flip on the sine terms.
     */
    private fun placeTurnedBitmap(bmp: Bitmap, dest: Rect, angle: Double) {
        if (bmp.isRecycled) return
        val img = LosslessFactory.createFromImage(doc, bmp)
        val w = dest.w * abs(sx)
        val h = dest.h * abs(sy)
        val cx = ux(dest.centerX)
        val cy = uy(dest.centerY)
        val co = cos(angle)
        val sn = sin(angle)
        val matrix = Matrix(
            (w * co).toFloat(), (-w * sn).toFloat(),
            (h * sn).toFloat(), (h * co).toFloat(),
            (cx - w / 2.0 * co - h / 2.0 * sn).toFloat(),
            (cy + w / 2.0 * sn - h / 2.0 * co).toFloat(),
        )
        cs.drawImage(img, matrix)
    }

    /**
     * Place a pre-rendered item bitmap (an effect/text item the exporter rasterized) at its content
     * rect. [multiply] composites it with the page beneath via the Multiply blend mode — used for the
     * highlighter, so it tints the PDF/ink underneath instead of painting a flat translucent block.
     */
    fun drawItemBitmap(bmp: Bitmap, dest: Rect, multiply: Boolean) {
        tag()
        placeBitmap(bmp, dest, multiply)
    }

    /**
     * A text box, as real text: laid out by the same [AndroidText.layout] the screen draws it with,
     * then written a word and a space at a time where that layout puts them. Asking the layout for
     * each position, rather than summing advances, keeps tab stops and right-to-left lines where
     * the screen has them.
     */
    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) {
        if (text.isEmpty()) return
        val layout = AndroidText.layout(text, rect.w.toInt(), AndroidText.textPaint(font))
        val links = LinkFinder.find(text)
        // Tagged, a word stops where a link starts or ends, so the link's text is an element of its own.
        fun linkAt(k: Int): Int = if (tagger == null) -1 else links.indexOfFirst { k >= it.start && k < it.end }
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            var end = layout.getLineEnd(line)
            while (end > start && text[end - 1].isWhitespace()) end--
            val baseline = rect.top + layout.getLineBaseline(line)
            fun leftOf(a: Int, b: Int): Double =
                rect.left + minOf(layout.getPrimaryHorizontal(a), layout.getPrimaryHorizontal(b)).toDouble()
            var i = start
            while (i < end) {
                if (text[i] == ' ' || text[i] == '\t') {
                    tagger?.boxLink = -1
                    drawTextRun(" ", leftOf(i, i + 1), baseline, font, color)
                    i++
                    continue
                }
                val link = linkAt(i)
                var j = i
                while (j < end && text[j] != ' ' && text[j] != '\t' && linkAt(j) == link) j++
                tagger?.boxLink = link
                drawTextRun(text.substring(i, j), leftOf(i, j), baseline, font, color)
                i = j
            }
            // A line broken at a space or a newline ends in one, so the words either side stay apart.
            if (end < layout.getLineEnd(line)) {
                tagger?.boxLink = -1
                drawTextRun(" ", rect.left + layout.getPrimaryHorizontal(end), baseline, font, color)
            }
        }
        for ((n, link) in links.withIndex()) {
            for (line in layout.getLineForOffset(link.start)..layout.getLineForOffset(link.end - 1)) {
                val a = maxOf(link.start, layout.getLineStart(line))
                val b = minOf(link.end, layout.getLineEnd(line))
                if (b <= a) continue
                val x0 = minOf(layout.getPrimaryHorizontal(a), layout.getPrimaryHorizontal(b))
                val x1 = maxOf(layout.getPrimaryHorizontal(a), layout.getPrimaryHorizontal(b))
                val top = layout.getLineTop(line).toDouble()
                val box = Rect(rect.left + x0, rect.top + top, (x1 - x0).toDouble(), layout.getLineBottom(line) - top)
                val annot = linkAnnotation(box, link.uri) ?: continue
                tagger?.boxLink = n
                tagger?.annotate(annot.cosObject)
            }
        }
        tagger?.boxLink = -1
    }

    override val writesText: Boolean get() = true

    override fun beginMark(mark: Mark) {
        tagger?.begin(mark)
    }

    override fun endMark() {
        tagger?.end()
    }

    /** Close the page's open marked content. Call once the page is painted, before its stream ends. */
    fun endPage() {
        tagger?.close()
    }

    private fun tag(kind: PageTagger.Kind = PageTagger.Kind.GRAPHIC, latex: String? = null) {
        tagger?.draw(kind, stack.size, latex)
    }

    // A bullet is a glyph of its own, so selecting a list copies "•" where the dot is.
    override fun drawBullet(center: Pt, radius: Double, baseline: Double, font: FontSpec, color: Rgba) {
        val sizePx = font.pointSize * AndroidText.POINTS_TO_PX
        val k = 1000.0 / sizePx
        val r = radius * k
        val cy = (baseline - center.y) * k
        val key = "bullet ${PdfNumbers.format(r, 1)} ${PdfNumbers.format(cy, 1)}"
        val glyph = ctx.text.markGlyph(key, "•", 2 * r, doubleArrayOf(0.0, cy - r, 2 * r, cy + r)) {
            StringBuilder().also { ellipseOps(it, r, cy, r) }.append("f\n").toString()
        }
        tag(PageTagger.Kind.TEXT)
        showRun(PdfText.Run(listOf(glyph), doubleArrayOf(0.0)), center.x - radius, baseline, sizePx, color)
    }

    // A checkbox glyph: its outline as an even-odd ring, plus the inner square when checked.
    override fun drawCheckbox(box: Rect, stroke: Double, checked: Boolean, baseline: Double, font: FontSpec, color: Rgba) {
        val sizePx = font.pointSize * AndroidText.POINTS_TO_PX
        val k = 1000.0 / sizePx
        val outer = box.outset(stroke / 2.0)
        val inner = box.outset(-stroke / 2.0)
        val mark = box.outset(-box.w * 0.25)
        fun rect(sb: StringBuilder, rc: Rect) {
            for (v in doubleArrayOf((rc.left - outer.left) * k, (baseline - rc.bottom) * k, rc.w * k, rc.h * k)) {
                PdfNumbers.append(sb, v, 1)
                sb.append(' ')
            }
            sb.append("re\n")
        }
        val key = "box ${PdfNumbers.format(box.w * k, 1)} ${PdfNumbers.format(stroke * k, 1)} " +
            "${PdfNumbers.format((baseline - box.bottom) * k, 1)} $checked"
        val bbox = doubleArrayOf(0.0, (baseline - outer.bottom) * k, outer.w * k, (baseline - outer.top) * k)
        val glyph = ctx.text.markGlyph(key, if (checked) "☑" else "☐", outer.w * k, bbox) {
            StringBuilder().also { sb ->
                rect(sb, outer)
                if (inner.w > 0.0 && inner.h > 0.0) rect(sb, inner)
                if (checked) rect(sb, mark)
                sb.append("f*\n")
            }.toString()
        }
        tag(PageTagger.Kind.TEXT)
        showRun(PdfText.Run(listOf(glyph), doubleArrayOf(0.0)), outer.left, baseline, sizePx, color)
    }

    /** A closed ellipse at ([cx], [cy]) with radii [rx] (and [ry]) in whatever space [sb] is in. */
    private fun ellipseOps(sb: StringBuilder, rx: Double, cy: Double, ry: Double, cx: Double = rx) {
        val k = 0.5522847498307936
        fun p(x: Double, y: Double) {
            PdfNumbers.append(sb, x, 1)
            sb.append(' ')
            PdfNumbers.append(sb, y, 1)
            sb.append(' ')
        }
        p(cx + rx, cy); sb.append("m\n")
        p(cx + rx, cy + ry * k); p(cx + rx * k, cy + ry); p(cx, cy + ry); sb.append("c\n")
        p(cx - rx * k, cy + ry); p(cx - rx, cy + ry * k); p(cx - rx, cy); sb.append("c\n")
        p(cx - rx, cy - ry * k); p(cx - rx * k, cy - ry); p(cx, cy - ry); sb.append("c\n")
        p(cx + rx * k, cy - ry); p(cx + rx, cy - ry * k); p(cx + rx, cy); sb.append("c\nh\n")
    }

    // A formula is one glyph: vector outlines on the page, its LaTeX when copied.
    override fun drawMath(latex: String, x: Double, baseline: Double, sizePt: Double, color: Rgba, display: Boolean) {
        val glyph = ctx.text.formulaGlyph(latex, sizePt, color, display) ?: return
        tag(PageTagger.Kind.TEXT, latex)
        showRun(PdfText.Run(listOf(glyph), doubleArrayOf(0.0)), x, baseline, sizePt * AndroidText.POINTS_TO_PX, color)
    }

    /** Make [link]'s share of the flow open its address when clicked. */
    fun addFlowLink(link: PlacedLink) {
        val annot = linkAnnotation(link.rect, link.uri) ?: return
        tagger?.annotateFlow(annot.cosObject, link.para, link.link)
    }

    /**
     * Make [rect] (content space) open [uri] when clicked, drawing nothing: an address in the text
     * looks exactly as it does on screen, and the link lies over it. Null for an empty [rect].
     */
    private fun linkAnnotation(rect: Rect, uri: String): PDAnnotationLink? {
        if (rect.w <= 0.0 || rect.h <= 0.0) return null
        val link = PDAnnotationLink()
        link.rectangle = userRect(rect)
        link.action = PDActionURI().apply { this.uri = uri }
        link.border = COSArray().apply { repeat(3) { add(COSInteger.ZERO) } }
        link.contents = uri
        val annots = page.annotations
        annots.add(link)
        page.annotations = annots
        return link
    }

    /** [rect] (content space) in the page's user space, where annotations are placed: [turn] doesn't reach them. */
    private fun userRect(rect: Rect): PDRectangle {
        if (turn == Affine.IDENTITY) return PDRectangle(ux(rect.left), uy(rect.bottom), (rect.w * abs(sx)).toFloat(), (rect.h * abs(sy)).toFloat())
        val box = Rect.bounding(listOf(Pt(ox + rect.left * sx, oy + rect.top * sy), Pt(ox + rect.right * sx, oy + rect.bottom * sy)).map(turn::apply))
        return PDRectangle(box.left.toFloat(), box.top.toFloat(), box.w.toFloat(), box.h.toFloat())
    }

    // Real text through the export's Type 3 fonts: each glyph pinned to the x the screen draws it at.
    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) {
        if (text.isEmpty()) return
        val run = ctx.text.shape(text, font)
        if (run.glyphs.isEmpty()) return
        tag(PageTagger.Kind.TEXT)
        showRun(run, x, baseline, font.pointSize * AndroidText.POINTS_TO_PX, color)
    }

    /**
     * Write [run] as one text object with its origin at ([x], [baseline]) in content space, in a
     * font of [sizePx] content px. Glyphs of one run can live in several of the export's fonts, so
     * the font is switched wherever the next glyph needs another; the pen carries across.
     */
    private fun showRun(run: PdfText.Run, x: Double, baseline: Double, sizePx: Double, color: Rgba) = translucent(color) {
        val size = (sizePx * abs(sy)).toFloat()
        cs.beginText()
        setFill(color)
        cs.setTextMatrix(Matrix((abs(sx) / abs(sy)).toFloat(), 0f, 0f, 1f, ux(x), uy(baseline)))
        val widths = DoubleArray(run.glyphs.size) { run.glyphs[it].width }
        val adj = GlyphPlacement.adjustments(widths, run.offsets, sizePx)
        val sb = StringBuilder(run.glyphs.size * 4 + 16)
        var font: Type3Font? = null
        var inHex = false
        for ((i, g) in run.glyphs.withIndex()) {
            if (g.font !== font) {
                if (font != null) {
                    if (inHex) sb.append('>')
                    sb.append("] TJ\n")
                    cs.appendRawCommands(sb.toString())
                    sb.setLength(0)
                }
                cs.setFont(g.font.pd, size)
                font = g.font
                sb.append('[')
                inHex = false
            }
            if (adj[i] != 0.0) {
                if (inHex) sb.append('>')
                sb.append(' ')
                PdfNumbers.append(sb, adj[i], 1)
                sb.append(' ')
                inHex = false
            }
            if (!inHex) sb.append('<')
            inHex = true
            sb.append(HEX[g.code shr 4]).append(HEX[g.code and 15])
        }
        if (inHex) sb.append('>')
        sb.append("] TJ\n")
        cs.appendRawCommands(sb.toString())
        cs.endText()
    }

    private fun placeBitmap(bmp: Bitmap, dest: Rect, multiply: Boolean) {
        if (bmp.isRecycled || dest.w <= 0.0 || dest.h <= 0.0) return
        val img = LosslessFactory.createFromImage(doc, bmp)
        val llx = ux(dest.left)
        val lly = uy(dest.bottom) // largest content-y maps to the smallest user-y → lower-left corner
        val w = (dest.w * abs(sx)).toFloat()
        val h = (dest.h * abs(sy)).toFloat()
        if (multiply) {
            cs.saveGraphicsState()
            cs.setGraphicsStateParameters(ctx.graphicsState(layerAlpha, BlendMode.MULTIPLY))
            cs.drawImage(img, llx, lly, w, h)
            cs.restoreGraphicsState()
        } else {
            cs.drawImage(img, llx, lly, w, h)
        }
    }

    // --- helpers ---
    private fun setFill(c: Rgba) {
        cs.setNonStrokingColor(c.r / 255f, c.g / 255f, c.b / 255f)
    }

    private fun applyPen(pen: Pen) {
        cs.setStrokingColor(pen.color.r / 255f, pen.color.g / 255f, pen.color.b / 255f)
        // Widths/dashes are content-px lengths; no CTM scale is applied, so convert to points here.
        cs.setLineWidth((pen.width * scaleAbs).toFloat().coerceAtLeast(0.05f))
        cs.setLineCapStyle(1) // round
        cs.setLineJoinStyle(1) // round
        if (pen.dashed) {
            val on = (pen.dashOn * scaleAbs).toFloat().coerceAtLeast(0.1f)
            val off = (pen.dashGap * scaleAbs).toFloat().coerceAtLeast(0.1f)
            cs.setLineDashPattern(floatArrayOf(on, off), 0f)
        } else {
            cs.setLineDashPattern(floatArrayOf(), 0f)
        }
    }

    private fun rectPath(rect: Rect) {
        cs.moveTo(ux(rect.left), uy(rect.top))
        cs.lineTo(ux(rect.right), uy(rect.top))
        cs.lineTo(ux(rect.right), uy(rect.bottom))
        cs.lineTo(ux(rect.left), uy(rect.bottom))
        cs.closePath()
    }

    private fun polyPath(points: List<Pt>, close: Boolean) {
        cs.moveTo(ux(points[0].x), uy(points[0].y))
        for (i in 1 until points.size) cs.lineTo(ux(points[i].x), uy(points[i].y))
        if (close) cs.closePath()
    }

    private fun ellipsePath(center: Pt, rx: Double, ry: Double) {
        val k = 0.5522847498307936 // cubic-Bézier circle constant
        val cx = center.x
        val cy = center.y
        fun curve(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) =
            cs.curveTo(ux(x1), uy(y1), ux(x2), uy(y2), ux(x3), uy(y3))
        cs.moveTo(ux(cx + rx), uy(cy))
        curve(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        curve(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        curve(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        curve(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        cs.closePath()
    }

    companion object {
        /** Long-edge cap (px) for an exported image XObject: keeps detail without ballooning the PDF. */
        private const val EXPORT_CAP_PX = 4096

        private const val HEX = "0123456789ABCDEF"

        // Vector images rasterize at 4 px per PDF point (288 dpi), a print-quality density.
        private const val VECTOR_EXPORT_SCALE = 4.0
    }
}
