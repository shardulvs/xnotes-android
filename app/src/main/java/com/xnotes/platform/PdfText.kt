package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathIterator
import android.graphics.RectF
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import android.os.Build
import android.text.TextPaint
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.MathBox
import com.xnotes.core.pdf.PdfNumbers
import com.xnotes.core.pdf.ToUnicode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Real, selectable text for PDF export, set in Type 3 fonts built from the very outlines Android
 * draws on screen. The fonts, weights, fallback, shaping and fake bold/italic therefore match the
 * screen exactly, and because every glyph's ToUnicode entry is written from our own strings, a
 * viewer copies back exactly what was typed in any script. Android's own PDF writer instead guesses
 * characters back from glyphs, which loses ligatures, CJK and Indic text.
 *
 * Each distinct cluster of a style is captured once, at a reference size, and shared by every
 * occurrence. A word whose shaping differs from its clusters drawn apart (a ligature, a code font's
 * arrows, Indic conjuncts, right-to-left text) becomes one glyph of its own that reads as the word.
 * Colour emoji have no outline and go in as small images.
 */
internal class PdfText(private val doc: PDDocument) {

    /** One glyph in one of the export's fonts: its code there and its advance in glyph units. */
    class Glyph(val font: Type3Font, val code: Int, val width: Double)

    /** A run set as glyphs, each at its px offset from the run's origin. */
    class Run(val glyphs: List<Glyph>, val offsets: DoubleArray)

    private val faces = HashMap<String, Face>()
    private val glyphs = HashMap<String, Glyph>()
    private val splits = HashMap<String, Boolean>()
    private val paints = HashMap<FontSpec, TextPaint>()
    private val refPaints = HashMap<String, TextPaint>()
    private val clusters = BreakIterator.getCharacterInstance()

    /** Set [text] in [font]: the glyphs to show and where each sits, in content px from the start. */
    fun shape(text: String, font: FontSpec): Run {
        val paint = paintFor(font)
        val adv = FloatArray(text.length)
        paint.getTextWidths(text, adv)
        val face = faceFor(font)
        val out = ArrayList<Glyph>(text.length)
        val offsets = ArrayList<Double>(text.length)
        var x = 0.0
        var i = 0
        while (i < text.length) {
            if (isBlank(text[i])) {
                out += glyph(face, "b", text[i].toString()) { blankGlyph(refPaint(font), text[i].toString()) }
                offsets += x
                x += adv[i]
                i++
                continue
            }
            var j = i
            while (j < text.length && !isBlank(text[j])) j++
            setWord(font, face, paint, text, adv, i, j, x, out, offsets)
            for (k in i until j) x += adv[k]
            i = j
        }
        return Run(out, offsets.toDoubleArray())
    }

    /**
     * A glyph drawing [body] (glyph space, 1000 units per em, no colour of its own) that reads
     * as [text]: a list bullet or checkbox, which the screen paints as shapes. [key] names its
     * geometry, so every bullet of one size shares a glyph.
     */
    fun markGlyph(key: String, text: String, width: Double, bbox: DoubleArray, body: () -> String): Glyph {
        val face = faces.getOrPut(MARKS) { Face(MARKS, "Marks", MARK_ASCENT, MARK_DESCENT) }
        return glyph(face, "m", key) { GlyphDef(text, width, bbox, body(), image = null) }
    }

    /**
     * A formula as one glyph that reads as its LaTeX, `$…$` (display `$$…$$`), so copying an
     * equation pastes back into a note as one. Its drawing is the LaTeX library's own, captured as
     * outlines; when the capture cannot take all of it, the formula's bitmap stands in. Null when
     * the LaTeX does not set.
     */
    fun formulaGlyph(latex: String, sizePt: Double, color: Rgba, display: Boolean): Glyph? {
        val box = MathRendering.measure(latex, sizePt, display) ?: return null
        val sizePx = sizePt * AndroidText.POINTS_TO_PX
        val face = faces.getOrPut(MATHS) { Face(MATHS, "Math", MARK_ASCENT, MARK_DESCENT) }
        val key = "$display ${PdfNumbers.format(sizePx, 2)} ${color.toArgb()} $latex"
        return glyph(face, "f", key) {
            val text = if (display) "\$\$$latex\$\$" else "\$$latex\$"
            val k = 1000.0 / sizePx
            outlineFormula(latex, sizePt, color, display, box, k, text)
                ?: imageFormula(latex, sizePt, color, display, box, k, text)
                ?: GlyphDef(text, box.width * k, doubleArrayOf(0.0, 0.0, 0.0, 0.0), "", image = null)
        }
    }

    private fun outlineFormula(latex: String, sizePt: Double, color: Rgba, display: Boolean, box: MathBox, k: Double, text: String): GlyphDef? {
        val cap = OutlineCapture()
        if (!MathRendering.drawVector(cap, latex, sizePt, color, display) || cap.incomplete || cap.shapes.isEmpty()) return null
        val own = color.toArgb() and 0xFFFFFF
        val uniform = cap.shapes.all { (it.argb and 0xFFFFFF) == own }
        val body = StringBuilder(1024)
        val tolerance = (1000.0 / k).toFloat() * TOLERANCE_EM
        var last = 0
        var llx = Double.MAX_VALUE
        var lly = Double.MAX_VALUE
        var urx = -Double.MAX_VALUE
        var ury = -Double.MAX_VALUE
        val r = RectF()
        for ((i, shape) in cap.shapes.withIndex()) {
            val path = shape.path
            path.offset(0f, -box.ascent.toFloat())
            if (!uniform && (i == 0 || shape.argb != last)) {
                for (c in intArrayOf(shape.argb shr 16, shape.argb shr 8, shape.argb)) {
                    PdfNumbers.append(body, (c and 0xFF) / 255.0, 3)
                    body.append(' ')
                }
                body.append("rg\n")
                last = shape.argb
            }
            if (!PathOps.append(body, path, k, tolerance)) continue
            body.append(if (path.fillType == Path.FillType.EVEN_ODD) "f*\n" else "f\n")
            path.computeBounds(r, true)
            llx = min(llx, r.left * k)
            urx = max(urx, r.right * k)
            lly = min(lly, -r.bottom * k)
            ury = max(ury, -r.top * k)
        }
        if (urx < llx) return null
        return GlyphDef(text, box.width * k, doubleArrayOf(llx, lly, urx, ury), body.toString(), image = null, colored = !uniform)
    }

    private fun imageFormula(latex: String, sizePt: Double, color: Rgba, display: Boolean, box: MathBox, k: Double, text: String): GlyphDef? {
        val (bmp, _) = MathRendering.formulaBitmap(latex, sizePt, color, display) ?: return null
        val image = LosslessFactory.createFromImage(doc, bmp)
        val body = StringBuilder()
        body.append("q ")
        PdfNumbers.append(body, box.width * k, 2)
        body.append(" 0 0 ")
        PdfNumbers.append(body, box.height * k, 2)
        body.append(" 0 ")
        PdfNumbers.append(body, -box.descent * k, 2)
        body.append(" cm /").append(IMAGE_NAME).append(" Do Q\n")
        return GlyphDef(text, box.width * k, null, body.toString(), image)
    }

    /** Write every font's glyph programs, widths and character map. Call once, before saving. */
    fun finish() {
        for (face in faces.values) for (font in face.fonts) font.finish(doc)
    }

    // --- words and clusters ---

    private fun setWord(
        font: FontSpec,
        face: Face,
        paint: TextPaint,
        text: String,
        adv: FloatArray,
        from: Int,
        to: Int,
        x0: Double,
        out: MutableList<Glyph>,
        offsets: MutableList<Double>,
    ) {
        val word = text.substring(from, to)
        val bounds = clusterBounds(word)
        fun offsetOf(k: Int): Double {
            var x = x0
            for (m in from until from + k) x += adv[m]
            return x
        }
        if (bounds.size <= 2 || splitsCleanly(font, paint, word, bounds, adv, from)) {
            for (c in 0 until bounds.size - 1) {
                val cluster = word.substring(bounds[c], bounds[c + 1])
                out += clusterGlyph(font, face, cluster)
                offsets += offsetOf(bounds[c])
            }
            return
        }
        // Shaped as a whole: one glyph per stretch between colour clusters, which have no outline
        // to carry and go in as their own image glyphs.
        var c = 0
        while (c < bounds.size - 1) {
            val cluster = word.substring(bounds[c], bounds[c + 1])
            if (isColour(cluster)) {
                out += clusterGlyph(font, face, cluster)
                offsets += offsetOf(bounds[c])
                c++
                continue
            }
            var e = c + 1
            while (e < bounds.size - 1 && !isColour(word.substring(bounds[e], bounds[e + 1]))) e++
            var width = 0.0
            for (m in from + bounds[c] until from + bounds[e]) width += adv[m]
            out += wordGlyph(face, paint, word.substring(bounds[c], bounds[e]), width)
            offsets += offsetOf(bounds[c])
            c = e
        }
    }

    /** Boundaries of [word]'s grapheme clusters, 0 and its length included. */
    private fun clusterBounds(word: String): IntArray {
        clusters.setText(word)
        val out = ArrayList<Int>(word.length + 1)
        var b = clusters.first()
        while (b != BreakIterator.DONE) {
            out += b
            b = clusters.next()
        }
        return out.toIntArray()
    }

    /**
     * Whether [word] draws exactly as its clusters drawn one by one at their advances: the same
     * outlines in the same places. A ligature, a contextual alternate or Indic shaping changes the
     * outline, so comparing the two paths catches all of them without knowing any font's tables.
     */
    private fun splitsCleanly(font: FontSpec, paint: TextPaint, word: String, bounds: IntArray, adv: FloatArray, from: Int): Boolean =
        splits.getOrPut(styleKey(font) + '\u0000' + word) {
            val whole = Path()
            paint.getTextPath(word, 0, word.length, 0f, 0f, whole)
            val parts = Path()
            var x = 0f
            for (c in 0 until bounds.size - 1) {
                val part = Path()
                paint.getTextPath(word.substring(bounds[c], bounds[c + 1]), 0, bounds[c + 1] - bounds[c], x, 0f, part)
                parts.addPath(part)
                for (m in bounds[c] until bounds[c + 1]) x += adv[from + m]
            }
            val tolerance = paint.textSize * 0.002f
            sameOutline(whole.approximate(tolerance), parts.approximate(tolerance), paint.textSize * 0.01f)
        }

    private fun sameOutline(a: FloatArray, b: FloatArray, eps: Float): Boolean {
        if (a.size != b.size) return false
        var i = 0
        while (i < a.size) {
            if (abs(a[i + 1] - b[i + 1]) > eps || abs(a[i + 2] - b[i + 2]) > eps) return false
            i += 3
        }
        return true
    }

    // --- glyph capture ---

    private fun clusterGlyph(font: FontSpec, face: Face, cluster: String): Glyph =
        glyph(face, "c", cluster) {
            val ref = refPaint(font)
            if (isColour(cluster)) {
                imageGlyph(ref, cluster)
            } else {
                outlineGlyph(ref, cluster, REF_PX, ref.measureText(cluster).toDouble()) ?: imageGlyph(ref, cluster)
            }
        }

    private fun wordGlyph(face: Face, paint: TextPaint, word: String, widthPx: Double): Glyph {
        // Captured at the size it is shown at, so the glyphs inside it sit at the screen's own advances.
        val size = PdfNumbers.format(paint.textSize.toDouble(), 2)
        return glyph(face, "w$size", word) {
            val def = outlineGlyph(paint, word, paint.textSize, widthPx) ?: blankGlyph(paint, word, widthPx)
            GlyphDef(visualOrder(word), def.width, def.bbox, def.body, def.image)
        }
    }

    /**
     * What a right-to-left [word] must read as in the file. PDF text is stored in visual order
     * and readers reverse right-to-left runs back into typing order, so a word made of right-to-
     * left letters is written reversed. Anything with digits or left-to-right letters in it keeps
     * its order, since a reader would not reverse those parts.
     */
    private fun visualOrder(word: String): String {
        var rtl = false
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl = true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER,
                Character.DIRECTIONALITY_ARABIC_NUMBER,
                -> return word
            }
            i += Character.charCount(cp)
        }
        if (!rtl) return word
        val cps = word.codePoints().toArray()
        cps.reverse()
        return String(cps, 0, cps.size)
    }

    private inline fun glyph(face: Face, kind: String, text: String, make: () -> GlyphDef): Glyph =
        glyphs.getOrPut(face.key + '\u0000' + kind + '\u0000' + text) { face.add(make()) }

    /**
     * [text]'s outline in glyph space (1000 units per em, y up), captured from [paint] whose
     * text size is [sizePx]. Null when there is no outline to take (a bitmap glyph).
     */
    private fun outlineGlyph(paint: Paint, text: String, sizePx: Float, advancePx: Double): GlyphDef? {
        val path = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        if (path.isEmpty) return null
        val k = 1000.0 / sizePx
        val body = StringBuilder(256)
        if (!PathOps.append(body, path, k, sizePx * TOLERANCE_EM)) return null
        body.append(if (path.fillType == Path.FillType.EVEN_ODD) "f*\n" else "f\n")
        val r = RectF()
        path.computeBounds(r, true)
        val bbox = doubleArrayOf(r.left * k, -r.bottom * k, r.right * k, -r.top * k)
        return GlyphDef(text, advancePx * k, bbox, body.toString(), image = null)
    }

    /** A colour glyph: [text] rendered into an image, placed on the baseline like the glyph it is. */
    private fun imageGlyph(ref: Paint, text: String): GlyphDef {
        val p = Paint(ref).apply {
            textSize = IMAGE_PX
            color = 0xFF000000.toInt()
        }
        val fm = p.fontMetrics
        val advance = p.measureText(text)
        val w = ceil(advance).toInt().coerceAtLeast(1)
        val h = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 0f, -fm.ascent, p)
        val image = LosslessFactory.createFromImage(doc, bmp)
        bmp.recycle()
        val k = 1000.0 / IMAGE_PX
        val body = StringBuilder()
        body.append("q ")
        PdfNumbers.append(body, w * k, 2)
        body.append(" 0 0 ")
        PdfNumbers.append(body, h * k, 2)
        body.append(" 0 ")
        PdfNumbers.append(body, -fm.descent * k, 2)
        body.append(" cm /").append(IMAGE_NAME).append(" Do Q\n")
        return GlyphDef(text, advance * k, null, body.toString(), image)
    }

    /** A glyph that draws nothing but still reads as [text] and keeps its width: a space. */
    private fun blankGlyph(ref: Paint, text: String, advancePx: Double = ref.measureText(text).toDouble()): GlyphDef =
        GlyphDef(text, advancePx * 1000.0 / ref.textSize, doubleArrayOf(0.0, 0.0, 0.0, 0.0), "", image = null)

    // --- fonts and faces ---

    private fun styleKey(font: FontSpec): String = "${font.face.id}\u0000${font.bold}\u0000${font.italic}"

    private fun paintFor(font: FontSpec): TextPaint = paints.getOrPut(font) { AndroidText.textPaint(font) }

    private fun refPaint(font: FontSpec): TextPaint = refPaints.getOrPut(styleKey(font)) {
        AndroidText.textPaint(font).apply { textSize = REF_PX }
    }

    private fun faceFor(font: FontSpec): Face = faces.getOrPut(styleKey(font)) {
        val fm = refPaint(font).fontMetrics
        val style = when {
            font.bold && font.italic -> "BoldItalic"
            font.bold -> "Bold"
            font.italic -> "Italic"
            else -> "Regular"
        }
        val family = FontCatalog.label(font.face).filter { it.isLetterOrDigit() }.ifEmpty { "Font" }
        Face(styleKey(font), "$family-$style", -fm.ascent.toDouble(), fm.descent.toDouble())
    }

    /** One style's glyphs, spread over as many 255-glyph fonts as it needs. */
    private class Face(val key: String, private val name: String, private val ascent: Double, private val descent: Double) {
        val fonts = mutableListOf<Type3Font>()

        fun add(def: GlyphDef): Glyph {
            var font = fonts.lastOrNull()
            if (font == null || font.isFull) {
                font = Type3Font(if (fonts.isEmpty()) name else "${name}_${fonts.size + 1}", ascent, descent)
                fonts += font
            }
            return Glyph(font, font.add(def), def.width)
        }
    }

    companion object {
        /** Clusters are captured at this text size, where 1 px is one glyph-space unit. */
        const val REF_PX = 1000f

        /** Colour glyphs are rendered at this text size, about the resolution of the emoji strike. */
        private const val IMAGE_PX = 136f

        /** Curve flattening tolerance where no curve iterator exists, as a fraction of the em. */
        private const val TOLERANCE_EM = 0.0005f

        const val IMAGE_NAME = "Im"

        /** The faces list markers and formulas live in; no font id can collide with them. */
        private const val MARKS = "\u0000marks"
        private const val MATHS = "\u0000maths"

        // A marker font's nominal line metrics, in glyph units, for readers sizing a selection.
        private const val MARK_ASCENT = 800.0
        private const val MARK_DESCENT = 200.0

        private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t' || c == ' ' || Character.isWhitespace(c)

        /** Whether [cluster] is set as a colour emoji rather than an outline. */
        fun isColour(cluster: String): Boolean {
            var i = 0
            while (i < cluster.length) {
                val cp = cluster.codePointAt(i)
                if (cp == 0xFE0F || cp in 0x1F1E6..0x1F1FF || emojiPresentation(cp)) return true
                i += Character.charCount(cp)
            }
            return false
        }

        // The ICU property arrives with API 28; before that the pictograph blocks cover nearly all.
        private fun emojiPresentation(cp: Int): Boolean =
            if (Build.VERSION.SDK_INT >= 28) {
                UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)
            } else {
                cp in 0x1F300..0x1FAFF
            }
    }
}

/**
 * What one glyph is before its font is written: the text it reads as, its advance and box in
 * glyph units, and the drawing after its `d0`/`d1` line. A [colored] glyph sets its own colours
 * (an image, a formula in several colours); any other takes the colour the text is shown in.
 */
internal class GlyphDef(
    val text: String,
    val width: Double,
    val bbox: DoubleArray?,
    val body: String,
    val image: PDImageXObject?,
    val colored: Boolean = image != null,
)

/**
 * One Type 3 font of the export: up to 255 glyphs under codes 1..255, glyph space at 1000 units
 * per em. The dictionary exists from the start so pages can name it in their resources; its glyph
 * programs, widths and character map are filled in by [finish], just before the file is saved.
 */
internal class Type3Font(private val name: String, private val ascent: Double, private val descent: Double) {
    private val dict = COSDictionary()
    private val differences = COSArray()
    private val defs = mutableListOf<GlyphDef>()

    val pd: PDType3Font

    init {
        dict.setItem(COSName.TYPE, COSName.FONT)
        dict.setItem(COSName.SUBTYPE, COSName.TYPE3)
        differences.add(COSInteger.ONE)
        dict.setItem(
            COSName.ENCODING,
            COSDictionary().apply {
                setItem(COSName.TYPE, COSName.ENCODING)
                setItem(COSName.DIFFERENCES, differences)
            },
        )
        dict.setItem(COSName.FONT_MATRIX, numbers(0.001, 0.0, 0.0, 0.001, 0.0, 0.0))
        dict.setItem(COSName.FONT_BBOX, numbers(0.0, 0.0, 0.0, 0.0))
        pd = PDType3Font(dict)
    }

    val isFull: Boolean get() = defs.size >= MAX_GLYPHS

    /** Add [def] and return its code. */
    fun add(def: GlyphDef): Int {
        defs += def
        return defs.size
    }

    fun finish(doc: PDDocument) {
        if (defs.isEmpty()) return
        val procs = COSDictionary()
        val widths = COSArray()
        val images = COSDictionary()
        var llx = Double.MAX_VALUE
        var lly = -descent
        var urx = -Double.MAX_VALUE
        var ury = ascent
        for ((i, def) in defs.withIndex()) {
            val code = i + 1
            val glyphName = "g$code"
            differences.add(COSName.getPDFName(glyphName))
            val proc = StringBuilder(def.body.length + 48)
            PdfNumbers.append(proc, def.width, 2)
            if (def.colored) {
                proc.append(" 0 d0\n")
                if (def.image != null) {
                    images.setItem(COSName.getPDFName("${PdfText.IMAGE_NAME}$code"), def.image)
                    proc.append(def.body.replace("/${PdfText.IMAGE_NAME} Do", "/${PdfText.IMAGE_NAME}$code Do"))
                } else {
                    proc.append(def.body)
                }
            } else {
                val b = def.bbox ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
                proc.append(" 0")
                for (v in b) {
                    proc.append(' ')
                    PdfNumbers.append(proc, v, 1)
                }
                proc.append(" d1\n").append(def.body)
            }
            procs.setItem(glyphName, stream(doc, proc.toString()))
            widths.add(COSFloat(PdfNumbers.format(def.width, 2)))
            val b = def.bbox
            if (b != null && b[2] > b[0]) {
                llx = min(llx, b[0])
                lly = min(lly, b[1])
                urx = max(urx, b[2])
                ury = max(ury, b[3])
            } else {
                llx = min(llx, 0.0)
                urx = max(urx, def.width)
            }
        }
        val bbox = numbers(llx, lly, urx, ury)
        dict.setItem(COSName.CHAR_PROCS, procs)
        dict.setInt(COSName.FIRST_CHAR, 1)
        dict.setInt(COSName.LAST_CHAR, defs.size)
        dict.setItem(COSName.WIDTHS, widths)
        dict.setItem(COSName.FONT_BBOX, bbox)
        if (images.size() > 0) dict.setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.XOBJECT, images) })
        dict.setItem(COSName.TO_UNICODE, stream(doc, ToUnicode.cmap(defs.mapIndexed { i, d -> (i + 1) to d.text })))
        dict.setItem(
            COSName.FONT_DESC,
            COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT_DESC)
                setName(COSName.FONT_NAME, name)
                setInt(COSName.FLAGS, SYMBOLIC)
                setInt(COSName.ITALIC_ANGLE, 0)
                setItem(COSName.ASCENT, COSFloat(PdfNumbers.format(ascent, 1)))
                setItem(COSName.DESCENT, COSFloat(PdfNumbers.format(-descent, 1)))
                setItem(COSName.FONT_BBOX, bbox)
            },
        )
    }

    private fun stream(doc: PDDocument, text: String): COSStream {
        val s = doc.document.createCOSStream()
        s.createOutputStream(COSName.FLATE_DECODE).use { it.write(text.toByteArray(Charsets.ISO_8859_1)) }
        return s
    }

    private fun numbers(vararg v: Double): COSArray = COSArray().apply { for (x in v) add(COSFloat(PdfNumbers.format(x, 3))) }

    companion object {
        const val MAX_GLYPHS = 255

        /** FontDescriptor flag: the glyphs fall outside the standard Latin set (true of a custom encoding). */
        private const val SYMBOLIC = 4
    }
}

/** Android [Path]s as PDF path operators, scaled into glyph space with y turned up. */
internal object PathOps {

    /**
     * Append [path] scaled by [k] with y negated. Curves come through exactly where Android can
     * iterate them (API 34+); earlier it only offers a flattened outline, taken within [tolerance]
     * px, which is still far below anything a reader could see. False when nothing was written.
     */
    fun append(sb: StringBuilder, path: Path, k: Double, tolerance: Float): Boolean =
        if (Build.VERSION.SDK_INT >= 34) iterate(sb, path, k) else flatten(sb, path, k, tolerance)

    private fun pt(sb: StringBuilder, x: Float, y: Float, k: Double) {
        PdfNumbers.append(sb, x * k, 1)
        sb.append(' ')
        PdfNumbers.append(sb, -y * k, 1)
        sb.append(' ')
    }

    @androidx.annotation.RequiresApi(34)
    private fun iterate(sb: StringBuilder, path: Path, k: Double): Boolean {
        val it = path.pathIterator
        val p = FloatArray(8)
        var wrote = false
        while (it.hasNext()) {
            when (it.next(p, 0)) {
                PathIterator.VERB_MOVE -> {
                    pt(sb, p[0], p[1], k)
                    sb.append("m\n")
                }
                PathIterator.VERB_LINE -> {
                    pt(sb, p[2], p[3], k)
                    sb.append("l\n")
                    wrote = true
                }
                PathIterator.VERB_QUAD -> {
                    // A quadratic is the cubic whose controls sit two thirds of the way to its own.
                    pt(sb, p[0] + (p[2] - p[0]) * 2f / 3f, p[1] + (p[3] - p[1]) * 2f / 3f, k)
                    pt(sb, p[4] + (p[2] - p[4]) * 2f / 3f, p[5] + (p[3] - p[5]) * 2f / 3f, k)
                    pt(sb, p[4], p[5], k)
                    sb.append("c\n")
                    wrote = true
                }
                PathIterator.VERB_CONIC -> {
                    val w = p[6]
                    val t = 4f * w / (3f * (1f + w))
                    pt(sb, p[0] + (p[2] - p[0]) * t, p[1] + (p[3] - p[1]) * t, k)
                    pt(sb, p[4] + (p[2] - p[4]) * t, p[5] + (p[3] - p[5]) * t, k)
                    pt(sb, p[4], p[5], k)
                    sb.append("c\n")
                    wrote = true
                }
                PathIterator.VERB_CUBIC -> {
                    pt(sb, p[2], p[3], k)
                    pt(sb, p[4], p[5], k)
                    pt(sb, p[6], p[7], k)
                    sb.append("c\n")
                    wrote = true
                }
                PathIterator.VERB_CLOSE -> sb.append("h\n")
                PathIterator.VERB_DONE -> break
            }
        }
        return wrote
    }

    private fun flatten(sb: StringBuilder, path: Path, k: Double, tolerance: Float): Boolean {
        val a = path.approximate(tolerance)
        if (a.size < 6) return false
        // Triples of (fraction, x, y); a repeated fraction marks a move to a new contour.
        var i = 0
        while (i < a.size) {
            val newContour = i == 0 || a[i] == a[i - 3]
            if (newContour) {
                if (i > 0) sb.append("h\n")
                pt(sb, a[i + 1], a[i + 2], k)
                sb.append("m\n")
            } else {
                pt(sb, a[i + 1], a[i + 2], k)
                sb.append("l\n")
            }
            i += 3
        }
        sb.append("h\n")
        return true
    }
}
