package com.xnotes.core.pdf

import com.xnotes.core.geometry.Pt

/**
 * How a PDF page's user space maps onto its points as displayed (top-left origin, /Rotate applied):
 * x' = a x + c y + e, y' = b x + d y + f, the map PDFium renders with. Its inverse places what is
 * drawn on the displayed page back in the PDF's own coordinates, as annotations need them.
 */
class PdfPageGeometry(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {

    fun toDisplay(x: Double, y: Double): Pt = Pt(a * x + c * y + e, b * x + d * y + f)

    fun toUser(x: Double, y: Double): Pt {
        val det = a * d - b * c
        val dx = x - e
        val dy = y - f
        return Pt((d * dx - c * dy) / det, (a * dy - b * dx) / det)
    }

    companion object {
        /** A page box (left, bottom, right, top) turned [rotation] degrees clockwise, as viewers show it. */
        fun of(left: Double, bottom: Double, right: Double, top: Double, rotation: Int): PdfPageGeometry =
            when (((rotation % 360) + 360) % 360) {
                90 -> PdfPageGeometry(0.0, 1.0, 1.0, 0.0, -bottom, -left)
                180 -> PdfPageGeometry(-1.0, 0.0, 0.0, 1.0, right, -bottom)
                270 -> PdfPageGeometry(0.0, -1.0, -1.0, 0.0, top, right)
                else -> PdfPageGeometry(1.0, 0.0, 0.0, -1.0, -left, top)
            }

        /** A page of no PDF, [height] points tall: its own points with the y axis turned up. */
        fun flipped(height: Double): PdfPageGeometry = PdfPageGeometry(1.0, 0.0, 0.0, -1.0, 0.0, height)
    }
}
