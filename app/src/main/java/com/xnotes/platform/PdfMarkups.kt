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

    /** Put [markups] on [pd], bottom first, where [frame] puts its page as displayed. */
    fun write(ctx: PdfExportContext, pd: PDPage, markups: List<TextMarkup>, frame: PageFrame) {
        if (markups.isEmpty()) return
        val annots = pd.annotations
        for (m in markups) {
            val a = MarkupAnnotation.of(m, frame::pointToUser) ?: continue
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
