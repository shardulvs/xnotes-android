package com.xnotes.platform

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pdf.MarkupAnnotation
import com.xnotes.core.pdf.PageFrame
import java.util.Calendar
import java.util.TimeZone

/**
 * Writes a page's text markups into an export as text markup annotations, which other viewers
 * list, open and edit. Each draws through its own appearance stream ([MarkupAnnotation]), so it
 * looks as it does in xnotes; a note goes in /Contents, with no popup and no author.
 */
internal object PdfMarkups {

    /**
     * One tile of a page room was inserted into, in user space: the page as it was shows through
     * [clip] moved by [shift], the clip itself where it was before the move.
     */
    class Tile(val clip: com.xnotes.core.geometry.Rect, val shift: com.xnotes.core.geometry.Pt)

    /**
     * Put [markups] on [pd], bottom first, where [frame] puts its page as displayed. On a page room
     * was inserted into, [shown] gives each markup as it shows there and [tiles] the bands it is drawn
     * through, so it is cut exactly as the PDF under it is.
     */
    fun write(
        ctx: PdfExportContext,
        pd: PDPage,
        markups: List<TextMarkup>,
        frame: PageFrame,
        shown: (TextMarkup) -> TextMarkup = { it },
        tiles: List<Tile> = emptyList(),
    ) {
        if (markups.isEmpty()) return
        val annots = pd.annotations
        for (m in markups) {
            val a = (if (tiles.isEmpty()) MarkupAnnotation.of(m, frame::pointToUser) else banded(m, shown(m), frame, tiles)) ?: continue
            val rect = PDRectangle(a.rect.left.toFloat(), a.rect.top.toFloat(), a.rect.w.toFloat(), a.rect.h.toFloat())
            val annot = PDAnnotationTextMarkup(subtypeOf(m.type))
            annot.rectangle = rect
            annot.quadPoints = a.quadPoints
            annot.color = PDColor(floatArrayOf(m.color.r / 255f, m.color.g / 255f, m.color.b / 255f), PDDeviceRGB.INSTANCE)
            if (m.type == MarkupType.HIGHLIGHT) annot.constantOpacity = m.intensity.toFloat()
            m.note?.let { annot.contents = it }
            annot.annotationName = m.id
            annot.creationDate = utc(m.created)
            annot.setModifiedDate(utc(m.modified))
            annot.isPrinted = true
            annot.page = pd
            annot.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance(ctx, m, a, rect)) }
            annots.add(annot)
            ctx.tags?.markup(pd, annot.cosObject, m.note ?: m.text)
        }
        pd.annotations = annots
    }

    /**
     * [m] on a page with room: the quads it shows at ([shown]) for viewers to read, and an appearance
     * that draws it on the page as it was through each tile, so its lines land where the app shows them.
     */
    private fun banded(m: TextMarkup, shown: TextMarkup, frame: PageFrame, tiles: List<Tile>): MarkupAnnotation? {
        val whole = MarkupAnnotation.of(m, frame::pointToUser) ?: return null
        val quads = MarkupAnnotation.of(shown, frame::pointToUser) ?: return null
        val sb = StringBuilder()
        var rect: com.xnotes.core.geometry.Rect? = null
        for (t in tiles) {
            val l = maxOf(whole.rect.left, t.clip.left)
            val b = maxOf(whole.rect.top, t.clip.top)
            val r = minOf(whole.rect.right, t.clip.right)
            val tp = minOf(whole.rect.bottom, t.clip.bottom)
            if (r <= l || tp <= b) continue
            val piece = com.xnotes.core.geometry.Rect(l + t.shift.x, b + t.shift.y, r - l, tp - b)
            rect = rect?.union(piece) ?: piece
            sb.append("q ")
            for (v in doubleArrayOf(piece.left, piece.top, piece.w, piece.h)) sb.append(com.xnotes.core.pdf.PdfNumbers.format(v)).append(' ')
            sb.append("re W n 1 0 0 1 ")
            sb.append(com.xnotes.core.pdf.PdfNumbers.format(t.shift.x)).append(' ').append(com.xnotes.core.pdf.PdfNumbers.format(t.shift.y))
            sb.append(" cm\n").append(whole.content).append("Q\n")
        }
        return MarkupAnnotation(quads.quadPoints, rect ?: return null, sb.toString())
    }

    /** [a]'s appearance stream, in user space over [rect]. */
    private fun appearance(ctx: PdfExportContext, m: TextMarkup, a: MarkupAnnotation, rect: PDRectangle): PDAppearanceStream {
        val ap = PDAppearanceStream(ctx.doc)
        ap.bBox = rect
        ap.resources = PDResources().apply {
            if (m.type == MarkupType.HIGHLIGHT) put(COSName.getPDFName(MarkupAnnotation.GS), ctx.graphicsState(m.intensity, BlendMode.MULTIPLY))
        }
        ap.contentStream.createOutputStream(COSName.FLATE_DECODE).use { it.write(a.content.toByteArray(Charsets.US_ASCII)) }
        return ap
    }

    private fun subtypeOf(type: MarkupType): String = when (type) {
        MarkupType.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
        MarkupType.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
        MarkupType.STRIKEOUT -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
        MarkupType.SQUIGGLY -> PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY
    }

    private fun utc(ms: Long): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
}
