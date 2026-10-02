package com.xnotes.platform

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlin.math.roundToInt

/**
 * What one PDF export shares across its pages, so a resource every page uses is written into the
 * file once rather than once per page. Each page's [PdfBoxRenderer] draws through the same context.
 */
internal class PdfExportContext(val doc: PDDocument) {

    /** The export's text fonts, filled as pages set text and written out by [finish]. */
    val text = PdfText(doc)

    /** The document's logical structure when the export is tagged; set before the first page. */
    var tags: PdfTags? = null

    private val states = HashMap<Pair<Int, BlendMode?>, PDExtendedGraphicsState>()

    /** Complete everything built up while drawing. Call once, after the last page and before saving. */
    fun finish() {
        text.finish()
        tags?.finish()
    }

    /**
     * A graphics state setting fill and stroke opacity to [alpha], and the blend to [blend] when
     * given. One object per distinct value: PdfBox reuses a resource name only for the same object.
     */
    fun graphicsState(alpha: Double, blend: BlendMode? = null): PDExtendedGraphicsState {
        val a = (alpha.coerceIn(0.0, 1.0) * 1000).roundToInt()
        return states.getOrPut(a to blend) {
            PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = a / 1000f
                strokingAlphaConstant = a / 1000f
                if (blend != null) blendMode = blend
            }
        }
    }
}
